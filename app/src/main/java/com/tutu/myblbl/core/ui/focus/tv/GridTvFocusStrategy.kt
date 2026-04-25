package com.tutu.myblbl.core.ui.focus.tv

import android.view.View

class GridTvFocusStrategy(
    private val spanCountProvider: () -> Int
) : TvFocusStrategy {

    private val spanCount: Int
        get() = spanCountProvider().coerceAtLeast(1)

    override fun anchorFor(
        position: Int,
        stableKey: String?,
        offsetTop: Int,
        source: TvFocusAnchor.Source
    ): TvFocusAnchor {
        val span = spanCount
        return TvFocusAnchor(
            stableKey = stableKey,
            adapterPosition = position,
            row = position / span,
            column = position % span,
            offsetTop = offsetTop,
            source = source
        )
    }

    override fun nextPosition(anchor: TvFocusAnchor, direction: Int, itemCount: Int): Int? {
        if (itemCount <= 0 || anchor.adapterPosition !in 0 until itemCount) {
            return null
        }
        val span = spanCount
        val current = anchor.adapterPosition
        return when (direction) {
            View.FOCUS_UP -> (current - span).takeIf { it >= 0 }
            View.FOCUS_DOWN -> (current + span).takeIf { it < itemCount }
            View.FOCUS_LEFT -> (current - 1).takeIf { it >= 0 && it / span == anchor.row }
            View.FOCUS_RIGHT -> (current + 1).takeIf { it < itemCount && it / span == anchor.row }
            else -> null
        }
    }
}
