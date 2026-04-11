package com.tutu.myblbl.feature.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.util.UnstableApi
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellEpisodeListBinding

@UnstableApi
class PlayerEpisodePanelAdapter(
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<PlayerEpisodePanelAdapter.EpisodeViewHolder>() {

    private val items = mutableListOf<VideoPlayerViewModel.PlayableEpisode>()
    private var selectedIndex: Int = -1

    fun submitList(newItems: List<VideoPlayerViewModel.PlayableEpisode>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun setSelectedIndex(index: Int) {
        val oldIndex = selectedIndex
        selectedIndex = index
        if (oldIndex in items.indices) {
            notifyItemChanged(oldIndex)
        }
        if (selectedIndex in items.indices) {
            notifyItemChanged(selectedIndex)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
        val binding = CellEpisodeListBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return EpisodeViewHolder(binding)
    }

    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
        holder.bind(
            item = items[position],
            isSelected = position == selectedIndex,
            onClick = { onClick(position) }
        )
    }

    override fun getItemCount(): Int = items.size

    class EpisodeViewHolder(
        private val binding: CellEpisodeListBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            item: VideoPlayerViewModel.PlayableEpisode,
            isSelected: Boolean,
            onClick: () -> Unit
        ) {
            binding.textTitle.text = item.panelTitle.ifBlank {
                item.title.ifBlank {
                    item.subtitle.ifBlank {
                        binding.root.context.getString(R.string.choose_episode)
                    }
                }
            }
            binding.iconPlaying.visibility = if (isSelected) View.VISIBLE else View.GONE
            binding.clickView.isSelected = isSelected
            binding.root.isSelected = isSelected
            binding.textTitle.isSelected = isSelected
            binding.clickView.setOnClickListener { onClick() }
            binding.clickView.isFocusable = true
            binding.clickView.isClickable = true
        }
    }
}
