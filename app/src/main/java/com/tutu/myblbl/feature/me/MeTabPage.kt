package com.tutu.myblbl.feature.me

interface MeTabPage {
    fun scrollToTop()
    fun refresh()
    fun focusPrimaryContent(): Boolean = false
    fun focusPrimaryContent(anchorView: android.view.View?, preferSpatialEntry: Boolean): Boolean {
        return focusPrimaryContent()
    }
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
