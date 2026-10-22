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
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.SlidersHorizontal
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
import com.mie.kreaworkbench.ui.locale.workflowLabel
import com.mie.kreaworkbench.ui.nav.NavModel
import com.mie.kreaworkbench.ui.nav.Overlay
import com.mie.kreaworkbench.data.workflows.BuiltinTemplates
import com.mie.kreaworkbench.data.workflows.ImportedWorkflowMeta
import com.mie.kreaworkbench.data.workflows.safeExportName
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(vm: LibraryModel = viewModel(), nav: NavModel = viewModel()) {
    val s by vm.settings.collectAsState()
    val libs by vm.libraries.collectAsState()
    val currentId by vm.currentId.collectAsState()
    val imported by vm.imported.collectAsState()
    // r38 E3：被删内置模板的墓碑（行与恢复弹窗都随它刷新）
    val deletedBuiltins by vm.deletedBuiltins.collectAsState()
    var showDeletedBuiltins by remember { mutableStateOf(false) }
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
    // r35：长按工作流行 → 操作弹层；内置模板与导入工作流的菜单不同（内置只读：复制/删除）
    var wfMenu by remember { mutableStateOf<ImportedWorkflowMeta?>(null) }
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
                // r35：内置模板单独一组（只读，可复制后再改）；没有内置时保持原平铺。
                // 分组标签只在两组并存时画，避免只有一个组时多一行噪音
                // r38 E3：内置全被删光时也保留组标题 + 「已删除」行，恢复入口不消失
                val builtins = imported.filter { it.source == BuiltinTemplates.SOURCE }
                val userWfs = imported.filter { it.source != BuiltinTemplates.SOURCE }
                if (builtins.isNotEmpty() || deletedBuiltins.isNotEmpty()) {
                    WfGroupLabel(stringResource(R.string.group_builtin))
                    builtins.forEach { wf -> WorkflowRow(wf, isCur = s.currentWorkflow == wf.id, onSetCurrent = { vm.setWorkflow(wf.id) }, onMenu = { wfMenu = wf }) }
                    if (deletedBuiltins.isNotEmpty()) {
                        DeletedBuiltinsRow(count = deletedBuiltins.size, onClick = { showDeletedBuiltins = true })
                    }
                }
                if (userWfs.isNotEmpty()) {
                    if (builtins.isNotEmpty() || deletedBuiltins.isNotEmpty()) WfGroupLabel(stringResource(R.string.group_imported))
                    userWfs.forEach { wf -> WorkflowRow(wf, isCur = s.currentWorkflow == wf.id, onSetCurrent = { vm.setWorkflow(wf.id) }, onMenu = { wfMenu = wf }) }
                }
                if (imported.isEmpty() && deletedBuiltins.isEmpty()) {
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
            text = {
                Column {
                    // r38：内置名按应用语言显示；条目刚好被并发删掉时退回存盘名
                    val label = imported.firstOrNull { it.id == id }?.let { workflowLabel(it) } ?: knownText(name)
                    Text(stringResource(R.string.delete_workflow_body, label))
                    // 内置模板删了可恢复（r35）
                    if (imported.any { it.id == id && it.source == BuiltinTemplates.SOURCE }) {
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.delete_builtin_hint), fontSize = 12.sp, color = ImmersiveInkMuted)
                    }
                }
            },
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

    // r38 E3：恢复被删的内置模板（单个）：列出墓碑里的条目；恢复成功后该行消失，列表空了自动关弹窗。
    val deletedEntries = BuiltinTemplates.all.filter { it.id in deletedBuiltins }
    LaunchedEffect(deletedEntries) {
        if (showDeletedBuiltins && deletedEntries.isEmpty()) showDeletedBuiltins = false
    }
    if (showDeletedBuiltins && deletedEntries.isNotEmpty()) {
        GlassAlertDialog(
            onDismissRequest = { showDeletedBuiltins = false },
            title = { Text(stringResource(R.string.deleted_builtins_title)) },
            text = {
                Column {
                    deletedEntries.forEach { t ->
                        val label = workflowLabel(t)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                vm.restoreBuiltin(t.id) { ok ->
                                    if (ok) notify(toaster, context, context.str(R.string.restore_builtin_done, label))
                                }
                            }) {
                                Text(stringResource(R.string.action_restore))
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDeletedBuiltins = false }) { Text(stringResource(R.string.action_cancel)) }
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

    // 长按工作流行 → 操作弹层（内置只读：复制/删除，没有重命名/分享；导入保持原三动作）
    wfMenu?.let { wf ->
        val builtin = wf.source == BuiltinTemplates.SOURCE
        RowActionSheet(
            title = workflowLabel(wf),
            onDismiss = { wfMenu = null },
            actions = if (builtin) {
                listOf(
                    // r38 E4：不直接落盘复制，先进参数选择页（预填同款勾选），保存后才建副本
                    RowAction(stringResource(R.string.action_copy_customize), Lucide.Copy) {
                        nav.push(Overlay.WorkflowCustomize(wf.id, Source.MODE_COPY))
                    },
                    RowAction(stringResource(R.string.action_delete), Lucide.Trash2, danger = true) { confirmDeleteWf = wf.id to wf.displayName },
                )
            } else {
                listOf(
                    // r38 E5：非内置可重选参数（内置只读，要改走「复制并自定义」）
                    RowAction(stringResource(R.string.action_edit_params), Lucide.SlidersHorizontal) {
                        nav.push(Overlay.WorkflowCustomize(wf.id, Source.MODE_EDIT))
                    },
                    RowAction(stringResource(R.string.action_rename), Lucide.Pencil) { renameWf = wf.id to wf.displayName },
                    RowAction(stringResource(R.string.action_share), Lucide.Share2) { vm.exportWorkflow(wf.id) },
                    RowAction(stringResource(R.string.action_delete), Lucide.Trash2, danger = true) { confirmDeleteWf = wf.id to wf.displayName },
                )
            },
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

/** r35：工作流分组的行间小标题（「内置」/「导入」）——11sp 浅白，不抢行内文字。 */
@Composable
private fun WfGroupLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        color = ImmersiveInkMuted,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

/** r38 E3：内置分组末尾的「已删除 N 个内置模板 ›」入口行；点开单个恢复弹窗。N=0 不渲染（调用方保证）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeletedBuiltinsRow(count: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .combinedClickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.builtin_missing_count, count),
            color = ImmersiveInk,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(Lucide.ChevronRight, contentDescription = null, tint = ImmersiveInkMuted)
    }
}

/**
 * 工作流列表行（r35 从内联抽出，内置/导入共用）：
 * 点 = 设为当前，长按 = 操作弹层；行高/徽标样式沿用 r21 的紧凑规格。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkflowRow(
    wf: ImportedWorkflowMeta,
    isCur: Boolean,
    onSetCurrent: () -> Unit,
    onMenu: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .combinedClickable(onClick = onSetCurrent, onLongClick = onMenu)
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
            workflowLabel(wf),
            color = ImmersiveInk,
            fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

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
