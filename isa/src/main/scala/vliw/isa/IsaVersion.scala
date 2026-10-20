package vliw.isa

/** ISA versions implemented by this code base (docs/ISA.md). `mvp-1` is `mvp-0` plus MAC, and
  * `mvp-2` is `mvp-1` plus W, the two-lane operations on register pairs (ADR-0022). Each machine
  * description names its version, and [[MachineDescription.parse]] rejects any other.
  */
object IsaVersion {
  val Mvp0: String = "mvp-0"
  val Mvp1: String = "mvp-1"
  val Mvp2: String = "mvp-2"
  val all: Seq[String] = Seq(Mvp0, Mvp1, Mvp2)

  /** The ISA version a machine description schema version is for (docs/MACHINE.md): schema 1 is for
    * mvp-0, 2 for mvp-1 and 3 for mvp-2.
    */
  def ofSchema(schemaVersion: Int): String = schemaVersion match {
    case 1     => Mvp0
    case 2     => Mvp1
    case 3     => Mvp2
    case other => throw new IllegalArgumentException(s"no ISA version has schema_version $other")
  }

  /** ISA mvp-2 is implemented in the stages of #110. Until its stage has landed, a component that
    * receives an mvp-2 machine refuses it with this reason, rather than treating it as mvp-1.
    * `component` names the code and `stage` the #110 stage that implements mvp-2 in it.
    */
  def notImplemented(
      machine: MachineDescription,
      component: String,
      stage: String
  ): Option[String] =
    Option.when(machine.isaVersion == Mvp2)(
      s"ISA mvp-2 is not implemented in $component yet (#110 $stage); machine ${machine.machineId}"
    )
}
