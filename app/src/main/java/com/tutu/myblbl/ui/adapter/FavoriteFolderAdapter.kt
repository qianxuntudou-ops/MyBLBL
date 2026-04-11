package com.tutu.myblbl.ui.adapter

import android.view.View
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
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
) : RecyclerView.Adapter<FavoriteFolderAdapter.FolderViewHolder>() {

    private val items = mutableListOf<FavoriteFolderModel>()
    private var focusedPosition = RecyclerView.NO_POSITION
    private var focusedView: View? = null
    private var attachedRecyclerView: RecyclerView? = null

    init {
        setHasStableIds(true)
    }

    fun setData(newItems: List<FavoriteFolderModel>) {
        val oldList = items.toList()
        val diffResult = DiffUtil.calculateDiff(FavoriteFolderDiffCallback(oldList, newItems))
        items.clear()
        items.addAll(newItems)
        focusedPosition = focusedPosition
            .takeIf { it != RecyclerView.NO_POSITION && it < items.size && hasActiveFocus() }
            ?: RecyclerView.NO_POSITION
        diffResult.dispatchUpdatesTo(this)
    }

    fun getFocusedPosition(): Int = focusedPosition

    fun getItemsSnapshot(): List<FavoriteFolderModel> = items.toList()

    fun updateCover(folderId: Long, coverUrl: String) {
        if (coverUrl.isBlank()) {
            return
        }
        val index = items.indexOfFirst { it.id == folderId }
        if (index == -1) {
            return
        }
        val item = items[index]
        if (item.displayImageUrl == coverUrl) {
            return
        }
        item.imageUrl = coverUrl
        notifyItemChangedSafely(index)
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
        holder.bind(items[position], position == focusedPosition && hasActiveFocus())
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].id

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
                    onItemClick?.invoke(position, items[position])
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

    private fun notifyItemChangedSafely(position: Int) {
        val recyclerView = attachedRecyclerView
        if (recyclerView != null && recyclerView.isComputingLayout) {
            recyclerView.post { notifyItemChanged(position) }
        } else {
            notifyItemChanged(position)
        }
    }

    private fun hasActiveFocus(): Boolean = focusedView?.hasFocus() == true

    private class FavoriteFolderDiffCallback(
        private val oldList: List<FavoriteFolderModel>,
        private val newList: List<FavoriteFolderModel>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size

        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].id == newList[newItemPosition].id
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition] == newList[newItemPosition]
        }
    }
}
