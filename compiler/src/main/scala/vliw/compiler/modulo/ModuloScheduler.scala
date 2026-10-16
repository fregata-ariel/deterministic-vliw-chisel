package vliw.compiler.modulo

import scala.collection.mutable

import vliw.compiler.loop.{Instance, LoopGraph, Place}
import vliw.compiler.regalloc.{Allocation, RegisterAllocator}
import vliw.compiler.schedule.Schedule
import vliw.isa.Slot

/** What one candidate II gives (docs/COMPILER.md, "Modulo Scheduling"). */
sealed trait Outcome extends Product with Serializable
object Outcome {

  /** (a) No modulo reservation table exists: the operations of one iteration cannot get rows of the
    * slots, units (L9) and write-back ports (L5) in `ii` cycles. `resources` names the resources
    * that conflict.
    */
  final case class NoMrt(resources: String) extends Outcome {
    override def toString: String = s"no reservation table ($resources)"
  }

  /** Placing in node order without backtracking finds no cycle for `node` between `earliest` and
    * `latest` (from its dependences; Int.MaxValue for none) where the rest still fit. With
    * `tableExists`, a table exists (so this is the order of placement, not (a)); otherwise whether
    * one exists was not decided (more than `RowFit.MaxRows` rows, or the search ran out of budget).
    */
  final case class NoTime(node: Int, earliest: Int, latest: Int, tableExists: Boolean = true)
      extends Outcome {
    override def toString: String = {
      val upto = if (latest == Int.MaxValue) "" else latest.toString
      val table = if (tableExists) "" else " (whether a reservation table exists is not decided)"
      s"node $node finds no cycle in $earliest..$upto$table"
    }
  }

  /** (b) The schedule keeps more values live at once than there are registers. */
  final case class TooManyLive(live: Int, registers: Int) extends Outcome {
    override def toString: String = s"$live values live at once > $registers registers"
  }

  /** The unrolled program does not fit the instruction memory. */
  final case class Overflow(bundles: Int, capacity: Int) extends Outcome {
    override def toString: String =
      s"$bundles bundles; the instruction memory holds $capacity"
  }

  case object Fits extends Outcome {
    override def toString: String = "fits"
  }
}

final case class Attempt(ii: Int, outcome: Outcome)

/** One operation of the program: its bundle (= issue cycle in mvp-0) and slot, and the node and
  * iteration of the loop it comes from. The table that the trace check reads (#68).
  */
final case class MapEntry(bundle: Int, slot: Slot, instance: Instance)

/** A modulo-scheduled loop: iteration k of Each node n issues at `start + times(n) + k * ii`; the
  * Before and After nodes are placed around the iterations. `schedule` is the fully unrolled loop
  * (`graph.expanded`), with registers in `allocation`.
  */
final case class ModuloSchedule(
    graph: LoopGraph,
    mii: Mii,
    iiMax: Int,
    ii: Int,
    attempts: Vector[Attempt],
    times: Map[Int, Int],
    start: Int,
    allocation: Allocation
) {
  def schedule: Schedule = allocation.schedule
  def cycles: Int = schedule.cycles

  /** Cycles from the first to the last issue of one iteration's operations, plus the occupancy of
    * the last one (its latency, or its unit's II if longer).
    */
  def span: Int = ModuloScheduler.span(graph, times)

  /** Every issued operation, by bundle and slot. */
  lazy val map: Vector[MapEntry] = {
    val origin = graph.expanded.origin
    origin.indices
      .map(i => MapEntry(schedule.issue(i), ModuloScheduler.slot(graph, origin(i).node), origin(i)))
      .sortBy(e => (e.bundle, e.slot.name))
      .toVector
  }
}

/** The search found no II that gives a program; `attempts` says why for each II. */
final case class NoSchedule(mii: Mii, iiMax: Int, attempts: Vector[Attempt]) {
  def message: String =
    s"no II from ${mii.mii} to $iiMax gives a program (" +
      attempts.map(a => s"II ${a.ii}: ${a.outcome}").mkString("; ") + ")"
}

