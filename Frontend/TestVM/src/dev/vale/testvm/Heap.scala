package dev.vale.testvm

import dev.vale.finalast.{BoolH, BorrowH, FloatH, InlineH, IntH, InterfaceRefH, KindH, Local, LocationH, OwnH, OwnershipH, ProgramH, PrototypeH, ReferenceH, RuntimeSizedArrayDefinitionHT, RuntimeSizedArrayHT, ShareH, StaticSizedArrayDefinitionHT, StaticSizedArrayHT, StrH, StructDefinitionH, StructMemberH, StructRefH, VoidH, WeakH}
import dev.vale.{vassert, vassertSome, vfail, vimpl, von}

import java.io.PrintStream
import dev.vale.finalast._
import dev.vale.von.{IVonData, VonArray, VonBool, VonFloat, VonInt, VonMember, VonObject, VonStr}

import scala.collection.mutable

class AdapterForExterns(
    val programH: ProgramH,
    private val heap: Heap,
    callId: CallId,
    val stdin: (() => String),
    val stdout: (String => Unit)
) {
  def dereference(reference: ReferenceV) = {
    heap.dereference(reference)
  }

  def addAllocationForReturn(ownership: OwnershipH, location: LocationH, kind: KindV): ReferenceV = {
    val ref = heap.add(ownership, location, kind)
//    heap.incrementReferenceRefCount(ResultToObjectReferrer(callId), ref) // incrementing because putting it in a return
//    ReturnV(callId, ref)
    ref
  }

  def makeVoid(): ReferenceV = {
    heap.void
  }
}

class AllocationMap(vivemDout: PrintStream) {
  private val objectsById = mutable.HashMap[AllocationId, Allocation]()
  private val STARTING_ID = 501
  private var nextId = STARTING_ID;
  val void: ReferenceV = add(ShareH, InlineH, VoidV)

  private def newId() = {
    val id = nextId;
    nextId = nextId + 1
    id
  }

  def isEmpty: Boolean = {
    objectsById.isEmpty
  }

  def size = {
    objectsById.size
  }

  def get(allocId: AllocationId) = {
    val allocation = vassertSome(objectsById.get(allocId))
    vassert(allocation.kind.tyype == allocId.tyype)
    allocation
  }

  def remove(allocId: AllocationId): Unit = {
    vassert(contains(allocId))
    vassert(objectsById(allocId).kind != VoidV)
    objectsById.remove(allocId)
  }

  def contains(allocId: AllocationId): Boolean = {
    objectsById.get(allocId) match {
      case None => false
      case Some(allocation) => {
        vassert(allocation.kind.tyype.hamut == allocId.tyype.hamut)
        true
      }
    }
  }

  def add(ownership: OwnershipH, location: LocationH, kind: KindV) = {
    val id = newId()
    if (kind == VoidV) {
      // Make sure it only happens once
      vassert(id == STARTING_ID)
    }
    val reference =
      ReferenceV(
        // These two are the same because when we allocate something,
        // we see it for what it truly is.
        //                                          ~ Wisdom ~
        actualKind = kind.tyype,
        seenAsKind = kind.tyype,
        ownership,
        location,
        id)
    val allocation = new Allocation(reference, kind)
    objectsById.put(reference.allocId, allocation)
    reference
  }

  def printAll(): Unit = {
    objectsById.foreach({
      case (id, allocation) => vivemDout.println(id + " (" + allocation.getTotalRefCount(None) + " refs) = " + allocation.kind)
    })
  }

  def checkForLeaks(): Unit = {
    val nonInternedObjects = objectsById.values.filter(_.kind != VoidV)
    if (nonInternedObjects.nonEmpty) {
      nonInternedObjects
        .map(_.reference.allocId.num)
        .toArray
        .sorted
        .foreach(objId => print("o" + objId + " "))
      println()
      nonInternedObjects.toArray.sortWith(_.reference.allocId.num < _.reference.allocId.num).foreach(_.printRefs())
      vfail("Memory leaks! See above for ")
    }
  }
}

// Just keeps track of all active objects
class Heap(in_vivemDout: PrintStream) {
  val vivemDout = in_vivemDout

  /*private*/ val objectsById = new AllocationMap(vivemDout)

  private val callIdStack = mutable.Stack[CallId]();
  private val callsById = mutable.HashMap[CallId, Call]()

