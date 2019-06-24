package sigmastate.utxo

import org.ergoplatform.ErgoLikeContext
import sigmastate._
import sigmastate.basics.DLogProtocol.DLogInteractiveProver
import sigmastate.basics.DiffieHellmanTupleInteractiveProver
import sigmastate.lang.Terms._
import sigmastate.helpers.{ContextEnrichingTestProvingInterpreter, ErgoLikeTestProvingInterpreter, SigmaTestingCommons}
import sigmastate.interpreter._

class DistributedSigSpecification extends SigmaTestingCommons {
  implicit lazy val IR: TestingIRContext = new TestingIRContext

  /**
    * An example test where Alice (A) and Bob (B) are signing an input in a distributed way. A statement which
    * protects the box to spend is "pubkey_Alice && pubkey_Bob". Note that a signature in this case is about
    * a transcript of a Sigma-protocol ((a_Alice, a_Bob), e, (z_Alice, z_Bob)),
    * which is done in non-interactive way (thus "e" is got via Fiat-Shamir transformation).
    *
    *
    * For that, they are going through following steps:
    *
    * - Bob is generating first protocol message a_Bob and sends it to Alice
    * - Alice forms a hint which contain Bob's commitment "a_Bob", and puts the hint into a hints bag
    * - She proves the statement using the bag, getting the partial protocol transcript
    * (a_Alice, e, z_Alice) as a result and sends "a_Alice" and "z_Alice" to Bob.
    * Please note that "e" is got from both a_Alice and a_Bob.
    * - Bob now also knows a_Alice, so can generate the same "e" as Alice. Thus Bob is generating valid
    * proof ((a_Alice, a_Bob), e, (z_Alice, z_Bob)).
    */
  property("distributed AND (2 out of 2)") {
    val proverA = new ErgoLikeTestProvingInterpreter
    val proverB = new ErgoLikeTestProvingInterpreter
    val verifier: ContextEnrichingTestProvingInterpreter = new ContextEnrichingTestProvingInterpreter

    val pubkeyAlice = proverA.dlogSecrets.head.publicImage
    val pubkeyBob = proverB.dlogSecrets.head.publicImage

    val env = Map("pubkeyA" -> pubkeyAlice, "pubkeyB" -> pubkeyBob)
    val prop: Values.Value[SSigmaProp.type] = compile(env, """pubkeyA && pubkeyB""").asSigmaProp

    val ctx: ErgoLikeContext = ErgoLikeContext(
      currentHeight = 1,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      minerPubkey = ErgoLikeContext.dummyPubkey,
      boxesToSpend = IndexedSeq(fakeSelf),
      spendingTransaction = null,
      self = fakeSelf)

    val (rBob, aBob) = DLogInteractiveProver.firstMessage(pubkeyBob)

    val dlBKnown: Hint = OtherCommitment(pubkeyBob, aBob)
    val bag = HintsBag(Seq(dlBKnown))

    val proofAlice = proverA.prove(prop, ctx, fakeMessage, bag).get

    val bagB = proverB.bagForMultisig(ctx, prop, proofAlice.proof, Seq(pubkeyAlice))
      .addHint(OwnCommitment(pubkeyBob, rBob, aBob))

    val proofBob = proverB.prove(prop, ctx, fakeMessage, bagB).get

    // Proof generated by Alice without getting Bob's part is not correct
    verifier.verify(prop, ctx, proofAlice, fakeMessage).get._1 shouldBe false

    // Compound proof from Bob is correct
    verifier.verify(prop, ctx, proofBob, fakeMessage).get._1 shouldBe true
  }

  property("distributed AND (3 out of 3)") {
    val proverA = new ErgoLikeTestProvingInterpreter
    val proverB = new ErgoLikeTestProvingInterpreter
    val proverC = new ErgoLikeTestProvingInterpreter
    val verifier: ContextEnrichingTestProvingInterpreter = new ContextEnrichingTestProvingInterpreter

    val pubkeyAlice = proverA.dlogSecrets.head.publicImage
    val pubkeyBob = proverB.dlogSecrets.head.publicImage
    val pubkeyCarol = proverC.dlogSecrets.head.publicImage

    val env = Map("pubkeyA" -> pubkeyAlice, "pubkeyB" -> pubkeyBob, "pubkeyC" -> pubkeyCarol)
    val prop: Values.Value[SSigmaProp.type] = compile(env, """pubkeyA && pubkeyB && pubkeyC""").asSigmaProp

    val ctx: ErgoLikeContext = ErgoLikeContext(
      currentHeight = 1,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      minerPubkey = ErgoLikeContext.dummyPubkey,
      boxesToSpend = IndexedSeq(fakeSelf),
      spendingTransaction = null,
      self = fakeSelf)

    val (rBob, aBob) = DLogInteractiveProver.firstMessage(pubkeyBob)
    val (rCarol, aCarol) = DLogInteractiveProver.firstMessage(pubkeyBob)

    val dlBKnown: Hint = OtherCommitment(pubkeyBob, aBob)
    val dlCKnown: Hint = OtherCommitment(pubkeyCarol, aCarol)
    val bag = HintsBag(Seq(dlBKnown, dlCKnown))

    val proofAlice = proverA.prove(prop, ctx, fakeMessage, bag).get

    val bagC = proverB.bagForMultisig(ctx, prop, proofAlice.proof, Seq(pubkeyAlice))
      .addHint(OwnCommitment(pubkeyCarol, rCarol, aCarol))
      .addHint(dlBKnown)

    val proofCarol = proverC.prove(prop, ctx, fakeMessage, bagC).get

    val bagB = proverB.bagForMultisig(ctx, prop, proofCarol.proof, Seq(pubkeyAlice, pubkeyCarol))
      .addHint(OwnCommitment(pubkeyBob, rBob, aBob))

    val proofBob = proverB.prove(prop, ctx, fakeMessage, bagB).get

    // Proof generated by Alice without getting Bob's part is not correct
    verifier.verify(prop, ctx, proofAlice, fakeMessage).get._1 shouldBe false

    // Proof generated by Alice without getting Bob's part is not correct
    verifier.verify(prop, ctx, proofCarol, fakeMessage).get._1 shouldBe false

    // Compound proof from Bob is correct
    verifier.verify(prop, ctx, proofBob, fakeMessage).get._1 shouldBe true
  }


