package com.myraa.android

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Configuration
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
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var appsGrid: LinearLayout
    private lateinit var assistantEntryPoint: AssistantInvocationEntryPoint
    private lateinit var voiceController: VoiceInteractionController
    private var voiceDialog: Dialog? = null
    private var launcherOrbEntry: View? = null
    private var launcherOrb: TitanCoreOrbView? = null
    private var voiceOrb: TitanCoreOrbView? = null
    private var voiceStatus: TextView? = null
    private var voiceMessage: TextView? = null
    private var voiceDraft: EditText? = null
    private var voiceButton: Button? = null
    private var voiceSnapshot = VoiceInteractionSnapshot(
        VoiceInteractionState.IDLE,
        "",
        "",
    )
    private var activityStarted = false
    private var permissionRequestPending = false
    private var restoreVoiceDialog = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdge()

        val restoredDraft = savedInstanceState?.getString(VOICE_DRAFT_STATE).orEmpty()
        restoreVoiceDialog = savedInstanceState?.getBoolean(VOICE_DIALOG_STATE) == true
        voiceController = VoiceInteractionController(this, ::renderVoiceSnapshot)
        if (restoredDraft.isNotEmpty()) voiceController.updateDraft(restoredDraft)
        assistantEntryPoint = object : AssistantInvocationEntryPoint {
            override fun request(source: AssistantInvocationSource) {
                showVoiceInteraction()
            }
        }
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
        val orbEntry = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener {
                assistantEntryPoint.request(AssistantInvocationSource.TOUCH)
            }
        }
        launcherOrbEntry = orbEntry
        launcherOrb = TitanCoreOrbView(this).apply {
            setInteractionState(voiceSnapshot.state)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        orbEntry.addView(launcherOrb, LinearLayout.LayoutParams(-1, dp(176)))
        orbEntry.addView(
            TextView(this).apply {
                text = getString(R.string.voice_open_description).uppercase(Locale.ROOT)
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(255, 215, 105))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(-1, dp(24)),
        )
        root.addView(
            orbEntry,
            FrameLayout.LayoutParams(dp(204), dp(204), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL),
        )
        updateLauncherOrbDescription(voiceSnapshot.state)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val (top, bottom) = systemBarInsets(insets)
            scrollView.setPadding(dp(12), top + dp(18), dp(12), bottom + dp(220))
            (launcherOrbEntry?.layoutParams as? FrameLayout.LayoutParams)?.let { layoutParams ->
                layoutParams.bottomMargin = bottom + dp(4)
                launcherOrbEntry?.layoutParams = layoutParams
            }
            insets
        }
        setContentView(root)
        populateApps()
        if (restoreVoiceDialog) {
            restoreVoiceDialog = false
            showVoiceInteraction()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(VOICE_DRAFT_STATE, voiceSnapshot.draft)
        outState.putBoolean(VOICE_DIALOG_STATE, voiceDialog?.isShowing == true)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        launcherOrb?.requestLayout()
        voiceOrb?.requestLayout()
        voiceDialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9f).toInt(),
            (resources.displayMetrics.heightPixels * 0.9f).toInt(),
        )
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        updateOrbVisibility()
    }

    override fun onStop() {
        activityStarted = false
        updateOrbVisibility()
        if (::voiceController.isInitialized) voiceController.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        voiceDialog?.dismiss()
        voiceDialog = null
        if (::voiceController.isInitialized) voiceController.shutdown()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        permissionRequestPending = false
        if (!activityStarted || isFinishing || isDestroyed) {
            voiceController.cancel()
        } else if (voiceDialog?.isShowing != true) {
            voiceController.cancel()
        } else if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            voiceController.startListening()
        } else {
            voiceController.permissionDenied()
        }
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

    private fun showVoiceInteraction() {
        if (voiceDialog?.isShowing == true) return
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            setBackgroundColor(Color.rgb(23, 32, 51))
        }
        content.addView(
            TextView(this).apply {
                text = getString(R.string.voice_title)
                textSize = 22f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        voiceStatus = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
            setTextColor(Color.rgb(99, 224, 207))
            contentDescription = getString(R.string.voice_open_description)
        }
        voiceOrb = TitanCoreOrbView(this).apply {
            setInteractionState(voiceSnapshot.state)
            setActivityVisible(activityStarted)
        }
        content.addView(
            voiceOrb,
            LinearLayout.LayoutParams(-1, dp(190)),
        )
        content.addView(voiceStatus, LinearLayout.LayoutParams(-1, -2))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        voiceMessage = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        }
        controls.addView(voiceMessage, LinearLayout.LayoutParams(-1, -2))
        voiceDraft = EditText(this).apply {
            hint = getString(R.string.voice_draft_hint)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setBackgroundColor(Color.rgb(35, 47, 69))
            gravity = Gravity.TOP or Gravity.START
            minLines = 3
            maxLines = 6
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            addTextChangedListener(SimpleTextWatcher { text ->
                voiceController.updateDraft(text)
            })
        }
        controls.addView(
            voiceDraft,
            LinearLayout.LayoutParams(-1, dp(120)).apply { bottomMargin = dp(12) },
        )

        val voiceActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        voiceButton = Button(this).apply {
            setOnClickListener {
                if (voiceSnapshot.state == VoiceInteractionState.LISTENING ||
                    voiceSnapshot.state == VoiceInteractionState.PROCESSING
                ) {
                    voiceController.cancel()
                } else {
                    requestVoiceCapture()
                }
            }
        }
        voiceActions.addView(voiceButton, LinearLayout.LayoutParams(0, -2, 1f))
        voiceActions.addView(
            Button(this).apply {
                text = getString(R.string.voice_speak_draft)
                setOnClickListener { voiceController.speakDraft() }
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        controls.addView(voiceActions, LinearLayout.LayoutParams(-1, -2))
        controls.addView(
            Button(this).apply {
                text = getString(R.string.voice_confirm_send)
                isEnabled = false
                contentDescription = getString(R.string.voice_send_disabled)
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        controls.addView(
            TextView(this).apply {
                text = getString(R.string.voice_send_disabled)
                textSize = 12f
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(8))
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        controls.addView(
            Button(this).apply {
                text = getString(R.string.voice_close)
                setOnClickListener {
                    voiceController.cancel()
                    voiceDialog?.dismiss()
                }
            },
            LinearLayout.LayoutParams(-1, -2),
        )

        val controlsScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(controls, FrameLayout.LayoutParams(-1, -2))
        }
        content.addView(controlsScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        voiceDialog = Dialog(this).apply {
            setContentView(content)
            setOnDismissListener {
                voiceController.cancel()
                voiceOrb = null
                voiceStatus = null
                voiceMessage = null
                voiceDraft = null
                voiceButton = null
                updateOrbVisibility()
            }
            show()
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.9f).toInt(),
                (resources.displayMetrics.heightPixels * 0.9f).toInt(),
            )
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        updateOrbVisibility()
        renderVoiceSnapshot(voiceSnapshot)
    }

    private fun requestVoiceCapture() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            voiceController.startListening()
            return
        }
        voiceController.permissionRequired()
        if (!permissionRequestPending) {
            permissionRequestPending = true
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        }
    }

    private fun renderVoiceSnapshot(snapshot: VoiceInteractionSnapshot) {
        voiceSnapshot = snapshot
        voiceOrb?.setInteractionState(snapshot.state)
        val stateLabel = voiceStateLabel(snapshot.state)
        launcherOrb?.setInteractionState(snapshot.state)
        updateLauncherOrbDescription(snapshot.state)
        voiceStatus?.apply {
            text = getString(stateLabel)
            contentDescription = "${getString(R.string.voice_title)}: ${getString(stateLabel)}"
        }
        voiceStatus?.setTextColor(
            when (snapshot.state) {
                VoiceInteractionState.ERROR, VoiceInteractionState.PERMISSION_REQUIRED -> Color.rgb(255, 142, 142)
                VoiceInteractionState.CANCELLED -> Color.LTGRAY
                VoiceInteractionState.SPEAKING -> Color.rgb(223, 177, 139)
                else -> Color.rgb(99, 224, 207)
            },
        )
        voiceMessage?.text = snapshot.message
        val editor = voiceDraft
        if (editor != null && editor.text.toString() != snapshot.draft) {
            editor.setText(snapshot.draft)
            editor.setSelection(editor.text.length)
        }
        voiceButton?.text = if (
            snapshot.state == VoiceInteractionState.LISTENING ||
            snapshot.state == VoiceInteractionState.PROCESSING
        ) {
            getString(R.string.voice_stop)
        } else {
            getString(R.string.voice_start)
        }
    }

    private fun updateOrbVisibility() {
        launcherOrb?.setActivityVisible(activityStarted && voiceDialog?.isShowing != true)
        voiceOrb?.setActivityVisible(activityStarted && voiceDialog?.isShowing == true)
    }

    private fun updateLauncherOrbDescription(state: VoiceInteractionState) {
        val label = getString(voiceStateLabel(state)).lowercase(Locale.ROOT)
        launcherOrbEntry?.contentDescription =
            "${getString(R.string.orb_accessibility_description, label)}. ${getString(R.string.voice_open_description)}"
    }

    private fun voiceStateLabel(state: VoiceInteractionState): Int = when (state) {
        VoiceInteractionState.IDLE -> R.string.voice_state_idle
        VoiceInteractionState.LISTENING -> R.string.voice_state_listening
        VoiceInteractionState.PROCESSING -> R.string.voice_state_processing
        VoiceInteractionState.THINKING -> R.string.voice_state_thinking
        VoiceInteractionState.SPEAKING -> R.string.voice_state_speaking
        VoiceInteractionState.ERROR -> R.string.voice_state_error
        VoiceInteractionState.CANCELLED -> R.string.voice_state_cancelled
        VoiceInteractionState.PERMISSION_REQUIRED -> R.string.voice_state_permission
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
        const val REQUEST_RECORD_AUDIO = 41
        const val VOICE_DRAFT_STATE = "voice_draft"
        const val VOICE_DIALOG_STATE = "voice_dialog_open"
    }

    private class SimpleTextWatcher(
        private val onChanged: (String) -> Unit,
    ) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            onChanged(s?.toString().orEmpty())
        }
        override fun afterTextChanged(s: android.text.Editable?) = Unit
    }
}
