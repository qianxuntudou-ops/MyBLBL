package com.tutu.myblbl.feature.live

import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.core.common.format.NumberUtils
import com.tutu.myblbl.core.ui.focus.VideoCardFocusHelper
import com.tutu.myblbl.databinding.CellVideoBinding
import com.tutu.myblbl.model.live.LiveRoomItem
import com.tutu.myblbl.model.video.Owner
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.ui.dialog.VideoCardMenuDialog

class LiveRoomAdapter(
    private val onItemClick: (LiveRoomItem) -> Unit,
    private val onTopEdgeUp: (() -> Boolean)? = null
) : ListAdapter<LiveRoomItem, LiveRoomAdapter.ViewHolder>(DiffCallback) {

    fun setData(list: List<LiveRoomItem>) {
        submitList(list)
    }

    fun addData(list: List<LiveRoomItem>) {
        submitList(currentList + list)
    }

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

    private fun removeItemsByAnchorName(blockedName: String) {
        val filtered = currentList.filter { !it.uname.equals(blockedName, ignoreCase = true) }
        if (filtered.size == currentList.size) return
        submitList(filtered)
    }

    private fun removeRoom(roomId: Long) {
        val filtered = currentList.filter { it.roomId != roomId }
        if (filtered.size == currentList.size) return
        submitList(filtered)
    }

    inner class ViewHolder(
        private val binding: CellVideoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentItem: LiveRoomItem? = null
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
                longPressTriggered = true
                AppLog.d(
                    "LiveRoomAdapter",
                    "show live dislike menu: roomId=${item.roomId}, uid=${item.uid}, uname=${item.uname}, title=${item.title.take(30)}"
                )
                VideoCardMenuDialog(
                    context = itemView.context,
                    video = item.toFeedbackVideoModel(),
                    onDislikeVideo = {
                        removeRoom(item.roomId)
                    },
                    onDislikeUp = { upName ->
                        removeItemsByAnchorName(upName)
                    }
                ).show()
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
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(position))
                }
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
                onTopEdgeUp = onTopEdgeUp
            )
        }

        fun bind(item: LiveRoomItem) {
            currentItem = item
            binding.textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, binding.root.resources.getDimension(R.dimen.px31))
            binding.textView.maxLines = 1
            binding.textView.minLines = 1
            binding.textView.text = item.title
            binding.textViewOwner.text = item.uname
            binding.imageAvatar.visibility = if (item.uname.isBlank()) View.GONE else View.VISIBLE
            binding.textPortraitBadge.visibility = View.GONE
            binding.textViewOwner.visibility = if (item.uname.isBlank()) View.GONE else View.VISIBLE
            binding.textPlayCount.text = NumberUtils.formatCount(item.online.toLong())
            binding.iconPlayCount.visibility = View.VISIBLE
            binding.textPlayCount.visibility = View.VISIBLE
            binding.iconDanmaku.visibility = View.GONE
            binding.textDanmakuCount.visibility = View.GONE
            binding.textDuration.visibility = View.GONE
            binding.progressBar.visibility = View.GONE

            ImageLoader.loadVideoCover(
                imageView = binding.imageView,
                url = item.cover
            )
        }
    }

    private fun LiveRoomItem.toFeedbackVideoModel(): VideoModel {
        return VideoModel(
            title = title,
            cover = cover,
            goto = "live",
            roomId = roomId,
            owner = Owner(
                mid = uid,
                name = uname,
                face = face
            )
        )
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<LiveRoomItem>() {
            override fun areItemsTheSame(oldItem: LiveRoomItem, newItem: LiveRoomItem): Boolean {
                return oldItem.roomId == newItem.roomId
            }

            override fun areContentsTheSame(oldItem: LiveRoomItem, newItem: LiveRoomItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}
