package com.tutu.myblbl.feature.live

import com.tutu.myblbl.core.ui.focus.TabContentFocusTarget

interface LiveTabPage : TabContentFocusTarget {
    fun scrollToTop()

    fun onTabSelected() {}

    fun onExplicitRefresh() {
        onReselected()
    }

    fun onReselected() {}
}
