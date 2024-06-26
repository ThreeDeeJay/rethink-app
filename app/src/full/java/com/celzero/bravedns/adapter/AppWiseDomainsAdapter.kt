/*
 * Copyright 2021 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.adapter

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleOwner
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.databinding.ListItemAppDomainDetailsBinding
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.ui.bottomsheet.AppDomainRulesBottomSheet
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.Utilities.removeBeginningTrailingCommas

class AppWiseDomainsAdapter(
    val context: Context,
    val lifecycleOwner: LifecycleOwner,
    val uid: Int
) :
    PagingDataAdapter<AppConnection, AppWiseDomainsAdapter.ConnectionDetailsViewHolder>(
        DIFF_CALLBACK
    ),
    AppDomainRulesBottomSheet.OnBottomSheetDialogFragmentDismiss {

    companion object {
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<AppConnection>() {

                override fun areItemsTheSame(
                    oldConnection: AppConnection,
                    newConnection: AppConnection
                ) = oldConnection == newConnection

                override fun areContentsTheSame(
                    oldConnection: AppConnection,
                    newConnection: AppConnection
                ) = oldConnection == newConnection
            }
    }

    private lateinit var adapter: AppWiseDomainsAdapter

    // ui component to update/toggle the buttons
    data class ToggleBtnUi(val txtColor: Int, val bgColor: Int)

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): AppWiseDomainsAdapter.ConnectionDetailsViewHolder {
        val itemBinding =
            ListItemAppDomainDetailsBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        adapter = this
        return ConnectionDetailsViewHolder(itemBinding)
    }

    override fun onBindViewHolder(
        holder: AppWiseDomainsAdapter.ConnectionDetailsViewHolder,
        position: Int
    ) {
        val appConnection: AppConnection = getItem(position) ?: return
        // updates the app-wise connections from network log to AppInfo screen
        holder.update(appConnection)
    }

    inner class ConnectionDetailsViewHolder(private val b: ListItemAppDomainDetailsBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun update(conn: AppConnection) {
            displayTransactionDetails(conn)
            setupClickListeners(conn)
        }

        private fun displayTransactionDetails(appConnection: AppConnection) {
            b.acdCount.text = appConnection.count.toString()
            b.acdDomain.text = appConnection.appOrDnsName
            if (appConnection.ipAddress.isNotEmpty()) {
                b.acdIpAddress.visibility = View.VISIBLE
                b.acdIpAddress.text = beautifyIpString(appConnection.ipAddress)
            } else {
                b.acdIpAddress.visibility = View.GONE
            }
            updateStatusUi(appConnection.uid, appConnection.appOrDnsName)
        }

        private fun setupClickListeners(appConn: AppConnection) {
            b.acdContainer.setOnClickListener {
                // open bottom sheet to apply domain/ip rules
                openBottomSheet(appConn)
            }
        }

        private fun openBottomSheet(appConn: AppConnection) {
            if (context !is AppCompatActivity) {
                Log.w(Logger.LOG_TAG_UI, "Error opening the app conn bottom sheet")
                return
            }

            val bottomSheetFragment = AppDomainRulesBottomSheet()
            // Fix: free-form window crash
            // all BottomSheetDialogFragment classes created must have a public, no-arg constructor.
            // the best practice is to simply never define any constructors at all.
            // so sending the data using Bundles
            val bundle = Bundle()
            bundle.putInt(AppDomainRulesBottomSheet.UID, uid)
            bundle.putString(AppDomainRulesBottomSheet.DOMAIN, appConn.appOrDnsName)
            bottomSheetFragment.arguments = bundle
            bottomSheetFragment.dismissListener(adapter, absoluteAdapterPosition)
            bottomSheetFragment.show(context.supportFragmentManager, bottomSheetFragment.tag)
        }

        private fun beautifyIpString(d: String): String {
            // replace two commas in the string to one
            // add space after all the commas
            return removeBeginningTrailingCommas(d).replace(",,", ",").replace(",", ", ")
        }

        private fun updateStatusUi(uid: Int, domain: String?) {
            if (domain == null) {
                b.acdFlag.text = context.getString(R.string.ci_no_rule_initial)
                return
            }

            val status = DomainRulesManager.getDomainRule(domain, uid)
            when (status) {
                DomainRulesManager.Status.NONE -> {
                    b.acdFlag.text = context.getString(R.string.ci_no_rule_initial)
                }
                DomainRulesManager.Status.BLOCK -> {
                    b.acdFlag.text = context.getString(R.string.ci_blocked_initial)
                }
                DomainRulesManager.Status.TRUST -> {
                    b.acdFlag.text = context.getString(R.string.ci_trust_initial)
                }
            }

            // returns the text and background color for the button
            val t = getToggleBtnUiParams(status)
            b.acdFlag.setTextColor(t.txtColor)
            b.acdFlag.backgroundTintList = ColorStateList.valueOf(t.bgColor)
        }

        private fun getToggleBtnUiParams(id: DomainRulesManager.Status): ToggleBtnUi {
            return when (id) {
                DomainRulesManager.Status.NONE -> {
                    ToggleBtnUi(
                        fetchColor(context, R.attr.chipTextNeutral),
                        fetchColor(context, R.attr.chipBgColorNeutral)
                    )
                }
                DomainRulesManager.Status.BLOCK -> {
                    ToggleBtnUi(
                        fetchColor(context, R.attr.chipTextNegative),
                        fetchColor(context, R.attr.chipBgColorNegative)
                    )
                }
                DomainRulesManager.Status.TRUST -> {
                    ToggleBtnUi(
                        fetchColor(context, R.attr.chipTextPositive),
                        fetchColor(context, R.attr.chipBgColorPositive)
                    )
                }
            }
        }
    }

    override fun notifyDataset(position: Int) {
        this.notifyItemChanged(position)
    }
}
