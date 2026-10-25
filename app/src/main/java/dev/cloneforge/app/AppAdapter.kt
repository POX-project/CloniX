package dev.cloneforge.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView adapter for the installed-app list, backed by [AppItem] rows.
 * Uses DiffUtil so search filtering animates instead of flashing.
 */
class AppAdapter(
    private val onClick: (AppItem) -> Unit
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    private val items = ArrayList<AppItem>()

    fun submitList(newList: List<AppItem>) {
        val old = ArrayList(items)
        items.clear()
        items.addAll(newList)
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
        holder.label.text = item.label
        holder.packageName.text = item.packageName
        if (item.icon != null) {
            holder.icon.setImageDrawable(item.icon)
        } else {
            holder.icon.setImageResource(R.mipmap.ic_launcher)
        }
        holder.itemView.setOnClickListener { onClick(item) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.iv_app_icon)
        val label: TextView = view.findViewById(R.id.tv_app_label)
        val packageName: TextView = view.findViewById(R.id.tv_app_package)
    }
}
