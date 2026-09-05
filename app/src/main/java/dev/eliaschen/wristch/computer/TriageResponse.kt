package dev.eliaschen.wristch.computer

import com.google.genai.types.Schema
import com.google.genai.types.Type
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The triage model's verdict on one goal.
 *
 * A schema rather than a sentinel string: asking for `NEEDS_DEVICE` as prose meant a reply
 * of "NEEDS_DEVICE." or "This needs the device, so NEEDS_DEVICE" decided the route, and the
 * second one routed it wrong. A boolean cannot be phrased.
 */
@Serializable
data class TriageResponse(
    @SerialName("needs_device") val needsDevice: Boolean,
    val answer: String = "",
) {
    companion object {
        /** The same shape, in the form the API needs to constrain generation. */
        val SCHEMA: Schema = Schema.builder()
            .type(Type(Type.Known.OBJECT))
            .properties(
                mapOf(
                    "needs_device" to Schema.builder()
                        .type(Type(Type.Known.BOOLEAN))
                        .description(
                            "True when the goal requires acting on the phone or reading " +
                                "something that exists only on it.",
                        )
                        .build(),
                    "answer" to Schema.builder()
                        .type(Type(Type.Known.STRING))
                        .description(
                            "The complete answer when needs_device is false; empty otherwise.",
                        )
                        .build(),
                ),
            )
            .required(listOf("needs_device", "answer"))
            .build()
    }
}
