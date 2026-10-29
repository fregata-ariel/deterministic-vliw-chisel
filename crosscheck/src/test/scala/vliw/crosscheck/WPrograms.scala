package vliw.crosscheck

import scala.util.Random

import vliw.compiler.Verifier
import vliw.isa._

/** Random programs of ISA mvp-2 (W, docs/ISA.md "mvp-2: W") and the shapes they must produce: the
  * generator of [[DifferentialSpec]] (verifier against reference model) and of [[RtlMvp2Spec]] (RTL
  * against both). The programs use r0..r5 (the pairs r0:r1, r2:r3 and r4:r5) and the words 0..5,
  * 510 and 511, which their inputs declare with probability 9/10; most programs are illegal.
  */
object WPrograms {

  /** The scratchpad words that the programs and their inputs use. */
  val usedWords: Set[Int] = (0 until 6).toSet ++ Set(510, 511)

  /** Programs on r0..r5 and the words 0..5: the scalar operations, VADD, VSUB, VMUL and VMAC (half
    * of them broadcast, a third of the VMACs with rd = rs3), and on W-full LD.W and ST.W, at an odd
    * address two times in five. One wide access in 16 is at 510 or 511, whose second word is 511 or
    * 512 (L7 on 512 words).
    */
  def program(rng: Random, m: MachineDescription): Vector[Bundle] = {
    val body = Vector.fill(rng.nextInt(8)) {
      val c = compute(rng)
      Bundle(c, if (c == ComputeOp.Halt) MemoryOp.Nop else memory(rng, m))
    }
    rng.nextInt(10) match {
      case 0     => body
      case 1 | 2 => body ++ Vector.fill(rng.nextInt(3))(Bundle.Nop) :+ Bundle(ComputeOp.Halt)
      case _     => body :+ Bundle.Nop :+ Bundle.Nop :+ Bundle(ComputeOp.Halt)
    }
  }

  /** A Compute operation of [[program]] on r0..r5; HALT one time in 16. */
  private def compute(rng: Random): ComputeOp = {
    def r = rng.nextInt(6)
    def p = 2 * rng.nextInt(3)
    rng.nextInt(16) match {
      case 0 | 1 | 2 => ComputeOp.Nop
      case 3         => ComputeOp.Add(r, r, r)
      case 4         => ComputeOp.Sub(r, r, r)
      case 5         => ComputeOp.Mul(r, r, r)
      case 6         =>
        val d = r
        ComputeOp.Mac(d, r, r, if (rng.nextInt(3) == 0) d else r)
      case 7 | 8   => ComputeOp.VAdd(p, p, p)
      case 9       => ComputeOp.VSub(p, p, p)
      case 10 | 11 =>
        val b = rng.nextBoolean()
        val d = p
        ComputeOp.VMul(d, if (b) r else p, p, b)
      case 12 | 13 =>
        val b = rng.nextBoolean()
        val d = p
        val x = if (b) r else p
        ComputeOp.VMac(d, x, p, if (rng.nextInt(3) == 0) d else p, b)
      case 14 => ComputeOp.Halt
      case _  => ComputeOp.Nop
    }
  }

  /** A Memory operation of [[program]] on r0..r5 and the words 0..5 (wide: 0..4, 510, 511). */
  private def memory(rng: Random, m: MachineDescription): MemoryOp = {
    def r = rng.nextInt(6)
    def p = 2 * rng.nextInt(3)
    def a = rng.nextInt(6)
    def wide = if (rng.nextInt(16) == 0) 510 + rng.nextInt(2) else rng.nextInt(5)
    rng.nextInt(if (m.hasWideMemory) 6 else 4) match {
      case 0 | 1 => MemoryOp.Nop
      case 2     => MemoryOp.Ld(r, a)
      case 3     => MemoryOp.St(a, r)
      case 4     => MemoryOp.LdW(p, wide)
      case _     => MemoryOp.StW(wide, p)
    }
  }

  /** Programs of the operations of [[program]] (without HALT) that never overlap: after each
    * bundle, NOPs until every result has committed, so no hazard rule (L3 to L6) applies. With
    * [[allInputs]] most are legal (some break L11 or L7), and each carries several W operations on
    * random values: both lanes, the broadcast, and LD.W and ST.W at odd and even addresses.
    */
  def spaced(rng: Random, m: MachineDescription): Vector[Bundle] = {
    val gap = m.operations.values.map(_.latency).max
    Vector
      .fill(2 + rng.nextInt(6)) {
        val c = Iterator.continually(compute(rng)).find(_ != ComputeOp.Halt).get
        Bundle(c, memory(rng, m)) +: Vector.fill(gap - 1)(Bundle.Nop)
      }
      .flatten :+ Bundle(ComputeOp.Halt)
  }

