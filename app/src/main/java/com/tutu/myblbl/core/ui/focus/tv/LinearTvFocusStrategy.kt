package com.tutu.myblbl.core.ui.focus.tv

import android.view.View

class LinearTvFocusStrategy : TvFocusStrategy {
    override fun anchorFor(
        position: Int,
        stableKey: String?,
        offsetTop: Int,
        source: TvFocusAnchor.Source
    ): TvFocusAnchor {
        return TvFocusAnchor(
            stableKey = stableKey,
            adapterPosition = position,
            row = position,
            column = 0,
            offsetTop = offsetTop,
            source = source
        )
    }

    override fun nextPosition(anchor: TvFocusAnchor, direction: Int, itemCount: Int): Int? {
        if (itemCount <= 0 || anchor.adapterPosition !in 0 until itemCount) {
            return null
        }
        return when (direction) {
            View.FOCUS_UP -> (anchor.adapterPosition - 1).takeIf { it >= 0 }
            View.FOCUS_DOWN -> (anchor.adapterPosition + 1).takeIf { it < itemCount }
            else -> null
        }
    }
}
