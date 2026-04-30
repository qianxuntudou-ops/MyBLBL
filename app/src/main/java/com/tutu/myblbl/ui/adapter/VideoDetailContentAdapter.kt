package com.tutu.myblbl.ui.adapter

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayout
import com.tutu.myblbl.R
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.core.ui.system.ScreenUtils
import com.tutu.myblbl.core.common.format.NumberUtils
import com.tutu.myblbl.core.common.time.TimeUtils
import com.tutu.myblbl.databinding.CellSeriesLaneBinding
import com.tutu.myblbl.databinding.CellVideoBinding
import com.tutu.myblbl.databinding.CellVideoDetailHeadBinding
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.model.video.detail.Tag
import com.tutu.myblbl.model.video.detail.VideoView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoDetailContentAdapter(
    private val onPlayClick: () -> Unit,
    private val onUploaderClick: () -> Unit,
    private val onLikeClick: () -> Unit,
    private val onCoinClick: () -> Unit,
    private val onFavoriteClick: () -> Unit,
    private val onTagClick: (Tag) -> Unit,
    private val onPageClick: (VideoModel) -> Unit,
    private val onUgcEpisodeClick: (View, VideoModel) -> Unit,
    private val onUgcOrderToggle: () -> Unit,
    private val onRelatedVideoClick: (VideoModel) -> Unit,
    private val onDescriptionClick: (CharSequence) -> Unit,
    private val onFollowClick: () -> Unit,
    private val onTripleAction: () -> Unit
) : ListAdapter<VideoDetailContentAdapter.Row, RecyclerView.ViewHolder>(RowItemCallback) {

    sealed interface Row {
        data class Header(
            val view: VideoView,
            val tags: List<Tag>,
            val isLiked: Boolean,
            val isCoined: Boolean,
            val isFavorited: Boolean
        ) : Row

        data class Pages(val items: List<VideoModel>) : Row

        data class UgcSeason(
            val title: String,
            val items: List<VideoModel>,
            val isReverse: Boolean,
            val currentAid: Long = 0
        ) : Row

        data class Related(val items: List<VideoModel>) : Row
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is Row.Header -> VIEW_TYPE_HEADER
            is Row.Pages -> VIEW_TYPE_PAGES
            is Row.UgcSeason -> VIEW_TYPE_UGC_SEASON
            is Row.Related -> VIEW_TYPE_RELATED
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> VideoDetailHeadViewHolder(
                CellVideoDetailHeadBinding.inflate(inflater, parent, false),
                onPlayClick,
                onUploaderClick,
                onLikeClick,
                onCoinClick,
                onFavoriteClick,
                onTagClick,
                onDescriptionClick,
                onFollowClick,
                onTripleAction,
                parent.context
            )

            VIEW_TYPE_PAGES -> PagesLaneViewHolder(
                CellSeriesLaneBinding.inflate(inflater, parent, false),
                onPageClick
            )

            VIEW_TYPE_UGC_SEASON -> UgcSeasonLaneViewHolder(
                CellSeriesLaneBinding.inflate(inflater, parent, false),
                onUgcEpisodeClick,
                onUgcOrderToggle
            )

            else -> RelatedLaneViewHolder(
                CellSeriesLaneBinding.inflate(inflater, parent, false),
                onRelatedVideoClick
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Header -> (holder as VideoDetailHeadViewHolder).bind(row.view, row.tags, row.isLiked, row.isCoined, row.isFavorited)
            is Row.Pages -> (holder as PagesLaneViewHolder).bind(row.items)
            is Row.UgcSeason -> (holder as UgcSeasonLaneViewHolder).bind(row.title, row.items, row.isReverse, row.currentAid)
            is Row.Related -> (holder as RelatedLaneViewHolder).bind(row.items)
        }
    }

    class VideoDetailHeadViewHolder(
        private val binding: CellVideoDetailHeadBinding,
        private val onPlayClick: () -> Unit,
        private val onUploaderClick: () -> Unit,
        private val onLikeClick: () -> Unit,
        private val onCoinClick: () -> Unit,
        private val onFavoriteClick: () -> Unit,
        private val onTagClick: (Tag) -> Unit,
        private val onDescriptionClick: (CharSequence) -> Unit,
        private val onFollowClick: () -> Unit,
        private val onTripleAction: () -> Unit,
        private val context: Context
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentTags: List<Tag> = emptyList()
        private var currentDescription: CharSequence = ""
        private var relationAttribute = 0

        private var tripleActionTriggered = false
        private val tripleStartRunnable = Runnable {
            binding.viewTripleProgress.onComplete = {
                tripleActionTriggered = true
                onTripleAction()
            }
            binding.viewTripleProgress.start()
        }

        init {
            binding.cardCover.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val cardHeight = binding.cardCover.height
                    if (cardHeight > 0) {
                        val ringSize = (cardHeight * 0.38f).toInt()
                        val iconSize = (ringSize * 0.476f).toInt()
                        binding.viewPlayRing.layoutParams = binding.viewPlayRing.layoutParams.apply {
                            width = ringSize
                            height = ringSize
                        }
                        binding.iconPlayButton.layoutParams = binding.iconPlayButton.layoutParams.apply {
                            width = iconSize
                            height = iconSize
                        }
                        binding.cardCover.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    }
                }
            })

            binding.buttonPlay.setOnClickListener { onPlayClick() }
            @SuppressLint("ClickableViewAccessibility")
            binding.buttonPlay.setOnTouchListener { _, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> animatePlayPress(1.0f)
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> animatePlayPress(1.3f)
                }
                false
            }
            binding.buttonPlay.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                    if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                        animatePlayPress(1.0f)
                    } else if (event.action == android.view.KeyEvent.ACTION_UP) {
                        animatePlayPress(1.3f)
                        onPlayClick()
                    }
                    true
                } else false
            }
            binding.buttonUploader.setOnClickListener { onUploaderClick() }
            binding.buttonLike.setOnClickListener {
                if (!tripleActionTriggered) {
                    onLikeClick()
                    showTripleHintIfNeeded()
                }
            }
            binding.buttonCoin.setOnClickListener { onCoinClick() }
            binding.buttonFavorite.setOnClickListener { onFavoriteClick() }
            binding.buttonDetail.setOnClickListener { onDescriptionClick(currentDescription) }
            binding.buttonFollow.setOnClickListener { onFollowClick() }

            binding.buttonLike.setOnLongClickListener {
                tripleActionTriggered = false
                tripleStartRunnable.run()
                true
            }
            @SuppressLint("ClickableViewAccessibility")
            binding.buttonLike.setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_UP || event.action == android.view.MotionEvent.ACTION_CANCEL) {
                    if (binding.viewTripleProgress.visibility == View.VISIBLE && !tripleActionTriggered) {
                        binding.viewTripleProgress.cancel()
                    }
                }
                false
            }
            binding.buttonLike.setOnKeyListener { v, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                    if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                        tripleActionTriggered = false
                        v.removeCallbacks(tripleStartRunnable)
                        v.postDelayed(tripleStartRunnable, 500)
                    } else if (event.action == android.view.KeyEvent.ACTION_UP) {
                        v.removeCallbacks(tripleStartRunnable)
                        val wasLongPress = binding.viewTripleProgress.visibility == View.VISIBLE
                        if (wasLongPress) {
                            binding.viewTripleProgress.cancel()
                        }
                        if (!tripleActionTriggered && !wasLongPress) {
                            onLikeClick()
                            showTripleHintIfNeeded()
                        }
                    }
                    true
                } else false
            }

            val scrollListener = View.OnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    (itemView.parent as? RecyclerView)?.smoothScrollToPosition(0)
                }
            }

            val accentColor = ContextCompat.getColor(context, R.color.colorAccent)
            val white = android.graphics.Color.WHITE

            binding.buttonPlay.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    (itemView.parent as? RecyclerView)?.smoothScrollToPosition(0)
                }
                val scale = if (hasFocus) 1.3f else 1.0f
                val iconColor = if (hasFocus) accentColor else white
                binding.iconPlayButton.animate().scaleX(scale).scaleY(scale).setDuration(200).start()
                binding.viewPlayRing.animate().scaleX(scale).scaleY(scale).setDuration(200).start()
                binding.iconPlayButton.setColorFilter(iconColor)
                val ringDrawable = binding.viewPlayRing.background?.mutate()
                if (ringDrawable is android.graphics.drawable.GradientDrawable) {
                    ringDrawable.setStroke(context.resources.getDimensionPixelSize(R.dimen.px6), iconColor)
                }
            }
            binding.buttonUploader.onFocusChangeListener = scrollListener
            binding.buttonLike.onFocusChangeListener = scrollListener
            binding.buttonCoin.onFocusChangeListener = scrollListener
            binding.buttonFavorite.onFocusChangeListener = scrollListener
            binding.buttonFollow.onFocusChangeListener = scrollListener
        }

        private fun animatePlayPress(scale: Float) {
            binding.viewPlayRing.animate().scaleX(scale).scaleY(scale).setDuration(100).start()
            binding.iconPlayButton.animate().scaleX(scale).scaleY(scale).setDuration(100).start()
        }

        fun bind(view: VideoView, tags: List<Tag>, isLiked: Boolean, isCoined: Boolean, isFavorited: Boolean) {
            ImageLoader.loadVideoCover(
                imageView = binding.imageCover,
                url = view.pic,
                placeholder = R.drawable.default_video,
                error = R.drawable.default_video
            )

            binding.textTitle.text = view.title

            val stat = view.stat
            val subtitleText = buildString {
                if (view.pubDate > 0) {
                    append("发布于：")
                    append(TimeUtils.formatTime(view.pubDate))
                }
                stat?.let { s ->
                    if (isNotEmpty()) append(" · ")
                    append(formatCount(s.view))
                    append("播放")
                    append(" · ")
                    append(formatCount(s.danmaku))
                    append("弹幕")
                    if (s.like > 0) {
                        append(" · ")
                        append(formatCount(s.like))
                        append("点赞")
                    }
                    if (s.coin > 0) {
                        append(" · ")
                        append(formatCount(s.coin))
                        append("投币")
                    }
                    if (s.favorite > 0) {
                        append(" · ")
                        append(formatCount(s.favorite))
                        append("收藏")
                    }
                }
            }
            binding.textSubtitle.text = subtitleText

            val owner = view.owner
            if (owner != null) {
                binding.textName.text = owner.name
                ImageLoader.loadCircle(
                    imageView = binding.imageAvatar,
                    url = owner.face,
                    placeholder = R.drawable.default_avatar,
                    error = R.drawable.default_avatar
                )
                binding.imageAvatar.setBadge(
                    officialVerifyType = owner.officialVerify?.type ?: -1
                )
            }

            currentDescription = view.desc.orEmpty()
            updateDescription(currentDescription)

            updateTagLayout(tags)
            updateActionButtons(isLiked, isCoined, isFavorited)
            updateUploaderNextFocusDown()
        }

        private fun updateUploaderNextFocusDown() {
            val targetId = if (binding.buttonDetail.visibility == View.VISIBLE) {
                R.id.button_detail
            } else if (binding.viewFlexLayout.visibility == View.VISIBLE && binding.viewFlexLayout.childCount > 0) {
                R.id.view_flex_layout
            } else {
                0
            }
            if (targetId != 0) {
                binding.buttonUploader.nextFocusDownId = targetId
                binding.buttonFollow.nextFocusDownId = targetId
            }
        }

        private fun updateDescription(description: CharSequence) {
            if (description.isBlank()) {
                binding.textDescription.visibility = View.GONE
                binding.buttonDetail.visibility = View.GONE
                updateUploaderNextFocusDown()
                return
            }
            binding.textDescription.visibility = View.VISIBLE
            val displayText = "简介：${description.toString().replace(Regex("[\\r\\n]+"), " ")}"
            binding.textDescription.text = displayText
            binding.textDescription.post {
                val availableWidth = binding.textDescription.width -
                        binding.textDescription.paddingLeft - binding.textDescription.paddingRight
                if (availableWidth > 0) {
                    val textWidth = binding.textDescription.paint.measureText(displayText.toString())
                    binding.buttonDetail.visibility = if (textWidth > availableWidth) View.VISIBLE else View.GONE
                    updateUploaderNextFocusDown()
                }
            }
        }

        private fun updateTagLayout(tags: List<Tag>) {
            binding.viewFlexLayout.removeAllViews()
            if (tags.isEmpty()) {
                binding.viewFlexLayout.visibility = View.GONE
                return
            }
            currentTags = tags
            binding.viewFlexLayout.visibility = View.VISIBLE
            tags.take(6).forEach { tag ->
                val tagView = createTagView(tag.tagName)
                tagView.setOnClickListener { onTagClick(tag) }
                binding.viewFlexLayout.addView(tagView)
            }
        }

        private fun createTagView(text: String): AppCompatTextView {
            val textSizePx = context.resources.getDimension(R.dimen.px24)
            val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
            val textColor = ta.getColor(0, 0)
            ta.recycle()
            return AppCompatTextView(context).apply {
                this.text = text
                tag = text
                setTextSize(0, textSizePx)
                setTextColor(textColor)
                setBackgroundResource(R.drawable.button_common)
                setPadding(15, 3, 15, 3)
                layoutParams = FlexboxLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 5
                    bottomMargin = 3
                    rightMargin = 8
                }
                isClickable = true
                isFocusable = true
            }
        }

        fun currentRelationAttribute(): Int = relationAttribute

        fun updateActionButtons(isLiked: Boolean, isCoined: Boolean, isFavorited: Boolean) {
            val pink = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.pink)
            )
            val default = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.textColor)
            )
            binding.buttonLike.imageTintList = if (isLiked) pink else default
            binding.buttonCoin.imageTintList = if (isCoined) pink else default
            binding.buttonFavorite.imageTintList = if (isFavorited) pink else default
        }

        fun updateFollowState(attribute: Int) {
            relationAttribute = attribute
            val isMutual = attribute == 6
            val isFollowing = attribute == 2 || attribute == 6
            val white = ContextCompat.getColorStateList(context, android.R.color.white)
            if (isMutual) {
                binding.textFollow.text = context.getString(R.string.follow_as_friend)
                binding.textFollow.setTextColor(ContextCompat.getColor(context, android.R.color.white))
                binding.iconFollow.setImageResource(R.drawable.ic_check)
                binding.iconFollow.imageTintList = white
            } else if (isFollowing) {
                binding.textFollow.text = context.getString(R.string.followed)
                binding.textFollow.setTextColor(ContextCompat.getColor(context, android.R.color.white))
                binding.iconFollow.setImageResource(R.drawable.ic_check)
                binding.iconFollow.imageTintList = white
            } else {
                binding.textFollow.text = context.getString(R.string.follow)
                binding.textFollow.setTextColor(ContextCompat.getColor(context, android.R.color.white))
                binding.iconFollow.setImageResource(R.drawable.ic_plus)
                binding.iconFollow.imageTintList = white
            }
        }

        fun updateFansCount(count: Long) {
            binding.textFans.text = formatFans(count)
        }

        private fun showTripleHintIfNeeded() {
            val prefs = context.getSharedPreferences("triple_action_hint", Context.MODE_PRIVATE)
            val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            if (prefs.getString("last_hint_date", "") != today) {
                prefs.edit().putString("last_hint_date", today).apply()
                Toast.makeText(context, "长按点赞可一键三连", Toast.LENGTH_SHORT).show()
            }
        }

        private fun formatCount(count: Long): String {
            return when {
                count >= 100000000 -> String.format(Locale.getDefault(), "%.1f亿", count / 100000000.0)
                count >= 10000 -> String.format(Locale.getDefault(), "%.1f万", count / 10000.0)
                count >= 1000 -> String.format(Locale.getDefault(), "%.1f千", count / 1000.0)
                else -> count.toString()
            }
        }

        private fun formatFans(count: Long): String {
            val number = when {
                count >= 10000 -> String.format(Locale.getDefault(), "%.1f万", count / 10000.0)
                else -> String.format(Locale.getDefault(), "%,d", count)
            }
            return "${number}粉丝"
        }
    }

    class PagesLaneViewHolder(
        private val binding: CellSeriesLaneBinding,
        onPageClick: (VideoModel) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private val adapter = EpisodeListAdapter()

        init {
            adapter.setShowLoadMore(false)
            binding.recyclerView.layoutManager =
                LinearLayoutManager(binding.root.context, RecyclerView.HORIZONTAL, false)
            binding.recyclerView.adapter = adapter
            binding.recyclerView.isFocusable = false
            binding.buttonOrder.visibility = View.GONE
            adapter.setOnItemClickListener { _, item ->
                onPageClick(item)
            }
        }

        fun bind(items: List<VideoModel>) {
            binding.topTitle.text = binding.root.context.getString(
                R.string.video_detail_pages_title, items.size
            )
            adapter.setData(items)
        }
    }

    class UgcSeasonLaneViewHolder(
        private val binding: CellSeriesLaneBinding,
        onEpisodeClick: (View, VideoModel) -> Unit,
        private val onOrderToggle: () -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private val videoAdapter = VideoAdapter(itemWidthPx = ScreenUtils.getScreenWidth(binding.root.context) / 5)
        val innerRecyclerView: RecyclerView get() = binding.recyclerView
        private var lastIsReverse: Boolean? = null
        private var lastCurrentAid: Long = 0

        init {
            videoAdapter.setShowLoadMore(false)
            binding.recyclerView.layoutManager =
                LinearLayoutManager(binding.root.context, RecyclerView.HORIZONTAL, false)
            binding.recyclerView.adapter = videoAdapter
            binding.recyclerView.isFocusable = false
            binding.buttonOrder.visibility = View.VISIBLE
            binding.buttonOrder.setOnClickListener {
                onOrderToggle()
            }
            videoAdapter.setOnItemClickListener { v, item ->
                onEpisodeClick(v, item)
            }
            bindBackButtonOnScroll(binding.recyclerView)
        }

        fun bind(title: String, items: List<VideoModel>, isReverse: Boolean, currentAid: Long) {
            binding.topTitle.text = title
            binding.textOrder.text = if (isReverse) "正序" else "倒序"
            if (lastIsReverse != isReverse) {
                lastIsReverse = isReverse
                lastCurrentAid = currentAid
                videoAdapter.currentPlayingAid = currentAid
                videoAdapter.setData(items) {
                    scrollToCurrentVideo(items, currentAid)
                }
            } else if (lastCurrentAid != currentAid) {
                val oldAid = lastCurrentAid
                lastCurrentAid = currentAid
                videoAdapter.currentPlayingAid = currentAid
                val snapshot = videoAdapter.getItemsSnapshot()
                val oldPos = snapshot.indexOfFirst { it.aid == oldAid }
                val newPos = snapshot.indexOfFirst { it.aid == currentAid }
                if (oldPos >= 0) videoAdapter.notifyItemChanged(oldPos)
                if (newPos >= 0 && newPos != oldPos) videoAdapter.notifyItemChanged(newPos)
            }
        }

        private fun scrollToCurrentVideo(items: List<VideoModel>, currentAid: Long) {
            val currentIndex = items.indexOfFirst { it.aid == currentAid }
            if (currentIndex < 0) return
            val llm = binding.recyclerView.layoutManager as? LinearLayoutManager ?: return
            if (currentIndex > 0) {
                val halfItemWidth = (ScreenUtils.getScreenWidth(binding.root.context) / 5) / 3
                llm.scrollToPositionWithOffset(currentIndex - 1, -halfItemWidth)
            } else {
                llm.scrollToPosition(0)
            }
        }
    }

    class RelatedLaneViewHolder(
        private val binding: CellSeriesLaneBinding,
        onVideoClick: (VideoModel) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private val videoAdapter = VideoAdapter(itemWidthPx = ScreenUtils.getScreenWidth(binding.root.context) / 5)

        init {
            videoAdapter.setShowLoadMore(false)
            binding.recyclerView.layoutManager =
                LinearLayoutManager(binding.root.context, RecyclerView.HORIZONTAL, false)
            binding.recyclerView.adapter = videoAdapter
            binding.recyclerView.isFocusable = false
            binding.buttonOrder.visibility = View.GONE
            videoAdapter.setOnItemClickListener { _, item ->
                onVideoClick(item)
            }
            bindBackButtonOnScroll(binding.recyclerView)
        }

        fun bind(items: List<VideoModel>) {
            binding.topTitle.text = binding.root.context.getString(R.string.related_video)
            videoAdapter.setData(items)
        }
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_PAGES = 1
        private const val VIEW_TYPE_UGC_SEASON = 2
        private const val VIEW_TYPE_RELATED = 3

        private fun bindBackButtonOnScroll(recyclerView: RecyclerView) {
            recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val llm = rv.layoutManager as? LinearLayoutManager ?: return
                    val firstPos = llm.findFirstVisibleItemPosition()
                    for (i in 0 until rv.childCount) {
                        val child = rv.getChildAt(i)
                        val vh = rv.getChildViewHolder(child)
                        child.nextFocusLeftId = if (vh.bindingAdapterPosition == firstPos) {
                            R.id.button_back_1
                        } else {
                            View.NO_ID
                        }
                    }
                }
            })
        }

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
                    is Row.Pages -> "pages"
                    is Row.UgcSeason -> "ugc_season"
                    is Row.Related -> "related"
                }
            }
        }
    }
}
