package com.tutu.myblbl.feature.live

import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.databinding.CellLaneScrollableBinding
import com.tutu.myblbl.model.live.LiveRecommendSection
import com.tutu.myblbl.model.live.LiveRoomItem
import com.tutu.myblbl.core.ui.layout.WrapContentGridLayoutManager

class LiveRecommendAdapter(
    private val onRoomClick: (LiveRoomItem) -> Unit,
    private val onTopEdgeUp: () -> Boolean = { false },
    private val onLeftEdge: () -> Boolean = { false }
) : ListAdapter<LiveRecommendSection, LiveRecommendAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<LiveRecommendSection>() {
            override fun areItemsTheSame(oldItem: LiveRecommendSection, newItem: LiveRecommendSection): Boolean {
                return oldItem.title == newItem.title
            }

            override fun areContentsTheSame(oldItem: LiveRecommendSection, newItem: LiveRecommendSection): Boolean {
                return oldItem == newItem
            }
        }
    }

    fun setData(list: List<LiveRecommendSection>) {
        submitList(list)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = CellLaneScrollableBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, onRoomClick, onTopEdgeUp, onLeftEdge)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: CellLaneScrollableBinding,
        onRoomClick: (LiveRoomItem) -> Unit,
        private val onTopEdgeUp: () -> Boolean,
        private val onLeftEdge: () -> Boolean
    ) : RecyclerView.ViewHolder(binding.root) {

        private val roomAdapter = LiveRoomAdapter(onRoomClick)

        init {
            binding.recyclerView.layoutManager = object : WrapContentGridLayoutManager(binding.root.context, 4) {
                override fun canScrollVertically(): Boolean = false
            }
            binding.recyclerView.adapter = roomAdapter
            binding.recyclerView.isNestedScrollingEnabled = false
            binding.topTitle.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    return@setOnKeyListener false
                }
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> bindingAdapterPosition == 0 && onTopEdgeUp()
                    KeyEvent.KEYCODE_DPAD_LEFT -> onLeftEdge()
                    else -> false
                }
            }
        }

        fun bind(item: LiveRecommendSection) {
            binding.topTitle.text = item.title
            roomAdapter.setData(item.rooms)
        }

        fun requestPrimaryFocus(): Boolean {
            return binding.topTitle.requestFocus()
        }

        fun focusRoomAt(roomIndex: Int): Boolean {
            val innerRv = binding.recyclerView
            val holder = innerRv.findViewHolderForAdapterPosition(roomIndex)
            if (holder != null && holder.itemView.isAttachedToWindow) {
                return holder.itemView.requestFocus()
            }
            innerRv.scrollToPosition(roomIndex)
            innerRv.post {
                innerRv.findViewHolderForAdapterPosition(roomIndex)?.itemView?.requestFocus()
            }
            return true
        }

        fun findRoomPositionByRoomId(roomId: Long): Int {
            return roomAdapter.currentList.indexOfFirst { it.roomId == roomId }
                .takeIf { it >= 0 } ?: RecyclerView.NO_POSITION
        }
    }
}
