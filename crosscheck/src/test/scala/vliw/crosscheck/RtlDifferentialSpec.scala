package vliw.crosscheck

import scala.util.Random

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.Verifier
import vliw.hw.{Core, CoreDriver, CoreParams, SeededChiselSim}
import vliw.isa._
import vliw.sim.ReferenceModel

/** Random legal programs on the RTL and on the reference model: the traces must be identical cycle
  * by cycle, and so must every location that the program defines.
  */
class RtlDifferentialSpec extends AnyFunSuite with SeededChiselSim {
  private val machine = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)

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

  /** `count` random programs that the verifier accepts on `m`, with their inputs. */
  private def legalPrograms(m: MachineDescription, seed: Long, count: Int) = {
    val rng = new Random(seed)
    val c = new BundleCodec(m)
    Iterator
      .continually((program(rng), inputs(rng)))
      .map { case (b, in) => (c.encodeAll(b).fold(e => fail(e), identity), in) }
      .filter { case (w, in) => Verifier.verify(m, w, Verifier.Declaration.of(in)).isEmpty }
      .take(count)
      .toVector
  }
  private val legalOnDefault = legalPrograms(machine, 314159L, 400)

  /** Runs every program on one RTL core for `m` and on the reference model, and compares. */
  private def compare(m: MachineDescription, programs: Vector[(Vector[Long], Inputs)]): Unit =
    simulate(new Core(CoreParams(m))) { dut =>
      val c = new BundleCodec(m)
      programs.zipWithIndex.foreach { case ((words, in), i) =>
        val model = ReferenceModel.run(m, words, in).fold(v => fail(s"#$i: $v"), identity)
        val rtl = CoreDriver.run(dut, m, words, in, (0 until 4).toSet)
        val listing =
          s"${m.machineId} #$i\n" + Disassembler.program(c.decodeAll(words).toOption.get)
        assert(rtl.violations.isEmpty, listing)
        assert(rtl.cycles == model.cycles, listing)
        val expected = model.trace.cycles.map(c => canonical(c.toJson))
        val actual = rtl.trace.map(canonical)
        expected.zip(actual).zipWithIndex.find { case ((e, a), _) => e != a }.foreach {
          case ((e, a), line) =>
            fail(s"differs at trace line $line\n  model: $e\n  rtl:   $a\n$listing")
        }
        assert(actual.size == expected.size, s"trace length\n$listing")
        model.registers.zipWithIndex.collect { case (Some(v), reg) =>
          assert(rtl.registers(reg) == BigInt(v), s"r$reg\n$listing")
        }
        model.memory.zipWithIndex.collect { case (Some(v), a) =>
          assert(rtl.memory(a) == BigInt(v), s"[$a]\n$listing")
        }
      }
    }

  test(
    "RTL and reference model produce identical traces and final states for random legal programs"
  ) {
    compare(machine, legalOnDefault)
  }

  /** Other configurations within what the RTL implements (docs/MACHINE.md). */
  private def variant(edit: ujson.Value => Unit): MachineDescription = {
    val v = ujson.read(machine.canonicalJson)
    edit(v)
    MachineDescription.parse(ujson.write(v)).fold(es => fail(es.mkString("\n")), identity)
  }
  private val variants = Seq(
    "add L=2, sub L=3, mul L=4, ld L=3, 4 banks x 64 words" -> variant { v =>
      v("operations")("ADD")("latency") = 2
      v("operations")("SUB")("latency") = 3
      v("operations")("MUL")("latency") = 4
      v("operations")("LD")("latency") = 3
      v("scratchpad")("banks") = 4
      v("scratchpad")("words_per_bank") = 64
      v("scratchpad")("bank_mapping") = "word_address_mod_4"
      v("scratchpad")("row_mapping") = "word_address_div_4"
    },
    "mul L=1, 8 registers, 1 bank x 4 words" -> variant { v =>
      v("operations")("MUL")("latency") = 1
      v("register_file")("count") = 8
      v("scratchpad")("banks") = 1
      v("scratchpad")("words_per_bank") = 4
      v("scratchpad")("bank_mapping") = "word_address_mod_1"
      v("scratchpad")("row_mapping") = "word_address_div_1"
    }
  )

  variants.zipWithIndex.foreach { case ((label, m), k) =>
    test(s"RTL and reference model agree on another configuration the RTL implements: $label") {
      assert(CoreParams.check(m).isRight)
      compare(m, legalPrograms(m, 1000L + k, 150))
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
      legalOnDefault.take(60).foreach { case (words, in) =>
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
