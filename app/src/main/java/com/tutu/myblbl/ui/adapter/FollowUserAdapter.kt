package com.tutu.myblbl.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellUserBinding
import com.tutu.myblbl.model.user.FollowingModel
import com.tutu.myblbl.core.ui.image.ImageLoader

class FollowUserAdapter(
    private val onItemClick: (FollowingModel) -> Unit,
    private val onItemFocused: ((Int) -> Unit)? = null
) : ListAdapter<FollowingModel, FollowUserAdapter.ViewHolder>(DIFF_CALLBACK) {

    private var focusedPosition = RecyclerView.NO_POSITION

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<FollowingModel>() {
            override fun areItemsTheSame(oldItem: FollowingModel, newItem: FollowingModel): Boolean {
                return oldItem.mid == newItem.mid
            }

            override fun areContentsTheSame(oldItem: FollowingModel, newItem: FollowingModel): Boolean {
                return oldItem == newItem
            }
        }
    }

    fun setData(newItems: List<FollowingModel>) {
        focusedPosition = focusedPosition
            .takeIf { it != RecyclerView.NO_POSITION && it < newItems.size }
            ?: if (newItems.isEmpty()) RecyclerView.NO_POSITION else 0
        submitList(newItems)
    }

    fun addData(newItems: List<FollowingModel>) {
        submitList(currentList + newItems)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = CellUserBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position == focusedPosition)
    }

    fun getFocusedPosition(): Int = focusedPosition

    inner class ViewHolder(
        private val binding: CellUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(currentList[position])
                }
            }

            binding.root.setOnFocusChangeListener { _, hasFocus ->
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) {
                    return@setOnFocusChangeListener
                }
                if (hasFocus) {
                    val oldPosition = focusedPosition
                    focusedPosition = position
                    onItemFocused?.invoke(position)
                    if (oldPosition != RecyclerView.NO_POSITION && oldPosition != position) {
                        notifyItemChanged(oldPosition)
                    }
                    notifyItemChanged(position)
                } else if (focusedPosition == position) {
                    notifyItemChanged(position)
                }
            }
        }

        fun bind(item: FollowingModel, isFocused: Boolean) {
            binding.root.isSelected = isFocused
            binding.textView.isSelected = isFocused
            binding.textView.text = item.uname
            binding.textSub.text = item.sign
            binding.textSub.isVisible = item.sign.isNotBlank()

            ImageLoader.loadCircle(
                imageView = binding.imageView,
                url = item.face,
                placeholder = R.drawable.default_avatar,
                error = R.drawable.default_avatar
            )
        }
    }
}
