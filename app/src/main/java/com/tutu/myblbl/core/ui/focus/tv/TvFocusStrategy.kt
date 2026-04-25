package com.tutu.myblbl.core.ui.focus.tv

interface TvFocusStrategy {
    fun anchorFor(
        position: Int,
        stableKey: String?,
        offsetTop: Int,
        source: TvFocusAnchor.Source = TvFocusAnchor.Source.FOCUS
    ): TvFocusAnchor

    fun nextPosition(anchor: TvFocusAnchor, direction: Int, itemCount: Int): Int?
}
