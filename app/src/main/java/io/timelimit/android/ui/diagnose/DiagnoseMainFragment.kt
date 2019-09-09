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
package io.timelimit.android.ui.diagnose

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.navigation.Navigation
import io.timelimit.android.R
import io.timelimit.android.databinding.FragmentDiagnoseMainBinding
import io.timelimit.android.extensions.safeNavigate
import io.timelimit.android.logic.DefaultAppLogic

class DiagnoseMainFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val binding = FragmentDiagnoseMainBinding.inflate(inflater, container, false)
        val navigation = Navigation.findNavController(container!!)
        val logic = DefaultAppLogic.with(context!!)

        binding.diagnoseClockButton.setOnClickListener {
            navigation.safeNavigate(
                    DiagnoseMainFragmentDirections.actionDiagnoseMainFragmentToDiagnoseClockFragment(),
                    R.id.diagnoseMainFragment
            )
        }

        binding.diagnoseConnectionButton.setOnClickListener {
            navigation.safeNavigate(
                    DiagnoseMainFragmentDirections.actionDiagnoseMainFragmentToDiagnoseConnectionFragment(),
                    R.id.diagnoseMainFragment
            )
        }

        binding.diagnoseSyncButton.setOnClickListener {
            navigation.safeNavigate(
                    DiagnoseMainFragmentDirections.actionDiagnoseMainFragmentToDiagnoseSyncFragment(),
                    R.id.diagnoseMainFragment
            )
        }

        binding.diagnoseFgaButton.setOnClickListener {
            navigation.safeNavigate(
                    DiagnoseMainFragmentDirections.actionDiagnoseMainFragmentToDiagnoseForegroundAppFragment(),
                    R.id.diagnoseMainFragment
            )
        }

        binding.diagnoseExfButton.setOnClickListener {
            navigation.safeNavigate(
                    DiagnoseMainFragmentDirections.actionDiagnoseMainFragmentToDiagnoseExperimentalFlagFragment(),
                    R.id.diagnoseMainFragment
            )
        }

        logic.backgroundTaskLogic.lastLoopException.observe(this, Observer { ex ->
            if (ex != null) {
                binding.diagnoseBgTaskLoopExButton.isEnabled = true
                binding.diagnoseBgTaskLoopExButton.setOnClickListener {
                    DiagnoseExceptionDialogFragment.newInstance(ex).show(fragmentManager!!)
                }
            } else {
                binding.diagnoseBgTaskLoopExButton.isEnabled = false
            }
        })

        return binding.root
    }
}
