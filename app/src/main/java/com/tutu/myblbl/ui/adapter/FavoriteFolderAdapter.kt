package com.tutu.myblbl.ui.adapter

import android.view.View
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.widget.AppCompatTextView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellFavoriteFolderBinding
import com.tutu.myblbl.model.favorite.FavoriteFolderModel
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.core.ui.focus.VideoCardFocusHelper

class FavoriteFolderAdapter(
    private val onItemClick: ((position: Int, item: FavoriteFolderModel) -> Unit)? = null,
    private val onItemFocused: ((Int) -> Unit)? = null,
    private val onTopEdgeUp: (() -> Boolean)? = null
) : ListAdapter<FavoriteFolderModel, FavoriteFolderAdapter.FolderViewHolder>(DIFF_CALLBACK) {

    private var focusedPosition = RecyclerView.NO_POSITION
    private var focusedView: View? = null
    private var attachedRecyclerView: RecyclerView? = null

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<FavoriteFolderModel>() {
            override fun areItemsTheSame(oldItem: FavoriteFolderModel, newItem: FavoriteFolderModel): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: FavoriteFolderModel, newItem: FavoriteFolderModel): Boolean {
                return oldItem == newItem
            }
        }
    }

    init {
        setHasStableIds(true)
    }

    fun setData(newItems: List<FavoriteFolderModel>) {
        focusedPosition = focusedPosition
            .takeIf { it != RecyclerView.NO_POSITION && it < newItems.size && hasActiveFocus() }
            ?: RecyclerView.NO_POSITION
        submitList(newItems)
    }

    fun getFocusedPosition(): Int = focusedPosition

    fun getItemsSnapshot(): List<FavoriteFolderModel> = currentList.toList()

    fun updateCover(folderId: Long, coverUrl: String) {
        if (coverUrl.isBlank()) {
            return
        }
        val index = currentList.indexOfFirst { it.id == folderId }
        if (index == -1) {
            return
        }
        val item = currentList[index]
        if (item.displayImageUrl == coverUrl) {
            return
        }
        val newList = currentList.toMutableList()
        newList[index] = item.apply { imageUrl = coverUrl }
        submitList(newList)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val binding = CellFavoriteFolderBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return FolderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        holder.bind(getItem(position), position == focusedPosition && hasActiveFocus())
    }

    override fun getItemId(position: Int): Long = getItem(position).id

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        if (attachedRecyclerView === recyclerView) {
            attachedRecyclerView = null
        }
        focusedView = null
        focusedPosition = RecyclerView.NO_POSITION
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun onViewRecycled(holder: FolderViewHolder) {
        if (focusedView === holder.itemView) {
            focusedView = null
            focusedPosition = RecyclerView.NO_POSITION
        }
        super.onViewRecycled(holder)
    }

    inner class FolderViewHolder(
        private val binding: CellFavoriteFolderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick?.invoke(position, currentList[position])
                }
            }
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) {
                    return@setOnFocusChangeListener
                }
                if (hasFocus) {
                    val prev = focusedView
                    focusedView = binding.root
                    focusedPosition = position
                    setFocusedState(prev, false)
                    setFocusedState(binding.root, true)
                    onItemFocused?.invoke(position)
                } else {
                    if (focusedView === binding.root) {
                        focusedView = null
                        focusedPosition = RecyclerView.NO_POSITION
                    }
                    setFocusedState(binding.root, false)
                }
            }
            VideoCardFocusHelper.bindSidebarExit(
                binding.root,
                onTopEdgeUp = onTopEdgeUp
            )
        }

        fun bind(item: FavoriteFolderModel, isFocused: Boolean) {
            binding.root.isSelected = isFocused
            binding.tvTitle.isSelected = isFocused
            binding.tvTitle.text = item.title
            binding.tvCount.text = binding.root.context.getString(
                R.string.favorite_folder_count_format,
                item.mediaCount
            )
            ImageLoader.loadVideoCover(
                imageView = binding.imageCover,
                url = item.displayImageUrl
            )
        }
    }

    private fun setFocusedState(view: View?, focused: Boolean) {
        view ?: return
        val rv = attachedRecyclerView
        if (rv != null && rv.isComputingLayout) {
            rv.post { setFocusedState(view, focused) }
            return
        }
        view.isSelected = focused
        view.findViewById<AppCompatTextView>(com.tutu.myblbl.R.id.tvTitle)?.isSelected = focused
    }

    private fun hasActiveFocus(): Boolean = focusedView?.hasFocus() == true
}