  /** Every register and word of the programs, declared with random values. */
  def allInputs(rng: Random): Inputs =
    Inputs(
      (0 until 6).map(_ -> (rng.nextLong() & 0xffffffffL)).toMap,
      usedWords.toSeq.sorted.map(_ -> (rng.nextLong() & 0xffffffffL)).toMap
    )

  /** A LD and a LD.W (in either order) issued so that they commit at the same boundary: L5 on the
    * memory port, on a machine whose two load latencies differ; a random Compute operation beside
    * each, and a NOP or two before.
    */
  def loadClash(rng: Random, m: MachineDescription): Vector[Bundle] = {
    val (ld, ldw) = (m.operation("LD").latency, m.operation("LD.W").latency)
    require(ld != ldw, s"${m.machineId}: LD and LD.W have the same latency")
    def plain = Iterator.continually(compute(rng)).find(_ != ComputeOp.Halt).get
    val first: MemoryOp =
      if (ldw > ld) MemoryOp.LdW(2 * rng.nextInt(3), rng.nextInt(5))
      else MemoryOp.Ld(rng.nextInt(6), rng.nextInt(6))
    val second: MemoryOp =
      if (ldw > ld) MemoryOp.Ld(rng.nextInt(6), rng.nextInt(6))
      else MemoryOp.LdW(2 * rng.nextInt(3), rng.nextInt(5))
    val apart = (ld - ldw).abs
    Vector.fill(rng.nextInt(3))(Bundle.Nop) ++ (Bundle(plain, first) +:
      Vector.fill(apart - 1)(Bundle.Nop)) ++ Vector(Bundle(plain, second)) ++
      Vector.fill(ld.max(ldw))(Bundle.Nop) :+ Bundle(ComputeOp.Halt)
  }

  /** The first `count` programs of [[program]] and [[inputs]] drawn with `seed` that the verifier
    * accepts on `m`, encoded, with their inputs.
    */
  def legalPrograms(
      m: MachineDescription,
      seed: Long,
      count: Int
  ): Vector[(Vector[Long], Inputs)] = {
    val rng = new Random(seed)
    val codec = new BundleCodec(m)
    Iterator
      .continually((program(rng, m), inputs(rng)))
      .map { case (b, in) => (codec.encodeAll(b).fold(e => sys.error(e), identity), in) }
      .filter { case (w, in) => Verifier.verify(m, w, Verifier.Declaration.of(in)).isEmpty }
      .take(count)
      .toVector
  }

  /** Declares each of r0..r5 and of the words 0..5, 510 and 511 with probability 9/10. */
  def inputs(rng: Random): Inputs =
    Inputs(
      (0 until 6).filter(_ => rng.nextInt(10) != 0).map(_ -> (rng.nextLong() & 0xffffffffL)).toMap,
      ((0 until 6) ++ Seq(510, 511))
        .filter(_ => rng.nextInt(10) != 0)
        .map(_ -> (rng.nextLong() & 0xffffffffL))
        .toMap
    )

  /** The operand fields of a bundle, each with the registers it reads. */
  def fields(b: Bundle): Seq[Seq[Int]] = {
    def pair(p: Int) = Seq(p, p + 1)
    def rs1(x: Int, bc: Boolean) = if (bc) Seq(x) else pair(x)
    val compute = b.compute match {
      case ComputeOp.Add(_, x, y)         => Seq(Seq(x), Seq(y))
      case ComputeOp.Sub(_, x, y)         => Seq(Seq(x), Seq(y))
      case ComputeOp.Mul(_, x, y)         => Seq(Seq(x), Seq(y))
      case ComputeOp.Mac(_, x, y, z)      => Seq(Seq(x), Seq(y), Seq(z))
      case ComputeOp.VAdd(_, x, y)        => Seq(pair(x), pair(y))
      case ComputeOp.VSub(_, x, y)        => Seq(pair(x), pair(y))
      case ComputeOp.VMul(_, x, y, bc)    => Seq(rs1(x, bc), pair(y))
      case ComputeOp.VMac(_, x, y, z, bc) => Seq(rs1(x, bc), pair(y), pair(z))
      case ComputeOp.Nop | ComputeOp.Halt => Nil
    }
    val memory = b.memory match {
      case MemoryOp.St(_, r)  => Seq(Seq(r))
      case MemoryOp.StW(_, r) => Seq(pair(r))
      case _                  => Nil
    }
    compute ++ memory
  }

  def isVector(op: ComputeOp): Boolean = op match {
    case _: ComputeOp.VAdd | _: ComputeOp.VSub | _: ComputeOp.VMul | _: ComputeOp.VMac => true
    case _                                                                             => false
  }