  val void = objectsById.void

  def addLocal(varAddr: VariableAddressV, reference: ReferenceV, expectedType: ReferenceH[KindH]) = {
    val call = getCurrentCall(varAddr.callId)
    call.addLocal(varAddr, reference, expectedType)
    incrementReferenceRefCount(VariableToObjectReferrer(varAddr, expectedType.ownership), reference)
  }

  def getReference(varAddr: VariableAddressV, expectedType: ReferenceH[KindH]) = {
    callsById(varAddr.callId).getLocal(varAddr).reference
  }

  def removeLocal(varAddr: VariableAddressV, expectedType: ReferenceH[KindH]) = {
    val call = getCurrentCall(varAddr.callId)
    val variable = getLocal(varAddr)
    val actualReference = variable.reference
    checkReference(expectedType, actualReference)
    decrementReferenceRefCount(VariableToObjectReferrer(varAddr, expectedType.ownership), actualReference)
    call.removeLocal(varAddr)
  }

  def getReferenceFromLocal(
      varAddr: VariableAddressV,
      expectedType: ReferenceH[KindH],
      targetType: ReferenceH[KindH]
  ): ReferenceV = {
    val variable = getLocal(varAddr)
    if (variable.expectedType != expectedType) {
      vfail("blort")
    }
    checkReference(expectedType, variable.reference)
    transmute(variable.reference, expectedType, targetType)
  }

  private def getLocal(varAddr: VariableAddressV): VariableV = {
    callsById(varAddr.callId).getLocal(varAddr)
  }

  def mutateVariable(varAddress: VariableAddressV, reference: ReferenceV, expectedType: ReferenceH[KindH]): ReferenceV = {
    val variable = callsById(varAddress.callId).getLocal(varAddress)
    checkReference(expectedType, reference)
    checkReference(variable.expectedType, reference)
    val oldReference = variable.reference
    decrementReferenceRefCount(VariableToObjectReferrer(varAddress, expectedType.ownership), oldReference)

    incrementReferenceRefCount(VariableToObjectReferrer(varAddress, expectedType.ownership), reference)
    callsById(varAddress.callId).mutateLocal(varAddress, reference, expectedType)
    oldReference
  }
  def mutateArray(elementAddress: ElementAddressV, reference: ReferenceV, expectedType: ReferenceH[KindH]): ReferenceV = {
    val ElementAddressV(arrayRef, elementIndex) = elementAddress
    objectsById.get(arrayRef).kind match {
      case ai @ ArrayInstanceV(_, _, _, _) => {
        val oldReference = ai.getElement(elementIndex)
        decrementReferenceRefCount(ElementToObjectReferrer(elementAddress, expectedType.ownership), oldReference)

        ai.setElement(elementIndex, reference)
        incrementReferenceRefCount(ElementToObjectReferrer(elementAddress, expectedType.ownership), reference)
        oldReference
      }
    }
  }
  def mutateStruct(memberAddress: MemberAddressV, reference: ReferenceV, expectedType: ReferenceH[KindH]):
  ReferenceV = {
    val MemberAddressV(objectId, fieldIndex) = memberAddress
    objectsById.get(objectId).kind match {
      case si @ StructInstanceV(structDefH, Some(members)) => {
        val oldMemberReference = members(fieldIndex)
        decrementReferenceRefCount(MemberToObjectReferrer(memberAddress, expectedType.ownership), oldMemberReference)
//        maybeDeallocate(actualReference)
        vassert(structDefH.members(fieldIndex).tyype == expectedType)
        // We only do this to check that it's non-empty. curiosity assert, do we gotta do somethin special if somethin was moved out
        si.getReferenceMember(fieldIndex)
        si.setReferenceMember(fieldIndex, reference)
        incrementReferenceRefCount(MemberToObjectReferrer(memberAddress, expectedType.ownership), reference)
        oldMemberReference
      }
    }
  }

