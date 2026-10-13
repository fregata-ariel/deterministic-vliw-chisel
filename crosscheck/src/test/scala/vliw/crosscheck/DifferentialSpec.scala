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

  private def inputs(rng: Random): Inputs = Inputs(
    (0 until 4).filter(_ => rng.nextInt(3) != 0).map(_ -> (rng.nextLong() & 0xffffffffL)).toMap,
    (0 until 4).filter(_ => rng.nextInt(3) != 0).map(_ -> (rng.nextLong() & 0xffffffffL)).toMap
  )

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
