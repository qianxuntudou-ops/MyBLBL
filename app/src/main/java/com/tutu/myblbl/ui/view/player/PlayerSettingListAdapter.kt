package com.tutu.myblbl.ui.view.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R

/**
 * Keeps row rendering and diffing separate from MyPlayerSettingView's menu flow logic.
 */
internal class PlayerSettingListAdapter(
    private val onClick: (PlayerSettingRow.Item) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ITEM = 1
    }

    private val rows = mutableListOf<PlayerSettingRow>()
    var currentMenuKey: Int = 0
        private set

    fun submitRows(menuKey: Int, newRows: List<PlayerSettingRow>) {
        currentMenuKey = menuKey
        val diffResult = DiffUtil.calculateDiff(PlayerSettingRowDiff(rows, newRows))
        rows.clear()
        rows.addAll(newRows)
        diffResult.dispatchUpdatesTo(this)
    }

    override fun getItemViewType(position: Int): Int {
        return when (rows[position]) {
            is PlayerSettingRow.Header -> VIEW_TYPE_HEADER
            is PlayerSettingRow.Item -> VIEW_TYPE_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_HEADER) {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.cell_player_setting_head, parent, false)
            HeaderViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.cell_player_setting, parent, false)
            ItemViewHolder(view, onClick)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is PlayerSettingRow.Header -> (holder as HeaderViewHolder).bind(row)
            is PlayerSettingRow.Item -> (holder as ItemViewHolder).bind(row)
        }
    }

    override fun getItemCount(): Int = rows.size

    private class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val titleView: TextView = view.findViewById(R.id.textView)
        private val subTitleView: TextView = view.findViewById(R.id.subTextView)

        fun bind(row: PlayerSettingRow.Header) {
            titleView.text = row.title
            val hasSubTitle = !row.subTitle.isNullOrBlank()
            subTitleView.visibility = if (hasSubTitle) View.VISIBLE else View.GONE
            subTitleView.text = row.subTitle.orEmpty()
        }
    }

    private class ItemViewHolder(
        view: View,
        private val onClick: (PlayerSettingRow.Item) -> Unit
    ) : RecyclerView.ViewHolder(view) {
        private val iconView: ImageView = view.findViewById(R.id.imageView)
        private val titleView: TextView = view.findViewById(R.id.text_view_primary)
        private val valueView: TextView = view.findViewById(R.id.text_view_sub)
        private val arrowView: ImageView = view.findViewById(R.id.icon_more)
        private val checkView: ImageView = view.findViewById(R.id.icon_check)
        private var currentItem: PlayerSettingRow.Item? = null

        init {
            view.isFocusable = true
            view.isClickable = true
            view.setOnClickListener {
                currentItem?.let(onClick)
            }
        }

        fun bind(item: PlayerSettingRow.Item) {
            currentItem = item
            itemView.isPressed = false
            itemView.isActivated = false
            itemView.isSelected = false
            itemView.jumpDrawablesToCurrentState()
            titleView.text = item.title
            val hasValue = item.value.isNotBlank()
            valueView.visibility = if (hasValue) View.VISIBLE else View.GONE
            valueView.text = item.value

            val iconRes = item.iconRes
            if (iconRes != null) {
                iconView.visibility = View.VISIBLE
                iconView.setImageResource(iconRes)
            } else {
                iconView.visibility = View.INVISIBLE
            }

            checkView.visibility = if (item.checked) View.VISIBLE else View.GONE
            arrowView.visibility = if (!item.checked && item.showArrow) View.VISIBLE else View.GONE
        }
    }

    private class PlayerSettingRowDiff(
        private val oldList: List<PlayerSettingRow>,
        private val newList: List<PlayerSettingRow>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size

        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldItem = oldList[oldItemPosition]
            val newItem = newList[newItemPosition]
            return when {
                oldItem is PlayerSettingRow.Header && newItem is PlayerSettingRow.Header ->
                    oldItem.title == newItem.title && oldItem.subTitle == newItem.subTitle

                oldItem is PlayerSettingRow.Item && newItem is PlayerSettingRow.Item ->
                    oldItem.id == newItem.id

                else -> false
            }
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition] == newList[newItemPosition]
        }
    }
}
