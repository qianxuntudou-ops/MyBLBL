package com.tutu.myblbl.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.View
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.databinding.CellSettingBinding
import com.tutu.myblbl.model.SettingModel

class SettingAdapter(
    private val onItemClick: ((position: Int, item: SettingModel) -> Unit)? = null
) : ListAdapter<SettingModel, SettingAdapter.SettingViewHolder>(DIFF_CALLBACK) {

    private var focusedPosition = RecyclerView.NO_POSITION

    companion object {
        private const val PAYLOAD_FOCUS = "payload_focus"

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SettingModel>() {
            override fun areItemsTheSame(oldItem: SettingModel, newItem: SettingModel): Boolean {
                return oldItem.title == newItem.title
            }

            override fun areContentsTheSame(oldItem: SettingModel, newItem: SettingModel): Boolean {
                return oldItem.title == newItem.title && oldItem.info == newItem.info
            }
        }
    }

    fun setData(newItems: List<SettingModel>) {
        focusedPosition = RecyclerView.NO_POSITION
        submitList(newItems)
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
        holder.bind(getItem(position), isLast = position == currentList.lastIndex)
    }

    override fun onBindViewHolder(
        holder: SettingViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            holder.bindContent(getItem(position))
            holder.bindFocusState(position == focusedPosition)
            holder.bindDivider(position == currentList.lastIndex)
        }
    }

    inner class SettingViewHolder(
        private val binding: CellSettingBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.clickView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick?.invoke(position, currentList[position])
                }
            }
            binding.clickView.setOnFocusChangeListener { _, hasFocus ->
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
            bindContent(item)
            bindDivider(isLast)
            bindFocusState(bindingAdapterPosition == focusedPosition)
        }

        fun bindContent(item: SettingModel) {
            binding.tvTitle.text = item.title
            binding.tvInfo.text = item.info
        }

        fun bindDivider(isLast: Boolean) {
            binding.dividerLine.visibility = if (isLast) View.GONE else View.VISIBLE
        }

        fun bindFocusState(isFocused: Boolean) {
            binding.clickView.isSelected = isFocused
            binding.iconArrow.alpha = if (isFocused) 1f else 0.72f
            binding.tvInfo.alpha = if (isFocused) 1f else 0.86f
            val targetTranslation = if (isFocused) binding.clickView.resources.displayMetrics.density * 4f else 0f
            binding.clickView.animate()
                .scaleX(if (isFocused) 1.01f else 1f)
                .scaleY(if (isFocused) 1.01f else 1f)
                .translationX(targetTranslation)
                .setDuration(120L)
                .start()
            ViewCompat.setElevation(binding.clickView, if (isFocused) binding.clickView.resources.displayMetrics.density * 2f else 0f)
        }
    }
}
