package vliw.crosscheck

import scala.util.Random

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.Verifier
import vliw.hw.{Core, CoreDriver, CoreParams}
import vliw.isa._
import vliw.sim.ReferenceModel

/** Random legal programs on the RTL and on the reference model: the traces must be identical cycle
  * by cycle, and so must every location that the program defines.
  */
class RtlDifferentialSpec extends AnyFunSuite with ChiselSim {
  private val machine = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)
  private val codec = new BundleCodec(machine)

  /** Dense hazards over r0..r3 and addresses 0..3, HALT last; most programs are illegal. */
  private def program(rng: Random): Vector[Bundle] = {
    def r = rng.nextInt(4)
    def compute: ComputeOp = rng.nextInt(8) match {
      case 0 | 1 => ComputeOp.Nop
      case 2 | 3 => ComputeOp.Add(r, r, r)
      case 4 | 5 => ComputeOp.Sub(r, r, r)
      case _     => ComputeOp.Mul(r, r, r)
    }
    def memory: MemoryOp = rng.nextInt(4) match {
      case 0 | 1 => MemoryOp.Nop
      case 2     => MemoryOp.Ld(r, rng.nextInt(4))
      case _     => MemoryOp.St(rng.nextInt(4), r)
    }
    Vector.fill(1 + rng.nextInt(10))(Bundle(compute, memory)) ++ Vector(
      Bundle.Nop,
      Bundle.Nop,
      Bundle(ComputeOp.Halt)
    )
  }

  private def inputs(rng: Random) = Inputs(
    (0 until 4).map(_ -> (rng.nextLong() & 0xffffffffL)).toMap,
    (0 until 4).map(_ -> (rng.nextLong() & 0xffffffffL)).toMap
  )

  private def canonical(v: ujson.Value): String =
    Json.canonical(v).fold(e => fail(e.message), identity)

  private val legalPrograms: Vector[(Vector[Long], Inputs)] = {
    val rng = new Random(314159L)
    Iterator
      .continually((program(rng), inputs(rng)))
      .map { case (b, in) => (codec.encodeAll(b).fold(e => fail(e), identity), in) }
      .filter { case (w, in) => Verifier.verify(machine, w, Verifier.Declaration.of(in)).isEmpty }
      .take(400)
      .toVector
  }

  test(
    "RTL and reference model produce identical traces and final states for random legal programs"
  ) {
    simulate(new Core(CoreParams(machine))) { dut =>
      legalPrograms.zipWithIndex.foreach { case ((words, in), i) =>
        val model = ReferenceModel.run(machine, words, in).fold(v => fail(s"#$i: $v"), identity)
        val rtl = CoreDriver.run(dut, machine, words, in, (0 until 4).toSet)
        val listing = Disassembler.program(codec.decodeAll(words).toOption.get)
        assert(rtl.violations.isEmpty, s"#$i\n$listing")
        assert(rtl.cycles == model.cycles, s"#$i\n$listing")
        val expected = model.trace.cycles.map(c => canonical(c.toJson))
        val actual = rtl.trace.map(canonical)
        expected.zip(actual).zipWithIndex.find { case ((e, a), _) => e != a }.foreach {
          case ((e, a), line) =>
            fail(s"#$i differs at trace line $line\n  model: $e\n  rtl:   $a\n$listing")
        }
        assert(actual.size == expected.size, s"#$i trace length\n$listing")
        model.registers.zipWithIndex.collect { case (Some(v), reg) =>
          assert(rtl.registers(reg) == BigInt(v), s"#$i r$reg\n$listing")
        }
        model.memory.zipWithIndex.collect { case (Some(v), a) =>
          assert(rtl.memory(a) == BigInt(v), s"#$i [$a]\n$listing")
        }
      }
    }
  }

  test("on the RTL, cycles and commit timing do not depend on input values") {
    val rng = new Random(2718L)
    def timing(t: Vector[ujson.Obj]) = t.map { line =>
      val l = ujson.read(ujson.write(line))
      l("rf_commit").arr.foreach(_.obj.remove("value"))
      l("mem_commit").arr.foreach(_.obj.remove("value"))
      canonical(l)
    }
    simulate(new Core(CoreParams(machine))) { dut =>
      legalPrograms.take(60).foreach { case (words, in) =>
        val reference = CoreDriver.run(dut, machine, words, in, Set.empty)
        (0 until 3).foreach { _ =>
          val other = Inputs(
            in.registers.map { case (k, _) => k -> (rng.nextLong() & 0xffffffffL) },
            in.memory.map { case (k, _) =>
              k -> (rng.nextLong() & 0xffffffffL)
            }
          )
          val run = CoreDriver.run(dut, machine, words, other, Set.empty)
          assert(run.cycles == reference.cycles)
          assert(timing(run.trace) == timing(reference.trace))
        }
      }
    }
  }
}