  /**
    * An example test where Alice (A), Bob (B) and Carol (C) are signing in a distributed way an input, which is
    * protected by 2-out-of-3 threshold multi-signature.
    *
    * A statement which protects the box to spend is "atLeast(2, Coll(pubkeyA, pubkeyB, pubkeyC))".
    *
    * The scheme is the same as in the previous example.
    */
  property("distributed THRESHOLD - 2 out of 3") {
    val proverA = new ErgoLikeTestProvingInterpreter
    val proverB = new ErgoLikeTestProvingInterpreter
    val proverC = new ErgoLikeTestProvingInterpreter
    val verifier = new ContextEnrichingTestProvingInterpreter

    val pubkeyAlice = proverA.dlogSecrets.head.publicImage
    val pubkeyBob = proverB.dlogSecrets.head.publicImage
    val pubkeyCarol = proverC.dlogSecrets.head.publicImage

    val env = Map("pubkeyA" -> pubkeyAlice, "pubkeyB" -> pubkeyBob, "pubkeyC" -> pubkeyCarol)
    val prop = compile(env, """atLeast(2, Coll(pubkeyA, pubkeyB, pubkeyC))""").asSigmaProp

    val ctx = ErgoLikeContext(
      currentHeight = 1,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      minerPubkey = ErgoLikeContext.dummyPubkey,
      boxesToSpend = IndexedSeq(fakeSelf),
      spendingTransaction = null,
      self = fakeSelf)

    val (rBob, aBob) = DLogInteractiveProver.firstMessage(pubkeyBob)
    val dlBKnown: Hint = OtherCommitment(pubkeyBob, aBob)
    val (rCarol, aCarol) = DLogInteractiveProver.firstMessage(pubkeyCarol)
    val dlCKnown: Hint = OtherCommitment(pubkeyCarol, aCarol)
    val bagA = HintsBag(Seq(dlBKnown, dlCKnown))

    val proofAlice = proverA.prove(prop, ctx, fakeMessage, bagA).get

    val bagB = proverB.bagForMultisig(ctx, prop, proofAlice.proof, Seq(pubkeyAlice, pubkeyCarol))
      .addHint(OwnCommitment(pubkeyBob, rBob, aBob))

    println(bagB)

    val proofBob = proverB.prove(prop, ctx, fakeMessage, bagB).get

    // Proof generated by Alice without getting Bob's part is not correct
    verifier.verify(prop, ctx, proofAlice, fakeMessage).get._1 shouldBe false

    println(proofBob)

    // Compound proof from Bob is correct
    verifier.verify(prop, ctx, proofBob, fakeMessage).get._1 shouldBe true
  }

