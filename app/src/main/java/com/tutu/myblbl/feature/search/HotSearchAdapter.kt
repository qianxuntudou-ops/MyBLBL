package com.tutu.myblbl.feature.search

import android.view.KeyEvent
import android.view.View
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellSearchRecentlyBinding
import com.tutu.myblbl.model.search.HotWordModel

class HotSearchAdapter(
    private val onItemClick: (String) -> Unit,
    private val onLeftEdge: ((View) -> Boolean)? = null
) : RecyclerView.Adapter<HotSearchAdapter.ViewHolder>() {

    private val data = mutableListOf<HotWordModel>()

    fun setData(list: List<HotWordModel>) {
        updateData(list)
    }

    fun setKeywords(keywords: List<String>) {
        val keywordRows = keywords.mapIndexed { index, keyword ->
            HotWordModel.createHot(
                keyword = keyword,
                showName = keyword,
                pos = index + 1
            )
        }
        updateData(keywordRows)
    }

    private fun updateData(newList: List<HotWordModel>) {
        val diffResult = DiffUtil.calculateDiff(HotWordDiff(data, newList))
        data.clear()
        data.addAll(newList)
        diffResult.dispatchUpdatesTo(this)
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
        holder.bind(data[position])
    }

    override fun getItemCount(): Int = data.size

    inner class ViewHolder(
        private val binding: CellSearchRecentlyBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.clickView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(data[position].keyword)
                }
            }
            binding.clickView.setOnKeyListener { view, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    return@setOnKeyListener false
                }
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> onLeftEdge?.invoke(view) == true
                    else -> false
                }
            }
        }

        fun bind(model: HotWordModel) {
            binding.clickView.tag = model.keyword
            binding.textTitle.text = model.showName
            updateIcon(binding.imageIcon, model)
        }

        private fun updateIcon(icon: AppCompatImageView, model: HotWordModel) {
            when {
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

    private class HotWordDiff(
        private val oldList: List<HotWordModel>,
        private val newList: List<HotWordModel>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size

        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].keyword == newList[newItemPosition].keyword
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition] == newList[newItemPosition]
        }
    }
}