  def getReferenceFromStruct(
    address: MemberAddressV,
    expectedType: ReferenceH[KindH],
    targetType: ReferenceH[KindH]
  ): ReferenceV = {
    val MemberAddressV(objectId, fieldIndex) = address
    objectsById.get(objectId).kind match {
      case StructInstanceV(_, Some(members)) => {
        val actualReference = members(fieldIndex)
        checkReference(expectedType, actualReference)
        transmute(actualReference, expectedType, targetType)
      }
    }
  }
  def getReferenceFromArray(
    address: ElementAddressV,
    expectedType: ReferenceH[KindH],
    targetType: ReferenceH[KindH]
  ): ReferenceV = {
    val ElementAddressV(objectId, elementIndex) = address
    objectsById.get(objectId).kind match {
      case ai @ ArrayInstanceV(_, _, _, _) => {
        val ref = ai.getElement(elementIndex)
        checkReference(expectedType, ref)
        transmute(ref, expectedType, targetType)
      }
    }
  }

  def containsLiveObject(reference: ReferenceV): Boolean = {
    containsLiveObject(reference.allocId)
  }

  private def containsLiveObject(allocId: AllocationId): Boolean = {
    if (!objectsById.contains(allocId))
      return false
    val alloc = objectsById.get(allocId)
    alloc.kind match {
      case StructInstanceV(_, None) => false
      case StructInstanceV(_, Some(_)) => true
      case _ => true
    }
  }

  // Undead meaning it's been zero'd out, but its still allocated because a weak
  // is pointing at it.
  private def containsLiveOrUndeadObject(allocId: AllocationId): Boolean = {
    objectsById.contains(allocId)
  }

  // Undead meaning it's been zero'd out, but its still allocated because a weak
  // is pointing at it.
  def dereference(reference: ReferenceV, allowUndead: Boolean = false): KindV = {
    if (!allowUndead) {
      vassert(containsLiveObject(reference.allocId))
    }
    objectsById.get(reference.allocId).kind
  }

  def isSameInstance(callId: CallId, left: ReferenceV, right: ReferenceV): ReferenceV = {
    val ref = allocateTransient(ShareH, InlineH, BoolV(left.allocId == right.allocId))
    incrementReferenceRefCount(RegisterToObjectReferrer(callId, ShareH), ref)
    ref
  }

  def incrementReferenceHoldCount(expressionId: ExpressionId, reference: ReferenceV) = {
    incrementObjectRefCount(RegisterHoldToObjectReferrer(expressionId, reference.ownership), reference.allocId)
  }

  def decrementReferenceHoldCount(expressionId: ExpressionId, reference: ReferenceV) = {
    decrementObjectRefCount(RegisterHoldToObjectReferrer(expressionId, reference.ownership), reference.allocId)
  }

  // rename to incrementObjectRefCount
  def incrementReferenceRefCount(referrer: IObjectReferrer, reference: ReferenceV) = {
    incrementObjectRefCount(referrer, reference.allocId, reference.ownership == WeakH)
  }

  // rename to decrementObjectRefCount
  def decrementReferenceRefCount(referrer: IObjectReferrer, reference: ReferenceV) = {
    decrementObjectRefCount(referrer, reference.allocId, reference.ownership == WeakH)
  }

  def destructureArray(reference: ReferenceV): Vector[ReferenceV] = {
    val allocation = dereference(reference)
    allocation match {
      case ArrayInstanceV(_, _, _, elements) => {
        val elementRefs =
          elements.indices.toVector
            .reverse
            .map(index => deinitializeArrayElement(reference))
            .reverse
        elementRefs
      }
    }
  }

  def destructure(reference: ReferenceV): Vector[ReferenceV] = {
    val allocation = dereference(reference)
    allocation match {
      case StructInstanceV(structDefH, Some(memberRefs)) => {
        memberRefs.zipWithIndex.foreach({ case (memberRef, index) =>
          decrementReferenceRefCount(
            MemberToObjectReferrer(
              MemberAddressV(reference.allocId, index), memberRef.ownership),
            memberRef)
        })

        zero(reference)
        deallocateIfNoWeakRefs(reference)
        memberRefs
      }
    }
  }

  // This is *conceptually* destroying the object, this is when the owning
  // reference disappears. If there are no weak refs, we also deallocate.
  // Otherwise, we deallocate when the last weak ref disappears.
  def zero(reference: ReferenceV) = {
    val allocation = objectsById.get(reference.allocId)
    vassert(allocation.getTotalRefCount(Some(OwnH)) == 0)
    vassert(allocation.getTotalRefCount(Some(BorrowH)) == 0)
    allocation.kind match {
      case si @ StructInstanceV(_, _) => si.zero()
      case _ =>
    }
    vivemDout.print(" o" + reference.allocId.num + "zero")
  }

