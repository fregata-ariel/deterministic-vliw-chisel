package vliw.hw

import vliw.isa.{MachineDescription, Slot}

/** One operation of a fixed pipeline's static schedule (#75). Operands name values: value `i` is
  * the result of operation `i` of the plan (a LD or a compute operation).
  */
sealed trait FixedOp extends Product with Serializable {

  /** The cycle the operation issues in, counted from E_0 (start acceptance). */
  def issue: Int

  /** ISA mnemonic: selects the latency and II in the machine description. */
  def mnemonic: String

  /** The values the operation reads in its issue cycle. */
  def operands: Seq[Int]
}

object FixedOp {

  /** Reads scratchpad word `addr` (an address of the layout). */
  final case class Load(issue: Int, addr: Int) extends FixedOp {
    def mnemonic: String = "LD"
    def operands: Seq[Int] = Nil
  }

  /** ADD, SUB or MUL of values `a` and `b`, modulo 2^32. */
  final case class Compute(issue: Int, mnemonic: String, a: Int, b: Int) extends FixedOp {
    def operands: Seq[Int] = Seq(a, b)
  }

  /** Writes value `value` to scratchpad word `addr`. */
  final case class Store(issue: Int, addr: Int, value: Int) extends FixedOp {
    def mnemonic: String = "ST"
    def operands: Seq[Int] = Seq(value)
  }
}

/** The static schedule a fixed pipeline is built from: every operation of one kernel with its issue
  * cycle. It names no register; [[FixedPlan.bind]] chooses the storage of the values.
  */
final case class FixedPlan(name: String, ops: Vector[FixedOp]) {
  def produces(i: Int): Boolean = !ops(i).isInstanceOf[FixedOp.Store]

  /** The operations that read value `i`. */
  lazy val readers: Vector[Vector[Int]] = {
    val by = ops.indices.flatMap(j => ops(j).operands.distinct.map(_ -> j)).groupMap(_._1)(_._2)
    ops.indices.map(i => by.getOrElse(i, Vector.empty).toVector.sorted).toVector
  }
}

/** Where a value lives: register `register`, written through the write-back port `port`. */
final case class Storage(register: Int, port: Slot)

/** How values share registers. */
sealed abstract class StoragePolicy(val name: String) extends Product with Serializable
object StoragePolicy {

  /** One register per value: every register is written once per run. */
  case object PerValue extends StoragePolicy("per-value")

  /** Values whose lifetimes do not overlap share a register (left-edge allocation, separately for
    * the values of each write-back port, so that no register needs a write multiplexer).
    */
  case object ByLifetime extends StoragePolicy("by-lifetime")

  /** The register of every operation's value as given (None for a store), for example the
    * compiler's allocation of the same schedule. A register written by both write-back ports takes
    * a 2:1 write multiplexer. [[FixedPlan.storageViolations]] checks that lifetimes do not overlap.
    */
  final case class Assigned(registers: Vector[Option[Int]]) extends StoragePolicy("assigned")
}

object FixedPlan {
  val ComputeMnemonics: Seq[String] = Seq("ADD", "SUB", "MUL")

  /** The boundary at which the last operation completes, E_T: the run takes T cycles. */
  def completion(machine: MachineDescription, plan: FixedPlan): Int =
    plan.ops.map(o => o.issue + machine.operation(o.mnemonic).latency).maxOption.getOrElse(0)

  /** The cycle in which value `i` is written to its register (the write lands at E_(s+L)). */
  def commitCycle(machine: MachineDescription, op: FixedOp): Int =
    op.issue + machine.operation(op.mnemonic).latency - 1

  /** Why `plan` breaks a resource limit or a latency of `machine`; empty when it is legal. Checked
    * here, independently of the compiler's scheduler: one compute and one memory operation per
    * cycle, one write per write-back port and boundary (L5), the units' II (L9), operands read no
    * earlier than issue + latency of their producer (L3), addresses in the scratchpad (L7), and
    * loads and stores on disjoint words, each stored once (as in every DSL kernel).
    */
  def violations(machine: MachineDescription, plan: FixedPlan): Seq[String] = {
    val ops = plan.ops
    def lat(o: FixedOp) = machine.operations.get(o.mnemonic).fold(1)(_.latency)
    val shape = Option.when(ops.isEmpty)(s"${plan.name} has no operation").toSeq ++
      ops.zipWithIndex.flatMap { case (o, i) =>
        val kind = o match {
          case c: FixedOp.Compute if !ComputeMnemonics.contains(c.mnemonic) =>
            Some(s"op $i: ${c.mnemonic} is not a compute operation")
          case _ => None
        }
        val time = Option.when(o.issue < 0)(s"op $i issues at ${o.issue}, before E_0")
        val addr = o match {
          case FixedOp.Load(_, a) if a < 0 || a >= machine.scratchpadWords =>
            Some(s"op $i: address $a is outside the scratchpad")
          case FixedOp.Store(_, a, _) if a < 0 || a >= machine.scratchpadWords =>
            Some(s"op $i: address $a is outside the scratchpad")
          case _ => None
        }
        val operands = o.operands.flatMap { v =>
          if (v < 0 || v >= ops.size || !plan.produces(v))
            Some(s"op $i reads $v, which is no value")
          else
            Option.when(o.issue < ops(v).issue + lat(ops(v)))(
              s"op $i issues at ${o.issue} and reads value $v, available from ${ops(v).issue + lat(ops(v))}"
            )
        }
        kind ++ time ++ addr ++ operands
      }
    if (shape.nonEmpty) return shape

    def slot(o: FixedOp) = machine.operation(o.mnemonic).slot
    val issueSlots = ops.zipWithIndex.groupBy { case (o, _) => (slot(o), o.issue) }.collect {
      case ((s, c), os) if os.size > 1 =>
        s"cycle $c issues ${os.size} ${s.name} operations (ops ${os.map(_._2).mkString(", ")})"
    }
    val ports = ops.zipWithIndex
      .flatMap { case (o, i) => machine.operation(o.mnemonic).writeback.map(p => (p, o, i)) }
      .groupBy { case (p, o, _) => (p, o.issue + lat(o)) }
      .collect {
        case ((p, b), ws) if ws.size > 1 =>
          s"boundary E_$b has ${ws.size} writes on the ${p.name} write-back port (ops ${ws.map(_._3).mkString(", ")})"
      }
    val units = ops.zipWithIndex.groupBy(_._1.mnemonic).toSeq.flatMap { case (m, os) =>
      val ii = machine.operation(m).ii
      os.sortBy(_._1.issue).sliding(2).collect {
        case Seq((a, i), (b, j)) if b.issue - a.issue < ii =>
          s"$m issues at ${a.issue} and ${b.issue} (ops $i, $j), less than its II $ii apart"
      }
    }
    val loads = ops.collect { case FixedOp.Load(_, a) => a }.toSet
    val stores = ops.collect { case FixedOp.Store(_, a, _) => a }
    val memory = stores.groupBy(identity).collect {
      case (a, ss) if ss.size > 1 => s"word $a is stored ${ss.size} times"
    } ++ stores.distinct.filter(loads).map(a => s"word $a is both loaded and stored")
    (issueSlots ++ ports ++ units ++ memory).toSeq.sorted
  }

