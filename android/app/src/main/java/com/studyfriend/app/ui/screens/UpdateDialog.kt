package com.studyfriend.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 发现新版本弹窗：更新说明 + 下载进度 + 安装。AppNav 顶层挂载，任何页面都能弹 */
@Composable
fun UpdateDialog(vm: UpdateViewModel) {
    val release = vm.available ?: return

    AlertDialog(
        onDismissRequest = { if (!vm.downloading) vm.dismissUpdate() },
        title = { Text("发现新版本 v${release.versionName}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (release.notes.isNotBlank()) {
                    Text(
                        release.notes,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (vm.downloading) {
                    Text("正在下载更新包… ${vm.progress}%")
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { vm.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                vm.downloadError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (vm.needsInstallPermission) {
                    Text(
                        "还没有允许「学伴」安装应用：请在打开的系统页面里打开开关，" +
                            "然后回到这里再点一次「安装更新」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            when {
                vm.downloading -> {}
                vm.needsInstallPermission || vm.downloadedApk != null ->
                    Button(onClick = { vm.tryInstall() }) { Text("安装更新") }
                else -> Button(onClick = { vm.startDownload() }) { Text("立即更新") }
            }
        },
        dismissButton = {
            TextButton(
                onClick = { vm.dismissUpdate() },
                enabled = !vm.downloading,
            ) { Text(if (vm.downloading) "后台继续" else "以后再说") }
        },
    )

    TokenGuideDialog(vm)
}

/** GitHub 只读令牌申请步骤（面向第一次配置的老板，逐步点出来） */
@Composable
private fun TokenGuideDialog(vm: UpdateViewModel) {
    if (!vm.guideOpen) return
    AlertDialog(
        onDismissRequest = { vm.closeGuide() },
        title = { Text("如何获取 GitHub 令牌？") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "用电脑或手机浏览器打开 github.com 并登录后：\n" +
                        "\n1. 点右上角头像 → Settings（设置）\n" +
                        "2. 左侧最底部 → Developer settings（开发者设置）\n" +
                        "3. Personal access tokens → Fine-grained tokens → Generate new token\n" +
                        "4. Repository access 选「Only select repositories」，勾选 Studying-with-friend\n" +
                        "5. Permissions → Repository permissions → Contents 设为 Read-only\n" +
                        "6. Expiration 有效期选 1 年（到期后重新生成换一个即可）\n" +
                        "7. 点 Generate token，复制生成的一长串（github_pat_ 开头），回到设置页粘贴并点「保存令牌」\n" +
                        "\n这个令牌只有「读取」权限，只用于下载更新包；换手机后需要重新填一次。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.closeGuide() }) { Text("知道了") }
        },
    )
}
