package com.example.skiphelper.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 规则 JSON 解析与节点身份的单测：这些字段决定原版规则文件能不能被正确执行。 */
class RuleJsonTest {

    private val sample = """
        {
          "keywords": ["跳过", "关闭"],
          "keywords_append": ["跳过广告"],
          "popup_rules": [
            {"id": "青少年模式", "action": "我知道了"},
            {"id": "skip_btn", "action": "", "times": 0},
            {"id": "tv_close", "action": "GLOBAL_ACTION_BACK", "times": 2}
          ],
          "click_way_popup": 1,
          "search_times_popup": 5,
          "delay": 200,
          "delay_popup": 350,
          "unite_popup_rules": 1,
          "reverse_traversal": true,
          "ltt_service": true
        }
    """.trimIndent()

    @Test fun parsesKeywordsWithAppend() {
        val r = RuleJson.fromOfficialJson("com.demo", JSONObject(sample))
        assertEquals(listOf("跳过", "关闭", "跳过广告"), r.keywords)
    }

    @Test fun parsesPopupRulesWithTimes() {
        val r = RuleJson.fromOfficialJson("com.demo", JSONObject(sample))
        assertEquals(3, r.popupRules.size)
        assertEquals(PopupRule("青少年模式", "我知道了", 1), r.popupRules[0])
        assertEquals(0, r.popupRules[1].times)
        assertEquals(2, r.popupRules[2].times)
    }

    @Test fun intFlagsAreAcceptedAsBooleans() {
        val r = RuleJson.fromOfficialJson("com.demo", JSONObject(sample))
        assertTrue(r.forceClick)
        assertTrue(r.uniteRules)
        assertTrue(r.reverseTraversal)
        assertTrue(r.enabled)
        assertEquals(5, r.searchTimes)
    }

    @Test fun delayTakesMaxOfDelayAndDelayPopup() {
        val r = RuleJson.fromOfficialJson("com.demo", JSONObject(sample))
        assertEquals(350, r.delayMs)
    }

    @Test fun missingFieldsFallBackToDefaults() {
        val r = RuleJson.fromOfficialJson("com.demo", JSONObject("{}"))
        assertTrue(r.isEmpty())
        assertTrue(r.enabled)
        assertEquals(1, r.searchTimes)
        assertEquals(0, r.delayMs)
        assertFalse(r.forceClick)
    }

    @Test fun storedJsonRoundTrips() {
        val original = RuleJson.fromOfficialJson("com.demo", JSONObject(sample))
        val restored = RuleJson.parseStored(RuleJson.toStored(original))
        assertEquals(original.pkg, restored?.pkg)
        assertEquals(original.keywords, restored?.keywords)
        assertEquals(original.popupRules, restored?.popupRules)
        assertEquals(original.delayMs, restored?.delayMs)
        assertEquals(original.searchTimes, restored?.searchTimes)
    }

    @Test fun storedWithoutPackageIsRejected() {
        assertNull(RuleJson.parseStored(JSONObject("{\"keywords\":[\"跳过\"]}")))
    }

    @Test fun disabledServiceIsKept() {
        val r = RuleJson.fromOfficialJson("com.demo", JSONObject("{\"ltt_service\":false}"))
        assertFalse(r.enabled)
    }

    @Test fun nodeKeyPrefersResourceIdOverDriftingBounds() {
        val a = RuleMatcher.nodeKey("跳过 3s", "skip_view", 100, 200)
        val b = RuleMatcher.nodeKey("跳过 3s", "skip_view", 104, 208)
        assertEquals(a, b)
        // 没有资源 id 时退回坐标
        val c = RuleMatcher.nodeKey("跳过", null, 100, 200)
        val d = RuleMatcher.nodeKey("跳过", null, 104, 208)
        assertFalse(c == d)
    }
}
