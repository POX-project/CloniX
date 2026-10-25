package dev.cloneforge.app

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main screen: searchable list of installed apps with a clone bottom sheet,
 * plus a persistent "Your clones" section driven by [CloneHistoryStore].
 */
class MainActivity : AppCompatActivity() {

    private data class PendingCloneRequest(
        val app: AppItem,
        val labelPrefix: String,
        val cloneCount: Int,
        val hueEnabled: Boolean,
        val silent: Boolean
    )

    private val history = CloneHistoryStore(this)
    private lateinit var adapter: AppAdapter

    private lateinit var searchInput: TextInputEditText
    private lateinit var includeSystemSwitch: MaterialSwitch
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var historyContainer: LinearLayout
    private lateinit var historyEmpty: TextView
    private lateinit var loadingView: TextView

    private var allApps: List<AppItem> = emptyList()
    private var pendingRequest: PendingCloneRequest? = null

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Notification permission is a nice-to-have, never blocks the clone.
            pendingRequest?.let { request ->
                pendingRequest = null
                startCloneService(request)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        searchInput = findViewById(R.id.input_search)
        includeSystemSwitch = findViewById(R.id.switch_include_system)
        recycler = findViewById(R.id.recycler_apps)
        emptyView = findViewById(R.id.tv_empty_list)
        historyContainer = findViewById(R.id.ll_history_list)
        historyEmpty = findViewById(R.id.tv_history_empty)
        loadingView = findViewById(R.id.tv_loading)

        adapter = AppAdapter(::showCloneSheet)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })
        includeSystemSwitch.setOnCheckedChangeListener { _, _ -> applyFilter() }

        loadInstalledApps()
        collectCloneBus()
    }

    /** Reacts to live clone events (e.g. a clone finished) by refreshing the history list. */
    private fun collectCloneBus() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                CloneBus.events.collect { event ->
                    if (event.finished) {
                        renderHistory()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderHistory()
        // Arriving back from "install unknown apps" settings — continue the queued request.
        val request = pendingRequest
        if (request != null && packageManager.canRequestPackageInstalls()) {
            pendingRequest = null
            requestNotificationThenStart(request)
        } else if (request != null && !packageManager.canRequestPackageInstalls()) {
            pendingRequest = null
            Snackbar.make(recycler, R.string.install_permission_rationale, Snackbar.LENGTH_LONG)
                .show()
        }
    }

    private fun loadInstalledApps() {
        loadingView.isVisible = true
        emptyView.isVisible = false
        lifecycleScope.launch {
            allApps = fetchInstalledApps()
            loadingView.isVisible = false
            applyFilter()
        }
    }

    /**
     * Lists apps with a launcher intent. System apps are hidden by default and
     * can be toggled in. No READ_EXTERNAL_STORAGE required — PackageManager only.
     */
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
        if (!this::adapter.isInitialized) return
        val query = searchInput.text?.toString()?.trim()?.lowercase().orEmpty()
        val includeSystem = includeSystemSwitch.isChecked
        val filtered = allApps.filter { app ->
            (!app.isSystem || includeSystem) &&
                (query.isEmpty() ||
                    app.label.lowercase().contains(query) ||
                    app.packageName.lowercase().contains(query))
        }
        adapter.submitList(filtered)
        emptyView.isVisible = filtered.isEmpty()
        recycler.isVisible = filtered.isNotEmpty()
    }

    private fun showCloneSheet(app: AppItem) {
        val dialog = BottomSheetDialog(this)
        val sheet = layoutInflater.inflate(R.layout.sheet_clone, null)
        dialog.setContentView(sheet)

        val title = sheet.findViewById<TextView>(R.id.tv_sheet_app)
        val prefixInput = sheet.findViewById<TextInputEditText>(R.id.edit_prefix)
        val picker = sheet.findViewById<android.widget.NumberPicker>(R.id.picker_count)
        val hueSwitch = sheet.findViewById<MaterialSwitch>(R.id.switch_hue)
        val start = sheet.findViewById<MaterialButton>(R.id.btn_start_clone)

        title.text = getString(R.string.sheet_title_arg, app.label)
        prefixInput.setText(app.label)
        picker.minValue = 1
        picker.maxValue = MAX_CLONES
        picker.value = 1
        picker.wrapSelectorWheel = false

        start.setOnClickListener {
            val prefix = prefixInput.text?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() } ?: app.label
            dialog.dismiss()
            val request = PendingCloneRequest(
                app = app,
                labelPrefix = prefix,
                cloneCount = picker.value,
                hueEnabled = hueSwitch.isChecked,
                silent = false
            )
            ensureInstallPermissionThenStart(request)
        }
        dialog.show()
    }

    /** START button gate #1: REQUEST_INSTALL_PACKAGES ("unknown apps"). */
    private fun ensureInstallPermissionThenStart(request: PendingCloneRequest) {
        if (!packageManager.canRequestPackageInstalls()) {
            pendingRequest = request
            Snackbar.make(recycler, R.string.install_permission_rationale, Snackbar.LENGTH_LONG)
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
                        Toast.makeText(this, R.string.toast_open_settings_failed, Toast.LENGTH_LONG)
                            .show()
                    }
                }
                .show()
            return
        }
        requestNotificationThenStart(request)
    }

    /** START button gate #2: POST_NOTIFICATIONS on API 33+. */
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
            putExtra(CloneService.EXTRA_TARGET_PACKAGE, request.app.packageName)
            putExtra(CloneService.EXTRA_APP_LABEL, request.labelPrefix)
            putExtra(CloneService.EXTRA_CLONE_COUNT, request.cloneCount)
            putExtra(CloneService.EXTRA_HUE_ENABLED, request.hueEnabled)
            putExtra(CloneService.EXTRA_SILENT, request.silent)
        }
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(
            this,
            resources.getQuantityString(
                R.plurals.toast_clones_started,
                request.cloneCount, request.cloneCount, request.app.label
            ),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun renderHistory() {
        val entries = history.clones()
        historyContainer.removeAllViews()
        historyEmpty.isVisible = entries.isEmpty()
        for (entry in entries) {
            val row = layoutInflater.inflate(R.layout.view_clone_history_item, historyContainer, false)
            val label = row.findViewById<TextView>(R.id.tv_history_item_label)
            val pkg = row.findViewById<TextView>(R.id.tv_history_item_pkg)
            val open = row.findViewById<MaterialButton>(R.id.btn_history_open)
            val uninstall = row.findViewById<MaterialButton>(R.id.btn_history_uninstall)

            label.text = entry.cloneLabel
            pkg.text = entry.clonePackage

            open.setOnClickListener { openClone(entry) }
            uninstall.setOnClickListener { confirmUninstall(entry) }
            historyContainer.addView(row)
        }
    }

    private fun openClone(entry: CloneHistoryStore.CloneEntry) {
        val launch = packageManager.getLaunchIntentForPackage(entry.clonePackage)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        } else {
            Toast.makeText(this, R.string.toast_clone_not_installed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmUninstall(entry: CloneHistoryStore.CloneEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.uninstall_title)
            .setMessage(getString(R.string.uninstall_message_arg, entry.cloneLabel))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.uninstall) { _, _ ->
                try {
                    startActivity(
                        Intent(Intent.ACTION_DELETE, Uri.parse("package:${entry.clonePackage}"))
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "unable to start uninstaller", t)
                }
            }
            .show()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val MAX_CLONES = 5
    }
}
