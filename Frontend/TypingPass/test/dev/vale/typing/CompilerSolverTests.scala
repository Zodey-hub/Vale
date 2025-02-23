package dev.vale.typing

import dev.vale.typing.env.ReferenceLocalVariableT
import dev.vale.typing.expression.CallCompiler
import dev.vale.{CodeLocationS, Collector, Err, FileCoordinate, FileCoordinateMap, PackageCoordinate, RangeS, vassert, vfail}
import dev.vale.typing.types.{BoolT, BorrowT, CoordT, IntT, InterfaceTT, OwnT, ShareT, StrT, StructTT}
import dev.vale._
import dev.vale.postparsing._
import dev.vale.postparsing.rules.CoordComponentsSR
import dev.vale.solver.RuleError
import OverloadResolver.{FindFunctionFailure, InferFailure, SpecificParamDoesntSend, WrongNumberOfArguments}
import dev.vale.postparsing.{CodeRuneS, ImplicitRuneS, LocationInDenizen}
import dev.vale.postparsing.rules.{CoordComponentsSR, KindComponentsSR, RuneUsage}
import dev.vale.solver.{FailedSolve, IncompleteSolve, RuleError, SolverConflict, Step}
import dev.vale.typing.ast.{ConstantIntTE, FunctionCallTE, KindExportT, PrototypeT, SignatureT, StructToInterfaceUpcastTE}
import dev.vale.typing.infer.{KindIsNotConcrete, SendingNonCitizen}
import dev.vale.typing.names.{CitizenNameT, CitizenTemplateNameT, FullNameT, FunctionNameT}
import dev.vale.typing.templata.{CoordTemplata, KindTemplata, OwnershipTemplata, simpleName}
import dev.vale.typing.ast._
import dev.vale.typing.infer.SendingNonCitizen
import dev.vale.typing.names.CitizenTemplateNameT
import dev.vale.typing.templata._
import dev.vale.typing.types._
//import dev.vale.typingpass.infer.NotEnoughToSolveError
import org.scalatest.{FunSuite, Matchers}

import scala.io.Source

class CompilerSolverTests extends FunSuite with Matchers {
  // TODO: pull all of the typingpass specific stuff out, the unit test-y stuff

  def readCodeFromResource(resourceFilename: String): String = {
    val is = Source.fromInputStream(getClass().getClassLoader().getResourceAsStream(resourceFilename))
    vassert(is != null)
    is.mkString("")
  }


  test("Humanize errors") {
    val interner = new Interner()
    val keywords = new Keywords(interner)
    val tz = RangeS.testZero(interner)
    val testPackageCoord = PackageCoordinate.TEST_TLD(interner, keywords)

    val fireflyKind = StructTT(FullNameT(testPackageCoord, Vector(), CitizenNameT(CitizenTemplateNameT(StrI("Firefly")), Vector())))
    val fireflyCoord = CoordT(OwnT,fireflyKind)
    val serenityKind = StructTT(FullNameT(testPackageCoord, Vector(), CitizenNameT(CitizenTemplateNameT(StrI("Serenity")), Vector())))
    val serenityCoord = CoordT(OwnT,serenityKind)
    val ispaceshipKind = InterfaceTT(FullNameT(testPackageCoord, Vector(), CitizenNameT(CitizenTemplateNameT(StrI("ISpaceship")), Vector())))
    val ispaceshipCoord = CoordT(OwnT,ispaceshipKind)
    val unrelatedKind = StructTT(FullNameT(testPackageCoord, Vector(), CitizenNameT(CitizenTemplateNameT(StrI("Spoon")), Vector())))
    val unrelatedCoord = CoordT(OwnT,unrelatedKind)
    val fireflySignature = SignatureT(FullNameT(testPackageCoord, Vector(), FunctionNameT(interner.intern(StrI("myFunc")), Vector(), Vector(fireflyCoord))))
    val fireflyExport = KindExportT(tz, fireflyKind, testPackageCoord, interner.intern(StrI("Firefly")));
    val serenityExport = KindExportT(tz, fireflyKind, testPackageCoord, interner.intern(StrI("Serenity")));

    val codeStr = "Hello I am A large piece Of code [that has An error]"
    val filenamesAndSources = FileCoordinateMap.test(interner, codeStr)
    def makeLoc(pos: Int) = CodeLocationS(FileCoordinate.test(interner), pos)
    def makeRange(begin: Int, end: Int) = RangeS(makeLoc(begin), makeLoc(end))

    val unsolvedRules =
      Vector(
        CoordComponentsSR(
          makeRange(0, codeStr.length),
          RuneUsage(makeRange(6, 7), CodeRuneS(interner.intern(StrI("I")))),
          RuneUsage(makeRange(11, 12), CodeRuneS(interner.intern(StrI("A")))),
          RuneUsage(makeRange(33, 52), ImplicitRuneS(LocationInDenizen(Vector(7))))),
        KindComponentsSR(
          makeRange(33, 52),
          RuneUsage(makeRange(33, 52), ImplicitRuneS(LocationInDenizen(Vector(7)))),
          RuneUsage(makeRange(43, 45), CodeRuneS(interner.intern(StrI("An"))))))

    vassert(CompilerErrorHumanizer.humanize(false, filenamesAndSources,
      TypingPassSolverError(
        tz,
        FailedSolve(
          Vector(
            Step(
              false,
              Vector(),
              Vector(),
              Map(
                CodeRuneS(interner.intern(StrI("A"))) -> OwnershipTemplata(OwnT)))),
          unsolvedRules,
          RuleError(KindIsNotConcrete(ispaceshipKind)))))
      .nonEmpty)

    val errorText =
      CompilerErrorHumanizer.humanize(false, filenamesAndSources,
        TypingPassSolverError(
          tz,
          IncompleteSolve(
            Vector(
              Step(
                false,
                Vector(),
                Vector(),
                Map(
                  CodeRuneS(interner.intern(StrI("A"))) -> OwnershipTemplata(OwnT)))),
            unsolvedRules,
            Set(
              CodeRuneS(interner.intern(StrI("I"))),
              CodeRuneS(interner.intern(StrI("Of"))),
              CodeRuneS(interner.intern(StrI("An"))),
              ImplicitRuneS(LocationInDenizen(Vector(7)))))))
    println(errorText)
    vassert(errorText.nonEmpty)
    vassert(errorText.contains("\n           ^ A: own"))
    vassert(errorText.contains("\n      ^ I: (unknown)"))
    vassert(errorText.contains("\n                                 ^^^^^^^^^^^^^^^^^^^ _7: (unknown)"))
  }

