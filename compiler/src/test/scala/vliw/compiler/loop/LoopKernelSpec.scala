package vliw.compiler.loop

import scala.util.Random

import org.scalatest.funsuite.AnyFunSuite

import vliw.compiler.dsl._
import vliw.isa.MachineDescription

class LoopKernelSpec extends AnyFunSuite {
  private val machine = MachineDescription.load().fold(es => fail(es.mkString("\n")), identity)

  private def run(k: Kernel, in: Values) = Interpreter.run(k, in).fold(e => fail(e), identity)

  test("the loop benchmarks unroll to the Phase 2 kernels exactly") {
    for (n <- Seq(1, 2, 8)) assert(LoopBenchmarks.saxpy(n).unroll == Benchmarks.saxpy(n))
    // Constants that are not words yet (-1, 2^33 + 5) are normalised as in dsl.Kernel.
    for (
      taps <- Seq(Seq(5L), Seq(1L, 2L, 3L, 4L), Seq(-1L, 2L), Seq(-3L, (1L << 33) + 5));
      n <- Seq(1, 3, 8)
    )
      assert(LoopBenchmarks.fir(taps, n).unroll == Benchmarks.fir(taps, n))
    for (n <- Seq(1, 2, 3)) assert(LoopBenchmarks.matmulRows(n).unroll == Benchmarks.matmul(n))
    for (n <- Seq(2, 3)) assert(LoopBenchmarks.matmulAcc(n).unroll == Benchmarks.matmul(n))
  }

  test("constants are normalised to 32-bit words") {
    val k = LoopKernel("neg", "i", 1) { b =>
      val out = b.output("out", 1)
      b.body(i => out(i) = b.const(-1))
    }
    assert(k.constants == Vector(0xffffffffL) && k.unroll.constants == Vector(0xffffffffL))
  }

  test("the IR keeps the loop: index, trips, affine indices, states") {
    val f = LoopBenchmarks.fir(Seq(1L, 2L), 8)
    assert((f.index, f.trips, f.states) == (("i", 8, Vector.empty)))
    assert(f.stores.map(s => (s.output, s.index)) == Vector(("out", Index(1, 0))))
    assert(
      f.stores.head.value == LoopExpr.Bin(
        Op.Add,
        LoopExpr.Bin(Op.Mul, LoopExpr.Const(1), LoopExpr.Elem("x", Index(1, 0))),
        LoopExpr.Bin(Op.Mul, LoopExpr.Const(2), LoopExpr.Elem("x", Index(1, 1)))
      )
    )
    // matmul3 as the accumulation over m = k + 1: a(3i + m) * b(3m + j), m = 0 before the loop.
    val m = LoopBenchmarks.matmulAcc(3)
    assert((m.index, m.trips, m.states.size, m.stores, m.finals.size) == (("m", 2, 9, Vector(), 9)))
    val s = m.states(5) // acc_1_2
    assert(s.name == "acc_1_2")
    assert(
      s.init == LoopExpr.Bin(
        Op.Mul,
        LoopExpr.Elem("a", Index(0, 3)),
        LoopExpr.Elem("b", Index(0, 2))
      )
    )
    assert(
      s.next == LoopExpr.Bin(
        Op.Add,
        LoopExpr.State("acc_1_2"),
        LoopExpr.Bin(Op.Mul, LoopExpr.Elem("a", Index(1, 4)), LoopExpr.Elem("b", Index(3, 5)))
      )
    )
    assert(m.finals(5) == FinalStore("c", 5, "acc_1_2"))
    val p = LoopBenchmarks.product(4)
    assert((p.index, p.trips) == (("i", 3)))
    assert(
      p.states == Vector(
        LoopState(
          "p",
          LoopExpr.Elem("x", Index(0, 0)),
          LoopExpr.Bin(Op.Mul, LoopExpr.State("p"), LoopExpr.Elem("x", Index(1, 1)))
        )
      )
    )
  }

  test("product and dot give the plain products and sums") {
    val rng = new Random(76)
    def w(v: Long) = v & 0xffffffffL
    for (n <- 2 to 9) {
      val x = Vector.fill(n)(rng.nextLong() & 0xffffffffL)
      val y = Vector.fill(n)(rng.nextLong() & 0xffffffffL)
      val p = run(LoopBenchmarks.product(n).unroll, Values(Map("x" -> x)))
      assert(p.arrays("out") == Vector(x.reduce((a, b) => w(a * b))))
      val d = run(LoopBenchmarks.dot(n).unroll, Values(Map("a" -> x, "b" -> y)))
      assert(
        d.arrays("out") == Vector(x.indices.map(i => w(x(i) * y(i))).reduce((a, b) => w(a + b)))
      )
    }
  }

  test("the states' values in each iteration: matmulAcc holds the partial sums") {
    val rng = new Random(3)
    val a = Vector.fill(9)(rng.nextLong() & 0xffffffffL)
    val b = Vector.fill(9)(rng.nextLong() & 0xffffffffL)
    val k = LoopBenchmarks.matmulAcc(3)
    assert(k.stateValues.size == 3) // before iterations 0 and 1, and after the loop
    for (t <- 0 to 2) {
      // stateValues(t)(3i + j) = sum over m <= t of a(3i + m) * b(3m + j)
      val probe = Kernel("probe") { p =>
        p.input("a", 9)
        p.input("b", 9)
        val out = p.output("out", 9)
        for (s <- 0 until 9) out(s) = k.stateValues(t)(s)
      }
      val got = run(probe, Values(Map("a" -> a, "b" -> b))).arrays("out")
      val want =
        for (i <- 0 until 3; j <- 0 until 3)
          yield (0 to t)
            .map(m => (a(3 * i + m) * b(3 * m + j)) & 0xffffffffL)
            .reduce((x, y) => (x + y) & 0xffffffffL)
      assert(got == want.toVector, s"t = $t")
    }
  }