  // This happens when the last owning and weak references disappear.
  def deallocateIfNoWeakRefs(reference: ReferenceV) = {
    val allocation = objectsById.get(reference.allocId)
    if (reference.actualCoord.hamut.ownership == OwnH && allocation.getTotalRefCount(Some(BorrowH)) > 0) {
      throw ConstraintViolatedException("Constraint violated!")
    }
    if (allocation.getTotalRefCount(None) == 0) {
      objectsById.remove(reference.allocId)
      vivemDout.print(" o" + reference.allocId.num + "dealloc")
    }
  }

  private def incrementObjectRefCount(
      pointingFrom: IObjectReferrer,
      allocId: AllocationId,
      allowUndead: Boolean = false): Unit = {
    if (!allowUndead) {
      if (!containsLiveObject(allocId)) {
        vfail("Trying to increment dead object: " + allocId)
      }
    }
    val obj = objectsById.get(allocId)
    obj.incrementRefCount(pointingFrom)
    val newRefCount = obj.getTotalRefCount(None)
    vivemDout.print(" o" + allocId.num + "rc" + (newRefCount - 1) + "->" + newRefCount)
  }

  private def decrementObjectRefCount(
      pointedFrom: IObjectReferrer,
      allocId: AllocationId,
      allowUndead: Boolean = false):
  Int = {
    if (!allowUndead) {
      if (!containsLiveObject(allocId)) {
        vfail("Can't decrement object " + allocId + ", not in heap!")
      }
    }
    val obj = objectsById.get(allocId)
    obj.decrementRefCount(pointedFrom)
    val newRefCount = obj.getTotalRefCount(None)
    vivemDout.print(" o" + allocId.num + "rc" + (newRefCount + 1) + "->" + newRefCount)
//    if (newRefCount == 0) {
//      deallocate(objectId)
//    }
    newRefCount
  }

  def getRefCount(reference: ReferenceV): Int = {
    if (reference.actualKind.hamut == VoidH()) {
      // Let's pretend Void permanently has 1 RC, so nobody tries to free it
      return 1
    }
    if (reference.ownership == WeakH) {
      vassert(containsLiveOrUndeadObject(reference.allocId))
    } else {
      vassert(containsLiveObject(reference.allocId))
    }
    val allocation = objectsById.get(reference.allocId)
    allocation.getRefCount()
  }

  def getTotalRefCount(reference: ReferenceV): Int = {
    if (reference.ownership == WeakH) {
      vassert(containsLiveOrUndeadObject(reference.allocId))
    } else {
      vassert(containsLiveObject(reference.allocId))
    }
    val allocation = objectsById.get(reference.allocId)
    allocation.getTotalRefCount(None)
  }

  def ensureRefCount(reference: ReferenceV, ownershipFilter: Option[Set[OwnershipH]], expectedNum: Int) = {
    vassert(containsLiveObject(reference.allocId))
    val allocation = objectsById.get(reference.allocId)
    allocation.ensureRefCount(ownershipFilter, expectedNum)
  }

  def add(ownership: OwnershipH, location: LocationH, kind: KindV): ReferenceV = {
    objectsById.add(ownership, location, kind)
  }

  def transmute(
    reference: ReferenceV,
    expectedType: ReferenceH[KindH],
    targetType: ReferenceH[KindH]):
  ReferenceV = {
    if (expectedType == targetType) {
      return reference
    }

    val ReferenceV(actualKind, oldSeenAsType, oldOwnership, oldLocation, objectId) = reference
    vassert((oldOwnership == ShareH) == (targetType.ownership == ShareH))
    if (oldSeenAsType.hamut != expectedType.kind) {
      // not sure if the above .actualType is right

      vfail("wot")
    }

    ReferenceV(
      actualKind,
      RRKind(targetType.kind),
      targetType.ownership,
      targetType.location,
      objectId)
  }

