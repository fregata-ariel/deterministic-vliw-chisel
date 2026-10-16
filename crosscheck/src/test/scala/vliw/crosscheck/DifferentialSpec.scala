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
  import DifferentialSpec.Judged

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

  /** Judges `count` random programs on machine `m` with both implementations: they give the same
    * verdict, a legal program runs `bundles.size` cycles, and the model's first violation is among
    * the verifier's findings.
    */
  private def agree(
      m: MachineDescription,
      seed: Long,
      count: Int,
      mac: Boolean = false
  ): Vector[Judged] = {
    val rng = new Random(seed)
    val c = new BundleCodec(m)
    Vector.tabulate(count) { i =>
      val bundles = program(rng, mac)
      val in = inputs(rng, m, leftOut(mac))
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
      Judged(bundles, findings.map(_.rule).toSet, result.left.toOption.map(_.rule))
    }
  }

  /** How often the model stops at each rule first. */
  private def firstRules(judged: Vector[Judged]): Map[Rule, Int] =
    judged.flatMap(_.first).groupMapReduce(identity)(_ => 1)(_ + _)

  test(
    "verifier and reference model agree under machine descriptions with other latencies and capacity"
  ) {
    variants.foreach { case (label, m) =>
      val judged = agree(m, 99L, 8000)
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
      mac: Boolean = false
  ): Vector[Vector[Bundle]] = {
    val rng = new Random(seed)
    val encoder = new BundleCodec(m)
    val checked = Vector.newBuilder[Vector[Bundle]]
    var n = 0
    while (n < count) {
      val bundles = program(rng, mac)
      val in = inputs(rng, m, leftOut(mac))
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
    sameTiming(machine, 7L, 2000)
  }

  // ISA mvp-1 (#77, docs/ISA.md "mvp-1: MAC").

  private def macs(b: Vector[Bundle]): Vector[(ComputeOp.Mac, Int)] =
    b.zipWithIndex.collect { case (Bundle(op: ComputeOp.Mac, _), s) => (op, s) }

  /** The register that a Compute operation writes, and its mnemonic. */
  private def computeWrite(op: ComputeOp): Option[(Int, String)] = op match {
    case ComputeOp.Add(d, _, _)         => Some(d -> "ADD")
    case ComputeOp.Sub(d, _, _)         => Some(d -> "SUB")
    case ComputeOp.Mul(d, _, _)         => Some(d -> "MUL")
    case ComputeOp.Mac(d, _, _, _)      => Some(d -> "MAC")
    case ComputeOp.Nop | ComputeOp.Halt => None
  }

  /** Whether an operation issued before cycle t writes `reg` and its value is not yet visible at t.
    */
  private def writing(m: MachineDescription, b: Vector[Bundle], t: Int, reg: Int): Boolean =
    (0 until t).exists { s =>
      val ld = b(s).memory match {
        case MemoryOp.Ld(d, _)                => Some(d -> "LD")
        case MemoryOp.St(_, _) | MemoryOp.Nop => None
      }
      (computeWrite(b(s).compute) ++ ld).exists { case (r, op) =>
        r == reg && t < s + m.operation(op).latency
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
        computeWrite(b(t).compute).exists { case (_, op) =>
          op != "MAC" && s + m.operation("MAC").latency == t + m.operation(op).latency
        }
      }
  }

  test(
    "ISA mvp-1: verifier and reference model agree with MAC, on 3 and 4 read ports, MAC latency 3 and MAC II 2"
  ) {
    mvp1Variants.zipWithIndex.foreach { case ((label, m), k) =>
      val judged = agree(m, 77L + k, 20000, mac = true)
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
      val checked = sameTiming(m, 700L + k, 1000, mac = true)
      assert(checked.count(macs(_).nonEmpty) > 80, label)
    }
  }
}

object DifferentialSpec {

  /** A random program, the rules the verifier finds, and the rule the model stops at (None: legal).
    */
  final case class Judged(bundles: Vector[Bundle], found: Set[Rule], first: Option[Rule])
}
