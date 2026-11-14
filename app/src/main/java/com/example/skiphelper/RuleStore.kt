package com.example.skiphelper

import android.content.Context
import org.json.JSONArray

/**
 * 规则与开关的持久化。
 *
 * 用 SharedPreferences + org.json，不引第三方库：这个应用要读的是全系统窗口，
 * 依赖越少越好审计。
 *
 * 键名尽量与李跳跳原版对齐（is_main_switch_on / hide_toast / filter_invisible_nodes /
 * filter_young_popups / whitelist），方便对照两边的行为。
 */
class RuleStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** 关键词，命中任意一个即视为跳过按钮 */
    var keywords: List<String>
        get() = readList(KEY_KEYWORDS, DEFAULT_KEYWORDS)
        set(value) = writeList(KEY_KEYWORDS, value)

    /** 排除词，命中则放弃——宁可漏点，不可错点 */
    var excludes: List<String>
        get() = readList(KEY_EXCLUDES, DEFAULT_EXCLUDES)
        set(value) = writeList(KEY_EXCLUDES, value)

    /** 白名单：完全不接管的应用（默认预置了原版那份系统应用/支付类清单） */
    var whitelist: List<String>
        get() = readList(KEY_WHITELIST, emptyList())
        set(value) = writeList(KEY_WHITELIST, value)

    fun isWhitelisted(pkg: String): Boolean = pkg in whitelist

    fun setWhitelisted(pkg: String, on: Boolean) {
        val cur = whitelist.toMutableSet()
        if (on) cur.add(pkg) else cur.remove(pkg)
        whitelist = cur.toList()
    }

    /** 临时关掉整个服务之外的全应用白名单（原版 whitelist_all_apps） */
    var whitelistAllApps: Boolean
        get() = prefs.getBoolean(KEY_WL_ALL, false)
        set(value) = prefs.edit().putBoolean(KEY_WL_ALL, value).apply()

    /** 跳过成功是否弹提示 */
    var tipEnabled: Boolean
        get() = prefs.getBoolean(KEY_TIP_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_TIP_ENABLED, value).apply()

    /** 提示文案，可自定义 */
    var tipText: String
        get() = prefs.getString(KEY_TIP_TEXT, null) ?: DEFAULT_TIP_TEXT
        set(value) = prefs.edit().putString(KEY_TIP_TEXT, value).apply()

    /** 提示停留时长（毫秒）。默认 700：够看清，又不挡住广告页之后的操作。 */
    var tipDurationMs: Int
        get() = prefs.getInt(KEY_TIP_DURATION, DEFAULT_TIP_DURATION_MS).coerceIn(300, 3000)
        set(value) = prefs.edit().putInt(KEY_TIP_DURATION, value.coerceIn(300, 3000)).apply()

    /** 详细日志：每次扫描都打行，排障用，默认关（避免刷屏） */
    var verboseLog: Boolean
        get() = prefs.getBoolean("verbose_log", false)
        set(value) = prefs.edit().putBoolean("verbose_log", value).apply()

    /** 全局规则（对所有应用生效）是否启用 */
    var globalEnabled: Boolean
        get() = prefs.getBoolean(KEY_GLOBAL_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_GLOBAL_ENABLED, value).apply()

    /** 额外延迟（毫秒），叠加在规则自带的 delay 之上 */
    var delayMs: Int
        get() = prefs.getInt(KEY_DELAY, 0)
        set(value) = prefs.edit().putInt(KEY_DELAY, value.coerceIn(0, MAX_DELAY)).apply()

    /** filter_invisible_nodes：只看 isVisibleToUser 的节点 */
    var filterInvisible: Boolean
        get() = prefs.getBoolean(KEY_FILTER_INVISIBLE, true)
        set(value) = prefs.edit().putBoolean(KEY_FILTER_INVISIBLE, value).apply()

    /** filter_young_popups：不自动点「青少年模式」类弹窗 */
    var filterYoung: Boolean
        get() = prefs.getBoolean(KEY_FILTER_YOUNG, true)
        set(value) = prefs.edit().putBoolean(KEY_FILTER_YOUNG, value).apply()

    /** 小程序宿主包名：这些包名里找不到跳过按钮时可以直接返回 */
    var miniProgramHosts: List<String>
        get() = readList(KEY_MP_HOSTS, DEFAULT_MP_HOSTS)
        set(value) = writeList(KEY_MP_HOSTS, value)

    var miniProgramAutoBack: Boolean
        get() = prefs.getBoolean(KEY_MP_AUTO_BACK, false)
        set(value) = prefs.edit().putBoolean(KEY_MP_AUTO_BACK, value).apply()

    fun recordHit(pkg: String) {
        val json = prefs.getString(KEY_STATS, null) ?: "{}"
        val obj = try { org.json.JSONObject(json) } catch (e: Exception) { org.json.JSONObject() }
        obj.put(pkg, obj.optInt(pkg, 0) + 1)
        prefs.edit().putString(KEY_STATS, obj.toString()).apply()
    }

    fun totalHits(): Int {
        val json = prefs.getString(KEY_STATS, null) ?: return 0
        return try {
            val obj = org.json.JSONObject(json)
            obj.keys().asSequence().sumOf { obj.optInt(it, 0) }
        } catch (e: Exception) { 0 }
    }

    /**
     * 首次运行把原版那份白名单写进去。
     *
     * 只做一次：用户之后把某个应用从白名单里去掉，不会被下次启动又塞回来。
     */
    fun seedDefaultsOnce() {
        if (prefs.getBoolean(KEY_SEEDED, false)) return
        val existing = whitelist.toMutableSet()
        existing.addAll(DEFAULT_WHITELIST)
        whitelist = existing.toList()
        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
    }

    private fun readList(key: String, fallback: List<String>): List<String> {
        val raw = prefs.getString(key, null) ?: return fallback
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) { fallback }
    }

    private fun writeList(key: String, values: List<String>) {
        prefs.edit().putString(key, JSONArray(values).toString()).apply()
    }

    companion object {
        private const val PREFS = "skip_helper"
        private const val KEY_ENABLED = "is_main_switch_on"
        private const val KEY_KEYWORDS = "keywords"
        private const val KEY_EXCLUDES = "excludes"
        private const val KEY_WHITELIST = "whitelist"
        private const val KEY_WL_ALL = "whitelist_all_apps"
        private const val KEY_STATS = "stats"
        private const val KEY_TIP_ENABLED = "hide_toast"
        private const val KEY_TIP_TEXT = "tip_text"
        private const val KEY_TIP_DURATION = "tip_duration_ms"
        const val DEFAULT_TIP_DURATION_MS = 700
        private const val KEY_GLOBAL_ENABLED = "global_rules_enabled"
        private const val KEY_DELAY = "delay"
        private const val KEY_FILTER_INVISIBLE = "filter_invisible_nodes"
        private const val KEY_FILTER_YOUNG = "filter_young_popups"
        private const val KEY_MP_HOSTS = "mini_program_hosts"
        private const val KEY_MP_AUTO_BACK = "mini_program_auto_back"
        private const val KEY_SEEDED = "seeded_defaults"

        const val MAX_DELAY = 3000

        /** 通用跳过词。各 App 的具体规则由用户自建，不内置某家的私有规则库。 */
        val DEFAULT_KEYWORDS = listOf("跳过", "跳过广告", "关闭广告", "Skip", "Skip Ad", "关闭")
        val DEFAULT_EXCLUDES = listOf(
            "跳过登录", "跳过支付", "跳过验证",
            // 「累计跳过 8 次」「已跳过 3 条」这类是统计文案，不是按钮
            "累计跳过", "已跳过", "跳过次数",
        )

        /** 小程序宿主：微信、QQ。里面的开屏广告多半没有可点按钮，只能靠返回兜底。 */
        val DEFAULT_MP_HOSTS = listOf("com.tencent.mm", "com.tencent.mobileqq")

        /** 默认提示文案，可在界面上改成任意文字 */
        const val DEFAULT_TIP_TEXT = "已跳过"

        /**
         * 原版 res/values/arrays.xml 里的 whitelist（108 个包名）：
         * 相机、时钟、文件管理、支付、视频播放器等不该被自动操作的应用。
         */
        val DEFAULT_WHITELIST = listOf(
            "com.spotify.music", "com.netflix.mediaclient", "com.huawei.search",
            "com.sec.android.app.voicenote",
            "com.eg.android.AlipayGphone", "com.litiaotiao.app", "cn.litiaotiao.app",
            "com.quark.browser", "com.google.android.gm", "com.hicorenational.antifraud",
            "com.kapp.youtube.final", "com.android.calendar", "com.android.dialer",
            "com.android.mms", "com.android.contacts", "com.android.deskclock",
            "com.android.alarmclock", "com.android.camera", "com.android.camera2",
            "com.android.gallery3d", "com.android.notes", "com.android.filemanager",
            "com.android.fileexplorer", "com.android.soundrecorder",
            "com.android.providers.downloads.ui", "com.mfashiongallery.emag", "com.miui.gallery",
            "com.miui.voiceassist", "com.miui.securitycenter", "com.huawei.deskclock",
            "com.huawei.camera", "com.huawei.photos", "com.huawei.contacts",
            "com.huawei.HwMultiScreenShot", "com.huawei.vassistant", "com.huawei.meetime",
            "com.huawei.hidisk", "com.huawei.notepad", "com.huawei.systemmanager",
            "com.huawei.soundrecorder", "com.android.BBKClock", "com.vivo.gallery",
            "com.vivo.agent", "com.vivo.easyshare", "com.android.bbksoundrecorder",
            "com.iqoo.secure", "com.vivo.permissionmanager", "com.heytap.speechassist",
            "com.oppo.camera", "com.coloros.alarmclock", "com.coloros.filemanager",
            "com.coloros.gallery3d", "com.coloros.safecenter", "com.oppo.safe",
            "com.coloros.oppoguardelf", "com.oneplus.deskclock", "com.oneplus.gallery",
            "com.oneplus.filemanager", "com.oneplus.mms", "com.oneplus.contacts",
            "com.oneplus.camera", "com.oneplus.brickmode", "com.oneplus.security",
            "com.samsung.android.messaging", "com.samsung.android.bixby.agent",
            "com.sec.android.gallery3d", "com.samsung.accessibility",
            "com.samsung.android.app.contacts", "com.sec.android.app.clockpackage",
            "com.samsung.android.dialer", "com.sec.android.app.camera",
            "com.samsung.android.sm_cn", "com.samsung.android.sm", "com.meizu.filemanager",
            "com.meizu.media.camera", "com.meizu.media.gallery", "com.meizu.voiceassistant",
            "com.meizu.safe", "com.zte.camera", "zte.com.cn.filer", "zte.com.cn.alarmclock",
            "com.zte.halo.app", "com.zte.heartyservice", "com.smartisanos.clock",
            "com.smartisanos.notes", "com.smartisanos.filemanager", "com.smartisanos.quicksearch",
            "com.smartisanos.sara", "com.smartisanos.recorder", "com.smartisanos.sidebar",
            "com.smartisanos.screenrecorder", "com.smartisanos.security", "com.zui.contacts",
            "com.zui.gallery", "com.zui.camera", "com.zui.filemanager", "com.zui.deskclock",
            "com.zui.mms", "com.zui.safecenter", "com.lenovo.security",
            "cn.nubia.deskclock.preset", "cn.nubia.gallery3d", "cn.nubia.voiceassist",
            "cn.nubia.diyaod", "cn.nubia.myfile", "cn.nubia.mms", "cn.nubia.contacts",
            "cn.nubia.security2",
        )
    }
}
