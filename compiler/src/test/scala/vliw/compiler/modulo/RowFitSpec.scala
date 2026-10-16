package vliw.compiler.modulo

import scala.util.Random

import org.scalatest.funsuite.AnyFunSuite

import RowFit.Kind

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
  }

  test("RowFit does not decide above 62 rows or beyond its budget") {
    assert(RowFit.fits(63, Vector(Kind(1, 1, port = true, 1)), 0, 0, Vector(0)).isEmpty)
    val kinds =
      Vector(Kind(1, 1, port = true, 12), Kind(5, 1, port = true, 12), Kind(8, 1, port = true, 12))
    assert(RowFit.fits(36, kinds, 0, 0, Vector(0, 0, 0), budget = 1000).isEmpty)
  }
}