  /** The mnemonic of a Compute operation and the registers it writes (two for W). */
  def computeWrite(op: ComputeOp): Option[(String, Seq[Int])] = op match {
    case ComputeOp.Add(d, _, _)         => Some("ADD" -> Seq(d))
    case ComputeOp.Sub(d, _, _)         => Some("SUB" -> Seq(d))
    case ComputeOp.Mul(d, _, _)         => Some("MUL" -> Seq(d))
    case ComputeOp.Mac(d, _, _, _)      => Some("MAC" -> Seq(d))
    case ComputeOp.VAdd(d, _, _)        => Some("VADD" -> Seq(d, d + 1))
    case ComputeOp.VSub(d, _, _)        => Some("VSUB" -> Seq(d, d + 1))
    case ComputeOp.VMul(d, _, _, _)     => Some("VMUL" -> Seq(d, d + 1))
    case ComputeOp.VMac(d, _, _, _, _)  => Some("VMAC" -> Seq(d, d + 1))
    case ComputeOp.Nop | ComputeOp.Halt => None
  }

  /** The mnemonic of a LD or LD.W and the registers it writes. */
  def loadWrite(op: MemoryOp): Option[(String, Seq[Int])] = op match {
    case MemoryOp.Ld(d, _)                                     => Some("LD" -> Seq(d))
    case MemoryOp.LdW(d, _)                                    => Some("LD.W" -> Seq(d, d + 1))
    case MemoryOp.St(_, _) | MemoryOp.StW(_, _) | MemoryOp.Nop => None
  }

  /** Whether an operation issued before cycle t writes `reg` and its value is not yet visible at t.
    */
  def writing(m: MachineDescription, b: Vector[Bundle], t: Int, reg: Int): Boolean =
    (0 until t).exists { s =>
      (computeWrite(b(s).compute) ++ loadWrite(b(s).memory)).exists { case (op, rs) =>
        rs.contains(reg) && t < s + m.operation(op).latency
      }
    }

  /** The operations of a program that write registers: (issue, port, latency, registers). */
  def portUses(m: MachineDescription, b: Vector[Bundle]): Vector[(Int, Slot, Int, Seq[Int])] =
    b.zipWithIndex.flatMap { case (x, s) =>
      computeWrite(x.compute).map { case (op, rs) =>
        (s, Slot.Compute, m.operation(op).latency, rs)
      } ++
        loadWrite(x.memory).map { case (op, rs) => (s, Slot.Memory, m.operation(op).latency, rs) }
    }

  /** Shapes the W generator must produce, and what each implies. */
  object Shapes {

    /** A pair field (or ST.W's source) whose second register, and no other register the bundle
      * reads, is being written: L3 applies to each register of a pair.
      */
    def secondLaneWritten(m: MachineDescription, b: Vector[Bundle]): Boolean =
      b.indices.exists { t =>
        val fs = fields(b(t))
        val written = fs.flatten.filter(writing(m, b, t, _)).toSet
        fs.exists(f => f.size == 2 && written == Set(f(1)))
      }

    /** Two operations commit through one port at one boundary, one of them moving a pair. */
    def pairPortClash(m: MachineDescription, b: Vector[Bundle]): Boolean = {
      val uses = portUses(m, b)
      uses.exists { case (s, p, l, rs) =>
        rs.size == 2 && uses.exists { case (t, q, k, _) => t != s && q == p && t + k == s + l }
      }
    }

    /** One vector operation or LD.W, so one use of a port for two registers. */
    def pairCommit(m: MachineDescription, b: Vector[Bundle]): Boolean =
      portUses(m, b).exists(_._4.size == 2)

    /** A pair on the compute port and a pair on the memory port at the same boundary. */
    def pairsOnBothPorts(m: MachineDescription, b: Vector[Bundle]): Boolean = {
      val pairs = portUses(m, b).filter(_._4.size == 2)
      pairs.exists { case (s, p, l, _) =>
        pairs.exists { case (t, q, k, _) =>
          p == Slot.Compute && q == Slot.Memory && s + l == t + k
        }
      }
    }

    /** A broadcast rs1 r(x) whose r(x + 1) has no value: a broadcast reads one register. */
    def broadcastAlone(b: Vector[Bundle], in: Inputs): Boolean = b.zipWithIndex.exists {
      case (Bundle(ComputeOp.VMul(_, x, _, true), _), t)    => undefined(b, in, t, x + 1)
      case (Bundle(ComputeOp.VMac(_, x, _, _, true), _), t) => undefined(b, in, t, x + 1)
      case _                                                => false
    }
    private def undefined(b: Vector[Bundle], in: Inputs, t: Int, reg: Int) =
      !in.registers.contains(reg) && (0 until t).forall { s =>
        (computeWrite(b(s).compute) ++ loadWrite(b(s).memory)).forall(!_._2.contains(reg))
      }

