package com.tutu.myblbl.feature.me

import com.tutu.myblbl.core.ui.focus.TabContentFocusTarget

interface MeTabPage : TabContentFocusTarget {
    fun scrollToTop()
    fun refresh()
    fun onTabSelected() {}
    fun onTabReselected() {}

    enum class HostEvent {
        SELECT_TAB4,
        CLICK_TAB4,
        BACK_PRESSED,
        KEY_MENU_PRESS
    }

    fun onHostEvent(event: HostEvent): Boolean = false
}
