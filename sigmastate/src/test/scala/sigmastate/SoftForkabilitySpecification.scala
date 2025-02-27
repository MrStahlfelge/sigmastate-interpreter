package sigmastate

import org.ergoplatform._
import org.ergoplatform.validation.ValidationRules._
import org.ergoplatform.validation._
import org.scalatest.BeforeAndAfterAll
import sigmastate.SPrimType.MaxPrimTypeCode
import sigmastate.Values.ErgoTree.EmptyConstants
import sigmastate.Values.{UnparsedErgoTree, NotReadyValueInt, ByteArrayConstant, Tuple, IntConstant, ErgoTree, ValueCompanion}
import sigmastate.eval.Colls
import sigmastate.helpers.{ErgoLikeContextTesting, ErgoLikeTestProvingInterpreter, ErgoLikeTestInterpreter}
import sigmastate.helpers.TestingHelpers._
import sigmastate.interpreter.Interpreter.{ScriptNameProp, emptyEnv, WhenSoftForkReductionResult}
import sigmastate.interpreter.{ProverResult, WhenSoftForkReducer, ContextExtension, CacheKey}
import sigmastate.lang.Terms._
import sigmastate.lang.exceptions.{SerializerException, SigmaException, InterpreterException, CosterException}
import sigmastate.serialization.OpCodes.{OpCodeExtra, LastConstantCode, OpCode}
import sigmastate.serialization.SigmaSerializer.startReader
import sigmastate.serialization._
import sigmastate.utxo.{DeserializeContext, SelectField}
import special.sigma.SigmaTestingData
import sigmastate.utils.Helpers._

class SoftForkabilitySpecification extends SigmaTestingData with BeforeAndAfterAll {

  implicit lazy val IR = new TestingIRContext
  lazy val prover = new ErgoLikeTestProvingInterpreter()
  lazy val verifier = new ErgoLikeTestInterpreter
  val deadline = 100
  val boxAmt = 100L

  lazy val booleanPropV1 = compile(emptyEnv + ("deadline" -> deadline),
    """{
     |  HEIGHT > deadline && OUTPUTS.size == 1
     |}""".stripMargin).asBoolValue

  // cast Boolean typed prop to SigmaProp (which is invalid) // ErgoTree v0
  lazy val invalidPropV1: ErgoTree =
    ErgoTree.fromProposition(
      ErgoTree.headerWithVersion(0),
      booleanPropV1.asSigmaProp)

  lazy val invalidTxV1 = createTransaction(createBox(boxAmt, invalidPropV1, 1))
  lazy val invalidTxV1bytes = invalidTxV1.messageToSign

  lazy val propV1 = booleanPropV1.toSigmaProp
  lazy val txV1 = createTransaction(
    createBox(boxAmt,
      ErgoTree.fromProposition(ErgoTree.headerWithVersion(0), propV1), // ErgoTree v0
      1))
  lazy val txV1bytes = txV1.messageToSign

  val blockHeight = 110

  def createContext(h: Int, tx: ErgoLikeTransaction, vs: SigmaValidationSettings) =
    ErgoLikeContextTesting(h,
      AvlTreeData.dummy, ErgoLikeContextTesting.dummyPubkey, IndexedSeq(fakeSelf),
      tx, fakeSelf, vs = vs, activatedVersion = activatedVersionInTests)

  def proveTx(name: String, tx: ErgoLikeTransaction, vs: SigmaValidationSettings): ProverResult = {
    val env = Map(ScriptNameProp -> (name + "_prove"))
    val ctx = createContext(blockHeight, tx, vs)
    val prop = tx.outputs(0).ergoTree
    val proof1 = prover.prove(env, prop, ctx, fakeMessage).get
    proof1
  }

  def verifyTx(name: String, tx: ErgoLikeTransaction, proof: ProverResult, vs: SigmaValidationSettings) = {
    val env = Map(ScriptNameProp -> (name + "_verify"))
    val ctx = createContext(blockHeight, tx, vs)
    val prop = tx.outputs(0).ergoTree
    verifier.verify(env, prop, ctx, proof, fakeMessage).map(_._1).getOrThrow shouldBe true
  }

  def proveAndVerifyTx(name: String, tx: ErgoLikeTransaction, vs: SigmaValidationSettings) = {
    val proof = proveTx(name, tx, vs)
    verifyTx(name, tx, proof, vs)
  }

