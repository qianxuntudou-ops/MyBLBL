package com.tutu.myblbl.ui.adapter

import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellLaneSeriesTimeLineBinding
import com.tutu.myblbl.databinding.CellLaneScrollableBinding
import com.tutu.myblbl.model.lane.HomeLaneSection
import com.tutu.myblbl.model.lane.LaneItemModel
import com.tutu.myblbl.model.series.timeline.SeriesTimeLineModel
import com.tutu.myblbl.ui.activity.MainActivity
import com.tutu.myblbl.core.ui.base.BaseAdapter

class HomeLaneAdapter(
    private val onSeriesClick: (LaneItemModel) -> Unit,
    private val onMoreClick: (Int, String, String) -> Unit,
    private val onTimelineClick: (SeriesTimeLineModel) -> Unit,
    private val defaultMoreSeasonType: Int? = null,
    private val onTopEdgeUp: () -> Boolean = { false },
    private val onFollowSectionClick: ((Int) -> Unit)? = null
) : BaseAdapter<HomeLaneSection, RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_SCROLLABLE = 100
        private const val VIEW_TYPE_TIMELINE = 101
        private const val VIEW_TYPE_LOAD_MORE = -1000
    }

    fun addData(newItems: List<HomeLaneSection>): Boolean {
        val existingKeys = items.map { it.deduplicateKey() }.toMutableSet()
        val deduped = newItems.filter { existingKeys.add(it.deduplicateKey()) }
        if (deduped.isEmpty()) return false
        addAll(deduped)
        return true
    }

    private fun HomeLaneSection.deduplicateKey(): String {
        if (timelineDays.isNotEmpty()) return "timeline"
        if (style == "follow") return "follow"
        return "${title.trim()}#${style}#${moreSeasonType}"
    }

    fun requestFocusedView(): Boolean {
        val view = focusedView ?: return false
        if (!view.isAttachedToWindow || view.visibility != View.VISIBLE) {
            return false
        }
        return view.requestFocus()
    }

    fun insertTimelineSection(section: HomeLaneSection): Boolean {
        if (items.isEmpty()) {
            return false
        }
        val existingIndex = items.indexOfFirst { it.timelineDays.isNotEmpty() }
        if (existingIndex >= 0) {
            items[existingIndex] = section
            notifyItemChanged(existingIndex)
            return true
        }
        val insertIndex = minOf(1, items.size)
        items.add(insertIndex, section)
        notifyItemInserted(insertIndex)
        return true
    }

    override fun getItemViewType(position: Int): Int {
        if (position == items.size && showLoadMore) {
            return LOAD_MORE_TYPE
        }
        return if (items[position].timelineDays.isNotEmpty()) {
            VIEW_TYPE_TIMELINE
        } else {
            VIEW_TYPE_SCROLLABLE
        }
    }

    override fun onCreateContentViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_TIMELINE -> {
                val binding = CellLaneSeriesTimeLineBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
                TimelineViewHolder(binding, onTimelineClick, onTopEdgeUp, ::rememberFocusedView)
            }

            else -> {
                val binding = CellLaneScrollableBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
                ScrollableViewHolder(
                    binding,
                    onSeriesClick,
                    onMoreClick,
                    defaultMoreSeasonType,
                    onTopEdgeUp,
                    ::rememberFocusedView,
                    onFollowSectionClick
                )
            }
        }
    }

    override fun onBindContentViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ScrollableViewHolder -> holder.bind(items[position])
            is TimelineViewHolder -> holder.bind(items[position])
        }
    }

    private fun rememberFocusedView(view: View) {
        focusedView = view
    }

    class ScrollableViewHolder(
        private val binding: CellLaneScrollableBinding,
        onSeriesClick: (LaneItemModel) -> Unit,
        private val onMoreClick: (Int, String, String) -> Unit,
        private val defaultMoreSeasonType: Int?,
        private val onTopEdgeUp: () -> Boolean,
        private val onViewFocused: (View) -> Unit,
        private val onFollowSectionClick: ((Int) -> Unit)? = null
    ) : RecyclerView.ViewHolder(binding.root) {

        private val adapter = LaneItemAdapter(
            onItemClick = onSeriesClick,
            onItemFocused = onViewFocused
        )
        private var moreSeasonType: Int? = null
        private var currentMoreUrl: String = ""
        private var currentSection: HomeLaneSection? = null

        init {
            binding.recyclerView.layoutManager = GridLayoutManager(binding.root.context, 6)
            binding.recyclerView.adapter = adapter
            binding.recyclerView.setHasFixedSize(true)
            val sectionHeaderKeyListener = View.OnKeyListener { view, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    return@OnKeyListener false
                }
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        bindingAdapterPosition == 0 && onTopEdgeUp()
                    }

                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        view.context.findMainActivity()?.focusLeftFunctionArea(view) == true
                    }

                    else -> false
                }
            }
            val openMore = View.OnClickListener {
                val section = currentSection
                if (section?.disableMore == true && section.style == "follow") {
                    val title = section.title
                    val type = if (title.contains("追剧")) 2 else 1
                    onFollowSectionClick?.invoke(type)
                } else {
                    moreSeasonType?.let { onMoreClick(it, currentMoreUrl, binding.topTitle.text.toString()) }
                }
            }
            val trackFocus = View.OnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    onViewFocused(view)
                }
            }
            binding.topTitle.setOnKeyListener(sectionHeaderKeyListener)
            binding.topTitle.setOnClickListener(openMore)
            binding.topTitle.onFocusChangeListener = trackFocus
        }

        fun bind(item: HomeLaneSection) {
            currentSection = item
            val isFollow = item.style == "follow" && item.disableMore
            moreSeasonType = if (item.disableMore) {
                if (isFollow) defaultMoreSeasonType else null
            } else {
                item.moreSeasonType ?: defaultMoreSeasonType
            }
            currentMoreUrl = item.moreUrl
            binding.topTitle.text = item.title
            if (moreSeasonType != null || isFollow) {
                val arrow = ResourcesCompat.getDrawable(
                    binding.root.context.resources,
                    R.drawable.ic_arrow_right,
                    binding.root.context.theme
                )
                if (arrow != null) {
                    val size = binding.topTitle.textSize.toInt()
                    arrow.setBounds(0, 0, size, size)
                    binding.topTitle.setCompoundDrawablesRelative(null, null, arrow, null)
                }
                binding.topTitle.compoundDrawablePadding = binding.root.context.resources.getDimensionPixelSize(R.dimen.px10)
            } else {
                binding.topTitle.setCompoundDrawablesRelative(null, null, null, null)
            }
            adapter.setData(item.items)
        }

        fun requestPrimaryFocus(): Boolean {
            return binding.topTitle.requestFocus() || requestFirstCardFocus()
        }

        private fun requestFirstCardFocus(): Boolean {
            return adapter.requestFirstItemFocus(binding.recyclerView)
        }
    }

    class TimelineViewHolder(
        private val binding: CellLaneSeriesTimeLineBinding,
        onTimelineClick: (SeriesTimeLineModel) -> Unit,
        private val onTopEdgeUp: () -> Boolean,
        private val onViewFocused: (View) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private val adapter = SeriesTimelineAdapter(
            onItemClick = onTimelineClick,
            onItemFocused = onViewFocused,
            trackFocusedView = false
        )
        private var item: HomeLaneSection? = null

        init {
            binding.recyclerView.layoutManager = GridLayoutManager(binding.root.context, 6)
            binding.recyclerView.adapter = adapter
            binding.recyclerView.setHasFixedSize(true)
            val timelineHeaderKeyListener = View.OnKeyListener { view, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    return@OnKeyListener false
                }
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        bindingAdapterPosition == 0 && onTopEdgeUp()
                    }

                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        view.context.findMainActivity()?.focusLeftFunctionArea(view) == true
                    }

                    else -> false
                }
            }
            val trackFocus = View.OnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    onViewFocused(view)
                }
            }
            binding.radioGroup.setOnCheckedChangeListener { _, checkedId ->
                when (checkedId) {
                    R.id.button_radio_0 -> showTimeline(0)
                    R.id.button_radio_1 -> showTimeline(1)
                    R.id.button_radio_2 -> showTimeline(2)
                    R.id.button_radio_3 -> showTimeline(3)
                    R.id.button_radio_4 -> showTimeline(4)
                    R.id.button_radio_5 -> showTimeline(5)
                    R.id.button_radio_6 -> showTimeline(6)
                    R.id.button_radio_7 -> showTimeline(7)
                }
            }
            binding.topTitle.setOnKeyListener(timelineHeaderKeyListener)
            binding.topTitle.onFocusChangeListener = trackFocus
            listOf(
                binding.buttonRadio0,
                binding.buttonRadio1,
                binding.buttonRadio2,
                binding.buttonRadio3,
                binding.buttonRadio4,
                binding.buttonRadio5,
                binding.buttonRadio6,
                binding.buttonRadio7
            ).forEach { radioButton ->
                radioButton.setOnKeyListener(timelineHeaderKeyListener)
                radioButton.onFocusChangeListener = trackFocus
            }
        }

        fun bind(item: HomeLaneSection) {
            this.item = item
            binding.buttonRadio0.isChecked = true
            showTimeline(0)
        }

        fun requestPrimaryFocus(): Boolean {
            return binding.topTitle.requestFocus() ||
                requestSelectedFilterFocus() ||
                requestFirstCardFocus()
        }

        private fun requestSelectedFilterFocus(): Boolean {
            val target = when (binding.radioGroup.checkedRadioButtonId) {
                R.id.button_radio_1 -> binding.buttonRadio1
                R.id.button_radio_2 -> binding.buttonRadio2
                R.id.button_radio_3 -> binding.buttonRadio3
                R.id.button_radio_4 -> binding.buttonRadio4
                R.id.button_radio_5 -> binding.buttonRadio5
                R.id.button_radio_6 -> binding.buttonRadio6
                R.id.button_radio_7 -> binding.buttonRadio7
                else -> binding.buttonRadio0
            }
            return target.requestFocus()
        }

        private fun requestFirstCardFocus(): Boolean {
            return adapter.requestFirstItemFocus(binding.recyclerView)
        }

        private fun showTimeline(targetDayOfWeek: Int) {
            val timelineDays = item?.timelineDays.orEmpty()
            if (timelineDays.isEmpty()) {
                showEmpty()
                return
            }

            if (targetDayOfWeek == 0) {
                updateEpisodes(timelineDays.firstOrNull()?.episodes.orEmpty())
                return
            }

            val todayIndex = timelineDays.indexOfFirst { it.isToday == 1 }
                .takeIf { it >= 0 }
                ?: 0
            if (timelineDays[todayIndex].dayOfWeek > targetDayOfWeek) {
                for (index in todayIndex downTo 0) {
                    if (timelineDays[index].dayOfWeek == targetDayOfWeek) {
                        updateEpisodes(timelineDays[index].episodes)
                        return
                    }
                }
            } else {
                for (index in todayIndex until timelineDays.size) {
                    if (timelineDays[index].dayOfWeek == targetDayOfWeek) {
                        updateEpisodes(timelineDays[index].episodes)
                        return
                    }
                }
            }
            showEmpty()
        }

        private fun updateEpisodes(episodes: List<SeriesTimeLineModel>) {
            if (episodes.isEmpty()) {
                showEmpty()
                return
            }
            binding.imageEmpty.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            adapter.setData(episodes)
        }

        private fun showEmpty() {
            binding.imageEmpty.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
            adapter.setData(emptyList())
        }
    }

}

private fun Context.findMainActivity(): MainActivity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is MainActivity) {
            return current
        }
        current = current.baseContext
    }
    return null
}
