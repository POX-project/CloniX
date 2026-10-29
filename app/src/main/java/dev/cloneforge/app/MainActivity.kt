package dev.cloneforge.app

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import dev.cloneforge.patcher.installer.RootHelper
import dev.cloneforge.patcher.installer.ShizukuInstaller
import rikka.shizuku.Shizuku
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Modern Material 3 Main Activity featuring:
 *  - Bottom Navigation Bar with 3 tabs: Apps, Clones, Settings
 *  - Multi-selection, Select All / Deselect All, and automated batch actions on both lists
 *  - Unlimited cloning with quick chips and number steppers
 *  - Per-instance launcher icon hue shifting and badge overlay
 *  - Silent root installation option using Magisk su
 *  - Silent Shizuku / ADB shell installation option (no root required)
 *  - Comprehensive app preferences and cache cleaning
 */
class MainActivity : AppCompatActivity() {

    private data class PendingCloneRequest(
        val app: AppItem?,
        val targetPackages: List<String>,
        val labelPrefix: String?,
        val cloneCount: Int,
        val addNumberName: Boolean,
        val addNumberIcon: Boolean,
        val hueEnabled: Boolean,
        val rootInstall: Boolean,
        val shizukuInstall: Boolean,
        val silent: Boolean
    )

    private val history by lazy { CloneHistoryStore(this) }
    private val prefs by lazy { PreferencesManager(this) }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var bottomNav: BottomNavigationView

    // Tab Views
    private lateinit var tabAppsView: View
    private lateinit var tabClonesView: View
    private lateinit var tabSettingsView: View

    // Tab 1: Apps views
    private lateinit var appAdapter: AppAdapter
    private lateinit var searchInput: TextInputEditText
    private lateinit var includeSystemSwitch: MaterialSwitch
    private lateinit var appRecycler: RecyclerView
    private lateinit var appEmptyView: TextView
    private lateinit var appLoadingView: ProgressBar
    private lateinit var appCountBadge: TextView
    private lateinit var btnAppsSelectMode: MaterialButton
    private lateinit var cardAppsSelectionBar: View
    private lateinit var btnAppsSelectionClose: View
    private lateinit var tvAppsSelectionCount: TextView
    private lateinit var btnAppsSelectAll: MaterialButton
    private lateinit var btnAppsBatchClone: MaterialButton

    // Tab 2: Clones views
    private lateinit var cloneAdapter: CloneAdapter
    private lateinit var cloneRecycler: RecyclerView
    private lateinit var cloneEmptyView: View
    private lateinit var cloneCountBadge: TextView
    private lateinit var btnEmptyBrowseApps: MaterialButton
    private lateinit var btnClonesSelectMode: MaterialButton
    private lateinit var cardClonesSelectionBar: View
    private lateinit var btnClonesSelectionClose: View
    private lateinit var tvClonesSelectionCount: TextView
    private lateinit var btnClonesSelectAll: MaterialButton
    private lateinit var btnClonesBatchInstall: MaterialButton
    private lateinit var btnClonesBatchUninstall: MaterialButton
    private lateinit var btnClonesBatchRemove: MaterialButton

