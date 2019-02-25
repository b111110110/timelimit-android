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
package io.timelimit.android.ui.manage.child.category

import io.timelimit.android.data.model.Category

sealed class ManageChildCategoriesListItem
object CategoriesIntroductionHeader: ManageChildCategoriesListItem()
object CreateCategoryItem: ManageChildCategoriesListItem()
object ManipulationWarningCategoryItem: ManageChildCategoriesListItem()
data class CategoryItem(
        val category: Category,
        val isBlockedTimeNow: Boolean,
        val remainingTimeToday: Long?,
        val usedTimeToday: Long,
        val usedForNotAssignedApps: Boolean,
        val parentCategoryTitle: String?
): ManageChildCategoriesListItem()