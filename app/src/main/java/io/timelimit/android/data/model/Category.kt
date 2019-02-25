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
package io.timelimit.android.data.model

import android.util.JsonReader
import android.util.JsonWriter
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import io.timelimit.android.data.IdGenerator
import io.timelimit.android.data.JsonSerializable
import io.timelimit.android.data.customtypes.ImmutableBitmask
import io.timelimit.android.data.customtypes.ImmutableBitmaskAdapter
import io.timelimit.android.data.customtypes.ImmutableBitmaskJson

@Entity(tableName = "category")
@TypeConverters(ImmutableBitmaskAdapter::class)
data class Category(
        @PrimaryKey
        @ColumnInfo(name = "id")
        val id: String,
        @ColumnInfo(name = "child_id")
        val childId: String,
        @ColumnInfo(name = "title")
        val title: String,
        @ColumnInfo(name = "blocked_times")
        val blockedMinutesInWeek: ImmutableBitmask,    // 10080 bit -> ~10 KB
        @ColumnInfo(name = "extra_time")
        val extraTimeInMillis: Long,
        @ColumnInfo(name = "temporarily_blocked")
        val temporarilyBlocked: Boolean,
        @ColumnInfo(name = "base_version")
        val baseVersion: String,
        @ColumnInfo(name = "apps_version")
        val assignedAppsVersion: String,
        @ColumnInfo(name = "rules_version")
        val timeLimitRulesVersion: String,
        @ColumnInfo(name = "usedtimes_version")
        val usedTimesVersion: String,
        @ColumnInfo(name = "parent_category_id")
        val parentCategoryId: String
): JsonSerializable {
    companion object {
        const val MINUTES_PER_DAY = 60 * 24
        const val BLOCKED_MINUTES_IN_WEEK_LENGTH = MINUTES_PER_DAY * 7

        private const val ID = "id"
        private const val CHILD_ID = "cid"
        private const val TITLE = "T"
        private const val BLOCKED_MINUTES_IN_WEEK = "b"
        private const val EXTRA_TIME_IN_MILLIS = "et"
        private const val TEMPORARILY_BLOCKED = "tb"
        private const val BASE_VERSION = "vb"
        private const val ASSIGNED_APPS_VERSION = "va"
        private const val RULES_VERSION = "vr"
        private const val USED_TIMES_VERSION = "vu"
        private const val PARENT_CATEGORY_ID = "pc"

        fun parse(reader: JsonReader): Category {
            var id: String? = null
            var childId: String? = null
            var title: String? = null
            var blockedMinutesInWeek: ImmutableBitmask? = null
            var extraTimeInMillis: Long? = null
            var temporarilyBlocked: Boolean? = null
            var baseVersion: String? = null
            var assignedAppsVersion: String? = null
            var timeLimitRulesVersion: String? = null
            var usedTimesVersion: String? = null
            // this field was added later so it has got a default value
            var parentCategoryId = ""

            reader.beginObject()

            while (reader.hasNext()) {
                when (reader.nextName()) {
                    ID -> id = reader.nextString()
                    CHILD_ID -> childId = reader.nextString()
                    TITLE -> title = reader.nextString()
                    BLOCKED_MINUTES_IN_WEEK -> blockedMinutesInWeek = ImmutableBitmaskJson.parse(reader.nextString(), BLOCKED_MINUTES_IN_WEEK_LENGTH)
                    EXTRA_TIME_IN_MILLIS -> extraTimeInMillis = reader.nextLong()
                    TEMPORARILY_BLOCKED -> temporarilyBlocked = reader.nextBoolean()
                    BASE_VERSION -> baseVersion = reader.nextString()
                    ASSIGNED_APPS_VERSION -> assignedAppsVersion = reader.nextString()
                    RULES_VERSION -> timeLimitRulesVersion = reader.nextString()
                    USED_TIMES_VERSION -> usedTimesVersion = reader.nextString()
                    PARENT_CATEGORY_ID -> parentCategoryId = reader.nextString()
                    else -> reader.skipValue()
                }
            }

            reader.endObject()

            return Category(
                    id = id!!,
                    childId = childId!!,
                    title = title!!,
                    blockedMinutesInWeek = blockedMinutesInWeek!!,
                    extraTimeInMillis = extraTimeInMillis!!,
                    temporarilyBlocked = temporarilyBlocked!!,
                    baseVersion = baseVersion!!,
                    assignedAppsVersion = assignedAppsVersion!!,
                    timeLimitRulesVersion = timeLimitRulesVersion!!,
                    usedTimesVersion = usedTimesVersion!!,
                    parentCategoryId = parentCategoryId
            )
        }
    }

    init {
        IdGenerator.assertIdValid(id)
        IdGenerator.assertIdValid(childId)

        if (extraTimeInMillis < 0) {
            throw IllegalStateException()
        }

        if (title.isEmpty()) {
            throw IllegalArgumentException()
        }
    }

    override fun serialize(writer: JsonWriter) {
        writer.beginObject()

        writer.name(ID).value(id)
        writer.name(CHILD_ID).value(childId)
        writer.name(TITLE).value(title)
        writer.name(BLOCKED_MINUTES_IN_WEEK).value(ImmutableBitmaskJson.serialize(blockedMinutesInWeek))
        writer.name(EXTRA_TIME_IN_MILLIS).value(extraTimeInMillis)
        writer.name(TEMPORARILY_BLOCKED).value(temporarilyBlocked)
        writer.name(BASE_VERSION).value(baseVersion)
        writer.name(ASSIGNED_APPS_VERSION).value(assignedAppsVersion)
        writer.name(RULES_VERSION).value(timeLimitRulesVersion)
        writer.name(USED_TIMES_VERSION).value(usedTimesVersion)
        writer.name(PARENT_CATEGORY_ID).value(parentCategoryId)

        writer.endObject()
    }
}