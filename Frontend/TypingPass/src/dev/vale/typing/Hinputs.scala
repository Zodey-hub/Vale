package dev.vale.typing

import dev.vale.typing.ast.{EdgeT, FunctionExportT, FunctionExternT, FunctionT, InterfaceEdgeBlueprint, KindExportT, KindExternT, PrototypeT, SignatureT}
import dev.vale.typing.names.{CitizenNameT, CitizenTemplateNameT, FullNameT, FunctionNameT, IFunctionNameT, LambdaCitizenNameT}
import dev.vale.typing.templata.simpleName
import dev.vale.typing.types.{InterfaceDefinitionT, InterfaceTT, KindT, StructDefinitionT, StructTT}
import dev.vale.{StrI, vassertSome, vcurious, vfail}
import dev.vale.typing.ast._
import dev.vale.typing.names._
import dev.vale.typing.types._

case class Hinputs(
  interfaces: Vector[InterfaceDefinitionT],
  structs: Vector[StructDefinitionT],
//  emptyPackStructRef: StructTT,
  functions: Vector[FunctionT],
  immKindToDestructor: Map[KindT, PrototypeT],
  edgeBlueprintsByInterface: Map[InterfaceTT, InterfaceEdgeBlueprint],
  edges: Vector[EdgeT],
  kindExports: Vector[KindExportT],
  functionExports: Vector[FunctionExportT],
  kindExterns: Vector[KindExternT],
  functionExterns: Vector[FunctionExternT]) {
  override def equals(obj: Any): Boolean = vcurious(); override def hashCode(): Int = vfail() // Would need a really good reason to hash something this big

  def lookupStruct(structTT: StructTT): StructDefinitionT = {
    structs.find(_.getRef == structTT) match {
      case None => vfail("Couldn't find struct: " + structTT)
      case Some(s) => s
    }
  }

  def lookupInterface(interfaceTT: InterfaceTT): InterfaceDefinitionT = {
    vassertSome(interfaces.find(_.getRef == interfaceTT))
  }

  def lookupFunction(signature2: SignatureT): Option[FunctionT] = {
    functions.find(_.header.toSignature == signature2).headOption
  }

  def lookupFunction(humanName: String): FunctionT = {
    val matches = functions.filter(f => {
      f.header.fullName.last match {
        case FunctionNameT(n, _, _) if n.str == humanName => true
        case _ => false
      }
    })
    if (matches.size == 0) {
      vfail("Function \"" + humanName + "\" not found!")
    } else if (matches.size > 1) {
      vfail("Multiple found!")
    }
    matches.head
  }

  def lookupStruct(humanName: String): StructDefinitionT = {
    val matches = structs.filter(s => {
      s.fullName.last match {
        case CitizenNameT(CitizenTemplateNameT(n), _) if n.str == humanName => true
        case _ => false
      }
    })
    if (matches.size == 0) {
      vfail("Struct \"" + humanName + "\" not found!")
    } else if (matches.size > 1) {
      vfail("Multiple found!")
    }
    matches.head
  }

  def lookupImpl(structTT: StructTT, interfaceTT: InterfaceTT): EdgeT = {
    edges.find(impl => impl.struct == structTT && impl.interface == interfaceTT).get
  }

  def lookupInterface(humanName: String): InterfaceDefinitionT = {
    val matches = interfaces.filter(s => {
      s.fullName.last match {
        case CitizenNameT(CitizenTemplateNameT(n), _) if n.str == humanName => true
        case _ => false
      }
    })
    if (matches.size == 0) {
      vfail("Interface \"" + humanName + "\" not found!")
    } else if (matches.size > 1) {
      vfail("Multiple found!")
    }
    matches.head
  }

  def lookupUserFunction(humanName: String): FunctionT = {
    val matches =
      functions
        .filter(function => simpleName.unapply(function.header.fullName).contains(humanName))
        .filter(_.header.isUserFunction)
    if (matches.size == 0) {
      vfail("Not found!")
    } else if (matches.size > 1) {
      vfail("Multiple found!")
    }
    matches.head
  }

  def nameIsLambdaIn(name: FullNameT[IFunctionNameT], needleFunctionHumanName: String): Boolean = {
    val lastThree = name.steps.slice(name.steps.size - 3, name.steps.size)
    lastThree match {
      case Vector(
      FunctionNameT(functionHumanName, _, _),
      LambdaCitizenNameT(_),
      FunctionNameT(StrI("__call"), _, _)) if functionHumanName.str == needleFunctionHumanName => true
      case _ => false
    }
  }

  def lookupLambdaIn(needleFunctionHumanName: String): FunctionT = {
    val matches = functions.filter(f => nameIsLambdaIn(f.header.fullName, needleFunctionHumanName))
    if (matches.size == 0) {
      vfail("Lambda for \"" + needleFunctionHumanName + "\" not found!")
    } else if (matches.size > 1) {
      vfail("Multiple found!")
    }
    matches.head
  }

  def getAllNonExternFunctions: Iterable[FunctionT] = {
    functions.filter(!_.header.isExtern)
  }

  def getAllUserFunctions: Iterable[FunctionT] = {
    functions.filter(_.header.isUserFunction)
  }
}
