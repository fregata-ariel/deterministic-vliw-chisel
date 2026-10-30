package vliw.compiler

import java.nio.file.Paths

import scala.util.Random

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.dag.{LaneReason, ScalarLane}
import vliw.compiler.dsl._
import vliw.compiler.regalloc.NoFit
import vliw.isa._

/** The compiler on ISA mvp-2 (W), list scheduling (#110 stage 4; ADR-0023, docs/COMPILER.md "ISA
  * mvp-2（W）"): saxpy8 end to end, odd lengths, the lane and kernel fallbacks. Outputs are checked
  * with `IsaExecutor` (written from docs/ISA.md) against the DSL reference implementation.
  */
class WCompilerSpec extends AnyFunSuite {
  import WCompilerSpec._

  private def load(path: String*) = MachineDescription
    .load(Paths.get(path.head, path.tail: _*))
    .fold(es => fail(es.mkString("\n")), identity)
  private val full = load("config", "machine-mvp2.json")
  private val mw1 = load("tests", "machines", "mvp2_mw1.json")
  private val rp4 = load("tests", "machines", "mvp2_rp4.json")
  private val vii = load("tests", "machines", "mvp2_vii.json")
  private val vlat = load("tests", "machines", "mvp2_vlat.json")
  private val mvp1 = load("config", "machine-mvp1.json")
  private def withRegisters(m: MachineDescription, n: Int) = {
    val v = ujson.read(m.canonicalJson)
    v("register_file")("count") = n
    MachineDescription.parse(ujson.write(v)).fold(es => fail(es.mkString), identity)
  }

  private def compiled(k: Kernel, m: MachineDescription) =
    KernelCompiler.compile(k, m).fold(e => fail(s"${k.name} on ${m.machineId}: $e"), identity)

  /** The outputs of `c` on `in`, run by `IsaExecutor`, equal the DSL reference implementation's. */
  private def computes(c: CompiledKernel, in: Values): Unit = {
    val image = c.layout.memoryImage(in).fold(e => fail(e), identity)
    val out = c.layout.outputs(IsaExecutor.run(c.machine, c.bundles, image))
    assert(out == Interpreter.run(c.kernel, in).fold(e => fail(e), identity), c.kernel.name)
  }

  /** The code matches what `simd` says (docs/COMPILER.md "ISA mvp-2（W）"): a fallback has no W
    * operation; a packed kernel has exactly the packed DAG's vector operations and ST.W, and its
    * LD.W (with the copies of a rematerializing attempt); no W-reg code has LD.W or ST.W.
    */
  private def invariants(c: CompiledKernel): Unit = {
    val label = s"${c.kernel.name} on ${c.machine.machineId}"
    def count(m: String) = c.bundles.count(b => mnemonics(b).contains(m))
    c.simd match {
      case Simd.Fallback(_, p) =>
        assert(!c.bundles.exists(isW) && c.dag == p.scalar, label)
      case Simd.Packed(p) =>
        assert(c.dag == p.dag, label)
        Seq("VADD", "VSUB", "VMUL", "VMAC", "ST.W").foreach(m => assert(count(m) == p.count(m), m))
        val copies = c.allocated.dag.nodes.count(_.mnemonic == "LD.W")
        assert(count("LD.W") == copies && copies >= p.count("LD.W"), label)
      case Simd.Scalar => fail(s"$label: an mvp-2 machine")
    }
    if (!c.machine.hasWideMemory) assert(!c.bundles.exists(wideMemory), label)
    assert(c.bundles.size == c.cycles && c.words.size == c.cycles, label)
  }

  /** The operations of `c` by mnemonic (the "演算" column of COMPILER.md's results). */
  private def operations(c: CompiledKernel): Map[String, Int] =
    c.bundles.flatMap(mnemonics).groupMapReduce(identity)(_ => 1)(_ + _)

