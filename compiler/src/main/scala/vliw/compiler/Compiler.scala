package vliw.compiler

import vliw.isa.IsaVersion

/** Entry point of the assembler and verifier, implemented from #6 onward. */
object Compiler {
  def targetIsa: String = IsaVersion.Current
}
