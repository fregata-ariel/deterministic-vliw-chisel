package vliw.hw

import chisel3._
import chisel3.simulator.PeekPokeAPI._

import vliw.isa._

/** Result of one run of the core under ChiselSim.
  *
  * `trace` is the body of a vliw-trace/1 trace (docs/TRACE.md), built from what the core reports on
  * its observe port; `violations` lists the rules signalled in any cycle. A run that
  * [[CoreDriver.run]] stopped at its first violation has `stoppedAt` (that cycle), the rules
  * signalled in that cycle, the trace up to that cycle, and no final state.
  */
final case class CoreRun(
    cycles: Int,
    trace: Vector[ujson.Obj],
    registers: Vector[BigInt],
    memory: Map[Int, BigInt],
    violations: Set[Rule],
    stoppedAt: Option[Int] = None
)

/** The program cannot be loaded: it does not fit the instruction memory (checked at load, rule L2).
  */
final class LoadRejected(message: String) extends Exception(message)

/** Loads a program and inputs through the host port, starts the core and records every cycle. */
object CoreDriver {
  private val Mask64 = (BigInt(1) << 64) - 1

  /** The issued bundle as the decoder reports it, rebuilt from the decoded fields rather than from
    * the instruction word, so that the trace shows what the slots actually execute (#56). None when
    * the decoder flags the bundle illegal (L1). On ISA mvp-2, the vector operations, B, LD.W and
    * ST.W come from `vector`, `broadcast` and `wide`.
    */
  private def decoded(d: DecodedBundle): Option[vliw.isa.Bundle] =
    Option.unless(d.illegal.peek().litToBoolean) {
      def field(x: UInt) = x.peek().litValue.toInt
      def flag(x: Option[Bool]) = x.exists(_.peek().litToBoolean)
      val compute: ComputeOp =
        if (d.halt.peek().litToBoolean) ComputeOp.Halt
        else if (!d.compute.valid.peek().litToBoolean) ComputeOp.Nop
        else {
          val (rd, a, b) = (field(d.compute.bits.rd), field(d.rs1), field(d.rs2))
          val (vector, broadcast) = (flag(d.compute.bits.vector), flag(d.compute.bits.broadcast))
          d.compute.bits.kind.peek().litValue match {
            case k if k == ComputeKind.Add.litValue =>
              if (vector) ComputeOp.VAdd(rd, a, b) else ComputeOp.Add(rd, a, b)
            case k if k == ComputeKind.Sub.litValue =>
              if (vector) ComputeOp.VSub(rd, a, b) else ComputeOp.Sub(rd, a, b)
            case k if k == ComputeKind.Mac.litValue =>
              val c = field(d.rs3.get)
              if (vector) ComputeOp.VMac(rd, a, b, c, broadcast) else ComputeOp.Mac(rd, a, b, c)
            case _ =>
              if (vector) ComputeOp.VMul(rd, a, b, broadcast) else ComputeOp.Mul(rd, a, b)
          }
        }
      val memory: MemoryOp =
        if (!d.memory.valid.peek().litToBoolean) MemoryOp.Nop
        else {
          val (reg, addr) = (field(d.memory.bits.reg), field(d.memory.bits.addr))
          val wide = flag(d.memory.bits.wide)
          if (d.memory.bits.kind.peek().litValue == MemoryKind.Ld.litValue)
            if (wide) MemoryOp.LdW(reg, addr) else MemoryOp.Ld(reg, addr)
          else if (wide) MemoryOp.StW(addr, reg)
          else MemoryOp.St(addr, reg)
        }
      vliw.isa.Bundle(compute, memory)
    }

  /** Resets the core, loads the program and inputs through the host port, and has start accepted at
    * E_0: the core is in cycle 0 of the run when this returns.
    */
  def start(dut: Core, machine: MachineDescription, words: Vector[Long], inputs: Inputs): Unit = {
    if (words.size > machine.instructionMemoryBundles)
      throw new LoadRejected(
        s"L2: ${words.size} bundles exceed the instruction memory (${machine.instructionMemoryBundles})"
      )
    val host = dut.io.host
    host.start.poke(false.B)
    host.imemWrite.valid.poke(false.B)
    host.rfWrite.valid.poke(false.B)
    host.memWrite.valid.poke(false.B)
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)

    words.zipWithIndex.foreach { case (w, i) =>
      host.imemWrite.valid.poke(true.B)
      host.imemWrite.bits.addr.poke(i.U)
      host.imemWrite.bits.data.poke((BigInt(w) & Mask64).U(64.W))
      dut.clock.step()
    }
    host.imemWrite.valid.poke(false.B)
    inputs.registers.foreach { case (r, v) =>
      host.rfWrite.valid.poke(true.B)
      host.rfWrite.bits.reg.poke(r.U)
      host.rfWrite.bits.data.poke(v.U(32.W))
      dut.clock.step()
    }
    host.rfWrite.valid.poke(false.B)
    inputs.memory.foreach { case (a, v) =>
      host.memWrite.valid.poke(true.B)
      host.memWrite.bits.addr.poke(a.U)
      host.memWrite.bits.data.poke(v.U(32.W))
      dut.clock.step()
    }
    host.memWrite.valid.poke(false.B)