  /** The other columns of COMPILER.md's results ("結果（W、16 レジスタ）") for `c`: cycles, the dependence
    * and slot bounds of the DAG scheduled, the chosen attempt and `heldByReadPorts`.
    */
  private def recorded(c: CompiledKernel, cycles: Int, slots: Int, held: Int): Unit = {
    val label = s"${c.kernel.name} on ${c.machine.machineId}"
    assert(c.cycles == cycles, label)
    assert(c.dag.cyclesLowerBound == 6 && c.dag.resourceLowerBound == slots, label)
    assert(c.allocated.attempt.toString == "critical-path, no register limit", label)
    assert(c.allocated.schedule.heldByReadPorts == held, label)
  }

  private val saxpy8 = Benchmarks.saxpy(8)

  test("saxpy8 on W-full: 1 LD, 8 LD.W, 4 VMAC broadcasting a, 4 ST.W at 17..23; 15 cycles") {
    val c = compiled(saxpy8, full)
    assert(c.simd.isInstanceOf[Simd.Packed])
    val memory = c.bundles.map(_.memory)
    val lds = memory.collect { case MemoryOp.Ld(r, a) => (r, a) }
    assert(lds.map(_._2) == Vector(16)) // a; no scalar LD of x or y
    assert(memory.collect { case MemoryOp.LdW(_, a) => a }.sorted == (0 until 16 by 2))
    assert(memory.collect { case MemoryOp.StW(a, _) => a } == Vector(17, 19, 21, 23))
    assert(!memory.exists(_.isInstanceOf[MemoryOp.St]))
    val vmacs = c.bundles.map(_.compute).collect { case v: ComputeOp.VMac => v }
    assert(vmacs.size == 4 && vmacs.forall(v => v.broadcast && v.rs1 == lds.head._1))
    assert(!c.bundles.exists(_.compute.isInstanceOf[ComputeOp.Mac]))
    // The COMPILER.md record. A hand schedule of the CriticalPath attempt (loads at 0-8, VMACs at 4,
    // 6, 8, 10, ST.W at 9, 11, 12, 13, HALT 14), the hand-written w_saxpy8_hand and the #78
    // estimate all give 15.
    // ST.W waits for the read ports beside a VMAC at 6, 8 and 10 (L11, form F: 3 + 1 > 3).
    recorded(c, cycles = 15, slots = 14, held = 3)
    assert(operations(c) == Map("LD" -> 1, "LD.W" -> 8, "VMAC" -> 4, "ST.W" -> 4))
    invariants(c)
    val rng = new Random(1101L)
    (0 until 3).foreach(_ => computes(c, RandomKernels.values(saxpy8, rng)))
  }

  test("saxpy8 on W-reg: 17 LD (8 gathers into aligned pairs), 4 VMAC, 8 lane STs; 27 cycles") {
    val c = compiled(saxpy8, mw1)
    assert(c.simd.isInstanceOf[Simd.Packed])
    assert(!c.bundles.exists(wideMemory))
    val memory = c.bundles.map(_.memory)
    assert(memory.count(_.isInstanceOf[MemoryOp.Ld]) == 17)
    assert(memory.collect { case MemoryOp.St(a, _) => a }.sorted == (17 to 24))
    val vmacs = c.bundles.zipWithIndex.collect { case (Bundle(v: ComputeOp.VMac, _), s) => (v, s) }
    assert(vmacs.size == 4)
    // rs2 and rs3 of every VMAC are gathers: the last writes of p and p + 1 before it are LDs.
    def lastWrite(r: Int, before: Int) = (0 until before).reverse.map(c.bundles(_)).collectFirst {
      case Bundle(_, MemoryOp.Ld(`r`, a))                                     => s"ld $a"
      case b if writes(b.compute).contains(r) || writes(b.memory).contains(r) => "other"
    }
    vmacs.foreach { case (v, s) =>
      Seq(v.rs2, v.rs3).foreach { p =>
        assert(lastWrite(p, s).exists(_.startsWith("ld")), s"r$p of $v at $s")
        assert(lastWrite(p + 1, s).exists(_.startsWith("ld")), s"r${p + 1} of $v at $s")
      }
    }
    // The COMPILER.md record (the slot bound is 26; the #78 estimate is 27).
    recorded(c, cycles = 27, slots = 26, held = 3)
    assert(operations(c) == Map("LD" -> 17, "VMAC" -> 4, "ST" -> 8))
    invariants(c)
    val rng = new Random(1102L)
    (0 until 3).foreach(_ => computes(c, RandomKernels.values(saxpy8, rng)))
  }

