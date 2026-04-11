package com.tutu.myblbl.feature.home

import com.tutu.myblbl.core.ui.focus.TabContentFocusTarget

interface HomeTabPage : TabContentFocusTarget {
    fun scrollToTop()
    fun refresh()
    fun onTabSelected() {}
}
