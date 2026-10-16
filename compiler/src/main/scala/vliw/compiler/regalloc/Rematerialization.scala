package vliw.compiler.regalloc

import scala.annotation.tailrec

import vliw.compiler.dag.Dag
import vliw.compiler.schedule.{ListScheduler, Priority, Schedule}

/** When a limited attempt may issue LDs again (#70). */
sealed abstract class Remat(val name: String) extends Product with Serializable
object Remat {

  /** Every word is read once (the DAG's sharing). */
  case object Never extends Remat("never")

  /** Only if the attempt gets stuck with every word read once; then as [[Always]]. */
  case object WhenStuck extends Remat("when stuck")

  /** At every block of the register limit, from the start. */
  case object Always extends Remat("always")
}

/** Rematerialization (#70; docs/COMPILER.md, "register allocation"): wherever the register limit
  * blocks a slot, a LD of a word that no store writes gets a copy for its readers not placed yet;
  * the original's register is then free after its last placed reader. Each copy changes the DAG,
  * and the attempt starts again on the new one.
  */
object Rematerialization {

  /** The LDs that a copy can free at `at`: placed, of a word that no store writes, with readers
    * both placed and not placed.
    */
  def candidates(dag: Dag, at: ListScheduler.Stopped): Vector[Int] =
    dag.nodes.indices.filter { v =>
      dag.rematerializable(v) && at.placed(v) && dag.readers(v).exists(at.placed) &&
      dag.readers(v).exists(r => !at.placed(r))
    }.toVector

  /** The candidate whose next use is furthest: whose first reader by `priority` among those not
    * placed comes last. Ties go to the lower id.
    */
  def choose(dag: Dag, priority: Priority, at: ListScheduler.Stopped): Option[Int] =
    candidates(dag, at).minByOption { v =>
      (dag.readers(v).filterNot(at.placed).map(priority.key(dag, _)).max, v)
    }

  /** The schedule of attempt `a` with rematerialization `when` (the schedule's DAG holds the
    * copies), or Stuck when no copy helps. Without a limit, `when` does nothing.
    */
  def schedule(dag: Dag, a: Attempt, when: Remat = Remat.WhenStuck): Either[NoFit, Schedule] = {
    def plain =
      ListScheduler.attempt(dag, a.priority, a.limit, a.singleIssue).left.map(_ => NoFit.Stuck)
    // Terminates: every copy adds a LD, and each word has at most as many LDs as readers;
    // between copies, `after` only grows and stays below the cycle the attempt stops at.
    @tailrec
    def go(d: Dag, after: Int): Either[NoFit, Schedule] =
      ListScheduler.attempt(d, a.priority, a.limit, a.singleIssue, Some(after)) match {
        case Right(s) => Right(s)
        case Left(at) =>
          choose(d, a.priority, at) match {
            case Some(v) => go(d.split(v, d.readers(v).filterNot(at.placed).toSet), at.cycle - 1)
            case None if at.blocked => go(d, at.cycle)
            case None               => Left(NoFit.Stuck)
          }
      }
    (a.limit, when) match {
      case (None, _) | (_, Remat.Never) => plain
      case (_, Remat.WhenStuck)         => plain.left.flatMap(_ => go(dag, -1))
      case (_, Remat.Always)            => go(dag, -1)
    }
  }
}
