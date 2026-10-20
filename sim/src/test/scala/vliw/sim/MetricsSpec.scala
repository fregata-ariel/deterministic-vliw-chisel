package vliw.sim

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import scala.jdk.CollectionConverters._

import org.scalatest.funsuite.AnyFunSuite

import vliw.isa._

class MetricsSpec extends AnyFunSuite {
  private def record(c: TestCase): ujson.Value = {
    val run = ReferenceModel.run(c.machine, c.words, c.inputs).fold(v => fail(v.toString), identity)
    Metrics.of(c.machine, c.words, run, c.name)
  }

  private def recordOf(
      m: MachineDescription,
      src: String,
      regs: Map[Int, Long],
      mem: Map[Int, Long] = Map.empty
  ): ujson.Value = {
    val words = Assembler
      .assemble(src, m)
      .flatMap(p => new BundleCodec(m).encodeAll(p.bundles).left.map(List(_)))
      .fold(es => fail(es.mkString("\n")), identity)
    val run = ReferenceModel.run(m, words, Inputs(regs, mem)).fold(v => fail(v.toString), identity)
    Metrics.of(m, words, run, "hand")
  }

  /** Register reads of a bundle, counted by operand field as docs/ISA.md rule L11 counts them. */
  private def readFields(b: Bundle): Int = {
    val compute = b.compute match {
      case _: ComputeOp.Add | _: ComputeOp.Sub | _: ComputeOp.Mul                               => 2
      case _: ComputeOp.Mac                                                                     => 3
      case ComputeOp.Nop | ComputeOp.Halt                                                       => 0
      case op @ (_: ComputeOp.VAdd | _: ComputeOp.VSub | _: ComputeOp.VMul | _: ComputeOp.VMac) =>
        fail(s"$op: the metrics of ISA mvp-2 come with #110 stage 2")
    }
    compute + (if (b.memory.isInstanceOf[MemoryOp.St]) 1 else 0)
  }

  /** Every key path of a record, in the order they are written; arrays are not entered. */
  private def paths(v: ujson.Value, prefix: String = ""): Seq[String] = v match {
    case o: ujson.Obj =>
      o.value.toSeq.flatMap { case (k, x) => (prefix + k) +: paths(x, s"$prefix$k.") }
    case _ => Nil
  }

  private val isaCase =
    TestCase
      .load(Paths.get("tests", "cases", "isa_example.json"))
      .fold(es => fail(es.mkString), identity)

  private val mvp1 = MachineDescription
    .load(MachineDescription.Mvp1Path)
    .fold(es => fail(es.mkString), identity)

  // Reads by operand field: 3 (MAC and LD), 0, 3 (MAC), 1 (ST), 0 (HALT).
  private def mvp1Record = recordOf(
    mvp1,
    "mac r4, r1, r2, r3 | ld r5, [0]\nnop\nmac r6, r4, r5, r4\nnop | st [1], r4\nhalt",
    Map(1 -> 2L, 2 -> 3L, 3 -> 4L),
    Map(0 -> 7L)
  )

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

  test("mvp-1: MAC, register reads and MAC with ST match values derived by hand") {
    val m = mvp1Record
    assert(m("metrics").str == "vliw-metrics/2" && m("isa").str == "mvp-1")
    assert(m("machine")("hash").str == mvp1.hash)
    assert(m("program")("bundles").num == 5 && m("cycles").num == 5)
    // Compute slot: 2 MACs, HALT, 2 NOPs. Memory slot: LD and ST, 3 NOPs.
    val issue = m("issue")
    assert(issue("compute")("operations").num == 2 && issue("compute")("nop").num == 2)
    assert(issue("compute")("nop_rate").num == 0.4)
    assert(issue("memory")("operations").num == 2 && issue("memory")("nop").num == 3)
    val ops = issue("by_operation").obj.view.mapValues(_.num.toInt).toMap
    assert(
      ops == Map("ADD" -> 0, "SUB" -> 0, "MUL" -> 0, "MAC" -> 2, "LD" -> 1, "ST" -> 1, "HALT" -> 1)
    )
    val reads = issue("register_reads")
    assert(reads("read_ports").num == 3 && reads("reads").num == 7)
    val byReads = reads("bundles_by_reads").obj.toSeq.map { case (k, n) => (k, n.num) }
    assert(byReads == Seq("0" -> 2.0, "1" -> 1.0, "2" -> 0.0, "3" -> 2.0))
    assert(issue("mac_with_st").num == 0)
    // Write-back: r4 and r6 through the compute port, r5 through the memory port.
    assert(m("writeback")("compute")("commits").num == 2)
    assert(m("writeback")("compute")("utilization").num == 0.4)
    assert(m("writeback")("memory")("commits").num == 1)
    // LD [0] reads bank 0, ST [1] writes bank 1.
    assert(m("scratchpad")("reads").num == 1 && m("scratchpad")("writes").num == 1)

    // With 4 read ports, MAC and ST share a bundle: reads 4 (MAC and ST), 1 (ST), 0 (HALT).
    val rp4 = MachineDescription
      .load(Paths.get("tests", "machines", "mvp1_rp4.json"))
      .fold(es => fail(es.mkString), identity)
    val w = recordOf(
      rp4,
      "mac r4, r1, r2, r3 | st [0], r5\nnop | st [1], r1\nhalt",
      Map(1 -> 2L, 2 -> 3L, 3 -> 4L, 5 -> 9L)
    )
    val wide = w("issue")("register_reads")
    assert(wide("read_ports").num == 4 && wide("reads").num == 5)
    val wideByReads = wide("bundles_by_reads").obj.toSeq.map { case (k, n) => (k, n.num) }
    assert(wideByReads == Seq("0" -> 1.0, "1" -> 1.0, "2" -> 0.0, "3" -> 0.0, "4" -> 1.0))
    assert(w("issue")("mac_with_st").num == 1)
  }

