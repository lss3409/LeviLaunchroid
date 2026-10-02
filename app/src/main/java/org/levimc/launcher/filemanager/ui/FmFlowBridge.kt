package org.levimc.launcher.filemanager.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Java Activity 收集 Kotlin Flow 的生命周期感知桥：
 * 前台（ON_START）时启动收集，后台（ON_STOP）时取消，销毁时释放。
 */
fun <T> collectFlow(owner: LifecycleOwner, flow: Flow<T>, onEach: (T) -> Unit) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var job: Job? = null
    val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> {
                if (job == null) job = scope.launch { flow.collect(onEach) }
            }
            Lifecycle.Event.ON_STOP -> {
                job?.cancel()
                job = null
            }
            Lifecycle.Event.ON_DESTROY -> scope.cancel()
            else -> {}
        }
    }
    owner.lifecycle.addObserver(observer)
}
