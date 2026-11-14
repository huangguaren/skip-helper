package com.example.skiphelper.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 规则评估器：输入一个应用（以及可选的全局规则）+ 当前屏幕的节点树，输出「要执行的动作」。
 *
 * 和原版语义对齐的几处细节：
 *  - 全局规则先于应用规则；先后命中的同一节点只保留一次
 *  - 关键词先于弹窗规则
 *  - times=0 的规则只做前置检测（unite_popup_rules 的"闸门"）
 *  - 动作支持 控件 / 系统返回 / 坐标，坐标还可以带 CheckBox 状态
 *  - reverse_traversal 让子树逆序，应付后渲染层在前的界面
 *  - filter_invisible_nodes / filter_young_popups 两个原版开关
 */
object RuleEvaluator {

    private const val MAX_NODES = 1200
    private const val MAX_DEPTH = 30

    /** 宽松的节点快照：只保留匹配和点击需要的信息。 */
    private class Snap(
        val node: AccessibilityNodeInfo,
        val text: String?,
        val desc: String?,
        val resId: String?,
        val clickable: Boolean,
        val visible: Boolean,
        /** 是否 CheckBox/CompoundButton，用于坐标动作的状态前置条件 */
        val checkable: Boolean,
        val checked: Boolean,
        val bounds: Rect,
        val children: List<Snap>,
    )

    fun evaluate(
        rule: AppRuleSet,
        roots: List<AccessibilityNodeInfo>,
        screenW: Int,
        screenH: Int,
        cfg: EvalConfig = EvalConfig(),
    ): List<PlannedAction> = evaluateCombined(rule, null, roots, screenW, screenH, cfg)

    /**
     * 同时评估全局规则与应用规则。
     *
     * @param app    当前前台应用的规则（可为空）
     * @param global 对所有应用生效的全局规则（可为空）
     */
    fun evaluateCombined(
        app: AppRuleSet?,
        global: AppRuleSet?,
        roots: List<AccessibilityNodeInfo>,
        screenW: Int,
        screenH: Int,
        cfg: EvalConfig = EvalConfig(),
    ): List<PlannedAction> {
        val out = ArrayList<PlannedAction>(4)
        val seenNodes = HashSet<String>()
        val seenRules = HashSet<String>()
        for (root in roots) {
            val snap = snapshot(root, 0) ?: continue
            global?.takeIf { !it.isEmpty() }?.let {
                run(it, snap, screenW, screenH, cfg, out, seenNodes, seenRules, "[全局]")
            }
            app?.takeIf { !it.isEmpty() }?.let {
                run(it, snap, screenW, screenH, cfg, out, seenNodes, seenRules, "")
            }
        }
        return out
    }

    private fun run(
        rule: AppRuleSet,
        snap: Snap,
        screenW: Int,
        screenH: Int,
        cfg: EvalConfig,
        out: MutableList<PlannedAction>,
        seenNodes: MutableSet<String>,
        seenRules: MutableSet<String>,
        suffix: String,
    ) {
        val screenArea = (screenW * screenH).toFloat().coerceAtLeast(1f)

        // 1) 关键词
        for (kw in rule.keywords) {
            val hit = findTarget(snap, kw, rule.reverseTraversal, cfg.filterInvisible) ?: continue
            if (!acceptable(hit, screenW, screenH, screenArea, cfg.excludes)) continue
            val key = nodeKey(hit)
            if (!seenNodes.add(key)) continue
            out.add(PlannedAction.Node(hit.node, hit.text ?: kw, "关键词「$kw」$suffix"))
        }

        // 2) 联合规则闸门：存在 times=0 的规则但没命中，就不执行后续点击
        if (rule.uniteRules) {
            val gates = rule.popupRules.filter { it.times == 0 && it.id.isNotEmpty() }
            if (gates.isNotEmpty() &&
                gates.none { findTarget(snap, it.id, rule.reverseTraversal, cfg.filterInvisible) != null }
            ) {
                return
            }
        }

        // 3) 弹窗规则
        repeat(rule.searchTimes.coerceAtLeast(1)) {
            for (pr in rule.popupRules) {
                if (pr.id.isEmpty()) continue
                if (cfg.filterYoung && pr.id.contains("青少年")) continue
                val ruleKey = "${rule.pkg}|${pr.id}|${pr.action}"
                if (!seenRules.add(ruleKey)) continue
                val trigger = findTarget(snap, pr.id, rule.reverseTraversal, cfg.filterInvisible) ?: continue
                if (pr.times == 0) continue
                // 排除词优先于任何正向规则：命中「跳过登录/支付/验证」这类字样的控件一律不碰
                if (RuleMatcher.isExcluded(trigger.text, trigger.desc, trigger.resId, cfg.excludes)) continue
                val action = resolve(pr, trigger, snap, rule, screenW, screenH, screenArea, cfg)
                repeat(pr.times.coerceAtLeast(1)) { out.add(action) }
            }
        }
    }

