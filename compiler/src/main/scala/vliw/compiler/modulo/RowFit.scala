package vliw.compiler.modulo

import scala.collection.mutable

/** Whether operations can still get rows of a modulo reservation table (docs/COMPILER.md, "Modulo
  * Scheduling"), ignoring dependences. The operations of one slot class issue in distinct slot
  * rows; an operation at row r also takes its unit at rows r to r + II_op - 1 (L9) and, if it
  * writes a register, the class's write-back port at row r + L (L5), all modulo the loop's II.
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
    * the rows already taken (bit r for row r). Exact: a depth-first search that decides row by row
    * which mnemonic issues there, with the failed states remembered. None (not decided) when `ii` >
    * [[MaxRows]] or the search visits more than `budget` states.
    */
  def fits(
      ii: Int,
      kinds: Vector[Kind],
      slot: Long,
      port: Long,
      unit: Vector[Long],
      usePorts: Boolean = true,
      useUnits: Boolean = true,
      budget: Int = Budget
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
                  left(i) > 0 && ((unit(i) | takenUnit(i)) & um) == 0 &&
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
}
