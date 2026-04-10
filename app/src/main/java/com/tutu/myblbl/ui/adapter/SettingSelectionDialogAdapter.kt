package com.tutu.myblbl.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellSettingChoiceBinding

class SettingSelectionDialogAdapter(
    private val options: List<String>,
    selectedIndex: Int,
    private val onItemSelected: (Int) -> Unit
) : RecyclerView.Adapter<SettingSelectionDialogAdapter.SettingChoiceViewHolder>() {

    private var selectedIndex = selectedIndex.coerceIn(0, options.lastIndex.coerceAtLeast(0))
    private var focusedPosition = RecyclerView.NO_POSITION

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SettingChoiceViewHolder {
        val binding = CellSettingChoiceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SettingChoiceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SettingChoiceViewHolder, position: Int) {
        holder.bind(option = options[position], isSelected = position == selectedIndex)
    }

    override fun getItemCount(): Int = options.size

    fun requestInitialFocus(recyclerView: RecyclerView?) {
        val targetPosition = selectedIndex.coerceIn(0, options.lastIndex.coerceAtLeast(0))
        recyclerView?.scrollToPosition(targetPosition)
        recyclerView?.post {
            recyclerView.findViewHolderForAdapterPosition(targetPosition)?.itemView?.requestFocus()
        }
    }

    inner class SettingChoiceViewHolder(
        private val binding: CellSettingChoiceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) {
                    return@setOnClickListener
                }
                selectedIndex = position
                onItemSelected(position)
            }
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) {
                    return@setOnFocusChangeListener
                }
                if (hasFocus) {
                    val oldFocusedPosition = focusedPosition
                    focusedPosition = position
                    if (oldFocusedPosition != RecyclerView.NO_POSITION && oldFocusedPosition != position) {
                        notifyItemChanged(oldFocusedPosition)
                    }
                    notifyItemChanged(position)
                } else if (focusedPosition == position) {
                    focusedPosition = RecyclerView.NO_POSITION
                    notifyItemChanged(position)
                }
            }
        }

        fun bind(option: String, isSelected: Boolean) {
            binding.tvTitle.text = option
            binding.tvCurrent.visibility = if (isSelected) View.VISIBLE else View.GONE
            binding.iconCheck.visibility = if (isSelected) View.VISIBLE else View.GONE

            val isFocused = bindingAdapterPosition == focusedPosition
            binding.root.isSelected = isSelected
            binding.root.animate()
                .scaleX(if (isFocused) 1.02f else 1f)
                .scaleY(if (isFocused) 1.02f else 1f)
                .translationX(
                    if (isFocused) binding.root.resources.displayMetrics.density * 6f else 0f
                )
                .setDuration(120L)
                .start()

            val titleColor = if (isSelected) {
                ContextCompat.getColor(binding.root.context, R.color.colorAccent)
            } else {
                ContextCompat.getColor(binding.root.context, R.color.textColor)
            }
            binding.tvTitle.setTextColor(titleColor)
        }
    }
}
