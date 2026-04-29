package com.tutu.myblbl.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

class NonFocusableScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    override fun addFocusables(views: ArrayList<android.view.View>, direction: Int, focusableMode: Int) {}
}
