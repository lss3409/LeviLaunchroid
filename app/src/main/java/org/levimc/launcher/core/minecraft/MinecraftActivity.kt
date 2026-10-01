package org.levimc.launcher.core.minecraft

import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatEditText
import com.mojang.minecraftpe.MainActivity
import org.levimc.launcher.R

import org.levimc.launcher.core.mods.ModManager
import org.levimc.launcher.core.mods.inbuilt.nativemod.PojavControlsMod
import org.levimc.launcher.core.mods.inbuilt.overlay.InbuiltOverlayManager
import org.levimc.launcher.preloader.PreloaderInput
import org.levimc.launcher.settings.FeatureSettings
import org.levimc.launcher.util.HardcoreBackupManager
import org.levimc.pojavcontrols.PojavControls
import org.levimc.pojavcontrols.PojavControlsHost
import java.io.File

/** 后台久置阈值：超过此时长回前台视为渲染不可恢复（v518 自愈）。 */
// v534：黑屏自愈阈值 5 分钟→90 秒（开了前台服务保活时渲染面丢失黑屏高发，
// 缩短等待——切后台超过 90 秒回来直接结束会话回启动器，世界自动存档兜底）
// v561：后台久置自愈阈值 90s→60s——vivo 类厂商后台机制激进（冻结 GL 上下文
// 更频繁），后台超 60s 回前台就走静默重启（游戏自动存档回启动器），
// 宁可重启也不要黑屏卡死；联想 ZUI 机制宽松（90s 时也只黑过 1 次）
private const val LONG_PAUSE_HEAL_MS = 60_000L

class MinecraftActivity : MainActivity(), PojavControlsHost {

    private lateinit var gameManager: GamePackageManager
    private lateinit var trace: LaunchTrace
    private var overlayManager: InbuiltOverlayManager? = null
    private var normalExitPrepared = false
    private var normalExitRestartScheduled = false
    private var gameRuntimeStarted = false
    private var preloaderTextInput: PreloaderTextInput? = null
    private var previousInputFocus: View? = null

    private val hardcoreBackupHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var hardcoreBackupRunning = false
    /** v518 久置自愈：后台久置后回前台，基岩版 EGL surface 不恢复（黑屏）。 */
    private var lastPauseElapsed = 0L
    /** v544：本次会话起点（累计游玩时长统计）。 */
    private var sessionStartElapsed = 0L
    private val hardcoreBackupRunnable = object : Runnable {
        override fun run() {
            runHardcoreBackupCheck()
        }
    }

