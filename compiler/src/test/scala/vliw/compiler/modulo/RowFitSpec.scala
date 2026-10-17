package vliw.compiler.modulo

import scala.util.Random

import org.scalatest.funsuite.AnyFunSuite

import RowFit.{Class, Kind}

class RowFitSpec extends AnyFunSuite {

  /** Whether `kinds` fit, by trying every row for every operation (the same rules as RowFit). */
  private def brute(
      ii: Int,
      kinds: Vector[Kind],
      slot: Long,
      port: Long,
      unit: Vector[Long]
  ): Boolean = {
    def bit(r: Int) = 1L << Math.floorMod(r, ii)
    val ops = kinds.indices.flatMap(i => Seq.fill(kinds(i).count)(i))
    def go(
        left: List[Int],
        slots: Long,
        ports: Long,
        units: Vector[Long],
        from: Map[Int, Int]
    ): Boolean =
      left match {
        case Nil       => true
        case i :: rest =>
          val k = kinds(i)
          (from.getOrElse(i, 0) until ii).exists { r =>
            val um = (0 until k.unitII).foldLeft(0L)((m, j) => m | bit(r + j))
            val pm = if (k.port) bit(r + k.latency) else 0L
            ((slot | slots) & bit(r)) == 0 && ((unit(i) | units(i)) & um) == 0 &&
            ((port | ports) & pm) == 0 &&
            go(
              rest,
              slots | bit(r),
              ports | pm,
              units.updated(i, units(i) | um),
              from.updated(i, r + 1)
            )
          }
      }
    kinds.forall(k => k.count == 0 || k.unitII <= ii) && go(
      ops.toList,
      0L,
      0L,
      Vector.fill(kinds.size)(0L),
      Map.empty
    )
  }

  test("RowFit agrees with trying every row, on random tables with rows already taken") {
    val rng = new Random(68)
    // (latency, unit II) of ADD/SUB, MUL and LD on the three machines.
    val ops = Seq((1, 1), (2, 1), (3, 1), (1, 2), (2, 2), (2, 3), (8, 1))
    (0 until 3000).foreach { n =>
      val ii = 2 + rng.nextInt(7)
      val kinds = Vector.fill(1 + rng.nextInt(3)) {
        val (l, u) = ops(rng.nextInt(ops.size))
        Kind(l, u, rng.nextInt(4) != 0, 1 + rng.nextInt(3))
      }
      def mask = (0 until ii).foldLeft(0L)((m, r) => if (rng.nextInt(4) == 0) m | (1L << r) else m)
      val (slot, port, unit) = (mask, mask, kinds.map(_ => mask))
      assert(
        RowFit.fits(ii, kinds, slot, port, unit) == Some(brute(ii, kinds, slot, port, unit)),
        s"case $n: $ii $kinds $slot $port $unit"
      )
    }
  }

  test("RowFit: the ports and units already taken count") {
    // Three ADD and two MUL in 7 rows, rows 1 and 3 taken and port row 1 taken: they fit.
    assert(
      RowFit.fits(
        7,
        Vector(Kind(1, 1, port = true, 3), Kind(2, 1, port = true, 2)),
        0xa,
        0x2,
        Vector(0, 0)
      ) == Some(true)
    )
    // One SUB of unit II 2 in 4 rows: rows 2 and 3 taken, port row 2 taken, unit rows 0 and 2 taken.
    assert(RowFit.fits(4, Vector(Kind(1, 2, port = true, 1)), 0xc, 0x4, Vector(0x5)) == Some(false))
    // One ADD in 2 rows, row 1 taken: at row 0 it writes back at port row 1, so the taken port row
    // alone decides.
    assert(RowFit.fits(2, Vector(Kind(1, 1, port = true, 1)), 0x2, 0x2, Vector(0)) == Some(false))
    assert(RowFit.fits(2, Vector(Kind(1, 1, port = true, 1)), 0x2, 0x0, Vector(0)) == Some(true))
    // One operation without a port in 3 rows, unit rows 1 and 2 taken: only row 0 is left, and the
    // taken slot row alone decides.
    assert(RowFit.fits(3, Vector(Kind(1, 1, port = false, 1)), 0x1, 0, Vector(0x6)) == Some(false))
    assert(RowFit.fits(3, Vector(Kind(1, 1, port = false, 1)), 0x0, 0, Vector(0x6)) == Some(true))
  }

  test("RowFit does not decide above 62 rows or beyond its budget") {
    assert(RowFit.fits(63, Vector(Kind(1, 1, port = true, 1)), 0, 0, Vector(0)).isEmpty)
    val kinds =
      Vector(Kind(1, 1, port = true, 12), Kind(5, 1, port = true, 12), Kind(8, 1, port = true, 12))
    assert(RowFit.fits(36, kinds, 0, 0, Vector(0, 0, 0), budget = 1000).isEmpty)
    val together = Vector(Class(kinds, 0, 0, Vector(0, 0, 0), Vector(true, false, false)))
    assert(RowFit.fitsTogether(63, together, 0).isEmpty)
    assert(RowFit.fitsTogether(36, together, 0, budget = 1000).isEmpty)
  }