/** Iterative modulo scheduling (docs/COMPILER.md, "Modulo Scheduling", #68). */
object ModuloScheduler {

  private[modulo] def slot(g: LoopGraph, n: Int): Slot =
    g.machine.operation(g.nodes(n).mnemonic).slot

  private[modulo] def span(g: LoopGraph, t: Map[Int, Int]): Int = {
    val each = g.ids(Place.Each)
    def occupies(n: Int) = g.latency(n).max(g.machine.operation(g.nodes(n).mnemonic).ii)
    each.map(n => t(n) + occupies(n)).max - each.map(t).min
  }

  /** Resource ids of the reservation tables: the slots, the ports, then one unit per mnemonic. */
  private final class Resources(g: LoopGraph) {
    private val mnemonics = g.machine.operations.keys.toVector.sorted
    private def op(m: String) = g.machine.operation(m)
    private def slotId(s: Slot) = if (s == Slot.Compute) 0 else 1
    private def portId(s: Slot) = if (s == Slot.Compute) 2 else 3
    private def unitId(m: String) = 4 + mnemonics.indexOf(m)

    /** The (resource, cycle) pairs that an issue of `m` at `c` takes. */
    def uses(m: String, c: Int): Seq[(Int, Int)] =
      Seq((slotId(op(m).slot), c)) ++ (0 until op(m).ii).map(j => (unitId(m), c + j)) ++
        op(m).writeback.map(p => (portId(p), c + op(m).latency))

    /** The kinds of the remaining operations of slot class `s`, and the rows already taken. */
    def kinds(s: Slot, left: Seq[String]): Vector[(String, RowFit.Kind)] =
      left.groupBy(identity).toVector.sortBy(_._1).collect {
        case (m, ms) if op(m).slot == s =>
          m -> RowFit.Kind(op(m).latency, op(m).ii, op(m).writeback.isDefined, ms.size)
      }

    def fit(
        ii: Int,
        s: Slot,
        left: Seq[String],
        taken: collection.Set[(Int, Int)],
        usePorts: Boolean = true,
        useUnits: Boolean = true
    ): Option[Boolean] = {
      def mask(id: Int) = taken.collect { case (`id`, r) => 1L << r }.foldLeft(0L)(_ | _)
      val ks = kinds(s, left)
      RowFit.fits(
        ii,
        ks.map(_._2),
        mask(slotId(s)),
        mask(portId(s)),
        ks.map { case (m, _) => mask(unitId(m)) },
        usePorts,
        useUnits
      )
    }
  }

  /** One iteration alone, list-scheduled in node order (dependences within the iteration and the
    * resources at absolute cycles). Its span is the largest II worth trying: there no iteration
    * overlaps the next, so this schedule is a modulo schedule.
    */
  def oneIteration(g: LoopGraph): Map[Int, Int] = {
    val res = new Resources(g)
    val used = mutable.HashSet.empty[(Int, Int)]
    val t = mutable.LinkedHashMap.empty[Int, Int]
    g.ids(Place.Each).foreach { n =>
      val m = g.nodes(n).mnemonic
      val earliest = (0 +: g.edges.collect {
        case e if e.to == n && e.iterations == 0 && t.contains(e.from) => t(e.from) + e.cycles
      }).max
      val c = Iterator.from(earliest).find(c => res.uses(m, c).forall(!used.contains(_))).get
      used ++= res.uses(m, c)
      t(n) = c
    }
    t.toMap
  }

