package com.studyfriend.app.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * 唤起系统安装器安装已下载的 APK（M7）。
 * targetSdk 26+ 覆盖安装第三方来源 APK 需要「安装未知应用」授权：
 * 没授权时先跳系统开关页，用户打开后回来再点一次「安装更新」即可。
 */
object UpdateInstaller {

    private const val AUTHORITY = "com.studyfriend.app.update"

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** 返回 false = 缺「安装未知应用」权限，已帮用户打开系统设置页 */
    fun install(context: Context, apk: File): Boolean {
        if (!canInstall(context)) {
            openInstallPermissionSettings(context)
            return false
        }
        val uri = FileProvider.getUriForFile(context, AUTHORITY, apk)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }
}
