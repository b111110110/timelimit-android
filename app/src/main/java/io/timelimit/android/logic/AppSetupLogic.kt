/*
 * TimeLimit Copyright <C> 2019 Jonas Lochmann
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
import io.timelimit.android.crypto.PasswordHashing
import io.timelimit.android.data.IdGenerator
import io.timelimit.android.data.backup.DatabaseBackup
import io.timelimit.android.data.customtypes.ImmutableBitmask
import io.timelimit.android.data.model.*
import io.timelimit.android.integration.platform.NewPermissionStatus
import io.timelimit.android.integration.platform.ProtectionLevel
import io.timelimit.android.integration.platform.RuntimePermissionStatus
import io.timelimit.android.ui.user.create.DefaultCategories
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

            appLogic.database.beginTransaction()
            try {
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
                            didReportUninstall = false,
                            isUserKeptSignedIn = false,
                            showDeviceConnected = false,
                            defaultUser = "",
                            defaultUserTimeout = 0,
                            considerRebootManipulation = false
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
                            mailNotificationFlags = 0
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
                            mailNotificationFlags = 0
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
                            temporarilyBlocked = false,
                            baseVersion = "",
                            assignedAppsVersion = "",
                            timeLimitRulesVersion = "",
                            usedTimesVersion = "",
                            parentCategoryId = ""
                    ))

                    appLogic.database.category().addCategory(Category(
                            id = allowedGamesCategoryId,
                            childId = childUserId,
                            title = defaultCategories.allowedGamesTitle,
                            blockedMinutesInWeek = defaultCategories.allowedGamesBlockedTimes,
                            extraTimeInMillis = 0,
                            temporarilyBlocked = false,
                            baseVersion = "",
                            assignedAppsVersion = "",
                            timeLimitRulesVersion = "",
                            usedTimesVersion = "",
                            parentCategoryId = ""
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

                appLogic.database.setTransactionSuccessful()
            } finally {
                appLogic.database.endTransaction()
            }
        })

        DatabaseBackup.with(appLogic.context).tryCreateDatabaseBackupAsync()
    }

    suspend fun dangerousResetApp() {
        Threads.database.executeAndWait(Runnable {
            // this is already wrapped in a transaction
            appLogic.database.deleteAllData()
        })

        // delete the old config
        DatabaseBackup.with(appLogic.context).tryCreateDatabaseBackupAsync()
    }

    suspend fun dangerousRemoteReset() {
        appLogic.platformIntegration.showRemoteResetNotification()
        dangerousResetApp()
    }
}