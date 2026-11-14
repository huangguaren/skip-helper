package com.example.skiphelper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.skiphelper.engine.AppRuleSet
import com.example.skiphelper.engine.EvalConfig
import com.example.skiphelper.engine.GLOBAL_RULE_PKG
import com.example.skiphelper.engine.PlannedAction
import com.example.skiphelper.engine.RuleEvaluator
import com.example.skiphelper.engine.RuleImport

/**
 * 无障碍服务：监听窗口变化，按规则识别并点击。
 *
 * 四道节流/闸门，缺一个都会出问题：
 *  - 事件去抖 120ms：开屏广告的按钮往往在 window state changed 之后才渲染
 *  - 点击冷却 1s：防止把倒计时广告连续点穿
 *  - 同控件去重：同一按钮在多个事件里重复出现时只点一次（有资源 id 时按 id 认）
 *  - 系统界面/白名单过滤：桌面、输入法、相机、支付这些一律不碰
 */
class SkipService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var pendingScan: Runnable? = null
    private var lastClickAt = 0L
    private var currentPackage: String? = null
    private val recentlyClicked = HashMap<String, Long>()
    private var cachedHome: String? = null
    private var lastMiniProgramAt = 0L
    private var lastMiniProgramClass: String? = null
    private var lastMiniProgramBackAt = 0L
    private var mpEmptyScans = 0
    private var lastActTrace = ""
    private var lastEventPkg: String? = null
    private var lastEventSig = ""
    private var pkgEnterAt = 0L
    private var lastActivityClass: String? = null
    private var blindTapsDone = 0
    private var blindSwiped = false
    private var blindRetries = 0
    private var lastBlindAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        Log.i(TAG, "service connected, sdk=${android.os.Build.VERSION.SDK_INT}")
        try {
            // 首启写白名单放到子线程：个别 ROM（MIUI / HyperOS）在绑定阶段对主线程有超时限制，
            // 这里同步写 prefs 一旦慢一点就可能被判定为 ANR 并自动关掉服务。
            Thread {
                runCatching { RuleStore(applicationContext).seedDefaultsOnce() }
                    .onFailure { Log.e(TAG, "seed defaults failed", it) }
            }.start()
        } catch (t: Throwable) {
            Log.e(TAG, "onServiceConnected post-init failed", t)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        SkipTip.release()
        connected = false
        pendingScan?.let { handler.removeCallbacks(it) }
        Log.i(TAG, "service destroyed")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            handleEvent(event)
        } catch (t: Throwable) {
            Log.e(TAG, "onAccessibilityEvent failed", t)
        }
    }

    private fun handleEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (isIgnoredPackage(pkg)) return

        // 小程序上下文：微信/QQ 的小程序都跑在固定的一批 Activity 里。
        // 记下进入时刻，后续「找不到按钮就返回」的兜底只在这个时间窗内生效，
        // 否则用户在聊天/浏览页里也会被返回。
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val cls = event.className?.toString().orEmpty()
            if (cls.isNotEmpty() && cls != lastActivityClass) {
                // 换 Activity（含冷启动、从最近任务重进开屏页）都算一次新的「进入」，
                // 否则重进同一个应用时盲点窗口早就过期了。
                lastActivityClass = cls
                pkgEnterAt = SystemClock.uptimeMillis()
                blindTapsDone = 0
            }
            // 诊断用：把指定包名的 Activity 逐个打出来（默认只有支付宝），
            // 用来确认广告跑在哪个容器里（小程序 Nebula / H5 / 原生 Activity）。
            if (cls.isNotEmpty() && pkg in TRACE_PACKAGES) {
                val tag = "$pkg/$cls"
                if (tag != lastActTrace) {
                    lastActTrace = tag
                    Log.i(TAG, "act: $tag")
                }
            }
            if (isMiniProgramActivity(cls)) {
                lastMiniProgramAt = SystemClock.uptimeMillis()
                mpEmptyScans = 0
                if (cls != lastMiniProgramClass) {
                    lastMiniProgramClass = cls
                    Log.i(TAG, "mini-program context: $pkg/$cls")
                }
            }
        }

        val store = RuleStore(this)
        if (!store.isEnabled) return
        if (store.whitelistAllApps) return
        if (store.isWhitelisted(pkg)) return
        if (SystemClock.uptimeMillis() - lastClickAt < CLICK_COOLDOWN_MS) return

        if (pkg != lastEventPkg) {
            lastEventPkg = pkg
            pkgEnterAt = SystemClock.uptimeMillis()
        }
        currentPackage = pkg
        scheduleScan()
    }

    /** Activity 名里出现这些片段就当成「进了小程序」。 */
    private fun isMiniProgramActivity(cls: String): Boolean {
        if (cls.isEmpty()) return false
        val lower = cls.lowercase()
        return MINI_PROGRAM_CLASS_HINTS.any { lower.contains(it) }
    }

    private fun scheduleScan() {
        pendingScan?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { scan() }
        pendingScan = runnable
        handler.postDelayed(runnable, SCAN_DELAY_MS)
    }

    private fun scan() {
        try {
            scanInner()
        } catch (t: Throwable) {
            Log.e(TAG, "scan failed", t)
        }
    }

    private fun scanInner() {
        val pkg = currentPackage ?: return
        val store = RuleStore(this)
        if (!store.isEnabled || store.whitelistAllApps) return
        if (store.isWhitelisted(pkg)) return

        // 原版语义：主来源是 rootInActiveWindow，windows 只用来补悬浮窗广告。
        // 不再按「包名等于前台应用」硬筛——HyperOS 上枚举回来的多半是系统窗口，
        // 一筛就把主路径筛空了（这也是「什么都跳不过」的直接原因）。
        val rawRoots = collectRoots()
        val roots = rawRoots.filter { r ->
            val p = r.packageName?.toString() ?: return@filter true
            p != packageName && p !in IGNORED_PACKAGES && p != homePackage()
        }

        val dm = resources.displayMetrics
        // displayMetrics 在服务里可能是「去掉状态栏/导航栏」的可用区高度（实测 2484 vs 屏幕 2670），
        // 盲点用的是绝对屏幕坐标，必须用窗口全尺寸，否则纵向会偏 100 多像素。
        val screen = fullScreenSize()
        val rules = RuleImport.load(this)
        val app = rules[pkg]?.takeIf { it.enabled && !it.isEmpty() }
        val global = if (store.globalEnabled) {
            rules[GLOBAL_RULE_PKG]?.takeIf { it.enabled && !it.isEmpty() }
                ?: AppRuleSet(pkg = GLOBAL_RULE_PKG, keywords = store.keywords)
        } else null

        val enteredAgo = SystemClock.uptimeMillis() - pkgEnterAt
        
        if (enteredAgo < 60) {
            blindTapsDone = 0
            blindRetries = 0
        }
        if (enteredAgo < BLIND_TAP_WINDOW_MS || store.verboseLog) {
            val activePkg = try { rootInActiveWindow?.packageName?.toString() } catch (e: Exception) { null }
            Log.i(TAG, "scan pkg=$pkg active=$activePkg raw=${rawRoots.size} own=${roots.size} " +
                "pkgs=${rawRoots.map { it.packageName?.toString() }} rule=${app != null} blind=${app?.blindX},${app?.blindY}")
        }

        // 开屏阶段窗口经常还没挂上（系统日志里的 wait for adding window timeout 就是这情况），
        // 事件驱动的扫描只会跑一次。这里补一个自旋：没枚举到自己的窗口就 350ms 后再扫一次，
        // 最多 10 次（≈3.5s），足够覆盖广告渲染。
        if (enteredAgo < BLIND_TAP_WINDOW_MS && roots.isEmpty() && blindRetries < 10) {
            blindRetries++
            handler.postDelayed({ scan() }, 350)
        }

        val cfg = EvalConfig(
            excludes = store.excludes,
            filterInvisible = store.filterInvisible,
            filterYoung = store.filterYoung,
        )
        val actions = RuleEvaluator.evaluateCombined(
            app, global, roots, dm.widthPixels, dm.heightPixels, cfg,
        ).toMutableList()

        // 开屏盲点：整屏自绘、无障碍树里没有任何文字的广告（咪咕视频等）。
        // 按固定时间槽点 3 次（0.6s / 1.4s / 2.2s），不依赖窗口是否已枚举到——
        // 坐标是屏幕绝对位置，窗口没挂上也照样能点，早点点中才有意义。
        val hasBlind = app != null && app.blindX in 0..100 && app.blindY in 0..100
        if (hasBlind && enteredAgo < BLIND_TAP_WINDOW_MS) {
            val slot = BLIND_TAP_SLOTS_MS.getOrNull(blindTapsDone)
            val nowMs = SystemClock.uptimeMillis()
            if (actions.isEmpty() && slot != null && enteredAgo >= slot && nowMs - lastBlindAt > 200) {
                val x = dm.widthPixels * app!!.blindX / 100
                val y = dm.heightPixels * app.blindY / 100
                blindTapsDone++
                lastBlindAt = nowMs
                Log.i(TAG, "blind tap: ${app.blindX}%,${app.blindY}% -> ($x,$y) pkg=$pkg own=${roots.size} slot=$blindTapsDone/${BLIND_TAP_SLOTS_MS.size}")
                actions.add(PlannedAction.Bounds(intArrayOf(x - 2, y - 2, x + 2, y + 2), null, "开屏盲点#$blindTapsDone"))
            }
            // 三个时间槽都点过还没命中，再补一次「向上滑动」：
            // 京东这类互动开屏底部标着「向上滑动」，滑动通常比点角落更被广告层接受。
            if (actions.isEmpty() && !blindSwiped && enteredAgo >= BLIND_SWIPE_AT_MS) {
                blindSwiped = true
                Log.i(TAG, "blind swipe up pkg=$pkg")
                actions.add(PlannedAction.Swipe("开屏盲滑"))
            }
            // 时间窗内持续自旋，直到三个槽位都点过：不受计数器影响，窗口枚举成功与否都要继续
            if (blindTapsDone < BLIND_TAP_SLOTS_MS.size) {
                handler.postDelayed({ scan() }, 250)
            }
        }

        // 小程序窗口里打一行扫描摘要：帮判断「是没按钮，还是有按钮但没命中规则」。
        if (SystemClock.uptimeMillis() - lastMiniProgramAt < MINI_PROGRAM_WINDOW_MS) {
            val texts = collectTexts(roots, MP_INTERESTING_WORDS, 6)
            Log.i(TAG, "mp-scan roots=${roots.size} actions=${actions.size} autoBack=${store.miniProgramAutoBack} host=${store.miniProgramHosts.contains(pkg)} texts=$texts")
        }

        // 小程序开屏广告兜底：进入小程序后，连续若干次扫描都找不到可点目标，才返回。
        // 三重限制缺一不可——时间窗（避免聊天页被点出去）、连续空扫描（避免广告按钮还没渲染就返回）、
        // 冷却（避免在小程序里反复进出）。
        val now = SystemClock.uptimeMillis()
        val inMiniProgram = now - lastMiniProgramAt < MINI_PROGRAM_WINDOW_MS
        val hostOk = pkg in store.miniProgramHosts
        val nothingToClick = actions.none { it !is PlannedAction.None }
        if (inMiniProgram && nothingToClick) mpEmptyScans++ else if (!inMiniProgram) mpEmptyScans = 0

        val enoughWait = now - lastMiniProgramAt >= MINI_PROGRAM_MIN_WAIT_MS
        if (nothingToClick && store.miniProgramAutoBack && hostOk && inMiniProgram &&
            enoughWait && mpEmptyScans >= MINI_PROGRAM_EMPTY_SCANS &&
            now - lastMiniProgramBackAt > MINI_PROGRAM_BACK_COOLDOWN_MS
        ) {
            lastMiniProgramBackAt = now
            mpEmptyScans = 0
            Log.i(TAG, "mini-program ad: ${MINI_PROGRAM_EMPTY_SCANS} 次空扫描后自动返回（$pkg）")
            actions.add(PlannedAction.Back("小程序开屏广告：未找到跳过按钮，自动返回"))
        }

        if (actions.isEmpty()) return

        val delay = maxOf(app?.delayMs ?: 0, 0) + store.delayMs
        if (delay > 0) {
            handler.postDelayed({ execute(actions, pkg, store) }, delay.toLong())
        } else {
            execute(actions, pkg, store)
        }
    }

    /** 依次执行动作，成功一个就返回 true（同一轮不做多个动作，避免误连点）。 */
    private fun execute(actions: List<PlannedAction>, pkg: String, store: RuleStore): Boolean {
        for (action in actions) {
            if (action is PlannedAction.None) continue
            val key = signature(pkg, action)
            val last = recentlyClicked[key] ?: 0L
            if (SystemClock.uptimeMillis() - last < DEDUPE_MS) continue

            val ok = when (action) {
                is PlannedAction.Node -> {
                    @Suppress("UNCHECKED_CAST")
                    val node = action.node as? AccessibilityNodeInfo
                    node != null && clickNode(node)
                }
                is PlannedAction.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
                is PlannedAction.Swipe -> swipeUp()
                is PlannedAction.Bounds -> tapBounds(action)
                is PlannedAction.None -> false
            }
            if (ok) {
                recentlyClicked[key] = SystemClock.uptimeMillis()
                lastClickAt = SystemClock.uptimeMillis()
                store.recordHit(pkg)
                if (store.tipEnabled) SkipTip.show(this, store.tipText, store.tipDurationMs.toLong())
                Log.i(TAG, "executed: ${describe(action)}")
                if (recentlyClicked.size > MAX_SIGNATURES) recentlyClicked.clear()
                return true
            }
        }
        return false
    }

    private fun describe(a: PlannedAction): String = when (a) {
        is PlannedAction.Node -> "node:${a.label}"
        is PlannedAction.Back -> "back"
        is PlannedAction.Swipe -> "swipe"
        is PlannedAction.Bounds -> "bounds:${a.rect.joinToString(",")}"
        is PlannedAction.None -> "none:${a.label}"
    }

    /**
     * 同一个控件的去重身份。
     *
     * 有资源 id 时按 id 认：倒计时动画里按钮坐标会漂移，坐标算进去会重复点击。
     */
    private fun signature(pkg: String, a: PlannedAction): String = when (a) {
        is PlannedAction.Node -> "$pkg|node|${a.label}|${a.reason}"
        is PlannedAction.Back -> "$pkg|back"
        is PlannedAction.Swipe -> "$pkg|swipe"
        is PlannedAction.Bounds -> "$pkg|bounds|${a.rect.joinToString(",")}"
        is PlannedAction.None -> "$pkg|none|${a.label}"
    }

    /** 点击节点：自身不可点就向上找可点击祖先，再不行用坐标兜底。 */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops <= 3) {
            if (cur.isClickable && cur.isEnabled) {
                if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            cur = cur.parent
            hops++
        }
        val r = Rect().also { node.getBoundsInScreen(it) }
        return if (r.isEmpty) false else tap(r.exactCenterX(), r.exactCenterY())
    }

    /** 屏幕全尺寸（含状态栏/导航栏）。API 30 起用 currentWindowMetrics，老系统退回 displayMetrics。 */
    private fun fullScreenSize(): Pair<Int, Int> {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val wm = getSystemService(WindowManager::class.java)
                val b = wm?.currentWindowMetrics?.bounds
                if (b != null && b.width() > 0 && b.height() > 0) return b.width() to b.height()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "fullScreenSize failed", t)
        }
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    /** 从屏幕下部往上滑一段，用于「向上滑动」式的互动开屏。 */
    private fun swipeUp(): Boolean {
        val dm = resources.displayMetrics
        // displayMetrics 在服务里可能是「去掉状态栏/导航栏」的可用区高度（实测 2484 vs 屏幕 2670），
        // 盲点用的是绝对屏幕坐标，必须用窗口全尺寸，否则纵向会偏 100 多像素。
        val screen = fullScreenSize()
        val x = dm.widthPixels / 2f
        val fromY = dm.heightPixels * 0.78f
        val toY = dm.heightPixels * 0.35f
        val path = Path().apply { moveTo(x, fromY); lineTo(x, toY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 260))
            .build()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                Log.i(TAG, "swipe completed")
            }

            override fun onCancelled(g: GestureDescription?) {
                Log.w(TAG, "swipe cancelled")
            }
        }, null)
        Log.i(TAG, "dispatchGesture=$ok swipe @($x,$fromY)->($x,$toY)")
        return ok
    }

    private fun tapBounds(a: PlannedAction.Bounds): Boolean {
        val cx = (a.rect[0] + a.rect[2]) / 2f
        val cy = (a.rect[1] + a.rect[3]) / 2f
        return tap(cx, cy, a.reason)
    }

    /**
     * 派发一次点击手势，并把系统回调记下来。
     *
     * 只看 dispatchGesture 的返回值不够——它返回 true 只代表「请求被受理」，
     * 真正有没有注入到目标窗口要看 GestureResultCallback：被系统拦掉时回调
     * onCancelled，这也是判断 HyperOS 是否禁了无障碍手势的唯一可靠信号。
     */
    private fun tap(x: Float, y: Float, label: String = ""): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)   // 单点路径在个别 ROM 上会被丢弃，补一段零长度线段更稳
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 40))
            .build()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                Log.i(TAG, "gesture completed @($x,$y) $label")
            }

            override fun onCancelled(g: GestureDescription?) {
                Log.w(TAG, "gesture cancelled @($x,$y) $label")
            }
        }, null)
        Log.i(TAG, "dispatchGesture=$ok @($x,$y) $label")
        return ok
    }

    /**
     * 收集窗口根节点。顺序对齐原版：先 rootInActiveWindow，再补 windows 里的悬浮窗。
     * 原版只用 getRootInActiveWindow()，这条路最稳，不能被 windows 的枚举结果挤掉。
     */
    private fun collectRoots(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>(6)
        try {
            rootInActiveWindow?.let { roots.add(it) }
        } catch (e: Exception) {
        }
        try {
            for (window in windows) window.root?.let { roots.add(it) }
        } catch (e: Exception) {
            // 部分 ROM 在服务未完全就绪时会抛异常
        }
        // 自己的窗口绝不参与匹配（否则会点到自己界面上的「累计跳过 N 次」）。
        roots.removeAll { root -> root.packageName?.toString() == packageName }
        return roots
    }

    /**
     * 采集当前窗口里含关键字的文本，用于小程序广告的诊断日志。
     * 只读节点文字，不截图、不落盘。
     */
    private fun collectTexts(
        roots: List<AccessibilityNodeInfo>,
        words: List<String>,
        limit: Int,
    ): List<String> {
        val out = ArrayList<String>(limit)
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 20 || out.size >= limit) return
            val t = n.text?.toString()?.takeIf { it.isNotBlank() }
                ?: n.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            if (t != null && t.length <= 20 && words.any { t.contains(it, ignoreCase = true) }) {
                out.add(t)
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        roots.forEach { walk(it, 0) }
        return out
    }

    private fun isIgnoredPackage(pkg: String): Boolean {
        if (pkg.isEmpty() || pkg == packageName) return true
        if (pkg in IGNORED_PACKAGES) return true
        if (pkg == homePackage()) return true
        return IGNORED_PREFIXES.any { pkg.startsWith(it) }
    }

    /**
     * 当前默认桌面的包名。
     *
     * 不能只写死 `com.android.launcher3`：HyperOS 的桌面是 `com.miui.home`，
     * 其它 ROM 还有 `com.miui.newhome`、`com.huawei.android.launcher` 等等。
     * 桌面上同样有「跳过/关闭」字样（负一屏卡片、推荐位），不排掉就会误点。
     */
    private fun homePackage(): String? {
        cachedHome?.let { return it }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = try {
            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        } catch (t: Throwable) {
            null
        }
        cachedHome = resolved
        return resolved
    }

    companion object {
        const val TAG = "SkipHelper"
        private const val SCAN_DELAY_MS = 120L
        private const val CLICK_COOLDOWN_MS = 1000L
        private const val DEDUPE_MS = 3000L
        private const val MINI_PROGRAM_WINDOW_MS = 8000L
        private const val BLIND_TAP_WINDOW_MS = 4000L
        /** 盲点时间槽：进入应用后按这些毫秒点一次（最多 3 次） */
        private val BLIND_TAP_SLOTS_MS = intArrayOf(600, 1400, 2200)
        private const val BLIND_SWIPE_AT_MS = 2600L
        private const val MINI_PROGRAM_MIN_WAIT_MS = 1500L
        private const val MINI_PROGRAM_EMPTY_SCANS = 3
        private const val MINI_PROGRAM_BACK_COOLDOWN_MS = 10000L

        /**
         * 小程序宿主里跑广告的 Activity 特征。
         * 微信：com.tencent.mm.plugin.appbrand.ui.AppBrandUI00..07 / AppBrandLauncherUI
         * QQ：com.tencent.mobileqq.activity.miniaio.*（QQ 小程序改名「小程序」后走 MiniApp）
         */
        private val MP_INTERESTING_WORDS = listOf("跳过", "广告", "关闭", "跳过广告")

        /** 诊断时跟踪 Activity 的包名：支付宝的扫码骑车广告就在它里面。 */
        private val TRACE_PACKAGES = setOf("com.eg.android.AlipayGphone")

        private val MINI_PROGRAM_CLASS_HINTS = listOf(
            "appbrand", "appbrandlauncher", "miniapp", "miniaio", "mini_program", "nebula"
        )
        private const val MAX_SIGNATURES = 200

        /** 系统界面 / 桌面 / 输入法不参与，避免误点系统 UI。 */
        val IGNORED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.inputmethod.latin",
            // 各家桌面：HyperOS / MIUI / EMUI / ColorOS / OriginOS / OneUI / Flyme / 原生
            "com.miui.home",
            "com.miui.newhome",
            "com.android.launcher",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.huawei.android.launcher",
            "com.hihonor.android.launcher",
            "com.oppo.launcher",
            "com.coloros.launcher",
            "com.vivo.launcher",
            "com.bbk.launcher2",
            "com.sec.android.app.launcher",
            "com.meizu.flyme.launcher",
            "com.teslacoilsw.launcher",
            // 李跳跳原版/衍生版：首页有「累计跳过 N 次」这类文本，会被「跳过」关键词命中误点
            "hello.litiaotiao.app",
            "cn.litiaotiao.app",
            "com.litiaotiao.app",
        )
        val IGNORED_PREFIXES = listOf(
            "com.android.inputmethod.",
            "com.google.android.inputmethod.",
        )

        @Volatile
        var connected: Boolean = false
            private set
    }
}
