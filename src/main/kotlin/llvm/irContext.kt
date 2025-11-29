package llvm

class LLVMContext {
    private val voidType: VoidType by lazy { VoidType }
    private val integerTypes = mutableMapOf<Int, IntegerType>()
    private val structTypes = mutableMapOf<String, StructType>()
    private val arrayTypes = mutableMapOf<Pair<IRType, Int>, ArrayType>()
    private val pointerType: PointerType by lazy { PointerType }
    private val functionTypes = mutableMapOf<Pair<IRType, List<IRType>>, FunctionType>()
    private val intConstants = mutableMapOf<Pair<IntegerType, Long>, ConstantInt>()
    private val structConstants = mutableMapOf<Pair<StructType, List<Constant>>, ConstantStruct>()
    private val arrayConstants = mutableMapOf<Pair<ArrayType, List<Constant>>, ConstantArray>()
    private val nullConstant: ConstantPointerNull by lazy { ConstantPointerNull(myGetPointerType()) }

    // 获取或创建void类型对象
    fun myGetVoidType(): VoidType {
        return voidType
    }

    // 获取或创建32位整数类型对象
    fun myGetI32Type(): I32Type {
        return integerTypes.getOrPut(32) { I32Type } as I32Type
    }

    // 获取或创建8位整数类型对象
    fun myGetI8Type(): I8Type {
        return integerTypes.getOrPut(8) { I8Type } as I8Type
    }

    // 获取或创建1位整数类型对象
    fun myGetI1Type(): I1Type {
        return integerTypes.getOrPut(1) { I1Type } as I1Type
    }

    // 获取或创建结构体类型对象
    fun myGetStructType(name: String): StructType {
        return structTypes.getOrPut(name) { StructType(name) }
    }

    // 获取所有已创建的结构体类型对象
    fun myGetAllStructTypes(): List<StructType> {
        return structTypes.values.toList()
    }

    // 获取或创建数组类型对象
    fun myGetArrayType(elementType: IRType, length: Int): ArrayType {
        val key = elementType to length
        return arrayTypes.getOrPut(key) { ArrayType(elementType, length) }
    }

    // 获取或创建函数类型对象
    fun myGetFunctionType(returnType: IRType, paramTypes: List<IRType>): FunctionType {
        val key = returnType to paramTypes
        return functionTypes.getOrPut(key) { FunctionType(returnType, paramTypes.toMutableList()) }
    }

    // 获取指针类型对象
    fun myGetPointerType(): PointerType {
        return pointerType
    }

    // 获取或创建整数常量对象
    fun myGetIntConstant(type: IntegerType, value: Int): ConstantInt {
        val key = type to value.toLong()
        return intConstants.getOrPut(key) { ConstantInt(type, value) }
    }

    // 获取或创建结构体常量对象
    fun myGetStructConstant(type: StructType, values: List<Constant>): ConstantStruct {
        val key = type to values
        return structConstants.getOrPut(key) { ConstantStruct(type, values) }
    }

    // 获取或创建数组常量对象
    fun mmyGetArrayConstant(type: ArrayType, values: List<Constant>): ConstantArray {
        val key = type to values
        return arrayConstants.getOrPut(key) { ConstantArray(type, values) }
    }

    // 获取或创建空指针常量对象
    fun myGetNullPtrConstant(): ConstantPointerNull {
        return nullConstant
    }
}
