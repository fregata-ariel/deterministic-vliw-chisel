package vliw.hw

import chisel3._
import chisel3.simulator.PeekPokeAPI._

/** Result of one run of a fixed pipeline under ChiselSim. `computeCommits` and `memoryCommits` hold
  * the cycle of every write on each write-back port (the write lands at the next boundary);
  * `computeIssues` and `memoryIssues` the cycles in which each slot issued.
  */
final case class FixedRun(
    cycles: Int,
    memory: Map[Int, BigInt],
    computeIssues: Vector[Int],
    memoryIssues: Vector[Int],
    computeCommits: Vector[Int],
    memoryCommits: Vector[Int],
    stores: Vector[(Int, Int, BigInt)], // (cycle, addr, value)
    doneCycles: Vector[Int],
    busyCycles: Vector[Int]
)

/** Loads a scratchpad image through the host port, starts the pipeline and records every cycle.
  * Cycles are counted as in [[CoreDriver]]: from start acceptance (E_0) to the cycle in which done
  * is high; the host's preload and readout are not counted.
  */
object FixedDriver {
  def run(
      dut: FixedPipeline,
      memory: Map[Int, Long],
      readBack: Set[Int],
      reset: Boolean = true,
      maxCycles: Int = 2000,
      extra: Int = 0
  ): FixedRun = {
    val host = dut.io.host
    host.start.poke(false.B)
    host.memWrite.valid.poke(false.B)
    if (reset) {
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
    }
    memory.toSeq.sorted.foreach { case (a, v) =>
      host.memWrite.valid.poke(true.B)
      host.memWrite.bits.addr.poke(a.U)
      host.memWrite.bits.data.poke((v & 0xffffffffL).U(32.W))
      dut.clock.step()
    }
    host.memWrite.valid.poke(false.B)

    // Accept start at E_0.
    host.start.poke(true.B)
    dut.clock.step()
    host.start.poke(false.B)

    val o = dut.io.observe
    val ci, mi, cc, mc, dones, busy = Vector.newBuilder[Int]
    val st = Vector.newBuilder[(Int, Int, BigInt)]
    var cycle = 0
    var doneAt = -1
    def running = doneAt < 0 || cycle < doneAt + extra
    // Runs `extra` cycles past done, to see that done stays low and nothing else issues.
    while (running) {
      if (cycle > maxCycles) throw new IllegalStateException(s"no done within $maxCycles cycles")
      if (o.computeIssue.peek().litToBoolean) ci += cycle
      if (o.memoryIssue.peek().litToBoolean) mi += cycle
      if (o.computeCommit.valid.peek().litToBoolean) cc += cycle
      if (o.memoryCommit.valid.peek().litToBoolean) mc += cycle
      if (o.memCommit.valid.peek().litToBoolean)
        st += ((
          cycle,
          o.memCommit.bits.addr.peek().litValue.toInt,
          o.memCommit.bits.data.peek().litValue
        ))
      if (dut.io.busy.peek().litToBoolean) busy += cycle
      if (dut.io.done.peek().litToBoolean) {
        dones += cycle
        if (doneAt < 0) doneAt = cycle
      }
      // A start while busy is ignored.
      host.start.poke(dut.io.busy.peek())
      if (running) {
        dut.clock.step()
        cycle += 1
      }
    }
    host.start.poke(false.B)
    val read = readBack.toSeq.sorted.map { a =>
      host.memReadAddr.poke(a.U)
      dut.clock.step()
      a -> host.memReadData.peek().litValue
    }.toMap
    FixedRun(
      doneAt,
      read,
      ci.result(),
      mi.result(),
      cc.result(),
      mc.result(),
      st.result(),
      dones.result(),
      busy.result()
    )
  }
}
