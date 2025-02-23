package dev.vale.typing

import dev.vale.parsing.ast.MutableP
import dev.vale.postparsing.{CodeNameS, CodeRuneS, CoordTemplataType, IImpreciseNameS, IRuneS, MutabilityTemplataType, RuneTypeSolver}
import dev.vale.postparsing.rules.{IRulexSR, RuneParentEnvLookupSR, RuneUsage}
import dev.vale.typing.expression.CallCompiler
import dev.vale.typing.function.DestructorCompiler
import dev.vale.typing.types.{CoordT, FinalT, ImmutableT, IntT, MutabilityT, MutableT, OwnT, ParamFilter, RuntimeSizedArrayTT, ShareT, StaticSizedArrayTT, VariabilityT, VaryingT}
import dev.vale.{CodeLocationS, Err, Interner, Keywords, Ok, Profiler, RangeS, StrI, vassert, vassertOne, vassertSome, vimpl}
import dev.vale.typing.types._
import dev.vale.typing.templata._
import dev.vale.postparsing.rules.RuneParentEnvLookupSR
import dev.vale.postparsing.RuneTypeSolver
import OverloadResolver.FindFunctionFailure
import dev.vale.typing.ast.{DestroyImmRuntimeSizedArrayTE, DestroyStaticSizedArrayIntoFunctionTE, FunctionCallTE, NewImmRuntimeSizedArrayTE, ReferenceExpressionTE, RuntimeSizedArrayLookupTE, StaticArrayFromCallableTE, StaticArrayFromValuesTE, StaticSizedArrayLookupTE}
import dev.vale.typing.env.{CitizenEnvironment, FunctionEnvironmentBox, GlobalEnvironment, IEnvironment, NodeEnvironmentBox, PackageEnvironment, TemplataEnvEntry, TemplataLookupContext, TemplatasStore}
import dev.vale.typing.names.RuneNameT
import dev.vale.typing.templata.{CoordTemplata, ITemplata, IntegerTemplata, MutabilityTemplata, VariabilityTemplata}
import dev.vale.typing.ast._
import dev.vale.typing.citizen.StructCompilerCore
import dev.vale.typing.env.CitizenEnvironment
import dev.vale.typing.names.SelfNameT
import dev.vale.typing.types._
import dev.vale.typing.templata._

import scala.collection.immutable.{List, Set}

