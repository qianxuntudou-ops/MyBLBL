package com.tutu.myblbl.core.ui.base

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.core.ui.focus.tv.TvFocusableAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class BaseAdapter<MODEL, VH : RecyclerView.ViewHolder> : RecyclerView.Adapter<VH>(), TvFocusableAdapter {

    internal val items = ArrayList<MODEL>()
    internal var showLoadMore = true

    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    protected open fun areItemsSame(old: MODEL, new: MODEL): Boolean = old == new
    protected open fun areContentsSame(old: MODEL, new: MODEL): Boolean = old == new
    protected open fun getFocusStableKey(item: MODEL): String? = null

    fun contentCount(): Int = items.size

    override fun focusableItemCount(): Int = contentCount()

    override fun stableKeyAt(position: Int): String? {
        return items.getOrNull(position)?.let(::getFocusStableKey)
    }

    override fun findPositionByStableKey(key: String): Int {
        return items.indexOfFirst { getFocusStableKey(it) == key }
            .takeIf { it >= 0 }
            ?: RecyclerView.NO_POSITION
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

    fun getItem(position: Int): MODEL? {
        return if (position in items.indices) items[position] else null
    }

    fun getItemsSnapshot(): List<MODEL> = items.toList()

    abstract fun onCreateContentViewHolder(parent: ViewGroup, viewType: Int): VH

    fun setData(data: List<MODEL>, onComplete: (() -> Unit)? = null) {
        val oldItems = items.toList()
        adapterScope.launch {
            val diffResult = withContext(Dispatchers.Default) {
                DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                    override fun getOldListSize() = oldItems.size
                    override fun getNewListSize() = data.size
                    override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                        areItemsSame(oldItems[oldPos], data[newPos])
                    override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                        areContentsSame(oldItems[oldPos], data[newPos])
                })
            }
            items.clear()
            items.addAll(data)
            diffResult.dispatchUpdatesTo(this@BaseAdapter)
            onComplete?.invoke()
        }
    }

    /**
     * 在后台线程计算 diff 并在主线程 dispatch 更新，完成后执行 [onComplete]。
     */
    internal fun submitItemsInBackground(
        newItems: List<MODEL>,
        areItemsTheSame: (old: MODEL, new: MODEL) -> Boolean,
        areContentsTheSame: (old: MODEL, new: MODEL) -> Boolean,
        onComplete: (() -> Unit)? = null
    ) {
        val oldItems = items.toList()
        adapterScope.launch {
            val diffResult = withContext(Dispatchers.Default) {
                DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                    override fun getOldListSize() = oldItems.size
                    override fun getNewListSize() = newItems.size
                    override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                        areItemsTheSame(oldItems[oldPos], newItems[newPos])
                    override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                        areContentsTheSame(oldItems[oldPos], newItems[newPos])
                })
            }
            items.clear()
            items.addAll(newItems)
            diffResult.dispatchUpdatesTo(this@BaseAdapter)
            onComplete?.invoke()
        }
    }

    fun clear() {
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
