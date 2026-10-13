package vliw.isa

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.nio.{ByteBuffer, ByteOrder}

import scala.util.Try

/** Program images: `.bin` is 8 bytes per bundle, little-endian; `.hex` is one bundle per line as 16
  * lower-case hex digits (the 64-bit value, memory word first), for `$readmemh` and Chisel's
  * `loadMemoryFromFile`.
  */
object ProgramFile {

  def toBin(words: Seq[Long]): Array[Byte] = {
    val buf = ByteBuffer.allocate(words.length * 8).order(ByteOrder.LITTLE_ENDIAN)
    words.foreach(w => buf.putLong(w))
    buf.array()
  }

  def fromBin(bytes: Array[Byte]): Either[String, Vector[Long]] =
    if (bytes.length % 8 != 0) Left(s"binary length ${bytes.length} is not a multiple of 8 bytes")
    else {
      val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
      Right(Vector.fill(bytes.length / 8)(buf.getLong()))
    }

  def toHex(words: Seq[Long]): String = words.map(w => f"$w%016x\n").mkString

  def fromHex(text: String): Either[String, Vector[Long]] = {
    val lines = text.split("\n", -1).toVector
    val body = if (lines.lastOption.contains("")) lines.init else lines
    body.zipWithIndex.collectFirst {
      case (line, i) if !line.matches("[0-9a-f]{16}") =>
        s"line ${i + 1}: expected 16 lower-case hex digits, got \"$line\""
    } match {
      case Some(error) => Left(error)
      case None        => Right(body.map(java.lang.Long.parseUnsignedLong(_, 16)))
    }
  }

  /** Reads `.bin` or `.hex` according to the file extension. */
  def read(path: Path): Either[String, Vector[Long]] =
    Try(Files.readAllBytes(path)).toEither.left
      .map(e => s"$path: cannot read: ${e.getMessage}")
      .flatMap { bytes =>
        val name = path.getFileName.toString
        val parsed =
          if (name.endsWith(".hex")) fromHex(new String(bytes, StandardCharsets.US_ASCII))
          else if (name.endsWith(".bin")) fromBin(bytes)
          else Left("unknown program format (expected .bin or .hex)")
        parsed.left.map(e => s"$path: $e")
      }

  /** Writes `.bin` or `.hex` according to the file extension. */
  def write(path: Path, words: Seq[Long]): Either[String, Unit] = {
    val name = path.getFileName.toString
    val bytes =
      if (name.endsWith(".hex")) Right(toHex(words).getBytes(StandardCharsets.US_ASCII))
      else if (name.endsWith(".bin")) Right(toBin(words))
      else Left(s"$path: unknown program format (expected .bin or .hex)")
    bytes.flatMap(b =>
      Try { Files.write(path, b); () }.toEither.left.map(e => s"$path: ${e.getMessage}")
    )
  }
}
