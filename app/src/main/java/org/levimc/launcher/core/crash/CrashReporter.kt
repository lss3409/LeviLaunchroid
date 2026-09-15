package org.levimc.launcher.core.crash

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import org.levimc.launcher.BuildConfig
import org.levimc.launcher.settings.FeatureSettings
import org.levimc.launcher.ui.activities.CrashActivity
import org.levimc.launcher.util.LauncherStorage
import xcrash.ICrashCallback
import xcrash.XCrash
import java.io.File

object CrashReporter {
    private const val MAX_CRASHLYTICS_VALUE_LENGTH = 1024
    private const val EXTRA_LOG_PATH = "LOG_PATH"
    private const val EXTRA_SUMMARY = "SUMMARY"
    private const val EXTRA_CRASH_TYPE = "CRASH_TYPE"
    private const val EXTRA_LEGACY_EMERGENCY = "EMERGENCY"
    private const val CRASH_TYPE_JAVA = "JAVA"
    private const val CRASH_TYPE_NATIVE = "NATIVE"
    private const val CRASH_TYPE_ANR = "ANR"

    @Volatile
    private var installed = false

    @Volatile
    private var handlingCrash = false

    @JvmStatic
    fun init(application: Application) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true

            val appContext = application.applicationContext
            configureCrashlytics(appContext)
            if (isCrashProcess()) return

