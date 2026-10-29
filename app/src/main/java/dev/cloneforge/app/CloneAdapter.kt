package dev.cloneforge.app

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import java.io.File

data class CloneItem(
    val entry: CloneHistoryStore.CloneEntry,
    val isInstalled: Boolean
)

class CloneAdapter(
    private val onOpenClick: (CloneHistoryStore.CloneEntry) -> Unit,
    private val onInstallClick: (CloneHistoryStore.CloneEntry) -> Unit,
    private val onUninstallClick: (CloneHistoryStore.CloneEntry) -> Unit,
    private val onSelectionChanged: (selectedCount: Int) -> Unit
) : ListAdapter<CloneItem, CloneAdapter.CloneViewHolder>(Diff) {

    val selectedClonePackages = LinkedHashSet<String>()
    var isSelectionMode: Boolean = false
        private set

    fun setSelectionMode(enabled: Boolean) {
        if (isSelectionMode != enabled) {
            isSelectionMode = enabled
            if (!enabled) {
                selectedClonePackages.clear()
            }
            notifyDataSetChanged()
            onSelectionChanged(selectedClonePackages.size)
        }
    }

    fun selectAll() {
        if (!isSelectionMode) isSelectionMode = true
        selectedClonePackages.clear()
        for (i in 0 until itemCount) {
            selectedClonePackages.add(getItem(i).entry.clonePackage)
        }
        notifyDataSetChanged()
        onSelectionChanged(selectedClonePackages.size)
    }

    fun deselectAll() {
        selectedClonePackages.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun toggleSelection(clonePackage: String) {
        if (!isSelectionMode) isSelectionMode = true
        if (selectedClonePackages.contains(clonePackage)) {
            selectedClonePackages.remove(clonePackage)
        } else {
            selectedClonePackages.add(clonePackage)
        }
        notifyDataSetChanged()
        onSelectionChanged(selectedClonePackages.size)
    }

    fun getSelectedItems(): List<CloneItem> {
        val list = ArrayList<CloneItem>()
        for (i in 0 until itemCount) {
            val item = getItem(i)
            if (selectedClonePackages.contains(item.entry.clonePackage)) {
                list.add(item)
            }
        }
        return list
    }

    override fun submitList(list: List<CloneItem>?) {
        if (selectedClonePackages.isNotEmpty() && list != null) {
            val valid = list.map { it.entry.clonePackage }.toSet()
            selectedClonePackages.retainAll(valid)
        }
        super.submitList(list)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CloneViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_clone, parent, false)
        return CloneViewHolder(view, onOpenClick, onInstallClick, onUninstallClick)
    }

    override fun onBindViewHolder(holder: CloneViewHolder, position: Int) {
        val item = getItem(position)
        val isSelected = selectedClonePackages.contains(item.entry.clonePackage)
        holder.bind(
            item = item,
            isSelected = isSelected,
            isSelectionMode = isSelectionMode,
            onToggleSelection = { toggleSelection(it) },
            onEnterSelection = {
                setSelectionMode(true)
                toggleSelection(it)
            }
        )
    }

    class CloneViewHolder(
        itemView: View,
        private val onOpenClick: (CloneHistoryStore.CloneEntry) -> Unit,
        private val onInstallClick: (CloneHistoryStore.CloneEntry) -> Unit,
        private val onUninstallClick: (CloneHistoryStore.CloneEntry) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val cardView: MaterialCardView = itemView.findViewById(R.id.card_clone_item)
        private val checkBox: MaterialCheckBox = itemView.findViewById(R.id.cb_clone_select)
        private val iconView: ImageView = itemView.findViewById(R.id.iv_clone_icon)
        private val labelView: TextView = itemView.findViewById(R.id.tv_clone_label)
        private val pkgView: TextView = itemView.findViewById(R.id.tv_clone_package)
        private val launchBtn: MaterialButton = itemView.findViewById(R.id.btn_clone_launch)
        private val installBtn: MaterialButton = itemView.findViewById(R.id.btn_clone_install)
        private val uninstallBtn: MaterialButton = itemView.findViewById(R.id.btn_clone_uninstall)

        fun bind(
            item: CloneItem,
            isSelected: Boolean,
            isSelectionMode: Boolean,
            onToggleSelection: (String) -> Unit,
            onEnterSelection: (String) -> Unit
        ) {
            val entry = item.entry
            val isInstalled = item.isInstalled
            labelView.text = entry.cloneLabel
            pkgView.text = entry.clonePackage

            val pm = itemView.context.packageManager

            val iconDrawable: Drawable? = try {
                pm.getApplicationIcon(entry.clonePackage)
            } catch (_: PackageManager.NameNotFoundException) {
                try {
                    pm.getApplicationIcon(entry.originalPackage)
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            }

            if (iconDrawable != null) {
                iconView.setImageDrawable(iconDrawable)
            } else {
                iconView.setImageResource(R.drawable.ic_clones)
            }

            val hasApk = entry.apkPath.isNotBlank() && File(entry.apkPath).exists()

            if (isSelectionMode) {
                checkBox.visibility = View.VISIBLE
                checkBox.isChecked = isSelected
                cardView.strokeWidth = if (isSelected) 4 else 0
                cardView.strokeColor = ContextCompat.getColor(itemView.context, R.color.cf_primary)
            } else {
                checkBox.visibility = View.GONE
                cardView.strokeWidth = 0
            }

            if (isInstalled) {
                launchBtn.visibility = if (isSelectionMode) View.GONE else View.VISIBLE
                installBtn.visibility = View.GONE
                uninstallBtn.visibility = if (isSelectionMode) View.GONE else View.VISIBLE
                uninstallBtn.text = itemView.context.getString(R.string.button_uninstall)
                launchBtn.setOnClickListener { onOpenClick(entry) }
                uninstallBtn.setOnClickListener { onUninstallClick(entry) }
            } else {
                launchBtn.visibility = View.GONE
                if (hasApk) {
                    installBtn.visibility = if (isSelectionMode) View.GONE else View.VISIBLE
                    installBtn.setOnClickListener { onInstallClick(entry) }
                } else {
                    installBtn.visibility = View.GONE
                }
                uninstallBtn.visibility = if (isSelectionMode) View.GONE else View.VISIBLE
                uninstallBtn.text = itemView.context.getString(R.string.button_uninstall)
                uninstallBtn.setOnClickListener { onUninstallClick(entry) }
            }

            itemView.setOnClickListener {
                if (isSelectionMode) {
                    onToggleSelection(entry.clonePackage)
                }
            }

            itemView.setOnLongClickListener {
                if (isSelectionMode) {
                    onToggleSelection(entry.clonePackage)
                } else {
                    onEnterSelection(entry.clonePackage)
                }
                true
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<CloneItem>() {
        override fun areItemsTheSame(
            oldItem: CloneItem,
            newItem: CloneItem
        ): Boolean = oldItem.entry.clonePackage == newItem.entry.clonePackage

        override fun areContentsTheSame(
            oldItem: CloneItem,
            newItem: CloneItem
        ): Boolean = oldItem == newItem
    }
}
