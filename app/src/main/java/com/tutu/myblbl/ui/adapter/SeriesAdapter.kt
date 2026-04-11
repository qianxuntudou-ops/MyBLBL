package com.tutu.myblbl.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellSeriesBinding
import com.tutu.myblbl.model.series.SeriesModel
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.core.ui.focus.VideoCardFocusHelper

class SeriesAdapter(
    private val onItemClick: (SeriesModel) -> Unit = {},
    private val enableSidebarExit: Boolean = true,
    private val onTopEdgeUp: (() -> Boolean)? = null,
    private val onLeftEdge: (() -> Boolean)? = null,
    private val onRightEdge: (() -> Boolean)? = null,
    private val onItemFocused: (() -> Unit)? = null,
    private val nextFocusUpId: Int? = null,
    private val rememberFocusedItem: Boolean = true,
    private val onVerticalKey: ((View, Int) -> Boolean)? = null
) : ListAdapter<SeriesModel, SeriesAdapter.SeriesViewHolder>(DIFF_CALLBACK) {

    var focusedView: View? = null
        private set

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SeriesModel>() {
            override fun areItemsTheSame(oldItem: SeriesModel, newItem: SeriesModel): Boolean {
                return oldItem.seasonId == newItem.seasonId
            }

            override fun areContentsTheSame(oldItem: SeriesModel, newItem: SeriesModel): Boolean {
                return oldItem == newItem
            }
        }
    }

    fun getItemsSnapshot(): List<SeriesModel> = currentList.toList()

    fun setData(newItems: List<SeriesModel>) {
        if (rememberFocusedItem) {
            focusedView = null
        }
        submitList(newItems)
    }

    fun addData(newItems: List<SeriesModel>) {
        submitList(currentList + newItems)
    }

    fun requestFocusedView(): Boolean {
        if (!rememberFocusedItem) {
            return false
        }
        val view = focusedView ?: return false
        return view.requestFocus()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SeriesViewHolder {
        val binding = CellSeriesBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SeriesViewHolder(
            binding = binding,
            onItemClick = onItemClick,
            enableSidebarExit = enableSidebarExit,
            onTopEdgeUp = onTopEdgeUp,
            onLeftEdge = onLeftEdge,
            onRightEdge = onRightEdge,
            nextFocusUpId = nextFocusUpId,
            onVerticalKey = onVerticalKey
        ) { view ->
            if (rememberFocusedItem) {
                focusedView = view
            }
            onItemFocused?.invoke()
        }
    }

    override fun onBindViewHolder(holder: SeriesViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class SeriesViewHolder(
        private val binding: CellSeriesBinding,
        private val onItemClick: (SeriesModel) -> Unit,
        private val enableSidebarExit: Boolean,
        private val onTopEdgeUp: (() -> Boolean)?,
        private val onLeftEdge: (() -> Boolean)?,
        private val onRightEdge: (() -> Boolean)?,
        private val nextFocusUpId: Int?,
        private val onVerticalKey: ((View, Int) -> Boolean)?,
        private val onFocused: (View) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentItem: SeriesModel? = null

        init {
            binding.clickView.setOnClickListener {
                currentItem?.let(onItemClick)
            }
            binding.clickView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    onFocused(view)
                }
            }
            if (onVerticalKey != null) {
                binding.clickView.setOnKeyListener { view, keyCode, event ->
                    if (event.action != android.view.KeyEvent.ACTION_DOWN) {
                        return@setOnKeyListener false
                    }
                    when (keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_UP,
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> onVerticalKey.invoke(view, keyCode)
                        else -> false
                    }
                }
            }
            if (enableSidebarExit || onTopEdgeUp != null || onLeftEdge != null || onRightEdge != null) {
                VideoCardFocusHelper.bindSidebarExit(
                    binding.clickView,
                    onTopEdgeUp = onTopEdgeUp,
                    onLeftEdge = onLeftEdge,
                    onRightEdge = onRightEdge
                )
            }
        }

        fun bind(item: SeriesModel) {
            currentItem = item
            binding.clickView.nextFocusUpId = nextFocusUpId ?: View.NO_ID

            ImageLoader.loadSeriesCover(binding.imageView, item.cover)

            val title = item.title.ifEmpty { item.seasonTitle }
            if (title.isBlank()) {
                binding.textView.visibility = View.GONE
            } else {
                binding.textView.visibility = View.VISIBLE
                binding.textView.text = title
            }

            val subtitle = buildSubtitle(item)
            if (subtitle.isBlank()) {
                binding.textSub.visibility = View.GONE
            } else {
                binding.textSub.visibility = View.VISIBLE
                binding.textSub.text = subtitle
            }

            val badgeText = item.badgeInfo?.text?.takeIf { it.isNotBlank() } ?: item.badge
            if (badgeText.isEmpty()) {
                binding.textBadge.visibility = View.GONE
            } else {
                binding.textBadge.visibility = View.VISIBLE
                binding.textBadge.text = badgeText
                applyBadgeBackground(item.badgeInfo?.bgColorNight ?: item.badgeInfo?.bgColor)
            }
        }

        private fun buildSubtitle(item: SeriesModel): String {
            return item.progress.takeIf { it.isNotBlank() }
                ?: item.newEp?.indexShow?.takeIf { it.isNotBlank() }
                ?: item.firstEpInfo?.indexShow?.takeIf { it.isNotBlank() }
                ?: item.summary.takeIf { it.isNotBlank() }
                ?: item.evaluate.takeIf { it.isNotBlank() }
                ?: ""
        }

        private fun applyBadgeBackground(colorString: String?) {
            val context = binding.textBadge.context
            val drawable = AppCompatResources.getDrawable(
                context,
                R.drawable.badge_background
            )?.mutate() ?: return
            if (colorString.isNullOrBlank()) {
                binding.textBadge.background = drawable
                return
            }
            runCatching {
                val wrapped = DrawableCompat.wrap(drawable)
                DrawableCompat.setTint(wrapped, Color.parseColor(colorString))
                binding.textBadge.background = wrapped
            }.onFailure {
                binding.textBadge.background = drawable
            }
        }
    }
}
