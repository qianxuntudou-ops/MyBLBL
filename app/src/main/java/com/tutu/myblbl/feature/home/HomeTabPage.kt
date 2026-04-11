package com.tutu.myblbl.feature.home

interface HomeTabPage {
    fun scrollToTop()
    fun refresh()
    fun onTabSelected() {}
    fun focusPrimaryContent(): Boolean = false

    fun focusPrimaryContent(anchorView: android.view.View?, preferSpatialEntry: Boolean): Boolean {
        return focusPrimaryContent()
    }
}
