package com.malayalam.assistant.ai

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RamStatus(
    val activeModel: ManagedModelId? = null,
    val activeModelLabel: String = "Idle (0 AI models in RAM)",
    val freeRamMb: Long = 0L,
    val totalRamMb: Long = 4096L,
    val isSystemLowMemory: Boolean = false,
    val lastUnloadReason: String = "startup"
)

/**
 * Strict 4 GB RAM Central ModelCoordinator ("One Model at a Time"):
 *  1. Global coroutine Mutex guarantees ONLY ONE AI model is ever loaded in RAM at any instant.
 *  2. Before loading Model B, ALWAYS closes/releases Model A, clears references, and runs GC cleanup.
 *  3. Auto-unloads any idle model after 45 seconds of inactivity.
 *  4. Immediately releases resident models inside `onTrimMemory(...)` and `onOverlayHidden()`.
 */
class ModelCoordinator(private val context: Context) {

    private data class LoadedSlot(
        val modelId: ManagedModelId,
        val instance: AutoCloseable
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var loadedSlot: LoadedSlot? = null
    private var idleUnloadJob: Job? = null

    private val _ramStatus = MutableStateFlow(readDeviceMemory(active = null, reason = "startup"))
    val ramStatus: StateFlow<RamStatus> = _ramStatus.asStateFlow()

    init {
        // Lightweight 5-second memory telemetry refresh for the live RAM badge
        scope.launch {
            while (true) {
                delay(5_000L)
                refreshMemoryBadge()
            }
        }
    }

    /**
     * Exclusively acquires `modelId`. If a different model is currently resident in RAM,
     * it is deterministically closed and cleaned up BEFORE `factory()` allocates `modelId`.
     */
    suspend fun <T : AutoCloseable, R> withModel(
        modelId: ManagedModelId,
        factory: suspend () -> T,
        block: suspend (T) -> R
    ): R = mutex.withLock {
        idleUnloadJob?.cancel()

        val current = loadedSlot
        @Suppress("UNCHECKED_CAST")
        val typedInstance: T = if (current != null && current.modelId == modelId) {
            current.instance as T
        } else {
            if (current != null) {
                closeSlotUnlocked(current, reason = "switching_${current.modelId.key}_to_${modelId.key}")
                // Brief pause so native XNNPACK / ONNX mmap pages return to OS before mapping next model
                delay(75L)
            }
            Log.i(TAG, "Loading model ${modelId.key} on CPU XNNPACK...")
            val created = factory()
            loadedSlot = LoadedSlot(modelId = modelId, instance = created)
            _ramStatus.value = readDeviceMemory(active = modelId, reason = "loaded_${modelId.key}")
            created
        }

        try {
            return@withLock block(typedInstance)
        } finally {
            scheduleIdleAutoUnload()
            _ramStatus.value = readDeviceMemory(active = loadedSlot?.modelId, reason = "active")
        }
    }

    /**
     * Immediately unloads any active model (called by 45s timer, onHide(), model deletion, etc.).
     */
    suspend fun unloadAll(reason: String) {
        mutex.withLock {
            idleUnloadJob?.cancel()
            loadedSlot?.let { slot ->
                closeSlotUnlocked(slot, reason = reason)
            }
        }
    }

    /**
     * Fire-and-forget immediate unload for Android lifecycle callbacks (`onTrimMemory` / `onHide`).
     */
    fun unloadAllAsync(reason: String) {
        scope.launch {
            unloadAll(reason)
        }
    }

    @Suppress("DEPRECATION")
    fun handleTrimMemory(level: Int) {
        if (
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        ) {
            Log.w(TAG, "onTrimMemory(level=$level) -> releasing active AI model immediately")
            unloadAllAsync("trim_memory_$level")
        } else {
            refreshMemoryBadge()
        }
    }

    fun refreshMemoryBadge() {
        _ramStatus.value = readDeviceMemory(
            active = loadedSlot?.modelId,
            reason = _ramStatus.value.lastUnloadReason
        )
    }

    private fun scheduleIdleAutoUnload() {
        idleUnloadJob?.cancel()
        idleUnloadJob = scope.launch {
            delay(IDLE_AUTO_UNLOAD_MS)
            mutex.withLock {
                loadedSlot?.let { slot ->
                    Log.i(TAG, "45s idle timeout reached -> auto-unloading ${slot.modelId.key}")
                    closeSlotUnlocked(slot, reason = "idle_45s_timeout")
                }
            }
        }
    }

    private fun closeSlotUnlocked(slot: LoadedSlot, reason: String) {
        Log.i(TAG, "Closing model ${slot.modelId.key} (reason=$reason)")
        runCatching { slot.instance.close() }
            .onFailure { Log.w(TAG, "Error closing ${slot.modelId.key}: ${it.message}") }
        loadedSlot = null
        System.gc()
        _ramStatus.value = readDeviceMemory(active = null, reason = reason)
    }

    private fun readDeviceMemory(active: ManagedModelId?, reason: String): RamStatus {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        val freeMb = if (info.availMem > 0) info.availMem / (1024L * 1024L) else 1200L
        val totalMb = if (info.totalMem > 0) info.totalMem / (1024L * 1024L) else 4096L
        val label = if (active != null) {
            "${active.title.substringBefore(" (")} (~${active.estRamMb} MB)"
        } else {
            "Idle (0 AI in RAM)"
        }
        return RamStatus(
            activeModel = active,
            activeModelLabel = label,
            freeRamMb = freeMb,
            totalRamMb = totalMb,
            isSystemLowMemory = info.lowMemory,
            lastUnloadReason = reason
        )
    }

    companion object {
        private const val TAG = "ModelCoordinator"
        const val IDLE_AUTO_UNLOAD_MS = 45_000L
    }
}
