package sigmastate

import java.math.BigInteger
import java.util
import java.util.{Objects, Arrays}

import org.bitbucket.inkytonik.kiama.relation.Tree
import org.bitbucket.inkytonik.kiama.rewriting.Rewritable
import scorex.crypto.authds.SerializedAdProof
import scorex.crypto.authds.avltree.batch.BatchAVLVerifier
import scorex.crypto.hash.{Digest32, Blake2b256}
import sigmastate.SCollection.SByteArray
import sigmastate.SType.TypeCode
import sigmastate.interpreter.{Context, GroupSettings}
import sigmastate.serialization.{ValueSerializer, OpCodes}
import sigmastate.serialization.OpCodes._
import sigmastate.utils.Helpers
import sigmastate.utils.Overloading.Overload1
import sigmastate.utxo.CostTable.Cost
import sigmastate.utxo.{ErgoContext, ErgoBox}
import sigmastate.utils.Extensions._

import scala.collection.immutable
import scala.language.implicitConversions
import scala.reflect.ClassTag

object Values {

  type SigmaTree = Tree[SigmaNode, SValue]

  type SValue = Value[SType]
  type Idn = String

  trait Value[+S <: SType] extends SigmaNode {
    val opCode: OpCode

    def tpe: S

    def typeCode: SType.TypeCode = tpe.typeCode

    def cost[C <: Context[C]](context: C): Long

    /** Returns true if this value represent some constant or sigma statement, false otherwise */
    def evaluated: Boolean

    lazy val bytes = ValueSerializer.serialize(this)
  }

  object Value {
    type PropositionCode = Byte

    implicit def liftInt(n: Int): Value[SInt.type] = IntConstant(n)

    implicit def liftLong(n: Long): Value[SInt.type] = IntConstant(n)

    implicit def liftByteArray(arr: Array[Byte]): Value[SByteArray] = ByteArrayConstant(arr)

    implicit def liftBigInt(arr: BigInteger): Value[SBigInt.type] = BigIntConstant(arr)

    implicit def liftGroupElement(g: GroupSettings.EcPointType): Value[SGroupElement.type] = GroupElementConstant(g)

    object Typed {
      def unapply(v: SValue): Option[(SValue, SType)] = Some((v, v.tpe))
    }

  }

  trait EvaluatedValue[S <: SType] extends Value[S] {
    val value: S#WrappedType
    override lazy val evaluated = true
  }

  trait NotReadyValue[S <: SType] extends Value[S] {
    override lazy val evaluated = false
  }

  trait TaggedVariable[S <: SType] extends NotReadyValue[S] {
    override val opCode: OpCode = TaggedVariableCode
    val id: Byte

    override def cost[C <: Context[C]](context: C) = context.extension.cost(id) + 1
  }

  case object UnitConstant extends EvaluatedValue[SUnit.type] {
    override val opCode = UnitConstantCode
    override def cost[C <: Context[C]](context: C) = 1

    override def tpe = SUnit

    val value = ()
  }

  case class IntConstant(value: Long) extends EvaluatedValue[SInt.type] {
    override val opCode: OpCode = IntConstantCode
    override def cost[C <: Context[C]](context: C) = Cost.IntConstantDeclaration

    override def tpe = SInt
  }

  case class ByteConstant(value: Byte) extends EvaluatedValue[SByte.type] {
    override val opCode: OpCode = ByteConstantCode
    override def cost[C <: Context[C]](context: C) = Cost.ByteConstantDeclaration
    override def tpe = SByte
  }

  trait NotReadyValueInt extends NotReadyValue[SInt.type] {
    override def tpe = SInt
  }

  case class TaggedInt(override val id: Byte) extends TaggedVariable[SInt.type] with NotReadyValueInt {
    override def cost[C <: Context[C]](context: C) = 1
  }

  case class BigIntConstant(value: BigInteger) extends EvaluatedValue[SBigInt.type] {

    override val opCode: OpCode = BigIntConstantCode

    override def cost[C <: Context[C]](context: C) = 1

    override def tpe = SBigInt
  }

  trait NotReadyValueBigInt extends NotReadyValue[SBigInt.type] {
    override def tpe = SBigInt
  }

  case class TaggedBigInt(override val id: Byte) extends TaggedVariable[SBigInt.type] with NotReadyValueBigInt {
  }

