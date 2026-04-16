package com.tutu.myblbl.ui.adapter

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.Toast
import androidx.appcompat.widget.AppCompatTextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellVideoBinding
import com.tutu.myblbl.model.video.HistoryVideoModel
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.core.common.format.NumberUtils
import com.tutu.myblbl.core.common.time.TimeUtils
import com.tutu.myblbl.core.ui.focus.VideoCardFocusHelper
import com.tutu.myblbl.ui.dialog.VideoCardMenuDialog

class HistoryVideoAdapter(
    private val onItemClick: (HistoryVideoModel) -> Unit,
    private val onTopEdgeUp: (() -> Boolean)? = null,
    private val onItemFocused: ((Int) -> Unit)? = null
) : ListAdapter<HistoryVideoModel, HistoryVideoAdapter.ViewHolder>(DiffCallback) {

    private var focusedPosition = RecyclerView.NO_POSITION
    var focusedView: View? = null
        private set

    init {
        setHasStableIds(true)
    }

    fun setData(
        newItems: List<HistoryVideoModel>,
        onCommitted: (() -> Unit)? = null
    ) {
        val deduplicated = newItems.distinctBy(::itemKey)
        focusedPosition = focusedPosition
            .takeIf { it != RecyclerView.NO_POSITION && it < deduplicated.size && hasActiveFocus() }
            ?: RecyclerView.NO_POSITION
        submitList(deduplicated) {
            onCommitted?.invoke()
        }
    }

    fun addData(newItems: List<HistoryVideoModel>) {
        val deduplicated = newItems
            .distinctBy(::itemKey)
            .filter { incoming -> currentList.none { existing -> itemKey(existing) == itemKey(incoming) } }
        if (deduplicated.isEmpty()) return
        submitList(currentList + deduplicated)
    }

    fun updateProgress(aid: Long, progress: Long, duration: Long) {
        if (aid <= 0L) return
        submitList(currentList.map {
            if ((it.history?.oid ?: 0L) == aid) it.copy(progress = progress, duration = duration)
            else it
        })
    }

    fun updateProgressFromPlayer(
        aid: Long,
        cid: Long,
        @Suppress("UNUSED_PARAMETER") pageOrProgressMs: Long
    ) {
        if (aid <= 0L) return
        val index = currentList.indexOfFirst { (it.history?.oid ?: 0L) == aid }
        if (index < 0) return
        val item = currentList[index]
        val updatedItem = item.copy(
            history = item.history?.copy(cid = cid),
            progress = pageOrProgressMs.coerceAtLeast(0L) / 1000L,
            viewAt = System.currentTimeMillis() / 1000L
        )
        val newList = currentList.toMutableList()
        newList[index] = updatedItem
        submitList(newList)
    }

    fun getFocusedPosition(): Int = focusedPosition

    fun getItemsSnapshot(): List<HistoryVideoModel> = currentList.toList()

    fun findPositionByKey(key: String): Int = currentList.indexOfFirst { itemKey(it) == key }

    fun clearFocusMemory() {
        setFocusedState(focusedView, false)
        focusedView = null
        focusedPosition = RecyclerView.NO_POSITION
    }

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
            onItemDisliked = { item -> removeDislikedItem(item) },
            onUpDisliked = { upName -> removeBlockedItems(upName) }
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position == focusedPosition && hasActiveFocus())
    }

    override fun getItemId(position: Int): Long = itemKey(getItem(position)).hashCode().toLong()

    private fun removeBlockedItems(blockedName: String) {
        val filtered = currentList.filter { !it.authorName.equals(blockedName, ignoreCase = true) }
        if (filtered.size == currentList.size) return
        submitList(filtered)
        focusedView?.requestFocus()
    }

    private fun removeDislikedItem(item: HistoryVideoModel) {
        val key = itemKey(item)
        val filtered = currentList.filter { itemKey(it) != key }
        if (filtered.size == currentList.size) return
        submitList(filtered)
        focusedView?.requestFocus()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
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
        private val onItemDisliked: ((HistoryVideoModel) -> Unit)? = null,
        private val onUpDisliked: ((String) -> Unit)? = null
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentItem: HistoryVideoModel? = null
        private val handler = Handler(Looper.getMainLooper())
        private var longPressRunnable: Runnable? = null
        private var longPressTriggered = false

        private val keyListener = View.OnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
                showCardMenu()
                true
            } else {
                false
            }
        }

        private fun showCardMenu() {
            cancelLongPress()
            val item = currentItem ?: return
            val video = item.toVideoModel()
            VideoCardMenuDialog(
                context = itemView.context,
                video = video,
                onDislikeVideo = { onItemDisliked?.invoke(item) },
                onDislikeUp = { upName -> onUpDisliked?.invoke(upName) }
            ).show()
        }

        private fun startLongPress() {
            cancelLongPress()
            longPressTriggered = false
            longPressRunnable = Runnable {
                longPressTriggered = true
                showCardMenu()
            }
            handler.postDelayed(longPressRunnable!!, ViewConfiguration.getLongPressTimeout().toLong())
        }

        private fun cancelLongPress() {
            longPressRunnable?.let { handler.removeCallbacks(it) }
            longPressRunnable = null
        }

        init {
            binding.root.setOnClickListener {
                if (longPressTriggered) {
                    longPressTriggered = false
                    return@setOnClickListener
                }
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemFocused?.invoke(position)
                    updateFocusedState(binding.root, position)
                }
                currentItem?.let(onItemClick)
            }
            binding.root.setOnKeyListener(keyListener)
            binding.root.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> startLongPress()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelLongPress()
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
            binding.textPortraitBadge.visibility = View.GONE
            binding.iconPlayCount.visibility = View.GONE
            binding.textPlayCount.visibility = View.GONE
            binding.iconDanmaku.visibility = View.GONE
            binding.textDanmakuCount.visibility = View.GONE
        }

        fun bind(item: HistoryVideoModel, isFocused: Boolean) {
            currentItem = item

            val d = item.dimension
            AppLog.d("PortraitDebug", "[History] title=${item.title.take(20)} dimension=${d?.width}x${d?.height} rotate=${d?.rotate} isPortrait=${item.isPortrait}")

            binding.root.isSelected = isFocused
            binding.textView.isSelected = isFocused
            binding.textView.text = item.title.ifBlank { item.showTitle }
            binding.imageAvatar.visibility = View.GONE
            binding.textPortraitBadge.visibility = if (item.isPortrait) View.VISIBLE else View.GONE
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
            binding.textChargeBadge.visibility = if (item.isChargingExclusive) View.VISIBLE else View.GONE

            ImageLoader.loadVideoCover(
                imageView = binding.imageView,
                url = item.cover.ifBlank { item.covers?.firstOrNull().orEmpty() }
            )
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<HistoryVideoModel>() {
            override fun areItemsTheSame(oldItem: HistoryVideoModel, newItem: HistoryVideoModel): Boolean {
                return itemKey(oldItem) == itemKey(newItem)
            }

            override fun areContentsTheSame(oldItem: HistoryVideoModel, newItem: HistoryVideoModel): Boolean {
                return oldItem == newItem
            }
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
