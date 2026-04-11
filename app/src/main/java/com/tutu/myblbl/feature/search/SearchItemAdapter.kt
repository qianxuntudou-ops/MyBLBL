package com.tutu.myblbl.feature.search

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.text.HtmlCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellLiveRoomBinding
import com.tutu.myblbl.databinding.CellMovieBinding
import com.tutu.myblbl.databinding.CellUserBinding
import com.tutu.myblbl.databinding.CellVideoBinding
import com.tutu.myblbl.model.search.SearchItemModel
import com.tutu.myblbl.model.search.SearchType
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.core.common.format.NumberUtils
import com.tutu.myblbl.core.common.time.TimeUtils
import com.tutu.myblbl.core.ui.focus.VideoCardFocusHelper
import com.bumptech.glide.Glide

data class SearchResultEntry(
    val pageType: SearchType,
    val item: SearchItemModel
)

class SearchItemAdapter(
    private val searchType: SearchType,
    private val onItemClick: (SearchResultEntry) -> Unit,
    private val onTopEdgeUp: ((View) -> Boolean)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<SearchItemModel>()

    fun setItems(list: List<SearchItemModel>) {
        val diffResult = DiffUtil.calculateDiff(SearchItemDiff(items, list))
        items.clear()
        items.addAll(list)
        diffResult.dispatchUpdatesTo(this)
    }

    private fun removeBlockedItems(blockedName: String) {
        val oldList = items.toList()
        val filtered = oldList.filter {
            val authorName = it.author.ifBlank { it.uname }
            !authorName.equals(blockedName, ignoreCase = true)
        }
        if (filtered.size == oldList.size) return
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldList.size
            override fun getNewListSize(): Int = filtered.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                oldList[oldPos].id == filtered[newPos].id
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                oldList[oldPos] == filtered[newPos]
        })
        items.clear()
        items.addAll(filtered)
        diffResult.dispatchUpdatesTo(this)
    }

    override fun getItemViewType(position: Int): Int = when (searchType) {
        SearchType.Video -> VIEW_TYPE_VIDEO
        SearchType.LiveRoom -> VIEW_TYPE_LIVE
        SearchType.Animation,
        SearchType.FilmAndTv -> VIEW_TYPE_SERIES
        SearchType.User -> VIEW_TYPE_USER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_VIDEO -> VideoViewHolder(
                CellVideoBinding.inflate(inflater, parent, false)
            )

            VIEW_TYPE_LIVE -> LiveViewHolder(
                CellLiveRoomBinding.inflate(inflater, parent, false)
            )

            VIEW_TYPE_USER -> UserViewHolder(
                CellUserBinding.inflate(inflater, parent, false)
            )

            else -> SeriesViewHolder(
                CellMovieBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is VideoViewHolder -> holder.bind(items[position])
            is LiveViewHolder -> holder.bind(items[position])
            is SeriesViewHolder -> holder.bind(items[position])
            is UserViewHolder -> holder.bind(items[position])
        }
    }

    override fun getItemCount(): Int = items.size

    private inner class VideoViewHolder(
        private val binding: CellVideoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            bindInteraction(binding.root)
        }

        fun bind(item: SearchItemModel) {
            binding.textView.text = decodeHtml(item.title)
            val ownerName = item.author.ifBlank { item.uname }
            val publishText = item.pubDate.takeIf { it > 0L }?.let(TimeUtils::formatRelativeTime).orEmpty()
            binding.textViewOwner.text = buildString {
                if (ownerName.isNotBlank()) {
                    append(ownerName)
                }
                if (publishText.isNotBlank()) {
                    if (isNotEmpty()) {
                        append(" · ")
                    }
                    append(publishText)
                }
            }

            val playCount = item.play.takeIf { it > 0L } ?: item.online
            if (playCount > 0L) {
                binding.iconPlayCount.visibility = View.VISIBLE
                binding.textPlayCount.visibility = View.VISIBLE
                binding.textPlayCount.text = NumberUtils.formatCount(playCount)
            } else {
                binding.iconPlayCount.visibility = View.GONE
                binding.textPlayCount.visibility = View.GONE
            }

            if (item.danmaku > 0L) {
                binding.iconDanmaku.visibility = View.VISIBLE
                binding.textDanmakuCount.visibility = View.VISIBLE
                binding.textDanmakuCount.text = NumberUtils.formatCount(item.danmaku)
            } else {
                binding.iconDanmaku.visibility = View.GONE
                binding.textDanmakuCount.visibility = View.GONE
            }

            binding.imageAvatar.visibility = if (ownerName.isNotBlank()) View.VISIBLE else View.GONE
            binding.textDuration.text = item.duration
            binding.progressBar.visibility = View.GONE

            ImageLoader.loadVideoCover(
                imageView = binding.imageView,
                url = item.pic.ifBlank { item.cover }
            )
        }
    }

    private inner class LiveViewHolder(
        private val binding: CellLiveRoomBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            bindInteraction(binding.root)
        }

        fun bind(item: SearchItemModel) {
            binding.textView.text = decodeHtml(item.title)
            binding.textPlayCount.text = NumberUtils.formatCount(item.online)

            val ownerName = item.uname.ifBlank { item.author }
            val avatar = normalizeUrl(item.upic)
            val hasOwner = ownerName.isNotBlank()
            binding.imageAvatar.visibility = if (hasOwner) View.VISIBLE else View.GONE
            binding.textViewOwner.visibility = if (hasOwner) View.VISIBLE else View.GONE
            if (hasOwner) {
                binding.textViewOwner.text = ownerName
            }

            Glide.with(binding.imageView)
                .load(normalizeUrl(item.cover.ifBlank { item.pic }))
                .placeholder(R.color.thirdBackgroundColor)
                .into(binding.imageView)

            if (hasOwner) {
                Glide.with(binding.imageAvatar)
                    .load(avatar)
                    .placeholder(R.drawable.default_avatar)
                    .circleCrop()
                    .into(binding.imageAvatar)
            }
        }
    }

    private inner class SeriesViewHolder(
        private val binding: CellMovieBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            bindInteraction(binding.root)
        }

        fun bind(item: SearchItemModel) {
            binding.textView.text = decodeHtml(item.title)
            binding.textSub.text = item.indexShow.ifBlank { item.desc }
            binding.textBadge.visibility = if (item.type.contains("media")) View.VISIBLE else View.GONE
            binding.textBadge.text = item.type.removePrefix("media_").ifBlank { "PGC" }

            Glide.with(binding.imageView)
                .load(normalizeUrl(item.cover.ifBlank { item.pic }))
                .placeholder(R.color.thirdBackgroundColor)
                .into(binding.imageView)
        }
    }

    private inner class UserViewHolder(
        private val binding: CellUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            bindInteraction(binding.root)
        }

        fun bind(item: SearchItemModel) {
            binding.textView.text = item.uname
            binding.textSub.text = item.usign

            Glide.with(binding.imageView)
                .load(normalizeUrl(item.upic))
                .placeholder(R.drawable.default_avatar)
                .circleCrop()
                .into(binding.imageView)
        }
    }

    private fun RecyclerView.ViewHolder.bindInteraction(
        view: View
    ) {
        val handler = Handler(Looper.getMainLooper())
        var longPressRunnable: Runnable? = null
        val longPressThreshold = 5_000L
        var longPressTriggered = false

        val triggerBlock = { authorName: String ->
            longPressTriggered = true
            ContentFilter.addBlockedUpName(itemView.context, authorName)
            Toast.makeText(
                itemView.context,
                itemView.context.getString(R.string.blocked_up_toast, authorName),
                Toast.LENGTH_LONG
            ).show()
            AppLog.d("SearchItemAdapter", "Blocked UP: $authorName")
            removeBlockedItems(authorName)
        }

        val keyListener = View.OnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) {
                            longPressRunnable?.let { handler.removeCallbacks(it) }
                            longPressTriggered = false
                            longPressRunnable = Runnable {
                                val pos = bindingAdapterPosition
                                if (pos != RecyclerView.NO_POSITION) {
                                    val item = items[pos]
                                    val authorName = item.author.ifBlank { item.uname }
                                    if (authorName.isNotBlank()) {
                                        triggerBlock(authorName)
                                    }
                                }
                            }
                            handler.postDelayed(longPressRunnable!!, longPressThreshold)
                        }
                    }
                    KeyEvent.ACTION_UP -> {
                        longPressRunnable?.let { handler.removeCallbacks(it) }
                        longPressRunnable = null
                    }
                }
            }
            false
        }

        view.setOnClickListener {
            if (longPressTriggered) {
                longPressTriggered = false
                return@setOnClickListener
            }
            val position = bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) {
                onItemClick(SearchResultEntry(searchType, items[position]))
            }
        }
        view.setOnKeyListener(keyListener)
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    longPressRunnable?.let { handler.removeCallbacks(it) }
                    longPressTriggered = false
                    longPressRunnable = Runnable {
                        val pos = bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION) {
                            val item = items[pos]
                            val authorName = item.author.ifBlank { item.uname }
                            if (authorName.isNotBlank()) {
                                triggerBlock(authorName)
                            }
                        }
                    }
                    handler.postDelayed(longPressRunnable!!, longPressThreshold)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { handler.removeCallbacks(it) }
                    longPressRunnable = null
                }
            }
            false
        }
        VideoCardFocusHelper.bindSidebarExit(
            view,
            onTopEdgeUp = {
                onTopEdgeUp?.invoke(view) == true
            }
        )
    }

    private fun normalizeUrl(url: String): String {
        return when {
            url.isBlank() -> ""
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            else -> url
        }
    }

    private fun decodeHtml(value: String): String {
        return HtmlCompat.fromHtml(value, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
    }

    private companion object {
        const val VIEW_TYPE_VIDEO = 0
        const val VIEW_TYPE_LIVE = 1
        const val VIEW_TYPE_SERIES = 2
        const val VIEW_TYPE_USER = 3
    }

    private class SearchItemDiff(
        private val oldList: List<SearchItemModel>,
        private val newList: List<SearchItemModel>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size

        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldItem = oldList[oldItemPosition]
            val newItem = newList[newItemPosition]
            return when {
                oldItem.id != 0L && newItem.id != 0L -> oldItem.id == newItem.id
                oldItem.aid != 0L && newItem.aid != 0L -> oldItem.aid == newItem.aid
                oldItem.bvid.isNotBlank() && newItem.bvid.isNotBlank() -> oldItem.bvid == newItem.bvid
                else -> oldItem.title == newItem.title
            }
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition] == newList[newItemPosition]
        }
    }
}
