package cta.models


import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming


@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class TypeFormPayload(
    val eventType: String,
    val formResponse: TypeformResponse
) {
}

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class TypeformResponse(
    val formId: String,
    val hidden: Map<String, String>
) {
}


/*
data class TypeformDefinition(
    val fields:  List<Field>
) {
}


data class Field(
    val id: String,
    val title: String
)
data class TypeformAnswer(
    val type: String,
    val field: Field,
    val text: String? = null,
    val email: String? = null,
    val date: String? = null,
    val choices: Choices? = null,
    val number: Int? = null,
    val boolean: Boolean? = null,
    val url: String? = null
)

data class Choices(
    val ids: List<String>,
    val labels: List<String>,
    val refs: List<String>
)
*/

