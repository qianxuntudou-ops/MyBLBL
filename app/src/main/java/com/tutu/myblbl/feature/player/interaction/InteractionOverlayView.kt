package com.tutu.myblbl.feature.player.interaction

import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.os.CountDownTimer
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.model.interaction.InteractionEdgeDimensionModel
import com.tutu.myblbl.model.interaction.InteractionEdgeQuestionChoiceModel
import com.tutu.myblbl.model.interaction.InteractionEdgeQuestionModel
import com.tutu.myblbl.model.interaction.InteractionEdgeSkinModel
import com.tutu.myblbl.model.interaction.InteractionModel
import com.tutu.myblbl.model.interaction.InteractionVariableModel

private const val TAG = "InteractionOverlay"

class InteractionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
    }

    interface Callback {
        fun onPauseVideo()
        fun onResumeVideo()
        fun onHidePlayerUI()
        fun onShowPlayerUI()
        fun onJumpToChoice(targetEdgeId: Long, targetCid: Long)
        fun onGoBackToNode(edgeId: Long, cid: Long)
        fun onGetVideoSurfaceRect(): Rect?
    }

    private var callback: Callback? = null
    private var engine: InteractionEngine? = null
    private var countDownTimer: CountDownTimer? = null
    private var backButton: TextView? = null
    private var variablesTextView: TextView? = null
    private var bottomBarContainer: LinearLayout? = null

    private var pendingQuestions: List<PendingQuestion> = emptyList()
    private var currentSkin: InteractionEdgeSkinModel? = null
    private var currentDimension: InteractionEdgeDimensionModel? = null
    private var currentHiddenVars: List<InteractionVariableModel>? = null
    private var hasActiveChoices = false
    private var currentEdgeStartMs: Long = 0L

    private data class PendingQuestion(
        val question: InteractionEdgeQuestionModel,
        val choices: List<InteractionEdgeQuestionChoiceModel>,
        val absoluteTriggerMs: Long,
        val triggered: Boolean = false
    )

    // ── Public API ──────────────────────────────────────────────────────────

    fun setCallback(cb: Callback) { callback = cb }
    fun setEngine(eng: InteractionEngine) { engine = eng }

    fun onNodeLoaded(model: InteractionModel) {
        cancelCountdown()
        removeAllChoiceViews()
        hasActiveChoices = false
        pendingQuestions = emptyList()

        val eng = engine ?: return
        val edges = model.edges ?: return
        val questions = edges.questions ?: return

        currentSkin = edges.skin
        currentDimension = edges.dimension
        currentHiddenVars = model.hiddenVars

        // 从 story_list 获取当前 edge 的起始位置
        currentEdgeStartMs = model.storyList
            ?.firstOrNull { it.isCurrent == 1 && it.startPos >= 0L }
            ?.startPos ?: 0L

        // 为每个 question 过滤出可见选项，并计算绝对触发时间
        val pq = mutableListOf<PendingQuestion>()
        for (q in questions) {
            val allChoices = q.choices ?: continue
            val visible = allChoices.filter { c ->
                c.isHidden != 1 && (c.condition.isBlank() || eng.evaluateCondition(c.condition))
            }
            if (visible.isEmpty()) continue
            val absoluteMs = (currentEdgeStartMs + q.startTimeR).coerceAtLeast(0L)
            pq += PendingQuestion(question = q, choices = visible, absoluteTriggerMs = absoluteMs)
        }

        if (pq.isEmpty()) return

        pendingQuestions = pq.sortedBy { it.absoluteTriggerMs }
        AppLog.d(TAG, "onNodeLoaded: ${pendingQuestions.size} questions pending, " +
                "edgeStartMs=$currentEdgeStartMs, triggerTimes=${pendingQuestions.map { it.absoluteTriggerMs }}")

        // 立即检查是否有 start_time_r <= 0 的问题需要立刻弹出
        checkPendingQuestions(0L)

        // 更新回退按钮和变量
        updateBackButton()
        updateVariablesDisplay()
    }

    /**
     * 由 Activity 在播放进度更新时调用，检查是否该弹出下一个问题。
     */
    fun onPositionUpdate(positionMs: Long) {
        checkPendingQuestions(positionMs)
    }

    fun triggerGoBack() {
        val eng = engine ?: return
        val entry = eng.goBack() ?: return
        callback?.onGoBackToNode(entry.edgeId, entry.cid)
    }

    fun hideAll() {
        cancelCountdown()
        removeAllChoiceViews()
        pendingQuestions = emptyList()
        hasActiveChoices = false
        backButton?.visibility = View.GONE
        hideVariables()
        callback?.onShowPlayerUI()
    }

    fun updateVariablesDisplay(hiddenVars: List<InteractionVariableModel>?) {
        currentHiddenVars = hiddenVars
        updateVariablesDisplay()
    }

    // ── 内部：按时间触发问题 ─────────────────────────────────────────────────

    private fun checkPendingQuestions(positionMs: Long) {
        if (hasActiveChoices) return
        var changed = false
        val updated = pendingQuestions.map { pq ->
            if (!pq.triggered && positionMs >= pq.absoluteTriggerMs) {
                AppLog.d(TAG, "triggering question: triggerMs=${pq.absoluteTriggerMs}, edgeStartMs=$currentEdgeStartMs, positionMs=$positionMs")
                triggerQuestion(pq)
                changed = true
                pq.copy(triggered = true)
            } else {
                pq
            }
        }
        if (changed) {
            pendingQuestions = updated
        }
    }

    private fun triggerQuestion(pq: PendingQuestion) {
        val q = pq.question
        val choices = pq.choices

        hasActiveChoices = true

        // 选项出现时暂停视频+弹幕+隐藏播放器控件
        callback?.onPauseVideo()
        callback?.onHidePlayerUI()

        when (q.type) {
            2 -> renderCoordinateChoices(choices, currentSkin, currentDimension)
            else -> renderBottomChoices(choices, currentSkin)
        }

        if (q.duration > 0) {
            startCountdown(q.duration, choices)
        }

        // 延迟二次请求焦点，确保隐藏播放器控件后焦点落到选项按钮
        post {
            findFirstChoiceButton()?.requestFocus()
        }
    }

    private fun findFirstChoiceButton(): InteractionChoiceButton? {
        // 优先从 bottomBarContainer 中找
        bottomBarContainer?.let { container ->
            return (0 until container.childCount)
                .map { container.getChildAt(it) }
                .filterIsInstance<InteractionChoiceButton>()
                .firstOrNull()
        }
        // 否则从自身子 View 中找
        return (0 until childCount)
            .map { getChildAt(it) }
            .filterIsInstance<InteractionChoiceButton>()
            .firstOrNull()
    }

    // ── 渲染选项 ─────────────────────────────────────────────────────────────

    private fun renderBottomChoices(choices: List<InteractionEdgeQuestionChoiceModel>, skin: InteractionEdgeSkinModel?) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xCC000000.toInt()) }
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val buttonHeight = dp(44)
        val params = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            bottomMargin = dp(16)
            leftMargin = dp(24)
            rightMargin = dp(24)
        }
        choices.forEachIndexed { index, choice ->
            container.addView(InteractionChoiceButton(context).apply {
                bind(choice, skin)
                layoutParams = LinearLayout.LayoutParams(0, buttonHeight, 1f).apply {
                    if (index > 0) marginStart = dp(8)
                }
                setOnClickListener { onChoiceClicked(this) }
            })
        }
        bottomBarContainer = container
        addView(container, params)
    }

    private fun renderCoordinateChoices(choices: List<InteractionEdgeQuestionChoiceModel>, skin: InteractionEdgeSkinModel?, dimension: InteractionEdgeDimensionModel?) {
        val videoRect = callback?.onGetVideoSurfaceRect() ?: return
        val videoW = dimension?.width ?: videoRect.width()
        val videoH = dimension?.height ?: videoRect.height()
        if (videoW <= 0 || videoH <= 0) return

        choices.forEach { choice ->
            val mappedX = videoRect.left + (choice.x * videoRect.width() / videoW)
            val mappedY = videoRect.top + videoRect.height() - (choice.y * videoRect.height() / videoH)
            addView(InteractionChoiceButton(context).apply {
                bind(choice, skin)
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = mappedX
                    topMargin = mappedY
                }
                setOnClickListener { onChoiceClicked(this) }
            })
        }
    }

    // ── 倒计时 ───────────────────────────────────────────────────────────────

    private fun startCountdown(durationSec: Long, choices: List<InteractionEdgeQuestionChoiceModel>) {
        val durationMs = durationSec * 1000L
        val countdownView = TextView(context).apply {
            textSize = 18f
            setTextColor(0xFFFF4444.toInt())
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x80000000.toInt())
                cornerRadius = dp(20).toFloat()
            }
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(8)
                rightMargin = dp(8)
            }
            text = "${durationSec}s"
        }
        addView(countdownView)

        countDownTimer = object : CountDownTimer(durationMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                countdownView.text = "${millisUntilFinished / 1000 + 1}s"
            }
            override fun onFinish() {
                removeView(countdownView)
                val eng = engine ?: return
                val defaultChoice = eng.getDefaultChoice(choices)
                if (defaultChoice != null) {
                    onChoiceSelected(defaultChoice)
                }
            }
        }.start()
    }

    private fun cancelCountdown() {
        countDownTimer?.cancel()
        countDownTimer = null
    }

    // ── 选项点击 ─────────────────────────────────────────────────────────────

    private fun onChoiceClicked(button: InteractionChoiceButton) {
        val choice = button.choiceData ?: return
        onChoiceSelected(choice)
    }

    private fun onChoiceSelected(choice: InteractionEdgeQuestionChoiceModel) {
        val eng = engine ?: return
        val result = eng.selectChoice(choice)
        cancelCountdown()
        removeAllChoiceViews()
        hasActiveChoices = false
        callback?.onJumpToChoice(result.targetEdgeId, result.targetCid)
    }

    // ── 回退按钮 ─────────────────────────────────────────────────────────────

    private fun updateBackButton() {
        val eng = engine ?: return
        if (!eng.canGoBack()) {
            backButton?.visibility = View.GONE
            return
        }
        if (backButton == null) {
            backButton = TextView(context).apply {
                text = "←"
                textSize = 22f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x80000000.toInt())
                    cornerRadius = dp(20).toFloat()
                }
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                    topMargin = dp(8)
                    leftMargin = dp(8)
                }
                setOnClickListener { triggerGoBack() }
                setOnFocusChangeListener { v, hasFocus ->
                    (v.background as? android.graphics.drawable.GradientDrawable)?.setStroke(
                        if (hasFocus) dp(3) else 0,
                        if (hasFocus) 0xFFFFFFFF.toInt() else 0x00000000.toInt()
                    )
                }
            }
            addView(backButton)
        }
        backButton?.visibility = View.VISIBLE
    }

    // ── 变量展示 ─────────────────────────────────────────────────────────────

    private fun updateVariablesDisplay() {
        val hiddenVars = currentHiddenVars
        if (hiddenVars.isNullOrEmpty()) { hideVariables(); return }
        val eng = engine ?: return
        val visibleVars = hiddenVars.filter { it.isShow == 1 }
        if (visibleVars.isEmpty()) { hideVariables(); return }

        val text = visibleVars.joinToString("\n") { v ->
            "${v.name}: ${eng.state.variables[v.idV2] ?: v.value}"
        }

        if (variablesTextView == null) {
            variablesTextView = TextView(context).apply {
                textSize = 12f
                setTextColor(0xFFFFFFFF.toInt())
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x80000000.toInt())
                    cornerRadius = dp(4).toFloat()
                }
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                    topMargin = dp(44)
                    leftMargin = dp(8)
                }
            }
            addView(variablesTextView)
        }
        variablesTextView?.text = text
        variablesTextView?.visibility = View.VISIBLE
    }

    private fun hideVariables() { variablesTextView?.visibility = View.GONE }

    // ── 工具 ─────────────────────────────────────────────────────────────────

    private fun removeAllChoiceViews() {
        bottomBarContainer?.let { removeView(it); bottomBarContainer = null }
        val toRemove = (0 until childCount).mapNotNull { i ->
            getChildAt(i).takeIf { it is InteractionChoiceButton }
        }
        toRemove.forEach { removeView(it) }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()
}
