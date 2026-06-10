package org.levimc.launcher.core.minecraft

object MinecraftLaunchSession {
    @Volatile
    private var preparedRuntime: MinecraftRuntimePreparer.PreparedRuntime? = null

    fun setPreparedRuntime(runtime: MinecraftRuntimePreparer.PreparedRuntime) {
        preparedRuntime = runtime
    }

    fun getPreparedRuntime(): MinecraftRuntimePreparer.PreparedRuntime? {
        return preparedRuntime
    }

    fun clear() {
        preparedRuntime = null
    }
}
