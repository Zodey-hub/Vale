package dev.vale.typing

import dev.vale.typing.env.ReferenceLocalVariableT
import dev.vale.{CodeLocationS, Collector, Err, FileCoordinateMap, Interner, PackageCoordinate, RangeS, vassert, vfail}
import dev.vale._
import dev.vale.highertyping.ProgramA
import dev.vale.parsing._
import dev.vale.postparsing.PostParser
import OverloadResolver.{FindFunctionFailure, WrongNumberOfArguments}
import dev.vale.postparsing.{CodeNameS, FunctionNameS}
import dev.vale.typing.ast.{ConstantIntTE, LocalLookupTE, MutateTE, ReferenceMemberLookupTE, SignatureT, StructToInterfaceUpcastTE}
import dev.vale.typing.names.{CitizenNameT, CitizenTemplateNameT, CodeVarNameT, FullNameT, FunctionNameT}
import dev.vale.typing.types.{CoordT, FinalT, ImmutableT, IntT, OwnT, ShareT, StaticSizedArrayTT, StrT, StructTT, VaryingT}
import dev.vale.typing.ast._
import dev.vale.typing.names.CitizenTemplateNameT
import dev.vale.typing.templata._
import dev.vale.typing.types._
import org.scalatest.{FunSuite, Matchers}

import scala.collection.immutable.List
import scala.io.Source

class CompilerMutateTests extends FunSuite with Matchers {
  // TODO: pull all of the typingpass specific stuff out, the unit test-y stuff

  def readCodeFromResource(resourceFilename: String): String = {
    val is = Source.fromInputStream(getClass().getClassLoader().getResourceAsStream(resourceFilename))
    vassert(is != null)
    is.mkString("")
  }

  test("Test mutating a local var") {
    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.tup.*;
        |exported func main() {a = 3; set a = 4; }
        |""".stripMargin)
    val coutputs = compile.expectCompilerOutputs();
    val main = coutputs.lookupFunction("main")
    Collector.only(main, { case MutateTE(LocalLookupTE(_,ReferenceLocalVariableT(FullNameT(_,_, CodeVarNameT(StrI("a"))), VaryingT, _)), ConstantIntTE(4, _)) => })

    val lookup = Collector.only(main, { case l @ LocalLookupTE(range, localVariable) => l })
    val resultCoord = lookup.result.reference
    resultCoord shouldEqual CoordT(ShareT, IntT.i32)
  }

  test("Test mutable member permission") {
    val compile =
      CompilerTestCompilation.test(
        """
          |import v.builtins.tup.*;
          |struct Engine { fuel int; }
          |struct Spaceship { engine! Engine; }
          |exported func main() {
          |  ship = Spaceship(Engine(10));
          |  set ship.engine = Engine(15);
          |}
          |""".stripMargin)
    val coutputs = compile.expectCompilerOutputs();
    val main = coutputs.lookupFunction("main")

    val lookup = Collector.only(main, { case l @ ReferenceMemberLookupTE(_, _, _, _, _) => l })
    val resultCoord = lookup.result.reference
    // See RMLRMO, it should result in the same type as the member.
    resultCoord match {
      case CoordT(OwnT, StructTT(_)) =>
      case x => vfail(x.toString)
    }
  }

  test("Local-set upcasts") {
    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.tup.*;
        |interface IXOption<T> where T Ref { }
        |struct XSome<T> where T Ref { value T; }
        |impl<T> IXOption<T> for XSome<T>;
        |struct XNone<T> where T Ref { }
        |impl<T> IXOption<T> for XNone<T>;
        |
        |exported func main() {
        |  m IXOption<int> = XNone<int>();
        |  set m = XSome(6);
        |}
      """.stripMargin)

