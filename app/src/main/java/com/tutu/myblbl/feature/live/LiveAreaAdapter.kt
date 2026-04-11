package com.tutu.myblbl.feature.live

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellUserBinding
import com.tutu.myblbl.model.live.LiveAreaCategory
import com.tutu.myblbl.core.ui.focus.VideoCardFocusHelper

class LiveAreaAdapter(
    private val onItemClick: (LiveAreaCategory) -> Unit,
    private val onTopEdgeUp: (() -> Boolean)? = null
) : RecyclerView.Adapter<LiveAreaAdapter.ViewHolder>() {

    private val data = mutableListOf<LiveAreaCategory>()

    fun setData(list: List<LiveAreaCategory>) {
        val diffResult = DiffUtil.calculateDiff(
            SimpleDiffCallback(data, list) { oldItem, newItem ->
                oldItem.id == newItem.id
            }
        )
        data.clear()
        data.addAll(list)
        diffResult.dispatchUpdatesTo(this)
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
        holder.bind(data[position])
    }

    override fun getItemCount(): Int = data.size

    inner class ViewHolder(
        private val binding: CellUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.textSub.visibility = View.GONE
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(data[position])
                }
            }
            VideoCardFocusHelper.bindSidebarExit(
                view = binding.root,
                onTopEdgeUp = onTopEdgeUp
            )
        }

        fun bind(item: LiveAreaCategory) {
            binding.textView.text = item.title.ifBlank { item.name }
            Glide.with(binding.imageView)
                .load(item.pic)
                .placeholder(R.drawable.default_avatar)
                .into(binding.imageView)
        }
    }
}

private class SimpleDiffCallback<T>(
    private val oldList: List<T>,
    private val newList: List<T>,
    private val idResolver: (T, T) -> Boolean
) : DiffUtil.Callback() {

    override fun getOldListSize(): Int = oldList.size

    override fun getNewListSize(): Int = newList.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return idResolver(oldList[oldItemPosition], newList[newItemPosition])
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldList[oldItemPosition] == newList[newItemPosition]
    }
}
