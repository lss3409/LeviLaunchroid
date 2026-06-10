package org.levimc.launcher.core.minecraft

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.levimc.launcher.R
import org.levimc.launcher.core.crash.CrashReporter
import org.levimc.launcher.ui.activities.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MinecraftLoadingActivity : AppCompatActivity(), MinecraftRuntimePreparer.ProgressListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var progressBar: ProgressBar
    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var returnButton: Button

    @Volatile
    private var returningToLauncher = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MinecraftReturnCoordinator.cancelLauncherReturnFallback(this)
        applyLaunchOrientation()
        hideSystemUi()
        setContentView(R.layout.activity_minecraft_loading)

        progressBar = findViewById(R.id.minecraftLaunchProgress)
        statusView = findViewById(R.id.minecraftLaunchStatus)
        detailView = findViewById(R.id.minecraftLaunchDetail)
        logView = findViewById(R.id.minecraftLaunchLog)
        logScroll = findViewById(R.id.minecraftLaunchLogScroll)
        returnButton = findViewById(R.id.minecraftLaunchReturn)

        returnButton.setOnClickListener {
            appendLog("Returning to launcher")
            returnToLauncher()
        }

        MinecraftLaunchSession.clear()
        appendLog("Launch preparation page is visible")
        window.decorView.postDelayed({ startPreparing() }, FIRST_FRAME_DELAY_MS)
    }

    private fun applyLaunchOrientation() {
        val version = MinecraftRuntimePreparer.resolveGameVersion(intent)
        val launchVertically = version?.launchVertically
            ?: intent.getBooleanExtra("LAUNCH_VERTICALLY", false)
        if (launchVertically) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun startPreparing() {
        executor.execute {
            try {
                val gameIntent = Intent(intent).apply {
                    setClass(this@MinecraftLoadingActivity, MinecraftActivity::class.java)
                }
                val preparedRuntime = MinecraftRuntimePreparer.prepare(this, gameIntent, this)
                MinecraftLaunchSession.setPreparedRuntime(preparedRuntime)

                mainHandler.post {
                    if (returningToLauncher || isFinishing || isDestroyed) return@post
                    updateProgress(100, getString(R.string.minecraft_loading_complete), getString(R.string.minecraft_loading_entering_game))
                    appendLog("Starting MinecraftActivity")
                    startActivity(gameIntent)
                    finish()
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                }
            } catch (throwable: Throwable) {
                mainHandler.post {
                    showFailure(throwable)
                }
            }
        }
    }

    override fun onProgress(progress: Int, status: String, detail: String?) {
        mainHandler.post {
            updateProgress(progress, status, detail)
        }
    }

    override fun onLog(message: String) {
        mainHandler.post {
            appendLog(message)
        }
    }

    private fun updateProgress(progress: Int, status: String, detail: String?) {
        if (isFinishing || isDestroyed) return
        progressBar.progress = progress.coerceIn(0, 100)
        statusView.text = status
        detailView.text = detail.orEmpty()
    }

    private fun appendLog(message: String) {
        if (isFinishing || isDestroyed) return
        val timestamp = timeFormat.format(Date())
        logView.append("[$timestamp] $message\n")
        logScroll.post {
            logScroll.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun showFailure(throwable: Throwable) {
        if (isFinishing || isDestroyed) return
        val message = throwable.message ?: throwable.javaClass.simpleName
        updateProgress(100, getString(R.string.minecraft_loading_failed), message)
        appendLog("ERROR: $message")
        returnButton.visibility = android.view.View.VISIBLE
    }

    override fun onBackPressed() {
        appendLog("Launch cancelled")
        returnToLauncher()
    }

    private fun returnToLauncher() {
        if (returningToLauncher) return
        returningToLauncher = true

        CrashReporter.disarmRecovery(this)
        MinecraftLaunchSession.clear()

        val launcherIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(launcherIntent)
        finish()
        mainHandler.postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, RETURN_KILL_DELAY_MS)
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private companion object {
        private const val FIRST_FRAME_DELAY_MS = 120L
        private const val RETURN_KILL_DELAY_MS = 250L
    }
}
