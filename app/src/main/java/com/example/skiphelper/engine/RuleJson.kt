package com.example.skiphelper.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * 规则 JSON 的纯解析层：不碰 Android API，方便 JVM 单测。
 *
 * 内层格式与官方规则文件一致：
 * `{"keywords":[],"keywords_append":[],"popup_rules":[{"id","action","times"}],
 *   "click_way_popup":1,"search_times_popup":5,"delay":200,"delay_popup":300,
 *   "unite_popup_rules":true,"reverse_traversal":true,"ltt_service":true}`
 */
object RuleJson {

    fun fromOfficialJson(pkg: String, o: JSONObject): AppRuleSet {
        val keywords = ArrayList<String>()
        o.optJSONArray("keywords")?.let {
            for (i in 0 until it.length()) it.optString(i).takeIf(String::isNotEmpty)?.let(keywords::add)
        }
        o.optJSONArray("keywords_append")?.let {
            for (i in 0 until it.length()) it.optString(i).takeIf(String::isNotEmpty)?.let(keywords::add)
        }

        val popups = ArrayList<PopupRule>()
        o.optJSONArray("popup_rules")?.let { arr ->
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val id = r.optString("id")
                if (id.isEmpty()) continue
                val action = r.optString("action", "")
                val times = if (r.has("times")) r.optInt("times", 1) else 1
                popups.add(PopupRule(id, action, times))
            }
        }

        return AppRuleSet(
            pkg = pkg,
            enabled = optBool(o, "ltt_service", true),
            keywords = keywords.distinct(),
            popupRules = popups,
            forceClick = optBool(o, "click_way_popup", false),
            searchTimes = optInt(o, "search_times_popup", 1),
            uniteRules = optBool(o, "unite_popup_rules", false),
            reverseTraversal = optBool(o, "reverse_traversal", false),
            delayMs = maxOf(optInt(o, "delay", 0), optInt(o, "delay_popup", 0)),
            blindX = optInt(o, "blind_tap_x", -1),
            blindY = optInt(o, "blind_tap_y", -1),
        )
    }

    fun toStored(r: AppRuleSet): JSONObject = JSONObject().apply {
        put("pkg", r.pkg)
        put("enabled", r.enabled)
        put("keywords", JSONArray(r.keywords))
        val arr = JSONArray()
        for (p in r.popupRules) arr.put(JSONObject().apply {
            put("id", p.id); put("action", p.action); put("times", p.times)
        })
        put("popup_rules", arr)
        put("click_way_popup", r.forceClick)
        put("search_times_popup", r.searchTimes)
        put("unite_popup_rules", r.uniteRules)
        put("reverse_traversal", r.reverseTraversal)
        put("delay", r.delayMs)
        if (r.blindX >= 0) put("blind_tap_x", r.blindX)
        if (r.blindY >= 0) put("blind_tap_y", r.blindY)
    }

    fun parseStored(o: JSONObject): AppRuleSet? {
        val pkg = o.optString("pkg").takeIf { it.isNotEmpty() } ?: return null
        val keywords = ArrayList<String>()
        o.optJSONArray("keywords")?.let { for (i in 0 until it.length()) keywords.add(it.optString(i)) }
        val popups = ArrayList<PopupRule>()
        o.optJSONArray("popup_rules")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val id = p.optString("id").takeIf { it.isNotEmpty() } ?: continue
                popups.add(PopupRule(id, p.optString("action", ""), p.optInt("times", 1)))
            }
        }
        return AppRuleSet(
            pkg = pkg,
            enabled = o.optBoolean("enabled", true),
            keywords = keywords,
            popupRules = popups,
            forceClick = o.optBoolean("click_way_popup", false),
            searchTimes = o.optInt("search_times_popup", 1),
            uniteRules = o.optBoolean("unite_popup_rules", false),
            reverseTraversal = o.optBoolean("reverse_traversal", false),
            delayMs = o.optInt("delay", 0),
            blindX = o.optInt("blind_tap_x", -1),
            blindY = o.optInt("blind_tap_y", -1),
        )
    }

    fun optBool(o: JSONObject, key: String, def: Boolean): Boolean = when {
        !o.has(key) -> def
        o.opt(key) is Boolean -> o.optBoolean(key, def)
        else -> o.optInt(key, if (def) 1 else 0) != 0
    }

    fun optInt(o: JSONObject, key: String, def: Int): Int =
        if (!o.has(key)) def else o.optInt(key, def)
}
