package com.myraa.android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.io.IOException
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var appsGrid: LinearLayout
    private lateinit var assistantEntryPoint: AssistantInvocationEntryPoint

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdge()

        assistantEntryPoint = UnavailableAssistantInvocationEntryPoint(this)
        try {
            AndroidLocalDeviceIdentityStore(this).loadOrCreateDeviceIdentity()
        } catch (error: IOException) {
            Log.e(TAG, "Local device identity is unavailable; protected actions remain disabled.", error)
        }

        val root = FrameLayout(this).apply {
            addView(WallpaperView(this@MainActivity), FrameLayout.LayoutParams(-1, -1))
            setOnLongClickListener {
                assistantEntryPoint.request(AssistantInvocationSource.TOUCH)
                true
            }
        }
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        appsGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        scrollView.addView(
            appsGrid,
            FrameLayout.LayoutParams(-1, -2),
        )
        root.addView(scrollView, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val (top, bottom) = systemBarInsets(insets)
            scrollView.setPadding(dp(12), top + dp(18), dp(12), bottom + dp(18))
            insets
        }
        setContentView(root)
        populateApps()
    }

    @Suppress("DEPRECATION")
    private fun configureEdgeToEdge() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    }

    private fun systemBarInsets(insets: WindowInsets): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            bars.top to bars.bottom
        } else {
            @Suppress("DEPRECATION")
            val top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val bottom = insets.systemWindowInsetBottom
            top to bottom
        }
    }

    private fun populateApps() {
        appsGrid.removeAllViews()
        val launchableApps = findLaunchableApps()
        launchableApps.chunked(COLUMN_COUNT).forEach { rowApps ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            repeat(COLUMN_COUNT) { index ->
                val app = rowApps.getOrNull(index)
                val cell = FrameLayout(this)
                val icon = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = app?.label
                    tooltipText = app?.label
                    app?.let { setImageDrawable(it.icon) }
                    if (app != null) {
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { launchApp(app.component) }
                    }
                }
                cell.addView(
                    icon,
                    FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER),
                )
                row.addView(cell, LinearLayout.LayoutParams(0, dp(88), 1f))
            }
            appsGrid.addView(row, LinearLayout.LayoutParams(-1, dp(88)))
        }
    }

    private fun findLaunchableApps(): List<LaunchableApp> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val results = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                query,
                PackageManager.ResolveInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(query, 0)
        }

        return results.mapNotNull(::toLaunchableApp)
            .filter { it.component.packageName != packageName }
            .distinctBy { it.component }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
    }

    private fun toLaunchableApp(resolveInfo: ResolveInfo): LaunchableApp? {
        val activity = resolveInfo.activityInfo ?: return null
        if (!activity.exported) return null
        return try {
            LaunchableApp(
                label = resolveInfo.loadLabel(packageManager).toString(),
                icon = resolveInfo.loadIcon(packageManager),
                component = ComponentName(activity.packageName, activity.name),
            )
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun launchApp(component: ComponentName) {
        val intent = Intent.makeMainActivity(component)
        val resolved = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        if (resolved?.activityInfo?.packageName != component.packageName) {
            Toast.makeText(this, "This app is no longer available.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "This app could not be opened.", Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, "Android denied this launch.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class LaunchableApp(
        val label: String,
        val icon: Drawable,
        val component: ComponentName,
    )

    private class WallpaperView(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val width = width.toFloat()
            val height = height.toFloat()
            paint.shader = LinearGradient(
                0f,
                0f,
                width,
                height,
                intArrayOf(Color.rgb(17, 22, 48), Color.rgb(27, 55, 81), Color.rgb(20, 38, 63)),
                null,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, width, height, paint)
            paint.shader = null
            paint.color = Color.argb(45, 99, 224, 207)
            canvas.drawCircle(width * 0.88f, height * 0.22f, width * 0.58f, paint)
            paint.color = Color.argb(38, 223, 177, 139)
            canvas.drawCircle(width * 0.1f, height * 0.76f, width * 0.62f, paint)
            paint.color = Color.argb(22, 190, 207, 255)
            val wave = Path().apply {
                moveTo(0f, height * 0.62f)
                cubicTo(width * 0.35f, height * 0.46f, width * 0.62f, height * 0.84f, width, height * 0.58f)
                lineTo(width, height)
                lineTo(0f, height)
                close()
            }
            canvas.drawPath(wave, paint)
        }
    }

    private companion object {
        const val TAG = "MYRAAHome"
        const val COLUMN_COUNT = 4
    }
}
