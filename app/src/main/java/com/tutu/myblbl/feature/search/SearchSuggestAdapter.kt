package com.tutu.myblbl.feature.search

import android.view.KeyEvent
import android.view.View
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellSearchRecentlyBinding
import com.tutu.myblbl.model.search.HotWordModel

class SearchSuggestAdapter(
    private val onItemClick: (String) -> Unit,
    private val onClearHistory: (() -> Unit)? = null,
    private val onLeftEdge: ((View) -> Boolean)? = null,
    private val onRightEdge: ((View) -> Boolean)? = null
) : ListAdapter<HotWordModel, SearchSuggestAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<HotWordModel>() {
            override fun areItemsTheSame(oldItem: HotWordModel, newItem: HotWordModel): Boolean {
                return oldItem.keyword == newItem.keyword && oldItem.hotId == newItem.hotId
            }

            override fun areContentsTheSame(oldItem: HotWordModel, newItem: HotWordModel): Boolean {
                return oldItem == newItem
            }
        }
    }

    fun setData(list: List<HotWordModel>) {
        submitList(list)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = CellSearchRecentlyBinding.inflate(
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
        private val binding: CellSearchRecentlyBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.clickView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val model = currentList[position]
                    if (model.isClearHistory) {
                        onClearHistory?.invoke()
                    } else {
                        onItemClick(model.keyword)
                    }
                }
            }
            binding.clickView.setOnKeyListener { view, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    return@setOnKeyListener false
                }
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> onLeftEdge?.invoke(view) == true
                    KeyEvent.KEYCODE_DPAD_RIGHT -> onRightEdge?.invoke(view) == true
                    else -> false
                }
            }
        }

        fun bind(model: HotWordModel) {
            if (model.isClearHistory) {
                binding.textTitle.setText(R.string.clear)
            } else {
                binding.textTitle.text = model.showName
            }
            binding.clickView.tag = model.keyword
            updateIcon(binding.imageIcon, model)
        }

        private fun updateIcon(icon: AppCompatImageView, model: HotWordModel) {
            when {
                model.isClearHistory -> {
                    icon.visibility = AppCompatImageView.VISIBLE
                    icon.setImageResource(R.drawable.ic_delete)
                }
                model.isHistory -> {
                    icon.visibility = AppCompatImageView.VISIBLE
                    icon.setImageResource(R.drawable.ic_history)
                }
                model.isSuggest -> {
                    icon.visibility = AppCompatImageView.GONE
                }
                else -> {
                    icon.visibility = AppCompatImageView.VISIBLE
                    icon.setImageResource(R.drawable.ic_hot)
                }
            }
        }
    }
}
