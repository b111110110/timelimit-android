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

import android.content.Context
import com.jaredrummler.android.device.DeviceName
import io.timelimit.android.R
import io.timelimit.android.async.Threads
import io.timelimit.android.coroutines.executeAndWait
import io.timelimit.android.coroutines.runAsync
import io.timelimit.android.crypto.PasswordHashing
import io.timelimit.android.data.IdGenerator
import io.timelimit.android.data.backup.DatabaseBackup
import io.timelimit.android.data.customtypes.ImmutableBitmask
import io.timelimit.android.data.model.*
import io.timelimit.android.integration.platform.NewPermissionStatus
import io.timelimit.android.integration.platform.ProtectionLevel
import io.timelimit.android.integration.platform.RuntimePermissionStatus
import io.timelimit.android.ui.user.create.DefaultCategories
import io.timelimit.android.util.AndroidVersion
import io.timelimit.android.work.CheckUpdateWorker
import io.timelimit.android.work.PeriodicSyncInBackgroundWorker
import io.timelimit.android.work.ReportUninstallWorker
import java.util.*

class AppSetupLogic(private val appLogic: AppLogic) {
    suspend fun setupForLocalUse(parentPassword: String, networkTimeVerification: NetworkTime, context: Context) {
        Threads.database.executeAndWait(Runnable {
            run {
                // assert that the device is not yet configured
                val oldDeviceId = appLogic.database.config().getOwnDeviceIdSync()

                if (oldDeviceId != null) {
                    throw IllegalStateException("already configured")
                }
            }

            val ownDeviceId = IdGenerator.generateId()
            val parentUserId = IdGenerator.generateId()
            val childUserId = IdGenerator.generateId()
            val allowedAppsCategoryId = IdGenerator.generateId()
            val allowedGamesCategoryId = IdGenerator.generateId()

            appLogic.database.runInTransaction {
                run {
                    val customServerUrl = appLogic.database.config().getCustomServerUrlSync()

                    // just for safety: delete everything except the custom server url
                    appLogic.database.deleteAllData()

                    appLogic.database.config().setCustomServerUrlSync(customServerUrl)
                }

                run {
                    // set device id
                    appLogic.database.config().setOwnDeviceIdSync(ownDeviceId)
                }

                val timeZone = appLogic.timeApi.getSystemTimeZone().id

                run {
                    // add device
                    val deviceName = DeviceName.getDeviceName()

                    val device = Device(
                            id = ownDeviceId,
                            name = deviceName,
                            model = deviceName,
                            addedAt = appLogic.timeApi.getCurrentTimeInMillis(),
                            currentUserId = childUserId,
                            installedAppsVersion = "",
                            networkTime = networkTimeVerification,
                            currentProtectionLevel = ProtectionLevel.None,
                            highestProtectionLevel = ProtectionLevel.None,
                            currentNotificationAccessPermission = NewPermissionStatus.NotGranted,
                            highestNotificationAccessPermission = NewPermissionStatus.NotGranted,
                            currentUsageStatsPermission = RuntimePermissionStatus.NotGranted,
                            highestUsageStatsPermission = RuntimePermissionStatus.NotGranted,
                            currentAppVersion = 0,
                            highestAppVersion = 0,
                            manipulationTriedDisablingDeviceAdmin = false,
                            manipulationDidReboot = false,
                            hadManipulation = false,
                            hadManipulationFlags = 0,
                            didReportUninstall = false,
                            isUserKeptSignedIn = false,
                            showDeviceConnected = false,
                            defaultUser = "",
                            defaultUserTimeout = 0,
                            considerRebootManipulation = false,
                            currentOverlayPermission = RuntimePermissionStatus.NotGranted,
                            highestOverlayPermission = RuntimePermissionStatus.NotGranted,
                            accessibilityServiceEnabled = false,
                            wasAccessibilityServiceEnabled = false,
                            enableActivityLevelBlocking = false,
                            qOrLater = AndroidVersion.qOrLater
                    )

                    appLogic.database.device().addDeviceSync(device)
                }

                run {
                    // add child

                    val child = User(
                            id = childUserId,
                            name = context.getString(R.string.setup_username_child),
                            password = "",
                            secondPasswordSalt = "",
                            type = UserType.Child,
                            timeZone = timeZone,
                            disableLimitsUntil = 0,
                            mail = "",
                            currentDevice = "",
                            categoryForNotAssignedApps = "",
                            relaxPrimaryDevice = false,
                            mailNotificationFlags = 0,
                            flags = 0
                    )

                    appLogic.database.user().addUserSync(child)
                }

                run {
                    // add parent

                    val parent = User(
                            id = parentUserId,
                            name = context.getString(R.string.setup_username_parent),
                            password = PasswordHashing.hashSync(parentPassword),
                            secondPasswordSalt = PasswordHashing.generateSalt(),
                            type = UserType.Parent,
                            timeZone = timeZone,
                            disableLimitsUntil = 0,
                            mail = "",
                            currentDevice = "",
                            categoryForNotAssignedApps = "",
                            relaxPrimaryDevice = false,
                            mailNotificationFlags = 0,
                            flags = 0
                    )

                    appLogic.database.user().addUserSync(parent)
                }

                val installedApps = appLogic.platformIntegration.getLocalApps(ownDeviceId)

                // add installed apps
                appLogic.database.app().addAppsSync(installedApps)

                val defaultCategories = DefaultCategories.with(context)

                // NOTE: the default config is created at the AddUserModel and at the AppSetupLogic
                run {
                    // add starter categories
                    appLogic.database.category().addCategory(Category(
                            id = allowedAppsCategoryId,
                            childId = childUserId,
                            title = defaultCategories.allowedAppsTitle,
                            blockedMinutesInWeek = ImmutableBitmask((BitSet())),
                            extraTimeInMillis = 0,
                            extraTimeDay = -1,
                            temporarilyBlocked = false,
                            temporarilyBlockedEndTime = 0,
                            baseVersion = "",
                            assignedAppsVersion = "",
                            timeLimitRulesVersion = "",
                            usedTimesVersion = "",
                            tasksVersion = "",
                            parentCategoryId = "",
                            blockAllNotifications = false,
                            timeWarnings = 0,
                            minBatteryLevelWhileCharging = 0,
                            minBatteryLevelMobile = 0,
                            sort = 0,
                            disableLimitsUntil = 0,
                            flags = 0,
                            blockNotificationDelay = 0
                    ))

                    appLogic.database.category().addCategory(Category(
                            id = allowedGamesCategoryId,
                            childId = childUserId,
                            title = defaultCategories.allowedGamesTitle,
                            blockedMinutesInWeek = ImmutableBitmask(BitSet()),
                            extraTimeInMillis = 0,
                            extraTimeDay = -1,
                            temporarilyBlocked = false,
                            temporarilyBlockedEndTime = 0,
                            baseVersion = "",
                            assignedAppsVersion = "",
                            timeLimitRulesVersion = "",
                            usedTimesVersion = "",
                            tasksVersion = "",
                            parentCategoryId = "",
                            blockAllNotifications = false,
                            timeWarnings = 0,
                            minBatteryLevelWhileCharging = 0,
                            minBatteryLevelMobile = 0,
                            sort = 1,
                            disableLimitsUntil = 0,
                            flags = 0,
                            blockNotificationDelay = 0
                    ))

                    // add default allowed apps
                    appLogic.database.categoryApp().addCategoryAppsSync(
                            installedApps
                                    .filter { it.recommendation == AppRecommendation.Whitelist }
                                    .map {
                                        CategoryApp(
                                                categoryId = allowedAppsCategoryId,
                                                packageName = it.packageName
                                        )
                                    }
                    )

                    // add default time limit rules
                    defaultCategories.generateGamesTimeLimitRules(allowedGamesCategoryId).forEach { rule ->
                        appLogic.database.timeLimitRules().addTimeLimitRule(rule)
                    }
                }
            }
        })

        DatabaseBackup.with(appLogic.context).tryCreateDatabaseBackupAsync()
    }

    fun resetAppCompletely() {
        appLogic.platformIntegration.setEnableSystemLockdown(false)

        runAsync {
            val server = appLogic.serverLogic.getServerConfigCoroutine()

            if (server.hasAuthToken) {
                ReportUninstallWorker.enqueue(
                        deviceAuthToken = server.deviceAuthToken,
                        customServerUrl = server.customServerUrl
                )
            }

            appLogic.appSetupLogic.dangerousResetApp()
        }
    }

    suspend fun dangerousResetApp() {
        Threads.database.executeAndWait(Runnable {
            // this is already wrapped in a transaction
            appLogic.database.deleteAllData()
        })

        // delete the old config
        DatabaseBackup.with(appLogic.context).tryCreateDatabaseBackupAsync()
        PeriodicSyncInBackgroundWorker.disable()
        CheckUpdateWorker.deschedule()
    }

    suspend fun dangerousRemoteReset() {
        appLogic.platformIntegration.setEnableSystemLockdown(false)
        appLogic.platformIntegration.showRemoteResetNotification()
        dangerousResetApp()
    }
}
