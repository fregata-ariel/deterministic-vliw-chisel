package vliw.crosscheck

import vliw.compiler.loop.{Instance, LoopGraph, LoopNode, Place, Ref}
import vliw.compiler.modulo.MapEntry
import vliw.isa.Slot

/** The trace check of a modulo-scheduled loop (#68, docs/COMPILER.md, "Modulo Scheduling"). It
  * reads a `vliw-trace/1` trace (of the reference model or the RTL) against the compiler's table
  * (bundle, slot) -> (node, iteration) and the loop graph only: not the schedule, the registers or
  * the unrolled loop that produced the program (ADR-0002).
  *
  *   - the table names every node of every iteration once, in the slot of its operation
  *   - each issued operation has the node's mnemonic, other slots issue NOP (HALT last)
  *   - each LD and ST has the address of the node in its iteration (`addr.at(k)`)
  *   - each source register was last committed (in the rows up to the issue, as the trace shows
  *     values from the row of their commit) by the expected producer: the node of the operand in
  *     the same iteration, `back` iterations earlier, its initial value for k < back, the value
  *     before the loop, or the last iteration for a node after the loop
  *
  * Then each Each node issues every iteration the same number of cycles after the previous one;
  * that number is the II the trace shows, and it must be the one recorded.
  */
object ModuloTrace {

  private final case class Issued(
      mnemonic: String,
      dest: Option[Int],
      sources: Vector[Int],
      addr: Option[Int]
  )

  private def parse(text: String): Issued = text.trim match {
    case s"ld r$d, [$a]"     => Issued("LD", Some(d.toInt), Vector.empty, Some(a.toInt))
    case s"st [$a], r$s"     => Issued("ST", None, Vector(s.toInt), Some(a.toInt))
    case s"$m r$d, r$a, r$b" => Issued(m.toUpperCase, Some(d.toInt), Vector(a.toInt, b.toInt), None)
    case other               => Issued(other.toUpperCase, None, Vector.empty, None)
  }

  /** Mismatches, or the II that the trace shows (None with one iteration). */
  def check(
      g: LoopGraph,
      map: Vector[MapEntry],
      rows: Vector[ujson.Value],
      ii: Int
  ): Either[Vector[String], Option[Int]] = {
    val m = g.machine
    def mnemonic(i: Instance) = g.nodes(i.node).mnemonic
    def slotOf(i: Instance) = m.operation(mnemonic(i)).slot
    val expected = g.ids(Place.Before).map(Instance(_, None)) ++
      g.ids(Place.Each).flatMap(n => (0 until g.trips).map(k => Instance(n, Some(k)))) ++
      g.ids(Place.After).map(Instance(_, None))
    val shape = Vector(
      Option.when(map.map(_.instance).toSet != expected.toSet || map.size != expected.size)(
        "the table does not name every node of every iteration once"
      ),
      Option.when(map.map(e => (e.bundle, e.slot)).distinct.size != map.size)(
        "two operations share a bundle and slot"
      ),
      map.find(e => e.slot != slotOf(e.instance)).map(e => s"${e.instance} is not in its slot")
    ).flatten
    if (shape.nonEmpty) Left(shape)
    else {
      val at = map.map(e => (e.bundle, e.slot) -> e).toMap
      def issue(row: Int, s: Slot) = parse(rows(row)("issue")(s.name).str)
      val issued = rows.indices.filter(r => !rows(r)("issue").isNull)
      val halt = issued.last
      val mismatches = Vector.newBuilder[String]

      // Slots: the node's mnemonic where the table has one, NOP elsewhere, HALT last.
      for (r <- issued; s <- Slot.all) {
        val got = issue(r, s).mnemonic
        val want = at.get((r, s)).map(e => mnemonic(e.instance)).getOrElse {
          if (r == halt && s == Slot.Compute) "HALT" else "NOP"
        }
        if (got != want) mismatches += s"row $r ${s.name}: expected $want, issued $got"
      }

      // The issuer of a register commit: the table's operation that writes `reg` and commits there.
      def issuer(row: Int, reg: Int, port: Slot): Option[Instance] = {
        val found = map.filter { e =>
          val op = m.operation(mnemonic(e.instance))
          op.writeback.contains(port) && e.bundle + op.latency == row &&
          issue(e.bundle, e.slot).dest.contains(reg)
        }
        Option.when(found.size == 1)(found.head.instance)
      }
      def producer(reg: Int, row: Int): Either[String, Instance] =
        (row to 0 by -1).iterator
          .flatMap(r => rows(r)("rf_commit").arr.find(_("reg").num.toInt == reg).map(r -> _))
          .nextOption()
          .toRight(s"r$reg has no commit up to row $row")
          .flatMap { case (r, c) =>
            val port = if (c("port").str == Slot.Compute.name) Slot.Compute else Slot.Memory
            issuer(r, reg, port).toRight(s"the commit to r$reg at row $r has no single issuer")
          }
      def expect(ref: Ref, k: Option[Int]): Instance = g.places(ref.node) match {
        case Place.Each =>
          k match {
            case Some(i) if i >= ref.back => Instance(ref.node, Some(i - ref.back))
            case Some(i)                  => Instance(ref.initial(i), None)
            case None                     => Instance(ref.node, Some(g.trips - 1 - ref.back))
          }
        case _ => Instance(ref.node, None)
      }

      map.foreach { e =>
        val got = issue(e.bundle, e.slot)
        val k = e.instance.iteration
        val where = s"row ${e.bundle} ${e.slot.name} (${e.instance})"
        val (addr, operands) = g.nodes(e.instance.node) match {
          case LoopNode.Load(a)          => (Some(a.at(k.getOrElse(0))), Vector.empty)
          case LoopNode.Store(a, v)      => (Some(a.at(k.getOrElse(0))), Vector(v))
          case LoopNode.Compute(_, a, b) => (None, Vector(a, b))
        }
        if (got.addr != addr) mismatches += s"$where: address ${got.addr}, expected $addr"
        if (got.sources.size == operands.size)
          operands.zip(got.sources).foreach { case (ref, reg) =>
            producer(reg, e.bundle) match {
              case Left(why)                       => mismatches += s"$where: $why"
              case Right(p) if p != expect(ref, k) =>
                mismatches += s"$where: r$reg comes from $p, expected ${expect(ref, k)}"
              case _ => ()
            }
          }
      }

      val done = mismatches.result()
      if (done.nonEmpty) Left(done)
      else {
        val bundle = map.map(e => e.instance -> e.bundle).toMap
        val gaps = g
          .ids(Place.Each)
          .flatMap { n =>
            (1 until g.trips)
              .map(k => bundle(Instance(n, Some(k))) - bundle(Instance(n, Some(k - 1))))
          }
          .distinct
        gaps match {
          case Vector()             => Right(None)
          case Vector(d) if d == ii => Right(Some(d))
          case other                =>
            Left(Vector(s"iterations start ${other.mkString(", ")} cycles apart, not $ii"))
        }
      }
    }
  }
}
