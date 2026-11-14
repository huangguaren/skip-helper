package com.example.skiphelper.engine

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

/**
 * 规则文件导入。
 *
 * 支持社区规则格式：`[{"<包名哈希>": "{\"keywords\":[...],\"popup_rules\":[...], 参数...}"}]`，
 * 也容忍多个数组用逗号拼接、数组提前闭合后接残片这类损坏情况。
 *
 * 只做解析与哈希还原；规则内容由用户自己的文件提供，应用不内置。
 */
object RuleImport {

    data class Result(val matchedApps: Int, val popupCount: Int, val keywordCount: Int, val unresolved: Int)

    private const val FILE_NAME = "rules_v2.json"
    private const val BUILTIN_ASSET = "AllRules.json"

    /** 市场规则包（由 tools/rules/build_market_rules.py 生成，见 tools/rules/README.md）。 */
    const val MARKET_ASSET = "market-rules-2026.11.json"

    /**
     * 规则缓存。
     *
     * 服务每次扫描都会取规则（最快每 120ms 一次），每次都读盘 + 解析整个 JSON
     * 会直接卡住主线程。这里按「文件最后修改时间 + 长度」判断是否失效，
     * 保存时主动清空缓存。
     */
    private var cache: Map<String, AppRuleSet>? = null
    private var cacheStamp: Long = -1L

    @Synchronized
    fun load(ctx: Context): Map<String, AppRuleSet> {
        val f = File(ctx.filesDir, FILE_NAME)
        if (!f.exists()) {
            cache = emptyMap()
            cacheStamp = -1L
            return emptyMap()
        }
        val stamp = f.lastModified() * 31L + f.length()
        cache?.let { if (stamp == cacheStamp) return it }
        val parsed = try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                parseStored(o)
            }.associateBy { it.pkg }
        } catch (e: Exception) {
            emptyMap()
        }
        cache = parsed
        cacheStamp = stamp
        return parsed
    }

    @Synchronized
    fun save(ctx: Context, rules: Collection<AppRuleSet>) {
        val arr = JSONArray()
        for (r in rules) arr.put(toStored(r))
        File(ctx.filesDir, FILE_NAME).writeText(arr.toString())
        cache = null
        cacheStamp = -1L
    }

    fun import(ctx: Context, uri: Uri): Result {
        val text = ctx.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: return Result(0, 0, 0, 0)
        return importText(ctx, text)
    }

    /**
     * 导入内置规则（assets/AllRules.json）。
     *
     * 规则库来自社区（AGPL 授权），这里只在用户点「导入内置规则」时读取一次，
     * 应用不会自动启用、也不联网更新。
     */
    fun importBuiltin(ctx: Context, assetName: String = BUILTIN_ASSET): Result {
        val text = runCatching {
            ctx.assets.open(assetName).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return Result(0, 0, 0, 0)
        return importText(ctx, text)
    }

    fun importText(ctx: Context, text: String): Result {
        val entries = parseEntries(text)
        val hashToPkg = buildHashIndex(ctx)

        val merged = HashMap<String, AppRuleSet>()
        var unresolved = 0

        for (entry in entries) {
            val key = entry.keys().asSequence().firstOrNull() ?: continue
            val hash = key.toIntOrNull() ?: continue
            val pkg = hashToPkg[hash]
            val inner = runCatching { JSONObject(entry.optString(key)) }.getOrNull() ?: continue

            if (pkg == null) {
                if (inner.has("popup_rules") || inner.has("keywords")) unresolved++
                continue
            }

            val parsed = fromOfficialJson(pkg, inner)
            if (parsed.isEmpty()) continue
            val prev = merged[pkg]
            merged[pkg] = if (prev == null) parsed else prev.merge(parsed)
        }

        val existing = load(ctx).toMutableMap()
        existing.putAll(merged)
        save(ctx, existing.values)

        return Result(
            matchedApps = merged.size,
            popupCount = merged.values.sumOf { it.popupRules.size },
            keywordCount = merged.values.sumOf { it.keywords.size },
            unresolved = unresolved,
        )
    }

    /** 解析官方格式的内层 JSON（纯解析，单测覆盖在 RuleJsonTest）。 */
    fun fromOfficialJson(pkg: String, o: JSONObject): AppRuleSet = RuleJson.fromOfficialJson(pkg, o)
    // ---------------------------------------------------------------- 全局规则
    /** 全局规则存在同一个规则文件里，包名用 GLOBAL_RULE_PKG。 */
    fun loadGlobal(ctx: Context): AppRuleSet? = load(ctx)[GLOBAL_RULE_PKG]

    fun saveGlobal(ctx: Context, rule: AppRuleSet) {
        val all = load(ctx).toMutableMap()
        val merged = rule.copy(pkg = GLOBAL_RULE_PKG)
        if (merged.isEmpty()) all.remove(GLOBAL_RULE_PKG) else all[GLOBAL_RULE_PKG] = merged
        save(ctx, all.values)
    }

    // ---------------------------------------------------------------- 存储
    private fun toStored(r: AppRuleSet): JSONObject = RuleJson.toStored(r)

    private fun parseStored(o: JSONObject): AppRuleSet? = RuleJson.parseStored(o)
    private fun parseEntries(text: String): List<JSONObject> {
        val out = ArrayList<JSONObject>(4096)
        var i = 0
        val n = text.length
        while (i < n) {
            while (i < n && (text[i].isWhitespace() || text[i] == ',')) i++
            if (i >= n) break
            if (text[i] != '[' && text[i] != '{') {
                val j = text.indexOf("[{", i); if (j < 0) break; i = j; continue
            }
            val end = balancedEnd(text, i) ?: break
            val value = runCatching { JSONTokener(text.substring(i, end + 1)).nextValue() }.getOrNull()
            if (value == null) {
                val j = text.indexOf("[{", i + 1); if (j < 0) break; i = j; continue
            }
            when (value) {
                is JSONArray -> for (k in 0 until value.length()) value.optJSONObject(k)?.let(out::add)
                is JSONObject -> out.add(value)
                else -> {}
            }
            i = end + 1
        }
        return out
    }

    private fun balancedEnd(text: String, start: Int): Int? {
        var depth = 0; var inStr = false; var esc = false
        for (i in start until text.length) {
            val c = text[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else when (c) {
                '"' -> inStr = true
                '[', '{' -> depth++
                ']', '}' -> { depth--; if (depth == 0) return i }
            }
        }
        return null
    }

    private fun buildHashIndex(ctx: Context): Map<Int, String> {
        val map = HashMap<Int, String>(512)
        @Suppress("DEPRECATION")
        for (info in ctx.packageManager.getInstalledPackages(0)) {
            map[javaHash(info.packageName)] = info.packageName
        }
        return map
    }

    fun javaHash(s: String): Int {
        var h = 0
        for (ch in s) h = 31 * h + ch.code
        return h
    }
}