  /** Places the Each nodes in a reservation table of `ii` rows: in node order, each at the first
    * cycle from its earliest (dependences on the placed nodes) within `ii` cycles and before its
    * latest (dependences of placed nodes on it), whose rows are free; with `lookahead`, also only
    * where the remaining nodes of its slot class can still get rows (once the look-ahead is not
    * decided, the rest of the placement goes without it). No backtracking.
    */
  def place(g: LoopGraph, ii: Int, lookahead: Boolean): Either[Outcome.NoTime, Map[Int, Int]] = {
    val res = new Resources(g)
    var ahead = lookahead
    val rows = mutable.HashSet.empty[(Int, Int)]
    def row(u: (Int, Int)) = (u._1, Math.floorMod(u._2, ii))
    val each = g.ids(Place.Each)
    each.zipWithIndex.foldLeft[Either[Outcome.NoTime, Map[Int, Int]]](Right(Map.empty)) {
      case (done, (n, i)) =>
        done.flatMap { t =>
          val m = g.nodes(n).mnemonic
          val earliest = (0 +: g.edges.collect {
            case e if e.to == n && e.from != n && t.contains(e.from) =>
              t(e.from) + e.cycles - ii * e.iterations
          }).max
          val latest = g.edges
            .collect {
              case e if e.from == n && e.to != n && t.contains(e.to) =>
                t(e.to) - e.cycles + ii * e.iterations
            }
            .minOption
            .getOrElse(Int.MaxValue)
          val rest = each.drop(i + 1).map(g.nodes(_).mnemonic)
          (earliest to latest.min(earliest + ii - 1))
            .find { c =>
              val need = res.uses(m, c).map(row)
              need.distinct.size == need.size && need.forall(!rows.contains(_)) &&
              (!ahead || res.fit(ii, slot(g, n), rest, rows ++ need).getOrElse {
                ahead = false
                true
              })
            }
            .toRight(Outcome.NoTime(n, earliest, latest))
            .map { c =>
              rows ++= res.uses(m, c).map(row)
              t.updated(n, c)
            }
        }
    }
  }

  /** Whether the Each nodes can get rows at all. Left (a): the resources that conflict, found by
    * leaving out the ports and the units. Right(true): a table exists; Right(false): not decided.
    */
  private def table(g: LoopGraph, ii: Int): Either[Outcome.NoMrt, Boolean] = {
    val res = new Resources(g)
    val all = g.ids(Place.Each).map(g.nodes(_).mnemonic)
    def fits(ports: Boolean, units: Boolean): Option[Boolean] =
      Slot.all.map(s => res.fit(ii, s, all, Set.empty, ports, units)).foldLeft(Option(true)) {
        case (Some(false), _) | (_, Some(false)) => Some(false)
        case (Some(true), Some(true))            => Some(true)
        case _                                   => None
      }
    fits(ports = true, units = true) match {
      case Some(false) =>
        def not(ports: Boolean, units: Boolean) = fits(ports, units).contains(false)
        Left(
          Outcome.NoMrt(
            if (not(ports = false, units = false)) "slot"
            else if (not(ports = true, units = false)) "slot + write-back port (L5)"
            else if (not(ports = false, units = true)) "slot + unit (L9)"
            else "slot + unit (L9) + write-back port (L5)"
          )
        )
      case decided => Right(decided.isDefined)
    }
  }

