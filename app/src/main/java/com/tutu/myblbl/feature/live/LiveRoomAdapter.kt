package com.tutu.myblbl.feature.live

import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellVideoBinding
import com.tutu.myblbl.model.live.LiveRoomItem
import com.tutu.myblbl.utils.AppLog
import com.tutu.myblbl.utils.ContentFilter
import com.tutu.myblbl.utils.ImageLoader
import com.tutu.myblbl.utils.NumberUtils
import com.tutu.myblbl.utils.VideoCardFocusHelper

class LiveRoomAdapter(
    private val onItemClick: (LiveRoomItem) -> Unit,
    private val onTopEdgeUp: (() -> Boolean)? = null
) : RecyclerView.Adapter<LiveRoomAdapter.ViewHolder>() {

    private val data = mutableListOf<LiveRoomItem>()

    fun setData(list: List<LiveRoomItem>) {
        val diffResult = DiffUtil.calculateDiff(LiveRoomDiff(data, list))
        data.clear()
        data.addAll(list)
        diffResult.dispatchUpdatesTo(this)
    }

    fun addData(list: List<LiveRoomItem>) {
        val startPosition = data.size
        data.addAll(list)
        notifyItemRangeInserted(startPosition, list.size)
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
        holder.bind(data[position])
    }

    override fun getItemCount(): Int = data.size

    private fun removeBlockedItems(blockedName: String) {
        val oldList = data.toList()
        val filtered = oldList.filter { !it.uname.equals(blockedName, ignoreCase = true) }
        if (filtered.size == oldList.size) return
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldList.size
            override fun getNewListSize(): Int = filtered.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                oldList[oldPos].roomId == filtered[newPos].roomId
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                oldList[oldPos] == filtered[newPos]
        })
        data.clear()
        data.addAll(filtered)
        diffResult.dispatchUpdatesTo(this)
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
                val anchorName = item.uname
                if (anchorName.isNotBlank()) {
                    longPressTriggered = true
                    ContentFilter.addBlockedUpName(itemView.context, anchorName)
                    Toast.makeText(
                        itemView.context,
                        itemView.context.getString(R.string.blocked_up_toast, anchorName),
                        Toast.LENGTH_LONG
                    ).show()
                    AppLog.d("LiveRoomAdapter", "Blocked anchor: $anchorName")
                    removeBlockedItems(anchorName)
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
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(data[position])
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
}

private class LiveRoomDiff(
    private val oldList: List<LiveRoomItem>,
    private val newList: List<LiveRoomItem>
) : DiffUtil.Callback() {

    override fun getOldListSize(): Int = oldList.size

    override fun getNewListSize(): Int = newList.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldList[oldItemPosition].roomId == newList[newItemPosition].roomId
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldList[oldItemPosition] == newList[newItemPosition]
    }
}