    // Tab 3: Settings views
    private lateinit var rowTheme: View
    private lateinit var tvThemeSummary: TextView
    private lateinit var switchPrefHue: MaterialSwitch
    private lateinit var switchPrefBadge: MaterialSwitch
    private lateinit var switchPrefShizuku: MaterialSwitch
    private lateinit var rowShizukuStatus: View
    private lateinit var ivShizukuStatusIcon: ImageView
    private lateinit var tvShizukuStatus: TextView
    private lateinit var switchPrefRoot: MaterialSwitch
    private lateinit var ivRootStatusIcon: ImageView
    private lateinit var tvRootStatus: TextView
    private lateinit var rowClearCache: View
    private lateinit var tvCacheSummary: TextView

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == 1001) {
            runOnUiThread {
                updateSettingsUi()
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Shizuku authorization granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Shizuku authorization denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private var allApps: List<AppItem> = emptyList()
    private var pendingRequest: PendingCloneRequest? = null

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            pendingRequest?.let { request ->
                pendingRequest = null
                startCloneService(request)
            }
        }

    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { updateSettingsUi() }
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread { updateSettingsUi() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs.applyTheme()
        setContentView(R.layout.activity_main)

        initViews()
        setupBottomNav()
        setupAppsTab()
        setupClonesTab()
        setupSettingsTab()
        setupBackPressHandler()

        loadInstalledApps()
        collectCloneBus()
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
        Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (appAdapter.isSelectionMode) {
                    exitAppsSelectionMode()
                    return
                }
                if (cloneAdapter.isSelectionMode) {
                    exitClonesSelectionMode()
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar_main)
        bottomNav = findViewById(R.id.bottom_navigation)

        tabAppsView = findViewById(R.id.tab_apps_view)
        tabClonesView = findViewById(R.id.tab_clones_view)
        tabSettingsView = findViewById(R.id.tab_settings_view)

        // Apps tab views
        searchInput = tabAppsView.findViewById(R.id.input_search)
        includeSystemSwitch = tabAppsView.findViewById(R.id.switch_include_system)
        appRecycler = tabAppsView.findViewById(R.id.recycler_apps)
        appEmptyView = tabAppsView.findViewById(R.id.tv_empty_list)
        appLoadingView = tabAppsView.findViewById(R.id.progress_loading)
        appCountBadge = tabAppsView.findViewById(R.id.tv_app_count_badge)
        btnAppsSelectMode = tabAppsView.findViewById(R.id.btn_apps_select_mode)
        cardAppsSelectionBar = tabAppsView.findViewById(R.id.card_apps_selection_bar)
        btnAppsSelectionClose = tabAppsView.findViewById(R.id.btn_apps_selection_close)
        tvAppsSelectionCount = tabAppsView.findViewById(R.id.tv_apps_selection_count)
        btnAppsSelectAll = tabAppsView.findViewById(R.id.btn_apps_select_all)
        btnAppsBatchClone = tabAppsView.findViewById(R.id.btn_apps_batch_clone)

        // Clones tab views
        cloneRecycler = tabClonesView.findViewById(R.id.recycler_clones)
        cloneEmptyView = tabClonesView.findViewById(R.id.ll_clones_empty)
        cloneCountBadge = tabClonesView.findViewById(R.id.tv_clones_count_badge)
        btnEmptyBrowseApps = tabClonesView.findViewById(R.id.btn_empty_browse_apps)
        btnClonesSelectMode = tabClonesView.findViewById(R.id.btn_clones_select_mode)
        cardClonesSelectionBar = tabClonesView.findViewById(R.id.card_clones_selection_bar)
        btnClonesSelectionClose = tabClonesView.findViewById(R.id.btn_clones_selection_close)
        tvClonesSelectionCount = tabClonesView.findViewById(R.id.tv_clones_selection_count)
        btnClonesSelectAll = tabClonesView.findViewById(R.id.btn_clones_select_all)
        btnClonesBatchInstall = tabClonesView.findViewById(R.id.btn_clones_batch_install)
        btnClonesBatchUninstall = tabClonesView.findViewById(R.id.btn_clones_batch_uninstall)
        btnClonesBatchRemove = tabClonesView.findViewById(R.id.btn_clones_batch_remove)

        // Settings tab views
        rowTheme = tabSettingsView.findViewById(R.id.row_setting_theme)
        tvThemeSummary = tabSettingsView.findViewById(R.id.tv_theme_summary)
        switchPrefHue = tabSettingsView.findViewById(R.id.switch_pref_hue)
        switchPrefBadge = tabSettingsView.findViewById(R.id.switch_pref_badge)
        switchPrefShizuku = tabSettingsView.findViewById(R.id.switch_pref_shizuku)
        rowShizukuStatus = tabSettingsView.findViewById(R.id.row_shizuku_status)
        ivShizukuStatusIcon = tabSettingsView.findViewById(R.id.iv_shizuku_status_icon)
        tvShizukuStatus = tabSettingsView.findViewById(R.id.tv_shizuku_status)
        switchPrefRoot = tabSettingsView.findViewById(R.id.switch_pref_root)
        ivRootStatusIcon = tabSettingsView.findViewById(R.id.iv_root_status_icon)
        tvRootStatus = tabSettingsView.findViewById(R.id.tv_root_status)
        rowClearCache = tabSettingsView.findViewById(R.id.row_clear_cache)
        tvCacheSummary = tabSettingsView.findViewById(R.id.tv_cache_summary)
    }

    private fun setupBottomNav() {
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_apps -> {
                    switchTab(0)
                    true
                }
                R.id.nav_clones -> {
                    switchTab(1)
                    true
                }
                R.id.nav_settings -> {
                    switchTab(2)
                    true
                }
                else -> false
            }
        }
    }

    private fun switchTab(tabIndex: Int) {
        if (tabIndex != 0 && appAdapter.isSelectionMode) exitAppsSelectionMode()
        if (tabIndex != 1 && cloneAdapter.isSelectionMode) exitClonesSelectionMode()

        tabAppsView.isVisible = tabIndex == 0
        tabClonesView.isVisible = tabIndex == 1
        tabSettingsView.isVisible = tabIndex == 2

        when (tabIndex) {
            0 -> toolbar.title = getString(R.string.app_title)
            1 -> {
                toolbar.title = getString(R.string.title_your_clones)
                renderClones()
            }
            2 -> {
                toolbar.title = getString(R.string.title_settings)
                updateSettingsUi()
            }
        }
    }

    private fun setupAppsTab() {
        appAdapter = AppAdapter(
            onClick = ::showCloneSheet,
            onSelectionChanged = ::onAppsSelectionChanged
        )
        appRecycler.layoutManager = LinearLayoutManager(this)
        appRecycler.adapter = appAdapter

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })
        includeSystemSwitch.setOnCheckedChangeListener { _, _ -> applyFilter() }

        btnAppsSelectMode.setOnClickListener {
            appAdapter.setSelectionMode(true)
        }

        btnAppsSelectionClose.setOnClickListener {
            exitAppsSelectionMode()
        }

        btnAppsSelectAll.setOnClickListener {
            if (appAdapter.selectedPackages.size == appAdapter.itemCount && appAdapter.itemCount > 0) {
                appAdapter.deselectAll()
            } else {
                appAdapter.selectAll()
            }
        }

        btnAppsBatchClone.setOnClickListener {
            val selected = appAdapter.getSelectedItems()
            if (selected.isNotEmpty()) {
                showBatchCloneSheet(selected)
            }
        }
    }

    private fun onAppsSelectionChanged(count: Int) {
        if (appAdapter.isSelectionMode) {
            cardAppsSelectionBar.isVisible = true
            btnAppsSelectMode.isVisible = false
            tvAppsSelectionCount.text = getString(R.string.selected_count_format, count)
            btnAppsBatchClone.isEnabled = count > 0
            btnAppsBatchClone.text = if (count > 0) "Clone ($count)" else "Clone"
            val total = appAdapter.itemCount
            btnAppsSelectAll.text = if (count == total && total > 0) {
                getString(R.string.action_deselect_all)
            } else {
                getString(R.string.action_select_all)
            }
        } else {
            cardAppsSelectionBar.isVisible = false
            btnAppsSelectMode.isVisible = true
        }
    }

    private fun exitAppsSelectionMode() {
        appAdapter.setSelectionMode(false)
        cardAppsSelectionBar.isVisible = false
        btnAppsSelectMode.isVisible = true
    }

    private fun setupClonesTab() {
        cloneAdapter = CloneAdapter(
            onOpenClick = ::openClone,
            onInstallClick = ::installCloneApk,
            onUninstallClick = ::confirmUninstall,
            onSelectionChanged = ::onClonesSelectionChanged
        )
        cloneRecycler.layoutManager = LinearLayoutManager(this)
        cloneRecycler.adapter = cloneAdapter

        btnEmptyBrowseApps.setOnClickListener {
            bottomNav.selectedItemId = R.id.nav_apps
        }

        btnClonesSelectMode.setOnClickListener {
            cloneAdapter.setSelectionMode(true)
        }

        btnClonesSelectionClose.setOnClickListener {
            exitClonesSelectionMode()
        }

        btnClonesSelectAll.setOnClickListener {
            if (cloneAdapter.selectedClonePackages.size == cloneAdapter.itemCount && cloneAdapter.itemCount > 0) {
                cloneAdapter.deselectAll()
            } else {
                cloneAdapter.selectAll()
            }
        }

        btnClonesBatchInstall.setOnClickListener {
            batchInstallSelectedClones()
        }

        btnClonesBatchUninstall.setOnClickListener {
            batchUninstallSelectedClones()
        }

        btnClonesBatchRemove.setOnClickListener {
            batchRemoveSelectedClones()
        }
    }

    private fun onClonesSelectionChanged(count: Int) {
        if (cloneAdapter.isSelectionMode) {
            cardClonesSelectionBar.isVisible = true
            btnClonesSelectMode.isVisible = false
            tvClonesSelectionCount.text = getString(R.string.selected_count_format, count)
            val total = cloneAdapter.itemCount
            btnClonesSelectAll.text = if (count == total && total > 0) {
                getString(R.string.action_deselect_all)
            } else {
                getString(R.string.action_select_all)
            }

            val selected = cloneAdapter.getSelectedItems()
            val countInstallable = selected.count { !it.isInstalled && it.entry.apkPath.isNotBlank() && File(it.entry.apkPath).exists() }
            val countInstalled = selected.count { it.isInstalled }

            btnClonesBatchInstall.isEnabled = countInstallable > 0
            btnClonesBatchInstall.text = if (countInstallable > 0) "Install ($countInstallable)" else "Install"

            btnClonesBatchUninstall.isEnabled = countInstalled > 0
            btnClonesBatchUninstall.text = if (countInstalled > 0) "Uninstall ($countInstalled)" else "Uninstall"

            btnClonesBatchRemove.isEnabled = count > 0
            btnClonesBatchRemove.text = if (count > 0) "Remove ($count)" else "Remove"
        } else {
            cardClonesSelectionBar.isVisible = false
            btnClonesSelectMode.isVisible = true
        }
    }

    private fun exitClonesSelectionMode() {
        cloneAdapter.setSelectionMode(false)
        cardClonesSelectionBar.isVisible = false
        btnClonesSelectMode.isVisible = true
    }

    private fun batchInstallSelectedClones() {
        val selected = cloneAdapter.getSelectedItems()
        val installable = selected.filter { !it.isInstalled && it.entry.apkPath.isNotBlank() && File(it.entry.apkPath).exists() }
        if (installable.isEmpty()) {
            Toast.makeText(this, "None of the selected clones have an installable APK", Toast.LENGTH_SHORT).show()
            return
        }

        val hasRoot = RootHelper.isRootAvailable()
        val hasShizuku = ShizukuInstaller.isShizukuPermissionGranted()

        if (prefs.isRootInstallEnabled && hasRoot) {
            Toast.makeText(this, "Silently installing ${installable.size} clones via root…", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch(Dispatchers.IO) {
                var successCount = 0
                for (item in installable) {
                    val ok = RootHelper.installApk(File(item.entry.apkPath))
                    if (ok) successCount++
                }
                withContext(Dispatchers.Main) {
                    exitClonesSelectionMode()
                    renderClones()
                    Toast.makeText(this@MainActivity, "Installed $successCount of ${installable.size} clones", Toast.LENGTH_SHORT).show()
                }
            }
        } else if (prefs.isShizukuInstallEnabled && hasShizuku) {
            Toast.makeText(this, "Silently installing ${installable.size} clones via Shizuku…", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch(Dispatchers.IO) {
                var successCount = 0
                for (item in installable) {
                    val ok = ShizukuInstaller.installApk(File(item.entry.apkPath))
                    if (ok) successCount++
                }
                withContext(Dispatchers.Main) {
                    exitClonesSelectionMode()
                    renderClones()
                    Toast.makeText(this@MainActivity, "Installed $successCount of ${installable.size} clones via Shizuku", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            exitClonesSelectionMode()
            for (item in installable) {
                installCloneApk(item.entry)
            }
        }
    }

    private fun batchUninstallSelectedClones() {
        val selected = cloneAdapter.getSelectedItems()
        val installed = selected.filter { it.isInstalled }
        if (installed.isEmpty()) {
            Toast.makeText(this, "None of the selected clones are currently installed", Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_batch_uninstall_title)
            .setMessage(getString(R.string.confirm_batch_uninstall_msg, installed.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.button_uninstall) { _, _ ->
                val hasRoot = RootHelper.isRootAvailable()
                val hasShizuku = ShizukuInstaller.isShizukuPermissionGranted()
                if (prefs.isRootInstallEnabled && hasRoot) {
                    Toast.makeText(this, "Silently uninstalling ${installed.size} clones via root…", Toast.LENGTH_SHORT).show()
                    lifecycleScope.launch(Dispatchers.IO) {
                        var successCount = 0
                        for (item in installed) {
                            val ok = RootHelper.uninstallPackage(item.entry.clonePackage)
                            if (ok) {
                                history.removeClone(item.entry.clonePackage)
                                successCount++
                            }
                        }
                        withContext(Dispatchers.Main) {
                            exitClonesSelectionMode()
                            renderClones()
                            Toast.makeText(this@MainActivity, "Uninstalled $successCount of ${installed.size} clones", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else if (prefs.isShizukuInstallEnabled && hasShizuku) {
                    Toast.makeText(this, "Silently uninstalling ${installed.size} clones via Shizuku…", Toast.LENGTH_SHORT).show()
                    lifecycleScope.launch(Dispatchers.IO) {
                        var successCount = 0
                        for (item in installed) {
                            val ok = ShizukuInstaller.uninstallPackage(item.entry.clonePackage)
                            if (ok) {
                                history.removeClone(item.entry.clonePackage)
                                successCount++
                            }
                        }
                        withContext(Dispatchers.Main) {
                            exitClonesSelectionMode()
                            renderClones()
                            Toast.makeText(this@MainActivity, "Uninstalled $successCount of ${installed.size} clones via Shizuku", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    exitClonesSelectionMode()
                    for (item in installed) {
                        startSystemUninstaller(item.entry.clonePackage)
                    }
                }
            }
            .show()
    }

    private fun batchRemoveSelectedClones() {
        val selected = cloneAdapter.getSelectedItems()
        if (selected.isEmpty()) return

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_batch_remove_title)
            .setMessage(getString(R.string.confirm_batch_remove_msg, selected.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.button_uninstall) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    for (item in selected) {
                        history.removeClone(item.entry.clonePackage)
                        if (item.entry.apkPath.isNotBlank()) {
                            File(item.entry.apkPath).parentFile?.deleteRecursively()
                        }
                    }
                    withContext(Dispatchers.Main) {
                        exitClonesSelectionMode()
                        renderClones()
                        Toast.makeText(this@MainActivity, "Removed ${selected.size} clones from list", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun setupSettingsTab() {
        updateSettingsUi()

        rowTheme.setOnClickListener { showThemeDialog() }

        switchPrefHue.setOnCheckedChangeListener { _, isChecked ->
            prefs.isAutoHueEnabled = isChecked
        }
        switchPrefBadge.setOnCheckedChangeListener { _, isChecked ->
            prefs.isBadgeIconsEnabled = isChecked
        }
        switchPrefShizuku.setOnCheckedChangeListener { _, isChecked ->
            prefs.isShizukuInstallEnabled = isChecked
        }
        rowShizukuStatus.setOnClickListener {
            if (ShizukuInstaller.isShizukuAvailable() && !ShizukuInstaller.isShizukuPermissionGranted()) {
                ShizukuInstaller.requestPermission(1001)
            } else if (!ShizukuInstaller.isShizukuAvailable()) {
                Toast.makeText(this, R.string.shizuku_status_not_running, Toast.LENGTH_SHORT).show()
            }
        }
        switchPrefRoot.setOnCheckedChangeListener { _, isChecked ->
            prefs.isRootInstallEnabled = isChecked
        }

        rowClearCache.setOnClickListener { clearStagingCache() }
    }

    private fun updateSettingsUi() {
        tvThemeSummary.text = when (prefs.themeMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> getString(R.string.theme_light)
            AppCompatDelegate.MODE_NIGHT_YES -> getString(R.string.theme_dark)
            else -> getString(R.string.theme_system)
        }

        switchPrefHue.isChecked = prefs.isAutoHueEnabled
        switchPrefBadge.isChecked = prefs.isBadgeIconsEnabled

        val hasShizukuRunning = ShizukuInstaller.isShizukuAvailable()
        val hasShizukuGranted = ShizukuInstaller.isShizukuPermissionGranted()
        when {
            hasShizukuGranted -> {
                tvShizukuStatus.text = getString(R.string.shizuku_status_authorized)
                tvShizukuStatus.setTextColor(Color.parseColor("#4CAF50"))
                ivShizukuStatusIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                switchPrefShizuku.isEnabled = true
                switchPrefShizuku.isChecked = prefs.isShizukuInstallEnabled
            }
            hasShizukuRunning -> {
                tvShizukuStatus.text = getString(R.string.shizuku_status_permission_needed)
                tvShizukuStatus.setTextColor(Color.parseColor("#FF9800"))
                ivShizukuStatusIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#FF9800"))
                switchPrefShizuku.isEnabled = false
                switchPrefShizuku.isChecked = false
            }
            else -> {
                tvShizukuStatus.text = getString(R.string.shizuku_status_not_running)
                tvShizukuStatus.setTextColor(Color.GRAY)
                ivShizukuStatusIcon.imageTintList = ColorStateList.valueOf(Color.GRAY)
                switchPrefShizuku.isEnabled = false
                switchPrefShizuku.isChecked = false
            }
        }

        switchPrefRoot.isChecked = prefs.isRootInstallEnabled

        val hasRoot = RootHelper.isRootAvailable()
        if (hasRoot) {
            tvRootStatus.text = getString(R.string.root_status_granted)
            tvRootStatus.setTextColor(Color.parseColor("#4CAF50"))
            ivRootStatusIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
            switchPrefRoot.isEnabled = true
        } else {
            tvRootStatus.text = getString(R.string.root_status_not_granted)
            tvRootStatus.setTextColor(Color.GRAY)
            ivRootStatusIcon.imageTintList = ColorStateList.valueOf(Color.GRAY)
            switchPrefRoot.isEnabled = false
            switchPrefRoot.isChecked = false
        }

        updateCacheSummary()
    }

    private fun showThemeDialog() {
        val themes = arrayOf(
            getString(R.string.theme_system),
            getString(R.string.theme_light),
            getString(R.string.theme_dark)
        )
        val currentIdx = when (prefs.themeMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> 1
            AppCompatDelegate.MODE_NIGHT_YES -> 2
            else -> 0
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pref_theme)
            .setSingleChoiceItems(themes, currentIdx) { dialog, which ->
                prefs.themeMode = when (which) {
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                tvThemeSummary.text = themes[which]
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateCacheSummary() {
        lifecycleScope.launch(Dispatchers.IO) {
            val stagingDir = File(filesDir, "cloneforge/staging")
            val cacheDir = cacheDir
            val sizeBytes = (stagingDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }) +
                    (cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() })
            val mb = sizeBytes / (1024.0 * 1024.0)
            val text = String.format("%.2f MB cached", mb)
            withContext(Dispatchers.Main) {
                tvCacheSummary.text = text
            }
        }
    }

    private fun clearStagingCache() {
        lifecycleScope.launch(Dispatchers.IO) {
            val stagingDir = File(filesDir, "cloneforge/staging")
            val beforeBytes = (stagingDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }) +
                    (cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() })

            stagingDir.deleteRecursively()
            cacheDir.deleteRecursively()

            val mbFreed = beforeBytes / (1024.0 * 1024.0)
            withContext(Dispatchers.Main) {
                updateCacheSummary()
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.toast_cache_cleared, String.format("%.2f MB", mbFreed)),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun collectCloneBus() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                CloneBus.events.collect { event ->
                    if (event.finished) {
                        renderClones()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderClones()
        updateSettingsUi()
        val request = pendingRequest
        if (request != null && (request.rootInstall || request.shizukuInstall || packageManager.canRequestPackageInstalls())) {
            pendingRequest = null
            requestNotificationThenStart(request)
        } else if (request != null && !packageManager.canRequestPackageInstalls()) {
            pendingRequest = null
            Snackbar.make(appRecycler, R.string.install_permission_rationale, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun loadInstalledApps() {
        appLoadingView.isVisible = true
        appEmptyView.isVisible = false
        lifecycleScope.launch {
            allApps = fetchInstalledApps()
            appLoadingView.isVisible = false
            applyFilter()
        }
    }

    private suspend fun fetchInstalledApps(): List<AppItem> = withContext(Dispatchers.IO) {
        val pm = packageManager
        try {
            pm.getInstalledPackages(PackageManager.GET_META_DATA)
                .asSequence()
                .mapNotNull { info ->
                    val ai = info.applicationInfo ?: return@mapNotNull null
                    val launch = pm.getLaunchIntentForPackage(info.packageName)
                    if (launch == null) return@mapNotNull null
                    AppItem(
                        label = ai.loadLabel(pm).toString(),
                        packageName = info.packageName,
                        icon = ai.loadIcon(pm),
                        isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                }
                .filter { !it.isSystem || !isSelf(it.packageName) }
                .sortedBy { it.label.lowercase() }
                .toList()
        } catch (t: Throwable) {
            Log.w(TAG, "package scan failed", t)
            emptyList()
        }
    }

    private fun isSelf(packageName: String): Boolean = packageName == this.packageName

    private fun applyFilter() {
        if (!this::appAdapter.isInitialized) return
        val query = searchInput.text?.toString()?.trim()?.lowercase().orEmpty()
        val includeSystem = includeSystemSwitch.isChecked
        val filtered = allApps.filter { app ->
            (!app.isSystem || includeSystem) &&
                    (query.isEmpty() ||
                            app.label.lowercase().contains(query) ||
                            app.packageName.lowercase().contains(query))
        }
        appAdapter.submitList(filtered)
        appEmptyView.isVisible = filtered.isEmpty()
        appRecycler.isVisible = filtered.isNotEmpty()
        appCountBadge.text = "${filtered.size} apps"
    }

    private fun renderClones() {
        val pm = packageManager
        val items = history.clones().map { entry ->
            val installed = try {
                pm.getPackageInfo(entry.clonePackage, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
            CloneItem(entry, installed)
        }
        cloneAdapter.submitList(items)
        cloneEmptyView.isVisible = items.isEmpty()
        cloneRecycler.isVisible = items.isNotEmpty()
        val installedCount = items.count { it.isInstalled }
        cloneCountBadge.text = "$installedCount installed"
    }

    private fun openClone(entry: CloneHistoryStore.CloneEntry) {
        val launch = packageManager.getLaunchIntentForPackage(entry.clonePackage)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        } else {
            Toast.makeText(this, R.string.toast_clone_not_installed, Toast.LENGTH_SHORT).show()
            renderClones()
        }
    }

    private fun installCloneApk(entry: CloneHistoryStore.CloneEntry) {
        val apkFile = File(entry.apkPath)
        if (!apkFile.exists()) {
            Toast.makeText(this, "Clone APK not found in storage", Toast.LENGTH_SHORT).show()
            return
        }

        val hasRoot = RootHelper.isRootAvailable()
        val hasShizuku = ShizukuInstaller.isShizukuPermissionGranted()

        if (prefs.isRootInstallEnabled && hasRoot) {
            Toast.makeText(this, "Installing ${entry.cloneLabel} silently via root…", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = RootHelper.installApk(apkFile)
                withContext(Dispatchers.Main) {
                    if (ok) {
                        Toast.makeText(this@MainActivity, "Installed ${entry.cloneLabel} successfully!", Toast.LENGTH_SHORT).show()
                        renderClones()
                    } else {
                        Toast.makeText(this@MainActivity, "Root installation failed", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else if (prefs.isShizukuInstallEnabled && hasShizuku) {
            Toast.makeText(this, "Installing ${entry.cloneLabel} silently via Shizuku…", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = ShizukuInstaller.installApk(apkFile)
                withContext(Dispatchers.Main) {
                    if (ok) {
                        Toast.makeText(this@MainActivity, "Installed ${entry.cloneLabel} successfully via Shizuku!", Toast.LENGTH_SHORT).show()
                        renderClones()
                    } else {
                        Toast.makeText(this@MainActivity, "Shizuku installation failed", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(installIntent)
        }
    }

    private fun confirmUninstall(entry: CloneHistoryStore.CloneEntry) {
        val isInstalled = try {
            packageManager.getPackageInfo(entry.clonePackage, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

        if (!isInstalled) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Remove Clone Record")
                .setMessage("Remove ${entry.cloneLabel} from your clones list and delete its stored APK?")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Remove") { _, _ ->
                    history.removeClone(entry.clonePackage)
                    if (entry.apkPath.isNotBlank()) {
                        File(entry.apkPath).parentFile?.deleteRecursively()
                    }
                    renderClones()
                }
                .show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.uninstall_title)
            .setMessage(getString(R.string.uninstall_message_arg, entry.cloneLabel))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.uninstall) { _, _ ->
                val hasRoot = RootHelper.isRootAvailable()
                val hasShizuku = ShizukuInstaller.isShizukuPermissionGranted()
                if (prefs.isRootInstallEnabled && hasRoot) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val uninstalled = RootHelper.uninstallPackage(entry.clonePackage)
                        withContext(Dispatchers.Main) {
                            if (uninstalled) {
                                history.removeClone(entry.clonePackage)
                                renderClones()
                                Toast.makeText(this@MainActivity, "Uninstalled ${entry.cloneLabel}", Toast.LENGTH_SHORT).show()
                            } else {
                                startSystemUninstaller(entry.clonePackage)
                            }
                        }
                    }
                } else if (prefs.isShizukuInstallEnabled && hasShizuku) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val uninstalled = ShizukuInstaller.uninstallPackage(entry.clonePackage)
                        withContext(Dispatchers.Main) {
                            if (uninstalled) {
                                history.removeClone(entry.clonePackage)
                                renderClones()
                                Toast.makeText(this@MainActivity, "Uninstalled ${entry.cloneLabel} via Shizuku", Toast.LENGTH_SHORT).show()
                            } else {
                                startSystemUninstaller(entry.clonePackage)
                            }
                        }
                    }
                } else {
                    startSystemUninstaller(entry.clonePackage)
                }
            }
            .show()
    }

    private fun startSystemUninstaller(packageName: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
            )
        } catch (t: Throwable) {
            Log.w(TAG, "unable to start uninstaller", t)
        }
    }

    private fun showCloneSheet(app: AppItem) {
        val dialog = BottomSheetDialog(this)
        val sheet = layoutInflater.inflate(R.layout.sheet_clone, null)
        dialog.setContentView(sheet)

        val title = sheet.findViewById<TextView>(R.id.tv_sheet_app)
        val prefixInput = sheet.findViewById<TextInputEditText>(R.id.edit_prefix)
        val chipGroup = sheet.findViewById<ChipGroup>(R.id.chip_group_count)
        val editCount = sheet.findViewById<TextInputEditText>(R.id.edit_count)
        val btnMinus = sheet.findViewById<MaterialButton>(R.id.btn_count_minus)
        val btnPlus = sheet.findViewById<MaterialButton>(R.id.btn_count_plus)
        val switchNameNumber = sheet.findViewById<MaterialSwitch>(R.id.switch_number_in_name)
        val switchIconNumber = sheet.findViewById<MaterialSwitch>(R.id.switch_number_in_icon)
        val hueSwitch = sheet.findViewById<MaterialSwitch>(R.id.switch_hue)
        val rootSwitch = sheet.findViewById<MaterialSwitch>(R.id.switch_root)
        val startBtn = sheet.findViewById<MaterialButton>(R.id.btn_start_clone)

        title.text = getString(R.string.sheet_title_arg, app.label)
        prefixInput.setText(app.label)

        switchNameNumber.isChecked = true
        switchIconNumber.isChecked = prefs.isBadgeIconsEnabled
        hueSwitch.isChecked = prefs.isAutoHueEnabled

        val hasRoot = RootHelper.isRootAvailable()
        val hasShizuku = ShizukuInstaller.isShizukuPermissionGranted()
        val hasSilent = (hasRoot && prefs.isRootInstallEnabled) || (hasShizuku && prefs.isShizukuInstallEnabled)
        val canSilent = hasRoot || hasShizuku

        val tvRootLabel = sheet.findViewById<TextView>(R.id.tv_root_label)
        val tvRootHelper = sheet.findViewById<TextView>(R.id.tv_root_helper)
        if (hasShizuku && !hasRoot) {
            tvRootLabel?.text = getString(R.string.pref_shizuku_install_setting)
            tvRootHelper?.text = getString(R.string.pref_shizuku_install_desc)
        } else if (hasRoot && hasShizuku) {
            tvRootLabel?.text = "Silent Install (Root / Shizuku)"
            tvRootHelper?.text = "Direct silent background installation without popups"
        } else if (hasRoot) {
            tvRootLabel?.text = getString(R.string.label_root_install)
            tvRootHelper?.text = getString(R.string.label_root_install_hint)
        } else {
            tvRootLabel?.text = "Silent Install (Unavailable)"
            tvRootHelper?.text = "Requires Root access or authorized Shizuku service"
        }

        rootSwitch.isEnabled = canSilent
        rootSwitch.isChecked = hasSilent

        fun getCount(): Int = editCount.text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 1

        fun setCount(num: Int) {
            val v = num.coerceAtLeast(1)
            editCount.setText(v.toString())
            startBtn.text = if (v == 1) getString(R.string.button_start_clones) else "Create $v Clones"
            when (v) {
                1 -> chipGroup.check(R.id.chip_count_1)
                2 -> chipGroup.check(R.id.chip_count_2)
                3 -> chipGroup.check(R.id.chip_count_3)
                5 -> chipGroup.check(R.id.chip_count_5)
                10 -> chipGroup.check(R.id.chip_count_10)
                else -> chipGroup.clearCheck()
            }
        }

        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            when (checkedIds.firstOrNull()) {
                R.id.chip_count_1 -> setCount(1)
                R.id.chip_count_2 -> setCount(2)
                R.id.chip_count_3 -> setCount(3)
                R.id.chip_count_5 -> setCount(5)
                R.id.chip_count_10 -> setCount(10)
            }
        }

        btnMinus.setOnClickListener {
            val current = getCount()
            if (current > 1) setCount(current - 1)
        }

        btnPlus.setOnClickListener {
            val current = getCount()
            setCount(current + 1)
        }

        editCount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val num = s?.toString()?.toIntOrNull() ?: 1
                startBtn.text = if (num == 1) getString(R.string.button_start_clones) else "Create $num Clones"
            }
        })

        startBtn.setOnClickListener {
            val prefix = prefixInput.text?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() } ?: app.label
            val cloneCount = getCount()
            dialog.dismiss()

            val useRoot = rootSwitch.isChecked && hasRoot && prefs.isRootInstallEnabled
            val useShizuku = rootSwitch.isChecked && !useRoot && hasShizuku && prefs.isShizukuInstallEnabled
            val isSilent = useRoot || useShizuku

            val request = PendingCloneRequest(
                app = app,
                targetPackages = listOf(app.packageName),
                labelPrefix = prefix,
                cloneCount = cloneCount,
                addNumberName = switchNameNumber.isChecked,
                addNumberIcon = switchIconNumber.isChecked,
                hueEnabled = hueSwitch.isChecked,
                rootInstall = useRoot,
                shizukuInstall = useShizuku,
                silent = isSilent
            )
            ensureInstallPermissionThenStart(request)
        }

        dialog.show()
    }

    private fun showBatchCloneSheet(selectedApps: List<AppItem>) {
        if (selectedApps.isEmpty()) return
        val dialog = BottomSheetDialog(this)
        val sheet = layoutInflater.inflate(R.layout.sheet_clone, null)
        dialog.setContentView(sheet)

        val title = sheet.findViewById<TextView>(R.id.tv_sheet_app)
        val hint = sheet.findViewById<TextView>(R.id.tv_sheet_hint)
        val prefixLayout = sheet.findViewById<View>(R.id.til_prefix)
        val chipGroup = sheet.findViewById<ChipGroup>(R.id.chip_group_count)
        val editCount = sheet.findViewById<TextInputEditText>(R.id.edit_count)
        val btnMinus = sheet.findViewById<MaterialButton>(R.id.btn_count_minus)
        val btnPlus = sheet.findViewById<MaterialButton>(R.id.btn_count_plus)
        val switchNameNumber = sheet.findViewById<MaterialSwitch>(R.id.switch_number_in_name)
        val switchIconNumber = sheet.findViewById<MaterialSwitch>(R.id.switch_number_in_icon)
        val hueSwitch = sheet.findViewById<MaterialSwitch>(R.id.switch_hue)
        val rootSwitch = sheet.findViewById<MaterialSwitch>(R.id.switch_root)
        val startBtn = sheet.findViewById<MaterialButton>(R.id.btn_start_clone)

        title.text = getString(R.string.sheet_batch_clone_title, selectedApps.size)
        hint.text = selectedApps.take(3).joinToString { it.label } +
                (if (selectedApps.size > 3) " and ${selectedApps.size - 3} more apps" else "")
        prefixLayout?.isVisible = false

        switchNameNumber.isChecked = true
        switchIconNumber.isChecked = prefs.isBadgeIconsEnabled
        hueSwitch.isChecked = prefs.isAutoHueEnabled

        val hasRoot = RootHelper.isRootAvailable()
        val hasShizuku = ShizukuInstaller.isShizukuPermissionGranted()
        val hasSilent = (hasRoot && prefs.isRootInstallEnabled) || (hasShizuku && prefs.isShizukuInstallEnabled)
        val canSilent = hasRoot || hasShizuku

        val tvRootLabel = sheet.findViewById<TextView>(R.id.tv_root_label)
        val tvRootHelper = sheet.findViewById<TextView>(R.id.tv_root_helper)
        if (hasShizuku && !hasRoot) {
            tvRootLabel?.text = getString(R.string.pref_shizuku_install_setting)
            tvRootHelper?.text = getString(R.string.pref_shizuku_install_desc)
        } else if (hasRoot && hasShizuku) {
            tvRootLabel?.text = "Silent Install (Root / Shizuku)"
            tvRootHelper?.text = "Direct silent background installation without popups"
        } else if (hasRoot) {
            tvRootLabel?.text = getString(R.string.label_root_install)
            tvRootHelper?.text = getString(R.string.label_root_install_hint)
        } else {
            tvRootLabel?.text = "Silent Install (Unavailable)"
            tvRootHelper?.text = "Requires Root access or authorized Shizuku service"
        }

        rootSwitch.isEnabled = canSilent
        rootSwitch.isChecked = hasSilent

        fun getCount(): Int = editCount.text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 1

        fun updateButtonText() {
            val countPerApp = getCount()
            val totalClones = countPerApp * selectedApps.size
            startBtn.text = "Forge $totalClones Clones ($countPerApp per app)"
        }

        fun setCount(num: Int) {
            val v = num.coerceAtLeast(1)
            editCount.setText(v.toString())
            updateButtonText()
            when (v) {
                1 -> chipGroup.check(R.id.chip_count_1)
                2 -> chipGroup.check(R.id.chip_count_2)
                3 -> chipGroup.check(R.id.chip_count_3)
                5 -> chipGroup.check(R.id.chip_count_5)
                10 -> chipGroup.check(R.id.chip_count_10)
                else -> chipGroup.clearCheck()
            }
        }

        setCount(1)

        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            when (checkedIds.firstOrNull()) {
                R.id.chip_count_1 -> setCount(1)
                R.id.chip_count_2 -> setCount(2)
                R.id.chip_count_3 -> setCount(3)
                R.id.chip_count_5 -> setCount(5)
                R.id.chip_count_10 -> setCount(10)
            }
        }

        btnMinus.setOnClickListener {
            val current = getCount()
            if (current > 1) setCount(current - 1)
        }

        btnPlus.setOnClickListener {
            val current = getCount()
            setCount(current + 1)
        }

        editCount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateButtonText()
            }
        })

        startBtn.setOnClickListener {
            val cloneCountPerApp = getCount()
            dialog.dismiss()

            val useRoot = rootSwitch.isChecked && hasRoot && prefs.isRootInstallEnabled
            val useShizuku = rootSwitch.isChecked && !useRoot && hasShizuku && prefs.isShizukuInstallEnabled
            val isSilent = useRoot || useShizuku

            val request = PendingCloneRequest(
                app = null,
                targetPackages = selectedApps.map { it.packageName },
                labelPrefix = null,
                cloneCount = cloneCountPerApp,
                addNumberName = switchNameNumber.isChecked,
                addNumberIcon = switchIconNumber.isChecked,
                hueEnabled = hueSwitch.isChecked,
                rootInstall = useRoot,
                shizukuInstall = useShizuku,
                silent = isSilent
            )
            exitAppsSelectionMode()
            bottomNav.selectedItemId = R.id.nav_clones
            ensureInstallPermissionThenStart(request)
        }

        dialog.show()
    }

    private fun ensureInstallPermissionThenStart(request: PendingCloneRequest) {
        if ((request.rootInstall && RootHelper.isRootAvailable()) ||
            (request.shizukuInstall && ShizukuInstaller.isShizukuPermissionGranted())
        ) {
            requestNotificationThenStart(request)
            return
        }

        if (!packageManager.canRequestPackageInstalls()) {
            pendingRequest = request
            Snackbar.make(appRecycler, R.string.install_permission_rationale, Snackbar.LENGTH_LONG)
                .setAction(android.R.string.ok) {
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (t: Throwable) {
                        Log.w(TAG, "no unknown-app settings entry", t)
                        Toast.makeText(this, R.string.toast_open_settings_failed, Toast.LENGTH_LONG).show()
                    }
                }
                .show()
            return
        }
        requestNotificationThenStart(request)
    }

    private fun requestNotificationThenStart(request: PendingCloneRequest) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingRequest = request
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        pendingRequest = null
        startCloneService(request)
    }

    private fun startCloneService(request: PendingCloneRequest) {
        val intent = Intent(this, CloneService::class.java).apply {
            if (request.targetPackages.size == 1 && request.app != null) {
                putExtra(CloneService.EXTRA_TARGET_PACKAGE, request.targetPackages[0])
                putExtra(CloneService.EXTRA_APP_LABEL, request.labelPrefix ?: request.app.label)
            } else {
                putStringArrayListExtra(CloneService.EXTRA_TARGET_PACKAGES, ArrayList(request.targetPackages))
                if (!request.labelPrefix.isNullOrBlank()) {
                    putExtra(CloneService.EXTRA_APP_LABEL, request.labelPrefix)
                }
            }
            putExtra(CloneService.EXTRA_CLONE_COUNT, request.cloneCount)
            putExtra(CloneService.EXTRA_ADD_NUMBER_NAME, request.addNumberName)
            putExtra(CloneService.EXTRA_ADD_NUMBER_ICON, request.addNumberIcon)
            putExtra(CloneService.EXTRA_HUE_ENABLED, request.hueEnabled)
            putExtra(CloneService.EXTRA_ROOT_INSTALL, request.rootInstall)
            putExtra(CloneService.EXTRA_SHIZUKU_INSTALL, request.shizukuInstall)
            putExtra(CloneService.EXTRA_SILENT, request.silent)
        }
        ContextCompat.startForegroundService(this, intent)

        if (request.targetPackages.size == 1 && request.app != null) {
            Toast.makeText(
                this,
                resources.getQuantityString(
                    R.plurals.toast_clones_started,
                    request.cloneCount, request.cloneCount, request.app.label
                ),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(
                this,
                "Forging clones for ${request.targetPackages.size} apps…",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