  test("a state used twice in its next value stays linear in the trips") {
    // s := s * s + x(i): the unrolled tree doubles each iteration, but its subtrees are shared.
    val k = LoopKernel("square", "i", 40) { b =>
      val x = b.input("x", 40)
      val out = b.output("out", 1)
      val s = b.state("s", b.const(3))
      b.body(i => s := s.value * s.value + x(i))
      out(0) = s
    }
    val layout = LoopLowering.layout(k, machine).fold(e => fail(e), identity)
    assert(layout.constants == Map(3L -> 40))
    val g = LoopGraph.of(LoopLowering.lower(k, layout), machine)
    assert(g.expanded.dag.nodes.size == 1 + 40 * 3 + 1)
  }

  private def rejects(why: String)(build: => LoopKernel) = {
    val e = intercept[IllegalArgumentException](build)
    assert(e.getMessage.contains(why), e.getMessage)
  }

  test("malformed loop kernels are rejected when they are built") {
    rejects("0 iterations")(LoopKernel("k", "i", 0)(_ => ()))
    rejects("has no body")(LoopKernel("k", "i", 2)(b => b.output("out", 1)))
    rejects("x(4) in iteration 3 is outside 0 until 4") {
      LoopKernel("k", "i", 4) { b =>
        val x = b.input("x", 4)
        val out = b.output("out", 4)
        b.body(i => out(i) = x(i + 1))
      }
    }
    rejects("out(1) is assigned twice") {
      LoopKernel("k", "i", 2) { b =>
        val x = b.input("x", 2)
        val out = b.output("out", 2)
        b.body(i => out(1) = x(i))
      }
    }
    rejects("out is not assigned at 1") {
      LoopKernel("k", "i", 1) { b =>
        val x = b.input("x", 1)
        val out = b.output("out", 2)
        b.body(i => out(i) = x(i))
      }
    }
    rejects("only a final state can be stored") {
      LoopKernel("k", "i", 1) { b =>
        val x = b.input("x", 1)
        val out = b.output("out", 1)
        out(0) = x(0)
        b.body(_ => ())
      }
    }
    rejects("body is given twice") {
      LoopKernel("k", "i", 1) { b =>
        b.body(_ => ())
        b.body(_ => ())
      }
    }
    rejects("state s is not updated") {
      LoopKernel("k", "i", 2) { b =>
        val out = b.output("out", 1)
        val s = b.state("s", b.const(0))
        b.body(_ => ())
        out(0) = s
      }
    }
    rejects("state s is updated twice") {
      LoopKernel("k", "i", 2) { b =>
        val x = b.input("x", 2)
        val out = b.output("out", 1)
        val s = b.state("s", b.const(0))
        b.body { i =>
          s := s.value + x(i)
          s := s.value * x(i)
        }
        out(0) = s
      }
    }
    rejects("initial value of state s changes with the iteration") {
      LoopKernel("k", "i", 2) { b =>
        val x = b.input("x", 2)
        b.state("s", x(Index(1, 0)))
        b.body(_ => ())
      }
    }
    // A next value that is not an operation: s := x(i) while x(i + 1) is read too would make the
    // state a load shared with another iteration.
    rejects("next value of state s must be an operation") {
      LoopKernel("k", "i", 2) { b =>
        val x = b.input("x", 3)
        val out = b.output("out", 2)
        val c = b.output("c", 1)
        val s = b.state("s", b.const(0))
        b.body { i =>
          s := x(i)
          out(i) = s.value + x(i + 1)
        }
        c(0) = s
      }
    }
    rejects("next value of state s must be an operation") {
      LoopKernel("k", "i", 2) { b =>
        val a = b.scalar("a")
        val out = b.output("out", 1)
        val s = b.state("s", b.const(0))
        b.body(_ => s := a * a)
        out(0) = s
      }
    }
    rejects("state s is updated outside the body") {
      LoopKernel("k", "i", 2) { b =>
        val x = b.input("x", 2)
        val out = b.output("out", 2)
        val s = b.state("s", b.const(1))
        b.body(i => out(i) = s.value + x(i))
        s := s.value * x(0)
      }
    }
    rejects("state s is never read in the body") {
      LoopKernel("k", "i", 2) { b =>
        val x = b.input("x", 2)
        val out = b.output("out", 1)
        val s = b.state("s", b.const(0))
        b.body(i => s := x(i) * x(i))
        out(0) = s
      }
    }
    rejects("state s is declared in the body") {
      LoopKernel("k", "i", 1) { b =>
        b.body(_ => b.state("s", b.const(0)))
      }
    }
    rejects("a final state is stored outside the body") {
      LoopKernel("k", "i", 1) { b =>
        val x = b.input("x", 1)
        val out = b.output("out", 1)
        val s = b.state("s", b.const(0))
        b.body { i =>
          s := s.value + x(i)
          out(0) = s
        }
      }
    }
    rejects("x is declared twice") {
      LoopKernel("k", "i", 1) { b =>
        b.input("x", 1)
        b.scalar("x")
        b.body(_ => ())
      }
    }
  }
}
