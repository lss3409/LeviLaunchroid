package org.levimc.launcher.core.minecraft

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import org.levimc.launcher.core.mods.Mod
import org.levimc.launcher.core.mods.ModManager
import org.levimc.launcher.core.mods.ModNativeLoader
import org.levimc.launcher.core.versions.GameVersion
import org.levimc.launcher.preloader.PreloaderInput
import org.levimc.launcher.preloader.PreloaderSignatureRulesManager
import org.levimc.launcher.util.LauncherStorage
import org.levimc.launcher.core.auth.MsftAuthManager
import org.levimc.launcher.core.auth.storage.XalExporter
import org.levimc.launcher.util.GlobalConfigManager
import org.levimc.launcher.util.PreloadManager
import java.io.File

object MinecraftRuntimePreparer {
    data class PreparedRuntime(
        val version: GameVersion?,
        val gameManager: GamePackageManager,
        val skippedIncompatibleMods: List<String> = emptyList()
    )

    interface ProgressListener {
        fun onProgress(progress: Int, status: String, detail: String? = null)
        fun onLog(message: String)
    }

    @JvmStatic
    @JvmName("nativeSetupRuntime")
    private external fun nativeSetupRuntime(modsPath: String)

    private val noopListener = object : ProgressListener {
        override fun onProgress(progress: Int, status: String, detail: String?) = Unit
        override fun onLog(message: String) = Unit
    }

    fun prepare(
        context: Context,
        launchIntent: Intent,
        listener: ProgressListener = noopListener
    ): PreparedRuntime {
        val trace = LaunchTrace.ensure(launchIntent)
        trace.milestone("Runtime preparation started")
        listener.onProgress(4, "Checking selected version")
        val version = resolveGameVersion(launchIntent)
            ?: throw IllegalArgumentException("No Minecraft version specified")
        listener.onLog("Using ${version.directoryName} (${version.versionCode})")
        trace.mark("Minecraft version resolved", "${version.directoryName} ${version.versionCode}")

        listener.onProgress(12, "Preparing game files")
        val gameManager = GamePackageManager.getInstance(context.applicationContext, version, trace, null)
        trace.mark("GamePackageManager ready")

        listener.onProgress(26, "Preparing launch")
        prepareMinecraftIntent(context, launchIntent, gameManager, version)
        trace.mark("Launch intent prepared")

        listener.onProgress(34, "Checking mods")
        val modManager = ModManager.getInstance()
        modManager.setCurrentVersion(version)
        trace.mark("ModManager state prepared")

        listener.onProgress(40, "Preparing game loader")
        listener.onLog("Loading game loader")
        trace.mark("Game loader load started")
        if (ModManager.ensurePreloaderLoaded()) {
            trace.mark("Game loader load finished")
        } else {
            trace.mark("Game loader load skipped", "preloader unavailable")
        }
        val signatureRulesFile = PreloaderSignatureRulesManager.getRulesFile(context.applicationContext)
        PreloaderInput.configureSignatureRules(signatureRulesFile, version.versionCode)
        trace.mark("Preloader signature rules configured", signatureRulesFile?.absolutePath ?: "<none>")

        listener.onLog("Loading native libraries")
        loadMinecraftLibraries(gameManager, version, listener, trace)

        listener.onProgress(78, "Loading enabled mods")
        listener.onLog("Loading native mods")

        try {
            org.levimc.launcher.core.mods.inbuilt.nativemod.InbuiltModsNative.loadLibrary()
            org.levimc.launcher.core.mods.inbuilt.nativemod.GyroMod.nativePreResolve()
        } catch (_: Throwable) {}

        //nativeSetupRuntime(modManager.currentVersion?.modsDir?.absolutePath.toString())
        val skippedIncompatibleMods = loadNativeMods(context, launchIntent, modManager, listener, trace)

        listener.onProgress(100, "Runtime ready", "Entering Minecraft")
        trace.milestone("Runtime preparation finished")
        return PreparedRuntime(version, gameManager, skippedIncompatibleMods)
    }

