package com.tutu.myblbl.feature.live

import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.databinding.CellLaneScrollableBinding
import com.tutu.myblbl.model.live.LiveRecommendSection
import com.tutu.myblbl.model.live.LiveRoomItem
import com.tutu.myblbl.ui.view.WrapContentGridLayoutManager
import com.tutu.myblbl.utils.AppLog

class LiveRecommendAdapter(
    private val onRoomClick: (LiveRoomItem) -> Unit,
    private val onTopEdgeUp: () -> Boolean = { false },
    private val onLeftEdge: () -> Boolean = { false }
) : RecyclerView.Adapter<LiveRecommendAdapter.ViewHolder>() {

    private val data = mutableListOf<LiveRecommendSection>()

    fun setData(list: List<LiveRecommendSection>) {
        AppLog.d("LiveRecommendAdapter", "setData: list.size=${list.size}, old size=${data.size}")
        val diffResult = DiffUtil.calculateDiff(LiveRecommendDiff(data, list))
        data.clear()
        data.addAll(list)
        diffResult.dispatchUpdatesTo(this)
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
        holder.bind(data[position])
    }

    override fun getItemCount(): Int = data.size

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
    }

    private class LiveRecommendDiff(
        private val oldList: List<LiveRecommendSection>,
        private val newList: List<LiveRecommendSection>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size

        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].title == newList[newItemPosition].title
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition] == newList[newItemPosition]
        }
    }
}
