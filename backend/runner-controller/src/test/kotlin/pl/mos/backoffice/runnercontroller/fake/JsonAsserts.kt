package pl.mos.backoffice.runnercontroller.fake

import tools.jackson.databind.JsonNode

/** String elements of a JSON array (or of field [field] of each element). */
fun JsonNode.strings(field: String? = null): List<String> =
    (0 until size()).map { i -> (if (field == null) get(i) else get(i).get(field)).asString() }