    // Accept start at E_0.
    host.length.poke(words.size.U)
    host.start.poke(true.B)
    dut.clock.step()
    host.start.poke(false.B)
  }

  /** Runs the program to done and reads back the registers and the words `readBack`.
    *
    * With `stopAtViolation`, the run instead stops in the first cycle in which the core signals a
    * violation (`io.violation`), before the clock edge at the end of that cycle: it holds reset
    * over that edge, so that no assertion fires (every assertion of the core is gated by reset) and
    * the same core can run the next program, whose `start` resets it again. The core itself never
    * stalls or stops; this is only how a test reads which rules the core signals and when, for many
    * programs in one build (docs/VERIFIER.md).
    */
  def run(
      dut: Core,
      machine: MachineDescription,
      words: Vector[Long],
      inputs: Inputs,
      readBack: Set[Int],
      maxCycles: Int = 2000,
      stopAtViolation: Boolean = false
  ): CoreRun = {
    start(dut, machine, words, inputs)
    val host = dut.io.host
    val lines = Vector.newBuilder[ujson.Obj]
    var violations = Set.empty[Rule]
    var visibleNow = (Vector.empty[ujson.Obj], Vector.empty[ujson.Obj]) // commits made at E_c
    var cycle = 0
    var finished = false
    while (!finished) {
      if (cycle > maxCycles) throw new IllegalStateException(s"no done within $maxCycles cycles")
      val o = dut.io.observe
      val busy = dut.io.busy.peek().litToBoolean
      val done = dut.io.done.peek().litToBoolean
      val now = signalled(dut)
      violations ++= now
      val issue: ujson.Value =
        if (!busy) ujson.Null
        else
          decoded(o.decoded) match {
            case Some(b) =>
              ujson.Obj(
                "compute" -> Disassembler.compute(b.compute),
                "memory" -> Disassembler.memory(b.memory)
              )
            case None => ujson.Obj("compute" -> "?", "memory" -> "?")
          }
      lines += ujson.Obj(
        "cycle" -> cycle,
        "pc" -> (if (busy) ujson.Num(o.pc.peek().litValue.toDouble) else ujson.Null),
        "issue" -> issue,
        "rf_commit" -> ujson.Arr.from(visibleNow._1),
        "mem_commit" -> ujson.Arr.from(visibleNow._2),
        "busy" -> busy,
        "done" -> done
      )
      if (stopAtViolation && now.nonEmpty) {
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        return CoreRun(cycle, lines.result(), Vector.empty, Map.empty, now, Some(cycle))
      }
      // Writes performed in this cycle become visible in the next one. A pair (mvp-2) is two
      // commits of one port, in register order; ST.W two words, in address order (docs/TRACE.md).
      val rf = Seq("compute", "memory")
        .zip(o.rfCommit)
        .collect {
          case (port, c) if c.valid.peek().litToBoolean =>
            val reg = c.bits.reg.peek().litValue.toInt
            def commit(r: Int, value: UInt) =
              ujson.Obj("reg" -> r, "value" -> value.peek().litValue.toDouble, "port" -> port)
            if (c.bits.pair.exists(_.peek().litToBoolean))
              Vector(commit(reg, c.bits.data), commit(reg + 1, c.bits.data1.get))
            else Vector(commit(reg, c.bits.data))
        }
        .flatten
        .toVector
      val mem = (Seq(o.memCommit) ++ o.memCommit1).collect {
        case c if c.valid.peek().litToBoolean =>
          ujson.Obj(
            "addr" -> c.bits.addr.peek().litValue.toInt,
            "value" -> c.bits.data.peek().litValue.toDouble
          )
      }.toVector
      visibleNow = (rf, mem)
      finished = done
      if (!finished) {
        dut.clock.step()
        cycle += 1
      }
    }

    val registers = (0 until machine.registerCount).toVector.map { r =>
      host.rfReadAddr.poke(r.U)
      host.rfReadData.peek().litValue
    }
    val memory = readBack.toSeq.sorted.map { a =>
      host.memReadAddr.poke(a.U)
      dut.clock.step()
      a -> host.memReadData.peek().litValue
    }.toMap
    CoreRun(cycle, lines.result(), registers, memory, violations)
  }

  private def signalled(dut: Core): Set[Rule] = {
    val v = dut.io.violation
    (Seq(
      v.encoding -> Rule.Encoding,
      v.programShape -> Rule.ProgramShape,
      v.inFlightRead -> Rule.InFlightRead,
      v.overlappingWrites -> Rule.OverlappingWrites,
      v.writebackPort -> Rule.WritebackPort,
      v.haltCompletion -> Rule.HaltCompletion,
      v.addressRange -> Rule.AddressRange
    ) ++ v.readPorts.map(_ -> Rule.ReadPorts)).collect {
      case (signal, rule) if signal.peek().litToBoolean => rule
    }.toSet
  }
}