  def checkTxProp[T <: ErgoLikeTransaction, R](tx1: T, tx2: T)(p: T => R) = {
    p(tx1) shouldBe p(tx2)
  }

  property("node v1, received tx with script v1, incorrect script") {
    assertExceptionThrown({
      // CheckDeserializedScriptIsSigmaProp rule violated
      ErgoLikeTransaction.serializer.parse(startReader(invalidTxV1bytes))
    }, {
      case se: SerializerException if se.cause.isDefined =>
        val ve = se.cause.get.asInstanceOf[ValidationException]
        ve.rule == CheckDeserializedScriptIsSigmaProp
      case _ => false
    })
  }

  property("node v1, received tx with script v1, correct script") {
    // able to parse
    val tx = ErgoLikeTransaction.serializer.parse(startReader(txV1bytes))

    // validating script
    proveAndVerifyTx("propV1", tx, vs)
  }

  val Height2Code: OpCode = OpCode @@ (LastConstantCode + 56).toByte
  /** Same as Height, but new opcode to test soft-fork */
  case object Height2 extends NotReadyValueInt with ValueCompanion {
    override def companion = this
    override val opCode: OpCode = Height2Code // use reserved code
    override val opType = SFunc(SContext, SInt)
    override val costKind: CostKind = Height.costKind
  }
  val Height2Ser = CaseObjectSerialization(Height2, Height2)

  // prepare soft-fork settings for v2
  val v2vs = vs.updated(CheckValidOpCode.id, ChangedRule(Array(Height2Code)))

  /** Runs the block as if on the v2 node. */
  def runOnV2Node[T](block: => T): T = {
    ValueSerializer.addSerializer(Height2Code, Height2Ser)
    val res = block
    ValueSerializer.removeSerializer(Height2Code)
    res
  }

  lazy val booleanPropV2 = GT(Height2, IntConstant(deadline))

  lazy val invalidPropV2: ErgoTree = ErgoTree.fromProposition(
    headerFlags = ErgoTree.headerWithVersion(0),  // ErgoTree v0
    prop = booleanPropV2.asSigmaProp)

  lazy val invalidTxV2 = createTransaction(createBox(boxAmt, invalidPropV2, 1))


  lazy val propV2 = booleanPropV2.toSigmaProp
  // prepare bytes using special serialization WITH `size flag` in the header
  lazy val propV2tree = ErgoTree.withSegregation(ErgoTree.SizeFlag,  propV2)
  lazy val propV2treeBytes = runOnV2Node {
    propV2tree.bytes
  }

  lazy val txV2 = createTransaction(createBox(boxAmt, propV2tree, 1))
  lazy val txV2messageToSign = runOnV2Node {
    txV2.messageToSign
  }

  val newTypeCode = (SGlobal.typeCode + 1).toByte

  property("node v1, soft-fork up to v2, script v2 without size bit") {
    // try prepare v2 script without `size bit` in the header
    assertExceptionThrown({
      ErgoTree(1.toByte, EmptyConstants, propV2)
    }, {
      case e: IllegalArgumentException  => true
      case _ => false
    } )

    // prepare bytes using default serialization and then replacing version in the header
    val v2tree_withoutSize_bytes = runOnV2Node {
      val tree = ErgoTree.fromProposition(
        ErgoTree.headerWithVersion(0), propV2)  // ErgoTree v0
      val bytes = tree.bytes
      // set version to v2 while not setting the size bit,
      // we cannot do this using ErgoTree constructor (due to require() check)
      bytes(0) = 1.toByte
      bytes
    }

    // v1 node should fail
    assertExceptionThrown(
      {
        val r = startReader(v2tree_withoutSize_bytes)
        ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(r, SigmaSerializer.MaxPropositionSize)
      },
      {
        case ve: ValidationException if ve.rule == CheckHeaderSizeBit => true
        case _ => false
      }
    )

    // v2 node should fail
    runOnV2Node {
      assertExceptionThrown(
        {
          val r = startReader(v2tree_withoutSize_bytes)
          ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(r, SigmaSerializer.MaxPropositionSize)
        },
        {
          case ve: ValidationException if ve.rule == CheckHeaderSizeBit => true
          case _ => false
        } )
    }
  }

