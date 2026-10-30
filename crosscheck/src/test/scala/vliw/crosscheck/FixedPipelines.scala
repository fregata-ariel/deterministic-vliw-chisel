package vliw.crosscheck

import vliw.compiler.{CompiledKernel, KernelCompiler}
import vliw.compiler.dag.{Dag, Node}
import vliw.compiler.dsl.{Kernel, Values}
import vliw.compiler.schedule.{ListScheduler, Priority, Schedule}
import vliw.hw.{FixedOp, FixedParams, FixedPlan, FixedRun, StoragePolicy}
import vliw.isa.{MachineDescription, Slot}

/** Fixed pipelines (#75) of the DSL kernels: the kernel's layout (`MemoryLayout.fixedOrder`) and
  * DAG, list-scheduled under the machine's slots, write-back ports, latencies and IIs, as the plan
  * of a [[vliw.hw.FixedPipeline]]. Two series per kernel: the kernel's VLIW program burnt in (the
  * same schedule and registers), and the same list scheduler without a register limit.
  */
object FixedPipelines {

  /** Where a plan comes from. */
  sealed trait Strategy extends Product with Serializable
  object Strategy {

    /** A priority of the compiler's list scheduler without a register limit. */
    final case class NoLimit(priority: Priority) extends Strategy {
      override def toString: String = s"${priority.name}, no register limit"
    }

    /** The schedule and the register allocation of the kernel's VLIW program. */
    final case class Vliw(attempt: vliw.compiler.regalloc.Attempt) extends Strategy {
      override def toString: String = s"the VLIW program's schedule and registers ($attempt)"
    }
  }

  /** The plan of `kernel` with the schedule it came from. `cycles` is the core latency, counted as
    * on the Core: done is high in cycle T + 1, where E_T is the boundary of the last write.
    */
  final case class Planned(
      prepared: KernelCompiler.Prepared,
      strategy: Strategy,
      schedule: Schedule,
      plan: FixedPlan,
      policy: StoragePolicy = StoragePolicy.ByLifetime
  ) {
    def kernel: Kernel = prepared.kernel
    def machine: MachineDescription = prepared.machine
    lazy val params: FixedParams = FixedParams(machine, plan, policy)
    def cycles: Int = params.cycles
  }

  /** The DAG's nodes with their issue cycles; value ids are node ids. */
  def plan(name: String, dag: Dag, issue: Int => Int): FixedPlan =
    FixedPlan(
      name,
      dag.nodes.zipWithIndex.map {
        case (Node.Load(addr), i)        => FixedOp.Load(issue(i), addr)
        case (Node.Compute(op, a, b), i) => FixedOp.Compute(issue(i), op.mnemonic, a, b)
        case (Node.Store(addr, v), i)    => FixedOp.Store(issue(i), addr, v)
        case (_: Node.Mac, _)            =>
          sys.error(s"$name: the fixed pipelines (#75) are built on mvp-0 and have no MAC")
        case (other, _) =>
          sys.error(s"$name: $other: the fixed pipelines (#75) are for mvp-0 and mvp-1 only")
      }
    )

  /** The plans without a register limit, in the order of the compiler's priorities. */
  def attempts(p: KernelCompiler.Prepared): Seq[Planned] =
    Priority.all.map { pr =>
      val s = ListScheduler.schedule(p.dag, pr)
      Planned(p, Strategy.NoLimit(pr), s, plan(p.kernel.name, p.dag, s.issue(_)))
    }

