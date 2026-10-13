package vliw.isa

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.util.Try

import vliw.isa.JsonReader.{Errors, Obj, kind}

/** Issue slot of a bundle; also names the register file write-back port it owns. */
sealed abstract class Slot(val name: String) extends Product with Serializable
object Slot {
  case object Compute extends Slot("compute")
  case object Memory extends Slot("memory")
  val all: Seq[Slot] = Seq(Compute, Memory)
}

/** One fixed-latency operation from `operations` in machine.json. */
final case class OperationSpec(
    mnemonic: String,
    slot: Slot,
    opcode: Int,
    latency: Int,
    ii: Int,
    writeback: Option[Slot]
)

/** Validated contents of config/machine.json (format: docs/MACHINE.md).
  *
  * Fields fixed by the mvp-0 ISA are checked by the loader but not stored. `canonicalJson` and
  * `hash` identify the exact document and are recorded in traces and evaluation results.
  */
final case class MachineDescription(
    schemaVersion: Int,
    revision: Int,
    status: String,
    machineId: String,
    registerCount: Int,
    banks: Int,
    wordsPerBank: Int,
    operations: Map[String, OperationSpec],
    haltOpcode: Int,
    nopOpcode: Int,
    instructionMemoryBundles: Int,
    canonicalJson: String,
    hash: String
) {
  def scratchpadWords: Int = banks * wordsPerBank
  def operation(mnemonic: String): OperationSpec = operations(mnemonic)
}

object MachineDescription {
  val SchemaVersion: Int = 1
  val DefaultPath: Path = Paths.get("config", "machine.json")

  /** Mnemonics of fixed-latency operations, with their slot and write-back port in mvp-0. */
  val FixedLatencyOperations: Seq[(String, Slot, Option[Slot])] = Seq(
    ("ADD", Slot.Compute, Some(Slot.Compute)),
    ("SUB", Slot.Compute, Some(Slot.Compute)),
    ("MUL", Slot.Compute, Some(Slot.Compute)),
    ("LD", Slot.Memory, Some(Slot.Memory)),
    ("ST", Slot.Memory, None)
  )

  /** Width of the memory word's immediate address field (docs/ISA.md). */
  val AddressBits: Int = 9
  val OpcodeBits: Int = 4
  val RegisterFieldBits: Int = 4

  def load(path: Path = DefaultPath): Either[List[String], MachineDescription] =
    Try(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).toEither.left
      .map(e => List(s"cannot read: ${e.getMessage}"))
      .flatMap(parse)
      .left
      .map(_.map(e => s"$path: $e"))

  def parse(text: String): Either[List[String], MachineDescription] =
    for {
      value <- Json.parse(text).left.map(e => List(e.message))
      canonical <- Json.canonical(value).left.map(e => List(e.message))
      machine <- decode(value, canonical)
    } yield machine

  private def decode(
      value: ujson.Value,
      canonical: String
  ): Either[List[String], MachineDescription] = {
    val errors = new Errors
    val result = value match {
      case ujson.Obj(fields) =>
        val root = new Obj("$", fields, errors)
        val machine = decodeRoot(root, canonical, errors)
        root.finish()
        machine
      case other =>
        errors.add("$", s"must be an object, got ${kind(other)}")
        None
    }
    if (errors.isEmpty) result.toRight(List("$: invalid machine description"))
    else Left(errors.toList)
  }

