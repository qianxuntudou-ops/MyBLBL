package com.tutu.myblbl.feature.dynamic

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.NO_POSITION
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellVideoBinding
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.core.common.format.NumberUtils
import com.tutu.myblbl.core.common.time.TimeUtils
import com.tutu.myblbl.core.ui.focus.VideoCardFocusHelper

class DynamicVideoAdapter(
    private val onItemClick: (VideoModel) -> Unit,
    private val onItemFocused: (Int) -> Unit,
    private val onLeftEdge: () -> Boolean = { false },
    private val debugTag: String? = null
) : ListAdapter<VideoModel, DynamicVideoAdapter.ViewHolder>(DiffCallback) {

    var focusedView: View? = null
        private set

    fun setData(list: List<VideoModel>) {
        val deduplicated = deduplicate(list)
        focusedView = null
        submitList(deduplicated)
    }

    fun addData(list: List<VideoModel>) {
        val deduplicated = deduplicate(list).filter { incoming ->
            currentList.none { existing -> dynamicVideoKey(existing) == dynamicVideoKey(incoming) }
        }
        if (deduplicated.isEmpty()) return
        submitList(currentList + deduplicated)
    }

    fun getItemsSnapshot(): List<VideoModel> = currentList.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = CellVideoBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    private fun deduplicate(source: List<VideoModel>): List<VideoModel> {
        if (source.isEmpty()) return emptyList()
        val seenKeys = LinkedHashSet<String>(source.size)
        return source.filter { seenKeys.add(dynamicVideoKey(it)) }
    }

    private fun removeBlockedItems(blockedName: String) {
        val filtered = currentList.filter { !it.authorName.equals(blockedName, ignoreCase = true) }
        if (filtered.size == currentList.size) return
        submitList(filtered)
        focusedView?.requestFocus()
    }

    inner class ViewHolder(
        private val binding: CellVideoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val handler = Handler(Looper.getMainLooper())
        private var longPressRunnable: Runnable? = null
        private val longPressThreshold = 5_000L
        private var longPressTriggered = false

        init {
            binding.root.setOnClickListener {
                if (longPressTriggered) {
                    longPressTriggered = false
                    return@setOnClickListener
                }
                val position = bindingAdapterPosition
                if (position != NO_POSITION) {
                    onItemClick(getItem(position))
                }
            }
            binding.root.setOnFocusChangeListener { view, hasFocus ->
                val position = bindingAdapterPosition
                debugTag?.let {
                    AppLog.d(
                        it,
                        "video card focus: position=$position hasFocus=$hasFocus title=${currentTitle().take(30)}"
                    )
                }
                if (hasFocus && position != NO_POSITION) {
                    focusedView = view
                    onItemFocused(position)
                }
            }
            binding.root.setOnKeyListener { _, keyCode, event ->
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
                } else if (event.action == KeyEvent.ACTION_DOWN) {
                    debugTag?.let {
                        AppLog.d(it, "video card key: position=$bindingAdapterPosition key=${keyName(keyCode)}")
                    }
                }
                false
            }
            binding.root.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> startLongPressTimer()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelLongPressTimer()
                }
                false
            }
            VideoCardFocusHelper.bindSidebarExit(
                view = binding.root,
                onLeftEdge = onLeftEdge,
                debugTag = debugTag
            )
        }

        private fun startLongPressTimer() {
            cancelLongPressTimer()
            longPressTriggered = false
            longPressRunnable = Runnable {
                val position = bindingAdapterPosition
                if (position != NO_POSITION && position < itemCount) {
                    val video = getItem(position)
                    val authorName = video.authorName
                    if (authorName.isNotBlank()) {
                        longPressTriggered = true
                        ContentFilter.addBlockedUpName(itemView.context, authorName)
                        Toast.makeText(
                            itemView.context,
                            itemView.context.getString(R.string.blocked_up_toast, authorName),
                            Toast.LENGTH_LONG
                        ).show()
                        AppLog.d("DynamicVideoAdapter", "Blocked UP: $authorName")
                        removeBlockedItems(authorName)
                    }
                }
            }
            handler.postDelayed(longPressRunnable!!, longPressThreshold)
        }

        private fun cancelLongPressTimer() {
            longPressRunnable?.let { handler.removeCallbacks(it) }
            longPressRunnable = null
        }

        fun bind(item: VideoModel) {
            val bangumi = item.bangumi
            val ownerName = item.authorName
            val publishText = formatPublishTime(item)

            if (bangumi != null) {
                binding.textView.text = bangumi.longTitle
                ImageLoader.loadVideoCover(
                    imageView = binding.imageView,
                    url = bangumi.cover
                )
            } else {
                binding.textView.text = item.title
                ImageLoader.loadVideoCover(
                    imageView = binding.imageView,
                    url = item.coverUrl
                )
            }

            if (ownerName.isNotBlank()) {
                binding.textViewOwner.text = if (publishText.isNotBlank()) {
                    "$ownerName · $publishText"
                } else {
                    ownerName
                }
                binding.imageAvatar.visibility = View.VISIBLE
            } else {
                binding.textViewOwner.text = publishText
                binding.imageAvatar.visibility = View.GONE
            }

            binding.textPlayCount.text = NumberUtils.formatCount(item.viewCount)
            binding.textDanmakuCount.text = NumberUtils.formatCount(item.danmakuCount)
            binding.textDuration.text = NumberUtils.formatDuration(item.durationValue.coerceAtLeast(0L))
        }

        private fun formatPublishTime(video: VideoModel): String {
            val publishedAt = video.pubDate
            return if (publishedAt > 0) {
                TimeUtils.formatRelativeTime(publishedAt)
            } else {
                ""
            }
        }

        private fun currentTitle(): String {
            val position = bindingAdapterPosition
            if (position == NO_POSITION || position !in currentList.indices) {
                return ""
            }
            val item = getItem(position)
            return item.bangumi?.longTitle?.takeIf { it.isNotBlank() } ?: item.title
        }

        private fun keyName(keyCode: Int): String {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> "UP"
                KeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
                KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
                KeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
                else -> keyCode.toString()
            }
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<VideoModel>() {
            override fun areItemsTheSame(oldItem: VideoModel, newItem: VideoModel): Boolean {
                return dynamicVideoKey(oldItem) == dynamicVideoKey(newItem)
            }

            override fun areContentsTheSame(oldItem: VideoModel, newItem: VideoModel): Boolean {
                return oldItem == newItem
            }
        }
    }
}

private fun dynamicVideoKey(video: VideoModel): String {
    return when {
        video.bvid.isNotBlank() -> "bvid:${video.bvid}"
        video.aid > 0 -> "aid:${video.aid}"
        video.cid > 0 -> "cid:${video.cid}"
        else -> "title:${video.title}|cover:${video.coverUrl}"
    }
}
