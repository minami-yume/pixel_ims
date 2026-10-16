package com.takaisaisei.pixelims.system

import android.Manifest
import android.content.Context
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import androidx.core.content.ContextCompat
import com.takaisaisei.pixelims.ApplyMode
import com.takaisaisei.pixelims.domain.BrokerContract
import com.takaisaisei.pixelims.domain.SlotQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.milliseconds

internal const val SHIZUKU_SERVICE_DESCRIPTOR = "com.takaisaisei.pixelims.SHIZUKU_COMMAND"
internal const val SHIZUKU_TRANSACTION_EXECUTE = IBinder.FIRST_CALL_TRANSACTION

/** Executes the existing shell-side IMS broker through a Shizuku-compatible service. */
class ShizukuShell(context: Context) {
    private val appContext = context.applicationContext
    private val packageName = appContext.packageName
    private val commandMutex = Mutex()
    private val connectionLock = Any()
    @Volatile private var serviceBinder: IBinder? = null
    private var binderReady: CompletableDeferred<IBinder>? = null
    private var bindRequested = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            synchronized(connectionLock) {
                serviceBinder = binder
                binderReady?.complete(binder)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            synchronized(connectionLock) {
                serviceBinder = null
                binderReady?.completeExceptionally(IllegalStateException("Shizuku UserService disconnected"))
                binderReady = null
                bindRequested = false
            }
        }

        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
        override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
    }

    fun isAuthorized(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    suspend fun runApply(slot: Int, clear: Boolean, notify: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        val target = "$packageName/$packageName.BrokerInstrumentation"
        val notifyResult = if (ApplyMode.detached) true else notify
        val arguments = "-e ${BrokerContract.ARG_CLEAR} $clear -e ${BrokerContract.ARG_SLOT} $slot " +
                "-e ${BrokerContract.ARG_NOTIFY} $notifyResult"
        val command = if (ApplyMode.detached) {
            "nohup am instrument -w $arguments $target > /dev/null 2>&1 &"
        } else {
            "am instrument -w --no-restart $arguments $target"
        }
        runCatching {
            val response = execute(command)
            check(response.exitCode == 0) { "Exit code ${response.exitCode}: ${response.output}" }
            check(ApplyMode.detached || !response.output.contains("INSTRUMENTATION_FAILED")) {
                response.output.trim()
            }
        }
    }

    suspend fun grantPermissionsIfNeeded() = withContext(Dispatchers.IO) {
        GRANTABLE_PERMISSIONS.filterNot(::hasPermission).forEach { permission ->
            runCatching { execute("pm grant $packageName $permission") }
                .onFailure { Log.w(TAG, "pm grant $permission failed", it) }
        }
    }

    suspend fun pollImsStatus(isPaused: () -> Boolean, onResult: (SlotQuery) -> Unit) {
        withContext(Dispatchers.IO) {
            while (true) {
                coroutineContext.ensureActive()
                if (!isPaused()) {
                    runCatching {
                        val apkPath = execute("pm path $packageName")
                            .output.trim().substringAfter("package:").takeIf(String::isNotEmpty)
                            ?: return@runCatching
                        val query = "export CLASSPATH=$apkPath; app_process /system/bin $packageName.ImsQueryTool"
                        BrokerContract.decodeQuery(execute(query).output).forEach(onResult)
                    }.onFailure { Log.d(TAG, "IMS poll error: ${it.message}") }
                }
                delay(if (isPaused()) PAUSE_INTERVAL_MS.milliseconds else POLL_INTERVAL_MS.milliseconds)
            }
        }
    }

    private suspend fun execute(command: String): CommandResult = commandMutex.withLock {
        val binder = getServiceBinder()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SHIZUKU_SERVICE_DESCRIPTOR)
            data.writeString(command)
            check(binder.transact(SHIZUKU_TRANSACTION_EXECUTE, data, reply, 0)) {
                "Shizuku UserService rejected command"
            }
            reply.readException()
            CommandResult(reply.readInt(), reply.readString().orEmpty())
        } catch (error: Throwable) {
            if (!binder.isBinderAlive) synchronized(connectionLock) { serviceBinder = null }
            throw error
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private suspend fun getServiceBinder(): IBinder {
        serviceBinder?.takeIf { it.isBinderAlive }?.let { return it }

        val ready = synchronized(connectionLock) {
            binderReady ?: CompletableDeferred<IBinder>().also { binderReady = it }
        }
        val shouldBind = synchronized(connectionLock) {
            if (bindRequested) false else {
                bindRequested = true
                true
            }
        }
        if (shouldBind) {
            try {
                withContext(Dispatchers.Main.immediate) {
                    Shizuku.bindUserService(serviceArgs, serviceConnection)
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to bind Shizuku UserService", error)
                synchronized(connectionLock) {
                    bindRequested = false
                    binderReady = null
                }
                throw IllegalStateException("Unable to start Shizuku service: ${error.message}", error)
            }
        }
        return try {
            withTimeout(SERVICE_BIND_TIMEOUT_MS) { ready.await() }
        } catch (error: Throwable) {
            synchronized(connectionLock) {
                if (binderReady === ready) {
                    binderReady = null
                    bindRequested = false
                }
            }
            throw error
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(appContext, ShizukuCommandService::class.java))
            .processNameSuffix("pixel_ims_shell")
            .tag("pixel_ims_shell").version(1).daemon(false)
    }

    private data class CommandResult(val exitCode: Int, val output: String)

    companion object {
        const val TAG = "ShizukuShell"
        const val POLL_INTERVAL_MS = 3_000L
        const val PAUSE_INTERVAL_MS = 1_000L
        const val SERVICE_BIND_TIMEOUT_MS = 15_000L
        val GRANTABLE_PERMISSIONS = listOf(Manifest.permission.WRITE_SECURE_SETTINGS, Manifest.permission.READ_PHONE_STATE)
    }
}
