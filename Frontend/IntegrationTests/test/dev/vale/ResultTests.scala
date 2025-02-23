package dev.vale

import dev.vale.testvm.PanicException
import dev.vale.von.{VonInt, VonStr}
import org.scalatest.{FunSuite, Matchers}

class ResultTests extends FunSuite with Matchers {
  test("Test borrow is_ok and expect for Ok") {
    val compile = RunCompilation.test(
        """
          |import v.builtins.panic.*;
          |import v.builtins.result.*;
          |
          |exported func main() int {
          |  result Result<int, str> = Ok<int, str>(42);
          |  return if (result.is_ok()) { result.expect() }
          |    else { panic("wat") };
          |}
        """.stripMargin)

    compile.evalForKind(Vector()) match { case VonInt(42) => }
  }

  test("Test is_err and borrow expect_err for Err") {
    val compile = RunCompilation.test(
        """
          |import v.builtins.panic.*;
          |import v.builtins.result.*;
          |
          |exported func main() str {
          |  result Result<int, str> = Err<int, str>("file not found!");
          |  return if (result.is_err()) { result.expect_err() }
          |    else { panic("fail!") };
          |}
        """.stripMargin)

    compile.evalForKind(Vector()) match { case VonStr("file not found!") => }
  }

  test("Test owning expect") {
    val compile = RunCompilation.test(
      """
        |import v.builtins.panic.*;
        |import v.builtins.result.*;
        |
        |exported func main() int {
        |  result Result<int, str> = Ok<int, str>(42);
        |  return (result).expect();
        |}
        """.stripMargin)

    compile.evalForKind(Vector()) match { case VonInt(42) => }
  }

  test("Test owning expect_err") {
    val compile = RunCompilation.test(
      """
        |import v.builtins.panic.*;
        |import v.builtins.result.*;
        |
        |exported func main() str {
        |  result Result<int, str> = Err<int, str>("file not found!");
        |  return (result).expect_err();
        |}
        """.stripMargin)

    compile.evalForKind(Vector()) match { case VonStr("file not found!") => }
  }

  test("Test expect() panics for Err") {
    val compile = RunCompilation.test(
        """
          |import v.builtins.panic.*;
          |import v.builtins.result.*;
          |
          |exported func main() int {
          |  result Result<int, str> = Err<int, str>("file not found!");
          |  return result.expect();
          |}
        """.stripMargin)

    try {
      compile.evalForKind(Vector())
      vfail()
    } catch {
      case PanicException() =>
    }
  }

  test("Test expect_err() panics for Ok") {
    val compile = RunCompilation.test(
      """
        |import v.builtins.panic.*;
        |import v.builtins.result.*;
        |
        |exported func main() str {
        |  result Result<int, str> = Ok<int, str>(73);
        |  return result.expect_err();
        |}
        """.stripMargin)

    try {
      compile.evalForKind(Vector())
      vfail()
    } catch {
      case PanicException() =>
    }
  }
}
