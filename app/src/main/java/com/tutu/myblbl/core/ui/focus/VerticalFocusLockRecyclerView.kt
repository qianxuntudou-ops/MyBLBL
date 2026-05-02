package com.tutu.myblbl.core.ui.focus

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class VerticalFocusLockRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    override fun setLayoutManager(layout: LayoutManager?) {
        super.setLayoutManager(
            if (layout is LinearLayoutManager) LockFocusLinearLayoutManager(context) else layout
        )
    }

    private inner class LockFocusLinearLayoutManager(context: Context) : LinearLayoutManager(context) {
        override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
            if (direction == View.FOCUS_DOWN) {
                val pos = getPosition(focused)
                val total = itemCount
                val lastVisible = findLastCompletelyVisibleItemPosition()
                if (pos >= lastVisible && pos < total - 1) {
                    smoothScrollToPosition(pos + 1)
                    return focused
                }
                if (pos >= total - 1) {
                    return focused
                }
            }
            return super.onInterceptFocusSearch(focused, direction)
        }
    }
}
