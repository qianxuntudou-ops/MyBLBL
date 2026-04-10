package com.tutu.myblbl.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.View
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.databinding.CellSettingBinding
import com.tutu.myblbl.model.SettingModel

class SettingAdapter(
    private val onItemClick: ((position: Int, item: SettingModel) -> Unit)? = null
) : RecyclerView.Adapter<SettingAdapter.SettingViewHolder>() {

    private val items = mutableListOf<SettingModel>()
    private var focusedPosition = RecyclerView.NO_POSITION

    fun setData(newItems: List<SettingModel>) {
        val diffResult = DiffUtil.calculateDiff(
            object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = items.size

                override fun getNewListSize(): Int = newItems.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return items[oldItemPosition].title == newItems[newItemPosition].title
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val oldItem = items[oldItemPosition]
                    val newItem = newItems[newItemPosition]
                    return oldItem.title == newItem.title && oldItem.info == newItem.info
                }
            }
        )
        items.clear()
        items.addAll(newItems)
        focusedPosition = RecyclerView.NO_POSITION
        diffResult.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SettingViewHolder {
        val binding = CellSettingBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SettingViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SettingViewHolder, position: Int) {
        holder.bind(items[position], isLast = position == items.lastIndex)
    }

    override fun onBindViewHolder(
        holder: SettingViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            holder.bindFocusState(position == focusedPosition)
            holder.bindDivider(position == items.lastIndex)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class SettingViewHolder(
        private val binding: CellSettingBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick?.invoke(position, items[position])
                }
            }
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) {
                    return@setOnFocusChangeListener
                }
                if (hasFocus) {
                    val oldFocused = focusedPosition
                    focusedPosition = position
                    if (oldFocused != RecyclerView.NO_POSITION && oldFocused != position) {
                        notifyItemChanged(oldFocused, PAYLOAD_FOCUS)
                    }
                    notifyItemChanged(position, PAYLOAD_FOCUS)
                    return@setOnFocusChangeListener
                }
                if (focusedPosition == position) {
                    focusedPosition = RecyclerView.NO_POSITION
                    notifyItemChanged(position, PAYLOAD_FOCUS)
                }
            }
        }

        fun bind(item: SettingModel, isLast: Boolean) {
            binding.tvTitle.text = item.title
            binding.tvInfo.text = item.info
            bindDivider(isLast)
            bindFocusState(bindingAdapterPosition == focusedPosition)
        }

        fun bindDivider(isLast: Boolean) {
            binding.dividerLine.visibility = if (isLast) View.GONE else View.VISIBLE
        }

        fun bindFocusState(isFocused: Boolean) {
            binding.root.isSelected = isFocused
            binding.iconArrow.alpha = if (isFocused) 1f else 0.72f
            binding.tvInfo.alpha = if (isFocused) 1f else 0.86f
            val targetTranslation = if (isFocused) binding.root.resources.displayMetrics.density * 6f else 0f
            binding.root.animate()
                .scaleX(if (isFocused) 1.02f else 1f)
                .scaleY(if (isFocused) 1.02f else 1f)
                .translationX(targetTranslation)
                .setDuration(120L)
                .start()
            ViewCompat.setElevation(binding.root, if (isFocused) binding.root.resources.displayMetrics.density * 3f else 0f)
        }
    }

    private companion object {
        private const val PAYLOAD_FOCUS = "payload_focus"
    }
}