  test("saxpy7: three VMAC and one MAC, the lane fallback out(6) by rule 1, no kernel fallback") {
    val rng = new Random(1107L)
    // The COMPILER.md records.
    val rows = Seq(
      (full, 15, 14, Map("LD" -> 3, "LD.W" -> 6, "VMAC" -> 3, "MAC" -> 1, "ST.W" -> 3, "ST" -> 1)),
      (mw1, 24, 23, Map("LD" -> 15, "VMAC" -> 3, "MAC" -> 1, "ST" -> 7))
    )
    for ((m, cycles, slots, ops) <- rows) {
      val c = compiled(Benchmarks.saxpy(7), m)
      c.simd match {
        case Simd.Packed(p) =>
          assert(p.scalarLanes == Vector(ScalarLane(21, LaneReason.NoNeighbour)), m.machineId)
        case other => fail(s"${m.machineId}: $other")
      }
      recorded(c, cycles, slots, held = 3)
      assert(operations(c) == ops, m.machineId)
      invariants(c)
      (0 until 3).foreach(_ => computes(c, RandomKernels.values(c.kernel, rng)))
    }
  }

  test("saxpy1: the kernel fallback NothingToPack, with the mvp-1 code") {
    val k = Benchmarks.saxpy(1)
    for (m <- Seq(full, mw1)) {
      val c = compiled(k, m)
      c.simd match {
        case Simd.Fallback(FallbackReason.NothingToPack, p) =>
          assert(p.scalarLanes == Vector(ScalarLane(3, LaneReason.NoNeighbour)))
        case other => fail(s"${m.machineId}: $other")
      }
      assert(c.bundles == compiled(k, mvp1).bundles)
      recorded(c, cycles = 8, slots = 5, held = 0) // the COMPILER.md record
      KernelCompiler.candidate(k, m) match {
        case Right(SimdCandidate.NothingToPack(_, scalar)) => assert(scalar.words == c.words)
        case other                                         => fail(s"${m.machineId}: $other")
      }
      invariants(c)
      assert(KernelCompiler.describeW(c).startsWith("W: scalar fallback, (NothingToPack: "))
    }
  }

  test("saxpy8 on a 4-register W-full: the SIMD candidate is infeasible; normal use falls back") {
    val tiny = withRegisters(full, 4)
    val failures = KernelCompiler.candidate(saxpy8, tiny) match {
      case Right(SimdCandidate.Infeasible(_, f)) => f
      case other                                 => fail(other.toString)
    }
    // Unlimited, a and two LD.W pairs are 5 registers; under the limit every attempt gets stuck.
    assert(failures.size == 8)
    assert(failures.take(2).forall(_._2.isInstanceOf[NoFit.TooManyLive]))
    assert(failures.drop(2).forall(_._2 == NoFit.Stuck))
    val c = compiled(saxpy8, tiny)
    assert(
      c.simd == Simd.Fallback(
        FallbackReason.NoAttemptFits(failures),
        c.simd match {
          case Simd.Fallback(_, p) => p
          case other               => fail(other.toString)
        }
      )
    )
    assert(c.bundles == compiled(saxpy8, withRegisters(mvp1, 4)).bundles)
    assert(
      KernelCompiler.describeW(c) ==
        "W: scalar fallback, every attempt of the packed DAG failed (TooManyLive 2, Stuck 6)"
    )
    invariants(c)
    val rng = new Random(1104L)
    (0 until 3).foreach(_ => computes(c, RandomKernels.values(saxpy8, rng)))
    // A kernel that fits neither way is an input error that names both failures.
    val e = KernelCompiler.compile(Benchmarks.matmul(3), withRegisters(full, 2)).left.toOption
    assert(e.exists(m => m.contains("no schedule fits 2 registers") && m.contains("packed DAG")))
    assert(!e.exists(_.startsWith("compiler error")))
  }

