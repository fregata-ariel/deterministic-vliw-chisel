package vliw.hw

import chisel3._
import chisel3.simulator.PeekPokeAPI._

import vliw.isa._

/** Result of one run of the core under ChiselSim.
  *
  * `trace` is the body of a vliw-trace/1 trace (docs/TRACE.md), built from what the core reports on
  * its observe port; `violations` lists the rules signalled in any cycle.
  */
final case class CoreRun(
    cycles: Int,
    trace: Vector[ujson.Obj],
    registers: Vector[BigInt],
    memory: Map[Int, BigInt],
    violations: Set[Rule]
)

/** The program cannot be loaded: it does not fit the instruction memory (checked at load, rule L2).
  */
final class LoadRejected(message: String) extends Exception(message)

/** Loads a program and inputs through the host port, starts the core and records every cycle. */
object CoreDriver {
  private val Mask64 = (BigInt(1) << 64) - 1

  def run(
      dut: Core,
      machine: MachineDescription,
      words: Vector[Long],
      inputs: Inputs,
      readBack: Set[Int],
      maxCycles: Int = 2000
  ): CoreRun = {
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

    val codec = new BundleCodec(machine)
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
      violations ++= signalled(dut)
      val issue: ujson.Value =
        if (!busy) ujson.Null
        else
          codec.decode(o.word.peek().litValue.toLong) match {
            case Right(b) =>
              ujson.Obj(
                "compute" -> Disassembler.compute(b.compute),
                "memory" -> Disassembler.memory(b.memory)
              )
            case Left(_) => ujson.Obj("compute" -> "?", "memory" -> "?")
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
      // Writes performed in this cycle become visible in the next one.
      val rf = Seq("compute", "memory")
        .zip(o.rfCommit)
        .collect {
          case (port, c) if c.valid.peek().litToBoolean =>
            ujson.Obj(
              "reg" -> c.bits.reg.peek().litValue.toInt,
              "value" -> c.bits.data.peek().litValue.toDouble,
              "port" -> port
            )
        }
        .toVector
      val mem =
        if (o.memCommit.valid.peek().litToBoolean)
          Vector(
            ujson.Obj(
              "addr" -> o.memCommit.bits.addr.peek().litValue.toInt,
              "value" -> o.memCommit.bits.data.peek().litValue.toDouble
            )
          )
        else Vector.empty
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
    Seq(
      v.encoding -> Rule.Encoding,
      v.programShape -> Rule.ProgramShape,
      v.inFlightRead -> Rule.InFlightRead,
      v.overlappingWrites -> Rule.OverlappingWrites,
      v.writebackPort -> Rule.WritebackPort,
      v.haltCompletion -> Rule.HaltCompletion,
      v.addressRange -> Rule.AddressRange
    ).collect { case (signal, rule) if signal.peek().litToBoolean => rule }.toSet
  }
}