    @JvmStatic
    fun resolveGameVersion(intent: Intent): GameVersion? {
        val parcelableVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(MinecraftLauncher.EXTRA_GAME_VERSION, GameVersion::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<GameVersion>(MinecraftLauncher.EXTRA_GAME_VERSION)
        }
        if (parcelableVersion != null) {
            return parcelableVersion
        }

        val versionDir = intent.getStringExtra("MC_PATH")
        val versionCode = intent.getStringExtra("MINECRAFT_VERSION") ?: ""
        val versionDirName = intent.getStringExtra("MINECRAFT_VERSION_DIR") ?: ""
        val isInstalled = intent.getBooleanExtra("IS_INSTALLED", false)

        return if (!versionDir.isNullOrEmpty()) {
            GameVersion(
                versionDirName,
                versionCode,
                versionCode,
                File(versionDir),
                isInstalled,
                MinecraftLauncher.MC_PACKAGE_NAME,
                ""
            )
        } else if (versionCode.isNotEmpty()) {
            GameVersion(
                versionDirName,
                versionCode,
                versionCode,
                null,
                isInstalled,
                MinecraftLauncher.MC_PACKAGE_NAME,
                ""
            )
        } else {
            null
        }
    }

    private fun prepareMinecraftIntent(
        context: Context,
        launchIntent: Intent,
        gameManager: GamePackageManager,
        version: GameVersion
    ) {
        val profileId = MinecraftLauncher.getStorageProfileId(version)
        val versionIsolation = version.versionIsolation
        val filesDir = LauncherStorage.getStorageFilesRoot(context, profileId, versionIsolation, false)
        val externalFilesDir = LauncherStorage.getStorageFilesRoot(context, profileId, versionIsolation, true)
        val dataDir = LauncherStorage.getStorageDataRoot(context, profileId, versionIsolation)
        val cacheDir = LauncherStorage.getStorageCacheRoot(context, profileId, versionIsolation)

        // 正版/盗版统一：XAL 写到启动器内部 filesDir（Java takeover 读取路径）。
        try {
            XalExporter.exportActiveAccountToFiles(context.applicationContext, context.applicationContext.filesDir)
        } catch (t: Throwable) {
            android.util.Log.w("MinecraftRuntimePreparer", "XAL export skipped: " + (t.message ?: t.javaClass.simpleName))
        }

        // 兜底：同时导出到该版本重定向的 filesDir（游戏 native XAL 可能读这里），
        // 与游戏内登录写入的位置一致；两份都写，兼容不同版本的读取路径。
        try {
            XalExporter.exportActiveAccountToFiles(context.applicationContext, filesDir)
        } catch (t: Throwable) {
            android.util.Log.w("MinecraftRuntimePreparer", "XAL version-dir export skipped: " + (t.message ?: t.javaClass.simpleName))
        }

        // 过渡方案：若存在「游戏内登录」捕获的模板，注入目标版本目录（覆盖导出），
        // 模板是游戏自己签发的完整状态，游戏读取即可静默登录（等价于备份恢复）。
        try {
            org.levimc.launcher.core.auth.storage.XalTemplateStore.inject(context.applicationContext, filesDir)
        } catch (t: Throwable) {
            android.util.Log.w("MinecraftRuntimePreparer", "XAL template inject skipped: " + (t.message ?: t.javaClass.simpleName))
        }
        try {
            org.levimc.launcher.core.auth.storage.XalTemplateStore.inject(context.applicationContext, context.applicationContext.filesDir)
        } catch (t: Throwable) {
            android.util.Log.w("MinecraftRuntimePreparer", "XAL template inject (launcher dir) skipped: " + (t.message ?: t.javaClass.simpleName))
        }

        // 全局配置与预加载写到启动器重定向目录（externalFilesDir），
        // 与游戏运行时 getExternalFilesDir 重定向一致，避免 Android 11+ 无法写入 MC 原版目录。
        try {
            GlobalConfigManager.apply(context.applicationContext, externalFilesDir)
        } catch (t: Throwable) {
            android.util.Log.w("MinecraftRuntimePreparer", "Global config skipped: " + (t.message ?: t.javaClass.simpleName))
        }

        if (version.isInstalled) {
            try {
                mergePreloadedPacks(context, externalFilesDir)
            } catch (t: Throwable) {
                android.util.Log.w("MinecraftRuntimePreparer", "merge preload skipped: " + (t.message ?: t.javaClass.simpleName))
            }
        } else {
            if (versionIsolation) {
                try {
                    mergeSharedContent(context, externalFilesDir)
                } catch (t: Throwable) {
                    android.util.Log.w("MinecraftRuntimePreparer", "merge shared content skipped: " + (t.message ?: t.javaClass.simpleName))
                }
            }

            // 预加载：把共享文件夹里标记为「预加载」的资源包/行为包复制进目标版本，
            // 并写入 global_resource_packs.json 作为全局资源（创建世界无需手动添加）。
            try {
                mergePreloadedPacks(context, externalFilesDir)
            } catch (t: Throwable) {
                android.util.Log.w("MinecraftRuntimePreparer", "merge preload skipped: " + (t.message ?: t.javaClass.simpleName))
            }
        }

        version.versionDir?.let { launchIntent.putExtra("MC_PATH", it.absolutePath) }
        launchIntent.putExtra("IS_INSTALLED", version.isInstalled)
        launchIntent.putExtra("VERSION_ISOLATION", versionIsolation)
        launchIntent.putExtra(MinecraftLauncher.EXTRA_STORAGE_PROFILE_ID, profileId)
        launchIntent.putExtra(MinecraftLauncher.EXTRA_STORAGE_FILES_DIR, filesDir.absolutePath)
        launchIntent.putExtra(MinecraftLauncher.EXTRA_STORAGE_EXTERNAL_FILES_DIR, externalFilesDir.absolutePath)
        launchIntent.putExtra(MinecraftLauncher.EXTRA_STORAGE_DATA_DIR, dataDir.absolutePath)
        launchIntent.putExtra(MinecraftLauncher.EXTRA_STORAGE_CACHE_DIR, cacheDir.absolutePath)

        val mcInfo: ApplicationInfo = if (version.isInstalled) {
            gameManager.getPackageContext().applicationInfo
        } else {
            MinecraftLauncher(context).createFakeApplicationInfo(version, MinecraftLauncher.MC_PACKAGE_NAME)
        }
        launchIntent.putExtra("MC_SRC", mcInfo.sourceDir)
        val splitSourceDirs = mcInfo.splitSourceDirs
        if (splitSourceDirs != null) {
            launchIntent.putExtra("MC_SPLIT_SRC", arrayListOf(*splitSourceDirs))
        }
        launchIntent.putExtra("MINECRAFT_VERSION", version.versionCode)
        launchIntent.putExtra("MINECRAFT_VERSION_DIR", version.directoryName)
        launchIntent.putExtra("LAUNCH_VERTICALLY", version.launchVertically)
        launchIntent.putExtra("VERSION_ISOLATION", version.versionIsolation)
    }