  test("RowFit: a kind may not issue at its blocked rows") {
    // One MAC in 3 rows, compute row 0 taken and the third read port taken at row 1 (a ST there).
    val mac = Vector(Kind(2, 1, port = true, 1))
    assert(RowFit.fits(3, mac, 0x1, 0, Vector(0), blocked = Vector(0x2)) == Some(true))
    assert(RowFit.fits(3, mac, 0x1, 0, Vector(0), blocked = Vector(0x6)) == Some(false))
    assert(RowFit.fits(3, mac, 0x1, 0, Vector(0)) == Some(true))
  }

  /** Whether `classes` fit together, by trying every row for every operation: the rules of `brute`
    * in each class, and the kinds that read through the third read port at distinct rows, none at a
    * row of `readPort`.
    */
  private def bruteTogether(ii: Int, classes: Vector[Class], readPort: Long): Boolean = {
    def bit(r: Int) = 1L << Math.floorMod(r, ii)
    val ops =
      for (
        c <- classes.indices; i <- classes(c).kinds.indices; _ <- 0 until classes(c).kinds(i).count
      )
        yield (c, i)
    def go(
        left: List[(Int, Int)],
        slots: Vector[Long],
        ports: Vector[Long],
        units: Map[(Int, Int), Long],
        reads: Long,
        from: Map[(Int, Int), Int]
    ): Boolean = left match {
      case Nil            => true
      case (c, i) :: rest =>
        val (cl, k) = (classes(c), classes(c).kinds(i))
        (from.getOrElse((c, i), 0) until ii).exists { r =>
          val um = (0 until k.unitII).foldLeft(0L)((m, j) => m | bit(r + j))
          val pm = if (k.port) bit(r + k.latency) else 0L
          val rm = if (cl.reads(i)) bit(r) else 0L
          ((cl.slot | slots(c)) & bit(r)) == 0 && ((cl.unit(i) | units((c, i))) & um) == 0 &&
          ((cl.port | ports(c)) & pm) == 0 && ((readPort | reads) & rm) == 0 &&
          go(
            rest,
            slots.updated(c, slots(c) | bit(r)),
            ports.updated(c, ports(c) | pm),
            units.updated((c, i), units((c, i)) | um),
            reads | rm,
            from.updated((c, i), r + 1)
          )
        }
    }
    classes.forall(_.kinds.forall(k => k.count == 0 || k.unitII <= ii)) && go(
      ops.toList,
      classes.map(_ => 0L),
      classes.map(_ => 0L),
      ops.map(_ -> 0L).toMap,
      0L,
      Map.empty
    )
  }

  test("RowFit decides two slot classes that share the third read port as trying every row does") {
    val rng = new Random(77)
    val ops = Seq((1, 1), (2, 1), (3, 1), (1, 2), (2, 2), (2, 3))
    (0 until 1000).foreach { n =>
      val ii = 2 + rng.nextInt(4)
      def mask = (0 until ii).foldLeft(0L)((m, r) => if (rng.nextInt(4) == 0) m | (1L << r) else m)
      val classes = Vector.fill(2) {
        val kinds = Vector.fill(1 + rng.nextInt(2)) {
          val (l, u) = ops(rng.nextInt(ops.size))
          Kind(l, u, rng.nextInt(4) != 0, 1 + rng.nextInt(2))
        }
        Class(kinds, mask, mask, kinds.map(_ => mask), kinds.map(_ => rng.nextBoolean()))
      }
      val readPort = mask
      assert(
        RowFit.fitsTogether(ii, classes, readPort) == Some(bruteTogether(ii, classes, readPort)),
        s"case $n: $ii $classes $readPort"
      )
    }
  }

  test("RowFit: the third read port couples two classes that fit one at a time") {
    // A MAC (compute row 1 taken) and a ST (memory row 1 taken) in 2 rows: each fits alone at row
    // 0, but they cannot share its third read port.
    val mac = Class(Vector(Kind(2, 1, port = true, 1)), 0x2, 0, Vector(0), Vector(true))
    val st = Class(Vector(Kind(1, 1, port = false, 1)), 0x2, 0, Vector(0), Vector(true))
    assert(RowFit.fits(2, mac.kinds, mac.slot, 0, Vector(0)) == Some(true))
    assert(RowFit.fits(2, st.kinds, st.slot, 0, Vector(0)) == Some(true))
    assert(RowFit.fitsTogether(2, Vector(mac, st), 0) == Some(false))
    // An LD in place of the ST does not read through the port.
    val ld = Class(Vector(Kind(2, 1, port = true, 1)), 0x2, 0, Vector(0), Vector(false))
    assert(RowFit.fitsTogether(2, Vector(mac, ld), 0) == Some(true))
    // With the memory row free, the ST takes row 1.
    assert(RowFit.fitsTogether(2, Vector(mac, st.copy(slot = 0)), 0) == Some(true))
    // The port already taken at row 1 leaves the ST nowhere.
    assert(RowFit.fitsTogether(2, Vector(mac, st.copy(slot = 0)), 0x2) == Some(false))
  }
}