  test("Simple int rule") {
    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.tup.*;
        |exported func main() int where N int = 3 {
        |  return N;
        |}
        |""".stripMargin
    )
    val coutputs = compile.expectCompilerOutputs()
    Collector.only(coutputs.lookupFunction("main"), { case ConstantIntTE(3, 32) => })
  }

  test("Equals transitive") {
    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.tup.*;
        |exported func main() int where N int = 3, M int = N {
        |  return M;
        |}
        |""".stripMargin
    )
    val coutputs = compile.expectCompilerOutputs()
    Collector.only(coutputs.lookupFunction("main"), { case ConstantIntTE(3, 32) => })
  }

  test("OneOf") {
    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.tup.*;
        |exported func main() int where N int = any(2, 3, 4), N = 3 {
        |  return N;
        |}
        |""".stripMargin
    )
    val coutputs = compile.expectCompilerOutputs()
    Collector.only(coutputs.lookupFunction("main"), { case ConstantIntTE(3, 32) => })
  }

  test("Components") {
    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.tup.*;
        |exported struct MyStruct { }
        |exported func main() X
        |where
        |  MyStruct = Ref[O Ownership, K Kind],
        |  X Ref = Ref[borrow, K]
        |{
        |  return &MyStruct();
        |}
        |""".stripMargin
    )
    val coutputs = compile.expectCompilerOutputs()
    coutputs.lookupFunction("main").header.returnType match {
      case CoordT(BorrowT, StructTT(_)) =>
    }
  }

  test("Prototype rule") {
    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.tup.*;
        |func moo(i int, b bool) str { return "hello"; }
        |exported func main() str
        |where mooFunc Prot = func moo(int, bool)_
        |{
        |  return (mooFunc)(5, true);
        |}
        |""".stripMargin
    )
    val coutputs = compile.expectCompilerOutputs()
    Collector.only(coutputs.lookupFunction("main"), {
      case FunctionCallTE(PrototypeT(simpleName("moo"), _), _) =>
    })
  }

  test("Prototype rule sugar") {
    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.tup.*;
        |func moo(i int, b bool) str { return "hello"; }
        |exported func main() str
        |where func moo(int, bool)_
        |{
        |  return moo(5, true);
        |}
        |""".stripMargin
    )
    val coutputs = compile.expectCompilerOutputs()
    Collector.only(coutputs.lookupFunction("main"), {
      case FunctionCallTE(PrototypeT(simpleName("moo"), _), _) =>
    })
  }

  test("Send struct to struct") {
    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.tup.*;
        |struct MyStruct {}
        |func moo(m MyStruct) { }
        |exported func main() {
        |  moo(MyStruct())
        |}
        |""".stripMargin
    )
    val coutputs = compile.expectCompilerOutputs()
  }

  test("Send struct to interface") {
    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.tup.*;
        |struct MyStruct {}
        |interface MyInterface {}
        |impl MyInterface for MyStruct;
        |func moo(m MyInterface) { }
        |exported func main() {
        |  moo(MyStruct())
        |}
        |""".stripMargin
    )
    val coutputs = compile.expectCompilerOutputs()
  }

  test("Assume most specific generic param") {
    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.tup.*;
        |struct MyStruct {}
        |interface MyInterface {}
        |impl MyInterface for MyStruct;
        |func moo<T>(m T) { }
        |exported func main() {
        |  moo(MyStruct())
        |}
        |""".stripMargin
    )

    val coutputs = compile.expectCompilerOutputs()
    coutputs.lookupFunction("moo").header.params.head.tyype match {
      case CoordT(_, StructTT(_)) =>
    }
  }

  test("Assume most specific common ancestor") {
    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.tup.*;
        |interface IShip {}
        |struct Firefly {}
        |impl IShip for Firefly;
        |struct Serenity {}
        |impl IShip for Serenity;
        |func moo<T>(a T, b T) { }
        |exported func main() {
        |  moo(Firefly(), Serenity())
        |}
        |""".stripMargin
    )

    val coutputs = compile.expectCompilerOutputs()
    val moo = coutputs.lookupFunction("moo")
    moo.header.params.head.tyype match {
      case CoordT(_, InterfaceTT(_)) =>
    }
    val main = coutputs.lookupFunction("main")
    Collector.all(main, {
      case StructToInterfaceUpcastTE(_, _) =>
    }).size shouldEqual 2
  }

  test("Descendant satisfying call") {
    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.tup.*;
        |interface IShip<T> where T Ref {}
        |struct Firefly<T> where T Ref {}
        |impl<T> IShip<T> for Firefly<T>;
        |func moo<T>(a IShip<T>) { }
        |exported func main() {
        |  moo(Firefly<int>())
        |}
        |""".stripMargin
    )

    val coutputs = compile.expectCompilerOutputs()
    val moo = coutputs.lookupFunction("moo")
    moo.header.params.head.tyype match {
      case CoordT(_, InterfaceTT(FullNameT(_, _, CitizenNameT(_, Vector(CoordTemplata(CoordT(_, IntT(_)))))))) =>
    }
  }

  test("Reports incomplete solve") {
    val interner = new Interner()
    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.tup.*;
        |exported func main() int where N int {
        |  M
        |}
        |""".stripMargin,
      interner)
    compile.getCompilerOutputs() match {
      case Err(TypingPassSolverError(_,IncompleteSolve(_,Vector(),unsolved))) => {
        unsolved shouldEqual Set(CodeRuneS(interner.intern(interner.intern(StrI("N")))))
      }
    }
  }


  test("Stamps an interface template via a function return") {
    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.tup.*;
        |interface MyInterface<X> where X Ref { }
        |
        |struct SomeStruct<X> where X Ref { x X; }
        |impl<X> MyInterface<X> for SomeStruct<X>;
        |
        |func doAThing<T>(t T) SomeStruct<T> {
        |  return SomeStruct<T>(t);
        |}
        |
        |exported func main() {
        |  doAThing(4);
        |}
        |""".stripMargin
    )
    val coutputs = compile.expectCompilerOutputs()
  }

  test("Pointer becomes share if kind is immutable") {
    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.tup.*;
        |
        |struct SomeStruct imm { i int; }
        |
        |func bork(x &SomeStruct) int {
        |  return x.i;
        |}
        |
        |exported func main() int {
        |  return bork(SomeStruct(7));
        |}
        |""".stripMargin
    )
    val coutputs = compile.expectCompilerOutputs()
    coutputs.lookupFunction("bork").header.params.head.tyype.ownership shouldEqual ShareT
  }

  test("Detects conflict between types") {
    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.tup.*;
        |struct ShipA {}
        |struct ShipB {}
        |exported func main() where N Kind = ShipA, N Kind = ShipB {
        |}
        |""".stripMargin
    )
    compile.getCompilerOutputs() match {
      case Err(TypingPassSolverError(_, FailedSolve(_, _, SolverConflict(_, KindTemplata(StructTT(FullNameT(_,_,CitizenNameT(CitizenTemplateNameT(StrI("ShipA")),_)))), KindTemplata(StructTT(FullNameT(_,_,CitizenNameT(CitizenTemplateNameT(StrI("ShipB")),_)))))))) =>
      case Err(TypingPassSolverError(_, FailedSolve(_, _, SolverConflict(_, KindTemplata(StructTT(FullNameT(_,_,CitizenNameT(CitizenTemplateNameT(StrI("ShipB")),_)))), KindTemplata(StructTT(FullNameT(_,_,CitizenNameT(CitizenTemplateNameT(StrI("ShipA")),_)))))))) =>
      case other => vfail(other)
    }
  }

  test("Can match KindTemplataType against StructEnvEntry / StructTemplata") {
    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.tup.*;
        |
        |struct SomeStruct<T> { x T; }
        |
        |func bork<X, Z>() Z
        |where X Kind = SomeStruct<int>, X = SomeStruct<Z> {
        |  return 9;
        |}
        |
        |exported func main() int {
        |  return bork();
        |}
        |""".stripMargin
    )
    val coutputs = compile.expectCompilerOutputs()
    coutputs.lookupFunction("bork").header.fullName.last.templateArgs.last shouldEqual CoordTemplata(CoordT(ShareT, IntT(32)))
  }

  test("Can turn a borrow coord into an owning coord") {
    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.tup.*;
        |
        |struct SomeStruct { }
        |
        |func bork<T>(x T) ^T {
        |  return SomeStruct();
        |}
        |
        |exported func main() {
        |  bork(SomeStruct());
        |}
        |""".stripMargin
    )
    val coutputs = compile.expectCompilerOutputs()
    coutputs.lookupFunction("bork").header.fullName.last.templateArgs.last match {
      case CoordTemplata(CoordT(OwnT, _)) =>
    }
  }

  test("Can destructure and assemble tuple") {
    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.tup.*;
        |
        |func swap<T, Y>(x (T, Y)) (Y, T) {
        |  [a, b] = x;
        |  return (b, a);
        |}
        |
        |exported func main() bool {
        |  return swap((5, true)).0;
        |}
        |""".stripMargin
    )
    val coutputs = compile.expectCompilerOutputs()
    coutputs.lookupFunction("swap").header.fullName.last.templateArgs.last match {
      case CoordTemplata(CoordT(ShareT, BoolT())) =>
    }
  }

  test("Can destructure and assemble static sized array") {
    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.tup.*;
        |import v.builtins.arrays.*;
        |
        |func swap<N, T>(x [#N]T) [#N]T {
        |  [a, b] = x;
        |  return [#][b, a];
        |}
        |
        |exported func main() int {
        |  return swap([#][5, 7]).0;
        |}
        |""".stripMargin
    )
    val coutputs = compile.expectCompilerOutputs()
    coutputs.lookupFunction("swap").header.fullName.last.templateArgs.last match {
      case CoordTemplata(CoordT(ShareT, IntT(32))) =>
    }
  }

  test("Impl rule") {
    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.tup.*;
        |
        |interface IShip {
        |  func getFuel(virtual self &IShip) int;
        |}
        |struct Firefly {}
        |func getFuel(self &Firefly) int { return 7; }
        |impl IShip for Firefly;
        |
        |func genericGetFuel<T>(x T) int
        |where implements(T, IShip) {
        |  return x.getFuel();
        |}
        |
        |exported func main() int {
        |  return genericGetFuel(Firefly());
        |}
        |""".stripMargin
    )
    val coutputs = compile.expectCompilerOutputs()
    coutputs.lookupFunction("genericGetFuel").header.fullName.last.templateArgs.last match {
      case CoordTemplata(CoordT(_,StructTT(FullNameT(_,_,CitizenNameT(CitizenTemplateNameT(StrI("Firefly")),_))))) =>
    }
  }

  test("Prototype rule to get return type") {
    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.tup.*;
        |import v.builtins.panic.*;
        |
        |func moo(i int, b bool) str { return "hello"; }
        |
        |exported func main() R
        |where mooFunc Prot = Prot["moo", Refs(int, bool), R Ref] {
        |  __vbi_panic();
        |}
        |
        |""".stripMargin
    )
    val coutputs = compile.expectCompilerOutputs()
    coutputs.lookupFunction("main").header.returnType match {
      case CoordT(_,StrT()) =>
    }
  }

  test("Detects sending non-citizen to citizen") {
    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.tup.*;
        |interface MyInterface {}
        |func moo<T>(a T)
        |where implements(T, MyInterface)
        |{ }
        |exported func main() {
        |  moo(7);
        |}
        |""".stripMargin
    )
    compile.getCompilerOutputs() match {
      case Err(CouldntFindFunctionToCallT(range, fff)) => {
        fff.rejectedCalleeToReason.map(_._2).head match {
          case InferFailure(reason) => {
            reason match {
              case FailedSolve(_, _, RuleError(SendingNonCitizen(IntT(32)))) =>
              case other => vfail(other)
            }
          }
        }
      }
    }
  }
}
