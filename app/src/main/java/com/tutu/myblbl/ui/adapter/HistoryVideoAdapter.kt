package com.tutu.myblbl.ui.adapter

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.AppCompatTextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellVideoBinding
import com.tutu.myblbl.model.video.HistoryVideoModel
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.core.common.format.NumberUtils
import com.tutu.myblbl.core.common.time.TimeUtils
import com.tutu.myblbl.core.ui.focus.VideoCardFocusHelper

class HistoryVideoAdapter(
    private val onItemClick: (HistoryVideoModel) -> Unit,
    private val onTopEdgeUp: (() -> Boolean)? = null,
    private val onItemFocused: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<HistoryVideoAdapter.ViewHolder>() {

    private val items = mutableListOf<HistoryVideoModel>()
    private var focusedPosition = RecyclerView.NO_POSITION
    var focusedView: View? = null
        private set
    private var attachedRecyclerView: RecyclerView? = null

    init {
        setHasStableIds(true)
    }

    fun setData(newItems: List<HistoryVideoModel>) {
        val deduplicated = newItems.distinctBy(::itemKey)
        val oldList = items.toList()
        val diffResult = DiffUtil.calculateDiff(HistoryVideoDiffCallback(oldList, deduplicated))
        items.clear()
        items.addAll(deduplicated)
        focusedPosition = focusedPosition
            .takeIf { it != RecyclerView.NO_POSITION && it < items.size && hasActiveFocus() }
            ?: RecyclerView.NO_POSITION
        diffResult.dispatchUpdatesTo(this)
    }

    fun addData(newItems: List<HistoryVideoModel>) {
        val deduplicated = newItems
            .distinctBy(::itemKey)
            .filter { incoming -> items.none { existing -> itemKey(existing) == itemKey(incoming) } }
        if (deduplicated.isEmpty()) {
            return
        }
        val startPosition = items.size
        items.addAll(deduplicated)
        notifyItemRangeInserted(startPosition, deduplicated.size)
    }

    fun updateProgress(aid: Long, progress: Long, duration: Long) {
        if (aid <= 0L) {
            return
        }
        val index = items.indexOfFirst { (it.history?.oid ?: 0L) == aid }
        if (index < 0) {
            return
        }
        items[index] = items[index].copy(progress = progress, duration = duration)
        notifyItemChangedSafely(index)
    }

    fun updateProgressFromPlayer(
        aid: Long,
        cid: Long,
        @Suppress("UNUSED_PARAMETER") pageOrProgressMs: Long
    ) {
        if (aid <= 0L) {
            return
        }
        val index = items.indexOfFirst { (it.history?.oid ?: 0L) == aid }
        if (index < 0) {
            return
        }
        val item = items[index]
        val updatedItem = item.copy(
            history = item.history?.copy(cid = cid),
            progress = pageOrProgressMs.coerceAtLeast(0L) / 1000L,
            viewAt = System.currentTimeMillis() / 1000L
        )
        items[index] = updatedItem
        if (index == 0) {
            notifyItemChangedSafely(0)
            return
        }
        items.removeAt(index)
        items.add(0, updatedItem)
        if (focusedPosition == index) {
            focusedPosition = 0
        } else if (focusedPosition in 0 until index) {
            focusedPosition += 1
        }
        notifyItemMovedSafely(index, 0)
        notifyItemChangedSafely(0)
    }

    fun getFocusedPosition(): Int = focusedPosition

    fun getItemsSnapshot(): List<HistoryVideoModel> = items.toList()

    fun findPositionByKey(key: String): Int = items.indexOfFirst { itemKey(it) == key }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = CellVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(
            binding = binding,
            onItemClick = onItemClick,
            onTopEdgeUp = onTopEdgeUp,
            onItemFocused = onItemFocused,
            updateFocusedState = { view, position ->
                setFocusedState(focusedView, false)
                focusedView = view
                focusedPosition = position
                setFocusedState(view, true)
            },
            clearFocusedState = { view ->
                if (focusedView === view) {
                    focusedView = null
                    focusedPosition = RecyclerView.NO_POSITION
                }
                setFocusedState(view, false)
            },
            onItemBlocked = { blockedName -> removeBlockedItems(blockedName) }
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position == focusedPosition && hasActiveFocus())
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = itemKey(items[position]).hashCode().toLong()

    private fun removeBlockedItems(blockedName: String) {
        val oldList = items.toList()
        val filtered = oldList.filter { !it.authorName.equals(blockedName, ignoreCase = true) }
        if (filtered.size == oldList.size) return
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldList.size
            override fun getNewListSize(): Int = filtered.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                itemKey(oldList[oldPos]) == itemKey(filtered[newPos])
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                oldList[oldPos] == filtered[newPos]
        })
        items.clear()
        items.addAll(filtered)
        diffResult.dispatchUpdatesTo(this)
        focusedView?.requestFocus()
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        if (attachedRecyclerView === recyclerView) {
            attachedRecyclerView = null
        }
        focusedView = null
        focusedPosition = RecyclerView.NO_POSITION
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        if (focusedView === holder.itemView) {
            focusedView = null
            focusedPosition = RecyclerView.NO_POSITION
        }
        super.onViewRecycled(holder)
    }

    private fun notifyItemChangedSafely(position: Int) {
        val recyclerView = attachedRecyclerView
        if (recyclerView != null && recyclerView.isComputingLayout) {
            recyclerView.post { notifyItemChanged(position) }
        } else {
            notifyItemChanged(position)
        }
    }

    private fun notifyItemMovedSafely(fromPosition: Int, toPosition: Int) {
        val recyclerView = attachedRecyclerView
        if (recyclerView != null && recyclerView.isComputingLayout) {
            recyclerView.post { notifyItemMoved(fromPosition, toPosition) }
        } else {
            notifyItemMoved(fromPosition, toPosition)
        }
    }

    private fun setFocusedState(view: View?, focused: Boolean) {
        view ?: return
        view.isSelected = focused
        view.findViewById<AppCompatTextView>(com.tutu.myblbl.R.id.textView)?.isSelected = focused
    }

    private fun hasActiveFocus(): Boolean = focusedView?.hasFocus() == true

    class ViewHolder(
        private val binding: CellVideoBinding,
        private val onItemClick: (HistoryVideoModel) -> Unit,
        onTopEdgeUp: (() -> Boolean)?,
        private val onItemFocused: ((Int) -> Unit)?,
        private val updateFocusedState: (View, Int) -> Unit,
        private val clearFocusedState: (View) -> Unit,
        private val onItemBlocked: ((String) -> Unit)? = null
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentItem: HistoryVideoModel? = null
        private val handler = Handler(Looper.getMainLooper())
        private var longPressRunnable: Runnable? = null
        private val longPressThreshold = 5_000L
        private var longPressTriggered = false

        private val keyListener = View.OnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) {
                            startLongPressTimer()
                        }
                    }
                    KeyEvent.ACTION_UP -> {
                        cancelLongPressTimer()
                    }
                }
            }
            false
        }

        private fun startLongPressTimer() {
            cancelLongPressTimer()
            longPressTriggered = false
            longPressRunnable = Runnable {
                val item = currentItem ?: return@Runnable
                val authorName = item.authorName
                if (authorName.isNotBlank()) {
                    longPressTriggered = true
                    ContentFilter.addBlockedUpName(itemView.context, authorName)
                    Toast.makeText(
                        itemView.context,
                        itemView.context.getString(R.string.blocked_up_toast, authorName),
                        Toast.LENGTH_LONG
                    ).show()
                    AppLog.d("HistoryVideoAdapter", "Blocked UP: $authorName")
                    onItemBlocked?.invoke(authorName)
                }
            }
            handler.postDelayed(longPressRunnable!!, longPressThreshold)
        }

        private fun cancelLongPressTimer() {
            longPressRunnable?.let { handler.removeCallbacks(it) }
            longPressRunnable = null
        }

        init {
            binding.root.setOnClickListener {
                if (longPressTriggered) {
                    longPressTriggered = false
                    return@setOnClickListener
                }
                currentItem?.let(onItemClick)
            }
            binding.root.setOnKeyListener(keyListener)
            binding.root.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> startLongPressTimer()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelLongPressTimer()
                }
                false
            }
            binding.root.setOnFocusChangeListener { view, hasFocus ->
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) {
                    return@setOnFocusChangeListener
                }
                if (hasFocus) {
                    onItemFocused?.invoke(position)
                    updateFocusedState(view, position)
                } else {
                    clearFocusedState(binding.root)
                }
            }
            VideoCardFocusHelper.bindSidebarExit(binding.root, onTopEdgeUp)
            binding.imageAvatar.visibility = View.GONE
            binding.iconPlayCount.visibility = View.GONE
            binding.textPlayCount.visibility = View.GONE
            binding.iconDanmaku.visibility = View.GONE
            binding.textDanmakuCount.visibility = View.GONE
        }

        fun bind(item: HistoryVideoModel, isFocused: Boolean) {
            currentItem = item
            binding.root.isSelected = isFocused
            binding.textView.isSelected = isFocused
            binding.textView.text = item.title.ifBlank { item.showTitle }
            binding.imageAvatar.visibility = View.GONE
            binding.iconPlayCount.visibility = View.GONE
            binding.textPlayCount.visibility = View.GONE
            binding.iconDanmaku.visibility = View.GONE
            binding.textDanmakuCount.visibility = View.GONE

            binding.progressBar.visibility = View.VISIBLE
            val durationValue = item.duration.coerceAtLeast(0L)
            val progressValue = item.progress.coerceAtLeast(0L).coerceAtMost(durationValue)
            binding.progressBar.max = durationValue.toInt()
            binding.progressBar.progress = progressValue.toInt()

            binding.textDuration.text = if (item.history?.business == "live" && item.badge.isNotBlank()) {
                item.badge
            } else {
                "${NumberUtils.formatDuration(progressValue)}/${NumberUtils.formatDuration(durationValue)}"
            }
            binding.textViewOwner.text = TimeUtils.formatHistoryViewTime(item.viewAt)

            ImageLoader.loadVideoCover(
                imageView = binding.imageView,
                url = item.cover.ifBlank { item.covers?.firstOrNull().orEmpty() }
            )
        }
    }

    private class HistoryVideoDiffCallback(
        private val oldList: List<HistoryVideoModel>,
        private val newList: List<HistoryVideoModel>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size

        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return itemKey(oldList[oldItemPosition]) == itemKey(newList[newItemPosition])
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition] == newList[newItemPosition]
        }
    }
}

private fun itemKey(item: HistoryVideoModel): String {
    return when {
        item.bvid.isNotBlank() -> "bvid:${item.bvid}"
        (item.history?.oid ?: 0L) > 0L -> "aid:${item.history?.oid}"
        else -> "title:${item.title}|cover:${item.cover}"
    }
}