  trait EvaluatedCollection[T <: SType] extends EvaluatedValue[SCollection[T]] {
    def elementType: T
  }

  case class CollectionConstant[T <: SType](value: Array[T#WrappedType], elementType: T) extends EvaluatedCollection[T] {

    override def cost[C <: Context[C]](context: C): Long = ((value.length / 1024) + 1) * Cost.ByteArrayPerKilobyte

    override val opCode: OpCode = CollectionConstantCode

    override val tpe = SCollection(elementType)

    override def equals(obj: scala.Any): Boolean = obj match {
      case c: CollectionConstant[_] => util.Objects.deepEquals(value, c.value) && elementType == c.elementType
      case _ => false
    }

    override def hashCode(): Int = 31 * Helpers.deepHashCode(value) + elementType.hashCode()
  }

  object ByteArrayConstant {
    def apply(value: Array[Byte]): CollectionConstant[SByte.type] = CollectionConstant[SByte.type](value, SByte)
    def unapply(node: SValue): Option[Array[Byte]] = node match {
      case arr: CollectionConstant[SByte.type] @unchecked if arr.elementType == SByte => Some(arr.value)
      case _ => None
    }
  }

  trait NotReadyValueByteArray extends NotReadyValue[SByteArray] {
    override def tpe = SByteArray
  }

  case class TaggedByteArray(override val id: Byte) extends TaggedVariable[SByteArray] with NotReadyValueByteArray {
    override def typeCode: TypeCode = SCollection.SByteArrayTypeCode
  }

  case class AvlTreeConstant(value: AvlTreeData) extends EvaluatedValue[SAvlTree.type] {
    override val opCode: OpCode = OpCodes.AvlTreeConstantCode

    override def cost[C <: Context[C]](context: C) = Cost.AvlTreeConstant

    override def tpe = SAvlTree

    def createVerifier(proof: SerializedAdProof) =
      new BatchAVLVerifier[Digest32, Blake2b256.type](
        value.startingDigest,
        proof,
        value.keyLength,
        value.valueLengthOpt,
        value.maxNumOperations,
        value.maxDeletes)
  }

  trait NotReadyValueAvlTree extends NotReadyValue[SAvlTree.type] {
    override def tpe = SAvlTree
  }

  case class TaggedAvlTree(override val id: Byte) extends TaggedVariable[SAvlTree.type] with NotReadyValueAvlTree {
  }


  case class GroupElementConstant(value: GroupSettings.EcPointType) extends EvaluatedValue[SGroupElement.type] {
    override def cost[C <: Context[C]](context: C) = 10

    override val opCode: OpCode = GroupElementConstantCode

    override def tpe = SGroupElement
  }


  case object GroupGenerator extends EvaluatedValue[SGroupElement.type] {

    override val opCode: OpCode = OpCodes.GroupGeneratorCode

    import GroupSettings.dlogGroup

    override def cost[C <: Context[C]](context: C) = 10

    override def tpe = SGroupElement

    override val value: GroupSettings.EcPointType = dlogGroup.generator
  }

  trait NotReadyValueGroupElement extends NotReadyValue[SGroupElement.type] {
    override def tpe = SGroupElement
  }

  case class TaggedGroupElement(override val id: Byte)
    extends TaggedVariable[SGroupElement.type] with NotReadyValueGroupElement {
  }

  sealed abstract class BooleanConstant(val value: Boolean) extends EvaluatedValue[SBoolean.type] {
    override def tpe = SBoolean
  }

  object BooleanConstant {
    def fromBoolean(v: Boolean): BooleanConstant = if (v) TrueLeaf else FalseLeaf
  }

  case object TrueLeaf extends BooleanConstant(true) {
    override val opCode: OpCode = TrueCode

    override def cost[C <: Context[C]](context: C): Long = Cost.ConstantNode
  }

  case object FalseLeaf extends BooleanConstant(false) {
    override val opCode: OpCode = FalseCode

    override def cost[C <: Context[C]](context: C): Long = Cost.ConstantNode
  }

  trait NotReadyValueBoolean extends NotReadyValue[SBoolean.type] {
    override def tpe = SBoolean
  }

  case class TaggedBoolean(override val id: Byte) extends TaggedVariable[SBoolean.type] with NotReadyValueBoolean {
    override def cost[C <: Context[C]](context: C) = 1
  }

  /**
    * For sigma statements
    */
  trait SigmaBoolean extends NotReadyValue[SBoolean.type] {
    override lazy val evaluated = true

    override def tpe = SBoolean

    def fields: Seq[(String, SType)] = SigmaBoolean.fields
  }

  object SigmaBoolean {
    val PropBytes = "propBytes"
    val IsValid = "isValid"
    val fields = Seq(
      PropBytes -> SByteArray,
      IsValid -> SBoolean
    )
  }

  case class BoxConstant(value: ErgoBox) extends EvaluatedValue[SBox.type] {
    override val opCode: OpCode = OpCodes.BoxConstantCode

    override def cost[C <: Context[C]](context: C): Long = value.cost

    override def tpe = SBox
  }

  trait NotReadyValueBox extends NotReadyValue[SBox.type] {
    def tpe = SBox
  }

  case class TaggedBox(override val id: Byte) extends TaggedVariable[SBox.type] with NotReadyValueBox {
  }

  case class Tuple(items: IndexedSeq[Value[SType]]) extends EvaluatedValue[STuple] {
    override val opCode: OpCode = TupleCode
    val cost: Int = value.size
    val tpe = STuple(items.map(_.tpe))
    lazy val value = items

    override def cost[C <: Context[C]](context: C) = Cost.ConcreteCollection + items.map(_.cost(context)).sum
  }

  object Tuple {
    def apply(items: Value[SType]*): Tuple = Tuple(items.toIndexedSeq)

    def apply(items: Seq[Value[SType]])(implicit o: Overload1): Tuple = Tuple(items.toIndexedSeq)
  }

  trait OptionValue[T <: SType] extends EvaluatedValue[SOption[T]] {
  }

  case class SomeValue[T <: SType](x: Value[T]) extends OptionValue[T] {
    override val opCode = SomeValueCode

    def cost[C <: Context[C]](context: C): Long = x.cost(context) + 1

    val tpe = SOption(x.tpe)
    lazy val value = Some(x)
  }

  case class NoneValue[T <: SType](elemType: T) extends OptionValue[T] {
    override val opCode = NoneValueCode

    def cost[C <: Context[C]](context: C): Long = 1

    val tpe = SOption(elemType)
    lazy val value = None
  }

  case class ConcreteCollection[V <: SType](items: IndexedSeq[Value[V]])(implicit val elementType: V)
    extends EvaluatedCollection[V] with Rewritable {
    override val opCode: OpCode = ConcreteCollectionCode

    def cost[C <: Context[C]](context: C): Long = Cost.ConcreteCollection + items.map(_.cost(context)).sum

    val tpe = SCollection[V](elementType)

    lazy val value = {
      val xs = items.cast[EvaluatedValue[V]].map(_.value)
      xs.toArray(elementType.classTag.asInstanceOf[ClassTag[V#WrappedType]])
    }

    def arity = 1 + items.size

    def deconstruct = immutable.Seq[Any](elementType) ++ items

    def reconstruct(cs: immutable.Seq[Any]) = cs match {
      case Seq(t: SType, vs@_*) => ConcreteCollection[SType](vs.asInstanceOf[Seq[Value[V]]].toIndexedSeq)(t)
      case _ =>
        illegalArgs("ConcreteCollection", "(IndexedSeq, SType)", cs)
    }
  }
  object ConcreteCollection {
    def apply[V <: SType](items: Value[V]*)(implicit tV: V) = new ConcreteCollection(items.toIndexedSeq)
  }

  trait LazyCollection[V <: SType] extends NotReadyValue[SCollection[V]]

  implicit class CollectionOps[T <: SType](coll: Value[SCollection[T]]) {
    def length: Int = fold(_.items.length, _.value.length)
    def items = fold(_.items, _ => sys.error(s"Cannot get 'items' property of node $coll"))
    def isEvaluated =
      coll.evaluated && fold(_.items.forall(_.evaluated), _ => true)
    def fold[R](whenConcrete: ConcreteCollection[T] => R, whenConstant: CollectionConstant[T] => R): R = coll match {
      case cc: ConcreteCollection[T]@unchecked => whenConcrete(cc)
      case const: CollectionConstant[T]@unchecked => whenConstant(const)
      case _ => sys.error(s"Unexpected node $coll")
    }
  }

}