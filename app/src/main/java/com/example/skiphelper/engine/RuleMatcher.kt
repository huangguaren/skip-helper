package com.example.skiphelper.engine

/**
 * 规则匹配语义。
 *
 * 前缀约定：
 *   +foo  以 foo 开头
 *   -foo  以 foo 结尾
 *   =foo  完全等于 foo
 *   foo   包含 foo（默认）
 *   a&b   a 且 b（按 & 拆分后逐个匹配，全部满足才算命中）
 *
 * 匹配字段依次尝试 text → contentDescription → 资源 id 名。
 * 这个顺序是有意的：文本是最可靠的人工可读信号，资源 id 兜底那些没有文字的按钮。
 */
object RuleMatcher {

    private val BOUNDS = Regex("^\\s*-?\\d+\\s*,\\s*-?\\d+\\s*,\\s*-?\\d+\\s*,\\s*-?\\d+\\s*(?:,\\s*[01]\\s*)?$")

    /** 是否是坐标动作 "x1,y1,x2,y2" 或带 CheckBox 状态的 "x1,y1,x2,y2,1"。 */
    fun isBounds(value: String?): Boolean = value != null && BOUNDS.matches(value)

    fun parseBounds(value: String): IntArray {
        val parts = value.trim().split(",")
        val out = IntArray(4)
        for (i in 0 until 4) out[i] = parts[i].trim().toInt()
        return out
    }

    /** 坐标动作里可选的第五段：0/1，表示要求 CheckBox 处于该状态才点。 */
    fun boundsCheckboxState(value: String): Int? {
        val parts = value.trim().split(",")
        return if (parts.size >= 5) parts[4].trim().toIntOrNull() else null
    }

    /** 对单个节点的所有可匹配字段尝试一次条件。 */
    fun matches(cond: String, text: String?, desc: String?, resId: String?): Boolean {
        if (cond.isEmpty()) return false
        return fieldMatches(cond, text) || fieldMatches(cond, desc) || fieldMatches(cond, resId)
    }

    /**
     * 节点的完整条件判定：坐标条件走几何匹配，其余走字段匹配。
     *
     * 原版 `rule_input_dialog.xml` 的 id 输入框提示是「文字、id、\nbounds」，
     * 说明坐标既能当动作也能当触发器。触发器语义取「谁盖住了这个坐标」：
     * 目标矩形的中心点落在节点的 bounds 内即命中，不需要矩形完全相等——
     * 用户是从开发者选项/布局边界里抄的坐标，抄的是按钮外框，和节点框常有几像素差。
     */
    fun matchesNode(
        cond: String,
        text: String?,
        desc: String?,
        resId: String?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Boolean {
        if (cond.isEmpty()) return false
        if (isBounds(cond)) return boundsCovers(cond, left, top, right, bottom)
        return matches(cond, text, desc, resId)
    }

    /** 坐标条件是否命中某个节点矩形：目标中心落在节点内。 */
    fun boundsCovers(cond: String, left: Int, top: Int, right: Int, bottom: Int): Boolean {
        if (right <= left || bottom <= top) return false
        val r = parseBounds(cond)
        if (r[2] <= r[0] || r[3] <= r[1]) return false
        val cx = (r[0] + r[2]) / 2
        val cy = (r[1] + r[3]) / 2
        return left <= cx && cx <= right && top <= cy && cy <= bottom
    }

    /**
     * 节点身份。
     *
     * 有资源 id 时只用 text|resId：开屏倒计时动画里同一按钮的坐标会漂移，
     * 把坐标算进去会让同一个按钮被当成两个，从而重复点击。
     */
    fun nodeKey(text: String?, resId: String?, left: Int, top: Int): String =
        if (!resId.isNullOrEmpty()) "$text|$resId" else "$text|@$left,$top"

    /**
     * 排除词判定：任一字段包含任一排除词即命中。
     *
     * 不走前缀语法——排除词是"最后一道闸"，写法越简单越不容易被规则作者写错。
     * 大小写不敏感只对纯 ASCII 生效，避免影响中文。
     */
    fun isExcluded(text: String?, desc: String?, resId: String?, excludes: List<String>): Boolean {
        if (excludes.isEmpty()) return false
        val fields = listOfNotNull(text, desc, resId)
        if (fields.isEmpty()) return false
        for (e in excludes) {
            val needle = e.trim()
            if (needle.isEmpty()) continue
            for (f in fields) {
                if (f.contains(needle, ignoreCase = true)) return true
            }
        }
        return false
    }

    private fun fieldMatches(cond: String, value: String?): Boolean {
        if (value.isNullOrEmpty()) return false
        if (cond.indexOf('&') >= 0) {
            for (sub in cond.split("&")) {
                if (sub.isEmpty()) continue
                if (!matchOne(sub, value)) return false
            }
            return true
        }
        return matchOne(cond, value)
    }

    private fun matchOne(cond: String, value: String): Boolean {
        if (cond.isEmpty()) return false
        return when (cond[0]) {
            '=' -> value == cond.substring(1)
            '+' -> value.startsWith(cond.substring(1))
            '-' -> value.endsWith(cond.substring(1))
            else -> value.contains(cond)
        }
    }
}
