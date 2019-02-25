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
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.paging.LivePagedListBuilder
import androidx.recyclerview.widget.LinearLayoutManager
import io.timelimit.android.R
import io.timelimit.android.async.Threads
import io.timelimit.android.databinding.DiagnoseSyncFragmentBinding
import io.timelimit.android.logic.DefaultAppLogic
import io.timelimit.android.sync.actions.apply.UploadActionsUtil

class DiagnoseSyncFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val binding = DiagnoseSyncFragmentBinding.inflate(inflater, container, false)
        val logic = DefaultAppLogic.with(context!!)
        val adapter = PendingSyncActionAdapter()

        binding.recycler.layoutManager = LinearLayoutManager(context!!)
        binding.recycler.adapter = adapter

        LivePagedListBuilder(logic.database.pendingSyncAction().getAllPendingSyncActionsPaged(), 10)
                .build()
                .observe(this, Observer {
                    adapter.submitList(it)
                    binding.isListEmpty = it.isEmpty()
                })

        binding.clearCacheBtn.setOnClickListener {
            Threads.database.execute {
                UploadActionsUtil.deleteAllVersionNumbersSync(logic.database)
            }

            Toast.makeText(context!!, R.string.diagnose_sync_btn_clear_cache_toast, Toast.LENGTH_SHORT).show()
        }

        binding.requestSyncBtn.setOnClickListener {
            logic.syncUtil.requestImportantSync(true)

            Toast.makeText(context!!, R.string.diagnose_sync_btn_request_sync_toast, Toast.LENGTH_SHORT).show()
        }

        return binding.root
    }
}