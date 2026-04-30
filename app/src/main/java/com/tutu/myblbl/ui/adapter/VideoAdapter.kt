package com.tutu.myblbl.ui.adapter

import android.annotation.SuppressLint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellVideoBinding
import com.tutu.myblbl.model.video.Dimension
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.core.ui.base.BaseVideoAdapter
import com.tutu.myblbl.core.ui.base.BaseVideoViewHolder
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
    onTopEdgeUp: (() -> Boolean)? = null,
    onBottomEdgeDown: (() -> Boolean)? = null,
    onItemFocused: ((Int) -> Unit)? = null,
    onItemFocusedWithView: ((View, Int) -> Unit)? = null,
    onItemDpad: ((View, Int, KeyEvent) -> Boolean)? = null,
    onItemsChanged: (() -> Unit)? = null,
    private val detectPortraitFromCover: Boolean = true
) : BaseVideoAdapter<VideoModel, VideoAdapter.VideoViewHolder>() {

    var currentPlayingAid: Long = 0

    init {
        this.onTopEdgeUp = onTopEdgeUp
        this.onBottomEdgeDown = onBottomEdgeDown
        this.onItemFocused = onItemFocused
        this.onItemFocusedWithView = onItemFocusedWithView
        this.onItemDpad = onItemDpad
        this.onItemsChanged = onItemsChanged
        setHasStableIds(true)
    }

    override fun itemKey(item: VideoModel): String {
        return when {
            item.bvid.isNotBlank() -> "bvid:${item.bvid}"
            item.aid > 0 -> "aid:${item.aid}"
            item.cid > 0 -> "cid:${item.cid}"
            else -> "title:${item.title}|cover:${item.coverUrl}"
        }
    }

    override fun areContentsSame(old: VideoModel, new: VideoModel): Boolean =
        old.title == new.title &&
        old.coverUrl == new.coverUrl &&
        old.viewCount == new.viewCount &&
        old.danmakuCount == new.danmakuCount &&
        old.isFollowed == new.isFollowed &&
        old.isPortrait == new.isPortrait &&
        old.isChargingExclusive == new.isChargingExclusive &&
        old.historyProgress == new.historyProgress &&
        old.durationValue == new.durationValue &&
        old.authorName == new.authorName &&
        old.pubDate == new.pubDate &&
        old.createTime == new.createTime &&
        old.bangumi == new.bangumi &&
        old.historyBadge == new.historyBadge &&
        old.historyBusiness == new.historyBusiness &&
        old.historyViewAt == new.historyViewAt

    enum class DisplayStyle {
        DEFAULT,
        HISTORY
    }

    private var onItemClickListener: ((View, VideoModel) -> Unit)? = null

    fun setOnItemClickListener(listener: (View, VideoModel) -> Unit) {
        onItemClickListener = listener
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
                    onItemFocused?.invoke(position)
                    onItemFocusedWithView?.invoke(view, position)
                }
            },
            { view, position ->
                onItemFocused?.invoke(position)
                onItemFocusedWithView?.invoke(view, position)
            },
            { video -> removeItems { itemKey(it) == itemKey(video) } },
            { upName -> removeItems { it.authorName.equals(upName, ignoreCase = true) } },
            onItemDpad,
            detectPortraitFromCover
        )
    }

    override fun onBindContentViewHolder(holder: VideoViewHolder, position: Int) {
        val video = getItem(position) ?: return
        holder.bind(video, video.aid == currentPlayingAid)
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
        private val onUpDisliked: ((String) -> Unit)? = null,
        private val onItemDpad: ((View, Int, KeyEvent) -> Boolean)? = null,
        private val detectPortraitFromCover: Boolean = true
    ) : BaseVideoViewHolder(binding.root) {

        private var currentVideo: VideoModel? = null
        private val defaultTextColor: Int by lazy {
            val ta = binding.root.context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
            val color = ta.getColor(0, 0)
            ta.recycle()
            color
        }

        private val keyListener = View.OnKeyListener { view, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
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
                onItemDpad?.invoke(view, keyCode, event) == true
            }
        }

        override fun showCardMenu() {
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
            @SuppressLint("ClickableViewAccessibility")
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
                handleListDpadDown = false,
                chainedListener = keyListener
            )
            if (onFocusChange != null) {
                binding.root.setOnFocusChangeListener { view, hasFocus ->
                    onFocusChange.invoke(view, bindingAdapterPosition, hasFocus)
                }
            }
        }

        private var splitRunnable: Runnable? = null

        fun bind(video: VideoModel, isCurrentlyPlaying: Boolean = false) {
            currentVideo = video
            splitRunnable?.let { binding.textView.removeCallbacks(it) }
            splitRunnable = null

            val title = resolveDisplayTitle(video)
            if (isCurrentlyPlaying) {
                binding.iconPlaying.visibility = View.VISIBLE
                val iconSize = binding.textView.textSize.toInt()
                binding.iconPlaying.layoutParams = binding.iconPlaying.layoutParams.apply {
                    width = iconSize
                    height = iconSize
                }
                val loadCtx = binding.root.context
                if (loadCtx !is android.app.Activity || !loadCtx.isDestroyed) {
                    com.bumptech.glide.Glide.with(loadCtx)
                        .asGif()
                        .load(R.drawable.playing)
                        .into(binding.iconPlaying)
                }
                val accentColor = ContextCompat.getColor(binding.root.context, R.color.colorAccent)
                binding.textView.setTextColor(accentColor)
                binding.textOverflow.setTextColor(accentColor)
                binding.textView.text = title
                binding.textView.ellipsize = TextUtils.TruncateAt.END
                binding.textView.minLines = 1
                binding.textView.maxLines = 1

                val split = Runnable {
                    val layout = binding.textView.layout ?: return@Runnable
                    if (layout.lineCount > 0) {
                        val ellipsisCount = layout.getEllipsisCount(0)
                        if (ellipsisCount > 0) {
                            val visibleEnd = layout.getEllipsisStart(0)
                            binding.textView.text = title.substring(0, visibleEnd)
                            binding.textOverflow.text = title.substring(visibleEnd)
                            binding.textOverflow.visibility = View.VISIBLE
                        } else {
                            binding.textOverflow.visibility = View.GONE
                        }
                    }
                }
                splitRunnable = split
                binding.textView.post(split)
            } else {
                binding.iconPlaying.visibility = View.GONE
                val clearCtx = binding.root.context
                if (clearCtx !is android.app.Activity || !clearCtx.isDestroyed) {
                    com.bumptech.glide.Glide.with(clearCtx).clear(binding.iconPlaying)
                }
                binding.textView.setTextColor(defaultTextColor)
                binding.textView.text = title
                binding.textView.minLines = 2
                binding.textView.maxLines = 2
                binding.textOverflow.visibility = View.GONE
            }
            when (displayStyle) {
                DisplayStyle.DEFAULT -> bindDefault(video)
                DisplayStyle.HISTORY -> bindHistory(video)
            }

            val coverUrl = resolveCoverUrl(video)
            val needPortraitDetect = detectPortraitFromCover &&
                displayStyle == DisplayStyle.DEFAULT &&
                !video.isPortrait
            ImageLoader.loadVideoCover(
                imageView = binding.imageView,
                url = coverUrl,
                onPortraitDetected = if (needPortraitDetect) { isPortrait ->
                    if (bindingAdapterPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION
                        && currentVideo === video && isPortrait
                    ) {
                        binding.imageAvatar.visibility = View.GONE
                        applyBadge(video.copy(dimension = Dimension(width = 1, height = 2)))
                    }
                } else null
            )
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
                    binding.textDuration.text = binding.root.context.getString(
                        R.string.video_watch_progress_format,
                        NumberUtils.formatDuration(progress),
                        NumberUtils.formatDuration(duration)
                    )
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