class ArrayCompiler(
    opts: TypingPassOptions,
    interner: Interner,
  keywords: Keywords,
    inferCompiler: InferCompiler,
    overloadResolver: OverloadResolver) {

  val runeTypeSolver = new RuneTypeSolver(interner)

  vassert(overloadResolver != null)

  def evaluateStaticSizedArrayFromCallable(
    coutputs: CompilerOutputs,
    fate: IEnvironment,
    range: RangeS,
    rulesA: Vector[IRulexSR],
    maybeElementTypeRuneA: Option[IRuneS],
    sizeRuneA: IRuneS,
    mutabilityRune: IRuneS,
    variabilityRune: IRuneS,
    callableTE: ReferenceExpressionTE):
  StaticArrayFromCallableTE = {
    val runeToType =
      runeTypeSolver.solve(
        opts.globalOptions.sanityCheck,
        opts.globalOptions.useOptimizedSolver,
        (nameS: IImpreciseNameS) => vassertOne(fate.lookupNearestWithImpreciseName(nameS, Set(TemplataLookupContext))).tyype,
        range,
        false,
        rulesA,
        List(),
        true,
        Map()) match {
        case Ok(r) => r
        case Err(e) => throw CompileErrorExceptionT(HigherTypingInferError(range, e))
      }
    val templatas =
      inferCompiler.solveExpectComplete(fate, coutputs, rulesA, runeToType, range, Vector(), Vector())
    val IntegerTemplata(size) = vassertSome(templatas.get(sizeRuneA))
    val mutability = getArrayMutability(templatas, mutabilityRune)
    val variability = getArrayVariability(templatas, variabilityRune)
    val prototype = overloadResolver.getArrayGeneratorPrototype(coutputs, fate, range, callableTE)
    val ssaMT = getStaticSizedArrayKind(fate.globalEnv, coutputs, mutability, variability, size.toInt, prototype.returnType)

    maybeElementTypeRuneA.foreach(elementTypeRuneA => {
      val expectedElementType = getArrayElementType(templatas, elementTypeRuneA)
      if (prototype.returnType != expectedElementType) {
        throw CompileErrorExceptionT(UnexpectedArrayElementType(range, expectedElementType, prototype.returnType))
      }
    })

    val expr2 = ast.StaticArrayFromCallableTE(ssaMT, callableTE, prototype)
    expr2
  }

  def evaluateRuntimeSizedArrayFromCallable(
    coutputs: CompilerOutputs,
    nenv: NodeEnvironmentBox,
    range: RangeS,
    rulesA: Vector[IRulexSR],
    maybeElementTypeRune: Option[IRuneS],
    mutabilityRune: IRuneS,
    sizeTE: ReferenceExpressionTE,
    maybeCallableTE: Option[ReferenceExpressionTE]):
  ReferenceExpressionTE = {
    val runeToType =
      runeTypeSolver.solve(
        opts.globalOptions.sanityCheck,
        opts.globalOptions.useOptimizedSolver,
        nameS => vassertOne(nenv.functionEnvironment.lookupNearestWithImpreciseName(nameS, Set(TemplataLookupContext))).tyype,
        range,
        false,
        rulesA,
        List(),
        true,
        Map(mutabilityRune -> MutabilityTemplataType) ++
          maybeElementTypeRune.map(_ -> CoordTemplataType)) match {
        case Ok(r) => r
        case Err(e) => throw CompileErrorExceptionT(HigherTypingInferError(range, e))
      }
    val templatas =
      inferCompiler.solveExpectComplete(nenv.functionEnvironment, coutputs, rulesA, runeToType, range, Vector(), Vector())
    val mutability = getArrayMutability(templatas, mutabilityRune)

//    val variability = getArrayVariability(templatas, variabilityRune)

    mutability match {
      case ImmutableT => {
        val callableTE =
          maybeCallableTE match {
            case None => {
              throw CompileErrorExceptionT(NewImmRSANeedsCallable(range))
            }
            case Some(c) => c
          }

        val prototype =
          overloadResolver.getArrayGeneratorPrototype(
            coutputs, nenv.functionEnvironment, range, callableTE)
        val rsaMT =
          getRuntimeSizedArrayKind(
            nenv.functionEnvironment.globalEnv, coutputs, prototype.returnType, mutability)

        maybeElementTypeRune.foreach(elementTypeRuneA => {
          val expectedElementType = getArrayElementType(templatas, elementTypeRuneA)
          if (prototype.returnType != expectedElementType) {
            throw CompileErrorExceptionT(UnexpectedArrayElementType(range, expectedElementType, prototype.returnType))
          }
        })

        NewImmRuntimeSizedArrayTE(rsaMT, sizeTE, callableTE, prototype)
      }
      case MutableT => {
        val prototype =
          overloadResolver.findFunction(
            nenv.functionEnvironment
              .addEntries(
                interner,
                Vector(
                  (interner.intern(RuneNameT(CodeRuneS(keywords.M))), TemplataEnvEntry(MutabilityTemplata(MutableT)))) ++
              maybeElementTypeRune.map(e => {
                (interner.intern(RuneNameT(e)), TemplataEnvEntry(CoordTemplata(getArrayElementType(templatas, e))))
              })),
            coutputs,
            range,
            interner.intern(CodeNameS(keywords.Array)),
            Vector(
              RuneParentEnvLookupSR(range, RuneUsage(range, CodeRuneS(keywords.M)))) ++
            maybeElementTypeRune.map(e => {
              RuneParentEnvLookupSR(range, RuneUsage(range, e))
            }),
            Array(CodeRuneS(keywords.M)) ++ maybeElementTypeRune,
            Vector(ParamFilter(sizeTE.result.reference, None)) ++
              maybeCallableTE.map(c => ParamFilter(c.result.reference, None)),
            Vector(),
            true) match {
            case Err(e) => throw CompileErrorExceptionT(CouldntFindFunctionToCallT(range, e))
            case Ok(x) => x
          }

        val elementType =
          prototype.returnType.kind match {
            case RuntimeSizedArrayTT(mutability, elementType) => {
              if (mutability != MutableT) {
                throw CompileErrorExceptionT(RangedInternalErrorT(range, "Array function returned wrong mutability!"))
              }
              elementType
            }
            case _ => {
              throw CompileErrorExceptionT(RangedInternalErrorT(range, "Array function returned wrong type!"))
            }
          }
        maybeElementTypeRune.foreach(elementTypeRuneA => {
          val expectedElementType = getArrayElementType(templatas, elementTypeRuneA)
          if (elementType != expectedElementType) {
            throw CompileErrorExceptionT(UnexpectedArrayElementType(range, expectedElementType, prototype.returnType))
          }
        })
        val callTE =
          FunctionCallTE(prototype, Vector(sizeTE) ++ maybeCallableTE)
        callTE
        //        throw CompileErrorExceptionT(RangedInternalErrorT(range, "Can't construct a mutable runtime array from a callable!"))
      }
    }
  }

  def evaluateStaticSizedArrayFromValues(
      coutputs: CompilerOutputs,
      fate: IEnvironment,
      range: RangeS,
      rulesA: Vector[IRulexSR],
    maybeElementTypeRuneA: Option[IRuneS],
    sizeRuneA: IRuneS,
    mutabilityRuneA: IRuneS,
    variabilityRuneA: IRuneS,
      exprs2: Vector[ReferenceExpressionTE]):
   StaticArrayFromValuesTE = {
    val runeToType =
      runeTypeSolver.solve(
        opts.globalOptions.sanityCheck,
        opts.globalOptions.useOptimizedSolver,
        nameS => vassertOne(fate.lookupNearestWithImpreciseName(nameS, Set(TemplataLookupContext))).tyype,
        range,
        false,
        rulesA,
        List(),
        true,
        Map()) match {
        case Ok(r) => r
        case Err(e) => throw CompileErrorExceptionT(HigherTypingInferError(range, e))
      }
    val memberTypes = exprs2.map(_.result.reference).toSet
    if (memberTypes.size > 1) {
      throw CompileErrorExceptionT(ArrayElementsHaveDifferentTypes(range, memberTypes))
    }
    val memberType = memberTypes.head

    val templatas =
      inferCompiler.solveExpectComplete(
        fate, coutputs, rulesA, runeToType, range, Vector(), Vector())
    maybeElementTypeRuneA.foreach(elementTypeRuneA => {
      val expectedElementType = getArrayElementType(templatas, elementTypeRuneA)
      if (memberType != expectedElementType) {
        throw CompileErrorExceptionT(UnexpectedArrayElementType(range, expectedElementType, memberType))
      }
    })

    val size = getArraySize(templatas, sizeRuneA)
    val mutability = getArrayMutability(templatas, mutabilityRuneA)
    val variability = getArrayVariability(templatas, variabilityRuneA)

        if (size != exprs2.size) {
          throw CompileErrorExceptionT(InitializedWrongNumberOfElements(range, size, exprs2.size))
        }

    val staticSizedArrayType = getStaticSizedArrayKind(fate.globalEnv, coutputs, mutability, variability, exprs2.size, memberType)
    val ownership = if (staticSizedArrayType.mutability == MutableT) OwnT else ShareT
    val finalExpr = StaticArrayFromValuesTE(exprs2, CoordT(ownership, staticSizedArrayType), staticSizedArrayType)
    (finalExpr)
  }

  def evaluateDestroyStaticSizedArrayIntoCallable(
    coutputs: CompilerOutputs,
    fate: FunctionEnvironmentBox,
    range: RangeS,
    arrTE: ReferenceExpressionTE,
    callableTE: ReferenceExpressionTE):
  DestroyStaticSizedArrayIntoFunctionTE = {
    val arrayTT =
      arrTE.result.reference match {
        case CoordT(_, s @ StaticSizedArrayTT(_, _, _, _)) => s
        case other => {
          throw CompileErrorExceptionT(RangedInternalErrorT(range, "Destroying a non-array with a callable! Destroying: " + other))
        }
      }

    val prototype =
      overloadResolver.getArrayConsumerPrototype(
        coutputs, fate, range, callableTE, arrayTT.elementType)

    ast.DestroyStaticSizedArrayIntoFunctionTE(
      arrTE,
      arrayTT,
      callableTE,
      prototype)
  }

  def evaluateDestroyRuntimeSizedArrayIntoCallable(
    coutputs: CompilerOutputs,
    fate: FunctionEnvironmentBox,
    range: RangeS,
    arrTE: ReferenceExpressionTE,
    callableTE: ReferenceExpressionTE):
  DestroyImmRuntimeSizedArrayTE = {
    val arrayTT =
      arrTE.result.reference match {
        case CoordT(_, s @ RuntimeSizedArrayTT(_, _)) => s
        case other => {
          throw CompileErrorExceptionT(RangedInternalErrorT(range, "Destroying a non-array with a callable! Destroying: " + other))
        }
      }

    arrayTT.mutability match {
      case ImmutableT =>
      case MutableT => {
        throw CompileErrorExceptionT(RangedInternalErrorT(range, "Can't destroy a mutable array with a callable!"))
      }
    }

    val prototype =
      overloadResolver.getArrayConsumerPrototype(
        coutputs, fate, range, callableTE, arrayTT.elementType)

    ast.DestroyImmRuntimeSizedArrayTE(
      arrTE,
      arrayTT,
      callableTE,
      prototype)
  }

  def getStaticSizedArrayKind(
    globalEnv: GlobalEnvironment,
    coutputs: CompilerOutputs,
    mutability: MutabilityT,
    variability: VariabilityT,
    size: Int,
    type2: CoordT):
  (StaticSizedArrayTT) = {
//    val rawArrayT2 = RawArrayTT()

    coutputs.getStaticSizedArrayType(size, mutability, variability, type2) match {
      case Some(staticSizedArrayT2) => (staticSizedArrayT2)
      case None => {
        val staticSizedArrayType = interner.intern(StaticSizedArrayTT(size, mutability, variability, type2))
        coutputs.addStaticSizedArray(staticSizedArrayType)
        val staticSizedArrayOwnership = if (mutability == MutableT) OwnT else ShareT
        val staticSizedArrayRefType2 = CoordT(staticSizedArrayOwnership, staticSizedArrayType)

        // We declare the function into the environment that we use to compile the
        // struct, so that those who use the struct can reach into its environment
        // and see the function and use it.
        // See CSFMSEO and SAFHE.
        val arrayEnv =
          CitizenEnvironment(
            globalEnv,
            PackageEnvironment(globalEnv, staticSizedArrayType.getName(interner, keywords), globalEnv.nameToTopLevelEnvironment.values.toVector),
            staticSizedArrayType.getName(interner, keywords),
            TemplatasStore(staticSizedArrayType.getName(interner, keywords), Map(), Map())
              .addEntries(
                interner,
                Vector()))
        coutputs.declareKind(staticSizedArrayType)
        coutputs.declareKindEnv(staticSizedArrayType, arrayEnv)

        (staticSizedArrayType)
      }
    }
  }

  def getRuntimeSizedArrayKind(globalEnv: GlobalEnvironment, coutputs: CompilerOutputs, type2: CoordT, arrayMutability: MutabilityT):
  (RuntimeSizedArrayTT) = {
//    val arrayVariability =
//      arrayMutability match {
//        case ImmutableT => FinalT
//        case MutableT => VaryingT
//      }
//    val rawArrayT2 = RuntimeSizedArrayTT()

    coutputs.getRuntimeSizedArray(arrayMutability, type2) match {
      case Some(staticSizedArrayT2) => (staticSizedArrayT2)
      case None => {
        val runtimeSizedArrayType = interner.intern(RuntimeSizedArrayTT(arrayMutability, type2))
        coutputs.addRuntimeSizedArray(runtimeSizedArrayType)
        val runtimeSizedArrayRefType2 =
          CoordT(
            if (arrayMutability == MutableT) OwnT else ShareT,
            runtimeSizedArrayType)

        // We declare the function into the environment that we use to compile the
        // struct, so that those who use the struct can reach into its environment
        // and see the function and use it.
        // See CSFMSEO and SAFHE.
        val arrayEnv =
          env.CitizenEnvironment(
            globalEnv,
            env.PackageEnvironment(globalEnv, runtimeSizedArrayType.getName(interner, keywords), globalEnv.nameToTopLevelEnvironment.values.toVector),
            runtimeSizedArrayType.getName(interner, keywords),
            env.TemplatasStore(runtimeSizedArrayType.getName(interner, keywords), Map(), Map())
              .addEntries(
                interner,
                Vector()))
        coutputs.declareKind(runtimeSizedArrayType)
        coutputs.declareKindEnv(runtimeSizedArrayType, arrayEnv)

        (runtimeSizedArrayType)
      }
    }
  }

  private def getArrayVariability(templatas: Map[IRuneS, ITemplata], variabilityRuneA: IRuneS) = {
    val VariabilityTemplata(m) = vassertSome(templatas.get(variabilityRuneA))
    m
  }

  private def getArrayMutability(templatas: Map[IRuneS, ITemplata], mutabilityRuneA: IRuneS) = {
    val MutabilityTemplata(m) = vassertSome(templatas.get(mutabilityRuneA))
    m
  }
  private def getArraySize(templatas: Map[IRuneS, ITemplata], sizeRuneA: IRuneS): Int = {
    val IntegerTemplata(m) = vassertSome(templatas.get(sizeRuneA))
    m.toInt
  }
  private def getArrayElementType(templatas: Map[IRuneS, ITemplata], typeRuneA: IRuneS): CoordT = {
    val CoordTemplata(m) = vassertSome(templatas.get(typeRuneA))
    m
  }

  def lookupInStaticSizedArray(
      range: RangeS,
      containerExpr2: ReferenceExpressionTE,
      indexExpr2: ReferenceExpressionTE,
      at: StaticSizedArrayTT) = {
    val StaticSizedArrayTT(size, mutability, variability, memberType) = at
    StaticSizedArrayLookupTE(range, containerExpr2, at, indexExpr2, variability)
  }

  def lookupInUnknownSizedArray(
    range: RangeS,
    containerExpr2: ReferenceExpressionTE,
    indexExpr2: ReferenceExpressionTE,
    rsa: RuntimeSizedArrayTT
  ): RuntimeSizedArrayLookupTE = {
    val RuntimeSizedArrayTT(mutability, memberType) = rsa
    if (indexExpr2.result.reference != CoordT(ShareT, IntT(32))) {
      throw CompileErrorExceptionT(IndexedArrayWithNonInteger(range, indexExpr2.result.reference))
    }
    val variability = mutability match { case ImmutableT => FinalT case MutableT => VaryingT }
    RuntimeSizedArrayLookupTE(range, containerExpr2, rsa, indexExpr2, variability)
  }

}
