package com.example.skiphelper

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.util.Log

/**
 * 跳过成功后的屏幕提示。
 *
 * 为什么不用 Toast：targetSdk 30+ 上，后台服务弹的 Toast 会被系统静默拦截
 * （Android 11 起 "Background custom toast" 受限），服务里调 Toast 用户根本看不到。
 * 用 TYPE_APPLICATION_OVERLAY 的悬浮窗是唯一可靠的方案，代价是需要
 * SYSTEM_ALERT_WINDOW 权限。
 *
 * 拿不到权限时静默降级——提示是锦上添花，不该因此影响跳过本身。
 *
 * 单例里持有 View / WindowManager 是刻意的：窗口生命周期只有 1.2 秒，
 * 服务销毁时 release() 会清掉，且只用 applicationContext，不会拖住 Activity。
 */
@SuppressLint("StaticFieldLeak")
object SkipTip {

    private const val TAG = "SkipHelper"

    private val main = Handler(Looper.getMainLooper())
    private var current: View? = null
    private var wm: WindowManager? = null
    private var hideTask: Runnable? = null

    /** 是否已授予"显示在其他应用上层"。 */
    fun canShow(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /**
     * 显示提示。连续调用只刷新文案并重置计时，不会叠出多个窗口。
     */
    fun show(ctx: Context, text: String, durationMs: Long = 1200L) {
        if (!canShow(ctx)) return
        main.post {
            try {
                dismissInternal()

                val w = ctx.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm = w

                val tv = TextView(ctx).apply {
                    this.text = text
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    val padH = dp(ctx, 18)
                    val padV = dp(ctx, 10)
                    setPadding(padH, padV, padH, padV)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(ctx, 20).toFloat()
                        setColor(Color.parseColor("#B3000000"))
                    }
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    // 固定在屏幕下方居中：顶部太靠近状态栏/通知横幅，底部离手指操作区近、
                    // 也不容易被开屏广告的「跳过」按钮和系统弹窗盖住。
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    y = dp(ctx, 160)
                }

                w.addView(tv, params)
                current = tv

                val task = Runnable { dismissInternal() }
                hideTask = task
                main.postDelayed(task, durationMs)
            } catch (t: Throwable) {
                // 部分 ROM 会拒绝 addView（权限被回收、窗口类型受限等），不影响主流程
                Log.w(TAG, "show tip failed", t)
                dismissInternal()
            }
        }
    }

    private fun dismissInternal() {
        val view = current ?: return
        current = null
        hideTask?.let { main.removeCallbacks(it) }
        hideTask = null
        try {
            wm?.removeView(view)
        } catch (t: Throwable) {
            // 窗口可能已被系统回收（例如服务被关闭），忽略
        }
    }

    /** 服务销毁时清理，避免残留窗口。 */
    fun release() {
        main.post { dismissInternal() }
        wm = null
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
