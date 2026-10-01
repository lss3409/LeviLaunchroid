package org.levimc.launcher.core.minecraft

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import org.levimc.launcher.R
import org.levimc.launcher.ui.activities.MainActivity
import org.levimc.launcher.util.ShortcutHelper

/**
 * 桌面快捷方式入口：快捷方式 intent 只携带基本类型（PersistableBundle 限制），
 * 这里通过 VersionManager 重新查出完整 GameVersion（含 versionIsolation 等元数据），
 * 再拉起 MinecraftLoadingActivity 进入对应版本加载流程。
 */
class ShortcutLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isInstalled = intent.getBooleanExtra(ShortcutHelper.EXTRA_SHORTCUT_IS_INSTALLED, false)
        val profileId = intent.getStringExtra(ShortcutHelper.EXTRA_SHORTCUT_PROFILE_ID)
        val versionDir = intent.getStringExtra(ShortcutHelper.EXTRA_SHORTCUT_VERSION_DIR)

        val target = PendingLaunchManager.findVersion(this, isInstalled, profileId, versionDir)
        if (target == null) {
            // 版本已不存在（如删除后重新导入），回退到启动器主页
            val fallback = Intent(this, MainActivity::class.java)
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(fallback)
            finish()
            return
        }

        if (MinecraftActivityState.isRunning()) {
            // 正在运行的版本号与快捷方式相同：直接「回到游戏」（把游戏任务栈带回前台）
            val runningDir = MinecraftActivityState.getRunningVersionDir()
            val sameVersion = target.directoryName != null && target.directoryName == runningDir
            if (sameVersion && MinecraftActivityState.bringGameToFront(this)) {
                finish()
                return
            }
            // 不同版本号：Minecraft 是 native 单例，重复启动会冲突崩溃。
            // v587：删自动关旧游戏开关（双后台配套回退），仅提示
            Toast.makeText(this, R.string.game_already_running, Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
            finish()
            return
        }

        val launchIntent = ShortcutHelper.buildLaunchIntent(this, target)
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
        finish()
    }
}
