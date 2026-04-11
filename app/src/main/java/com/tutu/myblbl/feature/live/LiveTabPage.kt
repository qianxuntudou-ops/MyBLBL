package com.tutu.myblbl.feature.live

interface LiveTabPage {
    fun scrollToTop()
    fun focusPrimaryContent(): Boolean = false

    fun focusPrimaryContent(anchorView: android.view.View?, preferSpatialEntry: Boolean): Boolean {
        return focusPrimaryContent()
    }

    fun onTabSelected() {}

    fun onExplicitRefresh() {
        onReselected()
    }

    fun onReselected() {}
}
