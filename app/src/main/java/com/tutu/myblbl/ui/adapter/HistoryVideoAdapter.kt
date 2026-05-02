package com.tutu.myblbl.ui.adapter

import android.annotation.SuppressLint
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellVideoBinding
import com.tutu.myblbl.model.video.HistoryVideoModel
import com.tutu.myblbl.core.ui.base.BaseVideoAdapter
import com.tutu.myblbl.core.ui.base.BaseVideoViewHolder
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.core.common.format.NumberUtils
import com.tutu.myblbl.core.common.time.TimeUtils
import com.tutu.myblbl.core.ui.focus.VideoCardFocusHelper
import com.tutu.myblbl.ui.dialog.VideoCardMenuDialog

class HistoryVideoAdapter(
    private val onItemClick: (HistoryVideoModel) -> Unit,
    onTopEdgeUp: (() -> Boolean)? = null,
    onItemFocused: ((Int) -> Unit)? = null,
    onItemFocusedWithView: ((View, Int) -> Unit)? = null,
    onItemDpad: ((View, Int, KeyEvent) -> Boolean)? = null,
    onItemsChanged: (() -> Unit)? = null
) : BaseVideoAdapter<HistoryVideoModel, HistoryVideoAdapter.ViewHolder>() {

    init {
        setHasStableIds(true)
        setShowLoadMore(false)
        this.onTopEdgeUp = onTopEdgeUp
        this.onItemFocused = onItemFocused
        this.onItemFocusedWithView = onItemFocusedWithView
        this.onItemDpad = onItemDpad
        this.onItemsChanged = onItemsChanged
    }

    override fun itemKey(item: HistoryVideoModel): String {
        return when {
            item.bvid.isNotBlank() -> "bvid:${item.bvid}"
            (item.history?.oid ?: 0L) > 0L -> "aid:${item.history?.oid}"
            else -> "title:${item.title}|cover:${item.cover}"
        }
    }

    override fun areContentsSame(old: HistoryVideoModel, new: HistoryVideoModel): Boolean = old == new

    override fun setData(
        newItems: List<HistoryVideoModel>,
        onCommitted: (() -> Unit)?
    ) {
        val savedPosition = focusedPosition
        setDataDeduplicated(newItems) {
            if (savedPosition != RecyclerView.NO_POSITION && savedPosition < items.size) {
                focusedPosition = savedPosition
            }
            onCommitted?.invoke()
        }
    }

    fun focusedItemPosition(): Int = focusedPosition

    fun findPositionByKey(key: String): Int = findPositionByStableKey(key)

    override fun onCreateContentViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = CellVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindContentViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position) ?: return
        holder.bind(item, position == focusedPosition)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        focusedPosition = RecyclerView.NO_POSITION
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        applyFocusState(holder.itemView, false)
        super.onViewRecycled(holder)
    }

    private fun applyFocusState(view: View?, focused: Boolean) {
        view ?: return
        view.isSelected = focused
        view.findViewById<AppCompatTextView>(com.tutu.myblbl.R.id.textView)?.isSelected = focused
    }

    inner class ViewHolder(
        private val binding: CellVideoBinding
    ) : BaseVideoViewHolder(binding.root) {

        private var currentItem: HistoryVideoModel? = null

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
            val item = currentItem ?: return
            val video = item.toVideoModel()
            VideoCardMenuDialog(
                context = itemView.context,
                video = video,
                onDislikeVideo = { removeItems { itemKey(it) == itemKey(item) } },
                onDislikeUp = { upName -> removeItems { it.authorName.equals(upName, ignoreCase = true) } }
            ).show()
        }

        init {
            val coverRadiusPx = binding.imageView.resources.getDimension(R.dimen.px15)
            binding.imageView.clipToOutline = true
            binding.imageView.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, coverRadiusPx)
                }
            }
            val progressLp = binding.progressBar.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            progressLp.marginStart = coverRadiusPx.toInt()
            progressLp.marginEnd = coverRadiusPx.toInt()
            binding.progressBar.layoutParams = progressLp
            binding.root.setOnClickListener {
                if (longPressTriggered) {
                    longPressTriggered = false
                    return@setOnClickListener
                }
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemFocused?.invoke(position)
                    val previous = focusedPosition
                    focusedPosition = position
                    if (previous != RecyclerView.NO_POSITION && previous != position) {
                        notifyItemChanged(previous)
                    }
                    applyFocusState(binding.root, true)
                    onItemFocusedWithView?.invoke(binding.root, position)
                }
                currentItem?.let(onItemClick)
            }
            @SuppressLint("ClickableViewAccessibility")
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
                    val previous = focusedPosition
                    focusedPosition = position
                    if (previous != RecyclerView.NO_POSITION && previous != position) {
                        notifyItemChanged(previous)
                    }
                    applyFocusState(view, true)
                    onItemFocusedWithView?.invoke(view, position)
                } else {
                    applyFocusState(binding.root, false)
                }
            }
            VideoCardFocusHelper.bindSidebarExit(
                view = binding.root,
                onTopEdgeUp = onTopEdgeUp,
                handleListDpadDown = false,
                chainedListener = keyListener
            )
            binding.imageAvatar.visibility = View.GONE
            binding.textBadge.visibility = View.GONE
            binding.iconPlayCount.visibility = View.GONE
            binding.textPlayCount.visibility = View.GONE
            binding.iconDanmaku.visibility = View.GONE
            binding.textDanmakuCount.visibility = View.GONE
        }

        fun bind(item: HistoryVideoModel, isFocused: Boolean) {
            currentItem = item

            binding.root.isSelected = isFocused
            binding.textView.isSelected = isFocused
            binding.textView.text = item.title.ifBlank { item.showTitle }
            binding.imageAvatar.visibility = View.GONE
            if (item.isPortrait) {
                binding.textBadge.text = "竖屏"
                binding.textBadge.visibility = View.VISIBLE
            } else {
                binding.textBadge.visibility = View.GONE
            }
            binding.iconPlayCount.visibility = View.GONE
            binding.textPlayCount.visibility = View.GONE
            binding.iconDanmaku.visibility = View.GONE
            binding.textDanmakuCount.visibility = View.GONE

            binding.progressBar.visibility = View.VISIBLE
            val durationValue = item.duration.coerceAtLeast(0L)
            val progressValue = item.progress.coerceAtLeast(0L).coerceAtMost(durationValue)
            binding.progressBar.max = durationValue.toInt()
            binding.progressBar.progress = progressValue.toInt()

            binding.textDuration.text = when {
                item.history?.business == "live" && item.badge.isNotBlank() -> item.badge
                durationValue > 0L -> "${NumberUtils.formatDuration(progressValue)}/${NumberUtils.formatDuration(durationValue)}"
                item.tagName.isNotBlank() -> item.tagName
                else -> ""
            }
            binding.textViewOwner.text = TimeUtils.formatHistoryViewTime(item.viewAt)
            binding.textChargeBadge.visibility = if (item.isChargingExclusive) View.VISIBLE else View.GONE
            binding.textInteractionBadge.visibility = if (item.isSteinsGate) View.VISIBLE else View.GONE

            ImageLoader.loadVideoCover(
                imageView = binding.imageView,
                url = item.cover.ifBlank { item.covers?.firstOrNull().orEmpty() }
            )
        }
    }
}
