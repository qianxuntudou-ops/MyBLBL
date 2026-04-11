package com.tutu.myblbl.feature.live

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellUserBinding
import com.tutu.myblbl.model.live.LiveAreaCategory
import com.tutu.myblbl.core.ui.focus.VideoCardFocusHelper

class LiveAreaAdapter(
    private val onItemClick: (LiveAreaCategory) -> Unit,
    private val onTopEdgeUp: (() -> Boolean)? = null
) : ListAdapter<LiveAreaCategory, LiveAreaAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<LiveAreaCategory>() {
            override fun areItemsTheSame(oldItem: LiveAreaCategory, newItem: LiveAreaCategory): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: LiveAreaCategory, newItem: LiveAreaCategory): Boolean {
                return oldItem == newItem
            }
        }
    }

    fun setData(list: List<LiveAreaCategory>) {
        submitList(list)
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
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: CellUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.textSub.visibility = View.GONE
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(currentList[position])
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
