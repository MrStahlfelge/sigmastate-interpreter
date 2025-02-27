package sigmastate

import spire.syntax.all.cfor

import scala.util.DynamicVariable

trait VersionTesting {

  protected val activatedVersions: Seq[Byte] =
    (0 to VersionContext.MaxSupportedScriptVersion).map(_.toByte).toArray[Byte]

  private[sigmastate] val _currActivatedVersion = new DynamicVariable[Byte](0)
  def activatedVersionInTests: Byte = _currActivatedVersion.value

  /** Checks if the current activated script version used in tests corresponds to v4.x. */
  def isActivatedVersion4: Boolean = activatedVersionInTests < VersionContext.JitActivationVersion

  val ergoTreeVersions: Seq[Byte] =
    (0 to VersionContext.MaxSupportedScriptVersion).map(_.toByte).toArray[Byte]

  private[sigmastate] val _currErgoTreeVersion = new DynamicVariable[Byte](0)

  /** Current ErgoTree version assigned dynamically. */
  def ergoTreeVersionInTests: Byte = _currErgoTreeVersion.value

  /** Executes the given block for each combination of _currActivatedVersion and
    * _currErgoTreeVersion assigned to dynamic variables.
    */
  def forEachScriptAndErgoTreeVersion
    (activatedVers: Seq[Byte], ergoTreeVers: Seq[Byte])
    (block: => Unit): Unit = {
    cfor(0)(_ < activatedVers.length, _ + 1) { i =>
      val activatedVersion = activatedVers(i)
      // setup each activated version
      _currActivatedVersion.withValue(activatedVersion) {

        cfor(0)(
          i => i < ergoTreeVers.length && ergoTreeVers(i) <= activatedVersion,
          _ + 1) { j =>
          val treeVersion = ergoTreeVers(j)
          // for each tree version up to currently activated, set it up and execute block
          _currErgoTreeVersion.withValue(treeVersion)(block)
        }

      }
    }
  }

  /** Helper method which executes the given `block` once for each `activatedVers`.
    * The method sets the dynamic variable activatedVersionInTests with is then available
    * in the block.
    */
  def forEachActivatedScriptVersion(activatedVers: Seq[Byte])(block: => Unit): Unit = {
    cfor(0)(_ < activatedVers.length, _ + 1) { i =>
      val activatedVersion = activatedVers(i)
      _currActivatedVersion.withValue(activatedVersion)(block)
    }
  }

  /** Helper method which executes the given `block` once for each `ergoTreeVers`.
    * The method sets the dynamic variable ergoTreeVersionInTests with is then available
    * in the block.
    */
  def forEachErgoTreeVersion(ergoTreeVers: Seq[Byte])(block: => Unit): Unit = {
    cfor(0)(_ < ergoTreeVers.length, _ + 1) { i =>
      val version = ergoTreeVers(i)
      _currErgoTreeVersion.withValue(version)(block)
    }
  }

  val printVersions: Boolean = false

  protected def testFun_Run(testName: String, testFun: => Any): Unit = {
    def msg = s"""property("$testName")(ActivatedVersion = $activatedVersionInTests; ErgoTree version = $ergoTreeVersionInTests)"""
    if (printVersions) println(msg)
    try testFun
    catch {
      case t: Throwable =>
        if (!printVersions) {
          // wasn't printed, print it now
          println(msg)
        }
        throw t
    }
  }

}
