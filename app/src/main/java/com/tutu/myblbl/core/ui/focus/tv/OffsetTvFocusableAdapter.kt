package com.tutu.myblbl.core.ui.focus.tv

import androidx.recyclerview.widget.RecyclerView

class OffsetTvFocusableAdapter(
    private val delegate: TvFocusableAdapter,
    private val offsetProvider: () -> Int
) : TvFocusableAdapter {

    override fun focusableItemCount(): Int = offsetProvider() + delegate.focusableItemCount()

    override fun stableKeyAt(position: Int): String? {
        val relative = position - offsetProvider()
        return delegate.stableKeyAt(relative)
    }

    override fun findPositionByStableKey(key: String): Int {
        val relative = delegate.findPositionByStableKey(key)
        return if (relative == RecyclerView.NO_POSITION) {
            RecyclerView.NO_POSITION
        } else {
            offsetProvider() + relative
        }
    }

    override fun isFocusablePosition(position: Int): Boolean {
        val relative = position - offsetProvider()
        return delegate.isFocusablePosition(relative)
    }
}
