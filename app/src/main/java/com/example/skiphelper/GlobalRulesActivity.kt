package com.example.skiphelper

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.skiphelper.engine.AppRuleSet
import com.example.skiphelper.engine.PopupRule
import com.example.skiphelper.engine.RuleImport

/**
 * 全局规则 / 小程序。
 *
 * 全局规则对所有应用生效——原版用它兜住「没有单独规则的应用」里的开屏广告；
 * 小程序宿主包名则用来处理「广告跑在小程序容器里、根本没有跳过按钮」的场景。
 */
class GlobalRulesActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { Surface(Modifier.fillMaxSize()) { Editor(this) } }
        }
    }
}

@Composable
private fun Editor(activity: GlobalRulesActivity) {
    val store = remember { RuleStore(activity) }
    val initial = remember { RuleImport.loadGlobal(activity) ?: AppRuleSet(pkg = "*") }

    val keywords = remember { mutableStateListOf<String>().apply { addAll(initial.keywords) } }
    val popups = remember { mutableStateListOf<PopupRule>().apply { addAll(initial.popupRules) } }
    val hosts = remember {
        mutableStateListOf<String>().apply { addAll(store.miniProgramHosts) }
    }

    var newKeyword by remember { mutableStateOf("") }
    var newId by remember { mutableStateOf("") }
    var newAction by remember { mutableStateOf("") }
    var newHost by remember { mutableStateOf("") }
    var autoBack by remember { mutableStateOf(store.miniProgramAutoBack) }
    var saved by remember { mutableStateOf(false) }

    fun persist() {
        RuleImport.saveGlobal(
            activity,
            AppRuleSet(
                pkg = "*",
                enabled = true,
                keywords = keywords.toList(),
                popupRules = popups.toList(),
            ),
        )
        store.miniProgramHosts = hosts.toList()
        store.miniProgramAutoBack = autoBack
        saved = true
        Toast.makeText(activity, "已保存", Toast.LENGTH_SHORT).show()
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("全局规则", style = MaterialTheme.typography.titleLarge)
            Text(
                "对所有应用生效。应用自己命中规则时优先，全局规则负责兜底。",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("全局关键词（${keywords.size}）", style = MaterialTheme.typography.titleMedium)
                    keywords.forEach { kw ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(kw)
                            TextButton(onClick = { keywords.remove(kw) }) { Text("删除") }
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
                            label = { Text("新增全局关键词") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(enabled = newKeyword.isNotBlank(), onClick = {
                            val k = newKeyword.trim()
                            if (k.isNotEmpty() && k !in keywords) keywords.add(k)
                            newKeyword = ""
                        }) { Text("添加") }
                    }
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("全局弹窗规则（${popups.size}）", style = MaterialTheme.typography.titleMedium)
                    popups.forEach { r ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "${r.id}  →  ${r.action.ifEmpty { "自身" }}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = { popups.remove(r) }) { Text("删除") }
                        }
                    }
                    OutlinedTextField(
                        value = newId,
                        onValueChange = { newId = it },
                        label = { Text("触发文字 / 资源 id") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newAction,
                        onValueChange = { newAction = it },
                        label = { Text("动作（留空=点自身，或 GLOBAL_ACTION_BACK / 坐标）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(enabled = newId.isNotBlank(), onClick = {
                        popups.add(PopupRule(newId.trim(), newAction.trim()))
                        newId = ""; newAction = ""
                    }) { Text("添加规则") }
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("小程序宿主", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "广告可能跑在小程序容器里，页面里没有跳过按钮（这类页面常常是 WebView/Canvas 画的，无障碍读不到文字）。" +
                            "在找不到可点按钮时直接返回。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("找不到按钮时自动返回")
                        Switch(checked = autoBack, onCheckedChange = { autoBack = it })
                    }
                    hosts.forEach { h ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(h, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { hosts.remove(h) }) { Text("删除") }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = newHost,
                            onValueChange = { newHost = it },
                            label = { Text("宿主包名，如 com.tencent.mm") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(enabled = newHost.isNotBlank(), onClick = {
                            val h = newHost.trim()
                            if (h.isNotEmpty() && h !in hosts) hosts.add(h)
                            newHost = ""
                        }) { Text("添加") }
                    }
                }
            }
        }

        item {
            Button(onClick = { persist() }, modifier = Modifier.fillMaxWidth()) {
                Text(if (saved) "已保存 ✓" else "保存")
            }
        }
    }
}
