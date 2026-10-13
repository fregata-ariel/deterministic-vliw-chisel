package vliw.isa

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import scala.collection.mutable

import upickle.core.{ArrVisitor, ObjVisitor, Visitor}

/** Strict JSON reading and the canonical form used for content hashes.
  *
  * The canonical form is RFC 8785 (JCS) restricted to integer numbers: object keys sorted by UTF-16
  * code units, no insignificant whitespace, minimal string escaping. For ASCII documents with
  * integer numbers it equals the output of `jq -cS .`.
  */
object Json {

  final case class Error(message: String)

  /** Parses `text`, rejecting duplicate object keys (which plain ujson silently overwrites). */
  def parse(text: String): Either[Error, ujson.Value] =
    try Right(ujson.transform(ujson.Readable.fromString(text), StrictValue))
    catch {
      case e: DuplicateKey                   => Left(Error(s"duplicate object key: ${e.key}"))
      case e: ujson.ParseException           => Left(Error(s"invalid JSON: ${e.getMessage}"))
      case e: ujson.IncompleteParseException => Left(Error(s"invalid JSON: ${e.getMessage}"))
    }

  /** Canonical form of `value`. Fails on non-integer numbers, which the formats here never use. */
  def canonical(value: ujson.Value): Either[Error, String] = {
    val out = new java.lang.StringBuilder
    def go(v: ujson.Value): Option[Error] = v match {
      case ujson.Null   => out.append("null"); None
      case ujson.True   => out.append("true"); None
      case ujson.False  => out.append("false"); None
      case ujson.Num(d) =>
        if (d.isWhole && math.abs(d) <= 9007199254740991.0) { out.append(d.toLong); None }
        else Some(Error(s"non-integer number: $d"))
      case ujson.Str(s)     => writeString(out, s); None
      case ujson.Arr(items) =>
        out.append('[')
        val err = items.iterator.zipWithIndex
          .map { case (item, i) =>
            if (i > 0) out.append(',')
            go(item)
          }
          .collectFirst { case Some(e) => e }
        out.append(']')
        err
      case ujson.Obj(fields) =>
        out.append('{')
        val keys = fields.keys.toVector.sorted // String ordering compares UTF-16 code units.
        val err = keys.iterator.zipWithIndex
          .map { case (k, i) =>
            if (i > 0) out.append(',')
            writeString(out, k)
            out.append(':')
            go(fields(k))
          }
          .collectFirst { case Some(e) => e }
        out.append('}')
        err
    }
    go(value).toLeft(out.toString)
  }

  /** `sha256:` followed by the lower-case hex SHA-256 of the UTF-8 bytes of `text`. */
  def sha256(text: String): String = {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))
    "sha256:" + digest.map(b => f"${b & 0xff}%02x").mkString
  }

  private def writeString(out: java.lang.StringBuilder, s: String): Unit = {
    out.append('"')
    s.foreach {
      case '"'           => out.append("\\\"")
      case '\\'          => out.append("\\\\")
      case '\b'          => out.append("\\b")
      case '\f'          => out.append("\\f")
      case '\n'          => out.append("\\n")
      case '\r'          => out.append("\\r")
      case '\t'          => out.append("\\t")
      case c if c < 0x20 => out.append(f"\\u${c.toInt}%04x")
      case c             => out.append(c)
    }
    out.append('"')
  }

  private final class DuplicateKey(val key: String) extends Exception(key)

  /** ujson.Value builder whose object visitors reject repeated keys, recursively. */
  private object StrictValue extends Visitor.Delegate[ujson.Value, ujson.Value](ujson.Value) {
    override def visitObject(
        length: Int,
        jsonableKeys: Boolean,
        index: Int
    ): ObjVisitor[ujson.Value, ujson.Value] = {
      val inner = ujson.Value.visitObject(length, jsonableKeys, index)
      new ObjVisitor[ujson.Value, ujson.Value] {
        private val seen = mutable.Set.empty[String]
        def visitKey(index: Int): Visitor[_, _] = inner.visitKey(index)
        def visitKeyValue(v: Any): Unit = {
          val key = v.toString
          if (!seen.add(key)) throw new DuplicateKey(key)
          inner.visitKeyValue(v)
        }
        def subVisitor: Visitor[_, _] = StrictValue
        def visitValue(v: ujson.Value, index: Int): Unit = inner.visitValue(v, index)
        def visitEnd(index: Int): ujson.Value = inner.visitEnd(index)
      }
    }

    override def visitArray(length: Int, index: Int): ArrVisitor[ujson.Value, ujson.Value] = {
      val inner = ujson.Value.visitArray(length, index)
      new ArrVisitor[ujson.Value, ujson.Value] {
        def subVisitor: Visitor[_, _] = StrictValue
        def visitValue(v: ujson.Value, index: Int): Unit = inner.visitValue(v, index)
        def visitEnd(index: Int): ujson.Value = inner.visitEnd(index)
      }
    }
  }
}
