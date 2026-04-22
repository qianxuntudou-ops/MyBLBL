package com.tutu.myblbl.ui.adapter

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import androidx.recyclerview.widget.DiffUtil
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellVideoBinding
import com.tutu.myblbl.model.video.Dimension
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.core.ui.base.BaseAdapter
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.core.common.format.NumberUtils
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.time.TimeUtils
import com.tutu.myblbl.core.ui.focus.VideoCardFocusHelper
import com.tutu.myblbl.ui.dialog.VideoCardMenuDialog

class VideoAdapter(
    private val displayStyle: DisplayStyle = DisplayStyle.DEFAULT,
    private val itemWidthPx: Int? = null,
    private val onItemClick: (VideoModel) -> Unit = {},
    private val onTopEdgeUp: (() -> Boolean)? = null,
    private val onBottomEdgeDown: (() -> Boolean)? = null,
    private val onItemFocused: ((Int) -> Unit)? = null
) : BaseAdapter<VideoModel, VideoAdapter.VideoViewHolder>() {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<VideoModel>() {
            override fun areItemsTheSame(oldItem: VideoModel, newItem: VideoModel): Boolean =
                videoKey(oldItem) == videoKey(newItem)

            override fun areContentsTheSame(oldItem: VideoModel, newItem: VideoModel): Boolean =
                oldItem == newItem
        }

        private fun videoKey(video: VideoModel): String {
            return when {
                video.bvid.isNotBlank() -> "bvid:${video.bvid}"
                video.aid > 0 -> "aid:${video.aid}"
                video.cid > 0 -> "cid:${video.cid}"
                else -> "title:${video.title}|cover:${video.coverUrl}"
            }
        }
    }

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
        val existingKeys = items.mapTo(HashSet(items.size)) { videoKey(it) }
        val deduplicated = deduplicate(newItems).filterNot { incoming ->
            existingKeys.contains(videoKey(incoming))
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
            { view, position, hasFocus ->
                if (hasFocus) {
                    rememberItemInteraction(view, position)
                    onItemFocused?.invoke(position)
                }
            },
            { view, position ->
                rememberItemInteraction(view, position)
                onItemFocused?.invoke(position)
            },
            { video -> removeDislikedItem(video) },
            { upName -> removeDislikedUpItems(upName) }
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

    private fun submitItems(newItems: List<VideoModel>) {
        submitItemsInBackground(
            newItems = newItems,
            areItemsTheSame = { old, new -> DIFF_CALLBACK.areItemsTheSame(old, new) },
            areContentsTheSame = { old, new -> DIFF_CALLBACK.areContentsTheSame(old, new) }
        )
    }

    private fun removeDislikedItem(video: VideoModel) {
        val key = videoKey(video)
        val filtered = items.filter { videoKey(it) != key }
        if (filtered.size == items.size) return
        val viewToFocus = focusedView
        submitItemsInBackground(
            newItems = filtered,
            areItemsTheSame = { old, new -> DIFF_CALLBACK.areItemsTheSame(old, new) },
            areContentsTheSame = { old, new -> DIFF_CALLBACK.areContentsTheSame(old, new) },
            onComplete = { viewToFocus?.requestFocus() }
        )
    }

    private fun removeDislikedUpItems(upName: String) {
        val filtered = items.filter { !it.authorName.equals(upName, ignoreCase = true) }
        if (filtered.size == items.size) return
        val viewToFocus = focusedView
        submitItemsInBackground(
            newItems = filtered,
            areItemsTheSame = { old, new -> DIFF_CALLBACK.areItemsTheSame(old, new) },
            areContentsTheSame = { old, new -> DIFF_CALLBACK.areContentsTheSame(old, new) },
            onComplete = { viewToFocus?.requestFocus() }
        )
    }

    class VideoViewHolder(
        private val binding: CellVideoBinding,
        private val displayStyle: DisplayStyle,
        private val onItemClick: (View, VideoModel) -> Unit,
        onTopEdgeUp: (() -> Boolean)?,
        onBottomEdgeDown: (() -> Boolean)?,
        onFocusChange: ((View, Int, Boolean) -> Unit)? = null,
        private val onItemInteracted: ((View, Int) -> Unit)? = null,
        private val onItemDisliked: ((VideoModel) -> Unit)? = null,
        private val onUpDisliked: ((String) -> Unit)? = null
    ) : androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root) {

        private var currentVideo: VideoModel? = null
        private val handler = Handler(Looper.getMainLooper())
        private var longPressRunnable: Runnable? = null
        private var longPressTriggered = false

        private val keyListener = View.OnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
                showCardMenu()
                true
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) {
                            startLongPress()
                        }
                    }
                    KeyEvent.ACTION_UP -> {
                        cancelLongPress()
                    }
                }
                false
            } else {
                false
            }
        }

        private fun showCardMenu() {
            cancelLongPress()
            val video = currentVideo ?: return
            VideoCardMenuDialog(
                context = itemView.context,
                video = video,
                onDislikeVideo = {
                    onItemDisliked?.invoke(video)
                },
                onDislikeUp = { upName ->
                    onUpDisliked?.invoke(upName)
                }
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
                    AppLog.w("VideoAdapter", "click blocked by longPressTriggered")
                    return@setOnClickListener
                }
                val position = bindingAdapterPosition
                if (position != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                    onItemInteracted?.invoke(binding.root, position)
                }
                if (currentVideo == null) {
                    AppLog.e("VideoAdapter", "click but currentVideo is null, pos=$position")
                } else {
                    AppLog.d("VideoAdapter", "click: pos=$position, bvid=${currentVideo?.bvid}, title=${currentVideo?.title}")
                }
                currentVideo?.let { onItemClick(binding.root, it) }
            }
            binding.root.setOnTouchListener { _, event ->
                when(event.action) {
                    MotionEvent.ACTION_DOWN -> startLongPress()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelLongPress()
                }
                false
            }
            VideoCardFocusHelper.bindSidebarExit(
                view = binding.root,
                onTopEdgeUp = onTopEdgeUp,
                onBottomEdgeDown = onBottomEdgeDown,
                chainedListener = keyListener
            )
            if (onFocusChange != null) {
                binding.root.setOnFocusChangeListener { view, hasFocus ->
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

            val coverUrl = resolveCoverUrl(video)
            ImageLoader.loadVideoCover(
                imageView = binding.imageView,
                url = coverUrl
            )
            if (displayStyle == DisplayStyle.DEFAULT && !video.isPortrait) {
                ImageLoader.detectPortraitFromCover(binding.imageView, coverUrl) { isPortrait ->
                    if (bindingAdapterPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION
                        && currentVideo === video && isPortrait
                    ) {
                        binding.imageAvatar.visibility = View.GONE
                        applyBadge(video.copy(dimension = Dimension(width = 1, height = 2)))
                    }
                }
            }
        }

        private fun bindDefault(video: VideoModel) {
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
            if (video.isPortrait) {
                binding.imageAvatar.visibility = View.GONE
            } else {
                binding.imageAvatar.visibility = if (ownerName.isNotBlank()) View.VISIBLE else View.GONE
            }
            applyBadge(video)

            val duration = video.durationValue
            val progress = video.historyProgress.coerceAtLeast(0L)
            if (duration > 0 && progress > 0) {
                binding.progressBar.visibility = View.VISIBLE
                binding.progressBar.max = duration.toInt()
                binding.progressBar.progress = progress.coerceAtMost(duration).toInt()
                if (duration > 3 && progress >= duration - 3) {
                    binding.textDuration.text = "已看完"
                } else {
                    binding.textDuration.text = "${NumberUtils.formatDuration(progress)}/${NumberUtils.formatDuration(duration)}"
                }
            } else {
                binding.progressBar.visibility = View.GONE
                binding.textDuration.text = NumberUtils.formatDuration(duration.coerceAtLeast(0L))
            }

            binding.textPlayCount.text = NumberUtils.formatCount(video.viewCount)
            binding.textDanmakuCount.text = NumberUtils.formatCount(video.danmakuCount)
            binding.textChargeBadge.visibility = if (video.isChargingExclusive) View.VISIBLE else View.GONE
        }

        private fun bindHistory(video: VideoModel) {
            binding.iconPlayCount.visibility = View.GONE
            binding.textPlayCount.visibility = View.GONE
            binding.iconDanmaku.visibility = View.GONE
            binding.textDanmakuCount.visibility = View.GONE
            binding.imageAvatar.visibility = View.GONE
            binding.textBadge.visibility = View.GONE

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
            binding.textChargeBadge.visibility = if (video.isChargingExclusive) View.VISIBLE else View.GONE
        }

        private fun applyBadge(video: VideoModel) {
            val parts = mutableListOf<String>()
            if (video.isFollowed) parts.add("已关注")
            if (video.isPortrait) parts.add("竖屏")
            if (parts.isEmpty()) {
                binding.textBadge.visibility = View.GONE
            } else {
                binding.textBadge.text = parts.joinToString("|")
                binding.textBadge.visibility = View.VISIBLE
            }
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
