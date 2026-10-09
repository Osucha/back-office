package pl.mos.backoffice.governance.adapter.out.json

import pl.mos.backoffice.shared.platform.DsseEnvelope
import pl.mos.backoffice.shared.platform.DsseSignature

object DsseJson {
    /** DSSE JSON envelope (`payload` base64, `signatures[].keyid` / `sig`). */
    fun toJson(envelope: DsseEnvelope): Map<String, Any?> = linkedMapOf(
        "payloadType" to envelope.payloadType,
        "payload" to envelope.payloadBase64,
        "signatures" to envelope.signatures.map { linkedMapOf("keyid" to it.keyId, "sig" to it.signature) },
    )

    fun fromJson(map: Map<String, Any?>): DsseEnvelope = DsseEnvelope(
        payloadType = map["payloadType"]?.toString() ?: "",
        payloadBase64 = map["payload"]?.toString() ?: "",
        signatures = (map["signatures"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
            .map { DsseSignature(it["keyid"]?.toString() ?: "", it["sig"]?.toString() ?: "") },
    )
}
