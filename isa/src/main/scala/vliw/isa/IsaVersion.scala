package vliw.isa

/** ISA versions implemented by this code base (docs/ISA.md). `mvp-1` is `mvp-0` plus MAC. Each
  * machine description names its version, and [[MachineDescription.parse]] rejects any other.
  */
object IsaVersion {
  val Mvp0: String = "mvp-0"
  val Mvp1: String = "mvp-1"
  val all: Seq[String] = Seq(Mvp0, Mvp1)

  /** The ISA version a machine description schema version is for (docs/MACHINE.md). */
  def ofSchema(schemaVersion: Int): String = if (schemaVersion == 1) Mvp0 else Mvp1
}
