package dev.vale.typing

//import dev.vale.astronomer.{GlobalFunctionFamilyNameS, INameS, INameA, ImmConcreteDestructorImpreciseNameA, ImmConcreteDestructorNameA, ImmInterfaceDestructorImpreciseNameS}
//import dev.vale.astronomer.VirtualFreeImpreciseNameS
import dev.vale.{Err, Interner, Ok, RangeS, vassert, vassertSome, vcurious}
import dev.vale.postparsing.IImpreciseNameS
import dev.vale.typing.ast.{InterfaceEdgeBlueprint, PrototypeT}
import dev.vale.typing.env.TemplatasStore
import dev.vale.typing.types.{CoordT, InterfaceTT, ParamFilter, StructTT}
import dev.vale.postparsing.GlobalFunctionFamilyNameS
import dev.vale.typing.ast._
import dev.vale.typing.types._
import dev.vale.Err

sealed trait IMethod
case class NeededOverride(
  name: IImpreciseNameS,
  paramFilters: Vector[ParamFilter]
) extends IMethod { val hash = runtime.ScalaRunTime._hashCode(this); override def hashCode(): Int = hash; override def equals(obj: Any): Boolean = vcurious(); }
case class FoundFunction(prototype: PrototypeT) extends IMethod { val hash = runtime.ScalaRunTime._hashCode(this); override def hashCode(): Int = hash; override def equals(obj: Any): Boolean = vcurious(); }

case class PartialEdgeT(
  struct: StructTT,
  interface: InterfaceTT,
  methods: Vector[IMethod]) { val hash = runtime.ScalaRunTime._hashCode(this); override def hashCode(): Int = hash; override def equals(obj: Any): Boolean = vcurious(); }

class EdgeCompiler(
    interner: Interner,
    overloadCompiler: OverloadResolver) {
  def compileITables(coutputs: CompilerOutputs):
  (Vector[InterfaceEdgeBlueprint], Map[InterfaceTT, Map[StructTT, Vector[PrototypeT]]]) = {
    val interfaceEdgeBlueprints =
      makeInterfaceEdgeBlueprints(coutputs)

    val itables =
      interfaceEdgeBlueprints.map(interfaceEdgeBlueprint => {
        val interface = interfaceEdgeBlueprint.interface
        interface -> {
          val overridingImpls = coutputs.getAllImpls().filter(_.interface == interface)
          overridingImpls.map(overridingImpl => {
            val overridingStruct = overridingImpl.struct
            overridingStruct -> {
              interfaceEdgeBlueprint.superFamilyRootBanners.map(abstractFunctionBanner => {
                val abstractFunctionSignature = abstractFunctionBanner.toSignature
                val abstractFunctionParamTypes = abstractFunctionSignature.paramTypes
                val abstractIndex = abstractFunctionBanner.params.indexWhere(_.virtuality.nonEmpty)
                vassert(abstractIndex >= 0)
                val abstractParamType = abstractFunctionParamTypes(abstractIndex)
                val impreciseName =
                  vassertSome(
                    TemplatasStore.getImpreciseName(
                      interner, abstractFunctionSignature.fullName.last))

                val overrideFunctionParamTypes =
                  abstractFunctionParamTypes
                    .updated(abstractIndex, abstractParamType.copy(kind = overridingStruct))

                val range = abstractFunctionBanner.originFunction.map(_.range).getOrElse(RangeS.internal(interner, -2976395))
                val foundFunction =
                  compileOverride(
                    coutputs, range, interface, overridingStruct, impreciseName, overrideFunctionParamTypes)

                foundFunction
              })
            }
          }).toMap
        }
      }).toMap
    (interfaceEdgeBlueprints, itables)
  }

  private def compileOverride(
      coutputs: CompilerOutputs,
      range: RangeS,
      interface: InterfaceTT,
      overridingStruct: StructTT,
      impreciseName: IImpreciseNameS,
      paramTypes: Vector[CoordT]):
  PrototypeT = {
    overloadCompiler.findFunction(
      coutputs.getEnvForKind(interface),
      coutputs,
      range,
      impreciseName,
      Vector.empty, // No explicitly specified ones. It has to be findable just by param filters.
      Array.empty,
      paramTypes.map(ParamFilter(_, None)),
      Vector(coutputs.getEnvForKind(overridingStruct)),
      true) match {
      case Err(e) => throw CompileErrorExceptionT(CouldntFindOverrideT(range, e))
      case Ok(x) => x
    }
  }

  private def makeInterfaceEdgeBlueprints(coutputs: CompilerOutputs): Vector[InterfaceEdgeBlueprint] = {
    val abstractFunctionHeadersByInterfaceWithoutEmpties =
      coutputs.getAllFunctions().flatMap({ case function =>
        function.header.getAbstractInterface match {
          case None => Vector.empty
          case Some(abstractInterface) => Vector(abstractInterface -> function)
        }
      })
        .groupBy(_._1)
        .mapValues(_.map(_._2))
        .map({ case (interfaceTT, functions) =>
          // Sort so that the interface's internal methods are first and in the same order
          // they were declared in. It feels right, and vivem also depends on it
          // when it calls array generators/consumers' first method.
          val interfaceDef = coutputs.getAllInterfaces().find(_.getRef == interfaceTT).get
          // Make sure `functions` has everything that the interface def wanted.
          vassert((interfaceDef.internalMethods.map(_.toSignature).toSet -- functions.map(_.header.toSignature).toSet).isEmpty)
          // Move all the internal methods to the front.
          val orderedMethods =
            interfaceDef.internalMethods ++
              functions.map(_.header).filter(x => {
                !interfaceDef.internalMethods.exists(y => y.toSignature == x.toSignature)
              })
          (interfaceTT -> orderedMethods)
        })
    // Some interfaces would be empty and they wouldn't be in
    // abstractFunctionsByInterfaceWithoutEmpties, so we add them here.
    val abstractFunctionHeadersByInterface =
    abstractFunctionHeadersByInterfaceWithoutEmpties ++
      coutputs.getAllInterfaces().map({ case i =>
        (i.getRef -> abstractFunctionHeadersByInterfaceWithoutEmpties.getOrElse(i.getRef, Set()))
      })

    val interfaceEdgeBlueprints =
      abstractFunctionHeadersByInterface
        .map({ case (interfaceTT, functionHeaders2) =>
          InterfaceEdgeBlueprint(
            interfaceTT,
            // This is where they're given order and get an implied index
            functionHeaders2.map(_.toBanner).toVector)
        })
    interfaceEdgeBlueprints.toVector
  }
}
