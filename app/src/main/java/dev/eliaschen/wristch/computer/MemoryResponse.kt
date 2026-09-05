package dev.eliaschen.wristch.computer

import com.google.genai.types.Schema
import com.google.genai.types.Type
import kotlinx.serialization.Serializable

/**
 * What a finished run is worth remembering for next time.
 *
 * A list rather than a single string, because one run can teach two unrelated things and
 * a model asked for one sentence will staple them together into a line the user then has
 * to split by hand. Empty is the ordinary answer: most runs teach nothing that outlives
 * them, and [dev.eliaschen.wristch.history.RunHistory] already keeps what happened.
 */
@Serializable
data class MemoryResponse(
    val remember: List<String> = emptyList(),
) {
    companion object {
        /** The same shape, in the form the API needs to constrain generation. */
        val SCHEMA: Schema = Schema.builder()
            .type(Type(Type.Known.OBJECT))
            .properties(
                mapOf(
                    "remember" to Schema.builder()
                        .type(Type(Type.Known.ARRAY))
                        .description(
                            "Durable facts learned on this run, one per entry. Empty " +
                                "when the run taught nothing worth keeping.",
                        )
                        .items(
                            Schema.builder()
                                .type(Type(Type.Known.STRING))
                                .description(
                                    "One self-contained fact, in a single sentence.",
                                )
                                .build(),
                        )
                        .build(),
                ),
            )
            .required(listOf("remember"))
            .build()
    }
}