  /** The first and the last cycle in which value `i` must be in its register: from the cycle after
    * its write (issue + L) to the issue cycle of its last reader.
    */
  def lifetime(machine: MachineDescription, plan: FixedPlan, i: Int): (Int, Int) = {
    val from = commitCycle(machine, plan.ops(i)) + 1
    (from, plan.readers(i).map(plan.ops(_).issue).maxOption.getOrElse(from))
  }

  /** Why `storage` cannot hold the values of `plan`: a value without a register, a store with one,
    * or two values in one register with overlapping lifetimes.
    */
  def storageViolations(
      machine: MachineDescription,
      plan: FixedPlan,
      storage: Vector[Option[Storage]]
  ): Seq[String] = {
    val shape = plan.ops.indices.flatMap { i =>
      if (storage.size != plan.ops.size) None
      else if (plan.produces(i) && storage(i).isEmpty) Some(s"value $i has no register")
      else if (!plan.produces(i) && storage(i).nonEmpty)
        Some(s"op $i is a store and has a register")
      else None
    }
    val size = Option.when(storage.size != plan.ops.size)(
      s"${storage.size} registers given for ${plan.ops.size} operations"
    )
    if (size.nonEmpty || shape.nonEmpty) return size.toSeq ++ shape
    plan.ops.indices
      .filter(plan.produces)
      .groupBy(storage(_).get.register)
      .toSeq
      .sortBy(_._1)
      .flatMap { case (r, vs) =>
        vs.combinations(2).collect {
          case Seq(a, b)
              if lifetime(machine, plan, a)._1 <= lifetime(machine, plan, b)._2 &&
                lifetime(machine, plan, b)._1 <= lifetime(machine, plan, a)._2 =>
            s"values $a ${lifetime(machine, plan, a)} and $b ${lifetime(machine, plan, b)} overlap in register $r"
        }
      }
  }

  /** The register of every value (None for a store) under `policy`. */
  def bind(
      machine: MachineDescription,
      plan: FixedPlan,
      policy: StoragePolicy
  ): Vector[Option[Storage]] = {
    val ops = plan.ops
    def port(i: Int) = machine.operation(ops(i).mnemonic).writeback.get
    val values = ops.indices.filter(plan.produces)
    def from(i: Int) = lifetime(machine, plan, i)._1
    def until(i: Int) = lifetime(machine, plan, i)._2
    val assigned: Map[Int, Storage] = policy match {
      case StoragePolicy.Assigned(rs) =>
        values.flatMap(v => rs.lift(v).flatten.map(r => v -> Storage(r, port(v)))).toMap
      case StoragePolicy.PerValue =>
        values.zipWithIndex.map { case (v, r) => v -> Storage(r, port(v)) }.toMap
      case StoragePolicy.ByLifetime =>
        var next = 0
        values
          .groupBy(port)
          .toSeq
          .sortBy(_._1.name)
          .flatMap { case (p, vs) =>
            // Left edge: in order of the first cycle, reuse the lowest register free before it.
            val busyUntil =
              scala.collection.mutable.ArrayBuffer.empty[(Int, Int)] // (reg, last use)
            vs.sortBy(v => (from(v), v)).map { v =>
              val r = busyUntil.indexWhere(_._2 < from(v)) match {
                case -1 =>
                  busyUntil += ((next, until(v))); next += 1; busyUntil.last._1
                case k =>
                  busyUntil(k) = (busyUntil(k)._1, until(v)); busyUntil(k)._1
              }
              v -> Storage(r, p)
            }
          }
          .toMap
    }
    ops.indices.map(assigned.get).toVector
  }
}
