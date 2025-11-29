package llvm

import exception.IRException

abstract class IRType {
    abstract fun isPointer(): Boolean // 是否为指针

    abstract fun isAggregate(): Boolean // 是否为聚合类型（struct / array）
}

object VoidType : IRType() {
    override fun isPointer(): Boolean {
        return false
    }

    override fun isAggregate(): Boolean {
        return false
    }

    override fun toString(): String {
        return "void"
    }
}

sealed class IntegerType : IRType() {
    override fun isPointer(): Boolean {
        return false
    }

    override fun isAggregate(): Boolean {
        return false
    }
}

object I32Type : IntegerType() {
    override fun toString(): String {
        return "i32"
    }
}

object I8Type : IntegerType() {
    override fun toString(): String {
        return "i8"
    }
}

object I1Type : IntegerType() {
    override fun toString(): String {
        return "i1"
    }
}

class StructType(name: String) : IRType() {
    val irName = "%struct.$name"
    val elements: MutableList<IRType> = mutableListOf()

    fun myGetElementType(i: Int): IRType {
        return elements.getOrNull(index = i) ?: throw IRException("Index out of bounds")
    }

    fun myGetElementsNum(): Int {
        return elements.size
    }

    override fun isPointer(): Boolean {
        return false
    }

    override fun isAggregate(): Boolean {
        return true
    }

    override fun equals(other: Any?): Boolean {
        if (other !is StructType) return false
        if (irName != other.irName || elements.size != other.elements.size) {
            return false
        }
        for (i in 0..<elements.size) {
            if (elements[i] == other.elements[i]) {
                return false
            }
        }
        return true
    }

    override fun toString(): String {
        return irName
    }

    override fun hashCode(): Int {
        var result = irName.hashCode()
        result = 31 * result + elements.hashCode()
        return result
    }

    fun printDef(): String {
        var result = "$irName = type { "
        for (i in 0..<elements.size - 1) {
            result += "${elements[i]}, "
        }
        result += "${elements[elements.size - 1]} }"
        return result
    }
}

class ArrayType(
    val elementType: IRType,
    val numElements: Int
) : IRType() {
    override fun isPointer(): Boolean {
        return false
    }

    override fun isAggregate(): Boolean {
        return true
    }

    override fun equals(other: Any?): Boolean {
        if (other !is ArrayType) return false
        return other.elementType == elementType && other.numElements == numElements
    }

    override fun toString(): String {
        return "[$numElements x ${elementType}]"
    }

    override fun hashCode(): Int {
        var result = numElements
        result = 31 * result + elementType.hashCode()
        return result
    }
}

class FunctionType(
    var returnType: IRType,
    val paramTypes: MutableList<IRType>
) : IRType() {
    fun myGetParamType(i: Int): IRType {
        return paramTypes.getOrNull(index = i) ?: throw IRException("Index out of bounds")
    }

    fun myGetNumParams(): Int {
        return paramTypes.size
    }

    fun addParamType(type: IRType) {
        paramTypes.add(type)
    }

    override fun isPointer(): Boolean {
        return false
    }

    override fun isAggregate(): Boolean {
        return false
    }

    override fun equals(other: Any?): Boolean {
        if (other !is FunctionType) return false
        if (returnType != other.returnType || paramTypes.size != other.paramTypes.size) {
            return false
        }
        for (i in 0..<paramTypes.size) {
            if (paramTypes[i] == other.paramTypes[i]) {
                return false
            }
        }
        return true
    }

    override fun toString(): String {
        return "$returnType (${paramTypes.joinToString(", ") { it.toString() }})"
    }

    override fun hashCode(): Int {
        var result = returnType.hashCode()
        result = 31 * result + paramTypes.hashCode()
        return result
    }
}

object PointerType : IRType() {
    override fun isPointer(): Boolean {
        return true
    }

    override fun isAggregate(): Boolean {
        return false
    }

    override fun toString(): String {
        return "ptr"
    }
}
