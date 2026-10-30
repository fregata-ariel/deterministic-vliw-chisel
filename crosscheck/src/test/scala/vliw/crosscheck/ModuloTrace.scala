package vliw.crosscheck

import vliw.compiler.loop.{Instance, LoopGraph, LoopNode, Place, Ref, VRef}
import vliw.compiler.modulo.MapEntry
import vliw.isa.Slot

/** The trace check of a modulo-scheduled loop (#68, docs/COMPILER.md, "Modulo Scheduling"). It
  * reads a `vliw-trace/1` trace (of the reference model or the RTL) against the compiler's table
  * (bundle, slot) -> (node, iteration) and the loop graph only: not the schedule, the registers or
  * the unrolled loop that produced the program (ADR-0002).
  *
  *   - the table names every node of every iteration once, in the slot of its operation
  *   - each issued operation has the node's mnemonic, other slots issue NOP (HALT last)
  *   - each LD, ST, LD.W and ST.W has the address of the node in its iteration (`addr.at(k)`)
  *   - each source field was last committed (in the rows up to the issue, as the trace shows values
  *     from the row of their commit) by the expected producer: the node of the operand in the same
  *     iteration, `back` iterations earlier, its initial value for k < back, the value before the
  *     loop, or the last iteration for a node after the loop. Sources are matched to the node's
  *     fields (ISA mvp-2): a pair field `rP:rP+1` needs both registers from the pair node, a
  *     broadcast is one register, a gather is P from its `lo` lane and P + 1 from its `hi` lane,
  *     and a lane ST reads P + lane of its pair node
  *   - a pair destination commits P and P + 1 on one port in one row
  *
  * Then each Each node issues every iteration the same number of cycles after the previous one;
  * that number is the II the trace shows, and it must be the one recorded.
  */
object ModuloTrace {

  /** A register field: one register, or the pair rP:rP+1. */
  private final case class Reg(r: Int, pair: Boolean) {
    def regs: Seq[Int] = if (pair) Seq(r, r + 1) else Seq(r)
  }

  private final case class Issued(
      mnemonic: String,
      dest: Option[Reg],
      sources: Vector[Reg],
      addr: Option[Int]
  )

  /** The text of `Disassembler`: `op dest, sources` or `st [a], source`; `[a]` is an address. */
  private def parse(text: String): Issued = {
    val (op, rest) = text.trim.span(_ != ' ')
    val args = rest.trim match {
      case ""   => Vector.empty
      case some => some.split(", ").toVector
    }
    val addr = args.collectFirst { case s"[$a]" => a.toInt }
    val regs = args.collect {
      case s"r$p:r$_" => Reg(p.toInt, pair = true)
      case s"r$r"     => Reg(r.toInt, pair = false)
    }
    val m = op.toUpperCase
    if (m == "ST" || m == "ST.W") Issued(m, None, regs, addr)
    else Issued(m, regs.headOption, regs.drop(1), addr)
  }

  /** What a source field of a node must hold. */
  private sealed trait Want
  private final case class One(ref: Ref) extends Want
  private final case class Both(ref: Ref) extends Want
  private final case class Lanes(lo: Ref, hi: Ref) extends Want
  private final case class Lane(ref: Ref, lane: Int) extends Want
  private def want(v: VRef): Want = v match {
    case VRef.Pair(r)        => Both(r)
    case VRef.Broadcast(r)   => One(r)
    case VRef.Gather(lo, hi) => Lanes(lo, hi)
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

      // The issuer of a register commit: the table's operation that writes `reg` (one of its pair)
      // and commits there.
      def issuer(row: Int, reg: Int, port: Slot): Option[Instance] = {
        val found = map.filter { e =>
          val op = m.operation(mnemonic(e.instance))
          op.writeback.contains(port) && e.bundle + op.latency == row &&
          issue(e.bundle, e.slot).dest.exists(_.regs.contains(reg))
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
        def at(a: vliw.compiler.loop.Address) = Some(a.at(k.getOrElse(0)))
        val node = g.nodes(e.instance.node)
        val (addr, fields) = node match {
          case LoopNode.Load(a)               => (at(a), Vector.empty)
          case LoopNode.Store(a, v)           => (at(a), Vector(One(v)))
          case LoopNode.Compute(_, a, b)      => (None, Vector(One(a), One(b)))
          case LoopNode.Mac(a, b, c)          => (None, Vector(One(a), One(b), One(c)))
          case LoopNode.LoadW(a)              => (at(a), Vector.empty)
          case LoopNode.StoreW(a, v)          => (at(a), Vector(want(v)))
          case LoopNode.StoreLane(a, p, lane) => (at(a), Vector(Lane(p, lane)))
          case n: LoopNode.VCompute           => (None, LoopNode.fields(n).map(want))
          case n: LoopNode.VMac               => (None, LoopNode.fields(n).map(want))
        }
        if (got.addr != addr) mismatches += s"$where: address ${got.addr}, expected $addr"
        def from(reg: Int, ref: Ref): Unit = producer(reg, e.bundle) match {
          case Left(why)                       => mismatches += s"$where: $why"
          case Right(p) if p != expect(ref, k) =>
            mismatches += s"$where: r$reg comes from $p, expected ${expect(ref, k)}"
          case _ => ()
        }
        if (got.sources.size != fields.size)
          mismatches += s"$where: ${got.sources.size} source fields, expected ${fields.size}"
        else
          fields.zip(got.sources).foreach {
            case (One(ref), Reg(r, false))                         => from(r, ref)
            case (Lane(ref, lane), Reg(r, false)) if r % 2 == lane => from(r, ref)
            case (Both(ref), Reg(p, true))                         => from(p, ref); from(p + 1, ref)
            case (Lanes(lo, hi), Reg(p, true))                     => from(p, lo); from(p + 1, hi)
            case (w, reg) => mismatches += s"$where: the field $reg does not hold $w"
          }
        // A pair destination commits both registers on the operation's port in one row.
        if (LoopNode.isPair(node)) got.dest.foreach { d =>
          val op = m.operation(mnemonic(e.instance))
          val row = e.bundle + op.latency
          val commits = Option
            .when(row < rows.size)(rows(row)("rf_commit").arr.toVector)
            .getOrElse(Vector.empty)
            .filter(c => op.writeback.exists(_.name == c("port").str))
            .map(_("reg").num.toInt)
          if (!d.pair || !d.regs.forall(commits.contains))
            mismatches += s"$where: the pair r${d.r} does not commit on one port at row $row"
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