            val logDir = crashLogDir(appContext)
            XCrash.init(application, XCrash.InitParameters().apply {
                setAppVersion(BuildConfig.VERSION_NAME)
                setLogDir(logDir.absolutePath)
                setJavaCallback(buildCrashCallback(appContext, CRASH_TYPE_JAVA))
                setNativeCallback(buildCrashCallback(appContext, CRASH_TYPE_NATIVE))
                setAnrCallback(buildCrashCallback(appContext, CRASH_TYPE_ANR))
                setJavaRethrow(false)
                setNativeRethrow(false)
                setAnrRethrow(false)
            })
        }
    }

    @JvmStatic
    fun sendUnsentReports() {
        if (!isCrashlyticsEnabled()) return
        try {
            FirebaseCrashlytics.getInstance().sendUnsentReports()
        } catch (_: Throwable) {
        }
    }

    @JvmStatic
    fun refreshCrashlyticsCollection(context: Context) {
        configureCrashlytics(context.applicationContext)
    }

    @JvmStatic
    fun isHandlingCrash(): Boolean {
        return handlingCrash
    }

    private fun buildCrashCallback(context: Context, crashType: String): ICrashCallback {
        val appContext = context.applicationContext
        return ICrashCallback { logPath, emergency ->
            handlingCrash = true
            val summary = buildCrashSummary(crashType, emergency)
            recordXCrashToCrashlytics(crashType, logPath, emergency, summary)
            sendUnsentReports()
            launchCrashActivity(appContext, crashType, logPath, emergency, summary)
        }
    }

    private fun launchCrashActivity(
        context: Context,
        crashType: String,
        logPath: String?,
        emergency: String?,
        summary: String
    ) {
        try {
            val intent = Intent(context, CrashActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(EXTRA_LOG_PATH, logPath)
                putExtra(EXTRA_SUMMARY, summary)
                putExtra(EXTRA_CRASH_TYPE, crashType)
                putExtra(EXTRA_LEGACY_EMERGENCY, emergency)
            }
            context.startActivity(intent)
        } catch (error: Throwable) {
            recordHandlerError(error)
        }
    }

    private fun recordXCrashToCrashlytics(
        crashType: String,
        logPath: String?,
        emergency: String?,
        summary: String
    ) {
        if (!isCrashlyticsEnabled()) return
        try {
            FirebaseCrashlytics.getInstance().apply {
                setCustomKey("last_crash_type", crashType)
                setCustomKey("last_crash_summary", trimCrashlyticsValue(summary))
                setCustomKey("version_name", BuildConfig.VERSION_NAME)
                setCustomKey("version_code", BuildConfig.VERSION_CODE)
                setCustomKey("is_beta", BuildConfig.IS_BETA)
                setCustomKey("build_type", BuildConfig.BUILD_TYPE)
                if (!logPath.isNullOrBlank()) {
                    setCustomKey("local_crash_log", trimCrashlyticsValue(logPath))
                    log("xCrash local log: $logPath")
                }
                if (!emergency.isNullOrBlank()) {
                    log("xCrash emergency: ${trimCrashlyticsValue(emergency)}")
                }
                recordException(XCrashCapturedException(crashType, summary))
            }
        } catch (_: Throwable) {
        }
    }

    private fun configureCrashlytics(context: Context) {
        if (!ensureFirebaseInitialized(context)) return
        try {
            FirebaseCrashlytics.getInstance().apply {
                val uploadEnabled = isCrashUploadEnabled()
                setCrashlyticsCollectionEnabled(uploadEnabled)
                if (!uploadEnabled) {
                    deleteUnsentReports()
                    return
                }
                setCustomKey("version_name", BuildConfig.VERSION_NAME)
                setCustomKey("version_code", BuildConfig.VERSION_CODE)
                setCustomKey("is_beta", BuildConfig.IS_BETA)
                setCustomKey("build_type", BuildConfig.BUILD_TYPE)
            }
        } catch (_: Throwable) {
        }
    }

    private fun recordHandlerError(error: Throwable) {
        if (!isCrashlyticsEnabled()) return
        try {
            FirebaseCrashlytics.getInstance().recordException(error)
        } catch (_: Throwable) {
        }
    }

    private fun ensureFirebaseInitialized(context: Context): Boolean {
        if (!isCrashlyticsAvailable()) return false
        return try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            }
            FirebaseApp.getApps(context).isNotEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    private fun isCrashlyticsEnabled(): Boolean {
        return isCrashlyticsAvailable() && isCrashUploadEnabled()
    }

    private fun isCrashlyticsAvailable(): Boolean {
        return BuildConfig.BUILD_TYPE == "debug" ||
            BuildConfig.BUILD_TYPE == "release" ||
            BuildConfig.BUILD_TYPE == "beta"
    }

    private fun isCrashUploadEnabled(): Boolean {
        return try {
            FeatureSettings.getInstance().isCrashUploadEnabled
        } catch (_: Throwable) {
            true
        }
    }

    private fun isCrashProcess(): Boolean {
        return Application.getProcessName().endsWith(":crash")
    }

    private fun crashLogDir(context: Context): File {
        val primary = LauncherStorage.getCrashLogsDir(context)
        if (ensureWritableDir(primary)) return primary

        val externalRoot = context.getExternalFilesDir(null)
        if (externalRoot != null) {
            val external = File(externalRoot, "crash_logs")
            if (ensureWritableDir(external)) return external
        }

        val fallback = File(context.filesDir, "crash_logs")
        ensureWritableDir(fallback)
        return fallback
    }

    private fun ensureWritableDir(dir: File): Boolean {
        if (!ensureDir(dir)) return false
        return try {
            val probe = File(dir, ".write_probe_${Process.myPid()}_${SystemClock.uptimeMillis()}")
            if (!probe.createNewFile()) return false
            probe.delete()
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun ensureDir(dir: File): Boolean {
        return try {
            if (dir.exists()) dir.isDirectory else dir.mkdirs()
        } catch (_: Throwable) {
            false
        }
    }

    private fun buildCrashSummary(crashType: String, emergency: String?): String {
        val trimmedEmergency = emergency?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotBlank() }
        return if (trimmedEmergency.isNullOrBlank()) {
            "xCrash captured $crashType crash"
        } else {
            "xCrash captured $crashType crash: $trimmedEmergency"
        }
    }

    private fun trimCrashlyticsValue(value: String): String {
        return if (value.length > MAX_CRASHLYTICS_VALUE_LENGTH) {
            value.take(MAX_CRASHLYTICS_VALUE_LENGTH)
        } else {
            value
        }
    }

    private class XCrashCapturedException(
        crashType: String,
        summary: String
    ) : RuntimeException("xCrash captured $crashType crash: $summary")
}