  test("vliw-metrics/2 has the keys of vliw-metrics/1, plus MAC and the read-port counts") {
    val added = Seq(
      "issue.register_reads",
      "issue.register_reads.read_ports",
      "issue.register_reads.reads",
      "issue.register_reads.bundles_by_reads"
    ) ++ (0 to 3).map(k => s"issue.register_reads.bundles_by_reads.$k") :+ "issue.mac_with_st"
    val expected = paths(record(isaCase)).flatMap {
      case p @ "issue.by_operation.MUL"  => Seq(p, "issue.by_operation.MAC")
      case p @ "issue.by_operation.HALT" => p +: added
      case p                             => Seq(p)
    }
    assert(paths(mvp1Record) == expected)
  }

  test("for every legal case, the counts are consistent with the program") {
    TestCase
      .loadAll()
      .fold(es => fail(es.mkString), identity)
      .filter(_.expect.verdict == Verdict.Legal)
      .foreach { c =>
        val m = record(c)
        val bundles = new BundleCodec(c.machine).decodeAll(c.words).toOption.get
        val issue = m("issue")
        val ops = issue("by_operation")
        val macs = ops.obj.get("MAC").fold(0.0)(_.num)
        // Every issued operation completes before done, so each one commits exactly once.
        assert(
          m("writeback")("compute")("commits").num ==
            ops("ADD").num + ops("SUB").num + ops("MUL").num + macs,
          c.name
        )
        assert(m("writeback")("memory")("commits").num == ops("LD").num, c.name)
        assert(m("scratchpad")("writes").num == ops("ST").num, c.name)
        assert(m("scratchpad")("reads").num == ops("LD").num, c.name)
        assert(m("cycles").num == bundles.size, c.name)
        val compute = issue("compute")
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
        if (c.machine.isaVersion == IsaVersion.Mvp1) {
          assert(m("metrics").str == "vliw-metrics/2", c.name)
          val reads = issue("register_reads")
          val byReads = reads("bundles_by_reads").obj.toSeq.map { case (k, n) => (k, n.num) }
          assert(
            byReads == (0 to c.machine.readPorts)
              .map(k => k.toString -> bundles.count(readFields(_) == k).toDouble),
            c.name
          )
          assert(reads("reads").num == bundles.map(readFields).sum, c.name)
          val macWithSt = bundles
            .count(b => b.compute.isInstanceOf[ComputeOp.Mac] && b.memory.isInstanceOf[MemoryOp.St])
          assert(issue("mac_with_st").num == macWithSt, c.name)
        } else {
          assert(m("metrics").str == "vliw-metrics/1", c.name)
          assert(!ops.obj.contains("MAC") && !issue.obj.contains("register_reads"), c.name)
          assert(!issue.obj.contains("mac_with_st"), c.name)
        }
      }
  }

  test("the mvp-0 records in benchmarks/ (vliw-metrics/1) are reproduced byte for byte") {
    val dir = Paths.get("benchmarks", "metrics")
    val records = Files
      .list(dir)
      .iterator()
      .asScala
      .filter(_.toString.endsWith(".json"))
      .map(f => (f, new String(Files.readAllBytes(f), StandardCharsets.UTF_8)))
      .filter { case (_, text) => ujson.read(text)("isa").str == IsaVersion.Mvp0 }
      .toVector
    assert(records.nonEmpty)
    records.foreach { case (f, text) =>
      val stored = ujson.read(text)
      val c = TestCase
        .load(Paths.get("benchmarks", "cases", f.getFileName.toString))
        .fold(es => fail(es.mkString), identity)
      val run =
        ReferenceModel.run(c.machine, c.words, c.inputs).fold(v => fail(v.toString), identity)
      val ii = if (stored("ii").isNull) None else Some(stored("ii").num.toInt)
      val m = Metrics.of(c.machine, c.words, run, stored("program")("name").str, ii)
      assert(m("metrics").str == "vliw-metrics/1", f.toString)
      // The generator section depends on the JVM that ran it.
      m("generator") = stored("generator")
      assert(ujson.write(m, indent = 2) + "\n" == text, f.toString)
    }
  }

  test("the record is valid JSON with integer counts and does not depend on input values") {
    val other = isaCase.copy(inputs = Inputs(Map(2 -> 123456789L), Map(0 -> 0xffffffffL)))
    val a = record(isaCase)
    val b = record(other)
    assert(ujson.read(ujson.write(a)) == a)
    assert(a == b, "metrics of a fixed schedule must not change with the data")
  }

  test("ISA mvp-2 is refused until #110 stage 2 (vliw-metrics/3), not written as /1") {
    val mvp2 = MachineDescription
      .load(MachineDescription.Mvp2Path)
      .fold(es => fail(es.mkString), identity)
    val run = ReferenceModel.run(isaCase.machine, isaCase.words, isaCase.inputs).toOption.get
    val expected = "ISA mvp-2 is not implemented in the metrics yet (#110 stage 2)"
    assert(
      intercept[UnsupportedOperationException](Metrics.format(mvp2)).getMessage.startsWith(expected)
    )
    assert(
      intercept[UnsupportedOperationException](Metrics.of(mvp2, isaCase.words, run, "w")).getMessage
        .startsWith(expected)
    )
    assert(Metrics.format(mvp1) == "vliw-metrics/2")
    assert(Metrics.format(isaCase.machine) == "vliw-metrics/1")
  }
}