  def cast(
    callId: CallId,
    reference: ReferenceV,
    expectedType: ReferenceH[KindH],
    targetType: ReferenceH[KindH]):
  ReferenceV = {
    if (expectedType == targetType) {
      return reference
    }

    val ReferenceV(actualKind, oldSeenAsType, oldOwnership, oldLocation, objectId) = reference
    vassert((oldOwnership == ShareH) == (targetType.ownership == ShareH))
    if (oldSeenAsType.hamut != expectedType.kind) {
      // not sure if the above .actualType is right

      vfail("wot")
    }

    incrementReferenceRefCount(RegisterToObjectReferrer(callId, targetType.ownership), reference)
    decrementReferenceRefCount(RegisterToObjectReferrer(callId, oldOwnership), reference)

    ReferenceV(
      actualKind,
      RRKind(targetType.kind),
      targetType.ownership,
      targetType.location,
      objectId)
  }

  def isEmpty: Boolean = {
    objectsById.isEmpty
  }

  def printAll() = {
    objectsById.printAll()
  }

  def countUnreachableAllocations(roots: Vector[ReferenceV]) = {
    val numReachables = findReachableAllocations(roots).size
    vassert(numReachables <= objectsById.size)
    objectsById.size - numReachables
  }

  def findReachableAllocations(
      inputReachables: Vector[ReferenceV]): Map[ReferenceV, Allocation] = {
    val destinationMap = mutable.Map[ReferenceV, Allocation]()
    inputReachables.foreach(inputReachable => {
      innerFindReachableAllocations(destinationMap, inputReachable)
    })
    innerFindReachableAllocations(destinationMap, void)
    destinationMap.toMap
  }

  private def innerFindReachableAllocations(
      destinationMap: mutable.Map[ReferenceV, Allocation],
      inputReachable: ReferenceV): Unit = {
    // Doublecheck that all the inputReachables are actually in this ..
    vassert(containsLiveObject(inputReachable.allocId))
    vassert(objectsById.get(inputReachable.allocId).kind.tyype.hamut == inputReachable.actualKind.hamut)

    val allocation = objectsById.get(inputReachable.allocId)
    if (destinationMap.contains(inputReachable)) {
      return
    }

    destinationMap.put(inputReachable, allocation)
    allocation.kind match {
      case IntV(_, _) =>
      case VoidV =>
      case BoolV(_) =>
      case FloatV(_) =>
      case StructInstanceV(structDefH, Some(members)) => {
        members.zip(structDefH.members).foreach({
          case (reference, StructMemberH(_, _, referenceH)) => {
            innerFindReachableAllocations(destinationMap, reference)
          }
        })
      }
    }
  }

  def checkForLeaks(): Unit = {
    objectsById.checkForLeaks()
  }

  def getCurrentCall(expectedCallId: CallId) = {
    vassert(callIdStack.top == expectedCallId)
    callsById(expectedCallId)
  }

  def takeArgument(callId: CallId, argumentIndex: Int, expectedType: ReferenceH[KindH]) = {
    val reference = getCurrentCall(callId).takeArgument(argumentIndex)
    checkReference(expectedType, reference)
    decrementReferenceRefCount(
      ArgumentToObjectReferrer(ArgumentId(callId, argumentIndex), expectedType.ownership),
      reference) // decrementing because taking it out of arg
    // Now, the register is the only one that has this reference.
    reference
  }

  // For example, for the integer we pass into the array generator
  def allocateTransient(ownership: OwnershipH, location: LocationH, kind: KindV) = {
    val ref = add(ownership, location, kind)
    vivemDout.print(" o" + ref.allocId.num + "=")
    printKind(kind)
    ref
  }

  def printKind(kind: KindV) = {
    kind match {
      case VoidV => vivemDout.print("void")
      case IntV(value, _) => vivemDout.print(value)
      case BoolV(value) => vivemDout.print(value)
      case StrV(value) => vivemDout.print(value)
      case FloatV(value) => vivemDout.print(value)
      case StructInstanceV(structH, Some(members)) => {
        vivemDout.print(structH.fullName + "{" + members.map("o" + _.allocId.num).mkString(", ") + "}")
      }
      case ArrayInstanceV(typeH, memberTypeH, capacity, elements) => vivemDout.print("array:" + capacity + ":" + memberTypeH + "{" + elements.map("o" + _.allocId.num).mkString(", ") + "}")
    }
  }

