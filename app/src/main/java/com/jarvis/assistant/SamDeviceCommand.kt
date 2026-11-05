package com.jarvis.assistant

import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Wire-format command data shared with the SAM gateway contract.
 * The gateway remains authoritative for authorization and execution.
 */
internal data class SamDeviceCommand(
    val commandId: UUID,
    val targetDeviceId: UUID,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val nonce: String,
    val action: String,
    val delaySeconds: Int? = null,
    val requiredApprovalId: UUID? = null
)

internal object SamDeviceCommandCanonicalizer {
    fun createSigningPayload(command: SamDeviceCommand): ByteArray {
        val fields = listOf(
            "sam-device-command-v1",
            command.commandId.toString(),
            command.targetDeviceId.toString(),
            command.issuedAtEpochMillis.toString(),
            command.expiresAtEpochMillis.toString(),
            command.nonce,
            command.action,
            command.delaySeconds?.toString() ?: "-",
            command.requiredApprovalId?.toString() ?: "-"
        )
        return fields.joinToString("\n").toByteArray(StandardCharsets.UTF_8)
    }
}
