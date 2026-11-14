package com.example.skiphelper

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * 用 `WRITE_SECURE_SETTINGS` 把自己写进无障碍服务列表。
 *
 * 为什么需要它：HyperOS / MIUI 对侧载应用的「受限制设置」会让无障碍开关点了就弹回，
 * 而零售版又把 `WRITE_SECURE_SETTINGS` 从 adb shell 上摘掉了，`settings put` 走不通。
 * 绕开的办法是把这条权限授给本应用（`adb shell pm grant`，development 级权限可以这样给），
 * 再由应用自己写 `enabled_accessibility_services`——权限在应用自己身上，不受 shell 限制。
 *
 * 只在权限真的授予时才动系统设置；没有权限就什么都不做，界面照常提供手动入口。
 */
object A11ySelfEnable {

    private const val TAG = "SkipHelper"

    fun canWriteSecureSettings(ctx: Context): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun isServiceListed(ctx: Context): Boolean {
        val comp = ComponentName(ctx, SkipService::class.java).flattenToString()
        val cur = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return cur.split(':').any { it.equals(comp, ignoreCase = true) }
    }

    /** @return true 表示列表里已经有本服务（原本就有，或刚刚写入成功）。 */
    fun ensureEnabled(ctx: Context): Boolean {
        if (isServiceListed(ctx)) return true
        if (!canWriteSecureSettings(ctx)) {
            Log.i(TAG, "WRITE_SECURE_SETTINGS 未授予，跳过自启")
            return false
        }
        val comp = ComponentName(ctx, SkipService::class.java).flattenToString()
        val cur = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val merged = (cur.split(':').filter { it.isNotEmpty() } + comp).distinct().joinToString(":")
        return try {
            Settings.Secure.putString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                merged,
            )
            Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i(TAG, "已写入无障碍列表: $merged")
            isServiceListed(ctx)
        } catch (t: Throwable) {
            Log.e(TAG, "写无障碍设置失败", t)
            false
        }
    }

    /** 给界面显示用的 adb 命令。 */
    fun grantHint(pkg: String): String =
        "adb shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS"
}
