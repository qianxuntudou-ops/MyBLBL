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

class FavoriteHistoryAdapter(
    private val onItemClick: (HistoryVideoModel) -> Unit,
    private val onItemFocused: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<FavoriteHistoryAdapter.ViewHolder>() {

    private val items = mutableListOf<HistoryVideoModel>()
    private var focusedPosition = RecyclerView.NO_POSITION
    private var focusedView: View? = null

    init {
        setHasStableIds(true)
    }

    fun setData(newItems: List<HistoryVideoModel>) {
        val deduplicated = newItems.distinctBy(::favoriteHistoryItemKey)
        val oldList = items.toList()
        val diffResult = DiffUtil.calculateDiff(FavoriteHistoryDiffCallback(oldList, deduplicated))
        items.clear()
        items.addAll(deduplicated)
        focusedPosition = focusedPosition
            .takeIf { it != RecyclerView.NO_POSITION && it < items.size && hasActiveFocus() }
            ?: RecyclerView.NO_POSITION
        diffResult.dispatchUpdatesTo(this)
    }

    fun addData(newItems: List<HistoryVideoModel>) {
        val deduplicated = newItems
            .distinctBy(::favoriteHistoryItemKey)
            .filter { incoming ->
                items.none { existing ->
                    favoriteHistoryItemKey(existing) == favoriteHistoryItemKey(incoming)
                }
            }
        if (deduplicated.isEmpty()) {
            return
        }
        val startPosition = items.size
        items.addAll(deduplicated)
        notifyItemRangeInserted(startPosition, deduplicated.size)
    }

    fun getItem(position: Int): HistoryVideoModel? = items.getOrNull(position)

    fun getItemsSnapshot(): List<HistoryVideoModel> = items.toList()

    fun getFocusedPosition(): Int = focusedPosition

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = CellVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(
            binding = binding,
            onItemClick = onItemClick,
            onItemFocused = onItemFocused,
            updateFocusedPosition = { view, position ->
                setFocusedState(focusedView, false)
                focusedView = view
                focusedPosition = position
                setFocusedState(view, true)
            },
            clearFocusedPosition = { view ->
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

    override fun getItemId(position: Int): Long = favoriteHistoryItemKey(items[position]).hashCode().toLong()

    private fun removeBlockedItems(blockedName: String) {
        val oldList = items.toList()
        val filtered = oldList.filter { !it.authorName.equals(blockedName, ignoreCase = true) }
        if (filtered.size == oldList.size) return
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldList.size
            override fun getNewListSize(): Int = filtered.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                favoriteHistoryItemKey(oldList[oldPos]) == favoriteHistoryItemKey(filtered[newPos])
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                oldList[oldPos] == filtered[newPos]
        })
        items.clear()
        items.addAll(filtered)
        diffResult.dispatchUpdatesTo(this)
        focusedView?.requestFocus()
    }

    class ViewHolder(
        private val binding: CellVideoBinding,
        private val onItemClick: (HistoryVideoModel) -> Unit,
        private val onItemFocused: ((Int) -> Unit)?,
        private val updateFocusedPosition: (View, Int) -> Unit,
        private val clearFocusedPosition: (View) -> Unit,
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
                    AppLog.d("FavoriteHistoryAdapter", "Blocked UP: $authorName")
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
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) {
                    return@setOnFocusChangeListener
                }
                if (hasFocus) {
                    onItemFocused?.invoke(position)
                    updateFocusedPosition(binding.root, position)
                } else {
                    clearFocusedPosition(binding.root)
                }
            }
            VideoCardFocusHelper.bindSidebarExit(binding.root)
            binding.textView.maxLines = 1
            binding.textView.minLines = 1
            binding.imageAvatar.visibility = View.GONE
            binding.textDuration.visibility = View.GONE
        }

        fun bind(item: HistoryVideoModel, isFocused: Boolean) {
            currentItem = item
            binding.root.isSelected = isFocused
            binding.textView.isSelected = isFocused
            binding.textView.text = item.title.ifBlank { item.showTitle }
            binding.progressBar.visibility = View.GONE
            binding.imageAvatar.visibility = View.GONE
            binding.iconDanmaku.visibility = View.GONE
            binding.textDanmakuCount.visibility = View.GONE
            binding.textDuration.visibility = View.GONE

            binding.textViewOwner.text = binding.root.context.getString(
                com.tutu.myblbl.R.string.favorite_added_at,
                TimeUtils.formatTime(item.favTime)
            )

            val stat = item.cntInfo
            if (stat != null) {
                binding.iconPlayCount.visibility = View.VISIBLE
                binding.textPlayCount.visibility = View.VISIBLE
                binding.iconDanmaku.visibility = View.VISIBLE
                binding.textDanmakuCount.visibility = View.VISIBLE
                binding.textPlayCount.text = NumberUtils.formatCount(stat.play)
                binding.textDanmakuCount.text = NumberUtils.formatCount(stat.danmaku)
            } else {
                binding.iconPlayCount.visibility = View.GONE
                binding.textPlayCount.visibility = View.GONE
                binding.iconDanmaku.visibility = View.GONE
                binding.textDanmakuCount.visibility = View.GONE
            }

            ImageLoader.loadVideoCover(
                imageView = binding.imageView,
                url = item.cover
            )
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        if (focusedView === holder.itemView) {
            focusedView = null
            focusedPosition = RecyclerView.NO_POSITION
        }
        super.onViewRecycled(holder)
    }

    private fun setFocusedState(view: View?, focused: Boolean) {
        view ?: return
        view.isSelected = focused
        view.findViewById<AppCompatTextView>(com.tutu.myblbl.R.id.textView)?.isSelected = focused
    }

    private fun hasActiveFocus(): Boolean = focusedView?.hasFocus() == true

    private class FavoriteHistoryDiffCallback(
        private val oldList: List<HistoryVideoModel>,
        private val newList: List<HistoryVideoModel>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size

        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return favoriteHistoryItemKey(oldList[oldItemPosition]) ==
                favoriteHistoryItemKey(newList[newItemPosition])
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition] == newList[newItemPosition]
        }
    }
}

private fun favoriteHistoryItemKey(item: HistoryVideoModel): String {
    return when {
        item.bvid.isNotBlank() -> "bvid:${item.bvid}"
        (item.history?.oid ?: 0L) > 0L -> "aid:${item.history?.oid}"
        else -> "title:${item.title}|cover:${item.cover}"
    }
}