    private fun resolve(
        pr: PopupRule,
        trigger: Snap,
        root: Snap,
        rule: AppRuleSet,
        screenW: Int, screenH: Int, screenArea: Float,
        cfg: EvalConfig,
    ): PlannedAction {
        val action = pr.action
        if (action == "GLOBAL_ACTION_BACK") {
            return PlannedAction.Back("规则「${pr.id}」要求返回")
        }
        if (RuleMatcher.isBounds(action)) {
            val rect = RuleMatcher.parseBounds(action)
            val state = RuleMatcher.boundsCheckboxState(action)
            // 第五段是 CheckBox 前置状态：状态不符就不点（找不到控件也按不符处理）
            if (state != null) {
                val checkable = findCheckableAt(root, rect)
                if (checkable == null || !checkable.checkable || checkable.checked != (state == 1)) {
                    return PlannedAction.None(pr.id, "规则「${pr.id}」CheckBox 状态不符")
                }
            }
            return PlannedAction.Bounds(rect, state, "规则「${pr.id}」点击坐标")
        }
        // action 为空或与触发器一致 → 点触发器本身
        if (action.isEmpty() || action == pr.id) {
            return PlannedAction.Node(trigger.node, pr.id, "规则「${pr.id}」点自身")
        }
        // 否则找 action 对应的控件，优先可点击的
        val target = findFirstClickable(root, action, rule.reverseTraversal, cfg.filterInvisible)
            ?: findFirst(root, action, rule.reverseTraversal, cfg.filterInvisible)
        if (target != null && acceptable(target, screenW, screenH, screenArea, cfg.excludes)) {
            return PlannedAction.Node(target.node, action, "规则「${pr.id}」→「$action」")
        }
        if (rule.forceClick) {
            return PlannedAction.Node(trigger.node, pr.id, "规则「${pr.id}」强制点触发器")
        }
        return PlannedAction.None(pr.id, "规则「${pr.id}」未找到目标")
    }

    // ---------------------------------------------------------------- 遍历
    private fun snapshot(node: AccessibilityNodeInfo, depth: Int): Snap? {
        if (depth > MAX_DEPTH) return null
        val text = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val desc = node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val resId = node.viewIdResourceName?.substringAfterLast('/')
        val rect = Rect().also { node.getBoundsInScreen(it) }

        val kids = ArrayList<Snap>(node.childCount)
        var count = 0
        for (i in 0 until node.childCount) {
            if (++count > MAX_NODES) break
            val c = node.getChild(i) ?: continue
            snapshot(c, depth + 1)?.let { kids.add(it) }
        }
        return Snap(
            node, text, desc, resId, node.isClickable, node.isVisibleToUser,
            node.isCheckable, node.isChecked, rect, kids,
        )
    }

