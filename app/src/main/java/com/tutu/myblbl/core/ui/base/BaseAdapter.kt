package com.tutu.myblbl.core.ui.base

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R

abstract class BaseAdapter<MODEL, VH : RecyclerView.ViewHolder> : RecyclerView.Adapter<VH>() {

    internal val items = ArrayList<MODEL>()
    internal var showLoadMore = true
    internal var focusedView: View? = null
    internal var rememberedPosition: Int = RecyclerView.NO_POSITION

    fun contentCount(): Int = items.size

    fun getRememberedPosition(): Int = rememberedPosition

    fun rememberItemInteraction(view: View, position: Int) {
        if (position == RecyclerView.NO_POSITION) {
            return
        }
        focusedView = view
        rememberedPosition = position
    }

    fun setShowLoadMore(show: Boolean) {
        if (showLoadMore == show) {
            return
        }
        showLoadMore = show
        if (show) {
            notifyItemInserted(items.size)
        } else {
            notifyItemRemoved(items.size)
        }
    }

    fun addAll(list: List<MODEL>) {
        val size = items.size
        items.addAll(list)
        if (list.size == 1) {
            notifyItemInserted(size)
        } else {
            notifyItemRangeInserted(size, list.size)
        }
    }

    fun requestFocusOnFirstItem() {
        focusedView?.requestFocus()
    }

    fun getItem(position: Int): MODEL? {
        return if (position in items.indices) items[position] else null
    }

    fun getItemsSnapshot(): List<MODEL> = items.toList()

    abstract fun onCreateContentViewHolder(parent: ViewGroup, viewType: Int): VH

    fun setData(data: List<MODEL>) {
        val oldItemCount = itemCount
        focusedView = null
        rememberedPosition = rememberedPosition
            .takeIf { it != RecyclerView.NO_POSITION && it < data.size }
            ?: RecyclerView.NO_POSITION
        items.clear()
        items.addAll(data)
        val newItemCount = itemCount
        when {
            oldItemCount == 0 && newItemCount > 0 -> {
                notifyItemRangeInserted(0, newItemCount)
            }
            oldItemCount > 0 && newItemCount == 0 -> {
                notifyItemRangeRemoved(0, oldItemCount)
            }
            oldItemCount > 0 && newItemCount > 0 -> {
                val sharedCount = minOf(oldItemCount, newItemCount)
                if (sharedCount > 0) {
                    notifyItemRangeChanged(0, sharedCount)
                }
                if (newItemCount > oldItemCount) {
                    notifyItemRangeInserted(oldItemCount, newItemCount - oldItemCount)
                } else if (oldItemCount > newItemCount) {
                    notifyItemRangeRemoved(newItemCount, oldItemCount - newItemCount)
                }
            }
        }
    }

    fun clear() {
        focusedView = null
        rememberedPosition = RecyclerView.NO_POSITION
        items.clear()
    }

    override fun getItemCount(): Int {
        return if (showLoadMore && items.isNotEmpty()) items.size + 1 else items.size
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == items.size && showLoadMore && items.isNotEmpty()) {
            LOAD_MORE_TYPE
        } else {
            super.getItemViewType(position)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        if (viewType == LOAD_MORE_TYPE) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.view_load_more, parent, false)
            @Suppress("UNCHECKED_CAST")
            return LoadMoreViewHolder(view) as VH
        }
        return onCreateContentViewHolder(parent, viewType)
    }

    final override fun onBindViewHolder(holder: VH, position: Int) {
        if (position == items.size && showLoadMore && items.isNotEmpty()) {
            return
        }
        onBindContentViewHolder(holder, position)
    }

    abstract fun onBindContentViewHolder(holder: VH, position: Int)

    class LoadMoreViewHolder(view: View) : RecyclerView.ViewHolder(view)

    companion object {
        const val LOAD_MORE_TYPE = -1000
    }
}