  /** A lower bound of the cycles (T + 1, as [[vliw.hw.FixedParams.cycles]]) of any legal plan of
    * `dag` (one compute and one memory slot, one commit per port and boundary), with the reason
    * that sets it. Bounds of the boundary T of the last write:
    *   - memory: M operations issue in distinct cycles, the last at M-1 or later;
    *   - compute: the first compute operation reads loads only, so it issues at L(LD) or later (1 +
    *     L(LD) when it reads two words); C operations take C distinct cycles; the last one is read
    *     only by stores, so a ST follows it;
    *   - L5: if the operations that can issue first all have latency l+1 and those that can be last
    *     all have latency l, somewhere an (l+1) is followed by an l, and not in the next cycle
    *     (both would write at one boundary): one compute cycle stays empty.
    */
  def lowerBound(dag: Dag): (Int, String) = {
    def lat(m: String) = dag.machine.operation(m).latency
    val n = dag.nodes.indices
    val compute = n.filter(i => ListScheduler.slot(dag.nodes(i)) == Slot.Compute)
    val memory = n.filterNot(compute.contains)
    val mem = memory.size - 1 + memory.map(dag.latency).min
    if (compute.isEmpty) (mem + 1, "memory")
    else {
      def operands(i: Int) = dag.nodes(i).operands.distinct
      val first = compute.filter(i => operands(i).forall(o => dag.nodes(o).isInstanceOf[Node.Load]))
      val last =
        compute.filter(i => dag.readers(i).forall(r => dag.nodes(r).isInstanceOf[Node.Store]))
      val start = first.map(i => operands(i).size - 1 + lat("LD")).min
      val (f, l) = (first.map(dag.latency).toSet, last.map(dag.latency).toSet)
      val bubble = if (f.size == 1 && l.size == 1 && f.head == l.head + 1) 1 else 0
      val tail = last.map(dag.latency).minOption.fold(1)(_ + lat("ST"))
      val comp = start + compute.size - 1 + bubble + tail
      if (comp > mem) (comp + 1, if (bubble > 0) "compute + L5" else "compute")
      else (mem + 1, "memory")
    }
  }

  /** The VLIW program of `c` as a plan: its schedule (of its DAG, with the LD copies of a
    * rematerializing attempt) and its registers, so that the circuit is that program without
    * instruction memory, decoder and register file; it takes `c.cycles`.
    */
  def burntIn(c: CompiledKernel): Planned = {
    val a = c.allocated
    val dag = a.dag
    val registers = dag.nodes.indices.map { i =>
      if (dag.nodes(i).isInstanceOf[Node.Store]) None else a.allocation.register.get(i)
    }.toVector
    Planned(
      KernelCompiler.Prepared(c.kernel, c.machine, c.layout, dag),
      Strategy.Vliw(a.attempt),
      a.schedule,
      plan(c.kernel.name, dag, a.schedule.issue(_)),
      StoragePolicy.Assigned(registers)
    )
  }

  /** The plan without a register limit with the fewest cycles, then the fewest value registers,
    * then the first priority.
    */
  def planned(kernel: Kernel, machine: MachineDescription): Either[String, Planned] =
    KernelCompiler.prepare(kernel, machine).map { p =>
      attempts(p).minBy(a => (a.cycles, a.params.registers))
    }

  /** How `run`, a run of the circuit of `p`, differs from its plan: the cycles, done high for one
    * cycle, busy in cycles 0..T, the outputs (`expected`), and the issue and commit cycles of each
    * slot. Empty when it does not.
    */
  def mismatches(p: Planned, run: FixedRun, expected: Values): Seq[String] = {
    val ops = p.plan.ops
    val computes = ops.filter(_.isInstanceOf[FixedOp.Compute])
    val memoryOps = ops.filterNot(_.isInstanceOf[FixedOp.Compute])
    val loads = ops.filter(_.isInstanceOf[FixedOp.Load])
    def commits(os: Seq[FixedOp]) = os.map(FixedPlan.commitCycle(p.machine, _)).sorted
    def differs[A](what: String, actual: A, planned: A) =
      Option.when(actual != planned)(s"$what: $actual, expected $planned")
    Seq(
      differs("cycles", run.cycles, p.cycles),
      differs("done is high for one cycle", run.doneCycles, Seq(p.cycles)),
      differs("busy in cycles 0..T", run.busyCycles, 0 until p.cycles),
      differs("outputs", p.prepared.layout.outputs(a => run.memory(a).toLong), expected),
      differs("compute issues", run.computeIssues, computes.map(_.issue).sorted),
      differs("memory issues", run.memoryIssues, memoryOps.map(_.issue).sorted),
      differs("compute commits", run.computeCommits, commits(computes)),
      differs("memory commits", run.memoryCommits, commits(loads))
    ).flatten
  }
}