    val coutputs = compile.expectCompilerOutputs()
    val main = coutputs.lookupFunction("main")
    Collector.only(main, {
      case MutateTE(_, StructToInterfaceUpcastTE(_, _)) =>
    })
  }

  test("Expr-set upcasts") {
    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.tup.*;
        |interface IXOption<T> where T Ref { }
        |struct XSome<T> where T Ref { value T; }
        |impl<T> IXOption<T> for XSome<T>;
        |struct XNone<T> where T Ref { }
        |impl<T> IXOption<T> for XNone<T>;
        |
        |struct Marine {
        |  weapon! IXOption<int>;
        |}
        |exported func main() {
        |  m = Marine(XNone<int>());
        |  set m.weapon = XSome(6);
        |}
      """.stripMargin)

    val coutputs = compile.expectCompilerOutputs()
    val main = coutputs.lookupFunction("main")
    Collector.only(main, {
      case MutateTE(_, StructToInterfaceUpcastTE(_, _)) =>
    })
  }

  test("Reports when we try to mutate an imm struct") {
    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.tup.*;
        |struct Vec3 imm { x float; y float; z float; }
        |exported func main() int {
        |  v = Vec3(3.0, 4.0, 5.0);
        |  set v.x = 10.0;
        |}
        |""".stripMargin)
    compile.getCompilerOutputs() match {
      case Err(CantMutateFinalMember(_, structTT, memberName)) => {
        structTT match {
          case StructTT(FullNameT(_, _, CitizenNameT(CitizenTemplateNameT(StrI("Vec3")), Vector()))) =>
        }
        memberName.last match {
          case CodeVarNameT(StrI("x")) =>
        }
      }
    }
  }

  test("Reports when we try to mutate a final member in a struct") {
    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.tup.*;
        |struct Vec3 { x float; y float; z float; }
        |exported func main() int {
        |  v = Vec3(3.0, 4.0, 5.0);
        |  set v.x = 10.0;
        |}
        |""".stripMargin)
    compile.getCompilerOutputs() match {
      case Err(CantMutateFinalMember(_, structTT, memberName)) => {
        structTT match {
          case StructTT(FullNameT(_, _, CitizenNameT(CitizenTemplateNameT(StrI("Vec3")), Vector()))) =>
        }
        memberName.last match {
          case CodeVarNameT(StrI("x")) =>
        }
      }
    }
  }

  test("Can mutate an element in a runtime-sized array") {
    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.tup.*;
        |import v.builtins.arrays.*;
        |import v.builtins.drop.*;
        |exported func main() int {
        |  arr = Array<mut, int>(3);
        |  arr.push(0);
        |  arr.push(1);
        |  arr.push(2);
        |  set arr[1] = 10;
        |  return 73;
        |}
        |""".stripMargin)
    compile.expectCompilerOutputs()
  }

  test("Reports when we try to mutate an element in an imm static-sized array") {
    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.tup.*;
        |import ifunction.ifunction1.*;
        |exported func main() int {
        |  arr = #[#10]({_});
        |  set arr[4] = 10;
        |  return 73;
        |}
        |""".stripMargin)
    compile.getCompilerOutputs() match {
      case Err(CantMutateFinalElement(_, arrRef2)) => {
        arrRef2.kind match {
          case StaticSizedArrayTT(10,ImmutableT,FinalT,CoordT(ShareT,IntT(_))) =>
        }
      }
    }
  }

  test("Reports when we try to mutate a local variable with wrong type") {
    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.tup.*;
        |exported func main() {
        |  a = 5;
        |  set a = "blah";
        |}
        |""".stripMargin)
    compile.getCompilerOutputs() match {
      case Err(CouldntConvertForMutateT(_, CoordT(ShareT, IntT.i32), CoordT(ShareT, StrT()))) =>
      case _ => vfail()
    }
  }

  test("Reports when we try to override a non-interface") {
    val compile = CompilerTestCompilation.test(
      """
        |import v.builtins.tup.*;
        |impl int for Bork;
        |struct Bork { }
        |exported func main() {
        |  Bork();
        |}
        |""".stripMargin)
    compile.getCompilerOutputs() match {
      case Err(CantImplNonInterface(_, IntT(32)) )=>
      case _ => vfail()
    }
  }

  test("Humanize errors") {
    val interner = new Interner()
    val keywords = new Keywords(interner)
    val fireflyKind = StructTT(FullNameT(PackageCoordinate.TEST_TLD(interner, keywords), Vector.empty, interner.intern(CitizenNameT(CitizenTemplateNameT(StrI("Firefly")), Vector.empty))))
    val fireflyCoord = CoordT(OwnT,fireflyKind)
    val serenityKind = StructTT(FullNameT(PackageCoordinate.TEST_TLD(interner, keywords), Vector.empty, interner.intern(CitizenNameT(CitizenTemplateNameT(StrI("Serenity")), Vector.empty))))
    val serenityCoord = CoordT(OwnT,serenityKind)

    val filenamesAndSources = FileCoordinateMap.test(interner, "blah blah blah\nblah blah blah")

    val tz = RangeS.testZero(interner)
    vassert(CompilerErrorHumanizer.humanize(false, filenamesAndSources,
      CouldntFindTypeT(tz, "Spaceship")).nonEmpty)
    vassert(CompilerErrorHumanizer.humanize(false, filenamesAndSources,
      CouldntFindFunctionToCallT(
        tz,
        FindFunctionFailure(interner.intern(CodeNameS(interner.intern(StrI("")))), Vector.empty, Map())))
      .nonEmpty)
    vassert(CompilerErrorHumanizer.humanize(false, filenamesAndSources,
      CannotSubscriptT(
        tz,
        fireflyKind))
      .nonEmpty)
    vassert(CompilerErrorHumanizer.humanize(false, filenamesAndSources,
      CouldntFindIdentifierToLoadT(
        tz,
        interner.intern(CodeNameS(StrI("spaceship")))))
      .nonEmpty)
    vassert(CompilerErrorHumanizer.humanize(false, filenamesAndSources,
      CouldntFindMemberT(
        tz,
        "hp"))
      .nonEmpty)
    vassert(CompilerErrorHumanizer.humanize(false, filenamesAndSources,
      BodyResultDoesntMatch(
        tz,
        FunctionNameS(interner.intern(StrI("myFunc")), CodeLocationS.testZero(interner)), fireflyCoord, serenityCoord))
      .nonEmpty)
    vassert(CompilerErrorHumanizer.humanize(false, filenamesAndSources,
      CouldntConvertForReturnT(
        tz,
        fireflyCoord, serenityCoord))
      .nonEmpty)
    vassert(CompilerErrorHumanizer.humanize(false, filenamesAndSources,
      CouldntConvertForMutateT(
        tz,
        fireflyCoord, serenityCoord))
      .nonEmpty)
    vassert(CompilerErrorHumanizer.humanize(false, filenamesAndSources,
      CouldntConvertForMutateT(
        tz,
        fireflyCoord, serenityCoord))
      .nonEmpty)
    vassert(CompilerErrorHumanizer.humanize(false, filenamesAndSources,
      CantMoveOutOfMemberT(
        tz,
        interner.intern(CodeVarNameT(StrI("hp")))))
      .nonEmpty)
    vassert(CompilerErrorHumanizer.humanize(false, filenamesAndSources,
      CantReconcileBranchesResults(
        tz,
        fireflyCoord,
        serenityCoord))
      .nonEmpty)
    vassert(CompilerErrorHumanizer.humanize(false, filenamesAndSources,
      CantUseUnstackifiedLocal(
        tz,
        interner.intern(CodeVarNameT(StrI("firefly")))))
      .nonEmpty)
    vassert(CompilerErrorHumanizer.humanize(false, filenamesAndSources,
      FunctionAlreadyExists(
        tz,
        tz,
        SignatureT(FullNameT(PackageCoordinate.TEST_TLD(interner, keywords), Vector.empty, interner.intern(FunctionNameT(interner.intern(StrI("myFunc")), Vector.empty, Vector.empty))))))
      .nonEmpty)
    vassert(CompilerErrorHumanizer.humanize(false, filenamesAndSources,
      CantMutateFinalMember(
        tz,
        serenityKind,
        FullNameT(PackageCoordinate.TEST_TLD(interner, keywords), Vector.empty, interner.intern(CodeVarNameT(StrI("bork"))))))
      .nonEmpty)
    vassert(CompilerErrorHumanizer.humanize(false, filenamesAndSources,
      LambdaReturnDoesntMatchInterfaceConstructor(
        tz))
      .nonEmpty)
    vassert(CompilerErrorHumanizer.humanize(false, filenamesAndSources,
      IfConditionIsntBoolean(
        tz, fireflyCoord))
      .nonEmpty)
    vassert(CompilerErrorHumanizer.humanize(false, filenamesAndSources,
      WhileConditionIsntBoolean(
        tz, fireflyCoord))
      .nonEmpty)
    vassert(CompilerErrorHumanizer.humanize(false, filenamesAndSources,
      CantImplNonInterface(
        tz, fireflyKind))
      .nonEmpty)
  }
}
