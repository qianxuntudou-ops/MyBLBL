package com.tutu.myblbl.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellMovieBinding
import com.tutu.myblbl.model.series.timeline.SeriesTimeLineModel
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.core.ui.focus.VideoCardFocusHelper

class SeriesTimelineAdapter(
    private val onItemClick: (SeriesTimeLineModel) -> Unit = {},
    private val onItemFocused: ((View) -> Unit)? = null,
    private val trackFocusedView: Boolean = true,
    private val enableSidebarExit: Boolean = true,
    private val onTopEdgeUp: (() -> Boolean)? = null,
    private val onLeftEdge: (() -> Boolean)? = null,
    private val onBottomEdgeDown: (() -> Boolean)? = null
) : ListAdapter<SeriesTimeLineModel, SeriesTimelineAdapter.SeriesTimelineViewHolder>(DIFF_CALLBACK) {

    var focusedView: View? = null
        private set

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SeriesTimeLineModel>() {
            override fun areItemsTheSame(oldItem: SeriesTimeLineModel, newItem: SeriesTimeLineModel): Boolean {
                return when {
                    oldItem.seasonId > 0L && newItem.seasonId > 0L -> oldItem.seasonId == newItem.seasonId
                    oldItem.episodeId > 0L && newItem.episodeId > 0L -> oldItem.episodeId == newItem.episodeId
                    oldItem.pubTs > 0L && newItem.pubTs > 0L -> oldItem.pubTs == newItem.pubTs
                    else -> oldItem.title == newItem.title && oldItem.pubTime == newItem.pubTime
                }
            }

            override fun areContentsTheSame(oldItem: SeriesTimeLineModel, newItem: SeriesTimeLineModel): Boolean {
                return oldItem == newItem
            }
        }
    }

    fun setData(newItems: List<SeriesTimeLineModel>) {
        if (trackFocusedView) {
            focusedView = null
        }
        submitList(newItems)
    }

    fun requestFocusedView(): Boolean {
        if (!trackFocusedView) {
            return false
        }
        val fv = focusedView ?: return false
        return fv.requestFocus()
    }

    fun requestFirstItemFocus(recyclerView: RecyclerView): Boolean {
        val holder = recyclerView.findViewHolderForAdapterPosition(0) as? SeriesTimelineViewHolder
        return holder?.requestFocus() == true
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SeriesTimelineViewHolder {
        val binding = CellMovieBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SeriesTimelineViewHolder(
            binding = binding,
            onItemClick = onItemClick,
            trackFocusedView = trackFocusedView,
            enableSidebarExit = enableSidebarExit,
            onTopEdgeUp = onTopEdgeUp,
            onLeftEdge = onLeftEdge,
            onBottomEdgeDown = onBottomEdgeDown
        ) { view ->
            if (trackFocusedView) {
                focusedView = view
            }
            onItemFocused?.invoke(view)
        }
    }

    override fun onBindViewHolder(holder: SeriesTimelineViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class SeriesTimelineViewHolder(
        private val binding: CellMovieBinding,
        private val onItemClick: (SeriesTimeLineModel) -> Unit,
        private val trackFocusedView: Boolean,
        private val enableSidebarExit: Boolean,
        private val onTopEdgeUp: (() -> Boolean)?,
        private val onLeftEdge: (() -> Boolean)?,
        private val onBottomEdgeDown: (() -> Boolean)?,
        private val onFocused: (View) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentItem: SeriesTimeLineModel? = null

        init {
            binding.clickView.setOnClickListener {
                onFocused(binding.clickView)
                currentItem?.let(onItemClick)
            }
            if (trackFocusedView) {
                binding.clickView.setOnFocusChangeListener { view, hasFocus ->
                    if (hasFocus) onFocused(view)
                }
            }
            if (enableSidebarExit || onTopEdgeUp != null || onLeftEdge != null) {
                VideoCardFocusHelper.bindSidebarExit(
                    binding.clickView,
                    onTopEdgeUp = onTopEdgeUp,
                    onLeftEdge = onLeftEdge,
                    onBottomEdgeDown = onBottomEdgeDown
                )
            }
        }

        fun bind(item: SeriesTimeLineModel) {
            currentItem = item

            ImageLoader.loadSeriesCover(binding.imageView, item.cover)

            binding.textView.text = item.title
            binding.textSub.visibility = View.VISIBLE
            binding.textSub.text = item.delayReason.ifBlank {
                binding.root.context.getString(R.string.pub_to_, item.pubIndex)
            }
            binding.textBadge.visibility = View.VISIBLE
            binding.textBadge.text = buildScheduleText(item)
        }

        fun requestFocus(): Boolean = binding.clickView.requestFocus()

        private fun buildScheduleText(item: SeriesTimeLineModel): String {
            val dayText = when (item.dayOfWeek) {
                1 -> "周一"
                2 -> "周二"
                3 -> "周三"
                4 -> "周四"
                5 -> "周五"
                6 -> "周六"
                7 -> "周日"
                else -> ""
            }
            return listOf(dayText, item.pubTime.trim())
                .filter { it.isNotBlank() }
                .joinToString(" ")
        }
    }
}
