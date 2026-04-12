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
import com.tutu.myblbl.databinding.CellMovieBinding
import com.tutu.myblbl.model.lane.LaneItemModel
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.core.ui.focus.VideoCardFocusHelper

class LaneItemAdapter(
    private val onItemClick: (LaneItemModel) -> Unit = {},
    private val onItemFocused: ((View) -> Unit)? = null
) : ListAdapter<LaneItemModel, LaneItemAdapter.LaneItemViewHolder>(LaneItemDiffCallback) {

    init {
        setHasStableIds(true)
    }

    fun setData(newItems: List<LaneItemModel>) {
        submitList(newItems.toList())
    }

    fun requestFirstItemFocus(recyclerView: RecyclerView): Boolean {
        val holder = recyclerView.findViewHolderForAdapterPosition(0) as? LaneItemViewHolder
        return holder?.requestFocus() == true
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LaneItemViewHolder {
        val binding = CellMovieBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LaneItemViewHolder(binding, onItemClick, onItemFocused)
    }

    override fun onBindViewHolder(holder: LaneItemViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun getItemId(position: Int): Long = getItem(position).stableItemId()

    override fun onViewRecycled(holder: LaneItemViewHolder) {
        holder.clearFocusState()
        super.onViewRecycled(holder)
    }

    class LaneItemViewHolder(
        private val binding: CellMovieBinding,
        private val onItemClick: (LaneItemModel) -> Unit,
        private val onItemFocused: ((View) -> Unit)?
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentItem: LaneItemModel? = null

        init {
            binding.clickView.setOnClickListener {
                onItemFocused?.invoke(binding.clickView)
                currentItem?.let(onItemClick)
            }
            binding.clickView.setOnFocusChangeListener { view, hasFocus ->
                applyFocusState(hasFocus)
                if (hasFocus) {
                    onItemFocused?.invoke(view)
                }
            }
            VideoCardFocusHelper.bindSidebarExit(binding.clickView)
        }

        fun bind(item: LaneItemModel) {
            currentItem = item

            ImageLoader.loadSeriesCover(binding.imageView, item.cover)

            binding.textView.text = item.title

            val subtitle = item.desc.ifBlank { item.subTitle }
            if (subtitle.isBlank()) {
                binding.textSub.visibility = View.GONE
                binding.textSub.text = ""
            } else {
                binding.textSub.visibility = View.VISIBLE
                binding.textSub.text = subtitle
            }

            val badgeText = item.badgeInfo?.text.orEmpty()
            if (badgeText.isBlank()) {
                binding.textBadge.visibility = View.GONE
                binding.textBadge.text = ""
            } else {
                binding.textBadge.visibility = View.VISIBLE
                binding.textBadge.text = badgeText
                applyBadgeBackground(item.badgeInfo?.bgColorNight ?: item.badgeInfo?.bgColor)
            }

            applyFocusState(binding.clickView.hasFocus())
        }

        fun requestFocus(): Boolean = binding.clickView.requestFocus()

        fun clearFocusState() {
            applyFocusState(false)
        }

        private fun applyFocusState(hasFocus: Boolean) {
            binding.clickView.isSelected = hasFocus
            binding.textView.isSelected = hasFocus
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

    private companion object {
        val LaneItemDiffCallback = object : DiffUtil.ItemCallback<LaneItemModel>() {
            override fun areItemsTheSame(oldItem: LaneItemModel, newItem: LaneItemModel): Boolean {
                return oldItem.diffKey() == newItem.diffKey()
            }

            override fun areContentsTheSame(oldItem: LaneItemModel, newItem: LaneItemModel): Boolean {
                return oldItem == newItem
            }
        }
    }
}

private fun LaneItemModel.diffKey(): String {
    return when {
        seasonId > 0L -> "season:$seasonId"
        oid > 0L -> "oid:$oid"
        linkValue != 0 -> "link:$linkType:$linkValue"
        link.isNotBlank() -> "url:$link"
        cover.isNotBlank() && title.isNotBlank() -> "cover:$cover|title:$title"
        else -> "title:$title|subtitle:$subTitle"
    }
}

private fun LaneItemModel.stableItemId(): Long = diffKey().hashCode().toLong()
