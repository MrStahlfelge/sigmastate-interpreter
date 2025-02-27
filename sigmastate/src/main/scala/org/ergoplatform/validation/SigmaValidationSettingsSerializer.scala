package org.ergoplatform.validation

import sigmastate.serialization.SigmaSerializer
import sigmastate.utils.{SigmaByteReader, SigmaByteWriter}
import scalan.util.Extensions.{IntOps,LongOps}

// TODO v5.x: remove unused class and related json encoders
/** The rules are serialized ordered by ruleId.
  * This serializer preserves roundtrip identity `deserialize(serialize(_)) = identity`
  * however it may not preserve `serialize(deserialize(_)) = identity` */
object SigmaValidationSettingsSerializer extends SigmaSerializer[SigmaValidationSettings, SigmaValidationSettings] {

  override def serialize(settings: SigmaValidationSettings, w: SigmaByteWriter): Unit = {
    val rules = settings.toArray.sortBy(_._1)
    w.putUInt(rules.length)
    rules.foreach { r =>
      w.putUShort(r._1)
      RuleStatusSerializer.serialize(r._2._2, w)
    }
  }

  override def parse(r: SigmaByteReader): SigmaValidationSettings = {
    val nRules = r.getUInt().toInt
    // Note, when nRules < 0 as a result of Int overflow, the loop is empty
    val parsed = (0 until nRules).map { _ =>
      val ruleId = r.getUShort().toShortExact
      val status = RuleStatusSerializer.parse(r)
      ruleId -> status
    }
    val initVs = ValidationRules.currentSettings
    val res = parsed
      .filter(pair => initVs.get(pair._1).isDefined)
      .foldLeft(initVs) { (vs, rule) => vs.updated(rule._1, rule._2) }
    res
  }
}

