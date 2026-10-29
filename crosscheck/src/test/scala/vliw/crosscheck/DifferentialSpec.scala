package vliw.crosscheck

import scala.util.Random

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.Verifier
import vliw.isa._
import vliw.sim.{Commit, ReferenceModel}

/** Random programs judged by both the static verifier and the reference model. The two share no
  * code beyond `isa`, so agreement is evidence that both follow docs/ISA.md.
  */
class DifferentialSpec extends AnyFunSuite {
  import DifferentialSpec.{Gen, Judged}

  private val machine = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)
  private val codec = new BundleCodec(machine)

  /** Machine descriptions that differ from `of` in latencies, II and capacity. */
  private def variant(
      edit: ujson.Value => Unit,
      of: MachineDescription = machine
  ): MachineDescription = {
    val v = ujson.read(of.canonicalJson)
    edit(v)
    MachineDescription.parse(ujson.write(v)).fold(es => fail(es.mkString("\n")), identity)
  }
  private val variants: Seq[(String, MachineDescription)] = Seq(
    "slow_units (tests/machines)" -> MachineDescription
      .load(java.nio.file.Paths.get("tests", "machines", "slow_units.json"))
      .fold(es => fail(es.mkString("\n")), identity),
    "ii_units (tests/machines)" -> MachineDescription
      .load(java.nio.file.Paths.get("tests", "machines", "ii_units.json"))
      .fold(es => fail(es.mkString("\n")), identity),
    "add L=2, sub L=3, 4 banks x 64 words" -> variant { v =>
      v("operations")("ADD")("latency") = 2
      v("operations")("SUB")("latency") = 3
      v("scratchpad")("banks") = 4
      v("scratchpad")("words_per_bank") = 64
      v("scratchpad")("bank_mapping") = "word_address_mod_4"
      v("scratchpad")("row_mapping") = "word_address_div_4"
    },
    "all units L=1, 2 words" -> variant { v =>
      Seq("MUL", "LD").foreach(op => v("operations")(op)("latency") = 1)
      v("scratchpad")("words_per_bank") = 1
    }
  )

  /** ISA mvp-1 (#77): the default machine (three read ports), four read ports (the verifier and the
    * model only), MAC latency 3, and MAC II 2.
    */
  private def load(path: java.nio.file.Path) =
    MachineDescription.load(path).fold(es => fail(es.mkString("\n")), identity)
  private val mvp1 = load(MachineDescription.Mvp1Path)
  private val mvp1Variants: Seq[(String, MachineDescription)] = Seq(
    "machine-mvp1 (config)" -> mvp1,
    "mvp1_rp4 (tests/machines)" -> load(
      java.nio.file.Paths.get("tests", "machines", "mvp1_rp4.json")
    ),
    "mvp1_mac3 (tests/machines)" -> load(
      java.nio.file.Paths.get("tests", "machines", "mvp1_mac3.json")
    ),
    "mvp-1 with MAC II 2" -> variant(
      { v =>
        v("machine_id") = "vliw2-i32-sp2-mac-ii2"
        v("status") = "proposed"
        v("operations")("MAC")("ii") = 2
      },
      of = mvp1
    )
  )

  /** Few registers and addresses so that hazards are frequent; HALT usually last. With `mac` (ISA
    * mvp-1), about a quarter of the Compute operations are MAC, a third of them with rd = rs3.
    */
  private def program(rng: Random, mac: Boolean = false): Vector[Bundle] = {
    def r = rng.nextInt(4)
    def a = rng.nextInt(4)
    def compute: ComputeOp = rng.nextInt(if (mac) 13 else 10) match {
      case 0 | 1 | 2 => ComputeOp.Nop
      case 3 | 4     => ComputeOp.Add(r, r, r)
      case 5 | 6     => ComputeOp.Sub(r, r, r)
      case 7 | 8     => ComputeOp.Mul(r, r, r)
      case 9         => ComputeOp.Halt
      case _         =>
        val d = r
        ComputeOp.Mac(d, r, r, if (rng.nextInt(3) == 0) d else r)
    }
    def memory: MemoryOp = rng.nextInt(4) match {
      case 0 | 1 => MemoryOp.Nop
      case 2     => MemoryOp.Ld(r, a)
      case _     => MemoryOp.St(a, r)
    }
    val body = Vector.fill(rng.nextInt(8)) {
      val c = compute
      Bundle(c, if (c == ComputeOp.Halt) MemoryOp.Nop else memory)
    }
    rng.nextInt(10) match {
      case 0     => body // usually without HALT
      case 1 | 2 => body ++ Vector.fill(rng.nextInt(3))(Bundle.Nop) :+ Bundle(ComputeOp.Halt)
      case _     => body :+ Bundle.Nop :+ Bundle.Nop :+ Bundle(ComputeOp.Halt)
    }
  }

  /** Declares a random subset of r0..r3 and of the addresses 0..3 that exist on `m`, leaving out
    * each with probability 1 / `oneIn`.
    */
  private def inputs(rng: Random, m: MachineDescription = machine, oneIn: Int = 3): Inputs =
    Inputs(
      (0 until 4)
        .filter(_ => rng.nextInt(oneIn) != 0)
        .map(_ -> (rng.nextLong() & 0xffffffffL))
        .toMap,
      (0 until 4.min(m.scratchpadWords))
        .filter(_ => rng.nextInt(oneIn) != 0)
        .map(_ -> (rng.nextLong() & 0xffffffffL))
        .toMap
    )

  /** MAC reads three registers: mvp-1 programs leave out fewer inputs, so that enough are legal. */
  private def leftOut(mac: Boolean) = if (mac) 8 else 3

  private def scalar(m: MachineDescription, mac: Boolean = false) =
    Gen(program(_, mac), inputs(_, m, leftOut(mac)))

  /** Judges `count` random programs on machine `m` with both implementations: they give the same
    * verdict, a legal program runs `bundles.size` cycles, and the model's first violation is among
    * the verifier's findings.
    */
  private def agree(m: MachineDescription, seed: Long, count: Int, gen: Gen): Vector[Judged] = {
    val rng = new Random(seed)
    val c = new BundleCodec(m)
    Vector.tabulate(count) { i =>
      val bundles = gen.program(rng)
      val in = gen.inputs(rng)
      val words = c.encodeAll(bundles).fold(e => fail(e), identity)
      val findings = Verifier.verify(m, words, Verifier.Declaration.of(in))
      val result = ReferenceModel.run(m, words, in)
      def show = s"${m.machineId} #$i\n" + Disassembler.program(bundles) +
        findings.mkString("verifier:\n  ", "\n  ", "\n") + s"model: $result"
      (findings.isEmpty, result) match {
        case (true, Right(run)) => assert(run.cycles == bundles.size, show)
        case (false, Left(v))   => assert(findings.map(_.rule).contains(v.rule), show)
        case _                  => fail(s"verdicts differ\n$show")
      }
      Judged(bundles, in, findings.map(_.rule).toSet, result.left.toOption.map(_.rule))
    }
  }

  /** How often the model stops at each rule first. */
  private def firstRules(judged: Vector[Judged]): Map[Rule, Int] =
    judged.flatMap(_.first).groupMapReduce(identity)(_ => 1)(_ + _)

  test(
    "verifier and reference model agree under machine descriptions with other latencies and capacity"
  ) {
    variants.foreach { case (label, m) =>
      val judged = agree(m, 99L, 8000, scalar(m))
      val (legal, rules) = (judged.count(_.first.isEmpty), firstRules(judged))
      assert(legal > 100, s"$label: only $legal legal programs")
      // With every latency 1 nothing is ever in flight, so L3, L5 and L6 cannot occur there. L9
      // needs an II above 1 and L10 a store latency above 1.
      val expected =
        if (label.startsWith("all units L=1"))
          Set[Rule](Rule.ProgramShape, Rule.AddressRange, Rule.UndefinedRead)
        else
          Set[Rule](
            Rule.ProgramShape,
            Rule.InFlightRead,
            Rule.OverlappingWrites,
            Rule.WritebackPort
          ) ++ (if (label.startsWith("ii_units")) Set(Rule.IssueInterval) else Set.empty) ++
            (if (label.startsWith("slow_units")) Set(Rule.InFlightStoreRead) else Set.empty)
      assert(expected.subsetOf(rules.keySet), s"$label: rules hit first: ${rules.keys.map(_.id)}")
    }
  }

  test("verifier and reference model agree on legality for random programs") {
    val rng = new Random(20261014L)
    var legal = 0
    val illegalByRule = collection.mutable.Map.empty[Rule, Int].withDefaultValue(0)
    (0 until 30000).foreach { i =>
      val bundles = program(rng)
      val in = inputs(rng)
      val words = codec.encodeAll(bundles).fold(e => fail(e), identity)
      val findings = Verifier.verify(machine, words, Verifier.Declaration.of(in))
      val result = ReferenceModel.run(machine, words, in)
      def show =
        s"#$i inputs=${in.registers.keys.toSeq.sorted.map("r" + _)} ${in.memory.keys.toSeq.sorted}\n" +
          Disassembler
            .program(bundles) + findings.mkString("verifier:\n  ", "\n  ", "\n") + s"model: $result"
      (findings.isEmpty, result) match {
        case (true, Right(run)) =>
          legal += 1
          assert(run.cycles == bundles.size, show)
        case (false, Left(v)) =>
          assert(findings.map(_.rule).contains(v.rule), show)
          illegalByRule(v.rule) += 1
        case _ => fail(s"verdicts differ\n$show")
      }
    }
    // The generator must exercise both outcomes and every rule the model can hit first.
    assert(legal > 1000, s"only $legal legal programs")
    Seq(
      Rule.ProgramShape,
      Rule.InFlightRead,
      Rule.OverlappingWrites,
      Rule.WritebackPort,
      Rule.HaltCompletion,
      Rule.UndefinedRead
    )
      .foreach(rule =>
        assert(illegalByRule(rule) > 50, s"${rule.id} hit only ${illegalByRule(rule)} times")
      )
  }

  /** Checks `count` legal random programs on `m`: other input values give the same cycles and
    * commit timing. Returns the programs checked.
    */
  private def sameTiming(
      m: MachineDescription,
      seed: Long,
      count: Int,
      gen: Gen
  ): Vector[Vector[Bundle]] = {
    val rng = new Random(seed)
    val encoder = new BundleCodec(m)
    val checked = Vector.newBuilder[Vector[Bundle]]
    var n = 0
    while (n < count) {
      val bundles = gen.program(rng)
      val in = gen.inputs(rng)
      val words = encoder.encodeAll(bundles).toOption.get
      if (Verifier.verify(m, words, Verifier.Declaration.of(in)).isEmpty) {
        def timing(i: Inputs) = ReferenceModel.run(m, words, i).map { r =>
          (
            r.cycles,
            r.trace.cycles.map(c =>
              (
                c.cycle,
                c.pc,
                c.issue,
                c.commits.map {
                  case Commit.Register(reg, _, port) => s"r$reg/${port.name}"
                  case Commit.Memory(a, _)           => s"[$a]"
                },
                c.busy,
                c.done
              )
            )
          )
        }
        val reference = timing(in)
        (0 until 5).foreach { _ =>
          val other = Inputs(
            in.registers.map { case (k, _) => k -> (rng.nextLong() & 0xffffffffL) },
            in.memory.map { case (k, _) => k -> (rng.nextLong() & 0xffffffffL) }
          )
          assert(timing(other) == reference, s"${m.machineId}\n" + Disassembler.program(bundles))
        }
        checked += bundles
        n += 1
      }
    }
    checked.result()
  }

  test("for legal programs, cycles and commit timing do not depend on input values") {
    sameTiming(machine, 7L, 2000, scalar(machine))
  }

  // ISA mvp-1 (#77, docs/ISA.md "mvp-1: MAC").

  private def macs(b: Vector[Bundle]): Vector[(ComputeOp.Mac, Int)] =
    b.zipWithIndex.collect { case (Bundle(op: ComputeOp.Mac, _), s) => (op, s) }

  /** The mnemonic of a Compute operation and the registers it writes (two for W). */
  private def computeWrite(op: ComputeOp): Option[(String, Seq[Int])] = op match {
    case ComputeOp.Add(d, _, _)         => Some("ADD" -> Seq(d))
    case ComputeOp.Sub(d, _, _)         => Some("SUB" -> Seq(d))
    case ComputeOp.Mul(d, _, _)         => Some("MUL" -> Seq(d))
    case ComputeOp.Mac(d, _, _, _)      => Some("MAC" -> Seq(d))
    case ComputeOp.VAdd(d, _, _)        => Some("VADD" -> Seq(d, d + 1))
    case ComputeOp.VSub(d, _, _)        => Some("VSUB" -> Seq(d, d + 1))
    case ComputeOp.VMul(d, _, _, _)     => Some("VMUL" -> Seq(d, d + 1))
    case ComputeOp.VMac(d, _, _, _, _)  => Some("VMAC" -> Seq(d, d + 1))
    case ComputeOp.Nop | ComputeOp.Halt => None
  }

  /** The mnemonic of a LD or LD.W and the registers it writes. */
  private def loadWrite(op: MemoryOp): Option[(String, Seq[Int])] = op match {
    case MemoryOp.Ld(d, _)                                     => Some("LD" -> Seq(d))
    case MemoryOp.LdW(d, _)                                    => Some("LD.W" -> Seq(d, d + 1))
    case MemoryOp.St(_, _) | MemoryOp.StW(_, _) | MemoryOp.Nop => None
  }

  /** Whether an operation issued before cycle t writes `reg` and its value is not yet visible at t.
    */
  private def writing(m: MachineDescription, b: Vector[Bundle], t: Int, reg: Int): Boolean =
    (0 until t).exists { s =>
      (computeWrite(b(s).compute) ++ loadWrite(b(s).memory)).exists { case (op, rs) =>
        rs.contains(reg) && t < s + m.operation(op).latency
      }
    }

  /** The shapes of MAC that the generator must produce: rd = rs3; rs3 (and neither rs1 nor rs2)
    * being written; MAC and ST in one bundle; a MAC(s) and an ADD, SUB or MUL issued later that
    * commit at the same boundary (L5: at s + 1 with MAC latency 2, MUL at s + 1 or ADD/SUB at s + 2
    * with latency 3).
    */
  private def accumulates(b: Vector[Bundle]) = macs(b).exists { case (op, _) => op.rd == op.rs3 }
  private def rs3Written(m: MachineDescription, b: Vector[Bundle]) = macs(b).exists {
    case (ComputeOp.Mac(_, x, y, z), t) =>
      writing(m, b, t, z) && !writing(m, b, t, x) && !writing(m, b, t, y)
  }
  private def macStore(b: Vector[Bundle]) =
    b.exists(x => x.compute.isInstanceOf[ComputeOp.Mac] && x.memory.isInstanceOf[MemoryOp.St])
  private def macPortClash(m: MachineDescription, b: Vector[Bundle]) = macs(b).exists {
    case (_, s) =>
      (s + 1 until b.size).exists { t =>
        computeWrite(b(t).compute).exists { case (op, _) =>
          op != "MAC" && s + m.operation("MAC").latency == t + m.operation(op).latency
        }
      }
  }

  test(
    "ISA mvp-1: verifier and reference model agree with MAC, on 3 and 4 read ports, MAC latency 3 and MAC II 2"
  ) {
    mvp1Variants.zipWithIndex.foreach { case ((label, m), k) =>
      val judged = agree(m, 77L + k, 20000, scalar(m, mac = true))
      val legal = judged.filter(_.first.isEmpty)
      val rules = firstRules(judged)
      def where = s"$label: rules hit first ${rules.map { case (r, n) => s"${r.id} $n" }}"
      assert(legal.size > 2000 && legal.count(j => macs(j.bundles).nonEmpty) > 300, where)
      assert(legal.count(j => accumulates(j.bundles)) > 150, where)
      // rs3 is read in the issue cycle like rs1 and rs2 (L3).
      val rs3 = judged.filter(j => rs3Written(m, j.bundles))
      assert(rs3.forall(_.found(Rule.InFlightRead)), where)
      assert(rs3.count(_.first.contains(Rule.InFlightRead)) > 100, where)
      // MAC uses the Compute write-back port at E_(s + L_MAC) (L5).
      val clash = judged.filter(j => macPortClash(m, j.bundles))
      assert(clash.forall(_.found(Rule.WritebackPort)), where)
      assert(clash.count(_.first.contains(Rule.WritebackPort)) > 100, where)
      // MAC and ST read four registers: illegal on three read ports, legal on four (L11).
      val withStore = judged.filter(j => macStore(j.bundles))
      if (m.readPorts == 3) {
        assert(withStore.forall(_.found(Rule.ReadPorts)), where)
        assert(withStore.count(_.first.contains(Rule.ReadPorts)) > 500, where)
      } else {
        assert(judged.forall(!_.found(Rule.ReadPorts)), where)
        assert(withStore.count(_.first.isEmpty) > 100, where)
      }
      // Every rule the model can hit first, and no other: the addresses 0..3 exist (L7), ST has
      // latency 1 (L10), and the generated words decode (L1).
      val expected = Set[Rule](
        Rule.ProgramShape,
        Rule.InFlightRead,
        Rule.OverlappingWrites,
        Rule.WritebackPort,
        Rule.HaltCompletion,
        Rule.UndefinedRead
      ) ++ (if (m.readPorts == 3) Set(Rule.ReadPorts) else Set.empty) ++
        (if (m.operation("MAC").ii > 1) Set(Rule.IssueInterval) else Set.empty)
      assert(rules.keySet == expected && expected.forall(rules(_) > 50), where)
    }
  }

  test(
    "ISA mvp-1: for legal programs with MAC, cycles and commit timing do not depend on input values"
  ) {
    mvp1Variants.zipWithIndex.foreach { case ((label, m), k) =>
      val checked = sameTiming(m, 700L + k, 1000, scalar(m, mac = true))
      assert(checked.count(macs(_).nonEmpty) > 80, label)
    }
  }

  // ISA mvp-2 (#110 stage 2, docs/ISA.md "mvp-2: W").

  /** W-full, W-reg, four read ports, the latency variant and the II variant (tests/machines). */
  private val mvp2Variants: Seq[(String, MachineDescription)] =
    ("machine-mvp2 (config)" -> load(MachineDescription.Mvp2Path)) +:
      Seq("mvp2_mw1", "mvp2_rp4", "mvp2_vlat", "mvp2_vii").map { f =>
        s"$f (tests/machines)" -> load(java.nio.file.Paths.get("tests", "machines", s"$f.json"))
      }

  /** Programs of ISA mvp-2 on r0..r5 (the pairs r0:r1, r2:r3 and r4:r5) and the words 0..5: the
    * scalar operations, VADD, VSUB, VMUL and VMAC (half of them broadcast, a third of the VMACs
    * with rd = rs3), and on W-full LD.W and ST.W, at an odd address two times in five. One wide
    * access in 16 is at 510 or 511, whose second word is 511 or 512 (L7 on 512 words).
    */
  private def wProgram(rng: Random, m: MachineDescription): Vector[Bundle] = {
    def r = rng.nextInt(6)
    def p = 2 * rng.nextInt(3)
    def a = rng.nextInt(6)
    def wide = if (rng.nextInt(16) == 0) 510 + rng.nextInt(2) else rng.nextInt(5)
    def compute: ComputeOp = rng.nextInt(16) match {
      case 0 | 1 | 2 => ComputeOp.Nop
      case 3         => ComputeOp.Add(r, r, r)
      case 4         => ComputeOp.Sub(r, r, r)
      case 5         => ComputeOp.Mul(r, r, r)
      case 6         =>
        val d = r
        ComputeOp.Mac(d, r, r, if (rng.nextInt(3) == 0) d else r)
      case 7 | 8   => ComputeOp.VAdd(p, p, p)
      case 9       => ComputeOp.VSub(p, p, p)
      case 10 | 11 =>
        val b = rng.nextBoolean()
        val d = p
        ComputeOp.VMul(d, if (b) r else p, p, b)
      case 12 | 13 =>
        val b = rng.nextBoolean()
        val d = p
        val x = if (b) r else p
        ComputeOp.VMac(d, x, p, if (rng.nextInt(3) == 0) d else p, b)
      case 14 => ComputeOp.Halt
      case _  => ComputeOp.Nop
    }
    def memory: MemoryOp = rng.nextInt(if (m.hasWideMemory) 6 else 4) match {
      case 0 | 1 => MemoryOp.Nop
      case 2     => MemoryOp.Ld(r, a)
      case 3     => MemoryOp.St(a, r)
      case 4     => MemoryOp.LdW(p, wide)
      case _     => MemoryOp.StW(wide, p)
    }
    val body = Vector.fill(rng.nextInt(8)) {
      val c = compute
      Bundle(c, if (c == ComputeOp.Halt) MemoryOp.Nop else memory)
    }
    rng.nextInt(10) match {
      case 0     => body
      case 1 | 2 => body ++ Vector.fill(rng.nextInt(3))(Bundle.Nop) :+ Bundle(ComputeOp.Halt)
      case _     => body :+ Bundle.Nop :+ Bundle.Nop :+ Bundle(ComputeOp.Halt)
    }
  }

  /** Declares each of r0..r5 and of the words 0..5, 510 and 511 with probability 9/10. */
  private def wInputs(rng: Random): Inputs =
    Inputs(
      (0 until 6).filter(_ => rng.nextInt(10) != 0).map(_ -> (rng.nextLong() & 0xffffffffL)).toMap,
      ((0 until 6) ++ Seq(510, 511))
        .filter(_ => rng.nextInt(10) != 0)
        .map(_ -> (rng.nextLong() & 0xffffffffL))
        .toMap
    )

  private def wGen(m: MachineDescription) = Gen(wProgram(_, m), wInputs)

  /** The operand fields of a bundle, each with the registers it reads. */
  private def fields(b: Bundle): Seq[Seq[Int]] = {
    def pair(p: Int) = Seq(p, p + 1)
    def rs1(x: Int, bc: Boolean) = if (bc) Seq(x) else pair(x)
    val compute = b.compute match {
      case ComputeOp.Add(_, x, y)         => Seq(Seq(x), Seq(y))
      case ComputeOp.Sub(_, x, y)         => Seq(Seq(x), Seq(y))
      case ComputeOp.Mul(_, x, y)         => Seq(Seq(x), Seq(y))
      case ComputeOp.Mac(_, x, y, z)      => Seq(Seq(x), Seq(y), Seq(z))
      case ComputeOp.VAdd(_, x, y)        => Seq(pair(x), pair(y))
      case ComputeOp.VSub(_, x, y)        => Seq(pair(x), pair(y))
      case ComputeOp.VMul(_, x, y, bc)    => Seq(rs1(x, bc), pair(y))
      case ComputeOp.VMac(_, x, y, z, bc) => Seq(rs1(x, bc), pair(y), pair(z))
      case ComputeOp.Nop | ComputeOp.Halt => Nil
    }
    val memory = b.memory match {
      case MemoryOp.St(_, r)  => Seq(Seq(r))
      case MemoryOp.StW(_, r) => Seq(pair(r))
      case _                  => Nil
    }
    compute ++ memory
  }

  private def isVector(op: ComputeOp) = op match {
    case _: ComputeOp.VAdd | _: ComputeOp.VSub | _: ComputeOp.VMul | _: ComputeOp.VMac => true
    case _                                                                             => false
  }

  /** The operations of a program that write registers: (issue, port, latency, registers). */
  private def portUses(m: MachineDescription, b: Vector[Bundle]) =
    b.zipWithIndex.flatMap { case (x, s) =>
      computeWrite(x.compute).map { case (op, rs) =>
        (s, Slot.Compute, m.operation(op).latency, rs)
      } ++
        loadWrite(x.memory).map { case (op, rs) => (s, Slot.Memory, m.operation(op).latency, rs) }
    }

  /** Shapes the W generator must produce, and what each implies. */
  private object Shapes {

    /** A pair field (or ST.W's source) whose second register, and no other register the bundle
      * reads, is being written: L3 applies to each register of a pair.
      */
    def secondLaneWritten(m: MachineDescription, b: Vector[Bundle]) = b.indices.exists { t =>
      val fs = fields(b(t))
      val written = fs.flatten.filter(writing(m, b, t, _)).toSet
      fs.exists(f => f.size == 2 && written == Set(f(1)))
    }

    /** Two operations commit through one port at one boundary, one of them moving a pair. */
    def pairPortClash(m: MachineDescription, b: Vector[Bundle]) = {
      val uses = portUses(m, b)
      uses.exists { case (s, p, l, rs) =>
        rs.size == 2 && uses.exists { case (t, q, k, _) => t != s && q == p && t + k == s + l }
      }
    }

    /** One vector operation or LD.W, so one use of a port for two registers. */
    def pairCommit(m: MachineDescription, b: Vector[Bundle]) = portUses(m, b).exists(_._4.size == 2)

    /** A broadcast rs1 r(x) whose r(x + 1) has no value: a broadcast reads one register. */
    def broadcastAlone(b: Vector[Bundle], in: Inputs) = b.zipWithIndex.exists {
      case (Bundle(ComputeOp.VMul(_, x, _, true), _), t)    => undefined(b, in, t, x + 1)
      case (Bundle(ComputeOp.VMac(_, x, _, _, true), _), t) => undefined(b, in, t, x + 1)
      case _                                                => false
    }
    private def undefined(b: Vector[Bundle], in: Inputs, t: Int, reg: Int) =
      !in.registers.contains(reg) && (0 until t).forall { s =>
        (computeWrite(b(s).compute) ++ loadWrite(b(s).memory)).forall(!_._2.contains(reg))
      }

    /** A register written as half of a pair and, at another time, alone (a partial pair update). */
    def partialUpdate(m: MachineDescription, b: Vector[Bundle]) = {
      val uses = portUses(m, b)
      uses.exists { case (s, _, _, rs) =>
        rs.size == 2 && uses.exists { case (t, _, _, qs) =>
          t != s && qs.size == 1 && rs.contains(qs.head)
        }
      }
    }

    /** VMAC or MAC with ST or ST.W: four fields. */
    def rs3Store(b: Vector[Bundle]) = b.exists {
      case Bundle(_: ComputeOp.Mac | _: ComputeOp.VMac, _: MemoryOp.St | _: MemoryOp.StW) => true
      case _                                                                              => false
    }

    /** LD.W at an odd address: its second word is in the next row of bank 0. */
    def oddWide(b: Vector[Bundle]) = b.exists {
      case Bundle(_, MemoryOp.LdW(_, a)) => a % 2 == 1
      case _                             => false
    }

    /** The words each bundle loads and stores, with the store latency. */
    private def loads(x: Bundle) = x.memory match {
      case MemoryOp.Ld(_, a)  => Seq(a)
      case MemoryOp.LdW(_, a) => Seq(a, a + 1)
      case _                  => Nil
    }
    private def stores(m: MachineDescription, x: Bundle) = x.memory match {
      case MemoryOp.St(a, _)  => Seq(a).map(_ -> m.operation("ST").latency)
      case MemoryOp.StW(a, _) => Seq(a, a + 1).map(_ -> m.operation("ST.W").latency)
      case _                  => Nil
    }

    /** A load of a word, at least one being a word of LD.W or ST.W, inside a store's interval. */
    def wordInFlight(m: MachineDescription, b: Vector[Bundle]) = b.indices.exists { t =>
      val wide =
        (x: Bundle) => x.memory.isInstanceOf[MemoryOp.LdW] || x.memory.isInstanceOf[MemoryOp.StW]
      (0 until t).exists { s =>
        (wide(b(s)) || wide(b(t))) && stores(m, b(s)).exists { case (w, l) =>
          loads(b(t)).contains(w) && t < s + l
        }
      }
    }
  }

  test(
    "ISA mvp-2: verifier and reference model agree on W-full, W-reg, 4 read ports and the variants"
  ) {
    mvp2Variants.zipWithIndex.foreach { case ((label, m), k) =>
      val judged = agree(m, 110L + k, 20000, wGen(m))
      val legal = judged.filter(_.first.isEmpty)
      val rules = firstRules(judged)
      def where = s"$label: rules hit first ${rules.map { case (r, n) => s"${r.id} $n" }}"
      def count(js: Vector[Judged])(p: Vector[Bundle] => Boolean) = js.count(j => p(j.bundles))
      val wide = m.hasWideMemory
      assert(legal.size > 1000, where)
      // One vector operation or LD.W commits two registers through one use of its port (L5).
      assert(count(legal)(_.exists(x => isVector(x.compute))) > 300, where)
      assert(count(legal)(Shapes.pairCommit(m, _)) > 300, where)
      if (wide) assert(count(legal)(_.exists(_.memory.isInstanceOf[MemoryOp.LdW])) > 100, where)
      // Two operations on one port at one boundary, one of them a pair, are L5.
      val clash = judged.filter(j => Shapes.pairPortClash(m, j.bundles))
      assert(clash.forall(_.found(Rule.WritebackPort)), where)
      assert(clash.count(_.first.contains(Rule.WritebackPort)) > 100, where)
      // L3 for the second register of a pair alone.
      val lane = judged.filter(j => Shapes.secondLaneWritten(m, j.bundles))
      assert(lane.forall(_.found(Rule.InFlightRead)), where)
      assert(lane.count(_.first.contains(Rule.InFlightRead)) > 100, where)
      // A broadcast rs1 reads one register; partial pair updates are legal.
      assert(legal.count(j => Shapes.broadcastAlone(j.bundles, j.inputs)) > 30, where)
      assert(count(legal)(Shapes.partialUpdate(m, _)) > 50, where)
      if (wide) assert(count(legal)(Shapes.oddWide) > 100, where)
      // L11 counts fields: VMAC or MAC with ST or ST.W are four.
      val withStore = judged.filter(j => Shapes.rs3Store(j.bundles))
      if (m.readPorts == 3) {
        assert(withStore.forall(_.found(Rule.ReadPorts)), where)
        assert(withStore.count(_.first.contains(Rule.ReadPorts)) > 300, where)
      } else {
        assert(judged.forall(!_.found(Rule.ReadPorts)), where)
        assert(withStore.count(_.first.isEmpty) > 100, where)
      }
      // L10 per word, with ST and ST.W latency 2.
      val inFlight = judged.filter(j => Shapes.wordInFlight(m, j.bundles))
      assert(inFlight.forall(_.found(Rule.InFlightStoreRead)), where)
      if (m.operation("ST").latency > 1)
        assert(inFlight.count(_.first.contains(Rule.InFlightStoreRead)) > 50, where)
      // Every rule the model can hit first, and no other: the words 0..5 exist and word 512 does
      // not (L7, W-full only), L9 needs an II above 1 and L10 a store latency above 1, and the
      // generated words decode (L1).
      val expected = Set[Rule](
        Rule.ProgramShape,
        Rule.InFlightRead,
        Rule.OverlappingWrites,
        Rule.WritebackPort,
        Rule.HaltCompletion,
        Rule.UndefinedRead
      ) ++ (if (m.readPorts == 3) Set(Rule.ReadPorts) else Set.empty) ++
        (if (wide) Set(Rule.AddressRange) else Set.empty) ++
        (if (m.operation("VMUL").ii > 1) Set(Rule.IssueInterval) else Set.empty) ++
        (if (m.operation("ST").latency > 1) Set(Rule.InFlightStoreRead) else Set.empty)
      assert(rules.keySet == expected && expected.forall(rules(_) > 50), where)
    }
  }

  test(
    "ISA mvp-2: for legal programs with W, cycles and commit timing do not depend on input values"
  ) {
    mvp2Variants.zipWithIndex.foreach { case ((label, m), k) =>
      val checked = sameTiming(m, 1100L + k, 1000, wGen(m))
      assert(checked.count(_.exists(x => isVector(x.compute))) > 200, label)
      if (m.hasWideMemory)
        assert(checked.count(_.exists(_.memory.isInstanceOf[MemoryOp.LdW])) > 50, label)
    }
  }
}

object DifferentialSpec {

  /** A random program, its declared inputs, the rules the verifier finds, and the rule the model
    * stops at (None: legal).
    */
  final case class Judged(
      bundles: Vector[Bundle],
      inputs: Inputs,
      found: Set[Rule],
      first: Option[Rule]
  )

  /** How random programs and their declared inputs are drawn, in this order, from one generator. */
  final case class Gen(program: Random => Vector[Bundle], inputs: Random => Inputs)
}
