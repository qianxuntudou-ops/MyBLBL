package com.tutu.myblbl.feature.dynamic

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView.NO_POSITION
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
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
) : RecyclerView.Adapter<DynamicVideoAdapter.ViewHolder>() {

    private val data = mutableListOf<VideoModel>()
    var focusedView: View? = null
        private set

    fun setData(list: List<VideoModel>) {
        val deduplicated = deduplicate(list)
        val oldList = data.toList()
        val diffResult = DiffUtil.calculateDiff(VideoDiffCallback(oldList, deduplicated))
        focusedView = null
        data.clear()
        data.addAll(deduplicated)
        diffResult.dispatchUpdatesTo(this)
    }

    fun addData(list: List<VideoModel>) {
        val deduplicated = deduplicate(list).filter { incoming ->
            data.none { existing -> videoKey(existing) == videoKey(incoming) }
        }
        if (deduplicated.isEmpty()) {
            return
        }
        val startPosition = data.size
        data.addAll(deduplicated)
        notifyItemRangeInserted(startPosition, deduplicated.size)
    }

    fun getItemsSnapshot(): List<VideoModel> = data.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = CellVideoBinding.inflate(
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

    private fun deduplicate(source: List<VideoModel>): List<VideoModel> {
        if (source.isEmpty()) {
            return emptyList()
        }
        val seenKeys = LinkedHashSet<String>(source.size)
        return source.filter { seenKeys.add(videoKey(it)) }
    }

    private fun videoKey(video: VideoModel): String {
        return when {
            video.bvid.isNotBlank() -> "bvid:${video.bvid}"
            video.aid > 0 -> "aid:${video.aid}"
            video.cid > 0 -> "cid:${video.cid}"
            else -> "title:${video.title}|cover:${video.coverUrl}"
        }
    }

    private fun removeBlockedItems(blockedName: String) {
        val oldList = data.toList()
        val filtered = oldList.filter { !it.authorName.equals(blockedName, ignoreCase = true) }
        if (filtered.size == oldList.size) return
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldList.size
            override fun getNewListSize(): Int = filtered.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                videoKey(oldList[oldPos]) == videoKey(filtered[newPos])
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                oldList[oldPos] == filtered[newPos]
        })
        data.clear()
        data.addAll(filtered)
        diffResult.dispatchUpdatesTo(this)
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
                    onItemClick(data[position])
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
                if (position != NO_POSITION && position < data.size) {
                    val video = data[position]
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
            if (position == NO_POSITION || position !in data.indices) {
                return ""
            }
            return data[position].bangumi?.longTitle?.takeIf { it.isNotBlank() } ?: data[position].title
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

    private class VideoDiffCallback(
        private val oldList: List<VideoModel>,
        private val newList: List<VideoModel>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size

        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return dynamicVideoKey(oldList[oldItemPosition]) == dynamicVideoKey(newList[newItemPosition])
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition] == newList[newItemPosition]
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