  property("node v1, soft-fork up to v2, script v2 with `size bit`") {
    val treeBytes = propV2treeBytes
    val txV2bytes = txV2messageToSign

    // parse and validate tx with v2 settings
    val tx = ErgoLikeTransaction.serializer.parse(startReader(txV2bytes))
    proveAndVerifyTx("propV2", tx, v2vs)

    // and with v1 settings
    assertExceptionThrown(
      proveAndVerifyTx("propV2", tx, vs),
      { t =>
        t.isInstanceOf[InterpreterException] &&
        t.getMessage.contains("Script has not been recognized due to ValidationException, and it cannot be accepted as soft-fork.")
      }
    )

    // also check that transaction prop was trivialized due to soft-fork
    tx.outputs(0).ergoTree.root.left.get.bytes.array shouldBe treeBytes
    tx.outputs(0).ergoTree.root.left.get.isInstanceOf[UnparsedErgoTree] shouldBe true

    // check deserialized tx is otherwise remains the same
    checkTxProp(txV2, tx)(_.inputs)
    checkTxProp(txV2, tx)(_.dataInputs)
    checkTxProp(txV2, tx)(_.outputs.length)
    checkTxProp(txV2, tx)(_.outputs(0).creationHeight)
    checkTxProp(txV2, tx)(_.outputs(0).value)
    checkTxProp(txV2, tx)(_.outputs(0).additionalTokens)
  }

  property("node v1, no soft-fork, received script v2, raise error") {
    assertExceptionThrown({
      val invalidTxV2bytes = runOnV2Node { invalidTxV2.messageToSign }
      ErgoLikeTransaction.serializer.parse(startReader(invalidTxV2bytes))
    },{
      case se: SerializerException if se.cause.isDefined =>
        val ve = se.cause.get.asInstanceOf[ValidationException]
        ve.rule == CheckValidOpCode
      case _ => false
    })
  }

  property("our node v2, was soft-fork up to v2, received script v2") {
    val txV2bytes = txV2.messageToSign

    // run as on node v2
    runOnV2Node {

      // parse and validate tx with v2 script (since serializers were extended to v2)
      val tx = ErgoLikeTransaction.serializer.parse(startReader(txV2bytes))
      tx shouldBe txV2

      // fails evaluation of v2 script (due to the rest of the implementation is still v1)
      assertExceptionThrown({
        proveAndVerifyTx("propV2", tx, v2vs)
      },{
        case _: CosterException => true
        case _ => false
      })
    }
  }

  property("our node v1, was soft-fork up to v2, received v1 script, DeserializeContext of v2 script") {
    // script bytes for context variable containing v2 operation
    val propBytes = runOnV2Node {
      ValueSerializer.serialize(booleanPropV2)
    }

    // v1 main script which deserializes v2 script from context
    val mainProp = BinAnd(GT(Height, IntConstant(deadline)), DeserializeContext(1, SBoolean)).toSigmaProp
    val mainTree = ErgoTree.fromProposition(
      headerFlags = ErgoTree.headerWithVersion(0), // ErgoTree v0
      prop = mainProp)

    val tx = createTransaction(createBox(boxAmt, mainTree, 1))
    val bindings = Map(1.toByte -> ByteArrayConstant(Colls.fromArray(propBytes)))
    val proof = new ProverResult(Array.emptyByteArray, ContextExtension(bindings))

    // verify transaction on v1 node using v2 validation settings
    verifyTx("deserialize", tx, proof, v2vs)
  }

  def checkRule(rule: ValidationRule, v2vs: SigmaValidationSettings, action: => Unit) = {
    // try SoftForkable block using current vs (v1 version)
    assertExceptionThrown({
      trySoftForkable(false) {
        action
        true
      }
    }, {
      case ve: ValidationException if ve.rule == rule => true
      case _ => false
    })

    val res = trySoftForkable(false)({
      action
      true
    })(v2vs)
    res shouldBe false
  }

  property("CheckTupleType rule") {
    val tuple = Tuple(IntConstant(1), IntConstant(2), IntConstant(3))
    val exp = SelectField(tuple, 3)
    val v2vs = vs.updated(CheckTupleType.id, ReplacedRule(0))
    checkRule(CheckTupleType, v2vs, {
      // simulate throwing of exception in
      CheckTupleType.throwValidationException(new SigmaException(s"Invalid tuple type"), Array[IR.Elem[_]]())
    })
  }

  property("CheckPrimitiveTypeCode rule") {
    val typeBytes = Array[Byte](MaxPrimTypeCode)
    val v2vs = vs.updated(CheckPrimitiveTypeCode.id, ChangedRule(Array[Byte](MaxPrimTypeCode)))
    checkRule(CheckPrimitiveTypeCode, v2vs, {
      val r = startReader(typeBytes)
      TypeSerializer.deserialize(r)
    })
  }

