package com.mie.kreaworkbench.ui.screens.library

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.data.workflows.textOutputNodes
import com.mie.kreaworkbench.data.workflows.videoOutputNodes
import com.mie.kreaworkbench.ui.components.CompactMenuField
import com.mie.kreaworkbench.ui.components.KreaCard
import com.mie.kreaworkbench.ui.components.LargeBarScaffold
import com.mie.kreaworkbench.ui.components.PrimaryButton
import com.mie.kreaworkbench.ui.locale.knownText
import com.mie.kreaworkbench.ui.motion.LocalExtraBottom
import org.json.JSONObject

@Composable
private fun confidenceLabel(c: String): String = when (c) {
    "high" -> stringResource(R.string.confidence_high)
    "medium" -> stringResource(R.string.confidence_mid)
    else -> stringResource(R.string.confidence_low)
}

@Composable
fun WorkflowImportScreen(uris: List<Uri>, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val m = remember(uris) { WorkflowImportModel(app, uris) }
    val scheme = MaterialTheme.colorScheme
    // 字段改名：点击第一行字段名弹出（原行内输入框会挤压两行布局）
    var renameIdx by remember { mutableStateOf<Int?>(null) }
    var renameText by remember { mutableStateOf("") }

    LaunchedEffect(m.saved) {
        if (m.saved) onBack()
    }

    val readingFallback = stringResource(R.string.state_reading)
    LargeBarScaffold(title = stringResource(R.string.import_config_title), onBack = onBack) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            if (m.loading) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        m.statusText.ifBlank { readingFallback },
                        color = scheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
            }
            m.loadError?.let { err ->
                Text(stringResource(R.string.read_failed, err), color = scheme.error, modifier = Modifier.padding(vertical = 16.dp))
            }
            if (!m.loading && m.loadError == null) {
                KreaCard {
                    Text(stringResource(R.string.display_name), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = m.displayName,
                        onValueChange = { m.displayName = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(m.workflowFileName.removeSuffix(".json")) },
                    )
                    if (m.outputNodeChoices.size > 1) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.output_node), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        val nodeOptions = ArrayList<String>(m.outputNodeChoices.size)
                        for (id in m.outputNodeChoices) nodeOptions.add(stringResource(R.string.node_id, id))
                        CompactMenuField(
                            value = stringResource(R.string.node_id, m.outputNode),
                            options = nodeOptions,
                            modifier = Modifier.fillMaxWidth(),
                        ) { i ->
                            m.outputNodeChoices.getOrNull(i)?.let { m.outputNode = it }
                        }
                    } else if (m.outputNode.isNotBlank()) {
                        Text(stringResource(R.string.output_node_line, m.outputNode), fontSize = 12.sp, color = scheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    // 输出类型（round6）：导入时按输出节点自动推断，可手改
                    Text(stringResource(R.string.output_kind), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    val kindLabels = listOf(
                        stringResource(R.string.kind_image),
                        stringResource(R.string.kind_text),
                        stringResource(R.string.kind_video),
                    )
                    val handEdited = stringResource(R.string.hand_edited)
                    val kindValues = listOf("image", "text", "video")
                    val kindIdx = kindValues.indexOf(m.outputKind).coerceAtLeast(0)
                    CompactMenuField(
                        value = kindLabels[kindIdx] + if (m.outputKind != m.outputKindInferred) handEdited else "",
                        options = kindLabels,
                        modifier = Modifier.fillMaxWidth(),
                    ) { i ->
                        kindValues.getOrNull(i)?.let {
                            m.outputKind = it
                            if (it == "text") m.outputNode = textOutputNodes(m.workflowJson ?: JSONObject()).firstOrNull().orEmpty()
                            if (it == "video") m.outputNode = videoOutputNodes(m.workflowJson ?: JSONObject()).firstOrNull().orEmpty()
                            if (it == "image") m.outputNode = m.outputNodeChoices.firstOrNull().orEmpty()
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                KreaCard {
                    Text(stringResource(R.string.fields_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                    Text(
                        stringResource(R.string.fields_hint),
                        fontSize = 11.sp,
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    val modelLabel = stringResource(R.string.label_model)
                    val basicLabel = stringResource(R.string.label_basic)
                    val advancedLabel = stringResource(R.string.label_advanced)
                    val linkRef = stringResource(R.string.link_ref)
                    var lastNode = ""
                    m.items.forEachIndexed { idx, item ->
                        val nodeHeader = stringResource(R.string.node_field_line, item.nodeId, item.nodeTitle)
                        if (nodeHeader != lastNode) {
                            lastNode = nodeHeader
                            Spacer(Modifier.height(8.dp))
                            Text(nodeHeader, fontSize = 12.sp, color = scheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                        val field = item.spec.optString("field")
                        // 第一行：勾选框 + 字段名（有改名则 label(field)）+ 类型/置信度小字；第二行：层级下拉靠右
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = item.checked,
                                    onCheckedChange = { m.toggleChecked(idx) },
                                    enabled = !item.unsupported,
                                )
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val shownLabel = knownText(item.label)
                                        val withField = stringResource(R.string.label_with_field, shownLabel, field)
                                        val name = when {
                                            item.label.isBlank() || item.label == field -> field
                                            else -> withField
                                        }
                                        val confidenceWord = confidenceLabel(item.confidence)
                                        Text(
                                            name,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp,
                                            color = if (item.unsupported) scheme.onSurfaceVariant else scheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .weight(1f, fill = false)
                                                .clickable(enabled = !item.unsupported) {
                                                    renameText = item.label.ifBlank { field }
                                                    renameIdx = idx
                                                },
                                        )
                                        Text(
                                            if (item.unsupported) stringResource(R.string.unsupported_type, item.type) else stringResource(R.string.confidence_suffix, item.type, confidenceWord),
                                            fontSize = 11.sp,
                                            color = if (item.unsupported) scheme.error else scheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (item.preview.isNotBlank()) {
                                        val preview = if (item.preview == "连线引用") linkRef else item.preview
                                        Text(
                                            stringResource(R.string.current_value, preview),
                                            fontSize = 11.sp,
                                            color = scheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(start = 44.dp),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (item.checked) {
                                    IconButton(onClick = { m.move(idx, -1) }) {
                                        Icon(Lucide.ChevronUp, stringResource(R.string.action_move_up), tint = scheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { m.move(idx, 1) }) {
                                        Icon(Lucide.ChevronDown, stringResource(R.string.action_move_down), tint = scheme.onSurfaceVariant)
                                    }
                                }
                                if (item.type == "model") {
                                    CompactMenuField(
                                        value = modelLabel,
                                        options = listOf(modelLabel),
                                        modifier = Modifier.width(96.dp),
                                    ) { }
                                } else if (!item.unsupported) {
                                    CompactMenuField(
                                        value = if (item.tier == "advanced") advancedLabel else basicLabel,
                                        options = listOf(basicLabel, advancedLabel),
                                        modifier = Modifier.width(96.dp),
                                    ) { i ->
                                        m.setTier(idx, if (i == 1) "advanced" else "basic")
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (m.saving && m.statusText.isNotBlank()) {
                    Text(m.statusText, fontSize = 12.sp, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                PrimaryButton(if (m.saving) stringResource(R.string.state_saving) else stringResource(R.string.action_save), enabled = !m.saving) {
                    m.save()
                }
                Spacer(Modifier.height(24.dp + LocalExtraBottom.current))
            }
        }
    }

    renameIdx?.let { idx ->
        AlertDialog(
            onDismissRequest = { renameIdx = null },
            title = { Text(stringResource(R.string.rename_field)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    m.rename(idx, renameText)
                    renameIdx = null
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { renameIdx = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    m.problems?.let { problems ->
        AlertDialog(
            onDismissRequest = { m.dismissProblems() },
            title = { Text(stringResource(R.string.validate_not_saved)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    problems.forEach { Text("· $it") }
                }
            },
            confirmButton = {
                TextButton(onClick = { m.dismissProblems() }) { Text(stringResource(R.string.action_got_it)) }
            },
        )
    }

    m.missingClasses?.let { missing ->
        AlertDialog(
            onDismissRequest = { m.dismissMissing() },
            title = { Text(stringResource(R.string.missing_nodes_title)) },
            text = {
                Text(
                    stringResource(R.string.missing_nodes_body, missing.joinToString(stringResource(R.string.list_sep))) + "\n" +
                        stringResource(R.string.missing_nodes_ask),
                )
            },
            confirmButton = {
                TextButton(onClick = { m.saveIgnoringMissing() }) { Text(stringResource(R.string.action_save_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = { m.dismissMissing() }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
