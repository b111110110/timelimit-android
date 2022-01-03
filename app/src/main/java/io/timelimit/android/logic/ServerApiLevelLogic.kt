/*
 * TimeLimit Copyright <C> 2019 - 2022 Jonas Lochmann
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

import io.timelimit.android.livedata.liveDataFromNonNullValue
import io.timelimit.android.livedata.map
import io.timelimit.android.livedata.switchMap

class ServerApiLevelLogic(logic: AppLogic) {
    val infoLive = logic.database.config().getDeviceAuthTokenAsync().switchMap { authToken ->
        if (authToken.isEmpty())
            liveDataFromNonNullValue(ServerApiLevelInfo.Offline)
        else
            logic.database.config().getServerApiLevelLive().map { apiLevel ->
                ServerApiLevelInfo.Online(serverLevel = apiLevel)
            }
    }
}

sealed class ServerApiLevelInfo {
    abstract fun hasLevelOrIsOffline(level: Int): Boolean

    data class Online(val serverLevel: Int): ServerApiLevelInfo() {
        override fun hasLevelOrIsOffline(level: Int) = serverLevel >= level
    }

    object Offline: ServerApiLevelInfo() {
        override fun hasLevelOrIsOffline(level: Int) = true
    }
}