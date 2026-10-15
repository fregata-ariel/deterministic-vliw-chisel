package vliw.compiler.loop

import vliw.compiler.dsl.{MemoryLayout, Op}
import vliw.isa.MachineDescription

/** `base + stride * k` for the iteration number k. */
final case class Address(base: Int, stride: Int) {
  def at(k: Int): Int = base + stride * k
}

/** A loop after memory layout (docs/COMPILER.md, "ループ IR"): every leaf reads a scratchpad word
  * whose address is affine in the iteration number, and states are numbered. This general form may
  * read words that the loop writes; a `LoopKernel` never does.
  *
  * Iterations k = 0 until `trips`. `inits(s)` is the value of state s before the first iteration
  * (read before the loop). In iteration k the statements of `body` run in order: a `Write` stores
  * its value at once, and an `Update` gives the state's value for iteration k + 1, so `StateRef`
  * reads the value at the start of the iteration. After the last iteration, `finals` store states.
  */
final case class Loop(
    trips: Int,
    inits: Vector[Loop.Value],
    body: Vector[Loop.Statement],
    finals: Vector[Loop.Final]
) {
  require(trips >= 1, s"a loop has $trips iterations")
  inits.foreach(v => require(Loop.invariantRead(v), s"an initial value reads a changing word: $v"))
  private val updated = body.collect { case Loop.Update(s, _) => s }
  require(
    updated.sorted == inits.indices,
    s"each state must be updated once in the body: ${updated.mkString(", ")}"
  )
  finals.foreach(f => require(inits.indices.contains(f.state), s"unknown state ${f.state}"))
}

object Loop {
  sealed trait Value extends Product with Serializable
  final case class Read(addr: Address) extends Value
  final case class Bin(op: Op, a: Value, b: Value) extends Value
  final case class StateRef(state: Int) extends Value

  sealed trait Statement extends Product with Serializable

  /** `[addr.at(k)] = value`. */
  final case class Write(addr: Address, value: Value) extends Statement

  /** The value of `state` in the next iteration. */
  final case class Update(state: Int, value: Value) extends Statement

  /** `[addr] = ` the final value of `state`. */
  final case class Final(addr: Int, state: Int)

  /** Reads only words with stride 0 and no state: a value that can be computed before the loop. */
  private def invariantRead(v: Value): Boolean = v match {
    case Read(a)      => a.stride == 0
    case Bin(_, a, b) => invariantRead(a) && invariantRead(b)
    case StateRef(_)  => false
  }
}

final case class LoweredLoop(layout: MemoryLayout, loop: Loop)

object LoopLowering {
  import Loop._

  /** The layout of the unrolled kernel (`MemoryLayout.fixedOrder`): the loop and the list-scheduled
    * kernel read the same words at the same addresses. A constant that the unrolled kernel does not
    * read (only a value that is never used reads it, such as a state's next value in the last
    * iteration when the state is not stored) gets a word after the outputs.
    */
  def layout(k: LoopKernel, machine: MachineDescription): Either[String, MemoryLayout] =
    MemoryLayout.fixedOrder(k.unroll, machine).flatMap { l =>
      val extra = k.constants.filterNot(l.constants.contains)
      val words = l.words + extra.size
      if (words > machine.scratchpadWords)
        Left(
          s"loop ${k.name} needs $words words; the scratchpad of ${machine.machineId} has ${machine.scratchpadWords}"
        )
      else
        Right(
          l.copy(
            constants = l.constants ++ extra.zipWithIndex.map { case (v, i) => v -> (l.words + i) },
            words = words
          )
        )
    }

  /** Replaces elements, scalars and constants with reads of their layout addresses. Stores come
    * before the states' updates in the body; a `LoopKernel` reads no word it writes, so the order
    * does not change a value.
    */
  def lower(k: LoopKernel, layout: MemoryLayout): LoweredLoop = {
    require(layout.kernel == k.unroll, s"the layout is not the one of ${k.name}")
    val state = k.states.map(_.name).zipWithIndex.toMap
    def value(e: LoopExpr): Value = e match {
      case LoopExpr.Elem(a, i)    => Read(Address(layout.elem(a, i.offset), i.stride))
      case LoopExpr.Scalar(s)     => Read(Address(layout.scalar(s), 0))
      case LoopExpr.Const(v)      => Read(Address(layout.constant(v), 0))
      case LoopExpr.State(s)      => StateRef(state(s))
      case LoopExpr.Bin(op, a, b) => Bin(op, value(a), value(b))
    }
    val writes = k.stores.map(s =>
      Write(Address(layout.elem(s.output, s.index.offset), s.index.stride), value(s.value))
    )
    val updates = k.states.zipWithIndex.map { case (s, i) => Update(i, value(s.next)) }
    LoweredLoop(
      layout,
      Loop(
        k.trips,
        k.states.map(s => value(s.init)),
        writes ++ updates,
        k.finals.map(f => Final(layout.elem(f.output, f.index), state(f.state)))
      )
    )
  }

  /** Runs the loop on a scratchpad image in the order of its definition; returns the image after
    * the finals.
    */
  def evaluate(loop: Loop, memory: Map[Int, Long]): Map[Int, Long] = {
    def eval(v: Value, mem: Map[Int, Long], states: Vector[Long], k: Int): Long = v match {
      case Read(a)       => mem(a.at(k))
      case Bin(op, a, b) => op(eval(a, mem, states, k), eval(b, mem, states, k))
      case StateRef(s)   => states(s)
    }
    val start = loop.inits.map(eval(_, memory, Vector.empty, 0))
    val (mem, states) = (0 until loop.trips).foldLeft((memory, start)) { case ((m0, s0), k) =>
      loop.body.foldLeft((m0, s0)) {
        case ((m, s), Write(a, v))  => (m.updated(a.at(k), eval(v, m, s0, k)), s)
        case ((m, s), Update(i, v)) => (m, s.updated(i, eval(v, m, s0, k)))
      }
    }
    loop.finals.foldLeft(mem)((m, f) => m.updated(f.addr, states(f.state)))
  }
}
