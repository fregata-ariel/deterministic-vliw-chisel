package vliw.compiler.schedule

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.KernelCompiler
import vliw.compiler.dag.{Dag, Fusion, Node, Src}
import vliw.compiler.dsl._
import vliw.compiler.dsl.LoweredKernel.{Bin, Read, Write}
import vliw.isa._

class ScheduleSpec extends AnyFunSuite {
  private def machineFile(name: String) =
    MachineDescription
      .load(java.nio.file.Paths.get("tests", "machines", name))
      .fold(es => fail(es.mkString("\n")), identity)
  private val machine = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)
  private val machines =
    Seq(machine, machineFile("slow_units.json"), machineFile("ii_units.json"))

  private def dagOf(k: Kernel, m: MachineDescription) =
    Dag.of(Lowering.lower(MemoryLayout.fixedOrder(k, m).fold(e => fail(e), identity)), m)

  // ISA mvp-1: MAC latency 2 with three and four read ports, and MAC latency 3 (ADR-0018).
  private val mvp1 = MachineDescription
    .load(java.nio.file.Paths.get("config", "machine-mvp1.json"))
    .fold(es => fail(es.mkString("\n")), identity)
  private val rp4 = machineFile("mvp1_rp4.json")
  private val mac3 = machineFile("mvp1_mac3.json")

  /** The DAG the compiler schedules: fused on mvp-1. */
  private def preparedDag(k: Kernel, m: MachineDescription) =
    KernelCompiler.prepare(k, m).fold(e => fail(e), identity).dag

  /** Registers read by the operand fields of a node (L11, form F: docs/ISA.md "mvp-2: W"). */
  private def reads(n: Node) = n match {
    case _: Node.Compute   => 2
    case _: Node.Mac       => 3
    case _: Node.Store     => 1
    case _: Node.Load      => 0
    case _: Node.VCompute  => 2
    case _: Node.VMac      => 3
    case _: Node.StoreW    => 1
    case _: Node.StoreLane => 1
    case _: Node.LoadW     => 0
  }

  /** Every constraint a schedule must meet, checked independently of the scheduler. */
  private def violations(s: Schedule): Seq[String] = {
    val d = s.dag
    def isCompute(i: Int) = d.machine.operation(d.nodes(i).mnemonic).slot == Slot.Compute
    val all = d.nodes.indices
    val edges = d.edges.collect {
      case e if s.issue(e.to) < s.issue(e.from) + e.distance =>
        s"edge $e: ${s.issue(e.from)} -> ${s.issue(e.to)}"
    }
    val slots = all.groupBy(i => (s.issue(i), isCompute(i))).collect {
      case ((c, comp), ns) if ns.size > 1 =>
        s"cycle $c: ${ns.size} ${if (comp) "compute" else "memory"} operations"
    }
    // One reservation per operation that writes back (a pair too); stores of every kind write none.
    val portUsers = all.collect {
      case i if d.machine.operation(d.nodes(i).mnemonic).writeback.nonEmpty =>
        (isCompute(i), s.issue(i) + d.latency(i)) -> i
    }
    val ports = portUsers.groupBy(_._1).collect {
      case ((comp, e), us) if us.size > 1 =>
        s"E_$e: ${us.size} commits on the ${if (comp) "compute" else "memory"} port"
    }
    val ii = all.groupBy(i => d.nodes(i).mnemonic).toSeq.flatMap { case (m, ns) =>
      ns.map(s.issue).sorted.sliding(2).collect {
        case Seq(a, b) if b - a < d.machine.operation(m).ii =>
          s"$m issued at $a and $b (II ${d.machine.operation(m).ii})"
      }
    }
    val halt =
      (if (all.exists(i => s.issue(i) + d.latency(i) > s.halt))
         Seq("an operation completes after HALT")
       else Nil) ++
        (if (all.nonEmpty && s.halt != all.map(i => s.issue(i) + d.latency(i)).max)
           Seq("HALT is later than needed")
         else Nil) ++
        (if (all.exists(i => s.issue(i) < 0 || s.issue(i) >= s.halt))
           Seq("an operation is not before HALT")
         else Nil)
    val readPorts = all.groupBy(s.issue).collect {
      case (c, ns) if ns.map(i => reads(d.nodes(i))).sum > d.machine.readPorts =>
        s"cycle $c: ${ns.map(i => reads(d.nodes(i))).sum} reads (L11)"
    }
    edges ++ slots ++ ports ++ ii ++ halt ++ readPorts
  }

  /** The two counts of a schedule without a register limit, from the issue cycles alone: the cycles
    * in which an unplaced ST that its edges and II allow is held back only by the read ports (with
    * the compute node of that cycle), and those in which such a compute node is held back only by
    * its write-back port, taken by a node issued earlier.
    */
  private def recount(s: Schedule): (Int, Int) = {
    val d = s.dag
    def op(i: Int) = d.machine.operation(d.nodes(i).mnemonic)
    def allowed(i: Int, c: Int) = s.issue(i) > c &&
      d.predecessors(i).forall(e => s.issue(e.from) < c && s.issue(e.from) + e.distance <= c) &&
      d.nodes.indices.forall { j =>
        op(j) != op(i) || s.issue(j) >= c || s.issue(j) + op(i).ii <= c
      }
    val cycles = 0 to s.issue.maxOption.getOrElse(0)
    val computeAt = d.nodes.indices.filter(op(_).slot == Slot.Compute).groupBy(s.issue)
    val stores = d.nodes.indices.filter(i => Set("ST", "ST.W")(d.nodes(i).mnemonic))
    val compute = d.nodes.indices.filter(op(_).slot == Slot.Compute)
    val readHeld = cycles.count { c =>
      val bundle = computeAt.getOrElse(c, Nil).map(i => reads(d.nodes(i))).sum
      stores.exists(i => allowed(i, c) && bundle + 1 > d.machine.readPorts)
    }
    val portHeld = cycles.count { c =>
      compute.exists { i =>
        allowed(i, c) && compute.exists { j =>
          s.issue(j) < c && s.issue(j) + op(j).latency == c + op(i).latency
        }
      }
    }
    (readHeld, portHeld)
  }

  test("the ISA example chain is scheduled in 7 cycles, as in docs/ISA.md") {
    val d =
      Dag.build(machine, Vector(Write(10, Bin(Op.Add, Bin(Op.Mul, Read(0), Read(1)), Read(2)))))
    val s = ListScheduler.schedule(d)
    assert(violations(s).isEmpty, violations(s))
    // LD [0] 0, LD [1] 1, LD [2] 2, MUL 3, ADD 5, ST 6, HALT 7: 8 cycles, one more than the ISA
    // example, which loads only two words. The DAG bound is 7; one memory slot costs one cycle.
    assert(s.issue == Vector(0, 1, 3, 2, 5, 6) && s.cycles == 8)
    val two =
      Dag.build(machine, Vector(Write(1, Bin(Op.Add, Bin(Op.Mul, Read(0), Read(0)), Read(0)))))
    assert(ListScheduler.schedule(two).cycles == two.cyclesLowerBound) // one load: 7 cycles
    assert(two.cyclesLowerBound == 7)
  }

  test("write-back ports are reserved for future boundaries (L5)") {
    // MUL at s commits at E_(s+2); an independent ADD must not issue at s+1.
    val d = Dag.build(
      machine,
      Vector(Write(5, Bin(Op.Mul, Read(0), Read(1))), Write(6, Bin(Op.Add, Read(2), Read(3))))
    )
    val s = ListScheduler.schedule(d)
    assert(violations(s).isEmpty, violations(s))
    val mul = d.nodes.indexWhere { case Node.Compute(Op.Mul, _, _) => true; case _ => false }
    val add = d.nodes.indexWhere { case Node.Compute(Op.Add, _, _) => true; case _ => false }
    assert(s.issue(add) + 1 != s.issue(mul) + 2)
  }

  test("the II of each operation is respected (L9)") {
    val ii = machineFile("ii_units.json") // MUL II 2
    val d =
      Dag.build(ii, (0 until 4).map(i => Write(10 + i, Bin(Op.Mul, Read(0), Read(i + 1)))).toVector)
    val s = ListScheduler.schedule(d)
    assert(violations(s).isEmpty, violations(s))
    val muls = d.nodes.indices.filter(i => d.nodes(i).mnemonic == "MUL").map(s.issue).sorted
    assert(muls.sliding(2).forall { case Seq(a, b) => b - a >= 2; case _ => true })
  }

  test("benchmark schedules meet every constraint and both lower bounds, on three machines") {
    for (m <- machines; k <- Benchmarks.all; p <- Priority.all) {
      val d = dagOf(k, m)
      val s = ListScheduler.schedule(d, p)
      val label = s"${k.name} on ${m.machineId} with ${p.name}"
      assert(violations(s).isEmpty, s"$label: ${violations(s)}")
      assert(s.cycles >= d.cyclesLowerBound && s.cycles >= d.resourceLowerBound, label)
    }
  }

  test("cycles of the benchmarks on the default machine") {
    def cycles(p: Priority) =
      Benchmarks.all.map(k => k.name -> ListScheduler.schedule(dagOf(k, machine), p).cycles).toMap
    // saxpy8 meets the slot bound (26). fir4x8 and matmul3 are compute-bound (bounds 57 and 46);
    // the gap is the start (two loads before the first MUL: 3 cycles), the end (ST, then HALT) and
    // up to two compute-slot holes where an ADD would share a write-back boundary with a MUL (L5).
    // Recorded here so that scheduler changes show up (docs/COMPILER.md).
    assert(cycles(Priority.CriticalPath) == Map("saxpy8" -> 26, "fir4x8" -> 63, "matmul3" -> 52))
    assert(cycles(Priority.ProgramOrder) == Map("saxpy8" -> 28, "fir4x8" -> 63, "matmul3" -> 53))
  }

  private def mul(a: LoweredKernel.Value, b: LoweredKernel.Value) = Bin(Op.Mul, a, b)
  private def add(a: LoweredKernel.Value, b: LoweredKernel.Value) = Bin(Op.Add, a, b)
  private def fused(m: MachineDescription, ws: Write*) = Fusion.fuse(Dag.build(m, ws.toVector))

  test("mvp-1 schedules meet every constraint; with three read ports no bundle has MAC and ST") {
    val limits = None +: (0 to 8).map(r => Some(ListScheduler.Limit(16, r)))
    val together = for {
      m <- Seq(mvp1, rp4, mac3); k <- Benchmarks.all; p <- Priority.all; l <- limits
      s <- ListScheduler.run(preparedDag(k, m), p, l)
    } yield {
      val d = s.dag
      val label = s"${k.name} on ${m.machineId} with ${p.name}, $l"
      assert(violations(s).isEmpty, s"$label: ${violations(s)}")
      assert(s.cycles >= d.cyclesLowerBound && s.cycles >= d.resourceLowerBound, label)
      m.machineId -> s.bundles.count { b =>
        b.compute.exists(d.nodes(_).isInstanceOf[Node.Mac]) &&
        b.memory.exists(d.nodes(_).isInstanceOf[Node.Store])
      }
    }
    val byMachine = together.groupMapReduce(_._1)(_._2)(_ + _)
    assert(byMachine(mvp1.machineId) == 0 && byMachine(mac3.machineId) == 0)
    assert(byMachine(rp4.machineId) > 0) // four read ports allow it
  }

  test("a ST that the read ports hold back waits, and the cycle is counted (L11)") {
    // [10] = [0] * [1] + [2]; [11] = [2]: LD 0, LD 1, LD 2, MAC 3, ST [10] 4, ST [11] 5. The MAC
    // and the ST of [11] are ready at 4, when the LD of [2] completes; compute is filled first.
    val ws = Seq(Write(10, add(mul(Read(0), Read(1)), Read(2))), Write(11, Read(2)))
    val three = ListScheduler.schedule(fused(mvp1, ws: _*))
    assert(three.issue == Vector(0, 1, 2, 4, 6, 5) && three.cycles == 8)
    assert(three.heldByReadPorts == 1 && three.heldByWritePort == 0)
    val four = ListScheduler.schedule(fused(rp4, ws: _*))
    assert(four.issue == Vector(0, 1, 2, 4, 6, 4) && four.cycles == 8)
    assert(four.heldByReadPorts == 0 && four.bundles(4) == Slots(Some(3), Some(5)))
    assert(Seq(three, four).forall(violations(_).isEmpty))
  }

  test("a compute node that its write-back port holds back is counted (L5), also for MAC") {
    // [5] = [0] * [1]; [6] = [0] + [1]: the ADD is ready at 3 with the MUL, which goes first; at 4
    // it would commit at E_5 with the MUL.
    val ws = Seq(Write(5, mul(Read(0), Read(1))), Write(6, add(Read(0), Read(1))))
    for (m <- Seq(machine, mvp1)) {
      val s = ListScheduler.schedule(fused(m, ws: _*))
      assert(s.issue == Vector(0, 1, 3, 5, 5, 6) && s.cycles == 8, m.machineId)
      assert(s.heldByWritePort == 1 && s.heldByReadPorts == 0, m.machineId)
    }
    // [10] = [0] * [1] + [2]; [11] = [0] * [2]: LD 0, LD 1, LD 2, MAC 3, ST 4, MUL 5, ST 6. With
    // MAC latency 3, MAC(4) and MUL(5) would both commit at E_7; with latency 2 they do not.
    val mac = Seq(Write(10, add(mul(Read(0), Read(1)), Read(2))), Write(11, mul(Read(0), Read(2))))
    val slow = ListScheduler.schedule(fused(mac3, mac: _*))
    assert(slow.issue == Vector(0, 1, 2, 4, 7, 6, 8) && slow.cycles == 10)
    assert(slow.heldByWritePort == 1 && violations(slow).isEmpty)
    val fast = ListScheduler.schedule(fused(mvp1, mac: _*))
    assert(fast.issue == Vector(0, 1, 2, 4, 6, 5, 7) && fast.cycles == 9)
    assert(fast.heldByWritePort == 0 && violations(fast).isEmpty)
  }

  test("the counts agree with a recount from the issue cycles alone") {
    val dags =
      for (m <- machines ++ Seq(mvp1, rp4, mac3); k <- Benchmarks.all)
        yield preparedDag(k, m)
    val random = for {
      m <- Seq(mvp1, mac3); k <- RandomKernels.kernels(49L, 100)
    } yield preparedDag(k, m)
    val counts = for (d <- dags ++ random; p <- Priority.all) yield {
      val s = ListScheduler.schedule(d, p)
      assert((s.heldByReadPorts, s.heldByWritePort) == recount(s), s"${d.machine.machineId} $p")
      if (d.machine.hasMac) 0 else s.heldByReadPorts
    }
    assert(counts.forall(_ == 0)) // mvp-0 reads at most three registers per bundle
  }

  test("cycles and counts of the benchmarks without a register limit, mvp-0 and mvp-1") {
    def got(m: MachineDescription, p: Priority) = Benchmarks.all.map { k =>
      val s = ListScheduler.schedule(preparedDag(k, m), p)
      k.name -> ((s.cycles, s.heldByReadPorts, s.heldByWritePort))
    }.toMap
    // (cycles, cycles a ST waited for the read ports, cycles a compute node waited for its
    // write-back port). Recorded so that scheduler changes show up (docs/COMPILER.md). With MAC
    // latency 2, fir4x8 and matmul3 issue only MUL and MAC, which never collide on the port.
    val cp = Priority.CriticalPath
    val po = Priority.ProgramOrder
    def row(s: (Int, Int, Int), f: (Int, Int, Int), m: (Int, Int, Int)) =
      Map("saxpy8" -> s, "fir4x8" -> f, "matmul3" -> m)
    assert(got(machine, cp) == row((26, 0, 1), (63, 0, 30), (52, 0, 25)))
    assert(got(machine, po) == row((28, 0, 1), (63, 0, 30), (53, 0, 24)))
    assert(got(mvp1, cp) == row((27, 7, 0), (44, 6, 0), (40, 7, 0)))
    assert(got(mvp1, po) == row((28, 5, 0), (40, 0, 0), (39, 0, 0)))
    assert(got(rp4, cp) == row((26, 0, 0), (38, 0, 0), (33, 0, 0)))
    assert(got(rp4, po) == row((28, 0, 0), (40, 0, 0), (39, 0, 0)))
    assert(got(mac3, cp) == row((27, 6, 0), (44, 5, 0), (40, 6, 0)))
    assert(got(mac3, po) == row((29, 2, 0), (53, 0, 19), (45, 0, 11)))
  }

  test("the II of MAC is respected (L9)") {
    val v = ujson.read(mvp1.canonicalJson)
    v("operations")("MAC")("ii") = 2
    val m = MachineDescription.parse(ujson.write(v)).fold(es => fail(es.mkString), identity)
    val d =
      fused(m, (0 until 4).map(i => Write(10 + i, add(mul(Read(0), Read(i + 1)), Read(5)))): _*)
    assert(d.macs == 4)
    val s = ListScheduler.schedule(d)
    assert(violations(s).isEmpty, violations(s))
    val macs = d.nodes.indices.filter(d.nodes(_).isInstanceOf[Node.Mac]).map(s.issue).sorted
    assert(macs.sliding(2).forall { case Seq(a, b) => b - a >= 2; case _ => true })
  }

  // ISA mvp-2 (W, #110 stage 4): packed DAGs (docs/COMPILER.md "ISA mvp-2（W）").
  private val mvp2 = MachineDescription
    .load(java.nio.file.Paths.get("config", "machine-mvp2.json"))
    .fold(es => fail(es.mkString("\n")), identity)
  private val wMachines = mvp2 +: Seq("mvp2_mw1", "mvp2_vlat", "mvp2_vii", "mvp2_rp4")
    .map(n => machineFile(s"$n.json"))
  private def withRegisters(m: MachineDescription, n: Int) = {
    val v = ujson.read(m.canonicalJson)
    v("register_file")("count") = n
    MachineDescription.parse(ujson.write(v)).fold(es => fail(es.mkString), identity)
  }
  private def packedDag(k: Kernel, m: MachineDescription) =
    KernelCompiler.prepare(k, m).fold(e => fail(e), identity).packing.get.dag

  /** The most registers live in one cycle, from the schedule alone (docs/COMPILER.md "ISA mvp-2
    * （W）"): a value from its issue to its last reader's issue, 1 register for a scalar and 2 for a
    * LD.W or a vector operation; a gather is 2 registers from its earlier lane to the last reader
    * of either lane.
    */
  private def maxRegisters(s: Schedule): Int = {
    val d = s.dag
    def isPair(n: Node) = n match {
      case _: Node.LoadW | _: Node.VCompute | _: Node.VMac => true
      case _                                               => false
    }
    def isStore(n: Node) = n match {
      case _: Node.Store | _: Node.StoreW | _: Node.StoreLane => true
      case _                                                  => false
    }
    val gathers = d.nodes.flatMap(_.sources).collect { case g: Src.Gather => (g.lo, g.hi) }.distinct
    val lanes = gathers.flatMap { case (lo, hi) => Seq(lo, hi) }.toSet
    def held(nodes: Seq[Int], weight: Int) = {
      val start = nodes.map(s.issue).min
      val readers = d.nodes.indices.filter(j => d.nodes(j).operands.exists(nodes.contains))
      (start, readers.map(s.issue).maxOption.getOrElse(start + 1), weight)
    }
    val held2 = gathers.map { case (lo, hi) => held(Seq(lo, hi), 2) } ++
      d.nodes.indices.collect {
        case i if !isStore(d.nodes(i)) && !lanes(i) =>
          held(Seq(i), if (isPair(d.nodes(i))) 2 else 1)
      }
    (0 to s.halt).map(c => held2.collect { case (a, b, w) if a <= c && c < b => w }.sum).max
  }

  test("ISA mvp-2: packed schedules meet every constraint, and a limit holds by registers") {
    val kernels = Benchmarks.all :+ Benchmarks.saxpy(7)
    val machines =
      wMachines :+ withRegisters(mvp2, 8) :+ withRegisters(machineFile("mvp2_mw1.json"), 8)
    val random = RandomKernels.kernels(110L, 60)
    var limited = 0
    val together = for {
      m <- machines; k <- kernels ++ (if (m.registerCount == 16) random else Nil)
      p <- Priority.all
      l <- None +: (0 to m.registerCount / 2).map(r =>
        Some(ListScheduler.Limit(m.registerCount, r))
      )
      s <- ListScheduler.run(packedDag(k, m), p, l)
    } yield {
      val d = s.dag
      val label = s"${k.name} on ${m.machineId} (${m.registerCount}) with ${p.name}, $l"
      assert(violations(s).isEmpty, s"$label: ${violations(s)}")
      assert(s.cycles >= d.cyclesLowerBound && s.cycles >= d.resourceLowerBound, label)
      // Under a limit, pairs and gathers count 2 (ADR-0023): never more registers than the limit.
      l.foreach { x =>
        limited += 1
        assert(maxRegisters(s) <= x.registers, s"$label: ${maxRegisters(s)} registers live")
      }
      // VMAC (3 fields) with ST.W (1) in one bundle needs 4 read ports.
      m.readPorts -> s.bundles.count { b =>
        b.compute.exists(d.nodes(_).isInstanceOf[Node.VMac]) &&
        b.memory.exists(d.nodes(_).isInstanceOf[Node.StoreW])
      }
    }
    val byPorts = together.groupMapReduce(_._1)(_._2)(_ + _)
    assert(byPorts(3) == 0 && byPorts(4) > 0, byPorts)
    assert(limited > 1000, limited)
  }

  test("ISA mvp-2: heldByReadPorts counts ST.W and lane STs; the counts agree with a recount") {
    // saxpy8, critical-path: the ST.W (or lane ST) ready beside a VMAC waits at three VMACs.
    val full = ListScheduler.schedule(packedDag(Benchmarks.saxpy(8), mvp2))
    assert(full.cycles == 15 && full.heldByReadPorts == 3 && full.heldByWritePort == 0)
    val reg = ListScheduler.schedule(packedDag(Benchmarks.saxpy(8), machineFile("mvp2_mw1.json")))
    assert(reg.cycles == 27 && reg.heldByReadPorts == 3)
    for (m <- wMachines; k <- Benchmarks.all :+ Benchmarks.saxpy(7); p <- Priority.all) {
      val s = ListScheduler.schedule(packedDag(k, m), p)
      assert((s.heldByReadPorts, s.heldByWritePort) == recount(s), s"${k.name} ${m.machineId} $p")
    }
  }

  test("ISA mvp-2: a MAC that would commit with a VMAC waits for the write-back port (mvp2_vlat)") {
    // saxpy7 on mvp2_vlat (VMAC latency 3, MAC 2), critical-path: the VMACs issue at 9, 10 and 11;
    // the MAC is ready at 11, where E_13 belongs to the VMAC at 10, and at 12, where E_14 belongs to
    // the VMAC at 11. It issues at 13.
    val d = packedDag(Benchmarks.saxpy(7), machineFile("mvp2_vlat.json"))
    val s = ListScheduler.schedule(d)
    val at = d.nodes.indices.groupMap(d.nodes(_).mnemonic)(s.issue)
    assert(at("VMAC") == Seq(9, 10, 11) && at("MAC") == Seq(13))
    assert(s.heldByWritePort == 2 && violations(s).isEmpty)
    assert((s.heldByReadPorts, s.heldByWritePort) == recount(s))
  }
}
