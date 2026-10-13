package vliw.isa

import org.scalatest.funsuite.AnyFunSuite

class JsonSpec extends AnyFunSuite {
  private def canonical(text: String): String =
    Json.parse(text).flatMap(Json.canonical).fold(e => fail(e.message), identity)

  test("canonical form sorts keys and drops whitespace") {
    assert(
      canonical("""{ "b": [1, true, null], "a": {"y": 2, "x": "s"} }""") ==
        """{"a":{"x":"s","y":2},"b":[1,true,null]}"""
    )
  }

  test("canonical form escapes only what JSON requires") {
    assert(
      canonical("{\"k\": \"q\\\"b\\\\n\\n\\u0001é/\"}") == "{\"k\":\"q\\\"b\\\\n\\n\\u0001é/\"}"
    )
  }

  test("integers are written without fraction or exponent") {
    assert(canonical("[1.0, -0, 1e3, 9007199254740991]") == "[1,0,1000,9007199254740991]")
  }

  test("non-integer numbers have no canonical form") {
    val result = Json.parse("[1.5]").flatMap(Json.canonical)
    assert(result.left.map(_.message.contains("non-integer")) == Left(true))
  }

  test("duplicate keys are rejected at any depth") {
    assert(Json.parse("""{"a": 1, "a": 2}""").isLeft)
    assert(Json.parse("""{"o": [{"k": 1, "k": 1}]}""").isLeft)
    assert(Json.parse("""{"a": {"k": 1}, "b": {"k": 1}}""").isRight)
  }

  test("malformed JSON is reported, not thrown") {
    assert(Json.parse("""{"a": }""").isLeft)
    assert(Json.parse("""{"a": 1""").isLeft)
  }

  test("sha256 matches a known digest") {
    assert(
      Json.sha256("abc") ==
        "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    )
  }
}
