package com.example.skiphelper.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 匹配语义的单测：这些行为对齐官方规则文件里的写法。 */
class RuleMatcherTest {

    private fun m(cond: String, text: String? = null, desc: String? = null, id: String? = null) =
        RuleMatcher.matches(cond, text, desc, id)

    @Test fun defaultIsContains() {
        assertTrue(m("跳过", text = "跳过 3s"))
        assertTrue(m("广告", text = "跳过广告"))
        assertFalse(m("跳过", text = "关闭"))
    }

    @Test fun plusIsPrefix() {
        assertTrue(m("+跳过", text = "跳过 5"))
        assertFalse(m("+跳过", text = "点击跳过"))
    }

    @Test fun minusIsSuffix() {
        assertTrue(m("-关闭", text = "点击关闭"))
        assertFalse(m("-关闭", text = "关闭广告"))
    }

    @Test fun equalsIsExact() {
        assertTrue(m("=跳过", text = "跳过"))
        assertFalse(m("=跳过", text = "跳过广告"))
    }

    @Test fun ampIsAnd() {
        // 两个子条件必须同时满足
        assertTrue(m("跳过&3", text = "跳过 3s"))
        assertFalse(m("跳过&5", text = "跳过 3s"))
    }

    @Test fun fallsBackToDescAndResId() {
        assertTrue(m("close", desc = "close"))
        assertTrue(m("skip_view", id = "skip_view"))
        assertTrue(m("skip", id = "ad_skip_btn"))
    }

    @Test fun emptyConditionNeverMatches() {
        assertFalse(m("", text = "跳过"))
    }

    @Test fun boundsDetection() {
        assertTrue(RuleMatcher.isBounds("100,200,300,400"))
        assertTrue(RuleMatcher.isBounds(" 10 , 20 , 30 , 40 , 1 "))
        assertFalse(RuleMatcher.isBounds("跳过"))
        assertFalse(RuleMatcher.isBounds("100,200,300"))
    }

    @Test fun excludesBeatPositiveMatch() {
        val ex = listOf("跳过登录", "跳过支付", "跳过验证")
        assertTrue(RuleMatcher.isExcluded("跳过登录", null, null, ex))
        assertTrue(RuleMatcher.isExcluded("跳过支付并继续", null, null, ex))
        assertFalse(RuleMatcher.isExcluded("跳过广告", null, null, ex))
        // 描述字段同样参与判定
        assertTrue(RuleMatcher.isExcluded(null, "跳过验证", null, ex))
        // 空排除表 / 空排除词不生效
        assertFalse(RuleMatcher.isExcluded("跳过登录", null, null, emptyList()))
        assertFalse(RuleMatcher.isExcluded("跳过登录", null, null, listOf("  ")))
    }

    @Test fun boundsParsing() {
        assertArrayEquals(intArrayOf(100, 200, 300, 400), RuleMatcher.parseBounds("100,200,300,400"))
        assertEquals(1, RuleMatcher.boundsCheckboxState("1,2,3,4,1"))
        assertEquals(null, RuleMatcher.boundsCheckboxState("1,2,3,4"))
    }

    // ---------------------------------------------------------------- 坐标触发器
    // 原版 id 输入框提示「文字、id、\nbounds」，坐标可以当触发器用。

    @Test fun boundsTriggerHitsNodeCoveringCenter() {
        // 节点 0,0-500,500 盖住坐标矩形 (100,100)-(200,200) 的中心
        assertTrue(RuleMatcher.boundsCovers("100,100,200,200", 0, 0, 500, 500))
    }

    @Test fun boundsTriggerMissesNodeOutside() {
        // 节点在右上角，点中心 (150,150) 不在它里面
        assertFalse(RuleMatcher.boundsCovers("100,100,200,200", 300, 300, 500, 500))
    }

    @Test fun boundsTriggerIgnoresFifthField() {
        // 第五段是 CheckBox 前置状态，不参与几何判定
        assertTrue(RuleMatcher.boundsCovers("100,100,200,200,1", 0, 0, 500, 500))
    }

    @Test fun degenerateRectsNeverMatch() {
        assertFalse(RuleMatcher.boundsCovers("100,100,100,200", 0, 0, 500, 500)) // 零宽
        assertFalse(RuleMatcher.boundsCovers("100,200,300,400", 100, 200, 100, 400)) // 节点零宽
    }

    @Test fun matchesNodeDispatchesBoundsVsText() {
        // 坐标条件走几何
        assertTrue(RuleMatcher.matchesNode("100,100,200,200", "别的文字", null, null, 0, 0, 500, 500))
        assertFalse(RuleMatcher.matchesNode("100,100,200,200", "跳过", null, null, 300, 300, 400, 400))
        // 非坐标条件仍走字段匹配
        assertTrue(RuleMatcher.matchesNode("跳过", "跳过广告", null, null, 0, 0, 10, 10))
        // 空条件永假
        assertFalse(RuleMatcher.matchesNode("", "跳过", null, null, 0, 0, 10, 10))
    }
}
