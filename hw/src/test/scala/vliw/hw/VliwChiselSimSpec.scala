package vliw.hw

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import chisel3._
import org.scalatest.funsuite.AnyFunSuite
import svsim.CommonCompilationSettings.VerilogPreprocessorDefine

class VliwChiselSimSpec extends AnyFunSuite with VliwChiselSim {
  test("the seed is rendered in the only form that Verilator accepts") {
    assert(SimSeed.plusArg.simulatorFlags == s"+verilator+seed+${SimSeed.value}")
    assert(SimSeed.value >= 1)
  }

  test("Verilator verilates in one thread and builds on all cores") {
    assert(
      SimVerilator.parallelism.toCompileFlags == Seq("--build-jobs", "0", "--verilate-jobs", "1")
    )
  }

  test("every simulation records its seed and is built and replayed with the project's settings") {
    simulate(new RegisterFile(16)) { dut =>
      dut.io.write.foreach(_.valid.poke(false.B))
      dut.clock.step()
    }
    val dir = implementation.getDirectory
    def read(name: String) =
      new String(Files.readAllBytes(dir.resolve(name)), StandardCharsets.UTF_8)
    assert(read("seed.txt") == SimSeed.describe + "\n")
    // The seed with the version of the Verilator that ran (ADR-0020, R3).
    assert(
      read("seed.txt") == s"VLIW_SIM_SEED=${SimSeed.value} (${SimSeed.source}) verilator=" +
        s"${Toolchain.installedVerilator}\n"
    )
    // `simulate` keeps ChiselSim's random initial values (#129).
    assert(read("init.txt") == "init=urandom RANDOM=$urandom\n")

    // The Makefile in the work directory holds the commands that svsim ran: `simulation` builds,
    // `replay` runs the simulation again with the same arguments as this run.
    val makefile = read("workdir-verilator/Makefile")
    assert(makefile.contains(SimSeed.plusArg.simulatorFlags))
    val verilator = makefile.linesIterator
      .dropWhile(!_.startsWith("simulation:"))
      .drop(1)
      .takeWhile(_.trim.nonEmpty)
      .flatMap(line => "'([^']*)'".r.findAllMatchIn(line).map(_.group(1)))
      .toSeq
    assert(verilator.contains("--build"), verilator)
    assert(verilator.containsSlice(Seq("--build-jobs", "0")), verilator)
    assert(verilator.containsSlice(Seq("--verilate-jobs", "1")), verilator)
    assert(!verilator.contains("-j"), verilator) // -j would also set the verilation threads
  }

  private def text(dir: Path, name: String) =
    new String(Files.readAllBytes(dir.resolve(name)), StandardCharsets.UTF_8)

  test("the all-ones mode reaches every register, and is recorded and passed to Verilator") {
    simulateInit(InitMode.Ones)(new RegisterFile(16)) { dut =>
      dut.io.write.foreach(_.valid.poke(false.B))
      (0 until 16).foreach { r =>
        dut.io.read(0).addr.poke(r.U)
        assert(dut.io.read(0).data.peek().litValue == BigInt(0xffffffffL), s"r$r")
      }
    }
    val dir = implementation.getDirectory
    assert(text(dir, "init.txt") == "init=ones RANDOM=32'hFFFFFFFF\n")
    assert(text(dir, "seed.txt") == SimSeed.describe + "\n")
    // The raw text: svsim's Makefile writes ' as '\'' (the pattern above would split the argument).
    assert(text(dir, "workdir-verilator/Makefile").contains("'+define+RANDOM=32'\\''hFFFFFFFF'"))
    val sv = SvInitCheck.directory(dir.resolve("primary-sources"))
    assert(sv.problems.isEmpty && sv.registers == 16 && sv.memories == 0, sv)
  }

  private val regInit = VerilogPreprocessorDefine("RANDOMIZE_REG_INIT")
  private val memInit = VerilogPreprocessorDefine("RANDOMIZE_MEM_INIT")
  private def random(value: String) = VerilogPreprocessorDefine("RANDOM", value)

  test("the defines of a build select one of the three modes, and nothing else") {
    InitMode.all.foreach { m =>
      assert(InitMode.of(Seq(regInit, memInit, random(m.value))) == Right(m), m)
      assert(
        InitMode.of(
          Seq(VerilogPreprocessorDefine("RANDOMIZE_DELAY", "1"), random(m.value), memInit, regInit)
        ) == Right(m),
        m
      )
    }
    def rejected(defines: Seq[VerilogPreprocessorDefine], reason: String) = {
      val result = InitMode.of(defines)
      assert(result.swap.exists(_.contains(reason)), s"$defines: $result")
    }
    val ones = random("32'hFFFFFFFF")
    rejected(Seq(memInit, ones), "RANDOMIZE_REG_INIT is defined 0 times")
    rejected(Seq(regInit, ones), "RANDOMIZE_MEM_INIT is defined 0 times")
    rejected(Seq(regInit, regInit, memInit, ones), "RANDOMIZE_REG_INIT is defined 2 times")
    rejected(Seq(regInit, memInit, memInit, ones), "RANDOMIZE_MEM_INIT is defined 2 times")
    rejected(Seq(regInit, memInit), "RANDOM is defined 0 times")
    rejected(Seq(regInit, memInit, ones, random("32'h0")), "RANDOM is defined 2 times")
    rejected(Seq(regInit, memInit, VerilogPreprocessorDefine("RANDOM")), "RANDOM= is not one of")
    // Unsized values fail the build; other values are not a mode.
    Seq("0", "'1", "32'h1", "$random", "32'hffffffff").foreach { v =>
      rejected(Seq(regInit, memInit, random(v)), s"RANDOM=$v is not one of 32'h0, 32'hFFFFFFFF")
    }
    rejected(
      Seq(regInit, memInit, ones, VerilogPreprocessorDefine("SYNTHESIS")),
      "SYNTHESIS is defined"
    )
  }

