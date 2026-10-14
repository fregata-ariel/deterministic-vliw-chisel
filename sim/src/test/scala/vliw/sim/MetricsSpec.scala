package vliw.sim

import java.nio.file.Paths

import org.scalatest.funsuite.AnyFunSuite

import vliw.isa._

class MetricsSpec extends AnyFunSuite {
  private def record(c: TestCase): ujson.Value = {
    val run = ReferenceModel.run(c.machine, c.words, c.inputs).fold(v => fail(v.toString), identity)
    Metrics.of(c.machine, c.words, run, c.name)
  }

  private val isaCase =
    TestCase
      .load(Paths.get("tests", "cases", "isa_example.json"))
      .fold(es => fail(es.mkString), identity)

  test("ISA example: every count matches values derived by hand from docs/ISA.md") {
    val m = record(isaCase)
    assert(m("metrics").str == "vliw-metrics/1" && m("isa").str == "mvp-0")
    assert(m("machine")("hash").str == isaCase.machine.hash)
    assert(m("program")("bundles").num == 7 && m("cycles").num == 7)
    assert(m("program")("hash").str == Json.sha256(ProgramFile.toHex(isaCase.words)))
    // Compute slot: MUL and ADD; HALT is counted separately; 4 NOPs out of 7 bundles.
    assert(m("issue")("compute")("operations").num == 2 && m("issue")("compute")("nop").num == 4)
    assert(m("issue")("compute")("nop_rate").num == 0.571429)
    // Memory slot: LD and ST; 5 NOPs.
    assert(m("issue")("memory")("operations").num == 2 && m("issue")("memory")("nop").num == 5)
    val ops = m("issue")("by_operation").obj.view.mapValues(_.num.toInt).toMap
    assert(ops == Map("ADD" -> 1, "SUB" -> 0, "MUL" -> 1, "LD" -> 1, "ST" -> 1, "HALT" -> 1))
    // Write-back: r3 and r4 through the compute port, r1 through the memory port.
    assert(
      m("writeback")("compute")("commits").num == 2 && m("writeback")("memory")("commits").num == 1
    )
    // LD [0] reads bank 0, ST [1] writes bank 1.
    val banks =
      m("scratchpad")("banks").arr.map(b => (b("bank").num, b("reads").num, b("writes").num))
    assert(banks == Seq((0, 1, 0), (1, 0, 1)))
    assert(m("scratchpad")("accesses_per_cycle").num == 0.285714)
    assert(m("ii").isNull && m("rtl").isNull)
  }

  test("for every legal case, the counts are consistent with the program") {
    TestCase
      .loadAll()
      .fold(es => fail(es.mkString), identity)
      .filter(_.expect.verdict == Verdict.Legal)
      .foreach { c =>
        val m = record(c)
        val bundles = new BundleCodec(c.machine).decodeAll(c.words).toOption.get
        val ops = m("issue")("by_operation")
        // Every issued operation completes before done, so each one commits exactly once.
        assert(
          m("writeback")("compute")("commits").num == ops("ADD").num + ops("SUB").num + ops(
            "MUL"
          ).num,
          c.name
        )
        assert(m("writeback")("memory")("commits").num == ops("LD").num, c.name)
        assert(m("scratchpad")("writes").num == ops("ST").num, c.name)
        assert(m("scratchpad")("reads").num == ops("LD").num, c.name)
        assert(m("cycles").num == bundles.size, c.name)
        val compute = m("issue")("compute")
        assert(
          compute("operations").num + compute("nop").num + ops("HALT").num == bundles.size,
          c.name
        )
        val perBank = m("scratchpad")("banks").arr
        assert(perBank.size == c.machine.banks, c.name)
        assert(
          perBank.map(b => b("reads").num + b("writes").num).sum == ops("LD").num + ops("ST").num,
          c.name
        )
      }
  }

  test("the record is valid JSON with integer counts and does not depend on input values") {
    val other = isaCase.copy(inputs = Inputs(Map(2 -> 123456789L), Map(0 -> 0xffffffffL)))
    val a = record(isaCase)
    val b = record(other)
    assert(ujson.read(ujson.write(a)) == a)
    assert(a == b, "metrics of a fixed schedule must not change with the data")
  }
}
