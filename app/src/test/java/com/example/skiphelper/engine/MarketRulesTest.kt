package com.example.skiphelper.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 市场规则包（assets/market-rules-2026.11.json）的结构校验。
 *
 * 这个文件由 `tools/rules/build_market_rules.py` 用 Python 生成，键是包名的 Java
 * `String.hashCode()`。测试用 Kotlin 侧独立算一遍哈希，等于跨语言核对生成器有没有算错。
 */
class MarketRulesTest {

    private fun asset(): File {
        val candidates = listOf(
            File("src/main/assets/${RuleImport.MARKET_ASSET}"),
            File("app/src/main/assets/${RuleImport.MARKET_ASSET}"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("asset not found: ${candidates.map { it.absolutePath }}")
    }

    /** 与 java.lang.String.hashCode 一致。 */
    private fun javaHash(s: String): Int {
        var h = 0
        for (ch in s) h = 31 * h + ch.code
        return h
    }

    private fun rows(): List<JSONObject> {
        val arr = JSONArray(asset().readText())
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    @Test fun everyEntryIsHashKeyedWithParsableInnerJson() {
        val list = rows()
        assertTrue("规则包条目太少: ${list.size}", list.size >= 300)
        var total = 0
        for (row in list) {
            val key = row.keys().asSequence().firstOrNull() ?: continue
            val hash = key.toIntOrNull()
            assertNotNull("键不是整型哈希: $key", hash)
            val inner = JSONObject(row.optString(key))
            assertTrue("入口未启用: $key", inner.optBoolean("ltt_service", false))
            val rules = inner.optJSONArray("popup_rules") ?: continue
            total += rules.length()
            for (i in 0 until rules.length()) {
                val r = rules.optJSONObject(i) ?: continue
                assertTrue("空 id: $key#$i", r.optString("id").isNotEmpty())
            }
        }
        assertTrue("弹窗规则总数偏少: $total", total >= 700)
    }

    @Test fun hashKeysMatchKotlinStringHashCode() {
        val keys = rows().flatMap { row -> row.keys().asSequence().toList() }.toSet()
        // 覆盖社区库高频 App 与模板补充的 5 个，验证生成器与 JVM 哈希一致
        val packages = listOf(
            "com.tencent.mm", "com.tencent.mobileqq", "com.taobao.taobao", "com.ss.android.ugc.aweme",
            "com.xingin.xhs", "com.sina.weibo", "tv.danmaku.bili", "com.sankuai.meituan",
            "com.dianping.v1", "ctrip.android.view", "com.MobileTicket", "com.zhiliaoapp.musically",
            "com.ifeng.news", "com.jifen.qukan",
        )
        for (pkg in packages) {
            assertTrue("缺少包名对应条目: $pkg", javaHash(pkg).toString() in keys)
        }
    }

    @Test fun keysAreUnique() {
        val all = rows().flatMap { row -> row.keys().asSequence().toList() }
        assertEquals("哈希键有重复", all.size, all.toSet().size)
    }
}