  /** The shape of firtool 1.158.0's output: two registers and a memory. */
  private val firtoolShaped =
    """// Generated by CIRCT firtool-1.158.0
      |`ifndef SYNTHESIS
      |  `ifndef ENABLE_INITIAL_REG_
      |    `define ENABLE_INITIAL_REG_
      |  `endif // not def ENABLE_INITIAL_REG_
      |`endif // not def SYNTHESIS
      |`ifndef SYNTHESIS
      |  `ifndef ENABLE_INITIAL_MEM_
      |    `define ENABLE_INITIAL_MEM_
      |  `endif // not def ENABLE_INITIAL_MEM_
      |`endif // not def SYNTHESIS
      |module M(
      |  input clock
      |);
      |
      |  reg [31:0] wide;	// M.scala:3:7
      |  reg        flag;	// M.scala:4:7
      |  reg [31:0] Memory[0:3];	// M.scala:5:7
      |  `ifdef ENABLE_INITIAL_REG_	// M.scala:1:7
      |    initial begin	// M.scala:1:7
      |      automatic logic [31:0] _RANDOM[0:1];	// M.scala:1:7
      |      `ifdef RANDOMIZE_REG_INIT	// M.scala:1:7
      |        for (logic [1:0] i = 2'h0; i < 2'h2; i += 2'h1) begin
      |          _RANDOM[i[0]] = `RANDOM;	// M.scala:1:7
      |        end	// M.scala:1:7
      |        wide = {_RANDOM[1'h0][31:1], _RANDOM[1'h1][0]};	// M.scala:1:7, :3:7
      |        flag = _RANDOM[1'h1][1];	// M.scala:1:7, :4:7
      |      `endif // RANDOMIZE_REG_INIT
      |    end // initial
      |  `endif // ENABLE_INITIAL_REG_
      |  `ifdef ENABLE_INITIAL_MEM_	// M.scala:5:7
      |    reg [31:0] _RANDOM_MEM;	// M.scala:5:7
      |    initial begin	// M.scala:5:7
      |      `ifdef RANDOMIZE_MEM_INIT	// M.scala:5:7
      |        for (logic [2:0] i = 3'h0; i < 3'h4; i += 3'h1) begin
      |          _RANDOM_MEM = `RANDOM;	// M.scala:5:7
      |          Memory[i[1:0]] = _RANDOM_MEM;	// M.scala:5:7
      |        end
      |      `endif // RANDOMIZE_MEM_INIT
      |    end // initial
      |  `endif // ENABLE_INITIAL_MEM_
      |endmodule
      |""".stripMargin

  test("the check of the generated SystemVerilog finds state that does not start from RANDOM") {
    val ok = SvInitCheck.file("M.sv", firtoolShaped)
    assert(ok == SvInitCheck.Result(1, 2, 1, Seq.empty))
    def problems(from: String, to: String) = {
      assert(firtoolShaped.contains(from), from)
      SvInitCheck.file("M.sv", firtoolShaped.replace(from, to)).problems
    }
    def found(from: String, to: String, problem: String) = {
      val p = problems(from, to)
      assert(p.exists(_.contains(problem)), p)
    }
    // A register or memory that is not set, or set from something else.
    found("        flag = _RANDOM[1'h1][1];", "", "reg flag is not set under RANDOMIZE_REG_INIT")
    found("_RANDOM[1'h1][1];", "1'h0;", "reg flag is set from '1'h0'")
    found("_RANDOM[1'h0][31:1], ", "31'h0, ", "reg wide is set from")
    found(
      "      `endif // RANDOMIZE_REG_INIT",
      "        wide = 32'h0;\n      `endif // RANDOMIZE_REG_INIT",
      "reg wide is set from '32'h0'"
    )
    found("          Memory[i[1:0]] = _RANDOM_MEM;", "", "memory Memory is not set")
    found("Memory[i[1:0]] = _RANDOM_MEM;", "Memory[i[1:0]] = 32'h0;", "memory Memory is set from")
    // A fill from something other than `RANDOM.
    found("_RANDOM_MEM = `RANDOM;", "_RANDOM_MEM = 32'h0;", "_RANDOM_MEM is filled from '32'h0'")
    found("_RANDOM[i[0]] = `RANDOM;", "_RANDOM[i[0]] = $random;", "_RANDOM is filled from")
    // The init blocks outside the SYNTHESIS guard, or outside ENABLE_INITIAL_*_.
    found("`ifndef SYNTHESIS", "`ifdef SYNTHESIS", "is not defined once inside `ifndef SYNTHESIS")
    found(
      "`endif // not def SYNTHESIS\nmodule",
      "`endif // not def SYNTHESIS\n`define ENABLE_INITIAL_REG_\nmodule",
      "ENABLE_INITIAL_REG_ is not defined once inside `ifndef SYNTHESIS"
    )
    found("  `ifdef ENABLE_INITIAL_REG_\t", "  `ifdef OTHER\t", "reg wide is not set")
    // A declaration in another form.
    found("  reg        flag;", "  reg        flag, other;", "unrecognized declaration")
    // A file without registers has nothing to check.
    assert(SvInitCheck.file("D.sv", "module D(input a, output b);\nendmodule\n").problems.isEmpty)
  }
}