    /** A VMUL or VMAC that broadcasts an even register: lane 1 must not read r(rs1 + 1). */
    def evenBroadcast(b: Vector[Bundle]): Boolean = b.exists {
      case Bundle(ComputeOp.VMul(_, x, _, true), _)    => x % 2 == 0
      case Bundle(ComputeOp.VMac(_, x, _, _, true), _) => x % 2 == 0
      case _                                           => false
    }

    /** VMAC with rd = rs3: the accumulating form. */
    def vmacAccumulates(b: Vector[Bundle]): Boolean = b.exists {
      case Bundle(ComputeOp.VMac(d, _, _, z, _), _) => d == z
      case _                                        => false
    }

    /** VMAC and LD.W in one bundle: three fields, six registers read or written. */
    def vmacWithLdW(b: Vector[Bundle]): Boolean = b.exists {
      case Bundle(_: ComputeOp.VMac, _: MemoryOp.LdW) => true
      case _                                          => false
    }

    /** A register written as half of a pair and, at another time, alone (a partial pair update). */
    def partialUpdate(m: MachineDescription, b: Vector[Bundle]): Boolean = {
      val uses = portUses(m, b)
      uses.exists { case (s, _, _, rs) =>
        rs.size == 2 && uses.exists { case (t, _, _, qs) =>
          t != s && qs.size == 1 && rs.contains(qs.head)
        }
      }
    }

    /** VMAC or MAC with ST or ST.W: four fields. */
    def rs3Store(b: Vector[Bundle]): Boolean = b.exists {
      case Bundle(_: ComputeOp.Mac | _: ComputeOp.VMac, _: MemoryOp.St | _: MemoryOp.StW) => true
      case _                                                                              => false
    }

    /** LD.W at an odd address: its second word is in the next row of bank 0. */
    def oddWide(b: Vector[Bundle]): Boolean = b.exists {
      case Bundle(_, MemoryOp.LdW(_, a)) => a % 2 == 1
      case _                             => false
    }

    /** ST.W at an odd address: its second word is in the next row of bank 0. */
    def oddStore(b: Vector[Bundle]): Boolean = b.exists {
      case Bundle(_, MemoryOp.StW(a, _)) => a % 2 == 1
      case _                             => false
    }

    /** ST.W at an odd address, and a later LD or LD.W that reads its second word. */
    def oddStoreReadBack(b: Vector[Bundle]): Boolean = b.indices.exists { s =>
      b(s).memory match {
        case MemoryOp.StW(a, _) if a % 2 == 1 =>
          (s + 1 until b.size).exists(t => loads(b(t)).contains(a + 1))
        case _ => false
      }
    }

    /** The words each bundle loads and stores, with the store latency. */
    private def loads(x: Bundle) = x.memory match {
      case MemoryOp.Ld(_, a)  => Seq(a)
      case MemoryOp.LdW(_, a) => Seq(a, a + 1)
      case _                  => Nil
    }
    private def stores(m: MachineDescription, x: Bundle) = x.memory match {
      case MemoryOp.St(a, _)  => Seq(a).map(_ -> m.operation("ST").latency)
      case MemoryOp.StW(a, _) => Seq(a, a + 1).map(_ -> m.operation("ST.W").latency)
      case _                  => Nil
    }

    /** A load of a word, at least one being a word of LD.W or ST.W, inside a store's interval. */
    def wordInFlight(m: MachineDescription, b: Vector[Bundle]): Boolean = b.indices.exists { t =>
      val wide =
        (x: Bundle) => x.memory.isInstanceOf[MemoryOp.LdW] || x.memory.isInstanceOf[MemoryOp.StW]
      (0 until t).exists { s =>
        (wide(b(s)) || wide(b(t))) && stores(m, b(s)).exists { case (w, l) =>
          loads(b(t)).contains(w) && t < s + l
        }
      }
    }

    /** A LD and a LD.W (or the reverse) that commit at one boundary, the later issued at cycle t:
      * L5 on the memory port, possible only when the two latencies differ.
      */
    def loadPortClashAt(m: MachineDescription, b: Vector[Bundle], t: Int): Boolean = {
      val loads = portUses(m, b).filter(_._2 == Slot.Memory)
      loads.exists { case (u, _, k, qs) =>
        u == t && loads.exists { case (s, _, l, rs) =>
          s < t && s + l == t + k && rs.size != qs.size
        }
      }
    }
  }
}
