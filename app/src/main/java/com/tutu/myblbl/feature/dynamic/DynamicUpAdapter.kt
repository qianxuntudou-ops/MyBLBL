package com.tutu.myblbl.feature.dynamic

import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellFollowingBinding
import com.tutu.myblbl.model.user.FollowingModel
import com.tutu.myblbl.utils.AppLog
import com.tutu.myblbl.utils.ImageLoader

class DynamicUpAdapter(
    private val onItemClick: (FollowingModel) -> Unit,
    private val onLeftEdge: () -> Boolean = { false },
    private val onRightEdge: () -> Boolean = { false },
    private val debugTag: String? = null
) : RecyclerView.Adapter<DynamicUpAdapter.ViewHolder>() {

    private val data = mutableListOf<FollowingModel>()
    private var selectedPosition = 0

    fun setData(list: List<FollowingModel>) {
        val oldList = data.toList()
        val diffResult = DiffUtil.calculateDiff(FollowingDiffCallback(oldList, list))
        data.clear()
        data.addAll(list)
        selectedPosition = selectedPosition.coerceIn(0, (data.lastIndex).coerceAtLeast(0))
        diffResult.dispatchUpdatesTo(this)
    }

    fun getData(): List<FollowingModel> = data.toList()

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
        holder.bind(data[position], position == selectedPosition)
    }

    override fun getItemCount(): Int = data.size

    inner class ViewHolder(
        private val binding: CellFollowingBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(data[position])
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
            } else {
                ImageLoader.loadCircle(
                    imageView = binding.imageAvatar,
                    url = item.face,
                    placeholder = R.drawable.default_avatar,
                    error = R.drawable.default_avatar
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

    private class FollowingDiffCallback(
        private val oldList: List<FollowingModel>,
        private val newList: List<FollowingModel>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size

        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldItem = oldList[oldItemPosition]
            val newItem = newList[newItemPosition]
            return when {
                oldItem.mid > 0L && newItem.mid > 0L -> oldItem.mid == newItem.mid
                else -> oldItem.uname == newItem.uname
            }
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition] == newList[newItemPosition]
        }
    }
}
