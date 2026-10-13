package vliw.sim

import vliw.isa.IsaVersion

/** Cycle-accurate reference model, implemented in #8. */
object ReferenceModel {
  def modeledIsa: String = IsaVersion.Current
}
