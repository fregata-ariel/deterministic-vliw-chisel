package vliw.isa

/** MVP legality rules (docs/ISA.md, "MVPの合法性規則"). Verifier findings and reference model errors are
  * both reported with these identifiers, so that their verdicts can be compared.
  */
sealed abstract class Rule(val id: String, val title: String) extends Product with Serializable
object Rule {
  case object Encoding extends Rule("L1", "encoding")
  case object ProgramShape extends Rule("L2", "program shape")
  case object InFlightRead extends Rule("L3", "read of a register being written")
  case object OverlappingWrites extends Rule("L4", "overlapping writes to a register")
  case object WritebackPort extends Rule("L5", "write-back port conflict")
  case object HaltCompletion extends Rule("L6", "operation pending at HALT")
  case object AddressRange extends Rule("L7", "scratchpad address out of range")
  case object UndefinedRead extends Rule("L8", "read of an undefined value")
  case object IssueInterval extends Rule("L9", "operation issued within its II")
  case object InFlightStoreRead extends Rule("L10", "load of a word being stored")
  case object ReadPorts extends Rule("L11", "more register reads than read ports")

  val all: Seq[Rule] = Seq(
    Encoding,
    ProgramShape,
    InFlightRead,
    OverlappingWrites,
    WritebackPort,
    HaltCompletion,
    AddressRange,
    UndefinedRead,
    IssueInterval,
    InFlightStoreRead,
    ReadPorts
  )
  val byId: Map[String, Rule] = all.map(r => r.id -> r).toMap
}