  def initializeArrayElement(
      arrayReference: ReferenceV,
      ret: ReferenceV) = {
    dereference(arrayReference) match {
      case a @ ArrayInstanceV(_, _, _, _) => {
        incrementReferenceRefCount(
          ElementToObjectReferrer(
            ElementAddressV(arrayReference.allocId, a.getSize()), a.elementTypeH.ownership),
          ret)
        a.initializeElement(ret)
      }
    }
  }

  def newStruct(
      structDefH: StructDefinitionH,
      structRefH: ReferenceH[StructRefH],
      memberReferences: Vector[ReferenceV]):
  ReferenceV = {
    val instance = StructInstanceV(structDefH, Some(memberReferences.toVector))
    val reference = add(structRefH.ownership, structRefH.location, instance)

    memberReferences.zipWithIndex.foreach({ case (memberReference, index) =>
      incrementReferenceRefCount(
        MemberToObjectReferrer(MemberAddressV(reference.allocId, index), memberReference.ownership),
        memberReference)
    })

    vivemDout.print(" o" + reference.num + "=")
    printKind(instance)
    reference
  }

  def deinitializeArrayElement(arrayReference: ReferenceV) = {
    val arrayInstance @ ArrayInstanceV(_, _, _, _) = dereference(arrayReference)
    val elementReference = arrayInstance.deinitializeElement()
    decrementReferenceRefCount(
      ElementToObjectReferrer(ElementAddressV(arrayReference.allocId, arrayInstance.getSize()), elementReference.ownership),
      elementReference)
    elementReference
  }

//  def initializeArrayElementFromRegister(
//      arrayReference: ReferenceV,
//      elementReference: ReferenceV) = {
//    val arrayInstance @ ArrayInstanceV(_, _, _, _) = dereference(arrayReference)
//    val index = arrayInstance.getSize()
//    incrementReferenceRefCount(
//      ElementToObjectReferrer(ElementAddressV(arrayReference.allocId, index), elementReference.ownership),
//      elementReference)
//    arrayInstance.initializeElement(elementReference)
//  }

  def addUninitializedArray(
      arrayDefinitionTH: RuntimeSizedArrayDefinitionHT,
      arrayRefType: ReferenceH[RuntimeSizedArrayHT],
      capacity: Int):
  (ReferenceV, ArrayInstanceV) = {
    val instance = ArrayInstanceV(arrayRefType, arrayDefinitionTH.elementType, capacity, Vector())
    val reference = add(arrayRefType.ownership, arrayRefType.location, instance)
    (reference, instance)
  }

  def addArray(
    arrayDefinitionTH: StaticSizedArrayDefinitionHT,
    arrayRefType: ReferenceH[StaticSizedArrayHT],
    memberRefs: Vector[ReferenceV]):
  (ReferenceV, ArrayInstanceV) = {
    val instance = ArrayInstanceV(arrayRefType, arrayDefinitionTH.elementType, memberRefs.size, memberRefs.toVector)
    val reference = add(arrayRefType.ownership, arrayRefType.location, instance)
    memberRefs.zipWithIndex.foreach({ case (memberRef, index) =>
      incrementReferenceRefCount(
        ElementToObjectReferrer(ElementAddressV(reference.allocId, index), memberRef.ownership),
        memberRef)
    })
    (reference, instance)
  }


  def checkReference(expectedType: ReferenceH[KindH], actualReference: ReferenceV): Unit = {
    if (actualReference.ownership == WeakH) {
      vassert(containsLiveOrUndeadObject(actualReference.allocId))
    } else {
      vassert(containsLiveObject(actualReference.allocId))
    }
    if (actualReference.seenAsCoord.hamut != expectedType) {
      vfail("Expected " + expectedType + " but was " + actualReference.seenAsCoord.hamut)
    }
    val actualKind = dereference(actualReference, actualReference.ownership == WeakH)
    checkKind(expectedType.kind, actualKind)
  }


  def checkReferenceRegister(tyype: ReferenceH[KindH], register: RegisterV): ReferenceRegisterV = {
    val reg = register.expectReferenceRegister()
    checkReference(tyype, reg.reference)
    reg
  }

