package pl.mos.backoffice.runnercontroller.protocol

import com.fasterxml.jackson.annotation.JsonInclude
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

object ProtocolJson {
    /** Lenient reader (unknown fields ignored), compact writer (nulls omitted). */
    fun mapper(): JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .changeDefaultPropertyInclusion { it.withValueInclusion(JsonInclude.Include.NON_NULL) }
        .build()
}
