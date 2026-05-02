package com.tutu.myblbl.feature.player.interaction

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.tutu.myblbl.model.interaction.InteractionEdgeQuestionChoiceModel
import com.tutu.myblbl.model.interaction.InteractionEdgeSkinModel

class InteractionChoiceButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val textView: TextView
    private val backgroundDrawable: GradientDrawable

    var choiceData: InteractionEdgeQuestionChoiceModel? = null
        private set

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true

        // Background: dark semi-transparent with rounded corners and white border
        backgroundDrawable = GradientDrawable().apply {
            setColor(0xCC000000.toInt())
            cornerRadius = 8f * resources.displayMetrics.density
            setStroke(STROKE_NORMAL, Color.WHITE)
        }
        background = backgroundDrawable

        // Text label
        textView = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        addView(textView, LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        ))
    }

    fun bind(choice: InteractionEdgeQuestionChoiceModel, skin: InteractionEdgeSkinModel? = null) {
        choiceData = choice
        textView.text = choice.option
        if (!skin?.textColor.isNullOrBlank()) {
            runCatching {
                textView.setTextColor(Color.parseColor(skin.textColor))
            }
        }
    }

    override fun onFocusChanged(gained: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gained, direction, previouslyFocusedRect)
        backgroundDrawable.setStroke(
            if (gained) STROKE_FOCUSED else STROKE_NORMAL,
            Color.WHITE
        )
    }

    companion object {
        private const val STROKE_NORMAL = 1  // px
        private const val STROKE_FOCUSED = 3 // px
    }
}
