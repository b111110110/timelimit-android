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
package io.timelimit.android.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.SeekBar
import io.timelimit.android.R
import io.timelimit.android.databinding.ViewSelectTimeSpanBinding
import io.timelimit.android.util.TimeTextUtil
import kotlin.properties.Delegates

class SelectTimeSpanView(context: Context, attributeSet: AttributeSet): FrameLayout(context, attributeSet) {
    private val binding = ViewSelectTimeSpanBinding.inflate(LayoutInflater.from(context), this, false)

    init {
        addView(binding.root)
    }

    var listener: SelectTimeSpanViewListener? = null

    var timeInMillis: Long by Delegates.observable(0L) {
        _, _, _ ->
            bindTime()
            listener?.onTimeSpanChanged(timeInMillis)
    }

    var maxDays: Int by Delegates.observable(0) {
        _, _, _ -> binding.maxDays = maxDays
    }

    init {
        val attributes = context.obtainStyledAttributes(attributeSet, R.styleable.SelectTimeSpanView)

        timeInMillis = attributes.getInt(R.styleable.SelectTimeSpanView_timeInMillis, timeInMillis.toInt()).toLong()
        maxDays = attributes.getInt(R.styleable.SelectTimeSpanView_maxDays, maxDays)

        attributes.recycle()

        bindTime()
    }

    private fun bindTime() {
        val totalMinutes = (timeInMillis / (1000 * 60)).toInt()
        val totalHours = totalMinutes  / 60
        val totalDays = totalHours / 24
        val minutes = totalMinutes % 60
        val hours = totalHours % 24

        binding.days = totalDays
        binding.minutes = minutes
        binding.hours = hours

        binding.daysText = TimeTextUtil.days(totalDays, context!!)
        binding.minutesText = TimeTextUtil.minutes(minutes, context!!)
        binding.hoursText = TimeTextUtil.hours(hours, context!!)
    }

    private fun readStatusFromBinding() {
        val days = binding.days!!.toLong()
        val hours = binding.hours!!.toLong()
        val minutes = binding.minutes!!.toLong()

        timeInMillis = (((days * 24) + hours) * 60 + minutes) * 1000 * 60
    }

    init {
        binding.daysSeek.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.days = progress
                readStatusFromBinding()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // ignore
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // ignore
            }
        })

        binding.hoursSeek.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.hours = progress
                readStatusFromBinding()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // ignore
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // ignore
            }
        })

        binding.minutesSeek.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.minutes = progress
                readStatusFromBinding()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // ignore
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // ignore
            }
        })
    }
}

interface SelectTimeSpanViewListener {
    fun onTimeSpanChanged(newTimeInMillis: Long)
}