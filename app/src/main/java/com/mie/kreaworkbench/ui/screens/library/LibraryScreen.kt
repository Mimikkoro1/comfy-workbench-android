package com.mie.kreaworkbench.ui.screens.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Tag
import com.composables.icons.lucide.Trash2
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.data.library.FMT_TXT
import com.mie.kreaworkbench.data.library.ImportResult
import com.mie.kreaworkbench.data.library.LibraryMeta
import com.mie.kreaworkbench.ui.components.GlassAlertDialog
import com.mie.kreaworkbench.ui.components.GlassBottomSheet
import com.mie.kreaworkbench.ui.components.GlassCard
import com.mie.kreaworkbench.ui.components.GlassDanger
import com.mie.kreaworkbench.ui.components.ImmersiveInk
import com.mie.kreaworkbench.ui.components.ImmersiveInkMuted
import com.mie.kreaworkbench.ui.components.LocalToaster
import com.mie.kreaworkbench.ui.components.ScreenHeader
import com.mie.kreaworkbench.ui.components.glassTextFieldColors
import com.mie.kreaworkbench.ui.components.notify
import com.mie.kreaworkbench.ui.motion.LocalExtraBottom
import com.mie.kreaworkbench.ui.locale.known
import com.mie.kreaworkbench.ui.locale.knownText
import com.mie.kreaworkbench.ui.locale.str
import com.mie.kreaworkbench.ui.nav.NavModel
import com.mie.kreaworkbench.ui.nav.Overlay
import com.mie.kreaworkbench.data.workflows.safeExportName
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(vm: LibraryModel = viewModel(), nav: NavModel = viewModel()) {
    val s by vm.settings.collectAsState()
    val libs by vm.libraries.collectAsState()
    val currentId by vm.currentId.collectAsState()
    val imported by vm.imported.collectAsState()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importLibrary(uri)
    }
    val pickWorkflow = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            vm.importWorkflows(uris) { pathUris ->
                // 路 B：裸 API 工作流 → 进全屏配置页
                nav.push(Overlay.WorkflowImport(pathUris))
            }
        }
    }
    var confirmDeleteLib by remember { mutableStateOf<LibraryMeta?>(null) }
    var confirmDeleteWf by remember { mutableStateOf<Pair<String, String>?>(null) }
    var renameWf by remember { mutableStateOf<Pair<String, String>?>(null) }
    // r13fix 第 3 项：长按工作流行 → 操作弹层（重命名 / 分享 / 删除）
    var wfMenu by remember { mutableStateOf<Pair<String, String>?>(null) }
    // round13 第 3 项：长按库行 → 菜单（重命名库 / 重命名分组 → 选分组 → 输入新名）
    var libMenu by remember { mutableStateOf<LibraryMeta?>(null) }
    var renameLib by remember { mutableStateOf<LibraryMeta?>(null) }
    var renameCat by remember { mutableStateOf<LibraryMeta?>(null) }
    var renameCatOld by remember { mutableStateOf("") }
    var renameCatInput by remember { mutableStateOf<LibraryMeta?>(null) }
    var catList by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    val scope = rememberCoroutineScope()
    var showExample by remember { mutableStateOf(false) }

    // 库导出（round8 2.1）：CreateDocument 与导入的 OpenDocument 对称；mime 按库 fmt 定死在契约上，
    // 所以 txt / json 各一个 launcher。建议文件名 = safeExportName(库名) + 对应扩展名。
    // 成功 / 失败 / 取消都有 toast。
    val toaster = LocalToaster.current
    val context = LocalContext.current
    var pendingExport by remember { mutableStateOf<LibraryMeta?>(null) }
    val exportTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val lib = pendingExport
        pendingExport = null
        when {
            lib == null -> {}
            uri == null -> notify(toaster, context, context.str(R.string.export_cancelled))
            else -> vm.exportLibrary(lib, uri) { err ->
                if (err == null) notify(toaster, context, context.str(R.string.exported_name, context.known(lib.name)))
                else notify(toaster, context, context.str(R.string.export_failed, err))
            }
        }
    }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val lib = pendingExport
        pendingExport = null
        when {
            lib == null -> {}
            uri == null -> notify(toaster, context, context.str(R.string.export_cancelled))
            else -> vm.exportLibrary(lib, uri) { err ->
                if (err == null) notify(toaster, context, context.str(R.string.exported_name, context.known(lib.name)))
                else notify(toaster, context, context.str(R.string.export_failed, err))
            }
        }
    }
    fun startExport(lib: LibraryMeta) {
        pendingExport = lib
        val name = safeExportName(lib.name) + if (lib.fmt == FMT_TXT) ".txt" else ".json"
        if (lib.fmt == FMT_TXT) exportTxt.launch(name) else exportJson.launch(name)
    }
    // 可编辑集合（round8 2.3）：库列表每次变化后重算（含编辑保存/存库引起的 count 变化）
    LaunchedEffect(libs) { vm.refreshEditable() }
    val editableIds = vm.editableIds

    // r28：压在共享背景上，标题和卡片内文字都走白字体系（玻璃卡由 GlassCard 提供）。
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.tab_library), ink = ImmersiveInk)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            GlassCard(padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.section_workflows),
                        fontSize = 13.sp,
                        color = ImmersiveInk,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { pickWorkflow.launch(arrayOf("application/json", "*/*")) },
                        enabled = !vm.wfImporting,
                        colors = ButtonDefaults.textButtonColors(contentColor = ImmersiveInk),
                    ) { Text(stringResource(R.string.import_workflow), fontSize = 12.sp) }
                }
                Spacer(Modifier.height(2.dp))
                imported.forEach { wf ->
                    val isCur = s.currentWorkflow == wf.id
                    // round13 第 2 项：改单行紧凑列表——去掉「导入于」次要行、压行距，
                    // 一屏能看 8 个以上；「当前」徽标 + 加粗即选中态，不再用整宽 RadioButton
                    // r13fix 第 3 项：去掉行内三个图标按钮，行高压到 ~44dp；点 = 设为当前，长按 = 操作弹层
                    // r21：行高再压到 ~40dp、行内纵向 padding 2dp，观感更紧
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 40.dp)
                            .combinedClickable(
                                onClick = { vm.setWorkflow(wf.id) },
                                onLongClick = { wfMenu = wf.id to wf.displayName },
                            )
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isCur) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.White.copy(alpha = 0.22f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text(stringResource(R.string.badge_current), fontSize = 10.sp, color = ImmersiveInk, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.size(6.dp))
                        }
                        Text(
                            knownText(wf.displayName),
                            color = ImmersiveInk,
                            fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (imported.isEmpty()) {
                    Text(
                        if (vm.wfImporting) stringResource(R.string.state_importing_long) else
                            stringResource(R.string.workflows_empty),
                        fontSize = 11.sp,
                        color = ImmersiveInkMuted,
                    )
                }
                if (vm.wfImporting) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = ImmersiveInk)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.state_importing), color = ImmersiveInkMuted, fontSize = 12.sp)
                    }
                }
            }
            if (!s.advGacha) {
            Spacer(Modifier.height(8.dp))
            GlassCard(padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.section_prompts),
                        fontSize = 13.sp,
                        color = ImmersiveInk,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { showExample = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = ImmersiveInk),
                    ) { Text(stringResource(R.string.example_format), fontSize = 12.sp) }
                    TextButton(
                        onClick = { pick.launch(arrayOf("*/*")) },
                        enabled = !vm.importing,
                        colors = ButtonDefaults.textButtonColors(contentColor = ImmersiveInk),
                    ) { Text(stringResource(R.string.action_import), fontSize = 12.sp) }
                }
                if (vm.importing) {
                    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = ImmersiveInk)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.state_importing), color = ImmersiveInkMuted, fontSize = 12.sp)
                    }
                }
                if (libs.isEmpty()) {
                    Text(
                        if (vm.importing) stringResource(R.string.state_importing_long) else stringResource(R.string.prompts_empty),
                        color = ImmersiveInkMuted,
                        fontSize = 13.sp,
                    )
                }
                libs.forEach { lib ->
                    val isCur = lib.id == currentId
                    Row(
                        // 点击 = 设为当前库；长按 = 操作弹层（编辑 / 重命名库 / 重命名分组 / 导出 / 删除）
                        // r21：行高 44 → 40、行内纵向 padding 4 → 2，列表更紧
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 40.dp)
                            .combinedClickable(
                                onClick = { vm.setCurrent(lib.id) },
                                onLongClick = { libMenu = lib },
                            )
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isCur) {
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color.White.copy(alpha = 0.22f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    ) {
                                        Text(stringResource(R.string.badge_current), fontSize = 10.sp, color = ImmersiveInk, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.size(6.dp))
                                }
                                Text(
                                    knownText(lib.name),
                                    color = ImmersiveInk,
                                    fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                pluralStringResource(R.plurals.entry_count, lib.count, lib.count),
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                color = ImmersiveInkMuted,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            }
            Spacer(Modifier.height(16.dp + LocalExtraBottom.current))
        }
    }

    confirmDeleteLib?.let { lib ->
        GlassAlertDialog(
            onDismissRequest = { confirmDeleteLib = null },
            title = { Text(stringResource(R.string.delete_library_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.delete_library_body,
                        knownText(lib.name),
                        pluralStringResource(R.plurals.entry_count, lib.count, lib.count),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.delete(lib.id); confirmDeleteLib = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = GlassDanger),
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteLib = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    confirmDeleteWf?.let { (id, name) ->
        GlassAlertDialog(
            onDismissRequest = { confirmDeleteWf = null },
            title = { Text(stringResource(R.string.delete_workflow_title)) },
            text = { Text(stringResource(R.string.delete_workflow_body, knownText(name))) },
            confirmButton = {
                TextButton(
                    onClick = { vm.deleteWorkflow(id); confirmDeleteWf = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = GlassDanger),
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteWf = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    renameWf?.let { (id, name) ->
        var text by remember(id) { mutableStateOf(name) }
        GlassAlertDialog(
            onDismissRequest = { renameWf = null },
            title = { Text(stringResource(R.string.rename_workflow)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = glassTextFieldColors(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.renameWorkflow(id, text)
                        renameWf = null
                    },
                    enabled = text.trim().isNotBlank(),
                ) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { renameWf = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    // 长按工作流行 → 操作弹层
    wfMenu?.let { (id, name) ->
        RowActionSheet(
            title = knownText(name),
            onDismiss = { wfMenu = null },
            actions = listOf(
                RowAction(stringResource(R.string.action_rename), Lucide.Pencil) { renameWf = id to name },
                RowAction(stringResource(R.string.action_share), Lucide.Share2) { vm.exportWorkflow(id) },
                RowAction(stringResource(R.string.action_delete), Lucide.Trash2, danger = true) { confirmDeleteWf = id to name },
            ),
        )
    }

    // 长按库行 → 操作弹层（round13 的重命名库/分组 + 原行内的编辑/导出/删除）
    libMenu?.let { lib ->
        val actions = buildList {
            // 编辑（round8 2.3）：仅 txt 且未超容量护栏（1MB）的库显示
            if (lib.id in editableIds) {
                add(RowAction(stringResource(R.string.action_edit), Lucide.FileText) { nav.push(Overlay.LibraryEditor(lib.id)) })
            }
            add(RowAction(stringResource(R.string.rename_library), Lucide.Pencil) { renameLib = lib })
            add(
                RowAction(stringResource(R.string.rename_category), Lucide.Tag) {
                    scope.launch {
                        catList = vm.categoriesOf(lib.id)
                        renameCat = lib
                    }
                },
            )
            add(RowAction(stringResource(R.string.action_export), Lucide.Share2) { startExport(lib) })
            add(RowAction(stringResource(R.string.action_delete), Lucide.Trash2, danger = true) { confirmDeleteLib = lib })
        }
        RowActionSheet(title = knownText(lib.name), onDismiss = { libMenu = null }, actions = actions)
    }

    renameLib?.let { lib ->
        var text by remember(lib.id) { mutableStateOf(lib.name) }
        val trimmed = text.trim()
        // 重名校验：与其他库同名不允许（同库改名回原名字然 OK）
        val dup = libs.any { it.id != lib.id && it.name == trimmed }
        GlassAlertDialog(
            onDismissRequest = { renameLib = null },
            title = { Text(stringResource(R.string.rename_library)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        isError = trimmed.isBlank() || dup,
                        modifier = Modifier.fillMaxWidth(),
                        colors = glassTextFieldColors(),
                    )
                    if (dup) {
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.dup_name), fontSize = 12.sp, color = GlassDanger)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.renameLibrary(lib.id, trimmed)
                        renameLib = null
                    },
                    enabled = trimmed.isNotBlank() && !dup,
                ) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { renameLib = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    renameCat?.let { lib ->
        // 只列分组让用户挑一个，不显示输入框：GlassAlertDialog 的 confirmButton 必须传，给空 lambda。
        GlassAlertDialog(
            onDismissRequest = { renameCat = null },
            title = { Text(stringResource(R.string.pick_category_rename)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (catList.isEmpty()) {
                        Text(stringResource(R.string.prompts_empty), fontSize = 13.sp, color = ImmersiveInkMuted)
                    }
                    catList.forEach { (name, count) ->
                        TextButton(onClick = {
                            renameCatOld = name
                            renameCatInput = lib
                            renameCat = null
                        }) {
                            Text(
                                stringResource(R.string.category_count, knownText(name), count),
                                color = ImmersiveInk,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { renameCat = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    renameCatInput?.let { lib ->
        var text by remember(lib.id + renameCatOld) { mutableStateOf(renameCatOld) }
        val trimmed = text.trim()
        // 与该库其他既有分组同名 = 合并，先拦下并提示（重命名分组不是合并入口）
        val dup = catList.any { it.first == trimmed && it.first != renameCatOld }
        GlassAlertDialog(
            onDismissRequest = { renameCatInput = null },
            title = { Text(stringResource(R.string.rename_category)) },
            text = {
                Column {
                    Text(stringResource(R.string.draw_cat, knownText(renameCatOld)), fontSize = 13.sp, color = ImmersiveInkMuted)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        isError = trimmed.isBlank() || dup,
                        modifier = Modifier.fillMaxWidth(),
                        colors = glassTextFieldColors(),
                    )
                    if (dup) {
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.dup_name), fontSize = 12.sp, color = GlassDanger)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.renameCategory(lib.id, renameCatOld, trimmed)
                        renameCatInput = null
                    },
                    enabled = trimmed.isNotBlank() && !dup,
                ) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { renameCatInput = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    vm.nameMismatch?.let { ask ->
        GlassAlertDialog(
            onDismissRequest = { vm.answerNameMismatch(false) },
            title = { Text(stringResource(R.string.name_mismatch)) },
            text = { Text(stringResource(R.string.name_mismatch_body, ask.expected, ask.actual, knownText(ask.displayName))) },
            confirmButton = {
                TextButton(onClick = { vm.answerNameMismatch(true) }) { Text(stringResource(R.string.action_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { vm.answerNameMismatch(false) }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    vm.wfOutcome?.let { r ->
        GlassAlertDialog(
            onDismissRequest = { vm.dismissWfOutcome() },
            title = { Text(r.title) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(r.detail)
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.dismissWfOutcome() }) { Text(stringResource(R.string.action_got_it)) }
            },
        )
    }

    vm.result?.let { r ->
        GlassAlertDialog(
            onDismissRequest = { vm.dismissResult() },
            title = { Text(if (r.ok) stringResource(R.string.import_done) else stringResource(R.string.import_failed)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (r.ok) {
                        Text(stringResource(R.string.import_summary, knownText(r.name), r.imported, r.skipped))
                    } else {
                        Text(r.error ?: stringResource(R.string.import_failed))
                    }
                    if (r.reasons.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.skip_reasons), fontWeight = FontWeight.SemiBold, color = ImmersiveInk)
                        r.reasons.forEach { reason ->
                            Text("· $reason", fontSize = 12.sp, color = ImmersiveInkMuted)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.dismissResult() }) { Text(stringResource(R.string.action_got_it)) }
            },
        )
    }

    if (showExample) {
        GlassAlertDialog(
            onDismissRequest = { showExample = false },
            title = { Text(stringResource(R.string.example_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.example_text), fontSize = 12.sp, color = ImmersiveInkMuted)
                }
            },
            confirmButton = {
                TextButton(onClick = { showExample = false }) { Text(stringResource(R.string.action_got_it)) }
            },
        )
    }
}

private class RowAction(
    val label: String,
    val icon: ImageVector,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/** 列表行长按后的操作弹层：标题 + 一列动作（危险操作用浅红）；点动作先关弹层再执行。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RowActionSheet(title: String, onDismiss: () -> Unit, actions: List<RowAction>) {
    GlassBottomSheet(onDismissRequest = onDismiss) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = ImmersiveInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            actions.forEach { a ->
                val tint = if (a.danger) GlassDanger else ImmersiveInkMuted
                ListItem(
                    headlineContent = { Text(a.label, color = if (a.danger) GlassDanger else ImmersiveInk) },
                    leadingContent = { Icon(a.icon, contentDescription = null, tint = tint) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {
                        onDismiss()
                        a.onClick()
                    },
                )
            }
        }
    }
}