  /** The fully unrolled loop: Each instances at `times(n) + k * ii`; Before nodes as late as their
    * consumers allow (in reverse node order) and After nodes as early as their producers allow,
    * each at a cycle whose slot, unit (both ways, L9) and write-back port are free; then all
    * shifted so that the first issue is cycle 0. Returns the issue of each instance, the start of
    * iteration 0 and HALT.
    */
  def expand(g: LoopGraph, ii: Int, times: Map[Int, Int]): (Vector[Int], Int, Int) = {
    val res = new Resources(g)
    val dag = g.expanded.dag
    val origin = g.expanded.origin
    val issue = Array.fill(dag.nodes.size)(Int.MinValue)
    val used = mutable.HashSet.empty[(Int, Int)]
    def take(i: Int, c: Int): Unit = {
      val need = res.uses(dag.nodes(i).mnemonic, c)
      assert(need.forall(!used.contains(_)), s"instance ${origin(i)} at cycle $c collides")
      used ++= need
      issue(i) = c
    }
    def free(i: Int, c: Int) = res.uses(dag.nodes(i).mnemonic, c).forall(!used.contains(_))
    val (each, others) = origin.indices.partition(i => origin(i).iteration.isDefined)
    each.foreach(i => take(i, times(origin(i).node) + origin(i).iteration.get * ii))
    val firstEach = each.map(issue).min
    others.filter(i => g.places(origin(i).node) == Place.Before).reverse.foreach { i =>
      val latest = dag
        .successors(i)
        .filter(e => issue(e.to) != Int.MinValue)
        .map(e => issue(e.to) - e.distance)
        .minOption
        .getOrElse(firstEach - 1)
      take(i, Iterator.iterate(latest)(_ - 1).find(free(i, _)).get)
    }
    others.filter(i => g.places(origin(i).node) == Place.After).foreach { i =>
      val earliest = dag.predecessors(i).map(e => issue(e.from) + e.distance).maxOption.getOrElse(0)
      take(i, Iterator.from(earliest).find(free(i, _)).get)
    }
    val shift = -issue.min
    val shifted = issue.toVector.map(_ + shift)
    val halt = dag.nodes.indices.map(i => shifted(i) + dag.latency(i)).max
    (shifted, shift, halt)
  }

  /** Tries II = MII, MII + 1, ... up to the span of one iteration alone; the first II whose
    * placement gives a program that fits the registers and the instruction memory wins.
    */
  def schedule(g: LoopGraph): Either[NoSchedule, ModuloSchedule] = search(g, place)

  /** [[schedule]] with the placement given, so that a test can make it fail. */
  private[modulo] def search(
      g: LoopGraph,
      placer: (LoopGraph, Int, Boolean) => Either[Outcome.NoTime, Map[Int, Int]]
  ): Either[NoSchedule, ModuloSchedule] = {
    require(g.ids(Place.Each).nonEmpty, "the loop has no operation in its iterations")
    val mii = Mii.of(g)
    val one = oneIteration(g)
    val iiMax = span(g, one)
    assert(mii.mii <= iiMax, s"MII ${mii.mii} > the span of one iteration $iiMax")
    val attempts = Vector.newBuilder[Attempt]
    val capacity = g.machine.instructionMemoryBundles
    def attempt(ii: Int): Either[Outcome, ModuloSchedule] = {
      val placed: Either[Outcome, Map[Int, Int]] = table(g, ii) match {
        case Left(no)      => Left(no)
        case Right(exists) =>
          // The look-ahead only changes a placement that fails without it, so it runs only then.
          placer(g, ii, false).left.flatMap(_ => placer(g, ii, true)) match {
            case Left(_) if ii == iiMax => Right(one)
            case Left(no)               => Left(no.copy(tableExists = exists))
            case Right(times)           => Right(times)
          }
      }
      placed.flatMap { times =>
        val (issue, start, halt) = expand(g, ii, times)
        val s = Schedule(g.expanded.dag, issue, halt)
        if (halt + 1 > capacity) Left(Outcome.Overflow(halt + 1, capacity))
        else
          RegisterAllocator
            .allocate(s)
            .left
            .map(_ => Outcome.TooManyLive(RegisterAllocator.maxLive(s), g.machine.registerCount))
            .map(a => ModuloSchedule(g, mii, iiMax, ii, Vector.empty, times, start, a))
      }
    }
    (mii.mii to iiMax).iterator
      .map { ii =>
        val result = attempt(ii)
        attempts += Attempt(ii, result.fold(identity, _ => Outcome.Fits))
        result
      }
      .collectFirst { case Right(done) => done.copy(attempts = attempts.result()) }
      .toRight(NoSchedule(mii, iiMax, attempts.result()))
  }
}
