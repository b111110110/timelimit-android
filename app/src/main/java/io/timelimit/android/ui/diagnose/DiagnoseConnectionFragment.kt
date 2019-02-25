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
import io.timelimit.android.R
import io.timelimit.android.databinding.FragmentDiagnoseConnectionBinding
import io.timelimit.android.logic.DefaultAppLogic
import io.timelimit.android.sync.websocket.NetworkStatus

class DiagnoseConnectionFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val binding = FragmentDiagnoseConnectionBinding.inflate(inflater, container, false)
        val logic = DefaultAppLogic.with(context!!)

        logic.networkStatus.observe(this, Observer {
            binding.generalStatus = getString(when (it!!) {
                NetworkStatus.Online -> R.string.diagnose_connection_yes
                NetworkStatus.Offline -> R.string.diagnose_connection_no
            })
        })

        logic.isConnected.observe(this, Observer {
            binding.ownServerStatus = getString(if (it == true)
                R.string.diagnose_connection_yes
            else
                R.string.diagnose_connection_no
            )
        })

        return binding.root
    }
}