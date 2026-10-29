package vliw.crosscheck

import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters._

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.Verifier
import vliw.isa._
import vliw.sim.ReferenceModel

/** Program compatibility of ISA mvp-2 (docs/ISA.md, "mvp-2: W"): on an mvp-2 machine that equals an
  * mvp-1 machine apart from the keys mvp-2 adds, every mvp-1 program has the same verdict, outputs,
  * cycles and trace body; only the trace header (`isa`, `machine`, `machine_revision`,
  * `machine_hash`) differs. Checked with the mvp-1 test cases and benchmark programs, and with the
  * legal mvp-0 ones on the default machine, whose scalar operations are those of mvp-1 (#110 stage
  * 2).
  */
class Mvp2CompatibilitySpec extends AnyFunSuite {
  private def load(p: Path) =
    MachineDescription.load(p).fold(es => fail(es.mkString("\n")), identity)

  private val mvp0 = load(MachineDescription.DefaultPath)
  private val mvp1 = load(MachineDescription.Mvp1Path)
  private val mvp1Rp4 = load(Paths.get("tests", "machines", "mvp1_rp4.json"))
  private val mvp2 = load(MachineDescription.Mvp2Path)
  private val mvp2Rp4 = load(Paths.get("tests", "machines", "mvp2_rp4.json"))

  /** The mvp-2 machine for the programs of each machine: the same machine apart from mvp-2's keys.
    */
  private val counterpart = Map(mvp0.hash -> mvp2, mvp1.hash -> mvp2, mvp1Rp4.hash -> mvp2Rp4)

  private val W = Set("VADD", "VSUB", "VMUL", "VMAC", "LD.W", "ST.W")

  /** `m` without what mvp-2 adds, and without what names it; mvp-0 also without MAC. */
  private def scalarPart(m: MachineDescription, mac: Boolean) =
    m.copy(
      schemaVersion = 0,
      isaVersion = "",
      revision = 0,
      status = "",
      machineId = "",
      maxWordsPerAccess = 1,
      operations = m.operations -- W -- (if (mac) Nil else Seq("MAC")),
      canonicalJson = "",
      hash = ""
    )

  test("each mvp-2 counterpart equals its machine apart from the keys mvp-2 adds") {
    assert(scalarPart(mvp2, mac = true) == scalarPart(mvp1, mac = true))
    assert(scalarPart(mvp2Rp4, mac = true) == scalarPart(mvp1Rp4, mac = true))
    // mvp-0 has no MAC; its other operations, registers, scratchpad and instruction memory match.
    assert(scalarPart(mvp2, mac = false) == scalarPart(mvp0, mac = false))
  }

  private def cases(dir: Path): Vector[TestCase] =
    TestCase.loadAll(dir).fold(es => fail(es.mkString("\n")), identity)

  /** mvp-1 programs, and legal mvp-0 programs (an mvp-0 word that is illegal there may be legal on
    * mvp-1 and mvp-2: opcode 4 is MAC).
    */
  private val programs: Vector[TestCase] =
    (cases(TestCase.DefaultDirectory) ++ cases(Paths.get("benchmarks", "cases"))).filter { c =>
      counterpart.contains(c.machine.hash) &&
      (c.machine.isaVersion == IsaVersion.Mvp1 || c.expect.verdict == Verdict.Legal)
    }

  test(
    "the mvp-1 test cases and benchmark programs keep their verdict, outputs, cycles and trace"
  ) {
    assert(programs.count(_.machine.isaVersion == IsaVersion.Mvp1) > 30)
    assert(programs.count(_.machine.isaVersion == IsaVersion.Mvp0) > 15)
    assert(programs.exists(_.expect.verdict != Verdict.Legal))
    programs.foreach { c =>
      val w = counterpart(c.machine.hash)
      val declared = Verifier.Declaration.of(c.inputs)
      assert(
        Verifier.verify(w, c.words, declared) == Verifier.verify(c.machine, c.words, declared),
        c.name
      )
      (
        ReferenceModel.run(c.machine, c.words, c.inputs),
        ReferenceModel.run(w, c.words, c.inputs)
      ) match {
        case (Left(a), Left(b))   => assert(a == b, c.name)
        case (Right(a), Right(b)) =>
          assert(a.cycles == b.cycles && a.registers == b.registers && a.memory == b.memory, c.name)
          assert(b.trace.bodyLines == a.trace.bodyLines, c.name)
          // The header names the ISA and the machine; nothing else in it differs.
          val (ha, hb) = (a.trace.header, b.trace.header)
          val names = Set("isa", "machine", "machine_revision", "machine_hash")
          assert(ha.obj.keySet == hb.obj.keySet, c.name)
          ha.obj.keys.filterNot(names).foreach(k => assert(ha(k) == hb(k), s"${c.name}: $k"))
          assert(hb("isa").str == IsaVersion.Mvp2 && hb("machine").str == w.machineId, c.name)
          assert(
            hb("machine_hash").str == w.hash && hb("machine_revision").num == w.revision,
            c.name
          )
        case (a, b) => fail(s"${c.name}: ${c.machine.machineId} gives $a, ${w.machineId} gives $b")
      }
    }
  }

  test("the benchmark programs of benchmarks/cases are among them") {
    val names = Files
      .list(Paths.get("benchmarks", "cases"))
      .iterator()
      .asScala
      .map(_.getFileName.toString.stripSuffix(".json"))
      .toSet
    assert(programs.count(c => names(c.name)) >= 34)
  }
}
