package vliw.crosscheck

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.loop.LoopBenchmarks
import vliw.compiler.modulo.{Attempt, Mii, ModuloCompiler, Outcome}
import vliw.isa.MachineDescription

/** The Modulo Scheduling records explain a loop that fits no II on the graph that `ModuloCompiler`
  * schedules: on a machine with MAC, the graph fused into MAC when `FusionChoice` says so.
  */
class ModuloRecordsSpec extends AnyFunSuite {
  test("a failure is explained on the fused graph on a machine with MAC") {
    // mvp-1 with 20 bundles of instruction memory: fir4x8's 23 memory operations fit no II.
    val v = ujson.read(
      MacRecords.machineAt("config/machine-mvp1.json").canonicalJson
    )
    v("instruction_memory")("bundles") = 20
    val small =
      MachineDescription.parse(ujson.write(v)).fold(es => fail(es.mkString("\n")), identity)
    val k = LoopBenchmarks.fir(Seq(1L, 2L, 3L, 4L), 8)
    assert(ModuloCompiler.compile(k, small).isLeft)
    val prepared = ModuloCompiler.prepare(k, small).fold(e => fail(e), identity)
    assert(prepared.fusion.exists(_.fuses))
    val no = ModuloRecords.failure(k, small)
    assert(no.mii == Mii.of(prepared.graph) && no.mii.resMII == 4)
    assert(no.attempts.nonEmpty && no.attempts.forall(_.outcome != Outcome.Fits))
  }

  test("the reasons group no aligned pair free as (b′), apart from (b)") {
    // ISA mvp-2 (#110 stage 5a): MODULO.md (mvp-0) has none, so a hand-made list of attempts.
    val attempts = Seq(
      Attempt(4, Outcome.TooManyLive(18, 16)),
      Attempt(5, Outcome.NoAlignedPair(12, 14, 16)),
      Attempt(6, Outcome.NoAlignedPair(15, 15, 16)),
      Attempt(7, Outcome.Fits)
    )
    assert(
      ModuloRecords.reasons(attempts) ==
        "II 4: (b) レジスタ（同時に生きる値 18 > 16）。II 5〜6: (b′) 整列した組（cycle 12 で生きる 14、cycle 15 で生きる 15 / 16）"
    )
  }
}
