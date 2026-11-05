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

    /** A fixed protocol vector catches accidental format drift between clients and gateway. */
    fun verifyKnownVector() {
        val command = SamDeviceCommand(
            UUID.fromString("11111111-2222-3333-4444-555555555555"),
            UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
            1793869200000L,
            1793869230000L,
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYX",
            "power.shutdown",
            60,
            UUID.fromString("99999999-8888-7777-6666-555555555555")
        )
        val expected = (
            "sam-device-command-v1\n" +
            "11111111-2222-3333-4444-555555555555\n" +
            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\n" +
            "1793869200000\n" +
            "1793869230000\n" +
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYX\n" +
            "power.shutdown\n" +
            "60\n" +
            "99999999-8888-7777-6666-555555555555"
        ).toByteArray(StandardCharsets.UTF_8)

        check(createSigningPayload(command).contentEquals(expected)) {
            "SAM Android canonical command format does not match the gateway test vector."
        }
    }
}