    // 把共享文件夹 games/com.mojang 下的内容（世界/资源包/行为包/皮肤包/截图）复制进版本目录，
    // 使版本隔离时游戏也能读取共享内容。用复制而非软链接，避免 Android FUSE 下软链接不生效。
    private fun mergeSharedContent(context: Context, externalFilesDir: File) {
        val sharedGameData = LauncherStorage.getSharedGameDataDir(context, true)
        val versionGameData = File(externalFilesDir, "games/com.mojang")
        val dirNames = arrayOf("minecraftWorlds", "resource_packs", "behavior_packs", "skin_packs", "Screenshots", "structures")
        for (dirName in dirNames) {
            val sharedDir = File(sharedGameData, dirName)
            val versionDir = File(versionGameData, dirName)
            if (!versionDir.exists()) versionDir.mkdirs()
            if (!sharedDir.exists() || !sharedDir.isDirectory) continue
            sharedDir.listFiles()?.forEach { item ->
                val target = File(versionDir, item.name)
                if (target.exists()) return@forEach
                try {
                    item.copyRecursively(target, overwrite = false)
                    android.util.Log.d("MinecraftRuntimePreparer", "merged shared: $dirName/${item.name}")
                } catch (e: Throwable) {
                    android.util.Log.w("MinecraftRuntimePreparer", "merge shared failed: ${item.name}: ${e.message}")
                }
            }
        }
    }

