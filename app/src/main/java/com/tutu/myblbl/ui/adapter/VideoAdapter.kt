package com.tutu.myblbl.ui.adapter

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellVideoBinding
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.core.ui.base.BaseAdapter
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.core.common.format.NumberUtils
import com.tutu.myblbl.core.common.time.TimeUtils
import com.tutu.myblbl.core.ui.focus.VideoCardFocusHelper

class VideoAdapter(
    private val displayStyle: DisplayStyle = DisplayStyle.DEFAULT,
    private val itemWidthPx: Int? = null,
    private val onItemClick: (VideoModel) -> Unit = {},
    private val onTopEdgeUp: (() -> Boolean)? = null,
    private val onBottomEdgeDown: (() -> Boolean)? = null,
    private val focusDebugTag: String? = null,
    private val onItemFocused: ((Int) -> Unit)? = null
) : BaseAdapter<VideoModel, VideoAdapter.VideoViewHolder>() {

    init {
        setHasStableIds(true)
    }

    enum class DisplayStyle {
        DEFAULT,
        HISTORY
    }

    private var onItemClickListener: ((View, VideoModel) -> Unit)? = null

    fun setOnItemClickListener(listener: (View, VideoModel) -> Unit) {
        onItemClickListener = listener
    }

    fun addData(newItems: List<VideoModel>) {
        val deduplicated = deduplicate(newItems).filter { incoming ->
            items.none { existing -> videoKey(existing) == videoKey(incoming) }
        }
        if (deduplicated.isEmpty()) {
            return
        }
        val startPosition = items.size
        items.addAll(deduplicated)
        notifyItemRangeInserted(startPosition, deduplicated.size)
    }

    override fun onCreateContentViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = CellVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        itemWidthPx?.let { width ->
            val layoutParams = binding.root.layoutParams
                ?: androidx.recyclerview.widget.RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            layoutParams.width = width
            binding.root.layoutParams = layoutParams
        }
        val clickLambda: (View, VideoModel) -> Unit = { v, item ->
            onItemClickListener?.invoke(v, item) ?: onItemClick(item)
        }
        return VideoViewHolder(
            binding,
            displayStyle,
            clickLambda,
            onTopEdgeUp,
            onBottomEdgeDown,
            focusDebugTag,
            { view, position, hasFocus ->
                if (hasFocus) {
                    focusedView = view
                    onItemFocused?.invoke(position)
                }
            },
            { blockedName -> removeBlockedItems(blockedName) }
        )
    }

    override fun onBindContentViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemId(position: Int): Long {
        return if (position < items.size) videoKey(items[position]).hashCode().toLong() else super.getItemId(position)
    }

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
        val oldList = items.toList()
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
        items.clear()
        items.addAll(filtered)
        diffResult.dispatchUpdatesTo(this)
        focusedView?.requestFocus()
    }

    class VideoViewHolder(
        private val binding: CellVideoBinding,
        private val displayStyle: DisplayStyle,
        private val onItemClick: (View, VideoModel) -> Unit,
        onTopEdgeUp: (() -> Boolean)?,
        onBottomEdgeDown: (() -> Boolean)?,
        private val focusDebugTag: String? = null,
        onFocusChange: ((View, Int, Boolean) -> Unit)? = null,
        private val onItemBlocked: ((String) -> Unit)? = null
    ) : androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root) {

        private var currentVideo: VideoModel? = null
        private val handler = Handler(Looper.getMainLooper())
        private var longPressRunnable: Runnable? = null
        private var longPressStartTime: Long = 0L
        private val longPressThreshold = 5_000L
        private var longPressTriggered = false

        private val keyListener = View.OnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                when (event.action) {
                    android.view.KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) {
                            startLongPressTimer()
                        }
                    }
                    android.view.KeyEvent.ACTION_UP -> {
                        cancelLongPressTimer()
                    }
                }
            }
            false
        }

        private fun startLongPressTimer() {
            cancelLongPressTimer()
            longPressTriggered = false
            longPressStartTime = System.currentTimeMillis()
            longPressRunnable = Runnable {
                val video = currentVideo ?: return@Runnable
                val authorName = video.authorName
                if (authorName.isNotBlank()) {
                    longPressTriggered = true
                    ContentFilter.addBlockedUpName(itemView.context, authorName)
                    Toast.makeText(
                        itemView.context,
                        itemView.context.getString(R.string.blocked_up_toast, authorName),
                        Toast.LENGTH_LONG
                    ).show()
                    AppLog.d("VideoAdapter", "Blocked UP: $authorName")
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
                currentVideo?.let { onItemClick(binding.root, it) }
            }
            binding.root.setOnKeyListener(keyListener)
            binding.root.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> startLongPressTimer()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelLongPressTimer()
                }
                false
            }
            VideoCardFocusHelper.bindSidebarExit(
                view = binding.root,
                onTopEdgeUp = onTopEdgeUp,
                onBottomEdgeDown = onBottomEdgeDown,
                debugTag = focusDebugTag
            )
            if (onFocusChange != null) {
                binding.root.setOnFocusChangeListener { view, hasFocus ->
                    focusDebugTag?.let {
                        AppLog.d(
                            it,
                            "card focus: position=$bindingAdapterPosition hasFocus=$hasFocus title=${currentVideo?.title.orEmpty().take(30)}"
                        )
                    }
                    onFocusChange.invoke(view, bindingAdapterPosition, hasFocus)
                }
            }
        }

        fun bind(video: VideoModel) {
            currentVideo = video

            binding.textView.text = resolveDisplayTitle(video)
            when (displayStyle) {
                DisplayStyle.DEFAULT -> bindDefault(video)
                DisplayStyle.HISTORY -> bindHistory(video)
            }

            ImageLoader.loadVideoCover(
                imageView = binding.imageView,
                url = resolveCoverUrl(video)
            )
        }

        private fun bindDefault(video: VideoModel) {
            binding.progressBar.visibility = View.GONE
            binding.imageAvatar.visibility = View.VISIBLE
            binding.iconPlayCount.visibility = View.VISIBLE
            binding.textPlayCount.visibility = View.VISIBLE
            binding.iconDanmaku.visibility = View.VISIBLE
            binding.textDanmakuCount.visibility = View.VISIBLE

            val ownerName = video.authorName
            val publishLabel = formatPublishTime(video)
            binding.textViewOwner.text = buildString {
                if (ownerName.isNotBlank()) {
                    append(ownerName)
                }
                if (publishLabel.isNotBlank()) {
                    if (isNotEmpty()) {
                        append(" · ")
                    }
                    append(publishLabel)
                }
            }
            binding.imageAvatar.visibility = if (ownerName.isNotBlank()) View.VISIBLE else View.GONE
            binding.textDuration.text = NumberUtils.formatDuration(video.durationValue.coerceAtLeast(0L))
            binding.textPlayCount.text = NumberUtils.formatCount(video.viewCount)
            binding.textDanmakuCount.text = NumberUtils.formatCount(video.danmakuCount)
        }

        private fun bindHistory(video: VideoModel) {
            binding.imageAvatar.visibility = View.GONE
            binding.iconPlayCount.visibility = View.GONE
            binding.textPlayCount.visibility = View.GONE
            binding.iconDanmaku.visibility = View.GONE
            binding.textDanmakuCount.visibility = View.GONE

            val duration = video.durationValue
            val progress = video.historyProgress.coerceAtLeast(0)
            if (duration > 0) {
                binding.progressBar.visibility = View.VISIBLE
                binding.progressBar.max = duration.toInt()
                binding.progressBar.progress = progress.coerceAtMost(duration).toInt()
            } else {
                binding.progressBar.visibility = View.GONE
            }

            binding.textDuration.text = if (video.historyBusiness == "live" && video.historyBadge.isNotBlank()) {
                video.historyBadge
            } else if (duration > 0) {
                "${NumberUtils.formatDuration(progress)}/${NumberUtils.formatDuration(duration)}"
            } else {
                video.historyBadge
            }
            binding.textViewOwner.text = formatHistoryTime(video.historyViewAt)
        }

        private fun resolveDisplayTitle(video: VideoModel): String {
            return video.bangumi?.longTitle?.takeIf { it.isNotBlank() } ?: video.title
        }

        private fun resolveCoverUrl(video: VideoModel): String {
            return video.bangumi?.cover?.takeIf { it.isNotBlank() } ?: video.coverUrl
        }

        private fun formatPublishTime(video: VideoModel): String {
            val publishedAt = when {
                video.pubDate > 0 -> video.pubDate
                video.createTime > 0 -> video.createTime
                else -> 0L
            }
            return if (publishedAt > 0) {
                TimeUtils.formatRelativeTime(publishedAt)
            } else {
                ""
            }
        }

        private fun formatHistoryTime(viewAtSeconds: Long): String {
            return TimeUtils.formatHistoryViewTime(viewAtSeconds)
        }
    }
}
