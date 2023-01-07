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
package com.celzero.bravedns.ui

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.FirewallAppListAdapter
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.databinding.FragmentFirewallAppListBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.CustomLinearLayoutManager
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.AppInfoViewModel
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class FirewallAppFragment :
    Fragment(R.layout.fragment_firewall_app_list), SearchView.OnQueryTextListener {
    private val b by viewBinding(FragmentFirewallAppListBinding::bind)

    private val appInfoViewModel: AppInfoViewModel by viewModel()
    private val persistentState by inject<PersistentState>()
    private val refreshDatabase by inject<RefreshDatabase>()

    private var layoutManager: RecyclerView.LayoutManager? = null

    private lateinit var animation: Animation

    companion object {
        fun newInstance() = FirewallAppFragment()

        val filters = MutableLiveData<Filters>()

        private const val ANIMATION_DURATION = 750L
        private const val ANIMATION_REPEAT_COUNT = -1
        private const val ANIMATION_PIVOT_VALUE = 0.5f
        private const val ANIMATION_START_DEGREE = 0.0f
        private const val ANIMATION_END_DEGREE = 360.0f

        private const val REFRESH_TIMEOUT: Long = 4000
        private const val QUERY_TEXT_TIMEOUT: Long = 600
    }

    // enum class for bulk ui update
    enum class BlockType {
        UNMETER,
        METER,
        BYPASS,
        LOCKDOWN,
        EXCLUDE
    }

    enum class TopLevelFilter(val id: Int) {
        ALL(0),
        INSTALLED(1),
        SYSTEM(2);

        fun getLabel(context: Context): String {
            return when (this) {
                ALL -> {
                    // getLabel is used only to show the filtered details in ui,
                    // no need to show "all" tag.
                    ""
                }
                INSTALLED -> {
                    context.getString(R.string.fapps_filter_parent_installed)
                }
                SYSTEM -> {
                    context.getString(R.string.fapps_filter_parent_system)
                }
            }
        }
    }

    enum class FirewallFilter(val id: Int) {
        ALL(0),
        ALLOWED(1),
        BLOCKED(2),
        BYPASS_UNIVERSAL(3),
        EXCLUDED(4),
        LOCKDOWN(5);

        fun getFilter(): Set<Int> {
            return when (this) {
                ALL -> setOf(0, 1, 2, 3, 4, 5)
                ALLOWED -> setOf(0)
                BLOCKED -> setOf(1)
                BYPASS_UNIVERSAL -> setOf(2)
                EXCLUDED -> setOf(3)
                LOCKDOWN -> setOf(4)
            }
        }

        fun getLabel(context: Context): String {
            return when (this) {
                ALL -> context.getString(R.string.fapps_firewall_filter_all)
                ALLOWED -> context.getString(R.string.fapps_firewall_filter_allowed)
                BLOCKED -> context.getString(R.string.fapps_firewall_filter_blocked)
                BYPASS_UNIVERSAL ->
                    context.getString(R.string.fapps_firewall_filter_bypass_universal)
                EXCLUDED -> context.getString(R.string.fapps_firewall_filter_excluded)
                LOCKDOWN -> context.getString(R.string.fapps_firewall_filter_isolate)
            }
        }

        companion object {
            fun filter(id: Int): FirewallFilter {
                return when (id) {
                    ALL.id -> ALL
                    ALLOWED.id -> ALLOWED
                    BLOCKED.id -> BLOCKED
                    BYPASS_UNIVERSAL.id -> BYPASS_UNIVERSAL
                    EXCLUDED.id -> EXCLUDED
                    LOCKDOWN.id -> LOCKDOWN
                    else -> ALL
                }
            }
        }
    }

    class Filters {
        var categoryFilters: MutableSet<String> = mutableSetOf()
        var topLevelFilter = TopLevelFilter.ALL
        var firewallFilter = FirewallFilter.ALL
        var searchString: String = ""
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initObserver()
        setupClickListener()
    }

    override fun onResume() {
        super.onResume()
        checkVpnLockdownAndAllNetworks()
        setFirewallFilter(filters.value?.firewallFilter)
    }

    private fun checkVpnLockdownAndAllNetworks() {
        if (VpnController.isVpnLockdown()) {
            b.firewallAppLockdownHint.text = getString(R.string.fapps_lockdown_hint)
            b.firewallAppLockdownHint.visibility = View.VISIBLE
            return
        }

        if (persistentState.useMultipleNetworks) {
            b.firewallAppLockdownHint.text = getString(R.string.fapps_all_network_hint)
            b.firewallAppLockdownHint.visibility = View.VISIBLE
            return
        }

        b.firewallAppLockdownHint.visibility = View.GONE
    }

    private fun initObserver() {
        filters.observe(this.viewLifecycleOwner) {
            // update the ui based on the filter
            resetFirewallIcons(BlockType.UNMETER)

            if (it == null) return@observe

            ui {
                appInfoViewModel.setFilter(it)
                b.ffaAppList.smoothScrollToPosition(0)
                updateFilterText(it)
            }
        }
    }

    private fun updateFilterText(filter: Filters) {
        val filterLabel = filter.topLevelFilter.getLabel(requireContext())
        val firewallLabel = filter.firewallFilter.getLabel(requireContext())
        if (filter.categoryFilters.isEmpty()) {
            b.firewallAppLabelTv.text =
                Utilities.updateHtmlEncodedText(
                    getString(
                        R.string.fapps_firewall_filter_desc,
                        firewallLabel.lowercase(),
                        filterLabel
                    )
                )
        } else {
            b.firewallAppLabelTv.text =
                Utilities.updateHtmlEncodedText(
                    getString(
                        R.string.fapps_firewall_filter_desc_category,
                        firewallLabel.lowercase(),
                        filterLabel,
                        filter.categoryFilters
                    )
                )
        }
        b.firewallAppLabelTv.isSelected = true
    }

    override fun onDetach() {
        filters.postValue(Filters())
        super.onDetach()
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        addQueryToFilters(query)
        return true
    }

    override fun onQueryTextChange(query: String): Boolean {
        Utilities.delay(QUERY_TEXT_TIMEOUT, lifecycleScope) {
            if (isAdded) {
                addQueryToFilters(query)
            }
        }
        return true
    }

    private fun addQueryToFilters(query: String) {
        if (filters.value == null) {
            val f = Filters()
            f.searchString = query
            filters.postValue(f)
            return
        }

        filters.value?.searchString = query
        filters.postValue(filters.value)
    }

    private fun setupClickListener() {
        b.ffaFilterIcon.setOnClickListener { openFilterBottomSheet() }

        b.ffaRefreshList.setOnClickListener {
            b.ffaRefreshList.isEnabled = false
            b.ffaRefreshList.animation = animation
            b.ffaRefreshList.startAnimation(animation)
            refreshDatabase()
            Utilities.delay(REFRESH_TIMEOUT, lifecycleScope) {
                if (isAdded) {
                    b.ffaRefreshList.isEnabled = true
                    b.ffaRefreshList.clearAnimation()
                    Utilities.showToastUiCentered(
                        requireContext(),
                        getString(R.string.refresh_complete),
                        Toast.LENGTH_SHORT
                    )
                }
            }
        }

        b.ffaToggleAllWifi.setOnClickListener {
            showBulkRulesUpdateDialog(
                getBulkActionDialogTitle(BlockType.UNMETER),
                getBulkActionDialogMessage(BlockType.UNMETER),
                BlockType.UNMETER
            )
        }

        b.ffaToggleAllMobileData.setOnClickListener {
            showBulkRulesUpdateDialog(
                getBulkActionDialogTitle(BlockType.METER),
                getBulkActionDialogMessage(BlockType.METER),
                BlockType.METER
            )
        }

        b.ffaToggleAllLockdown.setOnClickListener {
            showBulkRulesUpdateDialog(
                getBulkActionDialogTitle(BlockType.LOCKDOWN),
                getBulkActionDialogMessage(BlockType.LOCKDOWN),
                BlockType.LOCKDOWN
            )
        }

        b.ffaToggleAllBypass.setOnClickListener {
            showBulkRulesUpdateDialog(
                getBulkActionDialogTitle(BlockType.BYPASS),
                getBulkActionDialogMessage(BlockType.BYPASS),
                BlockType.BYPASS
            )
        }

        b.ffaToggleAllExclude.setOnClickListener {
            showBulkRulesUpdateDialog(
                getBulkActionDialogTitle(BlockType.EXCLUDE),
                getBulkActionDialogMessage(BlockType.EXCLUDE),
                BlockType.EXCLUDE
            )
        }

        b.ffaAppInfoIcon.setOnClickListener { showInfoDialog() }
    }

    private fun getBulkActionDialogTitle(type: BlockType): String {
        return when (type) {
            BlockType.UNMETER -> {
                if (isInitTag(b.ffaToggleAllWifi)) {
                    getString(R.string.fapps_unmetered_block_dialog_title)
                } else {
                    getString(R.string.fapps_unmetered_unblock_dialog_title)
                }
            }
            BlockType.METER -> {
                if (isInitTag(b.ffaToggleAllMobileData)) {
                    getString(R.string.fapps_metered_block_dialog_title)
                } else {
                    getString(R.string.fapps_metered_unblock_dialog_title)
                }
            }
            BlockType.LOCKDOWN -> {
                if (isInitTag(b.ffaToggleAllLockdown)) {
                    getString(R.string.fapps_isolate_block_dialog_title)
                } else {
                    getString(R.string.fapps_unblock_dialog_title)
                }
            }
            BlockType.BYPASS -> {
                if (isInitTag(b.ffaToggleAllBypass)) {
                    getString(R.string.fapps_bypass_block_dialog_title)
                } else {
                    getString(R.string.fapps_unblock_dialog_title)
                }
            }
            BlockType.EXCLUDE -> {
                if (isInitTag(b.ffaToggleAllExclude)) {
                    getString(R.string.fapps_exclude_block_dialog_title)
                } else {
                    getString(R.string.fapps_unblock_dialog_title)
                }
            }
        }
    }

    private fun getBulkActionDialogMessage(type: BlockType): String {
        return when (type) {
            BlockType.UNMETER -> {
                if (isInitTag(b.ffaToggleAllWifi)) {
                    getString(R.string.fapps_unmetered_block_dialog_message)
                } else {
                    getString(R.string.fapps_unmetered_unblock_dialog_message)
                }
            }
            BlockType.METER -> {
                if (isInitTag(b.ffaToggleAllMobileData)) {
                    getString(R.string.fapps_metered_block_dialog_message)
                } else {
                    getString(R.string.fapps_metered_unblock_dialog_message)
                }
            }
            BlockType.LOCKDOWN -> {
                if (isInitTag(b.ffaToggleAllLockdown)) {
                    getString(R.string.fapps_isolate_block_dialog_message)
                } else {
                    getString(R.string.fapps_unblock_dialog_message)
                }
            }
            BlockType.BYPASS -> {
                if (isInitTag(b.ffaToggleAllBypass)) {
                    getString(R.string.fapps_bypass_block_dialog_message)
                } else {
                    getString(R.string.fapps_unblock_dialog_message)
                }
            }
            BlockType.EXCLUDE -> {
                if (isInitTag(b.ffaToggleAllExclude)) {
                    getString(R.string.fapps_exclude_block_dialog_message)
                } else {
                    getString(R.string.fapps_unblock_dialog_message)
                }
            }
        }
    }

    private fun showBulkRulesUpdateDialog(title: String, message: String, type: BlockType) {
        val builder =
            AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(getString(R.string.fapps_unmetered_positive)) { _, _ ->
                    updateBulkRules(type)
                }
                .setNegativeButton(getString(R.string.fapps_unmetered_negative)) { _, _ -> }
                .setCancelable(true)

        builder.create().show()
    }

    private fun updateBulkRules(type: BlockType) {
        when (type) {
            BlockType.UNMETER -> {
                updateUnmeteredBulk()
            }
            BlockType.METER -> {
                updateMeteredBulk()
            }
            BlockType.LOCKDOWN -> {
                updateLockdownBulk()
            }
            BlockType.BYPASS -> {
                updateBypassBulk()
            }
            BlockType.EXCLUDE -> {
                updateExcludedBulk()
            }
        }
    }

    private fun showInfoDialog() {
        val li = requireActivity().getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view: View = li.inflate(R.layout.dialog_info_firewall_rules, null)
        val builder = AlertDialog.Builder(requireContext())
            .setView(view)
        builder.setPositiveButton(getString(R.string.fapps_info_dialog_positive_btn)) { dialog, _ ->
            dialog.dismiss()
        }
        builder.setCancelable(true)
        builder.create().show()
    }

    private fun setFirewallFilter(firewallFilter: FirewallFilter?) {
        if (firewallFilter == null) return

        val view: Chip = b.ffaFirewallChipGroup.findViewWithTag(firewallFilter.id)
        b.ffaFirewallChipGroup.check(view.id)
        colorUpChipIcon(view)
    }

    private fun remakeFirewallChipsUi() {
        b.ffaFirewallChipGroup.removeAllViews()

        val none =
            makeFirewallChip(
                FirewallFilter.ALL.id,
                getString(R.string.fapps_firewall_filter_all),
                true
            )
        val allowed =
            makeFirewallChip(
                FirewallFilter.ALLOWED.id,
                getString(R.string.fapps_firewall_filter_allowed),
                false
            )
        val blocked =
            makeFirewallChip(
                FirewallFilter.BLOCKED.id,
                getString(R.string.fapps_firewall_filter_blocked),
                false
            )
        val bypassUniversal =
            makeFirewallChip(
                FirewallFilter.BYPASS_UNIVERSAL.id,
                getString(R.string.fapps_firewall_filter_bypass_universal),
                false
            )
        val excluded =
            makeFirewallChip(
                FirewallFilter.EXCLUDED.id,
                getString(R.string.fapps_firewall_filter_excluded),
                false
            )
        val lockdown =
            makeFirewallChip(
                FirewallFilter.LOCKDOWN.id,
                getString(R.string.fapps_firewall_filter_isolate),
                false
            )

        b.ffaFirewallChipGroup.addView(none)
        b.ffaFirewallChipGroup.addView(allowed)
        b.ffaFirewallChipGroup.addView(blocked)
        b.ffaFirewallChipGroup.addView(bypassUniversal)
        b.ffaFirewallChipGroup.addView(excluded)
        b.ffaFirewallChipGroup.addView(lockdown)
    }

    private fun makeFirewallChip(id: Int, label: String, checked: Boolean): Chip {
        val chip = this.layoutInflater.inflate(R.layout.item_chip_filter, b.root, false) as Chip
        chip.tag = id
        chip.text = label
        chip.isChecked = checked

        chip.setOnCheckedChangeListener { button: CompoundButton, isSelected: Boolean ->
            if (isSelected) {
                applyFirewallFilter(button.tag)
                colorUpChipIcon(chip)
            } else {
                // no-op
                // no action needed for checkState: false
            }
        }

        return chip
    }

    private fun applyFirewallFilter(tag: Any) {
        val firewallFilter = FirewallFilter.filter(tag as Int)
        if (filters.value == null) {
            val f = Filters()
            f.firewallFilter = firewallFilter
            filters.postValue(f)
            return
        }

        filters.value?.firewallFilter = firewallFilter
        filters.postValue(filters.value)
    }

    private fun colorUpChipIcon(chip: Chip) {
        val colorFilter =
            PorterDuffColorFilter(
                ContextCompat.getColor(requireContext(), R.color.primaryText),
                PorterDuff.Mode.SRC_IN
            )
        chip.checkedIcon?.colorFilter = colorFilter
        chip.chipIcon?.colorFilter = colorFilter
    }

    private fun resetFirewallIcons(type: BlockType) {
        // reset all icons to default state based on selection
        when (type) {
            BlockType.UNMETER -> {
                b.ffaToggleAllMobileData.setImageResource(R.drawable.ic_firewall_data_on_grey)
                b.ffaToggleAllExclude.setImageResource(R.drawable.ic_firewall_exclude_off)
                b.ffaToggleAllBypass.setImageResource(R.drawable.ic_firewall_bypass_off)
                b.ffaToggleAllLockdown.setImageResource(R.drawable.ic_firewall_lockdown_off)
            }
            BlockType.METER -> {
                b.ffaToggleAllWifi.setImageResource(R.drawable.ic_firewall_wifi_on_grey)
                b.ffaToggleAllExclude.setImageResource(R.drawable.ic_firewall_exclude_off)
                b.ffaToggleAllBypass.setImageResource(R.drawable.ic_firewall_bypass_off)
                b.ffaToggleAllLockdown.setImageResource(R.drawable.ic_firewall_lockdown_off)
            }
            BlockType.LOCKDOWN -> {
                b.ffaToggleAllMobileData.setImageResource(R.drawable.ic_firewall_data_on_grey)
                b.ffaToggleAllWifi.setImageResource(R.drawable.ic_firewall_wifi_on_grey)
                b.ffaToggleAllExclude.setImageResource(R.drawable.ic_firewall_exclude_off)
                b.ffaToggleAllBypass.setImageResource(R.drawable.ic_firewall_bypass_off)
            }
            BlockType.BYPASS -> {
                b.ffaToggleAllMobileData.setImageResource(R.drawable.ic_firewall_data_on_grey)
                b.ffaToggleAllWifi.setImageResource(R.drawable.ic_firewall_wifi_on_grey)
                b.ffaToggleAllExclude.setImageResource(R.drawable.ic_firewall_exclude_off)
                b.ffaToggleAllLockdown.setImageResource(R.drawable.ic_firewall_lockdown_off)
            }
            BlockType.EXCLUDE -> {
                b.ffaToggleAllMobileData.setImageResource(R.drawable.ic_firewall_data_on_grey)
                b.ffaToggleAllWifi.setImageResource(R.drawable.ic_firewall_wifi_on_grey)
                b.ffaToggleAllBypass.setImageResource(R.drawable.ic_firewall_bypass_off)
                b.ffaToggleAllLockdown.setImageResource(R.drawable.ic_firewall_lockdown_off)
            }
        }
    }

    private fun updateMeteredBulk() {
        if (isInitTag(b.ffaToggleAllMobileData)) {
            b.ffaToggleAllMobileData.tag = 1
            b.ffaToggleAllMobileData.setImageResource(R.drawable.ic_firewall_data_off)
            io { appInfoViewModel.updateMeteredStatus(true) }
        } else {
            b.ffaToggleAllMobileData.tag = 0
            b.ffaToggleAllMobileData.setImageResource(R.drawable.ic_firewall_data_on)
            io { appInfoViewModel.updateMeteredStatus(false) }
        }
        resetFirewallIcons(BlockType.METER)
    }

    private fun updateUnmeteredBulk() {
        if (isInitTag(b.ffaToggleAllWifi)) {
            b.ffaToggleAllWifi.tag = 1
            b.ffaToggleAllWifi.setImageResource(R.drawable.ic_firewall_wifi_off)
            io { appInfoViewModel.updateUnmeteredStatus(true) }
        } else {
            b.ffaToggleAllWifi.tag = 0
            b.ffaToggleAllWifi.setImageResource(R.drawable.ic_firewall_wifi_on)
            io { appInfoViewModel.updateUnmeteredStatus(false) }
        }
        resetFirewallIcons(BlockType.UNMETER)
    }

    private fun updateBypassBulk() {
        if (isInitTag(b.ffaToggleAllBypass)) {
            b.ffaToggleAllBypass.tag = 1
            b.ffaToggleAllBypass.setImageResource(R.drawable.ic_firewall_bypass_on)
            io { appInfoViewModel.updateBypassStatus(true) }
        } else {
            b.ffaToggleAllBypass.tag = 0
            b.ffaToggleAllBypass.setImageResource(R.drawable.ic_firewall_bypass_off)
            io { appInfoViewModel.updateBypassStatus(false) }
        }
        resetFirewallIcons(BlockType.BYPASS)
    }

    private fun updateExcludedBulk() {
        if (isInitTag(b.ffaToggleAllExclude)) {
            b.ffaToggleAllExclude.tag = 1
            b.ffaToggleAllExclude.setImageResource(R.drawable.ic_firewall_exclude_on)
            io { appInfoViewModel.updateExcludeStatus(true) }
        } else {
            b.ffaToggleAllExclude.tag = 0
            b.ffaToggleAllExclude.setImageResource(R.drawable.ic_firewall_exclude_off)
            io { appInfoViewModel.updateExcludeStatus(false) }
        }
        resetFirewallIcons(BlockType.EXCLUDE)
    }

    private fun updateLockdownBulk() {
        if (isInitTag(b.ffaToggleAllLockdown)) {
            b.ffaToggleAllLockdown.tag = 1
            b.ffaToggleAllLockdown.setImageResource(R.drawable.ic_firewall_lockdown_on)
            io { appInfoViewModel.updateLockdownStatus(true) }
        } else {
            b.ffaToggleAllLockdown.tag = 0
            b.ffaToggleAllLockdown.setImageResource(R.drawable.ic_firewall_lockdown_off)
            io { appInfoViewModel.updateLockdownStatus(false) }
        }
        resetFirewallIcons(BlockType.LOCKDOWN)
    }

    private fun isInitTag(view: View): Boolean {
        return view.tag.equals("0") || view.tag == 0
    }

    private fun initView() {
        initListAdapter()
        b.ffaSearch.setOnQueryTextListener(this)
        addAnimation()
        remakeFirewallChipsUi()
    }

    private fun initListAdapter() {
        b.ffaAppList.setHasFixedSize(true)
        layoutManager = CustomLinearLayoutManager(requireContext())
        b.ffaAppList.layoutManager = layoutManager
        val recyclerAdapter = FirewallAppListAdapter(requireContext(), viewLifecycleOwner)
        appInfoViewModel.appInfo.observe(viewLifecycleOwner) {
            recyclerAdapter.submitData(viewLifecycleOwner.lifecycle, it)
        }
        b.ffaAppList.adapter = recyclerAdapter
    }

    private fun openFilterBottomSheet() {
        val bottomSheetFragment = FirewallAppFilterBottomSheet()
        bottomSheetFragment.show(parentFragmentManager, bottomSheetFragment.tag)
    }

    private fun addAnimation() {
        animation =
            RotateAnimation(
                ANIMATION_START_DEGREE,
                ANIMATION_END_DEGREE,
                Animation.RELATIVE_TO_SELF,
                ANIMATION_PIVOT_VALUE,
                Animation.RELATIVE_TO_SELF,
                ANIMATION_PIVOT_VALUE
            )
        animation.repeatCount = ANIMATION_REPEAT_COUNT
        animation.duration = ANIMATION_DURATION
    }

    private fun refreshDatabase() {
        io { refreshDatabase.refreshAppInfoDatabase() }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch { withContext(Dispatchers.IO) { f() } }
    }

    private fun ui(f: () -> Unit) {
        lifecycleScope.launch(Dispatchers.Main) { f() }
    }
}
