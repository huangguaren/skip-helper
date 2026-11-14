package com.example.skiphelper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
 * 单个应用的规则编辑页。
 *
 * 支持关键词与弹窗规则的增删，以及四个参数开关。
 * 语法提示直接写在界面上——用户不需要去翻文档才知道 `+` 和 `&` 是什么意思。
 */
class RuleEditActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty().ifEmpty { pkg }

        setContent {
            MaterialTheme { Surface(Modifier.fillMaxSize()) { Editor(this, pkg, label) } }
        }
    }

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_LABEL = "label"
    }
}

@Composable
private fun Editor(activity: RuleEditActivity, pkg: String, label: String) {
    val all = remember { RuleImport.load(activity).toMutableMap() }
    val initial = remember { all[pkg] ?: AppRuleSet(pkg = pkg) }

    val keywords = remember { mutableStateListOf<String>().apply { addAll(initial.keywords) } }
    val popups = remember { mutableStateListOf<PopupRule>().apply { addAll(initial.popupRules) } }
    var forceClick by remember { mutableStateOf(initial.forceClick) }
    var uniteRules by remember { mutableStateOf(initial.uniteRules) }
    var reverseTraversal by remember { mutableStateOf(initial.reverseTraversal) }
    var searchTimes by remember { mutableStateOf(initial.searchTimes.toString()) }
    var delayText by remember { mutableStateOf(initial.delayMs.toString()) }
    var blindX by remember { mutableStateOf(if (initial.blindX >= 0) initial.blindX.toString() else "") }
    var blindY by remember { mutableStateOf(if (initial.blindY >= 0) initial.blindY.toString() else "") }

    var newKeyword by remember { mutableStateOf("") }
    var newId by remember { mutableStateOf("") }
    var newAction by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    fun persist() {
        val set = AppRuleSet(
            pkg = pkg,
            enabled = initial.enabled,
            keywords = keywords.toList(),
            popupRules = popups.toList(),
            forceClick = forceClick,
            searchTimes = searchTimes.toIntOrNull() ?: 1,
            uniteRules = uniteRules,
            reverseTraversal = reverseTraversal,
            delayMs = delayText.toIntOrNull() ?: 0,
            blindX = blindX.toIntOrNull() ?: -1,
            blindY = blindY.toIntOrNull() ?: -1,
        )
        all[pkg] = set
        RuleImport.save(activity, all.values)
        saved = true
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(label, style = MaterialTheme.typography.titleLarge)
            Text(pkg, style = MaterialTheme.typography.bodySmall)
        }

        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("匹配语法", style = MaterialTheme.typography.titleSmall)
                    Text("+开头   -结尾   =完全   abc包含   a&b 同时满足（文本、描述、资源 id 都试）",
                        style = MaterialTheme.typography.bodySmall)
                    Text("动作：控件文字 / GLOBAL_ACTION_BACK（返回键）/ 坐标 100,200,300,400",
                        style = MaterialTheme.typography.bodySmall)
                    Text("坐标也能当触发器：x1,y1,x2,y2 会定位到盖住该点的最小控件；第五段 0/1 表示要求 CheckBox 状态",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("关键词（${keywords.size}）", style = MaterialTheme.typography.titleMedium)
                    keywords.forEachIndexed { i, kw ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(kw)
                            TextButton(onClick = { keywords.removeAt(i) }) { Text("删除") }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = newKeyword, onValueChange = { newKeyword = it },
                            label = { Text("新增关键词") }, singleLine = true, modifier = Modifier.weight(1f))
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
                    Text("弹窗规则（${popups.size}）", style = MaterialTheme.typography.titleMedium)
                    popups.forEachIndexed { i, r ->
                        Column {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${r.id}  →  ${r.action.ifEmpty { "自身" }}", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { popups.removeAt(i) }) { Text("删除") }
                            }
                        }
                    }
                    OutlinedTextField(value = newId, onValueChange = { newId = it },
                        label = { Text("触发文字 / 资源 id / 坐标 x1,y1,x2,y2") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = newAction, onValueChange = { newAction = it },
                        label = { Text("动作（留空=点自身，或 GLOBAL_ACTION_BACK / 坐标）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
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
                    Text("参数", style = MaterialTheme.typography.titleMedium)
                    ParamRow("命中但找不到目标时强点触发器", forceClick) { forceClick = it }
                    ParamRow("存在 times=0 规则时作为闸门（unite）", uniteRules) { uniteRules = it }
                    ParamRow("子树逆序遍历", reverseTraversal) { reverseTraversal = it }
                    OutlinedTextField(value = searchTimes, onValueChange = { searchTimes = it },
                        label = { Text("扫描次数 search_times_popup") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = delayText, onValueChange = { delayText = it.filter(Char::isDigit).take(4) },
                        label = { Text("延迟毫秒 delay / delay_popup") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Text("开屏盲点：整屏自绘、无障碍读不到文字的广告，按屏幕百分比点一下（只在应用刚启动的 3.5 秒内生效）。留空关闭。",
                        style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = blindX,
                            onValueChange = { blindX = it.filter(Char::isDigit).take(3) },
                            label = { Text("X %") }, singleLine = true, modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = blindY,
                            onValueChange = { blindY = it.filter(Char::isDigit).take(3) },
                            label = { Text("Y %") }, singleLine = true, modifier = Modifier.weight(1f),
                        )
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

@Composable
private fun ParamRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
