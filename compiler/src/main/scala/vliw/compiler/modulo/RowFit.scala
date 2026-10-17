package vliw.compiler.modulo

import scala.collection.mutable

/** Whether operations can still get rows of a modulo reservation table (docs/COMPILER.md, "Modulo
  * Scheduling"), ignoring dependences. The operations of one slot class issue in distinct slot
  * rows; an operation at row r also takes its unit at rows r to r + II_op - 1 (L9) and, if it
  * writes a register, the class's write-back port at row r + L (L5), all modulo the loop's II. On a
  * machine with MAC and three read ports, MAC and ST also take the third read port at row r (L11).
  */
private[modulo] object RowFit {

  /** Operations of one mnemonic: latency, unit II, whether they use the write-back port, and how
    * many still need rows. Operations of one mnemonic are interchangeable.
    */
  final case class Kind(latency: Int, unitII: Int, port: Boolean, count: Int)

  /** Above this II the rows do not fit in a Long, and [[fits]] does not decide. */
  val MaxRows: Int = 62

  /** The states one call of [[fits]] may visit before it gives up (the search is exponential in the
    * worst case). The recorded loops need far fewer.
    */
  val Budget: Int = 100000

  /** Whether `kinds` (one slot class) fit rows 0 until `ii`, where `slot`, `port` and `unit(i)` are
    * the rows already taken (bit r for row r), and `blocked(i)` the rows where kind i may not issue
    * (the third read port, L11; none when `blocked` is empty). Exact: a depth-first search that
    * decides row by row which mnemonic issues there, with the failed states remembered. None (not
    * decided) when `ii` > [[MaxRows]] or the search visits more than `budget` states.
    */
  def fits(
      ii: Int,
      kinds: Vector[Kind],
      slot: Long,
      port: Long,
      unit: Vector[Long],
      usePorts: Boolean = true,
      useUnits: Boolean = true,
      budget: Int = Budget,
      blocked: Vector[Long] = Vector.empty
  ): Option[Boolean] =
    if (ii > MaxRows) None
    else if (kinds.forall(_.count == 0)) Some(true)
    else if (kinds.exists(k => k.count > 0 && k.unitII > ii)) Some(false)
    else {
      val full = (1L << ii) - 1
      def bit(r: Int) = 1L << Math.floorMod(r, ii)
      def units(k: Kind, r: Int) =
        if (useUnits) (0 until k.unitII).foldLeft(0L)((m, j) => m | bit(r + j)) else 0L
      def ports(k: Kind, r: Int) = if (usePorts && k.port) bit(r + k.latency) else 0L
      def issues(i: Int, r: Int) = blocked.isEmpty || (blocked(i) & bit(r)) == 0
      // A decision at row r reaches at most `reach` rows ahead, so of the rows decided so far only
      // rows r and later, and the rows 0 until `reach` that later rows wrap around to, still matter.
      val reach = kinds.map(k => k.latency.max(k.unitII)).max.min(ii)
      def relevant(r: Int) = (full & ~((1L << r) - 1)) | ((1L << reach) - 1)
      val left = kinds.map(_.count).toArray
      val failed = mutable.HashSet.empty[(Int, List[Int], Long, List[Long])]
      var steps = 0
      def go(r: Int, takenPort: Long, takenUnit: Array[Long]): Boolean = {
        steps += 1
        val total = left.sum
        val freeSlots = java.lang.Long.bitCount(~slot & full & ~((1L << r) - 1))
        if (total == 0) true
        else if (freeSlots < total || steps > budget) false
        else {
          val m = relevant(r)
          val key = (r, left.toList, takenPort & m, takenUnit.toList.map(_ & m))
          if (failed.contains(key)) false
          else {
            var ok = false
            if ((slot & bit(r)) == 0) {
              var i = 0
              while (!ok && i < kinds.size) {
                val (um, pm) = (units(kinds(i), r), ports(kinds(i), r))
                if (
                  left(i) > 0 && issues(i, r) && ((unit(i) | takenUnit(i)) & um) == 0 &&
                  ((port | takenPort) & pm) == 0
                ) {
                  left(i) -= 1
                  val before = takenUnit(i)
                  takenUnit(i) = before | um
                  ok = go(r + 1, takenPort | pm, takenUnit)
                  takenUnit(i) = before
                  left(i) += 1
                }
                i += 1
              }
            }
            if (!ok) ok = go(r + 1, takenPort, takenUnit) // the row stays empty
            if (!ok) failed += key
            ok
          }
        }
      }
      val found = go(0, 0L, Array.fill(kinds.size)(0L))
      Option.when(found || steps <= budget)(found)
    }

  /** The operations of one slot class for [[fitsTogether]]: their kinds, the rows already taken of
    * the slot, of the write-back port and of each kind's unit, and whether each kind reads through
    * the third read port.
    */
  final case class Class(
      kinds: Vector[Kind],
      slot: Long,
      port: Long,
      unit: Vector[Long],
      reads: Vector[Boolean]
  )

  /** Whether the operations of `classes` fit rows 0 until `ii` together, where the kinds that read
    * through the third read port (MAC and ST, L11) issue at distinct rows, and not at the rows
    * `readPort` already taken. The port couples the compute and memory classes, which [[fits]]
    * decides one at a time. Exact, like [[fits]]: the search decides row by row what each class
    * issues there, with the failed states remembered at the start of each row. None as for
    * [[fits]].
    */
  def fitsTogether(
      ii: Int,
      classes: Vector[Class],
      readPort: Long,
      usePorts: Boolean = true,
      useUnits: Boolean = true,
      budget: Int = Budget
  ): Option[Boolean] = {
    val kinds = classes.flatMap(_.kinds)
    if (ii > MaxRows) None
    else if (kinds.forall(_.count == 0)) Some(true)
    else if (kinds.exists(k => k.count > 0 && k.unitII > ii)) Some(false)
    else {
      val full = (1L << ii) - 1
      def bit(r: Int) = 1L << Math.floorMod(r, ii)
      def units(k: Kind, r: Int) =
        if (useUnits) (0 until k.unitII).foldLeft(0L)((m, j) => m | bit(r + j)) else 0L
      def ports(k: Kind, r: Int) = if (usePorts && k.port) bit(r + k.latency) else 0L
      val reach = kinds.map(k => k.latency.max(k.unitII)).max.min(ii)
      def relevant(r: Int) = (full & ~((1L << r) - 1)) | ((1L << reach) - 1)
      val left = classes.map(_.kinds.map(_.count).toArray)
      val takenPort = Array.fill(classes.size)(0L)
      val takenUnit = classes.map(c => Array.fill(c.kinds.size)(0L))
      val failed = mutable.HashSet.empty[(Int, List[List[Int]], List[Long], List[List[Long]])]
      var steps = 0
      // Row r from its start: the checks of `fits`, for every class.
      def row(r: Int): Boolean = {
        steps += 1
        val after = full & ~((1L << r) - 1)
        if (left.forall(_.sum == 0)) true
        else if (
          steps > budget ||
          classes.indices
            .exists(c => java.lang.Long.bitCount(~classes(c).slot & after) < left(c).sum)
        ) false
        else {
          val m = relevant(r)
          val key = (
            r,
            left.map(_.toList).toList,
            takenPort.toList.map(_ & m),
            takenUnit.map(_.toList.map(_ & m)).toList
          )
          if (failed.contains(key)) false
          else {
            val ok = choose(r, 0, (readPort & bit(r)) != 0)
            if (!ok) failed += key
            ok
          }
        }
      }
      // What class c issues at row r, if anything, then the classes after it; `read` when the third
      // read port of row r is taken.
      def choose(r: Int, c: Int, read: Boolean): Boolean =
        if (c == classes.size) row(r + 1)
        else {
          val cl = classes(c)
          var ok = false
          if ((cl.slot & bit(r)) == 0) {
            var i = 0
            while (!ok && i < cl.kinds.size) {
              val (um, pm) = (units(cl.kinds(i), r), ports(cl.kinds(i), r))
              if (
                left(c)(i) > 0 && !(read && cl.reads(i)) &&
                ((cl.unit(i) | takenUnit(c)(i)) & um) == 0 && ((cl.port | takenPort(c)) & pm) == 0
              ) {
                left(c)(i) -= 1
                val (unitBefore, portBefore) = (takenUnit(c)(i), takenPort(c))
                takenUnit(c)(i) = unitBefore | um
                takenPort(c) = portBefore | pm
                ok = choose(r, c + 1, read || cl.reads(i))
                takenUnit(c)(i) = unitBefore
                takenPort(c) = portBefore
                left(c)(i) += 1
              }
              i += 1
            }
          }
          if (!ok) ok = choose(r, c + 1, read) // class c leaves the row empty
          ok
        }
      val found = row(0)
      Option.when(found || steps <= budget)(found)
    }
  }
}
