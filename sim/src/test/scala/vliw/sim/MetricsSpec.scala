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
      case _: ComputeOp.Add | _: ComputeOp.Sub | _: ComputeOp.Mul    => 2
      case _: ComputeOp.VAdd | _: ComputeOp.VSub | _: ComputeOp.VMul => 2
      case _: ComputeOp.Mac | _: ComputeOp.VMac                      => 3
      case ComputeOp.Nop | ComputeOp.Halt                            => 0
    }
    val memory = b.memory match {
      case _: MemoryOp.St | _: MemoryOp.StW => 1
      case _                                => 0
    }
    compute + memory
  }

  /** The 32-bit registers a bundle reads, one entry per field and register (ISA mvp-2: a pair field
    * reads r(p) and r(p+1), a broadcast rs1 one register).
    */
  private def readRegisters(b: Bundle): Seq[Int] = {
    def pair(p: Int) = Seq(p, p + 1)
    val compute = b.compute match {
      case ComputeOp.Add(_, x, y)         => Seq(x, y)
      case ComputeOp.Sub(_, x, y)         => Seq(x, y)
      case ComputeOp.Mul(_, x, y)         => Seq(x, y)
      case ComputeOp.Mac(_, x, y, z)      => Seq(x, y, z)
      case ComputeOp.VAdd(_, x, y)        => pair(x) ++ pair(y)
      case ComputeOp.VSub(_, x, y)        => pair(x) ++ pair(y)
      case ComputeOp.VMul(_, x, y, bc)    => (if (bc) Seq(x) else pair(x)) ++ pair(y)
      case ComputeOp.VMac(_, x, y, z, bc) => (if (bc) Seq(x) else pair(x)) ++ pair(y) ++ pair(z)
      case ComputeOp.Nop | ComputeOp.Halt => Nil
    }
    val memory = b.memory match {
      case MemoryOp.St(_, r)  => Seq(r)
      case MemoryOp.StW(_, r) => pair(r)
      case _                  => Nil
    }
    compute ++ memory
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
        def n(op: String) = ops.obj.get(op).fold(0.0)(_.num)
        // Every issued operation completes before done, so each one commits exactly once. A vector
        // operation or LD.W commits two registers in one use of its port; ST.W writes two words.
        val scalar = n("ADD") + n("SUB") + n("MUL") + n("MAC")
        val vector = n("VADD") + n("VSUB") + n("VMUL") + n("VMAC")
        assert(m("writeback")("compute")("commits").num == scalar + vector, c.name)
        assert(m("writeback")("memory")("commits").num == n("LD") + n("LD.W"), c.name)
        assert(m("scratchpad")("writes").num == n("ST") + 2 * n("ST.W"), c.name)
        assert(m("scratchpad")("reads").num == n("LD") + 2 * n("LD.W"), c.name)
        assert(m("cycles").num == bundles.size, c.name)
        val compute = issue("compute")
        assert(
          compute("operations").num + compute("nop").num + ops("HALT").num == bundles.size,
          c.name
        )
        val perBank = m("scratchpad")("banks").arr
        assert(perBank.size == c.machine.banks, c.name)
        assert(
          perBank.map(b => b("reads").num + b("writes").num).sum ==
            m("scratchpad")("reads").num + m("scratchpad")("writes").num,
          c.name
        )
        if (c.machine.isaVersion == IsaVersion.Mvp2) {
          assert(m("metrics").str == "vliw-metrics/3", c.name)
          assert(ops.obj.keys.toSeq == Mvp2Operations, c.name)
          assert(m("writeback")("compute")("registers").num == scalar + 2 * vector, c.name)
          assert(m("writeback")("memory")("registers").num == n("LD") + 2 * n("LD.W"), c.name)
          val sp = m("scratchpad")
          assert(sp("read_operations").num == n("LD") + n("LD.W"), c.name)
          assert(sp("write_operations").num == n("ST") + n("ST.W"), c.name)
          val reads = issue("register_reads")
          val byReads = reads("bundles_by_reads").obj.toSeq.map { case (k, v) => (k, v.num) }
          assert(
            byReads == (0 to c.machine.readPorts)
              .map(k => k.toString -> bundles.count(readFields(_) == k).toDouble),
            c.name
          )
          assert(reads("reads").num == bundles.map(readFields).sum, c.name)
          val registers = bundles.flatMap(readRegisters)
          assert(reads("registers").num == registers.size, c.name)
          val rfBanks = reads("banks").arr.map(b => (b("bank").num.toInt, b("registers").num))
          assert(
            rfBanks == Seq(0, 1).map(k => (k, registers.count(_ % 2 == k).toDouble)),
            c.name
          )
          val rs3WithStore = bundles.count {
            case Bundle(_: ComputeOp.Mac | _: ComputeOp.VMac, _: MemoryOp.St | _: MemoryOp.StW) =>
              true
            case _ => false
          }
          assert(issue("rs3_with_store").num == rs3WithStore, c.name)
          assert(!issue.obj.contains("mac_with_st"), c.name)
        } else if (c.machine.isaVersion == IsaVersion.Mvp1) {
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

  // ISA mvp-2 (#110 stage 2): vliw-metrics/3.

  private val mvp2 = MachineDescription
    .load(MachineDescription.Mvp2Path)
    .fold(es => fail(es.mkString), identity)

  private val Mvp2Operations =
    Seq(
      "ADD",
      "SUB",
      "MUL",
      "MAC",
      "VADD",
      "VSUB",
      "VMUL",
      "VMAC",
      "LD",
      "ST",
      "LD.W",
      "ST.W",
      "HALT"
    )

  /** The example of docs/METRICS.md "vliw-metrics/3": M[0] = 3, M[17] = 5, M[18] = 2^32 - 1. */
  private def mvp2Record = recordOf(
    mvp2,
    "nop | ld.w r2:r3, [17]\nnop | ld r1, [0]\nnop\nvmul r4:r5, r1, r2:r3\nnop\n" +
      "nop | st.w [20], r4:r5\nhalt",
    Map.empty,
    Map(0 -> 3L, 17 -> 5L, 18 -> 0xffffffffL)
  )

  test("the format follows the ISA version: /1, /2 and /3") {
    assert(Metrics.format(isaCase.machine) == "vliw-metrics/1")
    assert(Metrics.format(mvp1) == "vliw-metrics/2")
    assert(Metrics.format(mvp2) == "vliw-metrics/3")
  }

  test("mvp-2: the example of docs/METRICS.md, every count derived by hand") {
    val m = mvp2Record
    assert(m("metrics").str == "vliw-metrics/3" && m("isa").str == "mvp-2")
    assert(
      m("machine")("id").str == "vliw2-i32-sp2-mac-w2" && m("machine")("hash").str == mvp2.hash
    )
    assert(m("program")("bundles").num == 7 && m("cycles").num == 7)
    val issue = m("issue")
    // Compute slot: VMUL, HALT and 5 NOPs. Memory slot: LD.W, LD and ST.W, 4 NOPs.
    assert(issue("compute")("operations").num == 1 && issue("compute")("nop").num == 5)
    assert(issue("compute")("nop_rate").num == 0.714286)
    assert(issue("memory")("operations").num == 3 && issue("memory")("nop").num == 4)
    assert(issue("memory")("nop_rate").num == 0.571429)
    val ops = issue("by_operation").obj.toSeq.map { case (k, v) => (k, v.num.toInt) }
    val one = Set("VMUL", "LD", "LD.W", "ST.W", "HALT")
    assert(ops == Mvp2Operations.map(k => (k, if (one(k)) 1 else 0)))
    // Fields: vmul reads r1 (broadcast) and r2:r3, st.w reads r4:r5. Registers: r1, r2, r3, r4, r5;
    // RF bank 0 holds r2 and r4, bank 1 r1, r3 and r5.
    val reads = issue("register_reads")
    assert(reads("read_ports").num == 3 && reads("reads").num == 3 && reads("registers").num == 5)
    assert(
      reads("banks").arr.map(b => (b("bank").num, b("registers").num)) == Seq((0, 2), (1, 3))
    )
    val byReads = reads("bundles_by_reads").obj.toSeq.map { case (k, v) => (k, v.num) }
    assert(byReads == Seq("0" -> 5.0, "1" -> 1.0, "2" -> 1.0, "3" -> 0.0))
    assert(issue("rs3_with_store").num == 0 && !issue.obj.contains("mac_with_st"))
    // Write-back: one operation (VMUL, 2 registers) on the compute port; LD.W (2) and LD (1) on the
    // memory port.
    val wb = m("writeback")
    assert(wb("compute")("commits").num == 1 && wb("compute")("registers").num == 2)
    assert(wb("compute")("utilization").num == 0.142857)
    assert(wb("memory")("commits").num == 2 && wb("memory")("registers").num == 3)
    assert(wb("memory")("utilization").num == 0.285714)
    // Words 17, 18 and 0 read, words 20 and 21 written; 17 and 21 are in bank 1.
    val sp = m("scratchpad")
    assert(sp("read_operations").num == 2 && sp("write_operations").num == 1)
    assert(
      sp("reads").num == 3 && sp("writes").num == 2 && sp("accesses_per_cycle").num == 0.714286
    )
    val banks = sp("banks").arr.map { b =>
      (b("bank").num, b("reads").num, b("writes").num, b("accesses_per_cycle").num)
    }
    assert(banks == Seq((0, 2, 1, 0.428571), (1, 1, 1, 0.285714)))
    assert(m("ii").isNull && m("rtl").isNull)
  }

  test("vliw-metrics/3 has the keys of docs/METRICS.md, whatever the machine") {
    val vectorOps = Seq("VADD", "VSUB", "VMUL", "VMAC")
    val reads = Seq("read_ports", "reads", "registers", "banks", "bundles_by_reads")
      .map("issue.register_reads." + _)
    val expected = paths(mvp1Record).flatMap {
      case p @ "issue.by_operation.MAC" => p +: vectorOps.map("issue.by_operation." + _)
      case p @ "issue.by_operation.ST"  =>
        Seq(p, "issue.by_operation.LD.W", "issue.by_operation.ST.W")
      case "issue.register_reads.read_ports" | "issue.register_reads.reads" => Nil
      case "issue.register_reads.bundles_by_reads"                          => reads
      case "issue.mac_with_st"             => Seq("issue.rs3_with_store")
      case p @ "writeback.compute.commits" => Seq(p, "writeback.compute.registers")
      case p @ "writeback.memory.commits"  => Seq(p, "writeback.memory.registers")
      case "scratchpad.reads"              =>
        Seq("scratchpad.read_operations", "scratchpad.write_operations", "scratchpad.reads")
      case p => Seq(p)
    }
    assert(paths(mvp2Record) == expected)
    // W-reg has the LD.W and ST.W keys too, with 0.
    val mw1 = MachineDescription
      .load(Paths.get("tests", "machines", "mvp2_mw1.json"))
      .fold(es => fail(es.mkString), identity)
    val w = recordOf(
      mw1,
      "vadd r4:r5, r2:r3, r2:r3 | ld r6, [0]\nnop\nhalt",
      Map(2 -> 1L, 3 -> 2L),
      Map(0 -> 3L)
    )
    assert(paths(w) == paths(mvp2Record))
    assert(
      w("issue")("by_operation")("LD.W").num == 0 && w("issue")("by_operation")("ST.W").num == 0
    )
  }

  test("mvp-2: VMAC or MAC with ST or ST.W, counted on 4 read ports") {
    val rp4 = MachineDescription
      .load(Paths.get("tests", "machines", "mvp2_rp4.json"))
      .fold(es => fail(es.mkString), identity)
    val w = recordOf(
      rp4,
      "vmac r4:r5, r2, r6:r7, r8:r9 | st.w [0], r10:r11\nmac r12, r1, r2, r3 | st [2], r1\n" +
        "vmac r4:r5, r2, r6:r7, r8:r9 | ld.w r10:r11, [4]\nnop\nhalt",
      (1 to 11).map(r => r -> r.toLong).toMap,
      Map(4 -> 1L, 5 -> 2L)
    )
    val issue = w("issue")
    assert(issue("rs3_with_store").num == 2)
    val reads = issue("register_reads")
    // Fields 4, 4, 3, 0, 0; registers 1 + 2 + 2 + 2, 4, 1 + 2 + 2.
    assert(reads("reads").num == 11 && reads("registers").num == 16)
    val byReads = reads("bundles_by_reads").obj.toSeq.map { case (k, v) => (k, v.num) }
    assert(byReads == Seq("0" -> 2.0, "1" -> 0.0, "2" -> 0.0, "3" -> 1.0, "4" -> 2.0))
  }

  test("mvp-2: the record does not depend on input values") {
    val src = "vmac r4:r5, r1, r2:r3, r6:r7 | ld.w r8:r9, [3]\nnop\nnop | st.w [5], r4:r5\nhalt"
    val a =
      recordOf(mvp2, src, Map(1 -> 1L, 2 -> 2L, 3 -> 3L, 6 -> 4L, 7 -> 5L), Map(3 -> 6L, 4 -> 7L))
    val b = recordOf(
      mvp2,
      src,
      Map(1 -> 0xffffffffL, 2 -> 9L, 3 -> 0L, 6 -> 123L, 7 -> 0x80000000L),
      Map(3 -> 0L, 4 -> 0xffffffffL)
    )
    assert(a == b)
  }
}
