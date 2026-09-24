package com.phonecontrol.assistant.execution

/** Result of one command sent through the phone's local privileged bridge. */
data class PhoneProcessResult(
    val exitCode: Int?,
    val stdout: ByteArray,
    val stderr: String,
    val timedOut: Boolean = false,
)

/**
 * Narrow command boundary used by the observation and typed-action layers.
 * Implementations must only receive argv assembled by the phone-side code;
 * model or desktop text must never be passed here as a shell command.
 */
interface PhoneProcessRunner {
    suspend fun run(command: List<String>): PhoneProcessResult

    /**
     * Run a command and notify the caller when the underlying process
     * transport has accepted the request. Implementations that cannot expose
     * a later boundary retain the safe fallback of invoking the callback just
     * before the command starts.
     */
    suspend fun run(
        command: List<String>,
        onStarted: (() -> Unit)?,
    ): PhoneProcessResult {
        onStarted?.invoke()
        return run(command)
    }
}