    private fun findFirst(s: Snap, cond: String, reverse: Boolean, filterInvisible: Boolean): Snap? {
        if (s.visible || !filterInvisible) {
            if (condMatches(cond, s)) return s
        }
        val kids = if (reverse) s.children.asReversed() else s.children
        for (c in kids) findFirst(c, cond, reverse, filterInvisible)?.let { return it }
        return null
    }

    private fun findFirstClickable(s: Snap, cond: String, reverse: Boolean, filterInvisible: Boolean): Snap? {
        if ((s.visible || !filterInvisible) && s.clickable && condMatches(cond, s)) {
            return s
        }
        val kids = if (reverse) s.children.asReversed() else s.children
        for (c in kids) findFirstClickable(c, cond, reverse, filterInvisible)?.let { return it }
        return null
    }

    /**
     * 条件 → 目标节点。
     *
     * 坐标条件不能直接用遍历到的第一个命中节点：外层容器同样「盖住」这个坐标，
     * 点容器等于点整屏。这里改成在命中集合里挑面积最小的那个——它才是用户想点的按钮。
     */
    private fun findTarget(root: Snap, cond: String, reverse: Boolean, filterInvisible: Boolean): Snap? =
        if (RuleMatcher.isBounds(cond)) findSmallestAt(root, cond, filterInvisible)
        else findFirst(root, cond, reverse, filterInvisible)

    private fun findSmallestAt(root: Snap, cond: String, filterInvisible: Boolean): Snap? {
        var best: Snap? = null
        fun walk(n: Snap) {
            if (n.visible || !filterInvisible) {
                if (RuleMatcher.boundsCovers(
                        cond, n.bounds.left, n.bounds.top, n.bounds.right, n.bounds.bottom,
                    )
                ) {
                    val b = best
                    if (b == null || n.bounds.width() * n.bounds.height() < b.bounds.width() * b.bounds.height()) {
                        best = n
                    }
                }
            }
            for (c in n.children) walk(c)
        }
        walk(root)
        return best
    }

    /** 条件判定：坐标条件按几何命中，文字/id 按字段匹配。 */
    private fun condMatches(cond: String, s: Snap): Boolean = RuleMatcher.matchesNode(
        cond, s.text, s.desc, s.resId,
        s.bounds.left, s.bounds.top, s.bounds.right, s.bounds.bottom,
    )

    /** 找到覆盖 rect 中心的 CheckBox 节点（坐标规则的状态前置条件用）。 */
    private fun findCheckableAt(s: Snap, rect: IntArray): Snap? {
        val cx = (rect[0] + rect[2]) / 2
        val cy = (rect[1] + rect[3]) / 2
        if (s.checkable && s.visible && s.bounds.contains(cx, cy)) return s
        for (c in s.children) findCheckableAt(c, rect)?.let { return it }
        return null
    }

    /** 几何约束：整屏容器不点、屏幕外不点、太小不点、排除词命中不点。 */
    private fun acceptable(
        s: Snap,
        screenW: Int,
        screenH: Int,
        screenArea: Float,
        excludes: List<String>,
    ): Boolean {
        if (RuleMatcher.isExcluded(s.text, s.desc, s.resId, excludes)) return false
        val r = s.bounds
        if (r.isEmpty) return false
        if (r.width() < 8 || r.height() < 8) return false
        if (r.width().toFloat() * r.height() > screenArea * 0.4f) return false
        return r.left >= -8 && r.top >= -8 && r.right <= screenW + 8 && r.bottom <= screenH + 8
    }

    /**
     * 节点身份。
     *
     * 有资源 id 时只用 text|resId：开屏倒计时动画里同一按钮的坐标会漂移，
     * 把坐标算进去会让同一个按钮被当成两个，从而重复点击。
     */
    fun nodeKey(text: String?, resId: String?, left: Int, top: Int): String =
        RuleMatcher.nodeKey(text, resId, left, top)

    private fun nodeKey(s: Snap): String = nodeKey(s.text, s.resId, s.bounds.left, s.bounds.top)
}
