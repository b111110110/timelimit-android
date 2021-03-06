/*
 * TimeLimit Copyright <C> 2019 - 2021 Jonas Lochmann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package io.timelimit.android.logic

import android.content.pm.PackageManager
import android.util.Log
import androidx.lifecycle.MutableLiveData
import io.timelimit.android.BuildConfig
import io.timelimit.android.R
import io.timelimit.android.async.Threads
import io.timelimit.android.coroutines.executeAndWait
import io.timelimit.android.coroutines.runAsync
import io.timelimit.android.coroutines.runAsyncExpectForever
import io.timelimit.android.data.backup.DatabaseBackup
import io.timelimit.android.data.model.CategoryTimeWarnings
import io.timelimit.android.data.model.ExperimentalFlags
import io.timelimit.android.data.model.UserType
import io.timelimit.android.data.model.derived.UserRelatedData
import io.timelimit.android.date.DateInTimezone
import io.timelimit.android.date.getMinuteOfWeek
import io.timelimit.android.extensions.MinuteOfDay
import io.timelimit.android.extensions.flattenToSet
import io.timelimit.android.extensions.nextBlockedMinuteOfWeek
import io.timelimit.android.integration.platform.AppStatusMessage
import io.timelimit.android.integration.platform.NetworkId
import io.timelimit.android.integration.platform.ProtectionLevel
import io.timelimit.android.integration.platform.android.AccessibilityService
import io.timelimit.android.livedata.*
import io.timelimit.android.logic.blockingreason.AppBaseHandling
import io.timelimit.android.logic.blockingreason.CategoryHandlingCache
import io.timelimit.android.logic.blockingreason.CategoryItselfHandling
import io.timelimit.android.logic.blockingreason.needsNetworkId
import io.timelimit.android.sync.actions.ForceSyncAction
import io.timelimit.android.sync.actions.UpdateDeviceStatusAction
import io.timelimit.android.sync.actions.apply.ApplyActionUtil
import io.timelimit.android.ui.IsAppInForeground
import io.timelimit.android.ui.lock.LockActivity
import io.timelimit.android.util.AndroidVersion
import io.timelimit.android.util.TimeTextUtil
import io.timelimit.android.work.PeriodicSyncInBackgroundWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*

class BackgroundTaskLogic(val appLogic: AppLogic) {
    var pauseForegroundAppBackgroundLoop = false
    val lastLoopException = MutableLiveData<Exception?>().apply { value = null }
    private var slowMainLoop = false

    companion object {
        private const val LOG_TAG = "BackgroundTaskLogic"

        private const val CHECK_PERMISSION_INTERVAL = 10 * 1000L        // all 10 seconds

        private const val BACKGROUND_SERVICE_INTERVAL_SHORT = 100L      // all 100 ms
        private const val MAX_USED_TIME_PER_ROUND_SHORT = 1000          // 1 second
        private const val BACKGROUND_SERVICE_INTERVAL_LONG = 1000L      // every second
        private const val MAX_USED_TIME_PER_ROUND_LONG = 2000           // 1 second
        const val EXTEND_SESSION_TOLERANCE = 5 * 1000L                  // 5 seconds
    }

    init {
        runAsyncExpectForever { backgroundServiceLoop() }
        runAsyncExpectForever { syncDeviceStatusLoop() }
        runAsyncExpectForever { backupDatabaseLoop() }
        runAsyncExpectForever { annoyUserOnManipulationLoop() }
        runAsync {
            // this is effective after an reboot

            if (appLogic.deviceEntryIfEnabled.waitForNullableValue() != null) {
                appLogic.platformIntegration.setEnableSystemLockdown(true)
            } else {
                appLogic.platformIntegration.setEnableSystemLockdown(false)
            }
        }

        appLogic.deviceEntryIfEnabled
                .map { it?.id }
                .ignoreUnchanged()
                .observeForever {
                    _ ->

                    runAsync {
                        syncInstalledAppVersion()
                    }
                }

        appLogic.database.config().getEnableBackgroundSyncAsync().ignoreUnchanged().observeForever {
            if (it) {
                PeriodicSyncInBackgroundWorker.enable()
            } else {
                PeriodicSyncInBackgroundWorker.disable()
            }
        }

        appLogic.database.config().isExperimentalFlagsSetAsync(ExperimentalFlags.CUSTOM_HOME_SCREEN).observeForever {
            appLogic.platformIntegration.setEnableCustomHomescreen(it)
        }

        appLogic.database.config().isExperimentalFlagsSetAsync(ExperimentalFlags.NETWORKTIME_AT_SYSTEMLEVEL).observeForever {
            appLogic.platformIntegration.setForceNetworkTime(it)
        }

        appLogic.database.config().isExperimentalFlagsSetAsync(ExperimentalFlags.HIGH_MAIN_LOOP_DELAY).observeForever {
            slowMainLoop = it
        }
    }

    private val usedTimeUpdateHelper = UsedTimeUpdateHelper(appLogic)
    private var previousMainLogicExecutionTime = 0
    private var previousMainLoopEndTime = 0L
    private var previousAudioPlaybackBlock: Pair<Long, String>? = null
    private var previousMinuteOfWeek = -1
    private val dayChangeTracker = DayChangeTracker(
            timeApi = appLogic.timeApi,
            longDuration = 1000 * 60 * 10 /* 10 minutes */
    )
    private val undisturbedCategoryUsageCounter = UndisturbedCategoryUsageCounter()

    private val appTitleCache = QueryAppTitleCache(appLogic.platformIntegration)
    private val categoryHandlingCache = CategoryHandlingCache()

    private val isChromeOs = appLogic.context.packageManager.hasSystemFeature(PackageManager.FEATURE_PC)

    private suspend fun openLockscreen(blockedAppPackageName: String, blockedAppActivityName: String?, enableSoftBlocking: Boolean) {
        if (enableSoftBlocking) {
            appLogic.platformIntegration.setShowBlockingOverlay(false)
        } else {
            appLogic.platformIntegration.setShowBlockingOverlay(true, "$blockedAppPackageName:${blockedAppActivityName?.removePrefix(blockedAppPackageName)}")
        }

        if (isChromeOs) {
            LockActivity.currentInstances.forEach { it.finish() }

            var i = 0

            while (LockActivity.currentInstances.isNotEmpty() && i < 2000) {
                delay(10)
                i += 10
            }
        }

        if (appLogic.platformIntegration.isAccessibilityServiceEnabled() && !enableSoftBlocking) {
            if (blockedAppPackageName != appLogic.platformIntegration.getLauncherAppPackageName()) {
                AccessibilityService.instance?.showHomescreen()
                delay(100)
                AccessibilityService.instance?.showHomescreen()
                delay(100)
            }
        }

        appLogic.platformIntegration.showAppLockScreen(blockedAppPackageName, blockedAppActivityName)
    }

    private var showNotificationToRevokeTemporarilyAllowedApps: Boolean? = null

    private fun setShowNotificationToRevokeTemporarilyAllowedApps(show: Boolean) {
        if (showNotificationToRevokeTemporarilyAllowedApps != show) {
            showNotificationToRevokeTemporarilyAllowedApps = show
            appLogic.platformIntegration.setShowNotificationToRevokeTemporarilyAllowedApps(show)
        }
    }

    private suspend fun commitUsedTimeUpdaters() {
        usedTimeUpdateHelper.flush()
    }

    private suspend fun backgroundServiceLoop() {
        val realTime = RealTime.newInstance()

        while (true) {
            val backgroundServiceInterval = when (slowMainLoop) {
                true -> BACKGROUND_SERVICE_INTERVAL_LONG
                false -> BACKGROUND_SERVICE_INTERVAL_SHORT
            }

            val maxUsedTimeToAdd = when (slowMainLoop) {
                true -> MAX_USED_TIME_PER_ROUND_LONG
                false -> MAX_USED_TIME_PER_ROUND_SHORT
            }

            // app must be enabled
            if (!appLogic.enable.waitForNonNullValue()) {
                commitUsedTimeUpdaters()
                undisturbedCategoryUsageCounter.reset()
                appLogic.platformIntegration.setAppStatusMessage(null)
                appLogic.platformIntegration.setShowBlockingOverlay(false)
                setShowNotificationToRevokeTemporarilyAllowedApps(false)
                appLogic.enable.waitUntilValueMatches { it == true }

                continue
            }

            val deviceAndUSerRelatedData = Threads.database.executeAndWait {
                appLogic.database.derivedDataDao().getUserAndDeviceRelatedDataSync()
            }

            val deviceRelatedData = deviceAndUSerRelatedData?.deviceRelatedData
            val userRelatedData = deviceAndUSerRelatedData?.userRelatedData
            val showSyncRelatedNotifications = deviceAndUSerRelatedData?.deviceRelatedData?.isExperimentalFlagSetSync(ExperimentalFlags.SYNC_RELATED_NOTIFICATIONS) ?: false

            setShowNotificationToRevokeTemporarilyAllowedApps(deviceRelatedData?.temporarilyAllowedApps?.isNotEmpty() ?: false)

            // device must be used by a child
            if (deviceRelatedData == null || userRelatedData == null || userRelatedData.user.type != UserType.Child) {
                commitUsedTimeUpdaters()
                undisturbedCategoryUsageCounter.reset()

                val shouldDoAutomaticSignOut = deviceRelatedData != null && DefaultUserLogic.hasAutomaticSignOut(deviceRelatedData) && deviceRelatedData.canSwitchToDefaultUser

                if (shouldDoAutomaticSignOut) {
                    appLogic.defaultUserLogic.reportScreenOn(appLogic.platformIntegration.isScreenOn())

                    appLogic.platformIntegration.setAppStatusMessage(
                            AppStatusMessage(
                                    title = appLogic.context.getString(R.string.background_logic_timeout_title),
                                    text = appLogic.context.getString(R.string.background_logic_timeout_text),
                                    showSwitchToDefaultUserOption = true
                            )
                    )
                    appLogic.platformIntegration.setShowBlockingOverlay(false)

                    appLogic.timeApi.sleep(backgroundServiceInterval)
                } else {
                    appLogic.platformIntegration.setAppStatusMessage(null)
                    appLogic.platformIntegration.setShowBlockingOverlay(false)

                    appLogic.timeApi.sleep(backgroundServiceInterval)
                }

                continue
            }

            // loop logic
            try {
                // get the current time
                appLogic.realTimeLogic.getRealTime(realTime)

                val nowTimestamp = realTime.timeInMillis
                val nowTimezone = TimeZone.getTimeZone(userRelatedData.user.timeZone)
                val nowUptime = appLogic.timeApi.getCurrentUptimeInMillis()

                val nowDate = DateInTimezone.getLocalDate(nowTimestamp, nowTimezone)
                val nowMinuteOfWeek = getMinuteOfWeek(nowTimestamp, nowTimezone)
                val lastMinuteOfWeek = previousMinuteOfWeek.let { if (it < 0) nowMinuteOfWeek else it }
                previousMinuteOfWeek = nowMinuteOfWeek
                val dayOfEpoch = nowDate.toEpochDay().toInt()

                // eventually remove old used time data
                if (realTime.shouldTrustTimePermanently) {
                    val dayChange = dayChangeTracker.reportDayChange(dayOfEpoch)

                    fun deleteOldUsedTimes() = UsedTimeDeleter.deleteOldUsedTimeItems(
                            database = appLogic.database,
                            date = DateInTimezone.newInstance(nowDate),
                            timestamp = nowTimestamp
                    )

                    if (realTime.isNetworkTime) {
                        if (dayChange == DayChangeTracker.DayChange.Now) {
                            deleteOldUsedTimes()
                        }
                    } else {
                        if (dayChange == DayChangeTracker.DayChange.NowSinceLongerTime) {
                            deleteOldUsedTimes()
                        }
                    }
                }

                // get the current status
                val isScreenOn = appLogic.platformIntegration.isScreenOn()
                val batteryStatus = appLogic.platformIntegration.getBatteryStatus()

                appLogic.defaultUserLogic.reportScreenOn(isScreenOn)

                if (!isScreenOn) {
                    if (deviceRelatedData.temporarilyAllowedApps.isNotEmpty()) {
                        resetTemporarilyAllowedApps()
                    }
                }

                val foregroundApps = appLogic.platformIntegration.getForegroundApps(
                        appLogic.getForegroundAppQueryInterval(),
                        appLogic.getEnableMultiAppDetection()
                )

                val audioPlaybackPackageName = appLogic.platformIntegration.getMusicPlaybackPackage()
                val activityLevelBlocking = appLogic.deviceEntry.value?.enableActivityLevelBlocking ?: false

                val foregroundAppWithBaseHandlings = foregroundApps.map { app ->
                    app to AppBaseHandling.calculate(
                            foregroundAppPackageName = app.packageName,
                            foregroundAppActivityName = app.activityName,
                            pauseForegroundAppBackgroundLoop = pauseForegroundAppBackgroundLoop,
                            userRelatedData = userRelatedData,
                            deviceRelatedData = deviceRelatedData,
                            pauseCounting = !isScreenOn,
                            isSystemImageApp = appLogic.platformIntegration.isSystemImageApp(app.packageName)
                    )
                }

                val backgroundAppBaseHandling = AppBaseHandling.calculate(
                        foregroundAppPackageName = audioPlaybackPackageName,
                        foregroundAppActivityName = null,
                        pauseForegroundAppBackgroundLoop = false,
                        userRelatedData = userRelatedData,
                        deviceRelatedData = deviceRelatedData,
                        pauseCounting = false,
                        isSystemImageApp = audioPlaybackPackageName?.let { appLogic.platformIntegration.isSystemImageApp(it) } ?: false
                )

                val allAppsBaseHandlings = foregroundAppWithBaseHandlings.map { it.second } + listOf(backgroundAppBaseHandling)
                val currentCategoryIds = allAppsBaseHandlings.map {
                    if (it is AppBaseHandling.UseCategories) it.categoryIds else emptySet()
                }.flattenToSet()

                undisturbedCategoryUsageCounter.report(nowUptime, currentCategoryIds)
                val recentlyStartedCategories = undisturbedCategoryUsageCounter.getRecentlyStartedCategories(nowUptime)

                val needsNetworkId = allAppsBaseHandlings.find { it.needsNetworkId() } != null
                val networkId: NetworkId? = if (needsNetworkId) appLogic.platformIntegration.getCurrentNetworkId() else null

                fun reportStatusToCategoryHandlingCache(userRelatedData: UserRelatedData) {
                    categoryHandlingCache.reportStatus(
                            user = userRelatedData,
                            timeInMillis = nowTimestamp,
                            shouldTrustTimeTemporarily = realTime.shouldTrustTimeTemporarily,
                            assumeCurrentDevice = CurrentDeviceLogic.handleDeviceAsCurrentDevice(deviceRelatedData, userRelatedData),
                            batteryStatus = batteryStatus,
                            currentNetworkId = networkId,
                            hasPremiumOrLocalMode = deviceRelatedData.isLocalMode || deviceRelatedData.isConnectedAndHasPremium
                    )
                }; reportStatusToCategoryHandlingCache(userRelatedData)

                // check if should be blocked
                val blockedForegroundApp = foregroundAppWithBaseHandlings.find { (_, foregroundAppBaseHandling) ->
                    foregroundAppBaseHandling is AppBaseHandling.BlockDueToNoCategory ||
                            (foregroundAppBaseHandling is AppBaseHandling.UseCategories && foregroundAppBaseHandling.categoryIds.find {
                                categoryHandlingCache.get(it).shouldBlockActivities
                            } != null)
                }?.first

                val blockAudioPlayback = backgroundAppBaseHandling is AppBaseHandling.BlockDueToNoCategory ||
                        (backgroundAppBaseHandling is AppBaseHandling.UseCategories && backgroundAppBaseHandling.categoryIds.find {
                            val handling = categoryHandlingCache.get(it)
                            val hasPremium = deviceRelatedData.isLocalMode || deviceRelatedData.isConnectedAndHasPremium
                            val blockAllNotifications = handling.blockAllNotifications is CategoryItselfHandling.BlockAllNotifications.Yes && hasPremium

                            handling.shouldBlockActivities || blockAllNotifications
                        } != null)

                // update times
                val timeToSubtract = Math.min(previousMainLogicExecutionTime, maxUsedTimeToAdd)

                val categoryHandlingsToCount = AppBaseHandling.getCategoriesForCounting(
                        foregroundAppWithBaseHandlings.map { it.second } + listOf(backgroundAppBaseHandling)
                )
                        .map { categoryHandlingCache.get(it) }
                        .filter { it.shouldCountTime }

                fun timeToSubtractForCategory(categoryId: String): Int {
                    return if (usedTimeUpdateHelper.getCountedCategoryIds().contains(categoryId)) usedTimeUpdateHelper.getCountedTime() else 0
                }

                val triggerSync = kotlin.run {
                    var triggerSyncByLimitLoginCategory = false
                    var triggerSyncByTimeOver = false

                    categoryHandlingsToCount.forEach { handling ->
                        val category = handling.createdWithCategoryRelatedData.category
                        val categoryId = category.id
                        val timeToSubtractForCategory = timeToSubtractForCategory(categoryId)
                        val isRecentlyStartedCategory = recentlyStartedCategories.contains(categoryId)
                        val nowRemaining = handling.remainingTime ?: return@forEach // category is not limited anymore

                        val oldRemainingTime = nowRemaining.includingExtraTime - timeToSubtractForCategory
                        val newRemainingTime = oldRemainingTime - timeToSubtract

                        val commitedSessionDuration = handling.remainingSessionDuration
                        val oldSessionDuration = handling.remainingSessionDuration?.let { it - timeToSubtractForCategory }

                        // trigger time warnings
                        fun showTimeWarningNotification(title: Int, roundedNewTime: Long) {
                            appLogic.platformIntegration.showTimeWarningNotification(
                                    title = appLogic.context.getString(title, category.title),
                                    text = TimeTextUtil.remaining(roundedNewTime.toInt(), appLogic.context)
                            )
                        }

                        if (oldRemainingTime / (1000 * 60) != newRemainingTime / (1000 * 60)) {
                            // eventually show remaining time warning
                            val roundedNewTime = ((newRemainingTime / (1000 * 60)) + 1) * (1000 * 60)
                            val flagIndex = CategoryTimeWarnings.durationToBitIndex[roundedNewTime]

                            if (flagIndex != null && category.timeWarnings and (1 shl flagIndex) != 0) {
                                showTimeWarningNotification(title = R.string.time_warning_not_title, roundedNewTime = roundedNewTime)
                            }
                        }

                        if (oldSessionDuration != null) {
                            val newSessionDuration = oldSessionDuration - timeToSubtract
                            // eventually show session duration warning
                            if (oldSessionDuration / (1000 * 60) != newSessionDuration / (1000 * 60)) {
                                val roundedNewTime = ((newSessionDuration / (1000 * 60)) + 1) * (1000 * 60)
                                val flagIndex = CategoryTimeWarnings.durationToBitIndex[roundedNewTime]

                                if (flagIndex != null && category.timeWarnings and (1 shl flagIndex) != 0) {
                                    showTimeWarningNotification(title = R.string.time_warning_not_title_session, roundedNewTime = roundedNewTime)
                                }
                            }
                        }

                        if (handling.okByBlockedTimeAreas && nowMinuteOfWeek != lastMinuteOfWeek) {
                            val nextBlockedMinute = handling.createdWithCategoryRelatedData.nextBlockedMinuteOfWeek(nowMinuteOfWeek) ?: run {
                                handling.createdWithCategoryRelatedData.nextBlockedMinuteOfWeek(0)?.let { MinuteOfDay.LENGTH * 7 + it }
                            }

                            if (BuildConfig.DEBUG) {
                                Log.d(LOG_TAG, "next blocked minute: $nextBlockedMinute (current: $nowMinuteOfWeek)")
                            }

                            if (nextBlockedMinute != null) {
                                val minutesUntilNextBlockedMinute = nextBlockedMinute - nowMinuteOfWeek
                                val msUntilNextBlocking = minutesUntilNextBlockedMinute.toLong() * 1000 * 60
                                val flagIndex = CategoryTimeWarnings.durationToBitIndex[msUntilNextBlocking]

                                if (flagIndex != null && category.timeWarnings and (1 shl flagIndex) != 0) {
                                    showTimeWarningNotification(title = R.string.time_warning_not_title_blocked_time_area, roundedNewTime = msUntilNextBlocking)
                                }
                            }
                        }

                        // check if sync triggered by time over
                        if (oldRemainingTime > 0 && newRemainingTime <= 0) {
                            triggerSyncByTimeOver = true
                        }

                        // check if sync triggered by session duration reached
                        if (oldSessionDuration != null) {
                            val newSessionDuration = oldSessionDuration - timeToSubtract

                            if (oldSessionDuration > 0 && newSessionDuration <= 0 && !isRecentlyStartedCategory) {
                                triggerSyncByTimeOver = true
                            }
                        }

                        // check if limit login triggered
                        val triggerSyncByLimitLoginCategoryForThisCategory = userRelatedData.preBlockSwitchPoints.let { switchPoints ->
                            if (switchPoints.isEmpty()) false else {
                                val limitLoginBySessionDuration = if (commitedSessionDuration != null && oldSessionDuration != null) {
                                    val newSessionDuration = oldSessionDuration - timeToSubtract

                                    switchPoints.find { switchPoint -> switchPoint in newSessionDuration until commitedSessionDuration } != null
                                } else false

                                val limitLoginByRemainingTime = switchPoints.find { switchPoint -> oldRemainingTime >= switchPoint && newRemainingTime < switchPoint } != null

                                (limitLoginBySessionDuration && !isRecentlyStartedCategory) || limitLoginByRemainingTime
                            }
                        }

                        triggerSyncByLimitLoginCategory = triggerSyncByLimitLoginCategory || triggerSyncByLimitLoginCategoryForThisCategory
                    }

                    if (BuildConfig.DEBUG) {
                        if (triggerSyncByTimeOver) {
                            Log.d(LOG_TAG, "trigger sync because the time is over")
                        }

                        if (triggerSyncByLimitLoginCategory) {
                            Log.d(LOG_TAG, "trigger sync by the limit login category")
                        }
                    }

                    if (showSyncRelatedNotifications) {
                        if (triggerSyncByTimeOver) {
                            appLogic.platformIntegration.showOverlayMessage("trigger sync because the time is over")
                        }

                        if (triggerSyncByLimitLoginCategory) {
                            appLogic.platformIntegration.showOverlayMessage("trigger sync by the limit login category")
                        }
                    }

                    val triggerSync = triggerSyncByLimitLoginCategory || triggerSyncByTimeOver

                    triggerSync
                }

                val didAutoCommitOfUsedTimes = usedTimeUpdateHelper.report(
                        duration = timeToSubtract,
                        dayOfEpoch = dayOfEpoch,
                        trustedTimestamp = if (realTime.shouldTrustTimePermanently) realTime.timeInMillis else 0,
                        handlings = categoryHandlingsToCount,
                        recentlyStartedCategories = recentlyStartedCategories
                )

                if (didAutoCommitOfUsedTimes) {
                    if (BuildConfig.DEBUG) {
                        Log.d(LOG_TAG, "auto commit used times")
                    }
                }

                // trigger sync when required
                if (triggerSync) {
                    if (BuildConfig.DEBUG) {
                        Log.d(LOG_TAG, "trigger sync")
                    }

                    if (showSyncRelatedNotifications) {
                        appLogic.platformIntegration.showOverlayMessage("sync forced")
                    }

                    usedTimeUpdateHelper.doCommit()

                    ApplyActionUtil.applyAppLogicAction(
                            action = ForceSyncAction,
                            appLogic = appLogic,
                            ignoreIfDeviceIsNotConfigured = true
                    )
                }

                if (didAutoCommitOfUsedTimes || triggerSync) {
                    val newDeviceAndUserRelatedData = Threads.database.executeAndWait {
                        appLogic.database.derivedDataDao().getUserAndDeviceRelatedDataSync()
                    }

                    if (
                            newDeviceAndUserRelatedData?.userRelatedData?.user?.id != deviceAndUSerRelatedData.userRelatedData.user.id ||
                            newDeviceAndUserRelatedData.userRelatedData.categoryById.keys != deviceAndUSerRelatedData.userRelatedData.categoryById.keys
                    ) {
                        if (BuildConfig.DEBUG) {
                            Log.d(LOG_TAG, "restart the loop")
                        }

                        // start the loop directly again
                        continue
                    }

                    reportStatusToCategoryHandlingCache(userRelatedData = newDeviceAndUserRelatedData.userRelatedData)
                }

                // show notification
                fun buildStatusMessageWithCurrentAppTitle(
                        text: String,
                        titlePrefix: String = "",
                        titleSuffix: String = "",
                        appPackageName: String?,
                        appActivityToShow: String?
                ) = AppStatusMessage(
                        title = titlePrefix + appTitleCache.query(appPackageName ?: "invalid") + titleSuffix,
                        text = text,
                        subtext = if (appActivityToShow != null && appPackageName != null) appActivityToShow.removePrefix(appPackageName) else null,
                        showSwitchToDefaultUserOption = deviceRelatedData.canSwitchToDefaultUser
                )

                fun getCategoryTitle(categoryId: String?): String = categoryId.let { userRelatedData.categoryById[it]?.category?.title } ?: categoryId.toString()

                fun buildNotificationForAppWithCategoryUsage(
                        suffix: String,
                        appPackageName: String?,
                        appActivityToShow: String?,
                        categoryId: String
                ): AppStatusMessage {
                    val handling = categoryHandlingCache.get(categoryId)

                    return if (handling.areLimitsTemporarilyDisabled) {
                        buildStatusMessageWithCurrentAppTitle(
                                text = appLogic.context.getString(R.string.background_logic_limits_disabled),
                                titleSuffix = suffix,
                                appPackageName = appPackageName,
                                appActivityToShow = appActivityToShow
                        )
                    } else if (handling.remainingTime == null) {
                        buildStatusMessageWithCurrentAppTitle(
                                text = appLogic.context.getString(R.string.background_logic_no_timelimit),
                                titlePrefix = getCategoryTitle(categoryId) + " - ",
                                titleSuffix = suffix,
                                appPackageName = appPackageName,
                                appActivityToShow = appActivityToShow
                        )
                    } else {
                        val remainingTimeFromCache = handling.remainingTime
                        val timeSubtractedFromThisCategory = timeToSubtractForCategory(categoryId)
                        val realRemainingTimeDefault = (remainingTimeFromCache.default - timeSubtractedFromThisCategory).coerceAtLeast(0)
                        val realRemainingTimeWithExtraTime = (remainingTimeFromCache.includingExtraTime - timeSubtractedFromThisCategory).coerceAtLeast(0)
                        val realRemainingTimeUsingExtraTime = realRemainingTimeDefault == 0L && realRemainingTimeWithExtraTime > 0

                        val remainingSessionDuration = handling.remainingSessionDuration?.let { (it - timeToSubtractForCategory(categoryId)).coerceAtLeast(0) }

                        buildStatusMessageWithCurrentAppTitle(
                                text = if (realRemainingTimeUsingExtraTime)
                                    appLogic.context.getString(R.string.background_logic_using_extra_time, TimeTextUtil.remaining(realRemainingTimeWithExtraTime.toInt(), appLogic.context))
                                else if (remainingSessionDuration != null && remainingSessionDuration < realRemainingTimeDefault)
                                    TimeTextUtil.pauseIn(remainingSessionDuration.toInt(), appLogic.context)
                                else
                                    TimeTextUtil.remaining(realRemainingTimeDefault.toInt() ?: 0, appLogic.context),
                                titlePrefix = getCategoryTitle(categoryId) + " - ",
                                titleSuffix = suffix,
                                appPackageName = appPackageName,
                                appActivityToShow = appActivityToShow
                        )
                    }
                }

                fun buildNotificationForAppWithoutCategoryUsage(
                        handling: AppBaseHandling,
                        suffix: String,
                        appPackageName: String?,
                        appActivityToShow: String?
                ): AppStatusMessage = when (handling) {
                    is AppBaseHandling.UseCategories -> throw IllegalArgumentException()
                    AppBaseHandling.BlockDueToNoCategory -> throw IllegalArgumentException()
                    AppBaseHandling.PauseLogic -> AppStatusMessage(
                            title = appLogic.context.getString(R.string.background_logic_paused_title) + suffix,
                            text = appLogic.context.getString(R.string.background_logic_paused_text),
                            showSwitchToDefaultUserOption = deviceRelatedData.canSwitchToDefaultUser
                    )
                    AppBaseHandling.Whitelist -> buildStatusMessageWithCurrentAppTitle(
                            text = appLogic.context.getString(R.string.background_logic_whitelisted),
                            titleSuffix = suffix,
                            appPackageName = appPackageName,
                            appActivityToShow = appActivityToShow
                    )
                    AppBaseHandling.TemporarilyAllowed -> buildStatusMessageWithCurrentAppTitle(
                            text = appLogic.context.getString(R.string.background_logic_temporarily_allowed),
                            titleSuffix = suffix,
                            appPackageName = appPackageName,
                            appActivityToShow = appActivityToShow
                    )
                    AppBaseHandling.Idle -> AppStatusMessage(
                            appLogic.context.getString(R.string.background_logic_idle_title) + suffix,
                            appLogic.context.getString(R.string.background_logic_idle_text),
                            showSwitchToDefaultUserOption = deviceRelatedData.canSwitchToDefaultUser
                    )
                }

                val showBackgroundStatus = !(backgroundAppBaseHandling is AppBaseHandling.Idle) &&
                        !blockAudioPlayback &&
                        foregroundApps.find { it.packageName == audioPlaybackPackageName } == null

                val statusMessage = if (blockedForegroundApp != null) {
                    buildStatusMessageWithCurrentAppTitle(
                            text = appLogic.context.getString(R.string.background_logic_opening_lockscreen),
                            appPackageName = blockedForegroundApp.packageName,
                            appActivityToShow = if (activityLevelBlocking) blockedForegroundApp.activityName else null
                    )
                } else {
                    val pagesForTheForegroundApps = foregroundAppWithBaseHandlings.sumBy { (_, foregroundAppBaseHandling) ->
                        // category ids are never empty/ this would trigger an exception
                        if (foregroundAppBaseHandling is AppBaseHandling.UseCategories) foregroundAppBaseHandling.categoryIds.size else 1
                    }
                    val pagesForTheBackgroundApp = if (!showBackgroundStatus) 0 else if (backgroundAppBaseHandling is AppBaseHandling.UseCategories) backgroundAppBaseHandling.categoryIds.size else 1
                    val totalPages = pagesForTheForegroundApps.coerceAtLeast(1) + pagesForTheBackgroundApp
                    val currentPage = (nowTimestamp / 3000 % totalPages).toInt()

                    val suffix = if (totalPages == 1) "" else " (${currentPage + 1} / $totalPages)"

                    if (currentPage < pagesForTheForegroundApps.coerceAtLeast(1)) {
                        if (pagesForTheForegroundApps == 0) {
                            buildNotificationForAppWithoutCategoryUsage(
                                    appPackageName = null,
                                    appActivityToShow = null,
                                    suffix = suffix,
                                    handling = AppBaseHandling.Idle
                            )
                        } else {
                            val pageWithin = currentPage

                            var listItemIndex = 0
                            var indexWithinListItem = 0
                            var totalIndex = 0

                            while (listItemIndex < foregroundAppWithBaseHandlings.size) {
                                val item = foregroundAppWithBaseHandlings[listItemIndex]
                                val handling = item.second
                                val itemLength = if (handling is AppBaseHandling.UseCategories) handling.categoryIds.size else 1

                                if (pageWithin < totalIndex + itemLength) {
                                    indexWithinListItem = pageWithin - totalIndex
                                    break
                                }

                                totalIndex += itemLength
                                listItemIndex++
                            }

                            val (app, handling) = foregroundAppWithBaseHandlings[listItemIndex]

                            if (handling is AppBaseHandling.UseCategories) {
                                val categoryId = handling.categoryIds.toList()[indexWithinListItem]

                                buildNotificationForAppWithCategoryUsage(
                                        appPackageName = app.packageName,
                                        appActivityToShow = if (activityLevelBlocking) app.activityName else null,
                                        suffix = suffix,
                                        categoryId = categoryId
                                )
                            } else {
                                buildNotificationForAppWithoutCategoryUsage(
                                        appPackageName = app.packageName,
                                        appActivityToShow = if (activityLevelBlocking) app.activityName else null,
                                        suffix = suffix,
                                        handling = handling
                                )
                            }
                        }
                    } else {
                        val pageWithin = currentPage - pagesForTheForegroundApps

                        if (backgroundAppBaseHandling is AppBaseHandling.UseCategories) {
                            val categoryId = backgroundAppBaseHandling.categoryIds.toList()[pageWithin]

                            buildNotificationForAppWithCategoryUsage(
                                    appPackageName = audioPlaybackPackageName,
                                    appActivityToShow = null,
                                    suffix = suffix,
                                    categoryId = categoryId
                            )
                        } else {
                            buildNotificationForAppWithoutCategoryUsage(
                                    appPackageName = audioPlaybackPackageName,
                                    appActivityToShow = null,
                                    suffix = suffix,
                                    handling = backgroundAppBaseHandling
                            )
                        }
                    }
                }

                appLogic.platformIntegration.setAppStatusMessage(statusMessage)

                // handle blocking
                if (blockedForegroundApp != null) {
                    openLockscreen(
                            blockedAppPackageName = blockedForegroundApp.packageName,
                            blockedAppActivityName = blockedForegroundApp.activityName,
                            enableSoftBlocking = deviceRelatedData.experimentalFlags and ExperimentalFlags.ENABLE_SOFT_BLOCKING == ExperimentalFlags.ENABLE_SOFT_BLOCKING
                    )
                } else {
                    appLogic.platformIntegration.setShowBlockingOverlay(false)
                }

                if (blockAudioPlayback && audioPlaybackPackageName != null) {
                    val currentAudioBlockUptime = appLogic.timeApi.getCurrentUptimeInMillis()
                    val oldAudioPlaybackBlock = previousAudioPlaybackBlock
                    val skipAudioBlock = oldAudioPlaybackBlock != null &&
                            oldAudioPlaybackBlock.second == audioPlaybackPackageName &&
                            oldAudioPlaybackBlock.first >= currentAudioBlockUptime

                    if (!skipAudioBlock) {
                        val newAudioPlaybackBlock = currentAudioBlockUptime + 1000 * 10 /* block for 10 seconds */ to audioPlaybackPackageName

                        previousAudioPlaybackBlock = newAudioPlaybackBlock

                        runAsync {
                            if (appLogic.platformIntegration.muteAudioIfPossible(audioPlaybackPackageName)) {
                                appLogic.platformIntegration.showOverlayMessage(appLogic.context.getString(R.string.background_logic_toast_block_audio))

                                // allow blocking again
                                // no locking needed because everything happens on the main thread
                                if (previousAudioPlaybackBlock === newAudioPlaybackBlock) {
                                    previousAudioPlaybackBlock = appLogic.timeApi.getCurrentUptimeInMillis() + 1000 * 1 /* block for 1 more second */ to audioPlaybackPackageName
                                }
                            } else {
                                appLogic.platformIntegration.showOverlayMessage(appLogic.context.getString(R.string.background_logic_toast_block_audio_failed))
                            }
                        }
                    }
                }
            } catch (ex: SecurityException) {
                // this is handled by an other main loop (with a delay)
                lastLoopException.postValue(ex)

                appLogic.platformIntegration.setAppStatusMessage(AppStatusMessage(
                        appLogic.context.getString(R.string.background_logic_error),
                        appLogic.context.getString(R.string.background_logic_error_permission),
                        showSwitchToDefaultUserOption = deviceRelatedData.canSwitchToDefaultUser
                ))
                appLogic.platformIntegration.setShowBlockingOverlay(false)
            } catch (ex: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.w(LOG_TAG, "exception during running main loop", ex)
                }

                lastLoopException.postValue(ex)

                appLogic.platformIntegration.setAppStatusMessage(AppStatusMessage(
                        appLogic.context.getString(R.string.background_logic_error),
                        appLogic.context.getString(R.string.background_logic_error_internal),
                        showSwitchToDefaultUserOption = deviceRelatedData.canSwitchToDefaultUser
                ))
                appLogic.platformIntegration.setShowBlockingOverlay(false)
            }

            // delay before running next time
            val endTime = appLogic.timeApi.getCurrentUptimeInMillis()
            previousMainLogicExecutionTime = (endTime - previousMainLoopEndTime).toInt()
            previousMainLoopEndTime = endTime

            val timeToWait = Math.max(10, backgroundServiceInterval - previousMainLogicExecutionTime)
            appLogic.timeApi.sleep(timeToWait)
        }
    }

    private suspend fun syncInstalledAppVersion() {
        val currentAppVersion = BuildConfig.VERSION_CODE
        val deviceEntry = appLogic.deviceEntry.waitForNullableValue()

        if (deviceEntry != null) {
            if (deviceEntry.currentAppVersion != currentAppVersion) {
                ApplyActionUtil.applyAppLogicAction(
                        action = UpdateDeviceStatusAction.empty.copy(
                                newAppVersion = currentAppVersion
                        ),
                        appLogic = appLogic,
                        ignoreIfDeviceIsNotConfigured = true
                )
            }
        }
    }

    fun syncDeviceStatusAsync() {
        runAsync {
            syncDeviceStatusFast()
        }
    }

    private suspend fun syncDeviceStatusLoop() {
        while (true) {
            appLogic.deviceEntryIfEnabled.waitUntilValueMatches { it != null }

            syncDeviceStatusSlow()

            appLogic.timeApi.sleep(CHECK_PERMISSION_INTERVAL)
        }
    }

    private val syncDeviceStatusLock = Mutex()

    fun reportDeviceReboot() {
        runAsync {
            val deviceEntry = appLogic.deviceEntry.waitForNullableValue()

            if (deviceEntry?.considerRebootManipulation == true) {
                ApplyActionUtil.applyAppLogicAction(
                        action = UpdateDeviceStatusAction.empty.copy(
                                didReboot = true
                        ),
                        appLogic = appLogic,
                        ignoreIfDeviceIsNotConfigured = true
                )
            }
        }
    }

    private suspend fun getUpdateDeviceStatusAction(): UpdateDeviceStatusAction {
        val deviceEntry = appLogic.deviceEntry.waitForNullableValue()

        var changes = UpdateDeviceStatusAction.empty

        if (deviceEntry != null) {
            val protectionLevel = appLogic.platformIntegration.getCurrentProtectionLevel()
            val usageStatsPermission = appLogic.platformIntegration.getForegroundAppPermissionStatus()
            val notificationAccess = appLogic.platformIntegration.getNotificationAccessPermissionStatus()
            val overlayPermission = appLogic.platformIntegration.getDrawOverOtherAppsPermissionStatus()
            val accessibilityService = appLogic.platformIntegration.isAccessibilityServiceEnabled()
            val qOrLater = AndroidVersion.qOrLater

            if (protectionLevel != deviceEntry.currentProtectionLevel) {
                changes = changes.copy(
                        newProtectionLevel = protectionLevel
                )

                if (protectionLevel == ProtectionLevel.DeviceOwner) {
                    appLogic.platformIntegration.setEnableSystemLockdown(true)
                }
            }

            if (usageStatsPermission != deviceEntry.currentUsageStatsPermission) {
                changes = changes.copy(
                        newUsageStatsPermissionStatus = usageStatsPermission
                )
            }

            if (notificationAccess != deviceEntry.currentNotificationAccessPermission) {
                changes = changes.copy(
                        newNotificationAccessPermission = notificationAccess
                )
            }

            if (overlayPermission != deviceEntry.currentOverlayPermission) {
                changes = changes.copy(
                        newOverlayPermission = overlayPermission
                )
            }

            if (accessibilityService != deviceEntry.accessibilityServiceEnabled) {
                changes = changes.copy(
                        newAccessibilityServiceEnabled = accessibilityService
                )
            }

            if (qOrLater && !deviceEntry.qOrLater) {
                changes = changes.copy(isQOrLaterNow = true)
            }
        }

        return changes
    }

    private suspend fun syncDeviceStatusFast() {
        syncDeviceStatusLock.withLock {
            val changes = getUpdateDeviceStatusAction()

            if (changes != UpdateDeviceStatusAction.empty) {
                ApplyActionUtil.applyAppLogicAction(
                        action = changes,
                        appLogic = appLogic,
                        ignoreIfDeviceIsNotConfigured = true
                )
            }
        }
    }

    private suspend fun syncDeviceStatusSlow() {
        syncDeviceStatusLock.withLock {
            val changesOne = getUpdateDeviceStatusAction()

            delay(2000)

            val changesTwo = getUpdateDeviceStatusAction()

            if (
                    changesOne != UpdateDeviceStatusAction.empty &&
                    changesOne == changesTwo
            ) {
                ApplyActionUtil.applyAppLogicAction(
                        action = changesOne,
                        appLogic = appLogic,
                        ignoreIfDeviceIsNotConfigured = true
                )
            }
        }
    }

    suspend fun resetTemporarilyAllowedApps() {
        val deviceId = appLogic.deviceId.waitForNullableValue()

        if (deviceId != null) {
            Threads.database.executeAndWait(Runnable {
                appLogic.database.temporarilyAllowedApp().removeAllTemporarilyAllowedAppsSync(deviceId)
            })
        }
    }

    private suspend fun backupDatabaseLoop() {
        appLogic.timeApi.sleep(1000 * 60 * 5 /* 5 minutes */)

        while (true) {
            DatabaseBackup.with(appLogic.context).tryCreateDatabaseBackupAsync()

            appLogic.timeApi.sleep(1000 * 60 * 60 * 3 /* 3 hours */)
        }
    }

    // first time: annoy for 20 seconds; free for 5 minutes
    // second time: annoy for 30 seconds; free for 2 minutes
    // third time: annoy for 1 minute; free for 1 minute
    // then: annoy for 2 minutes; free for 1 minute
    private suspend fun annoyUserOnManipulationLoop() {
        val isManipulated = appLogic.deviceEntryIfEnabled.map { it?.hasActiveManipulationWarning ?: false }
        val enableAnnoy = appLogic.database.config().isExperimentalFlagsSetAsync(ExperimentalFlags.MANIPULATION_ANNOY_USER)
        val timeLimitNotActive = IsAppInForeground.isRunning.invert()

        var counter = 0
        var globalCounter = 0

        val shouldAnnoyNow = isManipulated.and(enableAnnoy).and(timeLimitNotActive)

        if (BuildConfig.DEBUG) {
            Log.d(LOG_TAG, "delay before enabling annoying")
        }

        delay(1000 * 15)

        while (true) {
            if (BuildConfig.DEBUG) {
                Log.d(LOG_TAG, "wait until should annoy")
            }

            shouldAnnoyNow.waitUntilValueMatches { it == true }

            val annoyDurationInSeconds = when (counter) {
                0 -> 20
                1 -> 30
                2 -> 60
                else -> 120
            }

            val freeDurationInSeconds = when (counter) {
                0 -> 5 * 60
                1 -> 2 * 60
                else -> 60
            }

            if (BuildConfig.DEBUG) {
                Log.d(LOG_TAG, "annoy for $annoyDurationInSeconds seconds; free for $freeDurationInSeconds seconds")
            }

            appLogic.platformIntegration.showAnnoyScreen(annoyDurationInSeconds.toLong())

            counter++
            globalCounter++

            // reset counter if there was nothing for one hour
            val globalCounterBackup = globalCounter
            appLogic.timeApi.runDelayed(Runnable {
                if (globalCounter == globalCounterBackup) {
                    counter = 0
                }
            }, 1000 * 60 * 60 /* 1 hour */)

            // wait before annoying next time
            delay((annoyDurationInSeconds + freeDurationInSeconds) * 1000L)
        }
    }
}