  test("the benchmarks and odd sizes on the W machines: correct outputs, the code invariants") {
    val rng = new Random(1105L)
    val kernels = Benchmarks.all ++ Seq(Benchmarks.saxpy(7), Benchmarks.fir(Seq(1L, 2L, 3L, 4L), 7))
    val got =
      for (m <- Seq(full, mw1, rp4, vii, vlat); k <- kernels; single <- Seq(false, true))
        yield {
          val c = KernelCompiler
            .compile(k, m, singleIssue = single)
            .fold(e => fail(s"${k.name} on ${m.machineId}: $e"), identity)
          invariants(c)
          // Compiling again gives the same program.
          assert(KernelCompiler.compile(k, m, singleIssue = single).map(_.words) == Right(c.words))
          (0 until 2).foreach(_ => computes(c, RandomKernels.values(k, rng)))
          if (m.readPorts == 3) assert(!c.bundles.exists(fourFields), s"${k.name} ${m.machineId}")
          s"${k.name} ${m.machineId.stripPrefix("vliw2-i32-sp2-mac-")}${if (single) " single"
            else ""}: ${c.cycles}"
        }
    info(got.mkString("; "))
    // read_ports 4 lets VMAC and ST.W share a bundle (the #78 estimate for saxpy8 is 14).
    assert(compiled(saxpy8, rp4).bundles.exists(fourFields))
    assert(compiled(saxpy8, rp4).cycles == 14)
  }
}

object WCompilerSpec {

  /** The mnemonics of a bundle's operations (NOP and HALT left out). */
  def mnemonics(b: Bundle): Seq[String] = {
    val c = b.compute match {
      case _: ComputeOp.Add               => Some("ADD")
      case _: ComputeOp.Sub               => Some("SUB")
      case _: ComputeOp.Mul               => Some("MUL")
      case _: ComputeOp.Mac               => Some("MAC")
      case _: ComputeOp.VAdd              => Some("VADD")
      case _: ComputeOp.VSub              => Some("VSUB")
      case _: ComputeOp.VMul              => Some("VMUL")
      case _: ComputeOp.VMac              => Some("VMAC")
      case ComputeOp.Nop | ComputeOp.Halt => None
    }
    val m = b.memory match {
      case _: MemoryOp.Ld  => Some("LD")
      case _: MemoryOp.St  => Some("ST")
      case _: MemoryOp.LdW => Some("LD.W")
      case _: MemoryOp.StW => Some("ST.W")
      case MemoryOp.Nop    => None
    }
    c.toSeq ++ m
  }

  /** Whether a bundle has an operation that ISA mvp-2 added. */
  def isW(b: Bundle): Boolean =
    mnemonics(b).exists(Set("VADD", "VSUB", "VMUL", "VMAC", "LD.W", "ST.W"))

  def wideMemory(b: Bundle): Boolean = b.memory.isInstanceOf[MemoryOp.LdW] ||
    b.memory.isInstanceOf[MemoryOp.StW]

  /** A bundle with 4 operand fields (L11, form F): (MAC or VMAC) with (ST or ST.W). */
  def fourFields(b: Bundle): Boolean =
    (b.compute.isInstanceOf[ComputeOp.Mac] || b.compute.isInstanceOf[ComputeOp.VMac]) &&
      (b.memory.isInstanceOf[MemoryOp.St] || b.memory.isInstanceOf[MemoryOp.StW])

  /** The registers an operation writes. */
  def writes(op: ComputeOp): Seq[Int] = op match {
    case ComputeOp.Add(d, _, _)         => Seq(d)
    case ComputeOp.Sub(d, _, _)         => Seq(d)
    case ComputeOp.Mul(d, _, _)         => Seq(d)
    case ComputeOp.Mac(d, _, _, _)      => Seq(d)
    case ComputeOp.VAdd(d, _, _)        => Seq(d, d + 1)
    case ComputeOp.VSub(d, _, _)        => Seq(d, d + 1)
    case ComputeOp.VMul(d, _, _, _)     => Seq(d, d + 1)
    case ComputeOp.VMac(d, _, _, _, _)  => Seq(d, d + 1)
    case ComputeOp.Nop | ComputeOp.Halt => Nil
  }
  def writes(op: MemoryOp): Seq[Int] = op match {
    case MemoryOp.Ld(d, _)  => Seq(d)
    case MemoryOp.LdW(d, _) => Seq(d, d + 1)
    case _                  => Nil
  }
}
