package dev.vale.simplifying

import dev.vale.{Interner, Keywords, PackageCoordinate, vassert, finalast => m}
import dev.vale.finalast.{BorrowH, EdgeH, InterfaceDefinitionH, InterfaceMethodH, InterfaceRefH, KindH, Mutable, PrototypeH, ReferenceH, StructDefinitionH, StructMemberH, StructRefH, YonderH}
import dev.vale.typing.Hinputs
import dev.vale.typing.ast.{EdgeT, PrototypeT}
import dev.vale.typing.names.{CitizenNameT, CitizenTemplateNameT, FreeNameT, FullNameT, INameT}
import dev.vale.typing.templata.CoordTemplata
import dev.vale.typing.types.{AddressMemberTypeT, CoordT, ImmutableT, InterfaceTT, MutableT, ReferenceMemberTypeT, StructMemberT, StructTT, VariabilityT, VaryingT}
import dev.vale.finalast._
import dev.vale.typing._
import dev.vale.typing.ast._
import dev.vale.typing.names.CitizenTemplateNameT
import dev.vale.typing.types._

import scala.collection.immutable.ListMap


class StructHammer(
    interner: Interner,
    keywords: Keywords,
    nameHammer: NameHammer,
    translatePrototype: (Hinputs, HamutsBox, PrototypeT) => PrototypeH,
    translateReference: (Hinputs, HamutsBox, CoordT) => ReferenceH[KindH]) {
  def translateInterfaces(hinputs: Hinputs, hamuts: HamutsBox): Unit = {
    hinputs.interfaces.foreach(interface => translateInterfaceRef(hinputs, hamuts, interface.getRef))
  }

  private def translateInterfaceRefs(
      hinputs: Hinputs,
    hamuts: HamutsBox,
      interfaceRefs2: Vector[InterfaceTT]):
  (Vector[InterfaceRefH]) = {
    interfaceRefs2.map(translateInterfaceRef(hinputs, hamuts, _))
  }

  def translateInterfaceMethods(
      hinputs: Hinputs,
      hamuts: HamutsBox,
      interfaceTT: InterfaceTT) = {

    val edgeBlueprint = hinputs.edgeBlueprintsByInterface(interfaceTT);

    val methodsH =
      edgeBlueprint.superFamilyRootBanners.map(superFamilyRootBanner => {
        val header = hinputs.lookupFunction(superFamilyRootBanner.toSignature).get.header
        val prototypeH = translatePrototype(hinputs, hamuts, header.toPrototype)
        val virtualParamIndex = header.params.indexWhere(_.virtuality.nonEmpty)
        InterfaceMethodH(prototypeH, virtualParamIndex)
      })

    methodsH
  }

  def translateInterfaceRef(
    hinputs: Hinputs,
    hamuts: HamutsBox,
    interfaceTT: InterfaceTT):
  InterfaceRefH = {
    hamuts.interfaceRefs.get(interfaceTT) match {
      case Some(structRefH) => structRefH
      case None => {
        val fullNameH = nameHammer.translateFullName(hinputs, hamuts, interfaceTT.fullName)
        // This is the only place besides InterfaceDefinitionH that can make a InterfaceRefH
        val temporaryInterfaceRefH = InterfaceRefH(fullNameH);
        hamuts.forwardDeclareInterface(interfaceTT, temporaryInterfaceRefH)
        val interfaceDefT = hinputs.lookupInterface(interfaceTT);


        val methodsH = translateInterfaceMethods(hinputs, hamuts, interfaceTT)

        val interfaceDefH =
          InterfaceDefinitionH(
            fullNameH,
            interfaceDefT.weakable,
            Conversions.evaluateMutability(interfaceDefT.mutability),
            Vector.empty /* super interfaces */,
            methodsH)
        hamuts.addInterface(interfaceTT, interfaceDefH)
        vassert(interfaceDefH.getRef == temporaryInterfaceRefH)

        // Make sure there's a destructor for this shared interface.
        interfaceDefT.mutability match {
          case MutableT => None
          case ImmutableT => {
            vassert(
              hinputs.functions.exists(function => {
                function.header.fullName match {
                  case FullNameT(_, _, FreeNameT(_, k)) if k == interfaceDefT.getRef => true
                  case _ => false
                }
              }))
          }
        }

        (interfaceDefH.getRef)
      }
    }
  }

  def translateStructs(hinputs: Hinputs, hamuts: HamutsBox): Unit = {
    hinputs.structs.foreach(structDefT => translateStructRef(hinputs, hamuts, structDefT.getRef))
  }

  def translateStructRef(
      hinputs: Hinputs,
      hamuts: HamutsBox,
      structTT: StructTT):
  (StructRefH) = {
    hamuts.structRefsByRef2.get(structTT) match {
      case Some(structRefH) => structRefH
      case None => {
        val (fullNameH) = nameHammer.translateFullName(hinputs, hamuts, structTT.fullName)
        // This is the only place besides StructDefinitionH that can make a StructRefH
        val temporaryStructRefH = StructRefH(fullNameH);
        hamuts.forwardDeclareStruct(structTT, temporaryStructRefH)
        val structDefT = hinputs.lookupStruct(structTT);
        val (membersH) =
          translateMembers(hinputs, hamuts, structDefT.fullName, structDefT.members)

        val (edgesH) = translateEdgesForStruct(hinputs, hamuts, temporaryStructRefH, structTT)

        val structDefH =
          StructDefinitionH(
            fullNameH,
            structDefT.weakable,
            Conversions.evaluateMutability(structDefT.mutability),
            edgesH,
            membersH);
        hamuts.addStructOriginatingFromTypingPass(structTT, structDefH)
        vassert(structDefH.getRef == temporaryStructRefH)

        // Make sure there's a destructor for this shared struct.
        structDefT.mutability match {
          case MutableT => None
          case ImmutableT => {
            vassert(
              hinputs.functions.exists(function => {
                function.header.fullName match {
                  case FullNameT(_, _, FreeNameT(_, k)) if k == structDefT.getRef => true
                  case _ => false
                }
              }))
          }
        }


        (structDefH.getRef)
      }
    }
  }

  def translateMembers(hinputs: Hinputs, hamuts: HamutsBox, structName: FullNameT[INameT], members: Vector[StructMemberT]):
  (Vector[StructMemberH]) = {
    members.map(translateMember(hinputs, hamuts, structName, _))
  }

  def translateMember(hinputs: Hinputs, hamuts: HamutsBox, structName: FullNameT[INameT], member2: StructMemberT):
  (StructMemberH) = {
    val (memberH) =
      member2.tyype match {
        case ReferenceMemberTypeT(coord) => {
          translateReference(hinputs, hamuts, coord)
        }
        case AddressMemberTypeT(coord) => {
          val (referenceH) =
            translateReference(hinputs, hamuts, coord)
          val (boxStructRefH) =
            makeBox(hinputs, hamuts, member2.variability, coord, referenceH)
          // The stack owns the box, closure structs just borrow it.
          (ReferenceH(BorrowH, YonderH, boxStructRefH))
        }
      }
    StructMemberH(
      nameHammer.translateFullName(hinputs, hamuts, structName.addStep(member2.name)),
      Conversions.evaluateVariability(member2.variability),
      memberH)
  }

  def makeBox(
    hinputs: Hinputs,
    hamuts: HamutsBox,
    conceptualVariability: VariabilityT,
    type2: CoordT,
    typeH: ReferenceH[KindH]):
  (StructRefH) = {
    val boxFullName2 = FullNameT(PackageCoordinate.BUILTIN(interner, keywords), Vector.empty, interner.intern(CitizenNameT(interner.intern(CitizenTemplateNameT(keywords.BOX_HUMAN_NAME)), Vector(CoordTemplata(type2)))))
    val boxFullNameH = nameHammer.translateFullName(hinputs, hamuts, boxFullName2)
    hamuts.structDefs.find(_.fullName == boxFullNameH) match {
      case Some(structDefH) => (structDefH.getRef)
      case None => {
        val temporaryStructRefH = StructRefH(boxFullNameH);

        // We don't actually care about the given variability, because even if it's final, we still need
        // the box to contain a varying reference, see VCBAAF.
        val _ = conceptualVariability
        val actualVariability = VaryingT

        val memberH =
          StructMemberH(
            nameHammer.addStep(hamuts, temporaryStructRefH.fullName, keywords.BOX_MEMBER_NAME.str),
            Conversions.evaluateVariability(actualVariability), typeH)

        val structDefH =
          StructDefinitionH(
            boxFullNameH,
            false,
            Mutable,
            Vector.empty,
            Vector(memberH));
        hamuts.addStructOriginatingFromHammer(structDefH)
        vassert(structDefH.getRef == temporaryStructRefH)
        (structDefH.getRef)
      }
    }
  }

  private def translateEdgesForStruct(
      hinputs: Hinputs, hamuts: HamutsBox,
      structRefH: StructRefH,
      structTT: StructTT):
  (Vector[EdgeH]) = {
    val edges2 = hinputs.edges.filter(_.struct == structTT)
    translateEdgesForStruct(hinputs, hamuts, structRefH, edges2.toVector)
  }

  private def translateEdgesForStruct(
      hinputs: Hinputs, hamuts: HamutsBox,
      structRefH: StructRefH,
      edges2: Vector[EdgeT]):
  (Vector[EdgeH]) = {
    edges2.map(e => translateEdge(hinputs, hamuts, structRefH, e.interface, e))
  }


  private def translateEdge(hinputs: Hinputs, hamuts: HamutsBox, structRefH: StructRefH, interfaceTT: InterfaceTT, edge2: EdgeT):
  (EdgeH) = {
    // Purposefully not trying to translate the entire struct here, because we might hit a circular dependency
    val interfaceRefH = translateInterfaceRef(hinputs, hamuts, interfaceTT)
    val interfacePrototypesH = translateInterfaceMethods(hinputs, hamuts, interfaceTT)
    val (prototypesH) =
      edge2.methods.map(translatePrototype(hinputs, hamuts, _))
    val structPrototypesByInterfacePrototype = ListMap[InterfaceMethodH, PrototypeH](interfacePrototypesH.zip(prototypesH) : _*)
    (EdgeH(structRefH, interfaceRefH, structPrototypesByInterfacePrototype))
  }
}