  property("CheckTypeCode rule") {
    val typeBytes = Array[Byte](newTypeCode)
    val v2vs = vs.updated(CheckTypeCode.id, ChangedRule(Array[Byte](newTypeCode)))
    checkRule(CheckTypeCode, v2vs, {
      val r = startReader(typeBytes)
      TypeSerializer.deserialize(r)
    })
  }

  property("CheckSerializableTypeCode rule") {
    val newType = SFunc(SInt, SInt)
    val dataBytes = Array[Byte](1, 2, 3) // any random bytes will work
    val v2vs = vs.updated(CheckSerializableTypeCode.id, ReplacedRule(0))
    checkRule(CheckSerializableTypeCode, v2vs, {
      val r = startReader(dataBytes)
      DataSerializer.deserialize(newType, r)
    })
  }

  property("CheckTypeWithMethods rule") {
    val freeMethodId = 1.toByte
    val mcBytes = Array[Byte](OpCodes.PropertyCallCode, newTypeCode, freeMethodId, Outputs.opCode)
    val v2vs = vs.updated(CheckTypeWithMethods.id, ChangedRule(Array(newTypeCode)))
    checkRule(CheckTypeWithMethods, v2vs, {
      ValueSerializer.deserialize(mcBytes)
    })
  }

  property("CheckMethod rule") {
    val freeMethodId = 16.toByte
    val mcBytes = Array[Byte](OpCodes.PropertyCallCode, SCollection.typeId, freeMethodId, Outputs.opCode)
    val v2vs = vs.updated(CheckAndGetMethod.id, ChangedRule(Array(SCollection.typeId, freeMethodId)))
    checkRule(CheckAndGetMethod, v2vs, {
      ValueSerializer.deserialize(mcBytes)
    })
  }

  property("CheckCostFuncOperation rule") {
    val exp = Height
    val v2vs = vs.updated(
      CheckCostFuncOperation.id,
      ChangedRule(CheckCostFuncOperation.encodeVLQUShort(Seq(OpCodes.toExtra(Height.opCode)))))
    checkRule(CheckCostFuncOperation, v2vs, {
      val costingRes = IR.doCostingEx(emptyEnv, exp, okRemoveIsProven = false)
      // We need to exercise CheckCostFunc rule.
      // The calcF graph have Height operation in it, which is not allowed to be in cost graph.
      // This leads to a ValidationException to be thrown with the CheckCostFunc rule in it.
      // And the rule is changed in v2vs, so Height is allowed, which is interpreted as
      // soft-fork condition
      CheckCostFunc(IR)(IR.asRep[Any => Int](costingRes.calcF))
    })
  }

  property("CheckCostFuncOperation rule (OpCodeExtra") {
    class TestingIRContextEmptyCodes extends TestingIRContext {
      override def isAllowedOpCodeInCosting(opCode: OpCodeExtra): Boolean = false
    }
    val tIR = new TestingIRContextEmptyCodes
    import tIR._
    val v2vs = vs.updated(CheckCostFuncOperation.id,
      ChangedRule(CheckCostFuncOperation.encodeVLQUShort(Seq(OpCodes.OpCostCode))))
    checkRule(CheckCostFuncOperation, v2vs, {
      implicit val anyType = AnyElement
      val v1 = variable[Int]
      val costF = fun[Any, Int] {_ => opCost(v1, Seq(1), 2) }
      CheckCostFunc(tIR)(asRep[Any => Int](costF))
    })
  }

  property("PrecompiledScriptProcessor is soft-forkable") {
    val p = ErgoLikeTestInterpreter.DefaultProcessorInTests
    val v1key = CacheKey(propV2treeBytes, vs)
    checkRule(CheckValidOpCode, v2vs, {
      p.getReducer(v1key)
    })

    val v2key = CacheKey(propV2treeBytes, v2vs)
    val r = p.getReducer(v2key)
    r shouldBe WhenSoftForkReducer

    val ctx = createContext(blockHeight, txV2, v2vs)
    r.reduce(ctx) shouldBe WhenSoftForkReductionResult(0)
  }

  override protected def afterAll(): Unit = {
    println(ErgoLikeTestInterpreter.DefaultProcessorInTests.getStats())
  }

}