    // 把共享文件夹里标记为「预加载」的资源包/行为包复制进目标版本目录，
    // 并写入 global_resource_packs.json 作为全局资源（创建世界时无需手动添加即生效）。
    private fun mergePreloadedPacks(context: Context, externalFilesDir: File) {
        val preloadManager = PreloadManager(context.applicationContext)
        val preloadUuids = preloadManager.getPreloadUuids()
        if (preloadUuids.isEmpty()) return

        val sharedGameData = LauncherStorage.getSharedGameDataDir(context, true)
        val versionGameData = File(externalFilesDir, "games/com.mojang")

        val globalResourcePacks = mutableListOf<org.json.JSONObject>()

        val dirNames = arrayOf("resource_packs", "behavior_packs")
        for (dirName in dirNames) {
            val sharedDir = File(sharedGameData, dirName)
            val versionDir = File(versionGameData, dirName)
            if (!versionDir.exists()) versionDir.mkdirs()
            if (!sharedDir.exists() || !sharedDir.isDirectory) continue
            sharedDir.listFiles()?.forEach { item ->
                if (!item.isDirectory) return@forEach
                val manifest = readManifestHeader(item) ?: return@forEach
                val uuid = manifest.first
                if (!preloadUuids.contains(uuid.lowercase())) return@forEach

                val target = File(versionDir, item.name)
                if (!target.exists()) {
                    try {
                        item.copyRecursively(target, overwrite = false)
                        android.util.Log.d("MinecraftRuntimePreparer", "preloaded: $dirName/${item.name}")
                    } catch (e: Throwable) {
                        android.util.Log.w("MinecraftRuntimePreparer", "preload failed: ${item.name}: ${e.message}")
                    }
                }

                // 资源包（非行为包）才加入全局资源列表
                if (dirName == "resource_packs") {
                    val obj = org.json.JSONObject()
                    obj.put("pack_id", uuid)
                    obj.put("version", manifest.second)
                    globalResourcePacks.add(obj)
                }
            }
        }

        if (globalResourcePacks.isNotEmpty()) {
            writeGlobalResourcePacks(versionGameData, globalResourcePacks)
        }
    }

    // 返回 (uuid, version数组)。version 必须用包 manifest 里的真实值，否则游戏无法匹配全局资源。
    private fun readManifestHeader(packDir: File): Pair<String, org.json.JSONArray>? {
        return try {
            val manifestFile = File(packDir, "manifest.json")
            if (!manifestFile.exists()) return null
            val json = org.json.JSONObject(manifestFile.readText())
            val header = json.optJSONObject("header") ?: return null
            val uuid = header.optString("uuid", null)
            if (uuid.isNullOrEmpty()) return null

            val versionArr = when (val v = header.opt("version")) {
                is org.json.JSONArray -> v
                is String -> parseVersionString(v)
                is Number -> org.json.JSONArray().put(v.toInt())
                else -> org.json.JSONArray().put(1).put(0).put(0)
            }
            Pair(uuid, versionArr)
        } catch (e: Throwable) {
            null
        }
    }

    private fun parseVersionString(v: String): org.json.JSONArray {
        val arr = org.json.JSONArray()
        v.split(".").forEach { part ->
            try {
                arr.put(part.trim().toInt())
            } catch (_: Throwable) {
                arr.put(0)
            }
        }
        return if (arr.length() == 0) org.json.JSONArray().put(1).put(0).put(0) else arr
    }

    private fun writeGlobalResourcePacks(versionGameData: File, packs: List<org.json.JSONObject>) {
        try {
            // global_resource_packs.json 必须位于 games/com.mojang/minecraftpe/ 下（与 options.txt 同级），
            // 且根节点是 JSON 数组（[{"pack_id":..., "version":[...]}]），不是 {"packs":[...]} 对象。
            val minecraftPeDir = File(versionGameData, "minecraftpe")
            if (!minecraftPeDir.exists()) minecraftPeDir.mkdirs()
            val file = File(minecraftPeDir, "global_resource_packs.json")
            val array = org.json.JSONArray()
            for (obj in packs) {
                array.put(obj)
            }
            file.writeText(array.toString(2))
            android.util.Log.d("MinecraftRuntimePreparer", "global_resource_packs.json written: ${file.absolutePath}")
        } catch (e: Throwable) {
            android.util.Log.w("MinecraftRuntimePreparer", "write global_resource_packs failed: ${e.message}")
        }
    }

