package com.tutu.myblbl.core.ui.focus.tv

data class TvFocusAnchor(
    val stableKey: String?,
    val adapterPosition: Int,
    val row: Int,
    val column: Int,
    val offsetTop: Int = 0,
    val source: Source = Source.FOCUS
) {
    enum class Source {
        FOCUS,
        VISIBLE_ITEM,
        RETURN_RESTORE,
        PENDING_LOAD_MORE
    }
}
