package com.example.skiphelper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.skiphelper.engine.GLOBAL_RULE_PKG
import com.example.skiphelper.engine.RuleImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RuleStore(this).seedDefaultsOnce()
        setContent {
            MaterialTheme {
                Surface { HomeScreen(this) }
            }
        }
    }
}

private data class AppEntry(val label: String, val pkg: String)

@Composable
private fun HomeScreen(activity: MainActivity) {
    val store = remember { RuleStore(activity) }
    var serviceOn by remember { mutableStateOf(isServiceEnabled(activity)) }
    var enabled by remember { mutableStateOf(store.isEnabled) }
    var hits by remember { mutableStateOf(store.totalHits()) }

    val keywords = remember { mutableStateListOf<String>().apply { addAll(store.keywords) } }
    var newKeyword by remember { mutableStateOf("") }

    val excludes = remember { mutableStateListOf<String>().apply { addAll(store.excludes) } }
    var newExclude by remember { mutableStateOf("") }

    val apps = remember { mutableStateListOf<AppEntry>() }
    val whitelist = remember { mutableStateListOf<String>().apply { addAll(store.whitelist) } }

    var tipEnabled by remember { mutableStateOf(store.tipEnabled) }
    var tipText by remember { mutableStateOf(store.tipText) }
    var verbose by remember { mutableStateOf(store.verboseLog) }
    var tipMs by remember { mutableStateOf(store.tipDurationMs.toString()) }
    var hasOverlay by remember { mutableStateOf(SkipTip.canShow(activity)) }

    var globalEnabled by remember { mutableStateOf(store.globalEnabled) }
    var whitelistAll by remember { mutableStateOf(store.whitelistAllApps) }
    var filterInvisible by remember { mutableStateOf(store.filterInvisible) }
    var filterYoung by remember { mutableStateOf(store.filterYoung) }
    var delayText by remember { mutableStateOf(store.delayMs.toString()) }

    var ruleApps by remember { mutableStateOf(0) }
    var ruleCount by remember { mutableStateOf(0) }
    var globalRuleCount by remember { mutableStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun toastResult(result: RuleImport.Result?) {
        if (result == null) {
            Toast.makeText(activity, "导入失败：文件格式不是规则 JSON", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(
                activity,
                "导入完成：${result.matchedApps} 个应用 / ${result.popupCount} 条弹窗 / ${result.keywordCount} 条关键词" +
                    if (result.unresolved > 0) "（另有 ${result.unresolved} 条对应应用未安装）" else "",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    val rulePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { RuleImport.import(activity, uri) }.getOrNull()
            }
            importing = false
            toastResult(result)
        }
    }

    fun refreshCounts() {
        val loaded = RuleImport.load(activity)
        ruleApps = loaded.size - if (loaded.containsKey(GLOBAL_RULE_PKG)) 1 else 0
        ruleCount = loaded.values.filter { it.pkg != GLOBAL_RULE_PKG }.sumOf { it.popupRules.size }
        globalRuleCount = loaded[GLOBAL_RULE_PKG]?.popupRules?.size ?: 0
    }

    LaunchedEffect(Unit) { refreshCounts() }

    // 拿到 WRITE_SECURE_SETTINGS（adb 授权）时，直接把自己写进无障碍列表：
    // HyperOS 的「受限制设置」会把开关弹回，这条路径绕开它。
    LaunchedEffect(Unit) {
        if (A11ySelfEnable.canWriteSecureSettings(activity)) {
            A11ySelfEnable.ensureEnabled(activity)
            serviceOn = isServiceEnabled(activity)
        }
    }

    // 从设置页/规则编辑页返回后状态会变，轮询保证界面跟得上
    LaunchedEffect(Unit) {
        while (true) {
            serviceOn = isServiceEnabled(activity)
            hasOverlay = SkipTip.canShow(activity)
            hits = store.totalHits()
            refreshCounts()
            delay(1500)
        }
    }

    LaunchedEffect(Unit) {
        val pm = activity.packageManager
        val loaded = withContext(Dispatchers.IO) {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { it.packageName != activity.packageName }
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { AppEntry(it.loadLabel(pm).toString(), it.packageName) }
                .sortedBy { it.label.lowercase() }
                .toList()
        }
        apps.clear()
        apps.addAll(loaded)
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("服务状态", style = MaterialTheme.typography.titleMedium)
                    val connected = SkipService.connected
                    val stateText = when {
                        !serviceOn -> "无障碍服务未开启"
                        !connected -> "系统里已勾选，但服务没连上（常见于 HyperOS 的受限制设置或省电策略拦截）"
                        else -> "无障碍服务已开启，正在监听窗口"
                    }
                    Text(
                        stateText,
                        color = if (connected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = {
                        activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }) { Text(if (serviceOn) "查看无障碍设置" else "去开启无障碍服务") }
                    Button(onClick = {
                        activity.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${activity.packageName}"),
                            ),
                        )
                    }) { Text("打开应用信息（受限制设置在这里）") }

                    val overlay = Settings.canDrawOverlays(activity)
                    val ignoringBattery = (activity.getSystemService(Context.POWER_SERVICE) as PowerManager)
                        .isIgnoringBatteryOptimizations(activity.packageName)
                    Text(
                        "诊断：Android SDK ${android.os.Build.VERSION.SDK_INT} · 系统列表=${if (serviceOn) "有" else "无"} · " +
                            "服务连接=${if (connected) "是" else "否"} · 悬浮窗=${if (overlay) "有" else "无"} · " +
                            "电池优化=${if (ignoringBattery) "已忽略" else "未忽略"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("详细日志（排障用，会刷屏）", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f))
                        Switch(checked = verbose, onCheckedChange = {
                            verbose = it
                            store.verboseLog = it
                        })
                    }
                    Text("累计跳过 $hits 次", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("无人值守开启（HyperOS / MIUI）", style = MaterialTheme.typography.titleSmall)
                    val canWrite = A11ySelfEnable.canWriteSecureSettings(activity)
                    Text(
                        when {
                            serviceOn -> "无障碍服务已在系统列表里，不需要再走这条路。"
                            canWrite -> "已获得 WRITE_SECURE_SETTINGS，点下面的按钮直接写入无障碍列表。"
                            else -> "没有 WRITE_SECURE_SETTINGS。先在电脑上执行一次：\n" +
                                A11ySelfEnable.grantHint(activity.packageName)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        enabled = !serviceOn,
                        onClick = {
                            val ok = A11ySelfEnable.ensureEnabled(activity)
                            serviceOn = isServiceEnabled(activity)
                            Toast.makeText(
                                activity,
                                if (ok) "已写入无障碍列表，稍等 1~2 秒服务会连上"
                                else "没写成：先 adb 授权 WRITE_SECURE_SETTINGS",
                                Toast.LENGTH_LONG,
                            ).show()
                        },
                    ) { Text("写入无障碍列表") }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("开启不了？按这个顺序查（MIUI / HyperOS）", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "1. 应用信息 → 右上角 ⋮ → 「允许受限制的设置」（侧载安装的 App 默认被拦，开关会自己弹回）。\n" +
                            "2. 设置 → 无障碍 → 已下载的应用 → 跳过助手 → 打开。\n" +
                            "3. 应用信息 → 省电策略 → 无限制；自启动 → 允许。\n" +
                            "4. 开发者选项里如果开着「MIUI 优化」的关闭状态，部分机型会拒绝绑定无障碍服务，改回默认再试。\n" +
                            "5. 仍不行就用 adb 看系统日志：adb logcat -s SkipHelper AccessibilityManagerService",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("总开关", style = MaterialTheme.typography.titleMedium)
                        Text("关闭后不再识别和点击", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = enabled, onCheckedChange = {
                        enabled = it
                        store.isEnabled = it
                    })
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("关键词", style = MaterialTheme.typography.titleMedium)
                    Text("按钮文字命中任意一个即点击", style = MaterialTheme.typography.bodySmall)
                    keywords.forEach { kw ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(kw)
                            TextButton(onClick = {
                                keywords.remove(kw)
                                store.keywords = keywords.toList()
                            }) { Text("删除") }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = newKeyword,
                            onValueChange = { newKeyword = it },
                            label = { Text("新增关键词") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            enabled = newKeyword.isNotBlank(),
                            onClick = {
                                val k = newKeyword.trim()
                                if (k.isNotEmpty() && k !in keywords) {
                                    keywords.add(k)
                                    store.keywords = keywords.toList()
                                }
                                newKeyword = ""
                            },
                        ) { Text("添加") }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("排除词", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "控件文字里出现任一词就绝不点击，优先级高于所有正向规则",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    excludes.forEach { ex ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(ex)
                            TextButton(onClick = {
                                excludes.remove(ex)
                                store.excludes = excludes.toList()
                            }) { Text("删除") }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = newExclude,
                            onValueChange = { newExclude = it },
                            label = { Text("新增排除词") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            enabled = newExclude.isNotBlank(),
                            onClick = {
                                val e = newExclude.trim()
                                if (e.isNotEmpty() && e !in excludes) {
                                    excludes.add(e)
                                    store.excludes = excludes.toList()
                                }
                                newExclude = ""
                            },
                        ) { Text("添加") }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("全局规则", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "对所有应用生效的兜底规则；当前 $globalRuleCount 条弹窗规则",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = globalEnabled, onCheckedChange = {
                            globalEnabled = it
                            store.globalEnabled = it
                        })
                    }
                    Button(onClick = {
                        activity.startActivity(Intent(activity, GlobalRulesActivity::class.java))
                    }) { Text("编辑全局规则 / 小程序") }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("识别细节", style = MaterialTheme.typography.titleMedium)
                    ParamRow("只点可见节点（filter_invisible_nodes）", filterInvisible) {
                        filterInvisible = it
                        store.filterInvisible = it
                    }
                    ParamRow("不自动点「青少年模式」弹窗（filter_young_popups）", filterYoung) {
                        filterYoung = it
                        store.filterYoung = it
                    }
                    ParamRow("全部应用都白名单（暂停接管）", whitelistAll) {
                        whitelistAll = it
                        store.whitelistAllApps = it
                    }
                    OutlinedTextField(
                        value = delayText,
                        onValueChange = {
                            delayText = it.filter { c -> c.isDigit() }.take(4)
                            store.delayMs = delayText.toIntOrNull() ?: 0
                        },
                        label = { Text("额外延迟（毫秒，0-3000）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "开屏倒计时还没结束就点会点不掉，加一点延迟更稳；规则自带的 delay 会叠加在这上面。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("跳过成功后提示", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (hasOverlay) "用悬浮窗显示，不打扰操作"
                                else "需要「显示在其他应用上层」权限",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (hasOverlay) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.error,
                            )
                        }
                        Switch(checked = tipEnabled, onCheckedChange = {
                            tipEnabled = it
                            store.tipEnabled = it
                        })
                    }

                    if (!hasOverlay) {
                        Button(onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + activity.packageName),
                            )
                            activity.startActivity(intent)
                        }) { Text("去开启悬浮窗权限") }
                    }

                    OutlinedTextField(
                        value = tipText,
                        onValueChange = {
                            tipText = it
                            if (it.isNotBlank()) store.tipText = it
                        },
                        label = { Text("提示文案") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = tipMs,
                        onValueChange = { v ->
                            tipMs = v.filter(Char::isDigit).take(4)
                            tipMs.toIntOrNull()?.let { store.tipDurationMs = it }
                        },
                        label = { Text("停留毫秒（300~3000，默认 ${RuleStore.DEFAULT_TIP_DURATION_MS}）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("规则文件", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (importing) "正在导入…"
                        else "已导入 $ruleApps 个应用 / $ruleCount 条弹窗规则",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "支持社区流通的规则格式（包名哈希 → popup_rules）。装上对应应用后重新导入即可生效。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        enabled = !importing,
                        onClick = {
                            rulePicker.launch(arrayOf("application/json", "text/plain", "*/*"))
                        },
                    ) { Text("导入规则文件") }
                    Button(
                        enabled = !importing,
                        onClick = {
                            importing = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { RuleImport.importBuiltin(activity) }.getOrNull()
                                }
                                importing = false
                                toastResult(result)
                                refreshCounts()
                            }
                        },
                    ) { Text("导入内置社区规则") }
                    Button(
                        enabled = !importing,
                        onClick = {
                            importing = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        RuleImport.importBuiltin(activity, RuleImport.MARKET_ASSET)
                                    }.getOrNull()
                                }
                                importing = false
                                toastResult(result)
                                refreshCounts()
                            }
                        },
                    ) { Text("导入市场规则（2026.11）") }
                    Text(
                        "市场规则包：337 个 App / 854 条弹窗（社区逐 App 规则 + 6 个高频 App 的模板补充），" +
                            "与内置规则同一个文件，重复导入只做合并去重。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            Text(
                "应用（共 ${apps.size} 个，开关关闭即加入白名单）",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        items(apps) { app ->
            val isWhitelisted = app.pkg in whitelist
            Row(
                Modifier.fillMaxWidth()
                    .clickable {
                        activity.startActivity(Intent(activity, RuleEditActivity::class.java).apply {
                            putExtra(RuleEditActivity.EXTRA_PKG, app.pkg)
                            putExtra(RuleEditActivity.EXTRA_LABEL, app.label)
                        })
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(app.label, maxLines = 1)
                    Text(
                        app.pkg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Switch(
                    checked = !isWhitelisted,
                    onCheckedChange = { on ->
                        if (on) whitelist.remove(app.pkg) else whitelist.add(app.pkg)
                        store.setWhitelisted(app.pkg, !on)
                    },
                )
            }
        }
    }
}

@Composable
private fun ParamRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

/** 通过系统设置判断无障碍服务是否已启用。 */
private fun isServiceEnabled(ctx: Context): Boolean {
    val expected = ComponentName(ctx, SkipService::class.java)
    val enabled = Settings.Secure.getString(
        ctx.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
}