  /**
    * Distributed threshold signature, 3 out of 4 case.
    */
  property("distributed THRESHOLD - 3 out of 4") {
    val proverA = new ErgoLikeTestProvingInterpreter
    val proverB = new ErgoLikeTestProvingInterpreter
    val proverC = new ErgoLikeTestProvingInterpreter
    val proverD = new ErgoLikeTestProvingInterpreter
    val verifier = new ContextEnrichingTestProvingInterpreter

    val pubkeyAlice = proverA.dlogSecrets.head.publicImage
    val pubkeyBob = proverB.dlogSecrets.head.publicImage
    val pubkeyCarol = proverC.dlogSecrets.head.publicImage
    val pubkeyDave = proverD.dlogSecrets.head.publicImage

    val env = Map("pubkeyA" -> pubkeyAlice, "pubkeyB" -> pubkeyBob, "pubkeyC" -> pubkeyCarol, "pubkeyD" -> pubkeyDave)
    val prop = compile(env, """atLeast(3, Coll(pubkeyA, pubkeyB, pubkeyC, pubkeyD))""").asSigmaProp

    val ctx = ErgoLikeContext(
      currentHeight = 1,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      minerPubkey = ErgoLikeContext.dummyPubkey,
      boxesToSpend = IndexedSeq(fakeSelf),
      spendingTransaction = null,
      self = fakeSelf)

    val (_, aAlice) = DLogInteractiveProver.firstMessage(pubkeyAlice)
    val dlAKnown: Hint = OtherCommitment(pubkeyAlice, aAlice)

    val (rBob, aBob) = DLogInteractiveProver.firstMessage(pubkeyBob)
    val dlBKnown: Hint = OtherCommitment(pubkeyBob, aBob)

    val (_, aCarol) = DLogInteractiveProver.firstMessage(pubkeyCarol)
    val dlCKnown: Hint = OtherCommitment(pubkeyCarol, aCarol)

    val bagA = HintsBag(Seq(dlBKnown, dlCKnown))
    val proofAlice = proverA.prove(prop, ctx, fakeMessage, bagA).get

    val bagC = HintsBag(Seq(dlAKnown, dlBKnown))
    val proofCarol = proverC.prove(prop, ctx, fakeMessage, bagC).get

    val bagB = (proverB.bagForMultisig(ctx, prop, proofAlice.proof, Seq(pubkeyAlice)) ++
                proverB.bagForMultisig(ctx, prop, proofCarol.proof, Seq(pubkeyCarol)))
                  .addHint(OwnCommitment(pubkeyBob, rBob, aBob))

    val proofBob = proverB.prove(prop, ctx, fakeMessage, bagB).get

    // Proof generated by Alice without getting Bob's part is not correct
    verifier.verify(prop, ctx, proofAlice, fakeMessage).get._1 shouldBe false

    // Compound proof from Bob is correct
    verifier.verify(prop, ctx, proofBob, fakeMessage).get._1 shouldBe true
  }

  /**
    * Distributed threshold signature, 3 out of 4 case, 1 real and 1 simulated secrets are of DH kind.
    */
  property("distributed THRESHOLD - 3 out of 4 - w. DH") {
    val proverA = new ErgoLikeTestProvingInterpreter
    val proverB = new ErgoLikeTestProvingInterpreter
    val proverC = new ErgoLikeTestProvingInterpreter
    val proverD = new ErgoLikeTestProvingInterpreter
    val verifier = new ContextEnrichingTestProvingInterpreter

    val pubkeyAlice = proverA.dlogSecrets.head.publicImage
    val pubkeyBob = proverB.dlogSecrets.head.publicImage
    val pubkeyCarol = proverC.dhSecrets.head.publicImage
    val pubkeyDave = proverD.dhSecrets.head.publicImage

    val env = Map("pubkeyA" -> pubkeyAlice, "pubkeyB" -> pubkeyBob, "pubkeyC" -> pubkeyCarol, "pubkeyD" -> pubkeyDave)
    val prop = compile(env, """atLeast(3, Coll(pubkeyA, pubkeyB, pubkeyC, pubkeyD))""").asSigmaProp

    val ctx = ErgoLikeContext(
      currentHeight = 1,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      minerPubkey = ErgoLikeContext.dummyPubkey,
      boxesToSpend = IndexedSeq(fakeSelf),
      spendingTransaction = null,
      self = fakeSelf)

    val (_, aAlice) = DLogInteractiveProver.firstMessage(pubkeyAlice)
    val dlAKnown: Hint = OtherCommitment(pubkeyAlice, aAlice)

    val (rBob, aBob) = DLogInteractiveProver.firstMessage(pubkeyBob)
    val dlBKnown: Hint = OtherCommitment(pubkeyBob, aBob)

    val (_, aCarol) = DiffieHellmanTupleInteractiveProver.firstMessage(pubkeyCarol)
    val dlCKnown: Hint = OtherCommitment(pubkeyCarol, aCarol)

    val bagA = HintsBag(Seq(dlBKnown, dlCKnown))
    val proofAlice = proverA.prove(prop, ctx, fakeMessage, bagA).get

    val bagC = HintsBag(Seq(dlAKnown, dlBKnown))
    val proofCarol = proverC.prove(prop, ctx, fakeMessage, bagC).get

    val bagB = (proverB.bagForMultisig(ctx, prop, proofAlice.proof, Seq(pubkeyAlice)) ++
                proverB.bagForMultisig(ctx, prop, proofCarol.proof, Seq(pubkeyCarol)))
                  .addHint(OwnCommitment(pubkeyBob, rBob, aBob))

    val proofBob = proverB.prove(prop, ctx, fakeMessage, bagB).get

    // Proof generated by Alice without getting Bob's part is not correct
    verifier.verify(prop, ctx, proofAlice, fakeMessage).get._1 shouldBe false

    // Compound proof from Bob is correct
    verifier.verify(prop, ctx, proofBob, fakeMessage).get._1 shouldBe true
  }
}
