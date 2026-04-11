package com.tutu.myblbl.feature.player.view

import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatTextView
import com.tutu.myblbl.R
import com.tutu.myblbl.model.interaction.*
import com.tutu.myblbl.utils.AppLog
import java.util.regex.Pattern

class InteractionVideoHandleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs), View.OnClickListener {

    private var interactionModel: InteractionModel? = null
    private var currentCid: Long = 0
    private var lastSetupCid: Long = 0
    private var callback: InteractionCallback? = null
    private val variables = mutableListOf<InteractionVariableModel>()
    
    private val textView: AppCompatTextView by lazy {
        AppCompatTextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(20, 20, 20, 20)
            }
            setPadding(20, 20, 20, 20)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
    }

    interface InteractionCallback {
        fun onPauseVideo()
        fun onJumpToCid(cid: Long, edgeId: Long)
        fun onGetPlayerView(): View?
    }

    fun setCallback(callback: InteractionCallback?) {
        this.callback = callback
    }

    fun setModel(model: InteractionModel) {
        this.interactionModel = model
        model.hiddenVars?.let { vars ->
            if (variables.isEmpty()) {
                variables.addAll(vars)
            } else {
                vars.forEach { newVar ->
                    val exists = variables.any { it.idV2 == newVar.idV2 }
                    if (!exists) {
                        variables.add(newVar)
                    }
                }
            }
        }
        updateVariablesDisplay()
    }

    fun setCurrentCid(cid: Long) {
        this.currentCid = cid
    }

    fun onPositionUpdate(positionMs: Long) {
        val storyList = interactionModel?.storyList ?: return
        for (story in storyList) {
            if (story.cid == currentCid && positionMs >= story.startPos) {
                val edges = interactionModel?.edges ?: continue
                val questions = edges.questions ?: continue
                if (questions.isNotEmpty() && lastSetupCid != story.cid) {
                    setupView(edges)
                    lastSetupCid = story.cid
                }
            }
        }
    }

    fun onStoryChanged(cid: Long) {
        val storyList = interactionModel?.storyList ?: return
        for (story in storyList) {
            if (story.cid == this.currentCid && cid != story.cid) {
                val edges = interactionModel?.edges ?: continue
                val questions = edges.questions ?: continue
                if (questions.isNotEmpty() && lastSetupCid != story.cid) {
                    setupView(edges)
                    lastSetupCid = story.cid
                }
            }
        }
    }

    override fun onClick(v: View?) {
        val tag = v?.tag ?: return
        val choice = tag as? InteractionEdgeQuestionChoiceModel ?: return
        
        callback?.onJumpToCid(choice.cid, choice.id)
        removeAllViews()
        
        val nativeAction = choice.nativeAction
        if (nativeAction.contains(";")) {
            nativeAction.split(";").forEach { executeAction(it) }
        } else {
            executeAction(nativeAction)
        }
    }

    private fun setupView(edges: InteractionEdgeModel) {
        val questions = edges.questions ?: return
        removeAllViews()
        
        for (question in questions) {
            if (question.pauseVideo == 1) {
                callback?.onPauseVideo()
            }
            
            val choices = question.choices ?: continue
            
            if (question.type == 2) {
                for (choice in choices) {
                    if (TextUtils.isEmpty(choice.condition) || evaluateCondition(choice.condition)) {
                        val button = createChoiceButton(choice, edges)
                        addView(button)
                    }
                }
                if (childCount > 0) {
                    post { requestFocus() }
                }
            } else {
                val linearLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        gravity = Gravity.BOTTOM
                    }
                    setPadding(20, 10, 20, 30)
                }
                
                for (choice in choices) {
                    if (TextUtils.isEmpty(choice.condition) || evaluateCondition(choice.condition)) {
                        val button = AppCompatButton(context).apply {
                            text = choice.option
                            setBackgroundResource(R.drawable.button_common)
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                                gravity = Gravity.CENTER
                                setMargins(5, 0, 5, 0)
                            }
                            tag = choice
                            setOnClickListener(this@InteractionVideoHandleView)
                        }
                        linearLayout.addView(button)
                    }
                }
                
                if (linearLayout.childCount > 0) {
                    addView(linearLayout)
                    linearLayout.post { linearLayout.requestFocus() }
                }
            }
        }
        
        updateVariablesDisplay()
    }

    private fun createChoiceButton(choice: InteractionEdgeQuestionChoiceModel, edges: InteractionEdgeModel): AppCompatButton {
        return AppCompatButton(context).apply {
            text = choice.option
            setBackgroundResource(R.drawable.button_common)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                val x = choice.x
                val y = choice.y
                
                val dimension = edges.dimension
                if (dimension != null) {
                    val videoRatio = dimension.width.toDouble() / dimension.height.toDouble()
                    val screenWidth = context.resources.displayMetrics.widthPixels.toDouble()
                    val screenHeight = context.resources.displayMetrics.heightPixels.toDouble()
                    val screenRatio = screenWidth / screenHeight
                    
                    if (screenRatio >= videoRatio) {
                        val scale = screenHeight / dimension.height.toDouble()
                        val offsetX = ((screenWidth - dimension.width * scale) / 2).toInt()
                        setMargins(
                            offsetX + (x * scale).toInt(),
                            ((dimension.height - y) * scale).toInt(),
                            0,
                            0
                        )
                    } else {
                        val scale = screenWidth / dimension.width.toDouble()
                        val offsetY = ((screenHeight - dimension.height * scale) / 2).toInt()
                        setMargins((x * scale).toInt(), offsetY + ((dimension.height - y) * scale).toInt(), 0, 0)
                    }
                } else {
                    setMargins(x, y, 0, 0)
                }
            }
            tag = choice
            setOnClickListener(this@InteractionVideoHandleView)
        }
    }

    private fun evaluateCondition(condition: String): Boolean {
        return when {
            condition.contains(">=") -> {
                val parts = condition.split(">=")
                if (parts.size > 1) {
                    val variable = findVariable(parts[0])
                    if (variable != null && isNumeric(parts[1])) {
                        return variable.value >= parts[1].toFloat()
                    }
                }
                true
            }
            condition.contains("<=") -> {
                val parts = condition.split("<=")
                if (parts.size > 1) {
                    val variable = findVariable(parts[0])
                    if (variable != null && isNumeric(parts[1])) {
                        return variable.value <= parts[1].toFloat()
                    }
                }
                true
            }
            condition.contains("==") -> {
                val parts = condition.split("==")
                if (parts.size > 1) {
                    val variable = findVariable(parts[0])
                    if (variable != null && isNumeric(parts[1])) {
                        return variable.value == parts[1].toFloat()
                    }
                }
                true
            }
            condition.contains("<") -> {
                val parts = condition.split("<")
                if (parts.size > 1) {
                    val variable = findVariable(parts[0])
                    if (variable != null && isNumeric(parts[1])) {
                        return variable.value < parts[1].toFloat()
                    }
                }
                true
            }
            condition.contains(">") -> {
                val parts = condition.split(">")
                if (parts.size > 1) {
                    val variable = findVariable(parts[0])
                    if (variable != null && isNumeric(parts[1])) {
                        return variable.value > parts[1].toFloat()
                    }
                }
                true
            }
            else -> true
        }
    }

    private fun executeAction(action: String) {
        if (TextUtils.isEmpty(action)) return
        
        try {
            val equalIndex = action.indexOf("=")
            if (equalIndex < 0) return
            
            val variableName = action.substring(0, equalIndex)
            val valueExpr = action.substring(equalIndex + 1)
            
            val variable = findVariable(variableName) ?: return
            
            val newValue = when {
                valueExpr.indexOf('+') > 0 -> {
                    val parts = valueExpr.split("+", limit = 2)
                    if (parts.size > 1 && isNumeric(parts[1])) {
                        variable.value + parts[1].toFloat()
                    } else 0f
                }
                valueExpr.indexOf('-') > 0 -> {
                    val parts = valueExpr.split("-", limit = 2)
                    if (parts.size > 1 && isNumeric(parts[1])) {
                        variable.value - parts[1].toFloat()
                    } else 0f
                }
                valueExpr.indexOf('*') > 0 -> {
                    val parts = valueExpr.split("*", limit = 2)
                    if (parts.size > 1 && isNumeric(parts[1])) {
                        variable.value * parts[1].toFloat()
                    } else 0f
                }
                valueExpr.indexOf('/') > 0 -> {
                    val parts = valueExpr.split("/", limit = 2)
                    if (parts.size > 1 && isNumeric(parts[1])) {
                        variable.value / parts[1].toFloat()
                    } else 0f
                }
                isNumeric(valueExpr) -> valueExpr.toFloat()
                else -> return
            }
            
            variable.value = newValue
        } catch (e: Exception) {
            AppLog.e("InteractionVideoHandleView", "executeAction failed: action=$action", e)
        }
    }

    private fun findVariable(id: String): InteractionVariableModel? {
        return variables.find { it.idV2 == id }
    }

    private fun isNumeric(str: String): Boolean {
        return Pattern.compile("^([-+])?\\d+(\\.\\d+)?$").matcher(str).matches()
    }

    private fun updateVariablesDisplay() {
        if (variables.isEmpty()) return
        
        val showVariables = variables.filter { it.isShow == 1 }
        if (showVariables.isEmpty()) return
        
        val displayText = buildString {
            showVariables.forEachIndexed { index, v ->
                append("${v.name}：${v.value}")
                if (index < showVariables.size - 1) {
                    append("\n")
                }
            }
        }
        
        if (!textView.isInLayout && !TextUtils.isEmpty(displayText)) {
            textView.text = displayText
            textView.setBackgroundResource(R.drawable.transparent_black_bg)
            addView(textView)
        }
    }
}
