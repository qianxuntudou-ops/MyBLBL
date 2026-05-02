package com.tutu.myblbl.feature.player.interaction

import com.tutu.myblbl.model.interaction.InteractionEdgeQuestionChoiceModel
import com.tutu.myblbl.model.interaction.InteractionEdgeQuestionModel
import com.tutu.myblbl.model.interaction.InteractionModel
import com.tutu.myblbl.model.interaction.InteractionVariableModel

/**
 * Interactive video state machine engine.
 *
 * Manages navigation through an interaction graph, variable tracking,
 * choice filtering by condition, and action execution.
 */
class InteractionEngine {

    // ── State ──────────────────────────────────────────────────────────────

    data class State(
        val graphVersion: Long = 0L,
        val currentEdgeId: Long = 0L,
        val currentCid: Long = 0L,
        val title: String = "",
        val history: List<HistoryEntry> = emptyList(),
        val variables: Map<String, Float> = emptyMap(),
        val isLeaf: Boolean = false
    )

    data class HistoryEntry(
        val edgeId: Long,
        val cid: Long,
        val title: String
    )

    data class ChoiceResult(
        val targetEdgeId: Long,
        val targetCid: Long
    )

    // ── Internal ───────────────────────────────────────────────────────────

    private var _state = State()
    val state: State get() = _state

    private val conditionRegex = Regex("(>=|<=|==|!=|>|<)")

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Initialize the engine with the first node of the interaction graph.
     */
    fun initialize(graphVersion: Long, firstNodeData: InteractionModel) {
        val edgeId = firstNodeData.edgeId.toLongOrNull() ?: 0L
        val cid = resolveFirstCid(firstNodeData)
        val variables = buildVariableMap(firstNodeData.hiddenVars)
        val isLeaf = firstNodeData.edges?.questions.isNullOrEmpty()

        _state = State(
            graphVersion = graphVersion,
            currentEdgeId = edgeId,
            currentCid = cid,
            title = firstNodeData.title,
            history = emptyList(),
            variables = variables,
            isLeaf = isLeaf
        )
    }

    /**
     * Process a newly loaded node — merge variables and update leaf status.
     */
    fun processNode(model: InteractionModel) {
        val edgeId = model.edgeId.toLongOrNull() ?: 0L
        val cid = resolveFirstCid(model)
        val mergedVariables = buildVariableMap(model.hiddenVars).let { incoming ->
            if (incoming.isEmpty()) _state.variables
            else _state.variables + incoming  // incoming values overwrite existing
        }
        val isLeaf = model.edges?.questions.isNullOrEmpty()

        _state = _state.copy(
            currentEdgeId = edgeId,
            currentCid = cid,
            title = model.title,
            variables = mergedVariables,
            isLeaf = isLeaf
        )
    }

    /**
     * Select a choice: save to history, execute native actions, return the target.
     */
    fun selectChoice(choice: InteractionEdgeQuestionChoiceModel): ChoiceResult {
        // Push current position onto history
        val entry = HistoryEntry(
            edgeId = _state.currentEdgeId,
            cid = _state.currentCid,
            title = _state.title
        )
        _state = _state.copy(history = _state.history + entry)

        // Execute variable mutations from nativeAction
        if (choice.nativeAction.isNotBlank()) {
            executeActions(choice.nativeAction)
        }

        return ChoiceResult(
            targetEdgeId = choice.id,
            targetCid = choice.cid
        )
    }

    /**
     * Pop the history stack and return the previous entry, or null if empty.
     */
    fun goBack(): HistoryEntry? {
        val history = _state.history
        if (history.isEmpty()) return null
        val entry = history.last()
        _state = _state.copy(
            history = history.dropLast(1),
            currentEdgeId = entry.edgeId,
            currentCid = entry.cid,
            title = entry.title,
            isLeaf = false  // previous node had at least one outgoing choice
        )
        return entry
    }

    fun canGoBack(): Boolean = _state.history.isNotEmpty()

    /**
     * Restore variables and history from saved progress.
     */
    fun restoreProgress(variables: Map<String, Float>, history: List<HistoryEntry>) {
        _state = _state.copy(variables = variables, history = history)
    }

    /**
     * Clear all engine state.
     */
    fun reset() {
        _state = State()
    }

    // ── Choice filtering ───────────────────────────────────────────────────

    /**
     * Filter out hidden choices and evaluate conditions.
     * Returns only the choices the user is allowed to see.
     */
    fun getVisibleChoices(questions: List<InteractionEdgeQuestionModel>?): List<InteractionEdgeQuestionChoiceModel> {
        if (questions.isNullOrEmpty()) return emptyList()
        val allChoices = questions.flatMap { it.choices ?: emptyList() }
        return allChoices.filter { choice ->
            // Skip explicitly hidden choices
            if (choice.isHidden == 1) return@filter false
            // Evaluate condition; if blank or true, keep it
            if (choice.condition.isBlank()) return@filter true
            evaluateCondition(choice.condition)
        }
    }

    /**
     * Return the default choice (isDefault == 1), or the first one if none marked.
     */
    fun getDefaultChoice(choices: List<InteractionEdgeQuestionChoiceModel>): InteractionEdgeQuestionChoiceModel? {
        return choices.firstOrNull { it.isDefault == 1 } ?: choices.firstOrNull()
    }

    // ── Condition evaluation ───────────────────────────────────────────────

    /**
     * Evaluate a condition string like "$score >= 10" or "$hp > 0 && $mp > 0".
     * Supports `&&` for logical AND.
     */
    fun evaluateCondition(condition: String): Boolean {
        // Split on && for compound conditions
        val parts = condition.split("&&").map { it.trim() }
        return parts.all { evaluateSingleCondition(it) }
    }

