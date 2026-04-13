package com.tutu.myblbl.feature.dynamic

import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellFollowingBinding
import com.tutu.myblbl.model.user.FollowingModel
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.ui.image.ImageLoader

class DynamicUpAdapter(
    private val onItemClick: (FollowingModel) -> Unit,
    private val onItemFocused: (() -> Unit)? = null,
    private val onLeftEdge: () -> Boolean = { false },
    private val onRightEdge: () -> Boolean = { false },
    private val debugTag: String? = null
) : ListAdapter<FollowingModel, DynamicUpAdapter.ViewHolder>(DIFF_CALLBACK) {

    private var selectedPosition = 0

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<FollowingModel>() {
            override fun areItemsTheSame(oldItem: FollowingModel, newItem: FollowingModel): Boolean {
                return when {
                    oldItem.mid > 0L && newItem.mid > 0L -> oldItem.mid == newItem.mid
                    else -> oldItem.uname == newItem.uname
                }
            }

            override fun areContentsTheSame(oldItem: FollowingModel, newItem: FollowingModel): Boolean {
                return oldItem == newItem
            }
        }
    }

    fun setData(list: List<FollowingModel>) {
        submitList(list)
        selectedPosition = selectedPosition.coerceIn(0, (list.lastIndex).coerceAtLeast(0))
    }

    fun getData(): List<FollowingModel> = currentList.toList()

    fun setSelectedPosition(position: Int) {
        val oldPosition = selectedPosition
        selectedPosition = position
        if (oldPosition >= 0) {
            notifyItemChanged(oldPosition)
        }
        if (position >= 0) {
            notifyItemChanged(position)
        }
    }

    fun getSelectedPosition(): Int = selectedPosition

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = CellFollowingBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position == selectedPosition)
    }

    inner class ViewHolder(
        private val binding: CellFollowingBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(currentList[position])
                }
            }
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                debugTag?.let {
                    AppLog.d(
                        it,
                        "up item focus: position=$bindingAdapterPosition hasFocus=$hasFocus name=${binding.textName.text}"
                    )
                }
                binding.textName.isSelected = hasFocus
                if (hasFocus) {
                    onItemFocused?.invoke()
                }
            }
            binding.root.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    return@setOnKeyListener false
                }
                debugTag?.let {
                    AppLog.d(
                        it,
                        "up item key: position=$bindingAdapterPosition key=${keyName(keyCode)}"
                    )
                }
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> onLeftEdge()
                    KeyEvent.KEYCODE_DPAD_RIGHT -> onRightEdge()
                    else -> false
                }
            }
        }

        fun bind(item: FollowingModel, isSelected: Boolean) {
            binding.root.isSelected = isSelected
            binding.indicator.visibility = if (isSelected) android.view.View.VISIBLE else android.view.View.INVISIBLE
            binding.textName.text = if (item.mid == 0L) {
                binding.root.context.getString(R.string.all_dynamic)
            } else {
                item.uname
            }

            if (item.mid == 0L) {
                binding.imageAvatar.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                binding.imageAvatar.setImageResource(R.drawable.ic_dynamic)
                binding.imageAvatar.setBadge(officialVerifyType = -1)
            } else {
                ImageLoader.loadCircle(
                    imageView = binding.imageAvatar,
                    url = item.face,
                    placeholder = R.drawable.default_avatar,
                    error = R.drawable.default_avatar
                )
                binding.imageAvatar.setBadge(
                    officialVerifyType = item.officialVerify?.type ?: -1
                )
            }
        }

        private fun keyName(keyCode: Int): String {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> "UP"
                KeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
                KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
                KeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
                else -> keyCode.toString()
            }
        }
    }
}
