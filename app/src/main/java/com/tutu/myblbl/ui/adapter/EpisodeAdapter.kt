package com.tutu.myblbl.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellEpisodeBinding
import com.tutu.myblbl.model.episode.EpisodeModel
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.core.ui.system.ScreenUtils

class EpisodeAdapter(
    private val onEpisodeClick: (EpisodeModel) -> Unit,
    private val onEpisodeFocused: (() -> Unit)? = null,
    private val nextFocusUpId: Int? = null,
    private val rememberFocusedItem: Boolean = true,
    private val onVerticalKey: ((View, Int) -> Boolean)? = null
) : RecyclerView.Adapter<EpisodeAdapter.EpisodeViewHolder>() {

    private val items = mutableListOf<EpisodeModel>()
    var focusedView: View? = null
        private set

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
        val binding = CellEpisodeBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return EpisodeViewHolder(
            binding = binding,
            onFocused = { view ->
            if (rememberFocusedItem) {
                focusedView = view
            }
            onEpisodeFocused?.invoke()
            },
            onVerticalKey = onVerticalKey
        ).also { holder ->
            holder.nextFocusUpId = nextFocusUpId
        }
    }

    fun submitList(list: List<EpisodeModel>?) {
        focusedView = null
        val newList = list ?: emptyList()
        val oldList = items.toList()
        val diffResult = DiffUtil.calculateDiff(EpisodeDiffCallback(oldList, newList))
        items.clear()
        items.addAll(newList)
        diffResult.dispatchUpdatesTo(this)
    }

    fun reverse() {
        java.util.Collections.reverse(items)
        notifyItemRangeChanged(0, items.size)
    }

    fun requestFocusedView(): Boolean {
        if (!rememberFocusedItem) {
            return false
        }
        val view = focusedView ?: return false
        return view.requestFocus()
    }

    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
        holder.bind(items[position], onEpisodeClick)
    }

    override fun getItemCount(): Int = items.size

    class EpisodeViewHolder(
        private val binding: CellEpisodeBinding,
        private val onFocused: (View) -> Unit,
        private val onVerticalKey: ((View, Int) -> Boolean)?
    ) : RecyclerView.ViewHolder(binding.root) {

        var nextFocusUpId: Int? = null

        fun bind(episode: EpisodeModel, onClick: (EpisodeModel) -> Unit) {
            applyDetailCardWidth(binding.clickView)
            binding.clickView.nextFocusUpId = nextFocusUpId ?: View.NO_ID
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
            ImageLoader.loadVideoCover(binding.imageView, episode.cover)
            binding.textPosition.text = episode.title.ifBlank {
                binding.root.context.getString(R.string.choose_episode)
            }

            val longTitle = episode.longTitle.trim()
            if (longTitle.isBlank()) {
                binding.textTitle.visibility = View.GONE
                binding.textTitle.text = ""
            } else {
                binding.textTitle.visibility = View.VISIBLE
                binding.textTitle.text = longTitle
            }

            val badgeText = episode.badgeInfo?.text?.takeIf { it.isNotBlank() } ?: episode.badge
            if (badgeText.isBlank()) {
                binding.textBadge.visibility = View.GONE
                binding.textBadge.text = ""
            } else {
                binding.textBadge.visibility = View.VISIBLE
                binding.textBadge.text = badgeText
                applyBadgeBackground(episode.badgeInfo?.bgColorNight ?: episode.badgeInfo?.bgColor)
            }

            binding.iconPlay.visibility = View.GONE
            binding.clickView.setOnClickListener { onClick(episode) }
            binding.clickView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    onFocused(view)
                }
            }
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

        private fun applyDetailCardWidth(view: View) {
            val layoutParams = view.layoutParams ?: return
            val targetWidth = ScreenUtils.getScreenWidth(view.context) / 5
            if (layoutParams.width != targetWidth) {
                layoutParams.width = targetWidth
                view.layoutParams = layoutParams
            }
        }
    }

    private class EpisodeDiffCallback(
        private val oldList: List<EpisodeModel>,
        private val newList: List<EpisodeModel>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size

        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldItem = oldList[oldItemPosition]
            val newItem = newList[newItemPosition]
            return when {
                oldItem.id > 0L && newItem.id > 0L -> oldItem.id == newItem.id
                oldItem.cid > 0L && newItem.cid > 0L -> oldItem.cid == newItem.cid
                oldItem.aid > 0L && newItem.aid > 0L -> oldItem.aid == newItem.aid
                else -> oldItem == newItem
            }
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition] == newList[newItemPosition]
        }
    }
}
