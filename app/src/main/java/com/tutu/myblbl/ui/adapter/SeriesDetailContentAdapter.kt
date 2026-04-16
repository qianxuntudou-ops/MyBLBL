package com.tutu.myblbl.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellEpisodeBinding
import com.tutu.myblbl.databinding.CellSeriesHeadBinding
import com.tutu.myblbl.databinding.CellSeriesLaneBinding
import com.tutu.myblbl.model.episode.EpisodeModel
import com.tutu.myblbl.model.series.EpisodesDetailModel
import com.tutu.myblbl.model.series.SectionModel
import com.tutu.myblbl.model.series.SeriesModel
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.core.common.format.NumberUtils
import com.tutu.myblbl.core.ui.system.ScreenUtils
import com.tutu.myblbl.core.common.time.TimeUtils

class SeriesDetailContentAdapter(
    private val onToggleFollow: () -> Unit,
    private val onContinueWatchClick: (EpisodeModel, Long) -> Unit,
    private val onEpisodeClick: (EpisodeModel) -> Unit,
    private val onSeasonClick: (SeriesModel) -> Unit,
    private val onSectionEpisodeClick: (VideoModel) -> Unit,
    private val onContentFocused: () -> Unit = {},
    private val onContentVerticalKey: ((View, Int) -> Boolean)? = null
) : ListAdapter<SeriesDetailContentAdapter.Row, RecyclerView.ViewHolder>(RowItemCallback) {

    sealed interface Row {
        data class Header(
            val detail: EpisodesDetailModel,
            val followed: Boolean
        ) : Row

        data class Episodes(
            val items: List<EpisodeModel>
        ) : Row

        data class Section(
            val section: SectionModel
        ) : Row

        data class Seasons(
            val title: String,
            val items: List<SeriesModel>
        ) : Row
    }

    private var lastFocusedRowPosition = RecyclerView.NO_POSITION

    fun submit(detail: EpisodesDetailModel?, isFollowed: Boolean) {
        val newRows = buildRows(detail, isFollowed)
        if (currentList == newRows) {
            return
        }
        lastFocusedRowPosition = newRows.lastIndex
            .takeIf { it >= 0 }
            ?.let { lastFocusedRowPosition.coerceAtMost(it) }
            ?: RecyclerView.NO_POSITION
        submitList(newRows)
    }

    fun updateHeader(detail: EpisodesDetailModel?, isFollowed: Boolean) {
        val headerIndex = currentList.indexOfFirst { it is Row.Header }
        if (detail == null || headerIndex == -1) {
            submit(detail, isFollowed)
            return
        }
        val newList = currentList.toMutableList()
        newList[headerIndex] = Row.Header(detail, isFollowed)
        submitList(newList)
    }

    fun requestLastFocusedView(recyclerView: RecyclerView): Boolean {
        val targetPosition = lastFocusedRowPosition
        if (targetPosition != RecyclerView.NO_POSITION) {
            when (val holder = recyclerView.findViewHolderForAdapterPosition(targetPosition)) {
                is EpisodeLaneViewHolder -> if (holder.requestStoredFocus()) return true
                is SectionLaneViewHolder -> if (holder.requestStoredFocus()) return true
                is SeasonLaneViewHolder -> if (holder.requestStoredFocus()) return true
            }
        }
        return false
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is Row.Header -> VIEW_TYPE_HEADER
            is Row.Episodes -> VIEW_TYPE_EPISODES
            is Row.Section -> VIEW_TYPE_SECTION
            is Row.Seasons -> VIEW_TYPE_SEASONS
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderViewHolder(
                CellSeriesHeadBinding.inflate(inflater, parent, false),
                onToggleFollow,
                onContinueWatchClick
            )

            VIEW_TYPE_EPISODES -> EpisodeLaneViewHolder(
                CellSeriesLaneBinding.inflate(inflater, parent, false),
                onEpisodeClick
            )

            VIEW_TYPE_SECTION -> SectionLaneViewHolder(
                CellSeriesLaneBinding.inflate(inflater, parent, false),
                onSectionEpisodeClick
            )

            else -> SeasonLaneViewHolder(
                CellSeriesLaneBinding.inflate(inflater, parent, false),
                onSeasonClick
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Header -> (holder as HeaderViewHolder).bind(row.detail, row.followed)
            is Row.Episodes -> (holder as EpisodeLaneViewHolder).bind(row.items)
            is Row.Section -> (holder as SectionLaneViewHolder).bind(row.section)
            is Row.Seasons -> (holder as SeasonLaneViewHolder).bind(row.title, row.items)
        }
    }

    private class HeaderViewHolder(
        private val binding: CellSeriesHeadBinding,
        onToggleFollow: () -> Unit,
        private val onContinueWatchClick: (EpisodeModel, Long) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentContinueEpisode: EpisodeModel? = null
        private var currentContinueSeekPositionMs: Long = 0L

        init {
            binding.buttonContinueWatch.setOnClickListener {
                currentContinueEpisode?.let { episode ->
                    onContinueWatchClick(episode, currentContinueSeekPositionMs)
                }
            }
            binding.buttonFollowSeries.setOnClickListener {
                onToggleFollow()
            }
        }

        fun bind(detail: EpisodesDetailModel, followed: Boolean) {
            ImageLoader.loadSeriesCover(
                imageView = binding.imageCover,
                url = detail.cover.ifEmpty { detail.squareCover }
            )
            ImageLoader.loadCenterCrop(
                imageView = binding.imageTop,
                url = detail.squareCover.ifEmpty { detail.cover },
                placeholder = R.drawable.default_video,
                error = R.drawable.default_video
            )

            binding.textTitle.text = detail.title.ifEmpty { detail.seasonTitle }
            binding.textSubtitle.text = buildSubtitle(detail)
            binding.textSubtitle.visibility =
                if (binding.textSubtitle.text.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.textDescription.text = detail.evaluate.ifBlank { detail.subtitle }
            binding.textDescription.visibility =
                if (binding.textDescription.text.isNullOrBlank()) View.GONE else View.VISIBLE

            val progress = detail.userStatus?.progress
            currentContinueEpisode = detail.episodes.orEmpty().firstOrNull {
                it.id == progress?.lastEpId
            } ?: detail.episodes.orEmpty().firstOrNull()
            currentContinueSeekPositionMs = progress?.lastTime
                ?.coerceAtLeast(0L)
                ?.times(1000L)
                ?: 0L

            val hasHistoryProgress = progress != null && progress.lastEpIndex.isNotBlank()
            binding.textWatchInfo.text = if (hasHistoryProgress) {
                val lastEpIndex = progress?.lastEpIndex.orEmpty()
                val lastTime = progress?.lastTime ?: 0L
                binding.root.context.getString(
                    R.string.last_watch_,
                    lastEpIndex,
                    TimeUtils.formatDuration(lastTime)
                )
            } else {
                detail.newEp?.pubTime?.takeIf { it.isNotBlank() }
                    ?: detail.subtitle
            }
            binding.textWatchInfo.visibility =
                if (binding.textWatchInfo.text.isNullOrBlank()) View.GONE else View.VISIBLE

            val isAnimation = detail.type == 1
            binding.buttonFollowSeries.text = binding.root.context.getString(
                if (followed) {
                    if (isAnimation) R.string.followed_animation else R.string.followed_series
                } else {
                    if (isAnimation) R.string.follow_animation else R.string.follow_series
                }
            )
            binding.buttonFollowSeries.setBackgroundResource(
                if (followed) R.drawable.button_common_2 else R.drawable.button_common
            )
            binding.buttonContinueWatch.text = binding.root.context.getString(
                if (hasHistoryProgress) R.string.continue_watch else R.string.start_watch
            )
            binding.buttonContinueWatch.isEnabled = currentContinueEpisode != null
            binding.buttonContinueWatch.alpha = if (currentContinueEpisode != null) 1f else 0.45f
        }

        private fun buildSubtitle(detail: EpisodesDetailModel): String {
            return buildList {
                buildStatusInfo(detail)?.let { add(it) }
                detail.rating?.score
                    ?.takeIf { it > 0 }
                    ?.let { add("评分 $it") }
                detail.stat?.view
                    ?.takeIf { it > 0 }
                    ?.let { add("${NumberUtils.formatCount(it)} 播放") }
                detail.stat?.danmaku
                    ?.takeIf { it > 0 }
                    ?.let { add("${NumberUtils.formatCount(it)} 弹幕") }
            }.joinToString(" | ")
        }

        private fun buildStatusInfo(detail: EpisodesDetailModel): String? {
            detail.newEp?.desc?.takeIf { it.isNotBlank() }?.let { return it }
            detail.newEp?.indexShow?.takeIf { it.isNotBlank() }?.let { return it }
            val publish = detail.publish
            if (publish != null) {
                val unit = if (detail.type == 1) "话" else "集"
                val statusText = if (publish.isFinish == 1) {
                    "已完结，全${detail.total}${unit}"
                } else {
                    val timeShow = publish.pubTimeShow.takeIf { it.isNotBlank() }
                        ?: publish.releaseDateShow.takeIf { it.isNotBlank() }
                    if (!timeShow.isNullOrBlank()) {
                        "连载中，$timeShow"
                    } else {
                        val episodeCount = detail.episodes?.size ?: 0
                        "连载中，更新至第${episodeCount}${unit}"
                    }
                }
                return statusText
            }
            val total = detail.total
            val episodeCount = detail.episodes?.size ?: 0
            val unit = if (detail.type == 1) "话" else "集"
            if (total > 0) {
                return if (episodeCount >= total) {
                    "已完结，全${total}${unit}"
                } else {
                    "连载中，更新至第${episodeCount}${unit}"
                }
            }
            if (episodeCount > 0) {
                return "更新至第${episodeCount}${unit}"
            }
            return null
        }
    }

    private inner class EpisodeLaneViewHolder(
        private val binding: CellSeriesLaneBinding,
        onEpisodeClick: (EpisodeModel) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private val adapter = EpisodeAdapter(
            onEpisodeClick = onEpisodeClick,
            onEpisodeFocused = ::onRowContentFocused,
            onVerticalKey = { view, keyCode ->
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP &&
                    binding.buttonOrder.visibility == View.VISIBLE &&
                    binding.buttonOrder.requestFocus()
                ) {
                    true
                } else {
                    onContentVerticalKey?.invoke(view, keyCode) == true
                }
            }
        )
        private var inReverseOrder = false
        private var items: List<EpisodeModel> = emptyList()
        private var orderButtonFocused = false

        init {
            binding.recyclerView.layoutManager =
                LinearLayoutManager(binding.root.context, RecyclerView.HORIZONTAL, false)
            binding.recyclerView.adapter = adapter
            binding.recyclerView.isNestedScrollingEnabled = false
            binding.buttonOrder.visibility = View.VISIBLE
            binding.textOrder.setText(R.string.negative_sequence)
            binding.buttonOrder.setOnClickListener {
                inReverseOrder = !inReverseOrder
                adapter.reverse()
                binding.recyclerView.post { binding.recyclerView.scrollToPosition(0) }
                binding.textOrder.setText(
                    if (inReverseOrder) R.string.positive_sequence else R.string.negative_sequence
                )
            }
            binding.buttonOrder.setOnFocusChangeListener { _, hasFocus ->
                orderButtonFocused = hasFocus
                if (hasFocus) {
                    onRowContentFocused()
                }
            }
            binding.buttonOrder.setOnKeyListener { view, keyCode, event ->
                if (event.action != android.view.KeyEvent.ACTION_DOWN) {
                    return@setOnKeyListener false
                }
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP ->
                        onContentVerticalKey?.invoke(view, keyCode) == true

                    android.view.KeyEvent.KEYCODE_DPAD_DOWN ->
                        focusNearestEpisodeItem(view.centerXOnScreen())

                    else -> false
                }
            }
        }

        fun bind(items: List<EpisodeModel>) {
            binding.topTitle.setText(R.string.episodes)
            this.items = items
            inReverseOrder = false
            binding.buttonOrder.visibility = if (items.size > 1) View.VISIBLE else View.GONE
            if (binding.buttonOrder.visibility != View.VISIBLE) {
                orderButtonFocused = false
            }
            bindEpisodes()
        }

        private fun bindEpisodes() {
            adapter.submitList(items)
            binding.textOrder.setText(R.string.negative_sequence)
        }

        fun requestStoredFocus(): Boolean {
            if (orderButtonFocused && binding.buttonOrder.isShown && binding.buttonOrder.requestFocus()) {
                return true
            }
            return adapter.requestFocusedView()
        }

        private fun onRowContentFocused() {
            updateFocusedRow(bindingAdapterPosition)
        }

        private fun focusNearestEpisodeItem(anchorX: Int): Boolean {
            val targetView = binding.recyclerView.visibleFocusableChildren()
                .minByOrNull { child -> kotlin.math.abs(child.centerXOnScreen() - anchorX) }
                ?: return false
            return targetView.requestFocus()
        }
    }

    private inner class SectionLaneViewHolder(
        private val binding: CellSeriesLaneBinding,
        onItemClick: (VideoModel) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private val adapter = SectionEpisodeAdapter(
            onItemClick = onItemClick,
            onItemFocused = ::onRowContentFocused,
            onVerticalKey = onContentVerticalKey
        )

        init {
            binding.recyclerView.layoutManager =
                LinearLayoutManager(binding.root.context, RecyclerView.HORIZONTAL, false)
            binding.recyclerView.adapter = adapter
            binding.recyclerView.isNestedScrollingEnabled = false
            binding.buttonOrder.visibility = View.GONE
        }

        fun bind(section: SectionModel) {
            binding.topTitle.text = section.resolveDisplayTitle(binding.root)
            adapter.setItems(section.episodes)
        }

        fun requestStoredFocus(): Boolean {
            return adapter.requestFocusedView()
        }

        private fun onRowContentFocused() {
            updateFocusedRow(bindingAdapterPosition)
        }
    }

    private inner class SeasonLaneViewHolder(
        private val binding: CellSeriesLaneBinding,
        onSeasonClick: (SeriesModel) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private val adapter = SeriesAdapter(
            onItemClick = onSeasonClick,
            enableSidebarExit = false,
            onItemFocused = ::onRowContentFocused,
            onVerticalKey = onContentVerticalKey
        )

        init {
            binding.recyclerView.layoutManager =
                LinearLayoutManager(binding.root.context, RecyclerView.HORIZONTAL, false)
            binding.recyclerView.adapter = adapter
            binding.recyclerView.isNestedScrollingEnabled = false
            binding.buttonOrder.visibility = View.GONE
        }

        fun bind(title: String, items: List<SeriesModel>) {
            binding.topTitle.text = title
            adapter.setData(items)
        }

        fun requestStoredFocus(): Boolean {
            return adapter.requestFocusedView()
        }

        private fun onRowContentFocused() {
            updateFocusedRow(bindingAdapterPosition)
        }
    }

    private class SectionEpisodeAdapter(
        private val onItemClick: (VideoModel) -> Unit,
        private val onItemFocused: (() -> Unit)? = null,
        private val onVerticalKey: ((View, Int) -> Boolean)? = null
    ) : ListAdapter<VideoModel, SectionEpisodeAdapter.SectionEpisodeViewHolder>(VideoItemCallback) {

        private var focusedView: View? = null

        fun setItems(data: List<VideoModel>) {
            if (currentList == data) {
                return
            }
            focusedView = null
            submitList(data)
        }

        fun requestFocusedView(): Boolean {
            val view = focusedView ?: return false
            return view.requestFocus()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionEpisodeViewHolder {
            val binding = CellEpisodeBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return SectionEpisodeViewHolder(binding, onItemClick) { _ ->
                onItemFocused?.invoke()
            }.also { holder ->
                holder.onVerticalKey = onVerticalKey
                holder.onFocusedViewChanged = { view ->
                    focusedView = view
                }
            }
        }

        override fun onBindViewHolder(holder: SectionEpisodeViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        class SectionEpisodeViewHolder(
            private val binding: CellEpisodeBinding,
            private val onItemClick: (VideoModel) -> Unit,
            private val onFocused: (View) -> Unit
        ) : RecyclerView.ViewHolder(binding.root) {

            private var currentItem: VideoModel? = null
            var onVerticalKey: ((View, Int) -> Boolean)? = null
            var onFocusedViewChanged: ((View) -> Unit)? = null

            init {
                binding.clickView.setOnClickListener {
                    currentItem?.let(onItemClick)
                }
            }

            fun bind(item: VideoModel) {
                currentItem = item
                applyDetailCardWidth(binding.clickView)
                binding.clickView.nextFocusUpId = View.NO_ID
                binding.clickView.setOnKeyListener { view, keyCode, event ->
                    if (event.action != android.view.KeyEvent.ACTION_DOWN) {
                        return@setOnKeyListener false
                    }
                    when (keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_UP,
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> onVerticalKey?.invoke(view, keyCode) == true
                        else -> false
                    }
                }
                ImageLoader.loadVideoCover(binding.imageView, item.cover.ifEmpty { item.pic })
                binding.viewCover.visibility = View.GONE
                binding.iconPlay.visibility = View.GONE
                binding.textBadge.visibility = View.GONE
                binding.textPosition.visibility = View.GONE
                binding.textTitle.visibility = View.VISIBLE
                binding.textTitle.text =
                    item.title.ifBlank { item.bangumi?.longTitle.orEmpty() }
                binding.clickView.setOnFocusChangeListener { view, hasFocus ->
                    if (hasFocus) {
                        onFocusedViewChanged?.invoke(view)
                        onFocused(view)
                    }
                }
            }

            private fun applyDetailCardWidth(view: View) {
                val layoutParams = view.layoutParams ?: return
                val targetWidth = ScreenUtils.getScreenWidth(view.context) / 5
                if (layoutParams.width != targetWidth) {
                    layoutParams.width = targetWidth
                    view.layoutParams = layoutParams
                }
            }
        }

        companion object {
            private val VideoItemCallback = object : DiffUtil.ItemCallback<VideoModel>() {
                override fun areItemsTheSame(oldItem: VideoModel, newItem: VideoModel): Boolean {
                    return when {
                        oldItem.aid > 0 && newItem.aid > 0 -> oldItem.aid == newItem.aid
                        oldItem.bvid.isNotBlank() && newItem.bvid.isNotBlank() -> oldItem.bvid == newItem.bvid
                        oldItem.cid > 0 && newItem.cid > 0 -> oldItem.cid == newItem.cid
                        else -> oldItem == newItem
                    }
                }

                override fun areContentsTheSame(oldItem: VideoModel, newItem: VideoModel): Boolean {
                    return oldItem == newItem
                }
            }
        }
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_EPISODES = 1
        private const val VIEW_TYPE_SECTION = 2
        private const val VIEW_TYPE_SEASONS = 3

        private val RowItemCallback = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean {
                return stableId(oldItem) == stableId(newItem)
            }

            override fun areContentsTheSame(oldItem: Row, newItem: Row): Boolean {
                return oldItem == newItem
            }

            private fun stableId(row: Row): String {
                return when (row) {
                    is Row.Header -> "header"
                    is Row.Episodes -> "episodes"
                    is Row.Section -> "section:${row.section.id}:${row.section.type}:${row.section.title}"
                    is Row.Seasons -> "seasons:${row.title}"
                }
            }
        }
    }

    private fun updateFocusedRow(position: Int) {
        if (position == RecyclerView.NO_POSITION) {
            return
        }
        lastFocusedRowPosition = position
        onContentFocused()
    }

    private fun buildRows(detail: EpisodesDetailModel?, isFollowed: Boolean): List<Row> {
        if (detail == null) {
            return emptyList()
        }
        return buildList {
            add(Row.Header(detail, isFollowed))

            detail.episodes.orEmpty()
                .takeIf { it.isNotEmpty() }
                ?.let { add(Row.Episodes(it)) }

            detail.section.orEmpty()
                .filter { it.episodes.isNotEmpty() }
                .forEach { section ->
                    add(Row.Section(section))
                }

            detail.seasons.orEmpty()
                .takeIf { it.isNotEmpty() }
                ?.let { seasons ->
                    add(
                        Row.Seasons(
                            title = detail.resolveSeasonRowTitle(),
                            items = seasons
                        )
                    )
                }
        }
    }

}

private fun EpisodesDetailModel.resolveSeasonRowTitle(): String {
    val seriesTitle = title.ifBlank { seasonTitle }
    return if (seriesTitle.isBlank()) {
        "相关系列"
    } else {
        java.lang.String.format("%s 相关系列", seriesTitle)
    }
}

private fun SectionModel.resolveDisplayTitle(view: View): String {
    return title.takeIf { it.isNotBlank() } ?: view.context.getString(R.string.extra_content)
}

private fun View.centerXOnScreen(): Int {
    val location = IntArray(2)
    getLocationOnScreen(location)
    return location[0] + width / 2
}

private fun RecyclerView.visibleFocusableChildren(): List<View> {
    return buildList {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child != null && child.isFocusable && child.visibility == View.VISIBLE) {
                add(child)
            }
        }
    }
}