    private fun loadMinecraftLibraries(
        gameManager: GamePackageManager,
        version: GameVersion,
        listener: ProgressListener,
        trace: LaunchTrace
    ) {
        listener.onProgress(46, "Loading native libraries")
        trace.mark("Minecraft library loading started")

        if (shouldLoadHttpClient(version)) {
            loadLibrary(gameManager, "c++_shared", 48, true, listener, trace)
            loadLibrary(gameManager, "HttpClient.Android", 52, true, listener, trace)
        }

        if (shouldLoadMaesdk(version)) {
            val excludeLibs = HashSet<String>()
            val excludeReasons = HashMap<String, String>()
            if (shouldLoadHttpClient(version)) {
                excludeLibs.add("c++_shared")
                excludeLibs.add("HttpClient.Android")
                excludeReasons["c++_shared"] = "already loaded before the bundle"
                excludeReasons["HttpClient.Android"] = "already loaded before the bundle"
            }
            if (!shouldLoadPlayFab(version)) {
                excludeLibs.add("PlayFabMultiplayer")
                excludeReasons["PlayFabMultiplayer"] = "not required by this Minecraft version"
            }
            listener.onProgress(56, "Loading native libraries")
            trace.mark("Minecraft native library bundle loading started", "1.21.110+ layout")
            val failedLibraries = gameManager
                .loadAllLibraries(excludeLibs, trace, listener, 56, 74, excludeReasons)
                .filterNot { it.loaded }
            if (failedLibraries.isNotEmpty()) {
                val details = failedLibraries.joinToString(separator = "\n") { result ->
                    "${result.fileName}: ${result.detail ?: "unknown error"}"
                }
                trace.error("Native library bundle load failed", details)
                throw RuntimeException("Failed to load native libraries:\n$details")
            }
            trace.mark("Minecraft native library bundle loading finished")
        } else {
            if (!shouldLoadHttpClient(version)) {
                loadLibrary(gameManager, "c++_shared", 50, true, listener, trace)
            }
            loadLibrary(gameManager, "fmod", 56, true, listener, trace)
            loadLibrary(gameManager, "MediaDecoders_Android", 62, true, listener, trace)
            loadLibrary(gameManager, "minecraftpe", 70, true, listener, trace)
            loadLibrary(gameManager, "gxcore", 74, true, listener, trace)
        }
        trace.mark("Minecraft library loading finished")
    }

    private fun loadLibrary(
        gameManager: GamePackageManager,
        name: String,
        progress: Int,
        required: Boolean,
        listener: ProgressListener,
        trace: LaunchTrace
    ) {
        val fileName = toLibraryFileName(name)
        listener.onProgress(progress, "Loading native libraries", fileName)
        listener.onLog("Loading native library: $fileName")
        trace.mark("Native library load started", fileName)
        val result = gameManager.loadLibraryDetailed(name)
        if (!result.loaded && required) {
            listener.onLog("Failed to load native library: ${result.fileName}")
            trace.error(
                "Required library load failed",
                "${result.fileName} in ${result.durationMs}ms from ${result.source}" +
                    (result.detail?.let { " - $it" } ?: "")
            )
            throw RuntimeException("Failed to load ${result.fileName}: ${result.detail ?: "unknown error"}")
        }
        if (result.loaded) {
            listener.onLog("Loaded native library: ${result.fileName}")
            trace.mark(
                "Native library load finished",
                "${result.fileName} in ${result.durationMs}ms from ${result.source}" +
                    (result.detail?.let { " - $it" } ?: "")
            )
        } else {
            listener.onLog("Skipped native library: ${result.fileName}")
            trace.mark(
                "Native library load skipped",
                "${result.fileName} in ${result.durationMs}ms from ${result.source}" +
                    (result.detail?.let { " - $it" } ?: "")
            )
        }
    }