  private def decodeRoot(
      root: Obj,
      canonical: String,
      errors: Errors
  ): Option[MachineDescription] = {
    val schemaVersion = root.int("schema_version", SchemaVersion, SchemaVersion)
    val revision = root.int("revision", 1, Int.MaxValue)
    val status = root.oneOf("status", Seq("proposed", "frozen"))
    root.const("isa_version", ujson.Str(IsaVersion.Current))
    val machineId = root.str("machine_id").filter { id =>
      val ok = id.matches("[a-z0-9][a-z0-9-]*")
      if (!ok) errors.add("$.machine_id", "must match [a-z0-9][a-z0-9-]*")
      ok
    }
    root.const("word_bits", ujson.Num(32))
    root.const("bundle_bits", ujson.Num(64))
    root.const("binary_endianness", ujson.Str("little"))
    root.const("arithmetic", ujson.Str("modulo_2_pow_32"))
    root.obj("slots") { o =>
      o.const("compute", ujson.Num(1))
      o.const("memory", ujson.Num(1))
    }

    val registerCount = root
      .obj("register_file") { o =>
        val count = o.int("count", 1, 1 << RegisterFieldBits)
        o.const("zero_register", ujson.Null)
        o.const("read_ports", ujson.Num(3))
        o.obj("write_ports") { w =>
          w.const("compute", ujson.Num(1))
          w.const("memory", ujson.Num(1))
        }
        o.const("same_cycle_same_destination_write", ujson.Str("forbidden"))
        count
      }
      .flatten

    val scratchpad = root
      .obj("scratchpad") { o =>
        val maxWords = 1 << AddressBits
        val banks = o.int("banks", 1, maxWords)
        val words = o.int("words_per_bank", 1, maxWords)
        o.const("ports_per_bank", ujson.Str("1RW"))
        o.const("global_issue_per_cycle", ujson.Num(1))
        o.const("address_unit", ujson.Str("word"))
        banks.foreach { b =>
          o.const("bank_mapping", ujson.Str(s"word_address_mod_$b"))
          o.const("row_mapping", ujson.Str(s"word_address_div_$b"))
        }
        o.const("read_during_write", ujson.Str("forbidden"))
        for (b <- banks; w <- words) yield {
          if (b.toLong * w > maxWords)
            errors.add(
              "$.scratchpad",
              s"banks * words_per_bank must be <= $maxWords (9-bit address)"
            )
          (b, w)
        }
      }
      .flatten

    val nopOpcode = root.int("nop_opcode", 0, 0)

    val operations = root.obj("operations") { o =>
      val fixed = FixedLatencyOperations.flatMap { case (mnemonic, slot, writeback) =>
        o.obj(mnemonic) { op =>
          op.const("slot", ujson.Str(slot.name))
          op.const("writeback", writeback.fold[ujson.Value](ujson.Null)(s => ujson.Str(s.name)))
          for {
            opcode <- op.int("opcode", 1, (1 << OpcodeBits) - 1)
            latency <- op.int("latency", 1, 8)
            ii <- op.int("ii", 1, 8)
          } yield OperationSpec(mnemonic, slot, opcode, latency, ii, writeback)
        }.flatten
      }
      val halt = o
        .obj("HALT") { op =>
          op.const("slot", ujson.Str(Slot.Compute.name))
          op.const("requires_no_pending_operations", ujson.True)
          op.const("requires_memory_nop", ujson.True)
          op.const("done_after_cycles", ujson.Num(1))
          op.int("opcode", 1, (1 << OpcodeBits) - 1)
        }
        .flatten
      (fixed, halt)
    }

    val bundles = root.obj("instruction_memory")(_.int("bundles", 1, 4096)).flatten
    root.obj("control") { o =>
      o.const("branches", ujson.False)
      o.const("loops", ujson.Str("unrolled"))
      o.const("internal_stalls", ujson.False)
    }
    root.obj("timing") { o =>
      o.const("source_read", ujson.Str("issue_cycle"))
      o.const("availability", ujson.Str("issue_cycle_plus_latency"))
      o.const("dependency_constraint", ujson.Str("consumer_issue_gte_producer_issue_plus_latency"))
      o.const("writeback_cycle_reservation", ujson.True)
      o.const("kernel_cycles", ujson.Str("halt_issue_cycle_plus_1"))
    }

    operations.foreach { case (fixed, halt) =>
      val bySlot = fixed.map(op => (op.slot, op.opcode, op.mnemonic)) ++
        halt.map(h => (Slot.Compute, h, "HALT"))
      bySlot.groupBy(t => (t._1, t._2)).foreach { case ((slot, opcode), ops) =>
        if (ops.size > 1)
          errors.add(
            "$.operations",
            s"opcode $opcode is used more than once in the ${slot.name} slot: " +
              ops.map(_._3).sorted.mkString(", ")
          )
        if (nopOpcode.contains(opcode))
          errors.add(
            "$.operations",
            s"opcode $opcode of ${ops.map(_._3).mkString} equals nop_opcode"
          )
      }
    }

    for {
      schema <- schemaVersion
      rev <- revision
      st <- status
      id <- machineId
      regs <- registerCount
      (banks, words) <- scratchpad
      nop <- nopOpcode
      (fixed, haltOpt) <- operations
      halt <- haltOpt
      if fixed.size == FixedLatencyOperations.size
      imem <- bundles
    } yield MachineDescription(
      schemaVersion = schema,
      revision = rev,
      status = st,
      machineId = id,
      registerCount = regs,
      banks = banks,
      wordsPerBank = words,
      operations = fixed.map(op => op.mnemonic -> op).toMap,
      haltOpcode = halt,
      nopOpcode = nop,
      instructionMemoryBundles = imem,
      canonicalJson = canonical,
      hash = Json.sha256(canonical)
    )
  }
}
