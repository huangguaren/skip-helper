package com.example.skiphelper.engine

/**
 * 规则模型：一个应用 → 关键词表 + 弹窗规则表 + 参数表。
 *
 * 结构与社区规则文件对齐（字段名沿用原版：keywords / popup_rules / click_way_popup /
 * search_times_popup / unite_popup_rules / reverse_traversal / delay / delay_popup /
 * ltt_service），但只保留实际会执行的子集。
 */
data class PopupRule(
    /** 触发器：命中的文字或资源 id */
    val id: String,
    /** 动作：控件文字 / GLOBAL_ACTION_BACK / 坐标 "x1,y1,x2,y2"；留空表示点触发器本身 */
    val action: String = "",
    /** 点击次数；0 表示"只检测不点击"，用于联合规则的前置条件 */
    val times: Int = 1,
)

data class AppRuleSet(
    val pkg: String,
    val enabled: Boolean = true,
    val keywords: List<String> = emptyList(),
    val popupRules: List<PopupRule> = emptyList(),
    /** click_way_popup：找不到目标时强行点触发器 */
    val forceClick: Boolean = false,
    /** search_times_popup：重复扫描次数 */
    val searchTimes: Int = 1,
    /** unite_popup_rules：存在 times=0 的前置规则时，必须先命中它才执行其它规则 */
    val uniteRules: Boolean = false,
    /** reverse_traversal：子树逆序遍历（有些 App 的后渲染节点优先级更高） */
    val reverseTraversal: Boolean = false,
    /** delay / delay_popup：规则命中后延迟多少毫秒再点（开屏倒计时还没结束时会用到） */
    val delayMs: Int = 0,
    /**
     * 开屏盲点（百分位坐标，0~100，-1 表示关闭）。
     *
     * 有些开屏广告整屏都是自绘 View，无障碍树里一个文字节点都没有（咪咕视频、
     * 部分游戏），关键词和 id 规则都无从下手，只能在固定相对位置点一下。
     * 只在这个应用刚启动的几秒内生效，避免乱点。
     */
    val blindX: Int = -1,
    val blindY: Int = -1,
) {
    fun isEmpty(): Boolean = keywords.isEmpty() && popupRules.isEmpty()

    /** 合并同名规则集：关键词取并集，弹窗规则按 (id, action) 去重。 */
    fun merge(other: AppRuleSet): AppRuleSet = AppRuleSet(
        pkg = pkg,
        enabled = enabled && other.enabled,
        keywords = (keywords + other.keywords).distinct(),
        popupRules = (popupRules + other.popupRules).distinctBy { it.id to it.action },
        forceClick = forceClick || other.forceClick,
        searchTimes = maxOf(searchTimes, other.searchTimes),
        uniteRules = uniteRules || other.uniteRules,
        reverseTraversal = reverseTraversal || other.reverseTraversal,
        delayMs = maxOf(delayMs, other.delayMs),
        blindX = if (blindX >= 0) blindX else other.blindX,
        blindY = if (blindY >= 0) blindY else other.blindY,
    )
}

/** 一次评估的全局参数。 */
data class EvalConfig(
    /** 命中即放弃的排除词 */
    val excludes: List<String> = emptyList(),
    /** filter_invisible_nodes：只考虑 isVisibleToUser 的节点 */
    val filterInvisible: Boolean = true,
    /** filter_young_popups：不自动点「青少年模式」类弹窗 */
    val filterYoung: Boolean = true,
)

/** 引擎产出的动作。 */
sealed class PlannedAction {
    /** 点击指定节点 */
    data class Node(val node: Any?, val label: String, val reason: String) : PlannedAction()
    /** 向上滑动（互动开屏的「向上滑动」入口） */
    data class Swipe(val reason: String) : PlannedAction()
    /** 触发系统返回 */
    data class Back(val reason: String) : PlannedAction()
    /** 点击坐标；checkState 非空时要求该位置的 CheckBox 处于指定状态 */
    data class Bounds(val rect: IntArray, val checkState: Int?, val reason: String) : PlannedAction() {
        override fun equals(other: Any?): Boolean {
            if (other !is Bounds) return false
            return rect.contentEquals(other.rect) && checkState == other.checkState && reason == other.reason
        }
        override fun hashCode(): Int = rect.contentHashCode() * 31 + (checkState ?: -1)
    }
    /** 仅检测，不执行（times=0 的中间态） */
    data class None(val label: String, val reason: String) : PlannedAction()
}

/** 全局规则的虚拟包名：存在规则文件里，但对所有应用生效。 */
const val GLOBAL_RULE_PKG = "*"