    private fun loadNativeMods(
        context: Context,
        launchIntent: Intent,
        modManager: ModManager,
        listener: ProgressListener,
        trace: LaunchTrace
    ): List<String> {
        val cacheDir = resolveNativeModCacheDir(context, launchIntent)
        trace.mark(
            "Native mod loading started",
            "mods=${modManager.currentVersion?.modsDir?.absolutePath ?: "<unknown>"}"
        )
        val modLoadLabels = java.util.IdentityHashMap<Mod, String>()
        val skippedIncompatibleMods = mutableListOf<String>()
        ModNativeLoader.loadEnabledSoMods(
            modManager,
            cacheDir,
            object : ModNativeLoader.LoadListener {
                override fun onScanStarted(totalEnabled: Int) {
                    if (totalEnabled > 0) {
                        listener.onLog("Loading $totalEnabled enabled mod(s)")
                    } else {
                        listener.onLog("No enabled native mods")
                    }
                }

                override fun onModLoadStarted(mod: Mod, index: Int, total: Int) {
                    val progress = 80 + ((index - 1) * 15 / total.coerceAtLeast(1))
                    val label = "$index/$total"
                    modLoadLabels[mod] = label
                    listener.onProgress(progress, "Loading native mods", "$label ${mod.displayName}")
                    trace.mark("Native mod load started", "$label ${mod.displayName}")
                }

                override fun onModLoadFinished(mod: Mod) {
                    val label = modLoadLabels.remove(mod)?.let { "$it " }.orEmpty()
                    listener.onLog("Loaded mod: $label${mod.displayName}")
                    trace.mark("Native mod load finished", mod.displayName)
                }

                override fun onModLoadSkipped(mod: Mod, minecraftVersion: String) {
                    val label = modLoadLabels.remove(mod)?.let { "$it " }.orEmpty()
                    skippedIncompatibleMods.add(mod.displayName)
                    listener.onLog("Skipped incompatible mod ${label}${mod.displayName} for Minecraft $minecraftVersion")
                    trace.warning("Native mod skipped as incompatible", "${mod.displayName}: $minecraftVersion")
                }

                override fun onModLoadFailed(mod: Mod, error: Throwable) {
                    trace.warning("Native mod load failed", "${mod.displayName}: ${error.message ?: error.javaClass.simpleName}")
                    listener.onLog("Failed to load mod ${mod.displayName}: ${error.message ?: error.javaClass.simpleName}")
                }

                override fun onMessage(message: String) {
                    listener.onLog(message)
                    trace.warning("Native mod loader message", message)
                }
            }
        )
        listener.onProgress(96, "Native mods ready")
        listener.onLog("Native mods ready")
        trace.mark("Native mod loading finished")
        return skippedIncompatibleMods
    }

    private fun resolveNativeModCacheDir(context: Context, launchIntent: Intent): File {
        val versionDirName = launchIntent.getStringExtra("MINECRAFT_VERSION_DIR")
            ?.takeIf { it.isNotBlank() }
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?: "default"
        return File(context.cacheDir, "native_mods/$versionDirName").also { it.mkdirs() }
    }

    private fun shouldLoadMaesdk(version: GameVersion): Boolean {
        val versionCode = version.versionCode
        val targetVersion = if (versionCode.contains("beta")) "1.21.110.22" else "1.21.110"
        return isVersionAtLeast(versionCode, targetVersion)
    }

    private fun shouldLoadHttpClient(version: GameVersion): Boolean {
        val versionCode = version.versionCode
        val targetVersion = if (versionCode.contains("beta")) "1.21.130.20" else "1.21.130"
        return isVersionAtLeast(versionCode, targetVersion)
    }

    private fun shouldLoadPlayFab(version: GameVersion): Boolean {
        val versionCode = version.versionCode
        val targetVersion = if (versionCode.contains("beta")) "1.21.130.20" else "1.21.130"
        return isVersionAtLeast(versionCode, targetVersion)
    }

    private fun toLibraryFileName(name: String): String {
        return if (name.startsWith("lib") && name.endsWith(".so")) name else "lib${name.removePrefix("lib").removeSuffix(".so")}.so"
    }

    private fun isVersionAtLeast(currentVersion: String, targetVersion: String): Boolean {
        return try {
            val current = currentVersion.replace(Regex("[^0-9.]"), "").split(".")
            val target = targetVersion.split(".")
            val maxLength = maxOf(current.size, target.size)

            for (i in 0 until maxLength) {
                val currentPart = current.getOrNull(i)?.toIntOrNull() ?: 0
                val targetPart = target.getOrNull(i)?.toIntOrNull() ?: 0

                if (currentPart > targetPart) return true
                if (currentPart < targetPart) return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
