package dev.cloneforge.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox

/**
 * RecyclerView adapter for the installed-app list, backed by [AppItem] rows.
 * Supports single selection and multi-selection mode with batch actions.
 */
class AppAdapter(
    private val onClick: (AppItem) -> Unit,
    private val onSelectionChanged: (selectedCount: Int) -> Unit
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    private val items = ArrayList<AppItem>()
    val selectedPackages = LinkedHashSet<String>()
    var isSelectionMode: Boolean = false
        private set

    fun setSelectionMode(enabled: Boolean) {
        if (isSelectionMode != enabled) {
            isSelectionMode = enabled
            if (!enabled) {
                selectedPackages.clear()
            }
            notifyDataSetChanged()
            onSelectionChanged(selectedPackages.size)
        }
    }

    fun selectAll() {
        if (!isSelectionMode) isSelectionMode = true
        selectedPackages.clear()
        for (item in items) {
            selectedPackages.add(item.packageName)
        }
        notifyDataSetChanged()
        onSelectionChanged(selectedPackages.size)
    }

    fun deselectAll() {
        selectedPackages.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun toggleSelection(packageName: String) {
        if (!isSelectionMode) isSelectionMode = true
        if (selectedPackages.contains(packageName)) {
            selectedPackages.remove(packageName)
        } else {
            selectedPackages.add(packageName)
        }
        notifyDataSetChanged()
        onSelectionChanged(selectedPackages.size)
    }

    fun getSelectedItems(): List<AppItem> {
        return items.filter { selectedPackages.contains(it.packageName) }
    }

    fun submitList(newList: List<AppItem>) {
        val old = ArrayList(items)
        items.clear()
        items.addAll(newList)
        if (selectedPackages.isNotEmpty()) {
            val valid = items.map { it.packageName }.toSet()
            selectedPackages.retainAll(valid)
        }
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = items.size

            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                old[oldPos].packageName == items[newPos].packageName

            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                old[oldPos].label == items[newPos].label &&
                    old[oldPos].isSystem == items[newPos].isSystem &&
                    old[oldPos].icon === items[newPos].icon
        }).dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val isSelected = selectedPackages.contains(item.packageName)

        holder.label.text = item.label
        holder.packageName.text = item.packageName
        if (item.icon != null) {
            holder.icon.setImageDrawable(item.icon)
        } else {
            holder.icon.setImageResource(R.mipmap.ic_launcher)
        }

        if (isSelectionMode) {
            holder.checkBox.visibility = View.VISIBLE
            holder.checkBox.isChecked = isSelected
            holder.btnClone?.visibility = View.GONE
            holder.cardView.strokeWidth = if (isSelected) 4 else 0
            holder.cardView.strokeColor = ContextCompat.getColor(holder.itemView.context, R.color.cf_primary)
        } else {
            holder.checkBox.visibility = View.GONE
            holder.btnClone?.visibility = View.VISIBLE
            holder.cardView.strokeWidth = 0
        }

        holder.itemView.setOnClickListener {
            if (isSelectionMode) {
                toggleSelection(item.packageName)
            } else {
                onClick(item)
            }
        }

        holder.itemView.setOnLongClickListener {
            if (!isSelectionMode) {
                setSelectionMode(true)
            }
            toggleSelection(item.packageName)
            true
        }

        holder.btnClone?.setOnClickListener {
            if (isSelectionMode) {
                toggleSelection(item.packageName)
            } else {
                onClick(item)
            }
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val cardView: MaterialCardView = view.findViewById(R.id.card_app_item)
        val checkBox: MaterialCheckBox = view.findViewById(R.id.cb_app_select)
        val icon: ImageView = view.findViewById(R.id.iv_app_icon)
        val label: TextView = view.findViewById(R.id.tv_app_label)
        val packageName: TextView = view.findViewById(R.id.tv_app_package)
        val btnClone: View? = view.findViewById(R.id.btn_item_clone)
    }
}
