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
  private val machine = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)
  private val codec = new BundleCodec(machine)

  /** Machine descriptions that differ in latencies and capacity (II stays 1, see #35). */
  private def variant(edit: ujson.Value => Unit): MachineDescription = {
    val v = ujson.read(machine.canonicalJson)
    edit(v)
    MachineDescription.parse(ujson.write(v)).fold(es => fail(es.mkString("\n")), identity)
  }
  private val variants: Seq[(String, MachineDescription)] = Seq(
    "slow_units (tests/machines)" -> MachineDescription
      .load(java.nio.file.Paths.get("tests", "machines", "slow_units.json"))
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

  /** Few registers and addresses so that hazards are frequent; HALT usually last. */
  private def program(rng: Random): Vector[Bundle] = {
    def r = rng.nextInt(4)
    def a = rng.nextInt(4)
    def compute: ComputeOp = rng.nextInt(10) match {
      case 0 | 1 | 2 => ComputeOp.Nop
      case 3 | 4     => ComputeOp.Add(r, r, r)
      case 5 | 6     => ComputeOp.Sub(r, r, r)
      case 7 | 8     => ComputeOp.Mul(r, r, r)
      case _         => ComputeOp.Halt
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

  /** Declares a random subset of r0..r3 and of the addresses 0..3 that exist on `m`. */
  private def inputs(rng: Random, m: MachineDescription = machine): Inputs = Inputs(
    (0 until 4).filter(_ => rng.nextInt(3) != 0).map(_ -> (rng.nextLong() & 0xffffffffL)).toMap,
    (0 until 4.min(m.scratchpadWords))
      .filter(_ => rng.nextInt(3) != 0)
      .map(_ -> (rng.nextLong() & 0xffffffffL))
      .toMap
  )

  /** Judges `count` random programs on machine `m` with both implementations. */
  private def agree(m: MachineDescription, seed: Long, count: Int): (Int, Map[Rule, Int]) = {
    val rng = new Random(seed)
    val c = new BundleCodec(m)
    var legal = 0
    val firstRule = collection.mutable.Map.empty[Rule, Int].withDefaultValue(0)
    (0 until count).foreach { i =>
      val bundles = program(rng)
      val in = inputs(rng, m)
      val words = c.encodeAll(bundles).fold(e => fail(e), identity)
      val findings = Verifier.verify(m, words, Verifier.Declaration.of(in))
      val result = ReferenceModel.run(m, words, in)
      def show = s"${m.machineId} #$i\n" + Disassembler.program(bundles) +
        findings.mkString("verifier:\n  ", "\n  ", "\n") + s"model: $result"
      (findings.isEmpty, result) match {
        case (true, Right(run)) => legal += 1; assert(run.cycles == bundles.size, show)
        case (false, Left(v))   =>
          assert(findings.map(_.rule).contains(v.rule), show); firstRule(v.rule) += 1
        case _ => fail(s"verdicts differ\n$show")
      }
    }
    (legal, firstRule.toMap)
  }

  test(
    "verifier and reference model agree under machine descriptions with other latencies and capacity"
  ) {
    variants.foreach { case (label, m) =>
      val (legal, rules) = agree(m, 99L, 8000)
      assert(legal > 100, s"$label: only $legal legal programs")
      // With every latency 1 nothing is ever in flight, so L3, L5 and L6 cannot occur there.
      val expected =
        if (label.startsWith("all units L=1"))
          Set[Rule](Rule.ProgramShape, Rule.AddressRange, Rule.UndefinedRead)
        else
          Set[Rule](
            Rule.ProgramShape,
            Rule.InFlightRead,
            Rule.OverlappingWrites,
            Rule.WritebackPort
          )
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

  test("for legal programs, cycles and commit timing do not depend on input values") {
    val rng = new Random(7L)
    var checked = 0
    while (checked < 2000) {
      val bundles = program(rng)
      val in = inputs(rng)
      val words = codec.encodeAll(bundles).toOption.get
      if (Verifier.verify(machine, words, Verifier.Declaration.of(in)).isEmpty) {
        def timing(i: Inputs) = ReferenceModel.run(machine, words, i).map { r =>
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
          assert(timing(other) == reference, Disassembler.program(bundles))
        }
        checked += 1
      }
    }
  }
}
