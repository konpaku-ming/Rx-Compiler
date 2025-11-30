package ir

import ast.ArrayResolvedType
import ast.NamedResolvedType
import ast.NeverResolvedType
import ast.PrimitiveResolvedType
import ast.ReferenceResolvedType
import ast.ResolvedType
import ast.StructSymbol
import ast.UnitResolvedType
import ast.UnknownResolvedType
import llvm.IRType
import llvm.LLVMContext
import exception.IRException

fun getIRType(context: LLVMContext, type: ResolvedType): IRType {
    return when (type) {
        is PrimitiveResolvedType -> {
            when (type) {
                PrimitiveResolvedType("i32"),
                PrimitiveResolvedType("u32"),
                PrimitiveResolvedType("isize"),
                PrimitiveResolvedType("usize") -> context.myGetI32Type()

                PrimitiveResolvedType("bool") -> context.myGetI1Type()

                PrimitiveResolvedType("char") -> context.myGetI8Type()

                PrimitiveResolvedType("str") -> context.myGetPointerType()

                else -> throw IRException("Unknown primitive type: $type")
            }
        }

        is UnitResolvedType,
        is NeverResolvedType -> context.myGetI8Type() // 无实际意义，占一个Byte

        is ReferenceResolvedType -> {
            if (type.inner is UnknownResolvedType) {
                throw IRException("Array element type is not resolved: $type")
            }
            context.myGetPointerType()
        }

        is ArrayResolvedType -> {
            if (type.elementType is UnknownResolvedType) {
                throw IRException("Array element type is not resolved: $type")
            }
            if (type.length == -1) {
                throw IRException("Array length is not resolved: $type")
            }
            context.myGetArrayType(getIRType(context, type.elementType), type.length)
        }

        is NamedResolvedType -> {
            val structSymbol = type.symbol as? StructSymbol
                ?: throw IRException("Named type is not a struct: $type")
            context.myGetStructType(structSymbol.name) // 此时应该已经定义好了
        }

        is UnknownResolvedType -> {
            throw IRException("Type is not resolved: $type")
        }
    }
}