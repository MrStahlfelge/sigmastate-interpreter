package sigmastate.serialization.transformers

import sigmastate.{SigmaTransformerCompanion, SigmaTransformer}
import sigmastate.Values.{SigmaPropValue, SValue}
import sigmastate.serialization.ValueSerializer
import sigmastate.util.safeNewArray
import sigmastate.utils.SigmaByteWriter.{DataInfo, valuesItemInfo}
import sigmastate.utils.{SigmaByteReader, SigmaByteWriter}
import spire.syntax.all.cfor

case class SigmaTransformerSerializer[I <: SigmaPropValue, O <: SigmaPropValue]
(opDesc: SigmaTransformerCompanion, cons: Seq[SigmaPropValue] => SigmaPropValue)
  extends ValueSerializer[SigmaTransformer[I, O]] {
  val itemsInfo: DataInfo[Seq[SValue]] = opDesc.argInfos(0)
  val itemsItemInfo = valuesItemInfo(itemsInfo)

  override def serialize(obj: SigmaTransformer[I, O], w: SigmaByteWriter): Unit =
    w.putValues(obj.items, itemsInfo, itemsItemInfo)

  override def parse(r: SigmaByteReader): SigmaPropValue = {
    val itemsSize = r.getUIntExact
    // NO-FORK: in v5.x getUIntExact may throw Int overflow exception
    // in v4.x r.getUInt().toInt is used and may return negative Int instead of the overflow
    // in which case the array allocation will throw NegativeArraySizeException
    val res = safeNewArray[SigmaPropValue](itemsSize)
    cfor(0)(_ < itemsSize, _ + 1) { i =>
      res(i) = r.getValue().asInstanceOf[SigmaPropValue]
    }
    cons(res)
  }
}