    private fun evaluateSingleCondition(cond: String): Boolean {
        val match = conditionRegex.find(cond) ?: return false
        val operator = match.value
        val operatorIndex = match.range.first

        val leftPart = cond.substring(0, operatorIndex).trim()
        val rightPart = cond.substring(operatorIndex + operator.length).trim()

        val leftVal = resolveValue(leftPart)
        val rightVal = resolveValue(rightPart)

        return when (operator) {
            ">="  -> leftVal >= rightVal
            "<="  -> leftVal <= rightVal
            "=="  -> leftVal == rightVal
            "!="  -> leftVal != rightVal
            ">"   -> leftVal > rightVal
            "<"   -> leftVal < rightVal
            else  -> false
        }
    }

    // ── Variable action execution ──────────────────────────────────────────

    /**
     * Execute semicolon-separated nativeAction statements.
     * Each statement is of the form "$varName = expr" where expr may contain
     * variable references, numeric literals, and +, -, *, / operators.
     */
    fun executeActions(actionString: String) {
        val statements = actionString.split(";").map { it.trim() }.filter { it.isNotBlank() }
        for (stmt in statements) {
            executeSingleAction(stmt)
        }
    }

    private fun executeSingleAction(stmt: String) {
        val eqIndex = stmt.indexOf('=')
        if (eqIndex < 0) return

        // Make sure it's not ==, !=, >=, <=
        if (eqIndex > 0 && stmt[eqIndex - 1] in listOf('!', '>', '<')) return
        if (eqIndex + 1 < stmt.length && stmt[eqIndex + 1] == '=') return

        val target = stmt.substring(0, eqIndex).trim()
        val expr = stmt.substring(eqIndex + 1).trim()

        val varName = if (target.startsWith("$")) target.substring(1) else target
        val result = evaluateExpression(expr)

        _state = _state.copy(
            variables = _state.variables + (varName to result)
        )
    }

    // ── Helper methods ─────────────────────────────────────────────────────

    /**
     * Build a variable map from the hidden_vars list.
     */
    fun buildVariableMap(vars: List<InteractionVariableModel>?): Map<String, Float> {
        if (vars.isNullOrEmpty()) return emptyMap()
        return vars.associate { it.idV2 to it.value }
    }

    /**
     * Resolve a value: if it starts with $, look up the variable;
     * otherwise parse as a float literal.
     */
    fun resolveValue(token: String): Float {
        val trimmed = token.trim()
        if (trimmed.startsWith("$")) {
            return _state.variables[trimmed.substring(1)] ?: 0f
        }
        return trimmed.toFloatOrNull() ?: 0f
    }

    /**
     * Evaluate a simple arithmetic expression with +, -, *, / and variable references.
     * Only handles binary expressions (left op right).
     */
    fun evaluateExpression(expr: String): Float {
        val trimmed = expr.trim()

        // Try parsing as a simple value first
        if (!trimmed.contains("+") && !trimmed.contains("-") &&
            !trimmed.contains("*") && !trimmed.contains("/")
        ) {
            return resolveValue(trimmed)
        }

        // Find the operator (only one level of binary operation supported)
        val idx = findOperatorIndex(trimmed)
        if (idx < 0) return resolveValue(trimmed)

        val op = trimmed[idx]
        val leftStr = trimmed.substring(0, idx).trim()
        val rightStr = trimmed.substring(idx + 1).trim()

        val left = resolveValue(leftStr)
        val right = resolveValue(rightStr)

        return when (op) {
            '+' -> left + right
            '-' -> left - right
            '*' -> left * right
            '/' -> if (right != 0f) left / right else 0f
            else -> 0f
        }
    }

    /**
     * Find the index of the lowest-precedence arithmetic operator in the expression.
     * Scans right-to-left to respect left-to-right evaluation of same-precedence ops.
     * Skips characters that are part of variable names (after $) or numeric literals.
     */
    fun findOperatorIndex(expr: String): Int {
        var lastPlus = -1
        var lastMinus = -1
        var lastMul = -1
        var lastDiv = -1

        for (i in expr.indices) {
            val c = expr[i]
            when (c) {
                '+' -> {
                    // Skip if it's part of a number (e.g. scientific notation) or sign
                    if (i == 0 || expr[i - 1] in listOf('e', 'E', '$')) continue
                    lastPlus = i
                }
                '-' -> {
                    if (i == 0 || expr[i - 1] in listOf('e', 'E', '$')) continue
                    lastMinus = i
                }
                '*' -> {
                    if (i > 0 && expr[i - 1] == '$') continue
                    lastMul = i
                }
                '/' -> {
                    if (i > 0 && expr[i - 1] == '$') continue
                    lastDiv = i
                }
            }
        }

        // + and - have lower precedence than * and /
        // Return the rightmost lowest-precedence operator
        return when {
            lastPlus >= 0  -> lastPlus
            lastMinus >= 0 -> lastMinus
            lastMul >= 0   -> lastMul
            lastDiv >= 0   -> lastDiv
            else           -> -1
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private fun resolveFirstCid(model: InteractionModel): Long {
        // Try storyList first
        val story = model.storyList?.firstOrNull()
        if (story != null && story.cid != 0L) return story.cid

        // Fall back to first choice's cid
        val firstChoice = model.edges?.questions
            ?.firstOrNull()
            ?.choices
            ?.firstOrNull()
        if (firstChoice != null && firstChoice.cid != 0L) return firstChoice.cid

        return 0L
    }
}
