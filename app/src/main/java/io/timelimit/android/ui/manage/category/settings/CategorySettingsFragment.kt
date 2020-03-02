/*
 * TimeLimit Copyright <C> 2019 - 2020 Jonas Lochmann
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
package io.timelimit.android.ui.manage.category.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import com.google.android.material.snackbar.Snackbar
import io.timelimit.android.R
import io.timelimit.android.async.Threads
import io.timelimit.android.data.extensions.mapToTimezone
import io.timelimit.android.databinding.FragmentCategorySettingsBinding
import io.timelimit.android.date.DateInTimezone
import io.timelimit.android.livedata.*
import io.timelimit.android.logic.AppLogic
import io.timelimit.android.logic.DefaultAppLogic
import io.timelimit.android.sync.actions.SetCategoryExtraTimeAction
import io.timelimit.android.ui.help.HelpDialogFragment
import io.timelimit.android.ui.main.ActivityViewModel
import io.timelimit.android.ui.main.getActivityViewModel
import io.timelimit.android.ui.manage.category.ManageCategoryFragmentArgs
import io.timelimit.android.ui.payment.RequiresPurchaseDialogFragment
import io.timelimit.android.ui.view.SelectTimeSpanViewListener

class CategorySettingsFragment : Fragment() {
    companion object {
        fun newInstance(params: ManageCategoryFragmentArgs): CategorySettingsFragment {
            val result = CategorySettingsFragment()
            result.arguments = params.toBundle()
            return result
        }
    }

    private val params: ManageCategoryFragmentArgs by lazy { ManageCategoryFragmentArgs.fromBundle(arguments!!) }
    private val appLogic: AppLogic by lazy { DefaultAppLogic.with(context!!) }
    private val auth: ActivityViewModel by lazy { getActivityViewModel(activity!!) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val binding = FragmentCategorySettingsBinding.inflate(inflater, container, false)

        val categoryEntry = appLogic.database.category().getCategoryByChildIdAndId(params.childId, params.categoryId)

        val childDate = appLogic.database.user().getChildUserByIdLive(params.childId).mapToTimezone().switchMap { timezone ->
            liveDataFromFunction (1000 * 10L) { DateInTimezone.newInstance(appLogic.timeApi.getCurrentTimeInMillis(), timezone) }
        }.ignoreUnchanged()

        val currentExtraTime = categoryEntry.switchMap { category ->
            childDate.map { date ->
                category?.getExtraTime(date.dayOfEpoch)
            }
        }.ignoreUnchanged()

        val currentExtraTimeBoundToDate = currentExtraTime.map { it != null && it != 0L }.and(
                categoryEntry.map { it?.extraTimeDay != null && it.extraTimeDay != -1 }
        ).ignoreUnchanged()

        ManageCategoryForUnassignedApps.bind(
                binding = binding.categoryForUnassignedApps,
                lifecycleOwner = this,
                categoryId = params.categoryId,
                childId = params.childId,
                database = appLogic.database,
                auth = auth,
                fragmentManager = fragmentManager!!
        )

        CategoryBatteryLimitView.bind(
                binding = binding.batteryLimit,
                lifecycleOwner = this,
                category = categoryEntry,
                auth = auth,
                categoryId = params.categoryId,
                fragmentManager = fragmentManager!!
        )

        ParentCategoryView.bind(
                binding = binding.parentCategory,
                lifecycleOwner = this,
                categoryId = params.categoryId,
                childId = params.childId,
                database = appLogic.database,
                fragmentManager = fragmentManager!!,
                auth = auth
        )

        CategoryNotificationFilter.bind(
                view = binding.notificationFilter,
                lifecycleOwner = this,
                fragmentManager = fragmentManager!!,
                auth = auth,
                categoryLive = categoryEntry
        )

        CategoryTimeWarningView.bind(
                view = binding.timeWarnings,
                auth = auth,
                categoryLive = categoryEntry,
                lifecycleOwner = this,
                fragmentManager = fragmentManager!!
        )

        binding.btnDeleteCategory.setOnClickListener { deleteCategory() }
        binding.editCategoryTitleGo.setOnClickListener { renameCategory() }

        binding.extraTimeTitle.setOnClickListener {
            HelpDialogFragment.newInstance(
                    title = R.string.category_settings_extra_time_title,
                    text = R.string.category_settings_extra_time_info
            ).show(fragmentManager!!)
        }

        fun updateEditExtraTimeConfirmButtonVisibility() {
            val roundedCurrentTimeInMillis = currentExtraTime.value?.let { (it / (1000 * 60)) * (1000 * 60) } ?: 0
            val newLimitToToday = binding.switchLimitExtraTimeToToday.isChecked
            val newTimeInMillis = binding.extraTimeSelection.timeInMillis

            val timeDiffers = newTimeInMillis != roundedCurrentTimeInMillis
            val dayDiffers = newLimitToToday != (currentExtraTimeBoundToDate.value ?: false)

            binding.extraTimeBtnOk.visibility = if (timeDiffers || dayDiffers)
                View.VISIBLE
            else
                View.GONE
        }

        currentExtraTime.observe(viewLifecycleOwner, Observer {
            if (it != null) {
                val roundedCurrentTimeInMillis = (it / (1000 * 60)) * (1000 * 60)

                if (binding.extraTimeSelection.timeInMillis != roundedCurrentTimeInMillis) {
                    binding.extraTimeSelection.timeInMillis = roundedCurrentTimeInMillis

                    updateEditExtraTimeConfirmButtonVisibility()
                }
            }
        })

        currentExtraTimeBoundToDate.observe(viewLifecycleOwner, Observer {
            if (binding.switchLimitExtraTimeToToday.isChecked != it) {
                binding.switchLimitExtraTimeToToday.isChecked = it

                updateEditExtraTimeConfirmButtonVisibility()
            }
        })

        appLogic.fullVersion.shouldProvideFullVersionFunctions.observe(viewLifecycleOwner, Observer { hasFullVersion ->
            binding.extraTimeBtnOk.setOnClickListener {
                binding.extraTimeSelection.clearNumberPickerFocus()

                if (hasFullVersion) {
                    val newExtraTime = binding.extraTimeSelection.timeInMillis

                    if (
                            auth.tryDispatchParentAction(
                                    SetCategoryExtraTimeAction(
                                            categoryId = params.categoryId,
                                            newExtraTime = newExtraTime,
                                            extraTimeDay = (if (binding.switchLimitExtraTimeToToday.isChecked) childDate.value?.dayOfEpoch else null) ?: -1
                                    )
                            )
                    ) {
                        Snackbar.make(binding.root, R.string.category_settings_extra_time_change_toast, Snackbar.LENGTH_SHORT).show()

                        binding.extraTimeBtnOk.visibility = View.GONE
                    }
                } else {
                    RequiresPurchaseDialogFragment().show(fragmentManager!!)
                }
            }
        })

        appLogic.database.config().getEnableAlternativeDurationSelectionAsync().observe(viewLifecycleOwner, Observer {
            binding.extraTimeSelection.enablePickerMode(it)
        })

        binding.extraTimeSelection.listener = object: SelectTimeSpanViewListener {
            override fun onTimeSpanChanged(newTimeInMillis: Long) {
                updateEditExtraTimeConfirmButtonVisibility()
            }

            override fun setEnablePickerMode(enable: Boolean) {
                Threads.database.execute {
                    appLogic.database.config().setEnableAlternativeDurationSelectionSync(enable)
                }
            }
        }

        binding.switchLimitExtraTimeToToday.setOnCheckedChangeListener { _, _ ->
            updateEditExtraTimeConfirmButtonVisibility()
        }

        return binding.root
    }

    private fun renameCategory() {
        if (auth.requestAuthenticationOrReturnTrue()) {
            RenameCategoryDialogFragment.newInstance(params).show(fragmentManager!!)
        }
    }

    private fun deleteCategory() {
        if (auth.requestAuthenticationOrReturnTrue()) {
            DeleteCategoryDialogFragment.newInstance(params).show(fragmentManager!!)
        }
    }
}
