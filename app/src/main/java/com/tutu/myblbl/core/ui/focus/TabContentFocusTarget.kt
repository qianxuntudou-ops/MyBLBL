package com.tutu.myblbl.core.ui.focus

import android.view.View

interface TabContentFocusTarget {
    fun focusPrimaryContent(): Boolean = false

    fun focusPrimaryContent(anchorView: View?, preferSpatialEntry: Boolean): Boolean {
        return focusPrimaryContent()
    }
}
