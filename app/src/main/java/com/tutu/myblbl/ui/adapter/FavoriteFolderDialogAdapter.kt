package com.tutu.myblbl.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellFavoriteChoiceBinding
import com.tutu.myblbl.model.favorite.FavoriteFolderModel

class FavoriteFolderDialogAdapter(
    private val folders: List<FavoriteFolderModel>,
    private val onItemSelected: (Int) -> Unit
) : RecyclerView.Adapter<FavoriteFolderDialogAdapter.ViewHolder>() {

    private var focusedPosition = RecyclerView.NO_POSITION

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = CellFavoriteChoiceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(folders[position])
    }

    override fun getItemCount(): Int = folders.size

    fun requestInitialFocus(recyclerView: RecyclerView?) {
        if (folders.isEmpty()) return
        recyclerView?.post {
            recyclerView.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    inner class ViewHolder(
        private val binding: CellFavoriteChoiceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                onItemSelected(position)
            }
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnFocusChangeListener
                if (hasFocus) {
                    focusedPosition = position
                } else if (focusedPosition == position) {
                    focusedPosition = RecyclerView.NO_POSITION
                }
                applyFocusState(hasFocus)
            }
        }

        fun bind(folder: FavoriteFolderModel) {
            binding.tvTitle.text = folder.title
            binding.tvCount.text = binding.root.context.getString(
                R.string.favorite_folder_media_count_format,
                folder.mediaCount
            )
            applyFocusState(binding.root.hasFocus())
        }

        private fun applyFocusState(isFocused: Boolean) {
            binding.root.animate()
                .scaleX(if (isFocused) 1.02f else 1f)
                .scaleY(if (isFocused) 1.02f else 1f)
                .translationX(
                    if (isFocused) binding.root.resources.displayMetrics.density * 6f else 0f
                )
                .setDuration(120L)
                .start()
        }
    }
}
