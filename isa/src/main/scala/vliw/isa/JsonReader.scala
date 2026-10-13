package vliw.isa

import scala.collection.mutable

/** Error-accumulating readers for the strict JSON formats (machine description, test cases). */
private[isa] object JsonReader {
  final class Errors {
    private val buffer = mutable.ListBuffer.empty[String]
    def add(path: String, message: String): Unit = buffer += s"$path: $message"
    def isEmpty: Boolean = buffer.isEmpty
    def toList: List[String] = buffer.toList
  }

  def kind(v: ujson.Value): String = v match {
    case _: ujson.Obj             => "object"
    case _: ujson.Arr             => "array"
    case _: ujson.Str             => "string"
    case _: ujson.Num             => "number"
    case ujson.True | ujson.False => "boolean"
    case ujson.Null               => "null"
  }

  /** Reads one JSON object; every key must be read exactly once or `finish` reports it. */
  final class Obj(
      path: String,
      fields: collection.Map[String, ujson.Value],
      errors: Errors
  ) {
    private val used = mutable.Set.empty[String]

    private def get(key: String): Option[ujson.Value] = {
      used += key
      val v = fields.get(key)
      if (v.isEmpty) errors.add(path, s"""missing key "$key"""")
      v
    }

    def int(key: String, min: Int, max: Int): Option[Int] = get(key).flatMap {
      case ujson.Num(d) if d.isWhole && d >= min && d <= max => Some(d.toInt)
      case ujson.Num(d) if d.isWhole                         =>
        errors.add(s"$path.$key", s"must be in $min..$max, got ${d.toLong}")
        None
      case other =>
        errors.add(s"$path.$key", s"must be an integer, got ${kind(other)}")
        None
    }

    def str(key: String): Option[String] = get(key).flatMap {
      case ujson.Str(s) => Some(s)
      case other        =>
        errors.add(s"$path.$key", s"must be a string, got ${kind(other)}")
        None
    }

    def oneOf(key: String, allowed: Seq[String]): Option[String] = str(key).filter { s =>
      val ok = allowed.contains(s)
      if (!ok) errors.add(s"$path.$key", s"must be one of ${allowed.mkString(", ")}, got $s")
      ok
    }

    def const(key: String, expected: ujson.Value): Unit = get(key).foreach { v =>
      if (v != expected)
        errors.add(s"$path.$key", s"must be ${ujson.write(expected)}, got ${ujson.write(v)}")
    }

    def obj[A](key: String)(f: Obj => A): Option[A] =
      get(key).flatMap {
        case ujson.Obj(sub) =>
          val reader = new Obj(s"$path.$key", sub, errors)
          val a = f(reader)
          reader.finish()
          Some(a)
        case other =>
          errors.add(s"$path.$key", s"must be an object, got ${kind(other)}")
          None
      }

    def has(key: String): Boolean = fields.contains(key)

    def at(key: String): String = s"$path.$key"

    def report(key: String, message: String): Unit = errors.add(at(key), message)

    def raw(key: String): Option[ujson.Value] = get(key)

    /** Entries of the object under `key`, in document order. */
    def entries(key: String): Option[Seq[(String, ujson.Value)]] = get(key).flatMap {
      case ujson.Obj(sub) => Some(sub.toSeq)
      case other          =>
        errors.add(at(key), s"must be an object, got ${kind(other)}")
        None
    }

    def strings(key: String): Option[Vector[String]] = get(key).flatMap {
      case ujson.Arr(items) =>
        val strs = items.toVector.zipWithIndex.map {
          case (ujson.Str(s), _) => Some(s)
          case (other, i)        =>
            errors.add(s"${at(key)}[$i]", s"must be a string, got ${kind(other)}")
            None
        }
        if (strs.forall(_.isDefined)) Some(strs.flatten) else None
      case other =>
        errors.add(at(key), s"must be an array, got ${kind(other)}")
        None
    }

    def finish(): Unit =
      fields.keys.filterNot(used).toSeq.sorted.foreach { k =>
        errors.add(path, s"""unknown key "$k"""")
      }
  }
}