  def checkKind(expectedType: KindH, actualKind: KindV): Unit = {
    (actualKind, expectedType) match {
      case (IntV(_, actualBits), IntH(expectedBits)) => {
        if (actualBits != expectedBits) {
          vfail("Expected " + expectedType + " but was " + actualKind)
        }
      }
      case (VoidV, VoidH()) =>
      case (BoolV(_), BoolH()) =>
      case (StrV(_), StrH()) =>
      case (FloatV(_), FloatH()) =>
      case (StructInstanceV(structDefH, _), structRefH @ StructRefH(_)) => {
        if (structDefH.getRef != structRefH) {
          vfail("Expected " + structRefH + " but was " + structDefH)
        }
      }
      case (ArrayInstanceV(typeH, actualElementTypeH, _, _), arrayH @ RuntimeSizedArrayHT(_)) => {
        if (typeH.kind != arrayH) {
          vfail("Expected " + arrayH + " but was " + typeH)
        }
      }
      case (ArrayInstanceV(typeH, actualElementTypeH, _, _), arrayH @ StaticSizedArrayHT(_)) => {
        if (typeH.kind != arrayH) {
          vfail("Expected " + arrayH + " but was " + typeH)
        }
      }
      case (StructInstanceV(structDefH, _), irH @ InterfaceRefH(_)) => {
        val structImplementsInterface =
          structDefH.edges.exists(_.interface == irH)
        if (!structImplementsInterface) {
          vfail("Struct " + structDefH.getRef + " doesnt implement interface " + irH);
        }
      }
      case (a, b) => {
        vfail("Mismatch! " + a + " is not a " + b)
      }
    }
  }

  def checkStructId(expectedStructType: StructRefH, expectedStructPointerType: ReferenceH[KindH], register: RegisterV): AllocationId = {
    val reference = checkReferenceRegister(expectedStructPointerType, register).reference
    dereference(reference) match {
      case siv @ StructInstanceV(structDefH, _) => {
        vassert(structDefH.getRef == expectedStructType)
      }
      case _ => vfail("Expected a struct but was " + register)
    }
    reference.allocId
  }

  def checkStructReference(expectedStructType: StructRefH, expectedStructPointerType: ReferenceH[KindH], register: RegisterV): StructInstanceV = {
    val reference = checkReferenceRegister(expectedStructPointerType, register).reference
    dereference(reference) match {
      case siv @ StructInstanceV(structDefH, _) => {
        vassert(structDefH.getRef == expectedStructType)
        siv
      }
      case _ => vfail("Expected a struct but was " + register)
    }
  }

  def checkStructReference(expectedStructType: StructRefH, reference: ReferenceV): StructInstanceV = {
    dereference(reference) match {
      case siv @ StructInstanceV(structDefH, _) => {
        vassert(structDefH.getRef == expectedStructType)
        siv
      }
      case _ => vfail("Expected a struct but was " + reference)
    }
  }

  def pushNewStackFrame(functionH: PrototypeH, args: Vector[ReferenceV]) = {
    vassert(callsById.size == callIdStack.size)
    val callId =
      CallId(
        if (callIdStack.nonEmpty) callIdStack.top.callDepth + 1 else 0,
        functionH)
    val call = new Call(callId, args)
    callsById.put(callId, call)
    callIdStack.push(callId)
    vassert(callsById.size == callIdStack.size)
    callId
  }

  def popStackFrame(expectedCallId: CallId): Unit = {
    vassert(callsById.size == callIdStack.size)
    vassert(callIdStack.top == expectedCallId)
    val call = callsById(expectedCallId)
    call.prepareToDie()
    callIdStack.pop()
    callsById.remove(expectedCallId)
    vassert(callsById.size == callIdStack.size)
  }

  def toVon(ref: ReferenceV): IVonData = {
    dereference(ref) match {
      case VoidV => VonObject("void", None, Vector())
      case IntV(value, bits) => VonInt(value)
      case FloatV(value) => VonFloat(value)
      case BoolV(value) => VonBool(value)
      case StrV(value) => VonStr(value)
      case ArrayInstanceV(_, _, _, elements) => {
        VonArray(None, elements.map(toVon))
      }
      case StructInstanceV(structH, Some(members)) => {
        vassert(members.size == structH.members.size)
        von.VonObject(
          structH.fullName.toString,
          None,
          structH.members.zip(members).zipWithIndex.map({ case ((memberH, memberV), index) =>
            VonMember(vimpl(memberH.name.toString), toVon(memberV))
          }).toVector)
      }
    }
  }

  def getVarAddress(callId: CallId, local: Local) = {
    VariableAddressV(callId, local)
  }
}
