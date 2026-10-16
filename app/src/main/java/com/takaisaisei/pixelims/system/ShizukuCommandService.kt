package com.takaisaisei.pixelims.system

import android.os.Binder
import android.os.Parcel
import java.util.concurrent.TimeUnit

/** Small Shizuku UserService that runs existing broker commands with shell identity. */
class ShizukuCommandService : Binder() {
    init {
        attachInterface(null, SHIZUKU_SERVICE_DESCRIPTOR)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code != SHIZUKU_TRANSACTION_EXECUTE) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(SHIZUKU_SERVICE_DESCRIPTOR)
        val command = data.readString() ?: throw IllegalArgumentException("Missing command")
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("Command timed out")
        }
        reply?.writeNoException()
        reply?.writeInt(process.exitValue())
        reply?.writeString(output)
        return true
    }
}
