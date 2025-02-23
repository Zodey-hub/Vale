package dev.vale.testvm

import dev.vale.{Interner, Keywords, PackageCoordinate, PackageCoordinateMap, StrI, finalast}
import dev.vale.finalast.{BlockH, CallH, ConstantIntH, FullNameH, FunctionH, InlineH, IntH, PackageH, ProgramH, PrototypeH, ReferenceH, ShareH, UserFunctionH}
import dev.vale.finalast._
import dev.vale.von.{VonArray, VonInt, VonMember, VonObject, VonStr}
import org.scalatest.{FunSuite, Matchers}

class VivemTests extends FunSuite with Matchers {
  test("Return 7") {
    val interner = new Interner()
    val keywords = new Keywords(interner)
    val main =
      FunctionH(
        PrototypeH(
          FullNameH(
            "main",
            0,
            PackageCoordinate.TEST_TLD(interner, keywords),
            Vector(VonObject("F",None,Vector(VonMember("humanName",VonStr("main")), VonMember("templateArgs",VonArray(None,Vector())), VonMember("parameters",VonArray(None,Vector())))))),Vector.empty,ReferenceH(ShareH,InlineH,IntH.i32)),
        true,
        false,
        Vector(UserFunctionH),
        BlockH(ConstantIntH(7, 32)))
    val packages = new PackageCoordinateMap[PackageH]()
    packages.put(PackageCoordinate.TEST_TLD(interner, keywords), PackageH(Vector.empty, Vector.empty, Vector(main), Vector.empty, Vector.empty, Map(), Map(interner.intern(StrI("main")) -> main.prototype), Map(), Map(), Map()))
    val programH = ProgramH(packages)
    val result =
      Vivem.executeWithPrimitiveArgs(programH, Vector(), System.out, Vivem.emptyStdin, Vivem.nullStdout)
    result match { case VonInt(7) => }
  }

  test("Adding") {
    val interner = new Interner()
    val keywords = new Keywords(interner)

    val intRef =
      VonObject("Ref",None,Vector(VonMember("ownership",VonObject("Share",None,Vector())), VonMember("location",VonObject("Inline",None,Vector())), VonMember("kind",VonObject("Int",None,Vector(VonMember("bits", VonInt(32)))))))

    val addPrototype =
      PrototypeH(
        FullNameH(
          "__vbi_addI32",
          0,
          PackageCoordinate.BUILTIN(interner, keywords),
          Vector(VonObject("F",None,Vector(VonMember("humanName",VonStr("__vbi_addI32")), VonMember("templateArgs",VonArray(None,Vector())), VonMember("parameters",VonArray(None,Vector(intRef, intRef))))))),
        Vector(ReferenceH(ShareH,InlineH,IntH.i32), ReferenceH(ShareH,InlineH,IntH.i32)),
        ReferenceH(ShareH,InlineH,IntH.i32))
    val main =
      FunctionH(
        PrototypeH(
          FullNameH(
            "main",
            0,
            PackageCoordinate.TEST_TLD(interner, keywords),
            Vector(VonObject("F",None,Vector(VonMember("humanName",VonStr("main")), VonMember("templateArgs",VonArray(None,Vector())), VonMember("parameters",VonArray(None,Vector())))))),Vector.empty,ReferenceH(finalast.ShareH,InlineH,IntH.i32)),
        true,
        false,
        Vector(UserFunctionH),
        BlockH(
          CallH(
            addPrototype,
            Vector(
              ConstantIntH(52, 32),
              CallH(
                addPrototype,
                Vector(
                  ConstantIntH(53, 32),
                  ConstantIntH(54, 32)))))))
    val addExtern =
      FunctionH(
        addPrototype,
        false,
        true,
        Vector.empty,
        BlockH(ConstantIntH(133337, 32)))

    val packages = new PackageCoordinateMap[PackageH]()
    packages.put(PackageCoordinate.BUILTIN(interner, keywords), PackageH(Vector.empty, Vector.empty, Vector(addExtern), Vector.empty, Vector.empty, Map(), Map(), Map(), Map(interner.intern(StrI("__vbi_addI32")) -> addPrototype), Map()))
    packages.put(PackageCoordinate.TEST_TLD(interner, keywords), PackageH(Vector.empty, Vector.empty, Vector(main), Vector.empty, Vector.empty, Map(), Map(interner.intern(StrI("main")) -> main.prototype), Map(), Map(), Map()))
    val programH = ProgramH(packages)

    val result =
      Vivem.executeWithPrimitiveArgs(programH, Vector(), System.out, Vivem.emptyStdin, Vivem.nullStdout)
    result match { case VonInt(159) => }
  }
}
