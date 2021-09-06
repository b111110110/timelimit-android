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
package io.timelimit.android.ui.manage.parent

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import io.timelimit.android.data.model.User
import io.timelimit.android.databinding.ManageParentNotificationsBinding
import io.timelimit.android.sync.actions.UpdateParentNotificationFlagsAction
import io.timelimit.android.ui.main.ActivityViewModel

object ManageParentNotifications {
    fun bind(
            view: ManageParentNotificationsBinding,
            lifecycleOwner: LifecycleOwner,
            auth: ActivityViewModel,
            userEntry: LiveData<User?>
    ) {
        userEntry.observe(lifecycleOwner, Observer { user ->
            view.hasMailAddress = user?.mail?.isNotEmpty() ?: false

            val manipulation = (user?.mailNotificationFlags ?: 0) and 1 == 1
            val tasks = (user?.mailNotificationFlags ?: 0) and 2 == 2

            view.manipulationCheckbox.setOnCheckedChangeListener { _, _ ->  }
            view.manipulationCheckbox.isChecked = manipulation
            view.manipulationCheckbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked != manipulation) {
                    if (
                        auth.tryDispatchParentAction(
                                UpdateParentNotificationFlagsAction(
                                        parentId = user?.id!!,
                                        flags = 1,
                                        set = isChecked
                                )
                        )
                    ) {
                        // it worked
                    } else {
                        view.manipulationCheckbox.isChecked = manipulation
                    }
                }
            }

            view.taskCheckbox.setOnCheckedChangeListener { _, _ ->  }
            view.taskCheckbox.isChecked = tasks
            view.taskCheckbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked != tasks) {
                    if (
                            auth.tryDispatchParentAction(
                                    UpdateParentNotificationFlagsAction(
                                            parentId = user?.id!!,
                                            flags = 2,
                                            set = isChecked
                                    )
                            )
                    ) {
                        // it worked
                    } else {
                        view.taskCheckbox.isChecked = tasks
                    }
                }
            }
        })
    }
}