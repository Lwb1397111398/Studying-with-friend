package com.studyfriend.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studyfriend.app.data.LlmProfile
import com.studyfriend.app.data.LlmRole
import com.studyfriend.app.data.SettingsRepository
import com.studyfriend.app.ui.components.SectionCard

/**
 * 模型页（2026-11-15 多模型轮）：三张书房卡——
 * ①模型档案：一个档案 = 一家服务商的地址/Key/模型，可建多个；
 * ②按任务指派：主力（总结/总览/导图）、讲解（逐段高频）、视觉转写、备用视觉接力
 *   各自指派档案，讲解/视觉未指派跟随主力，备用未指派不启用；
 * ③本地识别：扫描书离线 OCR（模型下载管理）。
 * 「查找」从服务商 GET /models 拉列表点选，拉不动仍可手填。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(vm: ModelsViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 模型") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 720.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (vm.busy || vm.editorBusy || vm.fetchingModels) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                ProfileCard(vm)
                AssignCard(vm)
                OcrCard(vm)

                vm.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                vm.message?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    EditorDialog(vm)
    RolePickerDialog(vm)
    ModelPickerDialog(vm)
}

// ============ 卡①：模型档案 ============

@Composable
private fun ProfileCard(vm: ModelsViewModel) {
    SectionCard {
        Text("模型档案", style = MaterialTheme.typography.labelLarge)
        Text(
            "一个档案 = 一家服务商的地址、Key 和模型。可以建多个，下面按任务指派用哪个",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        vm.profiles.forEach { p ->
            ProfileRow(
                profile = p,
                roles = rolesUsing(vm.assign, vm.profiles, p.id),
                hasKey = vm.keyIds.contains(p.id),
                onClick = { vm.openEditor(p) },
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { vm.openEditor(null) }
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text("新建档案", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** 档案行：首字圆徽 + 名称/模型/服务商 + 角色徽标 + 缺 Key 提示，点击进编辑 */
@Composable
private fun ProfileRow(profile: LlmProfile, roles: List<LlmRole>, hasKey: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                modifier = Modifier.size(40.dp),
            ) {}
            Text(
                profile.name.firstOrNull()?.toString() ?: "档",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    profile.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                roles.forEach { RoleBadge(it.label()) }
            }
            Text(
                "${profile.model} · ${hostOf(profile.baseUrl)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!hasKey) {
                Text(
                    "还没填 Key，点进去配置后才能用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "编辑档案「${profile.name}」",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 角色徽标（DESIGN.md：状态徽标 = 小圆角矩形 4dp，次级色底） */
@Composable
private fun RoleBadge(label: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** api host 简写（行副标题展示）：去协议取域名段 */
private fun hostOf(baseUrl: String): String = baseUrl
    .removePrefix("https://")
    .removePrefix("http://")
    .substringBefore('/')
    .ifBlank { baseUrl }

// ============ 卡②：按任务指派 ============

@Composable
private fun AssignCard(vm: ModelsViewModel) {
    SectionCard {
        Text("按任务指派", style = MaterialTheme.typography.labelLarge)
        Text(
            "每个任务用哪个档案，点右侧胶囊切换，改完即时生效",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AssignRow(
            title = "总结 · 总览 · 导图",
            desc = "章末总结、全书总览、思维导图、粗读章级合并——质量要求高，指派主力档案",
            current = mainProfileLabel(vm),
            onClick = { vm.openRolePicker(LlmRole.MAIN) },
        )
        AssignRow(
            title = "段落讲解 · 粗读批注",
            desc = "讲解、粗读批注、随手问、换个讲法、判卷共用——逐段调用用量最大，可指派便宜快速的档案",
            current = resolveRoleLabel(vm, LlmRole.NOTE),
            onClick = { vm.openRolePicker(LlmRole.NOTE) },
        )
        AssignRow(
            title = "扫描书视觉转写",
            desc = "扫描页和识别困难的页交给视觉模型整页转写，需要一个带视觉的模型档案",
            current = resolveRoleLabel(vm, LlmRole.VISION),
            onClick = { vm.openRolePicker(LlmRole.VISION) },
            switch = SwitchRow(checked = vm.visionEnabled, onChange = { vm.changeVisionEnabled(it) }),
        )
        AssignRow(
            title = "备用视觉接力",
            desc = "主视觉档案限流或失败时自动接力转写；不指派 = 不启用",
            current = resolveRoleLabel(vm, LlmRole.VISION2),
            onClick = { vm.openRolePicker(LlmRole.VISION2) },
        )
    }
}

/** 指派行：任务名 + 说明 + 当前档案胶囊（视觉行右侧带开关） */
@Composable
private fun AssignRow(
    title: String,
    desc: String,
    current: String,
    onClick: () -> Unit,
    switch: SwitchRow? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ProfileChip(current, onClick)
        }
        switch?.let {
            Switch(checked = it.checked, onCheckedChange = it.onChange)
        }
    }
}

/** 开关参数（AssignRow 可选右侧开关） */
private data class SwitchRow(val checked: Boolean, val onChange: (Boolean) -> Unit)

/** 当前档案胶囊：淡描边全圆 + 下拉箭头，点击弹指派选择 */
@Composable
private fun ProfileChip(text: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge)
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = "选择档案",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 主力行显示名：指派的档案名（失效回退第一档，与 resolveLlm 口径一致） */
private fun mainProfileLabel(vm: ModelsViewModel): String =
    vm.profiles.firstOrNull { it.id == vm.assign.main }?.name
        ?: vm.profiles.firstOrNull()?.name ?: "未配置"

/** 各角色胶囊显示名：NOTE/VISION 未指派=跟随主力，VISION2 未指派=不启用 */
private fun resolveRoleLabel(vm: ModelsViewModel, role: LlmRole): String {
    val id = when (role) {
        LlmRole.MAIN -> vm.assign.main
        LlmRole.NOTE -> vm.assign.note
        LlmRole.VISION -> vm.assign.vision
        LlmRole.VISION2 -> vm.assign.vision2
    }
    val p = vm.profiles.firstOrNull { it.id == id }
    if (p != null) return p.name
    return when (role) {
        LlmRole.MAIN -> vm.profiles.firstOrNull()?.name ?: "未配置"
        LlmRole.VISION2 -> "不启用"
        else -> "跟随主力"
    }
}

// ============ 卡③：本地识别 ============

@Composable
private fun OcrCard(vm: ModelsViewModel) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("扫描书本地识别", style = MaterialTheme.typography.labelLarge)
                Text(
                    "扫描版 PDF 先用手机离线引擎识别文字，无需联网、逐页约 1 秒；识别不了的" +
                        "图示页自动转交上方视觉档案增强。关闭后扫描书只能靠视觉模型整本转写",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = vm.ocrEnabled, onCheckedChange = { vm.changeOcrEnabled(it) })
        }
        if (!vm.ocrModelsReady) {
            Text(
                "识别模型尚未下载（约 21MB，需联网一次，此后离线识别）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            val pct = vm.ocrDownloadPct
            if (pct != null) {
                LinearProgressIndicator(
                    progress = { pct / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            } else {
                OutlinedButton(onClick = { vm.downloadOcrModels() }) { Text("下载识别模型") }
            }
        }
    }
}

// ============ 档案编辑弹层 ============

@Composable
private fun EditorDialog(vm: ModelsViewModel) {
    vm.editor?.let { ed ->
        AlertDialog(
            onDismissRequest = { vm.closeEditor() },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.widthIn(max = 560.dp),
            title = { Text(if (ed.isNew) "新建档案" else "编辑档案") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ModelInput(
                        value = ed.name,
                        onValueChange = { v -> vm.updateEditor { it.copy(name = v) } },
                        label = "档案名称",
                        placeholder = { Text("比如：智谱中转、DeepSeek 官方") },
                    )
                    ModelInput(
                        value = ed.baseUrl,
                        onValueChange = { v -> vm.updateEditor { it.copy(baseUrl = v) } },
                        label = "API 地址",
                        placeholder = { Text("https://api.openai.com/v1（OpenAI 兼容，一般以 /v1 结尾）") },
                    )
                    OutlinedTextField(
                        value = ed.keyInput,
                        onValueChange = { v -> vm.updateEditor { it.copy(keyInput = v) } },
                        label = { Text("API Key") },
                        placeholder = { Text(if (ed.hasKey) "已保存（输入以更换）" else "sk-…") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        trailingIcon = {
                            if (ed.hasKey) TextButton(onClick = { vm.clearEditorKey() }) { Text("清除") }
                        },
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = ed.model,
                            onValueChange = { v -> vm.updateEditor { it.copy(model = v) } },
                            label = { Text("模型名") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                        )
                        OutlinedButton(
                            onClick = { vm.fetchModels() },
                            enabled = !vm.fetchingModels && !vm.editorTesting,
                        ) { Text(if (vm.fetchingModels) "查找中…" else "查找") }
                    }
                    ModelInput(
                        value = ed.tempText,
                        onValueChange = { v -> vm.updateEditor { it.copy(tempText = v) } },
                        label = "温度（0.0–1.0，非法值用 0.3）",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { if (vm.editorTesting) vm.cancelTest() else vm.testEditorConnection() },
                            enabled = !vm.editorBusy,
                        ) { Text(if (vm.editorTesting) "取消测试" else "测试连接") }
                    }
                    if (vm.editorLiveDelta.isNotEmpty()) {
                        Text(
                            vm.editorLiveDelta.take(200),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    vm.editorError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (!ed.isNew && vm.profiles.size > 1) {
                        TextButton(
                            onClick = { vm.deleteEditorProfile() },
                            enabled = !vm.editorBusy,
                            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) { Text("删除此档案") }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { vm.saveEditor() }, enabled = !vm.editorBusy) {
                    Text(if (vm.editorBusy) "保存中…" else "保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.closeEditor() }) { Text("取消") }
            },
        )
    }
}

/** 弹层输入框统一 12dp 圆角（DESIGN.md：输入 12dp） */
@Composable
private fun ModelInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
    )
}

// ============ 指派选择弹层 ============

@Composable
private fun RolePickerDialog(vm: ModelsViewModel) {
    val role = vm.rolePicker ?: return
    val title = when (role) {
        LlmRole.MAIN -> "主力档案用在哪些任务？"
        LlmRole.NOTE -> "讲解任务用哪个档案？"
        LlmRole.VISION -> "视觉转写用哪个档案？"
        LlmRole.VISION2 -> "备用视觉用哪个档案？"
    }
    val noneOptionLabel = if (role == LlmRole.VISION2) "不启用" else "跟随主力"
    val currentId = when (role) {
        LlmRole.MAIN -> vm.assign.main
        LlmRole.NOTE -> vm.assign.note
        LlmRole.VISION -> vm.assign.vision
        LlmRole.VISION2 -> vm.assign.vision2
    }
    AlertDialog(
        onDismissRequest = { vm.closeRolePicker() },
        title = { Text(title) },
        text = {
            Column {
                if (role != LlmRole.MAIN) {
                    PickerOptionRow(
                        title = noneOptionLabel,
                        subtitle = when (role) {
                            LlmRole.NOTE -> "讲解跑在主力档案上"
                            else -> "视觉跑在主力档案上（主力模型需带视觉）"
                        },
                        selected = currentId.isBlank(),
                        onClick = {
                            vm.assignRole(role, null)
                            vm.closeRolePicker()
                        },
                    )
                }
                vm.profiles.forEach { p ->
                    PickerOptionRow(
                        title = p.name,
                        subtitle = "${p.model}" + if (vm.keyIds.contains(p.id)) "" else " · 还没填 Key",
                        selected = currentId == p.id || (role == LlmRole.MAIN && currentId.isBlank() && p.id == vm.profiles.firstOrNull()?.id),
                        onClick = {
                            vm.assignRole(role, p.id)
                            vm.closeRolePicker()
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.closeRolePicker() }) { Text("关闭") }
        },
    )
}

/** 指派弹层选项行：radio + 档案名 + 模型/状态副标题 */
@Composable
private fun PickerOptionRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ============ 查找模型弹层 ============

@Composable
private fun ModelPickerDialog(vm: ModelsViewModel) {
    vm.pickerModels?.let { models ->
        val filtered = remember(models, vm.pickerQuery) {
            if (vm.pickerQuery.isBlank()) models
            else models.filter { it.contains(vm.pickerQuery, ignoreCase = true) }
        }
        AlertDialog(
            onDismissRequest = { vm.dismissPicker() },
            title = { Text("选择模型") },
            text = {
                Column {
                    OutlinedTextField(
                        value = vm.pickerQuery,
                        onValueChange = { vm.pickerQuery = it },
                        label = { Text("搜索（${models.size} 个模型）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                    )
                    LazyColumn(Modifier.fillMaxWidth().height(380.dp).padding(top = 8.dp)) {
                        items(filtered) { name ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.pickModel(name) }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                            ) {
                                Text(name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.dismissPicker() }) { Text("取消") }
            },
        )
    }
}