    private class PreloaderTextInput(context: Context) : AppCompatEditText(context) {
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
            val target = super.onCreateInputConnection(outAttrs) ?: return null
            return object : InputConnectionWrapper(target, true) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    if (!text.isNullOrEmpty() && PreloaderInput.onTextInput(text)) {
                        super.commitText(text, newCursorPosition)
                        return true
                    }
                    return super.commitText(text, newCursorPosition)
                }

                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    if (beforeLength > 0 && dispatchBackspace()) {
                        return true
                    }
                    return super.deleteSurroundingText(beforeLength, afterLength)
                }

                override fun deleteSurroundingTextInCodePoints(
                    beforeLength: Int,
                    afterLength: Int
                ): Boolean {
                    if (beforeLength > 0 && dispatchBackspace()) {
                        return true
                    }
                    return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
                }

                private fun dispatchBackspace(): Boolean {
                    val downConsumed = PreloaderInput.onKeyEvent(
                        KeyEvent.KEYCODE_DEL,
                        0,
                        true
                    )
                    val upConsumed = PreloaderInput.onKeyEvent(
                        KeyEvent.KEYCODE_DEL,
                        0,
                        false
                    )
                    return downConsumed || upConsumed
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        trace = LaunchTrace.ensure(intent)
        trace.mark("MinecraftActivity onCreate entered")
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(resolveLaunchBackgroundColor()))

        if (savedInstanceState != null) {
            trace.mark("MinecraftActivity finishing restored instance")
            // v545：进程被杀后系统恢复实例时，实例 native 库尚未加载——
            // 直接 super.onCreate 会因 GameActivity.initializeNativeCode 无实现而崩
            // （UnsatisfiedLinkError）。先走 prepare 加载实例库再结束会话。
            try {
                val preparedRuntime = MinecraftLaunchSession.getPreparedRuntime()
                    ?: MinecraftRuntimePreparer.prepare(applicationContext, intent)
                gameManager = preparedRuntime.gameManager
            } catch (t: Throwable) {
                android.util.Log.w("MinecraftActivity", "恢复实例库加载失败，直接结束", t)
            }
            gameRuntimeStarted = true
            super.onCreate(null)
            finish()
            return
        }

        try {
            val preparedRuntime = MinecraftLaunchSession.getPreparedRuntime()
                ?: MinecraftRuntimePreparer.prepare(applicationContext, intent)
            gameManager = preparedRuntime.gameManager
            configureMinecraftFirebase()
            trace.mark("Prepared runtime consumed")
        } catch (throwable: Throwable) {
            trace.error("MinecraftActivity prepare failed", formatLaunchFailure(throwable))
            // v432：必须先调 super.onCreate() 再 return——Android 要求
            // 每个 onCreate 都调 super，否则抛 SuperNotCalledException
            // 崩溃（官方代码固有 bug；1.20.30 prepare 失败实测触发）
            gameRuntimeStarted = true
            super.onCreate(null)
            returnToLauncherAfterLaunchFailure()
            return
        }
        // 崩溃分析：记录本次会话与启用的模组快照（用于异常退出后的中文分析提示）
        try {
            val enabledModNames = java.util.ArrayList<String>()
            for (mod in ModManager.getInstance().mods) {
                if (mod.isEnabled) enabledModNames.add(mod.displayName)
            }
            org.levimc.launcher.util.CrashAnalyzer.onGameLaunchStarted(
                this, enabledModNames, intent?.getStringExtra("MINECRAFT_VERSION"))
        } catch (t: Throwable) {
            trace.warning("Crash analyzer snapshot failed", t.message)
        }
        trace.mark("Native mod enable started")
        ModManager.enableLoadedMods()
        trace.mark("Native mod enable finished")
        setLeviKeepRunningInBackground(FeatureSettings.getInstance().isForegroundServiceEnabled())
        trace.mark("Mojang MainActivity super.onCreate starting")
        try {
            gameRuntimeStarted = true
            super.onCreate(savedInstanceState)
        } catch (throwable: Throwable) {
            trace.error("Mojang MainActivity super.onCreate failed", formatLaunchFailure(throwable))
            returnToLauncherAfterLaunchFailure()
            return
        }
        trace.mark("Mojang MainActivity super.onCreate finished")

        MinecraftForegroundService.startIfEnabled(this)

        val launchVertically = intent.getBooleanExtra("LAUNCH_VERTICALLY", false)
        if (launchVertically) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        
        initializePreloaderTextInput()
        PreloaderInput.setActivity(this)
        MinecraftActivityState.onCreated(this)
        // v544：会话起点（onDestroy 累加游玩时长）
        sessionStartElapsed = android.os.SystemClock.elapsedRealtime()

        trace.mark("MinecraftActivity onCreate finished")
    }


    private fun configureMinecraftFirebase() {
        val appId = gameManager.getGameStringResource("google_app_id")
            ?: "1:486187589451:android:b2331110821fe2304bd2ce"
        val apiKey = gameManager.getGameStringResource("google_api_key")
            ?: gameManager.getGameStringResource("google_crash_reporting_api_key")
            ?: ""
        val projectId = gameManager.getGameStringResource("project_id")
            ?: "minecraft-bedrock-57580"
        val senderId = gameManager.getGameStringResource("gcm_defaultSenderId")
            ?: "486187589451"
        intent.putExtra("MINECRAFT_FIREBASE_APP_ID", appId)
        intent.putExtra("MINECRAFT_FIREBASE_API_KEY", apiKey)
        intent.putExtra("MINECRAFT_FIREBASE_PROJECT_ID", projectId)
        intent.putExtra("MINECRAFT_FIREBASE_SENDER_ID", senderId)
    }

    private fun returnToLauncherAfterLaunchFailure() {
        gameRuntimeStarted = false
        MinecraftLaunchSession.clear()
        MinecraftProcessRestarter.restartLauncherAfterMinecraftExit(this, org.levimc.launcher.ui.activities.MainActivity.sForeground)
        finish()
    }

    private fun formatLaunchFailure(throwable: Throwable): String {
        return throwable.message ?: throwable.javaClass.simpleName
    }

    private fun resolveLaunchBackgroundColor(): Int {
        val typedValue = android.util.TypedValue()
        return if (theme.resolveAttribute(android.R.attr.colorBackground, typedValue, true)) {
            typedValue.data
        } else {
            Color.BLACK
        }
    }

    private fun startInbuiltModServices() {
        overlayManager = InbuiltOverlayManager(this)
        overlayManager?.showEnabledOverlays()
    }

    private fun stopInbuiltModServices() {
        overlayManager?.hideAllOverlays()
        overlayManager = null
    }

    // v450：性能监控悬浮窗已整体移除（MemoryMonitor 删除）

    override fun onNewIntent(intent: Intent) {
        // 仅深链（带 data 的 VIEW）才更新 intent 并交给 Mojang 处理。
        // 「回到游戏」的纯带回前台请求不覆盖启动参数（存储路径等 extras），
        // 否则游戏会丢失重定向目录，世界列表/深链都会失效。
        if (intent.data != null && Intent.ACTION_VIEW == intent.action) {
            setIntent(intent)
            super.onNewIntent(intent)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (PojavControls.onActivityResult(requestCode, resultCode, data)) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onBackPressed() {
        if (PojavControls.closeEditor()) return
        super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        // v518 久置自愈：后台久置（>5 分钟）后回前台，基岩版渲染面不会恢复——
        // 实测黑屏。直接结束会话走正常退出流程（静默重启进程回启动器，
        // 世界进度由游戏自动存档兜底），避免把黑屏甩给用户。
        if (lastPauseElapsed > 0
            && android.os.SystemClock.elapsedRealtime() - lastPauseElapsed > LONG_PAUSE_HEAL_MS
        ) {
            android.util.Log.i("MinecraftActivity", "后台久置后恢复，主动结束会话防黑屏")
            finish()
            return
        }
        if (!isFinishing) {
            normalExitPrepared = false
            normalExitRestartScheduled = false
        }
        MinecraftActivityState.onResumed(this)

        if (overlayManager == null) {
            startInbuiltModServices()
        }

        // v521：联机会话激活时显示游戏内悬浮窗
        try {
            if (org.levimc.launcher.core.online.EasyTierManager.get().state
                == org.levimc.launcher.core.online.EasyTierManager.State.CONNECTED) {
                org.levimc.launcher.core.online.OnlineOverlay.get(this).show()
                // v527：回前台恢复语音采集
                org.levimc.launcher.core.online.voice.VoiceEngine.get(this).resume()
            }
        } catch (t: Throwable) {
            android.util.Log.w("MinecraftActivity", "online overlay failed", t)
        }

        startHardcoreBackupScheduler()
    }

    /** 游玩中自动备份：按设定的间隔周期检查并备份极限世界（最后一层保险）。 */
    private fun startHardcoreBackupScheduler() {
        hardcoreBackupHandler.removeCallbacks(hardcoreBackupRunnable)
        val manager = HardcoreBackupManager(applicationContext)
        if (!manager.isEnabled) return
        // 仅在「局内备份」时机下启动定时器
        if (manager.getBackupTrigger() != HardcoreBackupManager.TRIGGER_INGAME) return
        // 立即执行一次检查，再按间隔循环
        hardcoreBackupHandler.postDelayed(hardcoreBackupRunnable, 1000L)
    }

    private fun runHardcoreBackupCheck() {
        if (hardcoreBackupRunning) return
        val manager = HardcoreBackupManager(applicationContext)
        if (!manager.isEnabled) return

        hardcoreBackupRunning = true
        val intervalMs = manager.getIntervalMs()
        Thread({
            try {
                val extFilesDir = getExternalFilesDir(null)
                val worldsDir = extFilesDir?.let { File(it, "games/com.mojang/minecraftWorlds") }
                val hardcoreWorlds = HardcoreBackupManager.scanHardcoreWorldsIn(worldsDir)
                manager.checkAndBackup(hardcoreWorlds, object : HardcoreBackupManager.Callback {
                    override fun onBackedUp(world: org.levimc.launcher.core.content.WorldItem, backupPath: String) {
                        android.util.Log.i("HardcoreBackup", "In-game backed up: " + backupPath)
                        // 游戏内 Toast 提示备份完成
                        runOnUiThread {
                            android.widget.Toast.makeText(
                                this@MinecraftActivity,
                                getString(R.string.hardcore_backup_completed_toast, world.getWorldName()),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    override fun onSkipped(world: org.levimc.launcher.core.content.WorldItem, reason: String) {
                        android.util.Log.d("HardcoreBackup", "Skipped ${world.getWorldName()}: $reason")
                    }

                    override fun onFinished(backedUp: Int, skipped: Int) {
                        hardcoreBackupRunning = false
                        // 继续下一轮定时检查
                        hardcoreBackupHandler.postDelayed(hardcoreBackupRunnable, intervalMs.coerceAtLeast(60_000L))
                    }
                })
            } catch (t: Throwable) {
                android.util.Log.w("HardcoreBackup", "check failed: ${t.message}")
                hardcoreBackupRunning = false
                hardcoreBackupHandler.postDelayed(hardcoreBackupRunnable, intervalMs.coerceAtLeast(60_000L))
            }
        }, "hardcore-in-game-backup").start()
    }

    private fun stopHardcoreBackupScheduler() {
        hardcoreBackupHandler.removeCallbacks(hardcoreBackupRunnable)
    }

    /** 「局内暂停时备份」：游戏切到后台/暂停时触发一次备份。 */
    private fun backupOnPauseIfNeeded() {
        val manager = HardcoreBackupManager(applicationContext)
        if (!manager.isEnabled) return
        if (manager.getBackupTrigger() != HardcoreBackupManager.TRIGGER_PAUSE) return
        if (hardcoreBackupRunning) return
        hardcoreBackupRunning = true
        Thread({
            try {
                val extFilesDir = getExternalFilesDir(null)
                val worldsDir = extFilesDir?.let { File(it, "games/com.mojang/minecraftWorlds") }
                val hardcoreWorlds = HardcoreBackupManager.scanHardcoreWorldsIn(worldsDir)
                manager.checkAndBackup(hardcoreWorlds, object : HardcoreBackupManager.Callback {
                    override fun onBackedUp(world: org.levimc.launcher.core.content.WorldItem, backupPath: String) {
                        android.util.Log.i("HardcoreBackup", "Pause backed up: " + backupPath)
                    }

                    override fun onSkipped(world: org.levimc.launcher.core.content.WorldItem, reason: String) {
                        android.util.Log.d("HardcoreBackup", "Pause skipped ${world.getWorldName()}: $reason")
                    }

                    override fun onFinished(backedUp: Int, skipped: Int) {
                        hardcoreBackupRunning = false
                    }
                })
            } catch (t: Throwable) {
                android.util.Log.w("HardcoreBackup", "pause backup failed: ${t.message}")
                hardcoreBackupRunning = false
            }
        }, "hardcore-pause-backup").start()
    }

    private fun isMouseSource(source: Int): Boolean {
        return (source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
            (source and InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
    }

    private fun getMouseButton(event: KeyEvent): Int {
        if (!isMouseSource(event.source)) {
            return 0
        }
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_1 -> MotionEvent.BUTTON_PRIMARY
            KeyEvent.KEYCODE_BUTTON_2 -> MotionEvent.BUTTON_SECONDARY
            KeyEvent.KEYCODE_BUTTON_3 -> MotionEvent.BUTTON_TERTIARY
            KeyEvent.KEYCODE_BUTTON_4 -> MotionEvent.BUTTON_BACK
            KeyEvent.KEYCODE_BUTTON_5 -> MotionEvent.BUTTON_FORWARD
            else -> 0
        }
    }

    private fun shouldConsumeMouseMotion(event: MotionEvent): Boolean {
        return isMouseSource(event.source) &&
            PreloaderInput.onMouseMotion(
                event.actionMasked,
                event.actionButton,
                event.buttonState
            )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val mouseButton = getMouseButton(event)
        if (mouseButton != 0 &&
            (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) &&
            PreloaderInput.onMouse(mouseButton, event.action == KeyEvent.ACTION_DOWN)) {
            return true
        }

        val unicodeChar = event.unicodeChar
        if (event.action == KeyEvent.ACTION_UP) {
            if (org.levimc.launcher.preloader.PreloaderInput.onKeyEvent(event.keyCode, unicodeChar, false)) {
                return true
            }
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (org.levimc.launcher.preloader.PreloaderInput.onKeyEvent(event.keyCode, unicodeChar, true)) {
                return true
            }
        }

        overlayManager?.let { manager ->
            if (manager.handleKeyEvent(event.keyCode, event.action)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (shouldConsumeMouseMotion(event)) {
            return true
        }

        if (org.levimc.launcher.core.mods.inbuilt.overlay.VirtualCursorMod.isActive()) {
            org.levimc.launcher.core.mods.inbuilt.overlay.VirtualCursorMod.processTouchEvent(event, this)
            return true
        }

        if (PojavControls.ownsTouchInput()) {
            return super.dispatchTouchEvent(event)
        }

        val actionIndex = event.actionIndex
        if (org.levimc.launcher.preloader.PreloaderInput.onTouch(
                event.actionMasked,
                event.getPointerId(actionIndex),
                event.getX(actionIndex),
                event.getY(actionIndex)
            )) {
            return true
        }

        overlayManager?.handleTouchEvent(event)

        return super.dispatchTouchEvent(event)
    }

    fun dispatchGenericMotionEventToGame(event: MotionEvent): Boolean {
        return super.dispatchGenericMotionEvent(event)
    }

    fun dispatchTouchEventToGame(event: MotionEvent): Boolean {
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (shouldConsumeMouseMotion(event)) {
            return true
        }

        if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
            event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE) {
            overlayManager?.handleMouseEvent(event)
        }

        if (event.action == MotionEvent.ACTION_SCROLL) {
            val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (vScroll != 0f) {
                overlayManager?.let { manager ->
                    if (manager.handleScrollEvent(vScroll)) {
                        return true
                    }
                }
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun pojavSendKey(bedrockKeyCode: Int, down: Boolean) {
        PojavControlsMod.nativeSendKey(bedrockKeyCode, down)
    }

    override fun pojavSendMouseButton(androidButton: Int, down: Boolean) {
        PojavControlsMod.nativeSendMouseButton(androidButton, down)
    }

    override fun pojavSendScroll(vertical: Float) {
        PojavControlsMod.nativeSendScroll(vertical)
    }

    override fun pojavSendLookDelta(deltaX: Float, deltaY: Float) {
        PojavControlsMod.nativeSendLookDelta(deltaX, deltaY)
    }

    override fun pojavSendPointer(x: Float, y: Float) {
        PojavControlsMod.nativeSendPointer(x, y)
    }

    override fun pojavSendTouch(event: MotionEvent): Boolean {
        return super.onTouchEvent(event)
    }

    override fun pojavShowKeyboard() {
        showSoftKeyboard()
    }

    override fun pojavIsMenuOpen(): Boolean {
        if (PreloaderInput.isShowingMenu() || PreloaderInput.isPauseMenuOpen()) return true
        return !PreloaderInput.shouldForceGlobalModMenu() && !PreloaderInput.isHudScreenOpen()
    }

    override fun onPause() {
        lastPauseElapsed = android.os.SystemClock.elapsedRealtime()
        // v527：退后台停语音采集（不再收麦），回前台自动恢复
        try {
            org.levimc.launcher.core.online.voice.VoiceEngine.get(this).suspend()
        } catch (t: Throwable) {
        }
        org.levimc.launcher.core.online.OnlineOverlay.hideIfShown()
        stopHardcoreBackupScheduler()
        backupOnPauseIfNeeded()
        val shouldRestartAfterNormalExit = shouldRestartAfterNormalExit()
        if (shouldRestartAfterNormalExit) {
            // v577：native unload 挪后台线程——BSChat 的 C++ 卸载生命周期
            // 在主线程会卡死（与 enable 死锁同源），退出游戏后启动器
            // 主线程挂掉 → MainActivity 黑屏（平板实测）
            Thread({ ModManager.disableAndUnloadLoadedMods() }, "mod-unload").start()
            prepareNormalExitCleanup()
            scheduleNormalExitProcessRestart()
        }
        MinecraftActivityState.onPaused(this)
        super.onPause()
    }

    // v527：麦克风权限结果转发给联机悬浮窗
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        try {
            if (requestCode == 100) {
                var granted = false
                for (i in permissions.indices) {
                    if (permissions[i] == android.Manifest.permission.RECORD_AUDIO
                        && i < grantResults.size
                        && grantResults[i] == android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        granted = true
                    }
                }
                org.levimc.launcher.core.online.OnlineOverlay.forwardMicPermissionResult(this, granted)
            }
        } catch (t: Throwable) {
        }
    }

    override fun onDestroy() {
        // v544：累计游玩时长（分钟，联机玩家详情卡展示）
        try {
            if (sessionStartElapsed > 0) {
                val mins = (android.os.SystemClock.elapsedRealtime() - sessionStartElapsed) / 60_000L
                if (mins > 0) {
                    org.levimc.launcher.core.online.PlayerIdentity.addPlayMinutes(this, mins)
                }
            }
        } catch (t: Throwable) {
        }
        stopHardcoreBackupScheduler()
        // v577：native unload 挪后台线程（主线程跑 BSChat unload 会卡死
        // → 返回启动器黑屏，与 onPause 处同因）
        Thread({ ModManager.disableAndUnloadLoadedMods() }, "mod-unload").start()
        val shouldPrepareNormalExit = shouldRestartAfterNormalExit()
        if (shouldPrepareNormalExit) {
            prepareNormalExitCleanup()
        }

        preloaderTextInput = null
        previousInputFocus = null
        PreloaderInput.clearActivity()
        MinecraftActivityState.onDestroyed(this)
        MinecraftLaunchSession.clear()
        stopInbuiltModServices()

        setLeviKeepRunningInBackground(false)
        MinecraftForegroundService.stop(this)

        try {
            super.onDestroy()
        } finally {
            if (shouldPrepareNormalExit) {
                scheduleNormalExitProcessRestart()
            }
        }
    }

    private fun shouldRestartAfterNormalExit(): Boolean {
        return gameRuntimeStarted && isFinishing
    }

    private fun prepareNormalExitCleanup() {
        if (normalExitPrepared) return
        normalExitPrepared = true
        // 崩溃分析：标记本次会话为正常退出
        org.levimc.launcher.util.CrashAnalyzer.onGameExitNormal(this)
    }

    private fun scheduleNormalExitProcessRestart() {
        if (normalExitRestartScheduled) return
        normalExitRestartScheduled = true

        MinecraftProcessRestarter.restartLauncherAfterMinecraftExit(this, org.levimc.launcher.ui.activities.MainActivity.sForeground)
    }

    override fun getAssets(): AssetManager {
        return if (::gameManager.isInitialized) {
            gameManager.getAssets()
        } else {
            super.getAssets()
        }
    }

    override fun getFilesDir(): File {
        // 正版/盗版统一走启动器重定向目录：内部文件（世界/资源包等在内部存储时也读这里），
        // 避免 Android 11+ 无法写入正版 MC 原版 Android/data 目录。证书验证只依赖 getDataDir。
        return resolveStorageDir(MinecraftLauncher.EXTRA_STORAGE_FILES_DIR, super.getFilesDir())
    }

    override fun tick() {
        super.tick()
        overlayManager?.tick()
    }

    override fun getDataDir(): File {
        // 恢复 v107 行为：正版/盗版统一重定向到启动器目录。
        // 注意：若证书验证失败，再考虑用 originalMcContext 保持原版 dataDir 单独处理。
        return resolveStorageDir(MinecraftLauncher.EXTRA_STORAGE_DATA_DIR, super.getDataDir())
    }

    override fun getExternalFilesDir(type: String?): File? {
        // 正版/盗版统一走启动器重定向目录：外部文件（世界/资源包等）写到启动器自己的目录，
        // 避免 Android 11+ 无法写入正版 MC 原版 Android/data 目录的问题。
        // dataDir 仍保持 MC 原版（见 getDataDir），证书验证不受影响。
        val baseDir = resolveStorageDir(
            MinecraftLauncher.EXTRA_STORAGE_EXTERNAL_FILES_DIR,
            super.getExternalFilesDir(null)
        )
        return if (type.isNullOrEmpty()) {
            baseDir
        } else {
            File(baseDir, type).also { it.mkdirs() }
        }
    }

    override fun getExternalFilesDirs(type: String?): Array<File> {
        // 关键：AGDK GameActivity 传给 native 的 externalDataPath 取自
        // getExternalFilesDirs(null)[0]（复数版本），而 ContextImpl 的复数实现
        // 不经过单数 getExternalFilesDir，直接用包名拼 Android/data 默认目录。
        // 不重写它的话，游戏原生始终认为外部存储是启动器默认目录（那里没有世界），
        // minecraft://?load= 深链查不到世界会静默失败。
        val baseDir = resolveStorageDir(
            MinecraftLauncher.EXTRA_STORAGE_EXTERNAL_FILES_DIR,
            super.getExternalFilesDir(null)
        )
        return if (type.isNullOrEmpty()) {
            arrayOf(baseDir)
        } else {
            arrayOf(File(baseDir, type).also { it.mkdirs() })
        }
    }

    override fun getInternalStoragePath(): String {
        return getFilesDir().absolutePath
    }

    override fun getExternalStoragePath(): String {
        return (getExternalFilesDir(null) ?: getFilesDir()).absolutePath
    }

    // 正版 MC 的原版 context（createPackageContext 创建），用于返回 MC 自己的目录；
    // 盗版 MC 返回 null，走下面的 resolveStorageDir 重定向。
    private fun originalMcContext(): android.content.Context? {
        if (intent?.getBooleanExtra("IS_INSTALLED", false) != true) return null
        return if (::gameManager.isInitialized) gameManager.getPackageContext() else null
    }

    private fun resolveStorageDir(extraName: String, fallback: File?): File {
        val path = intent?.getStringExtra(extraName)
        val dir = if (!path.isNullOrEmpty()) File(path) else fallback ?: super.getFilesDir()
        if (!dir.exists()) {
            dir.mkdirs()
        }
        android.util.Log.i("MinecraftActivity", "resolveStorageDir $extraName -> ${dir.absolutePath}"
                + if (path.isNullOrEmpty()) " [FALLBACK=${fallback?.absolutePath}]" else "")
        return dir
    }

    override fun getDatabasePath(name: String): File {
        originalMcContext()?.let { return it.getDatabasePath(name) }
        val dbDir = File(getDataDir(), "databases")
        if (!dbDir.exists()) {
            dbDir.mkdirs()
        }
        return File(dbDir, name)
    }

    override fun getCacheDir(): File {
        originalMcContext()?.let { return it.cacheDir }
        return resolveStorageDir(MinecraftLauncher.EXTRA_STORAGE_CACHE_DIR, super.getCacheDir())
    }

    private fun initializePreloaderTextInput() {
        val input = PreloaderTextInput(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isEmojiCompatEnabled = false
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.TRANSPARENT)
            isCursorVisible = false
            alpha = 0f
            visibility = View.GONE
        }
        findViewById<ViewGroup>(android.R.id.content).addView(
            input,
            ViewGroup.LayoutParams(1, 1)
        )
        preloaderTextInput = input
    }

    fun showSoftKeyboard() {
        runOnUiThread {
            val input = preloaderTextInput ?: return@runOnUiThread
            previousInputFocus = currentFocus?.takeUnless { it === input }
            input.visibility = View.VISIBLE
            input.setText("")
            input.requestFocus()
            input.setSelection(0)

            val inputMethodManager =
                getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.restartInput(input)
            if (!inputMethodManager.showSoftInput(
                    input,
                    InputMethodManager.SHOW_IMPLICIT
                )
            ) {
                inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
            }
        }
    }

    fun hideSoftKeyboard() {
        runOnUiThread {
            val input = preloaderTextInput ?: return@runOnUiThread
            val inputMethodManager =
                getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.hideSoftInputFromWindow(input.windowToken, 0)
            input.clearFocus()
            input.visibility = View.GONE

            previousInputFocus
                ?.takeIf { it.isAttachedToWindow && it.visibility == View.VISIBLE }
                ?.requestFocus()
            previousInputFocus = null
        }
    }
}
