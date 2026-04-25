package com.tutu.myblbl.core.ui.focus.tv

interface TvFocusableAdapter {
    fun focusableItemCount(): Int
    fun stableKeyAt(position: Int): String?
    fun findPositionByStableKey(key: String): Int
    fun isFocusablePosition(position: Int): Boolean = position in 0 until focusableItemCount()
}
