package com.tutu.myblbl.feature.dynamic

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.NO_POSITION
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellVideoBinding
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.core.common.format.NumberUtils
import com.tutu.myblbl.core.common.time.TimeUtils
import com.tutu.myblbl.core.ui.focus.VideoCardFocusHelper
import com.tutu.myblbl.ui.dialog.VideoCardMenuDialog

class DynamicVideoAdapter(
    private val onItemClick: (VideoModel) -> Unit,
    private val onItemFocused: (Int) -> Unit,
    private val onLeftEdge: () -> Boolean = { false }
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

    private fun removeDislikedItem(video: VideoModel) {
        val key = dynamicVideoKey(video)
        val filtered = currentList.filter { dynamicVideoKey(it) != key }
        if (filtered.size == currentList.size) return
        submitList(filtered)
        focusedView?.requestFocus()
    }

    inner class ViewHolder(
        private val binding: CellVideoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentItem: VideoModel? = null
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

        init {
            binding.root.setOnClickListener {
                if (longPressTriggered) {
                    longPressTriggered = false
                    return@setOnClickListener
                }
                val position = bindingAdapterPosition
                if (position != NO_POSITION) {
                    focusedView = binding.root
                    onItemFocused(position)
                    onItemClick(getItem(position))
                }
            }
            binding.root.setOnFocusChangeListener { view, hasFocus ->
                val position = bindingAdapterPosition
                if (hasFocus && position != NO_POSITION) {
                    focusedView = view
                    onItemFocused(position)
                }
            }
            binding.root.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> startLongPress()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelLongPress()
                }
                false
            }
            VideoCardFocusHelper.bindSidebarExit(
                view = binding.root,
                onLeftEdge = onLeftEdge,
                chainedListener = keyListener
            )
        }

        private fun showCardMenu() {
            cancelLongPress()
            val position = bindingAdapterPosition
            if (position == NO_POSITION || position !in currentList.indices) return
            val video = getItem(position)
            VideoCardMenuDialog(
                context = itemView.context,
                video = video,
                onDislikeVideo = { removeDislikedItem(video) },
                onDislikeUp = { upName -> removeBlockedItems(upName) }
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

        fun bind(item: VideoModel) {
            currentItem = item
            val bangumi = item.bangumi
            val ownerName = item.authorName
            val publishText = formatPublishTime(item)

            val coverUrl: String
            if (bangumi != null) {
                binding.textView.text = bangumi.longTitle
                coverUrl = bangumi.cover
            } else {
                binding.textView.text = item.title
                coverUrl = item.coverUrl
            }
            ImageLoader.loadVideoCover(
                imageView = binding.imageView,
                url = coverUrl
            )

            if (ownerName.isNotBlank()) {
                binding.textViewOwner.text = if (publishText.isNotBlank()) {
                    "$ownerName · $publishText"
                } else {
                    ownerName
                }
                if (item.isPortrait) {
                    binding.imageAvatar.visibility = View.GONE
                    binding.textPortraitBadge.visibility = View.VISIBLE
                } else {
                    binding.imageAvatar.visibility = View.VISIBLE
                    binding.textPortraitBadge.visibility = View.GONE
                    ImageLoader.detectPortraitFromCover(binding.imageView, coverUrl) { isPortrait ->
                        if (bindingAdapterPosition != NO_POSITION
                            && currentItem === item && isPortrait
                        ) {
                            binding.imageAvatar.visibility = View.GONE
                            binding.textPortraitBadge.visibility = View.VISIBLE
                        }
                    }
                }
            } else {
                binding.textViewOwner.text = publishText
                binding.imageAvatar.visibility = View.GONE
                binding.textPortraitBadge.visibility = View.GONE
            }

            binding.textPlayCount.text = NumberUtils.formatCount(item.viewCount)
            binding.textDanmakuCount.text = NumberUtils.formatCount(item.danmakuCount)
            binding.textDuration.text = NumberUtils.formatDuration(item.durationValue.coerceAtLeast(0L))
            binding.textChargeBadge.visibility = if (item.isChargingExclusive) View.VISIBLE else View.GONE
        }

        private fun formatPublishTime(video: VideoModel): String {
            val publishedAt = video.pubDate
            return if (publishedAt > 0) {
                TimeUtils.formatRelativeTime(publishedAt)
            } else {
                ""
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
