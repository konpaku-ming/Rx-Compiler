package llvm

import exception.IRException

class Module(
    private val moduleName: String, // 模块名称
    private val context: LLVMContext  // 所属上下文
) {
    // 模块内函数
    private val functions = mutableMapOf<String, Function>()

    // 模块内全局变量
    private val globalVars = mutableMapOf<String, GlobalVariable>()

    // 获取函数（不存在时返回null）
    fun myGetFunction(name: String): Function? {
        return functions[name]
    }

    // 向模块中添加函数
    fun addFunction(name: String, function: Function) {
        functions[name] = function
    }

    // 获取函数（不存在时创建）
    fun myGetOrCreateFunction(name: String, funcType: FunctionType): Function {
        return functions.getOrPut(name) {
            Function(name, funcType, this)
        }
    }

    // 用函数类型创建函数并添加到模块中，返回该函数
    fun createFunction(name: String, funcType: FunctionType): Function {
        if (name in functions) {
            throw IRException("Function '$name' already exists in module")
        }
        return Function(name, funcType, this).also { functions[name] = it }
    }

    // 获取全局变量（不存在时返回null）
    fun myGetGlobalVariable(name: String): GlobalVariable? {
        return globalVars[name]
    }

    // 向模块中添加全局变量
    fun addGlobalVariable(name: String, globalVar: GlobalVariable) {
        globalVars[name] = globalVar
    }

    // 获取全局变量（不存在时创建）
    fun myGetOrCreateGlobalVariable(
        name: String,
        varType: IRType,
        isConstant: Boolean,
        initValue: Constant
    ): GlobalVariable {
        return globalVars.getOrPut(name) {
            GlobalVariable(name, varType, initValue, isConstant)
        }
    }

    // 用LLVM类型创建全局变量并添加到模块中，返回该全局变量
    fun createGlobalVariable(
        name: String,
        varType: IRType,
        isConstant: Boolean,
        initValue: Constant
    ): GlobalVariable {
        if (name in globalVars) {
            throw IRException("Global variable '$name' already exists in module")
        }
        return GlobalVariable(name, varType, initValue, isConstant).also { globalVars[name] = it }
    }

    fun print(): String {
        // 打印模块内所有 Struct、函数与全局变量的信息
        val result = StringBuilder()
        val structTypes = context.myGetAllStructTypes() // 获取所有结构体类型列表
        structTypes.forEach { structType ->
            result.appendLine(structType.printDef())
        }
        if (structTypes.isNotEmpty() && (globalVars.isNotEmpty() || functions.isNotEmpty())) {
            result.appendLine()
        }
        // 打印 GlobalVariable
        globalVars.values.forEach { globalVar ->
            result.appendLine("$globalVar")
        }
        if (globalVars.isNotEmpty() && functions.isNotEmpty()) {
            result.appendLine()
        }
        // 打印 Function
        functions.values.forEach { function ->
            result.appendLine("$function")
        }
        return result.toString()
    }
}

abstract class Value {
    val users: MutableList<User> = mutableListOf()

    abstract fun myGetType(): IRType // 获取值的类型

    abstract fun myGetName(): String // 获取值的名称

    fun addUser(user: User) { // 添加使用该值的用户
        users.add(user)
    }

    fun myGetAllUsers(): List<User> = users // 获取使用该值的用户列表
}

abstract class User : Value() {
    val operands: MutableList<Value> = mutableListOf() // 操作数列表

    fun addOperand(operand: Value) { // 添加操作数
        operands.add(operand)
        operand.addUser(user = this)
    }

    fun myGetOperands(): List<Value> = operands // 获取操作数列表
}

class GlobalVariable(
    val name: String,
    val type: IRType,
    val initialValue: Constant, // 变量初始值，可以为空表示未初始化
    val isConstant: Boolean = false // 是否为常量
) : Value() {

    override fun myGetType(): IRType {
        return type
    }

    override fun myGetName(): String {
        return "@$name"
    }

    override fun toString(): String {
        val prefix = if (isConstant) "constant " else "global "
        val value = initialValue.toString()
        return "@$name = $prefix$value"
    }
}

class Function(
    val name: String,
    val funcType: FunctionType,
    val parent: Module // 所属模块
) : Value() {
    private val basicBlocks: MutableList<BasicBlock> = mutableListOf() // 函数体内的基本块列表
    private val bbNameMap: MutableMap<String, Int> = mutableMapOf() // 基本块名称到索引的映射，用于防止重名
    private val arguments: MutableList<Argument> = mutableListOf() // 函数参数列表
    private var isDefined: Boolean = false // 函数是否已定义（有函数体）

    fun addBasicBlock(bb: BasicBlock) {
        basicBlocks.add(bb)
    }

    fun createBasicBlock(name: String): BasicBlock {
        isDefined = true
        val actualName = if (bbNameMap.containsKey(name)) {
            bbNameMap[name] = bbNameMap.getValue(name) + 1
            "$name.${bbNameMap[name]}"
        } else {
            bbNameMap[name] = 0
            name
        }
        val bb = BasicBlock(actualName, this)
        addBasicBlock(bb)
        return bb
    }

    fun myGetBasicBlocks(): List<BasicBlock> {
        return basicBlocks.toList()
    }

    fun myGetBBbyIndex(i: Int): BasicBlock? {
        return basicBlocks.getOrNull(i)
    }

    fun addArgument(arg: Argument) {
        arguments.add(arg)
    }

    fun setArguments(args: List<Argument>) {
        arguments.clear()
        arguments.addAll(args)
    }

    fun myGetArguments(): List<Argument> {
        return arguments.toList()
    }

    fun myGetArgByIndex(i: Int): Argument? {
        return arguments.getOrNull(i)
    }

    override fun myGetType(): FunctionType {
        return funcType
    }

    override fun myGetName(): String {
        return name
    }

    fun myGetParent(): Module {
        return parent
    }

    override fun toString(): String {
        val result = StringBuilder()
        result.append(
            if (isDefined) {
                "define "
            } else {
                "declare "
            }
        )
        result.append("${funcType.returnType} @$name(")

        val paramTypes = funcType.paramTypes
        paramTypes.forEachIndexed { i, type ->
            result.append(
                if (i < arguments.size) {
                    "$type ${arguments[i].myGetName()}"
                } else {
                    "$type"
                }
            )
            if (i < paramTypes.size - 1) {
                result.append(", ")
            }
        }

        result.append(") ")
        if (isDefined) {
            result.append("{\n")
            basicBlocks.forEach { bb ->
                result.append("${bb}\n")
            }
            result.append("}")
        }
        result.append("\n")

        return result.toString()
    }
}

class BasicBlock(
    val name: String, // 基本块名称
    val parent: Function // 所属函数
) : Value() {
    val instructions: MutableList<Instruction> = mutableListOf() // 块内指令

    fun addInstruction(inst: Instruction) {
        if (isTerminated()) {
            val terminator = instructions.removeLast()
            instructions.addAll(listOf(inst, terminator))
        } else {
            instructions.add(inst)
        }
    }

    fun myGetInstructions(): List<Instruction> {
        return instructions.toList()
    }

    fun isTerminated(): Boolean {
        if (instructions.isEmpty()) {
            return false
        }
        return instructions.last() is TerminatorInst
    }

    fun myGetTerminator(): Instruction? {
        if (instructions.isEmpty()) {
            return null
        }
        return instructions.last() as? TerminatorInst
    }

    override fun myGetType(): IRType {
        throw IRException(
            "BasicBlock cannot be used as a value"
        )
    }

    override fun myGetName(): String {
        return name
    }

    fun myGetParent(): Function {
        return parent
    }

    override fun toString(): String {
        val result = StringBuilder()
        result.append("$name:\n")
        instructions.forEach { inst ->
            result.append("  ${inst}\n")
        }
        return result.toString()
    }
}

abstract class Instruction : User()

class BinaryOperator(
    private val name: String, // 指令对应 Value 的名称
    private val op: String,
    private val type: IRType, // 结果类型
    private val lhs: Value, // 左操作数
    private val rhs: Value  // 右操作数
) : Instruction() {

    init {
        // 检查操作数类型是否为整数
        val lhsIntType = lhs.myGetType() as? IntegerType
        val rhsIntType = rhs.myGetType() as? IntegerType
        if (lhsIntType == null || rhsIntType == null) {
            throw IRException("BinaryOperator operands must be of integer type")
        }
        // 检查操作数类型是否与结果类型匹配
        if (lhs.myGetType() != type || rhs.myGetType() != type) {
            throw IRException("BinaryOperator operand types must match result type")
        }
        addOperand(lhs)
        addOperand(rhs)
    }

    override fun myGetType(): IRType {
        return type
    }

    override fun myGetName(): String {
        return "%$name"
    }

    override fun toString(): String {
        return "%$name = $op $type ${lhs.myGetName()}, ${rhs.myGetName()}"
    }
}

class LoadInst(
    val name: String, // 指令对应 Value 的名称
    val type: IRType, // 结果类型
    val ptr: Value // 指向 load 地址的指针
) : Instruction() {
    init {
        // 检查ptr类型是否为指针
        if (!ptr.myGetType().isPointer()) {
            throw IRException("LoadInst pointer operand '${ptr.myGetName()}' must be of pointer type")
        }
        addOperand(ptr)
    }

    override fun myGetType(): IRType {
        return type
    }

    override fun myGetName(): String {
        return "%$name"
    }

    override fun toString(): String = "%$name = load ${type}, ptr ${ptr.myGetName()}"
}

class StoreInst(
    val type: IRType, // 存储值的类型
    val ptr: Value, // 指向 store 地址的指针
    val value: Value // 要存储的值
) : Instruction() {

    init {
        // 检查指针类型是否为指针
        if (!ptr.myGetType().isPointer()) {
            throw IRException("StoreInst pointer operand must be of pointer type")
        }
        // 检查存储值类型是否与指定类型匹配
        if (value.myGetType() != (type)) {
            throw IRException("StoreInst value type must match specified type")
        }
        addOperand(ptr)
        addOperand(value)
    }

    override fun myGetType(): IRType {
        return type
    }

    override fun myGetName(): String {
        throw IRException("StoreInst cannot be used as a value")
    }

    override fun toString(): String {
        return "store $type ${value.myGetName()}, ptr ${ptr.myGetName()}"
    }
}

class AllocaInst(
    val name: String, // 对应 Value 的名称
    val type: IRType // 分配空间的类型
) : Instruction() {

    override fun myGetType(): IRType {
        // 产生指针
        return PointerType
    }

    override fun myGetName(): String {
        return "%$name"
    }

    override fun toString(): String = "%$name = alloca $type"
}

class ICmpInst(
    val name: String, // 指令对应 Value 的名称
    val predicate: String, // 比较操作符
    val type: IRType, // 结果类型，应为I1Type
    val lhs: Value, // 左操作数
    private val rhs: Value // 右操作数
) : Instruction() {
    init {
        // 检查操作数类型是否为整数
        val lhsIntType = lhs.myGetType() as? IntegerType
        val rhsIntType = rhs.myGetType() as? IntegerType
        if (lhsIntType == null || rhsIntType == null) {
            throw IRException("ICmpInst operands must be of integer type")
        }
        // 检查操作数类型是否相同
        if (lhs.myGetType() != rhs.myGetType()) {
            throw IRException("ICmpInst operand types must match")
        }
        // 检查结果类型是
        if (type !is I1Type) {
            throw IRException("ICmpInst result type must be Int1Type")
        }
        addOperand(lhs)
        addOperand(rhs)
    }

    override fun myGetType(): IRType {
        return type
    }

    override fun myGetName(): String {
        return "%$name"
    }

    override fun toString(): String {
        return "%$name = icmp $predicate ${lhs.myGetType()} ${lhs.myGetName()}, ${rhs.myGetName()}"
    }
}

abstract class TerminatorInst : Instruction() // 终止指令，用来结束一个BB

class BrInst(
    val target: BasicBlock // 目标基本块
) : TerminatorInst() {

    override fun myGetType(): IRType {
        throw IRException("BrInst cannot be used as a value")
    }

    override fun myGetName(): String {
        throw IRException("BrInst cannot be used as a value")
    }

    override fun toString(): String = "br label %${target.myGetName()}"
}

// 条件分支跳转指令
class ConBrInst(
    val condition: Value, // 条件值
    val thenBlock: BasicBlock, // 真时跳转的基本块
    val elseBlock: BasicBlock  // 假时跳转的基本块
) : TerminatorInst() {
    init {
        // 检查条件值是否为i1类型
        if (condition.myGetType() !is I1Type) {
            throw IRException("ConBrInst condition must be of Int1Type")
        }
        addOperand(condition)
    }

    override fun myGetType(): IRType {
        throw IRException("ConBrInst cannot be used as a value")
    }

    override fun myGetName(): String {
        throw IRException("ConBrInst cannot be used as a value")
    }

    override fun toString(): String {
        return "br i1 ${condition.myGetName()}, label %${thenBlock.myGetName()}, label %${elseBlock.myGetName()}"
    }
}

class ReturnInst(
    val returnValue: Value? = null // 返回值，可以为空表示无返回值
) : TerminatorInst() {
    init {
        returnValue?.let { addOperand(it) }
    }

    override fun myGetType(): IRType {
        throw IRException("ReturnInst cannot be used as a value")
    }

    override fun myGetName(): String {
        throw IRException("ReturnInst cannot be used as a value")
    }

    override fun toString(): String {
        return if (returnValue != null) {
            "ret ${returnValue.myGetType()} ${returnValue.myGetName()}"
        } else {
            "ret void"
        }
    }
}

class PHINode(
    val name: String, // 指令对应 Value 的名称
    val type: IRType  // 结果类型
) : Instruction() {
    // 输入值及其对应的前驱基本块列表
    val incomings = mutableListOf<Pair<Value, BasicBlock>>()

    fun addIncoming(value: Value, block: BasicBlock) {
        // 检查输入值类型是否与指令结果类型一致
        if (value.myGetType() != type) {
            throw IRException("PHINode incoming value type must match PHI node type")
        }
        incomings.add(value to block)
        addOperand(value)
    }

    override fun myGetType(): IRType {
        return type
    }

    override fun myGetName(): String {
        return "%$name"
    }

    override fun toString(): String {
        val result = StringBuilder("$name = phi $type")

        incomings.forEachIndexed { index, (value, block) ->
            result.append(" [ ${value.myGetName()}, %${block.myGetName()} ]")
            if (index < incomings.size - 1) {
                result.append(", ")
            }
        }

        return result.toString()
    }
}

class CallInst(
    val name: String, // 指令对应 Value（结果寄存器）的名称
    val function: Function, // 被调用的函数
    args: List<Value>           // 函数调用参数列表
) : Instruction() {
    val args: List<Value> = args.toList()

    init {
        val funcType = function.myGetType()
        // 检查参数数量是否匹配
        val paramTypes = funcType.paramTypes
        if (args.size != paramTypes.size) {
            throw IRException("CallInst argument count does not match function parameter count")
        }
        // 检查每个参数类型是否匹配
        args.forEachIndexed { i, arg ->
            if (arg.myGetType() != paramTypes[i]) {
                throw IRException("CallInst argument type does not match function parameter type")
            }
        }
        // 添加操作数
        args.forEach { addOperand(it) }
    }

    override fun myGetType(): IRType {
        val funcType = function.myGetType()
        return funcType.returnType
    }

    override fun myGetName(): String {
        return "%$name"
    }

    override fun toString(): String {
        val funcType = function.myGetType()
        val result = StringBuilder()

        val typeName = if (name.isNotEmpty() && funcType.returnType !is VoidType) {
            result.append("%$name = ")
            "${funcType.returnType}"
        } else {
            "void"
        }
        result.append("call $typeName @${function.myGetName()}(")

        args.forEachIndexed { i, arg ->
            result.append("${arg.myGetType()} ${arg.myGetName()}")
            if (i < args.size - 1) {
                result.append(", ")
            }
        }

        result.append(")")
        return result.toString()
    }
}

class PtrToIntInst(
    private val name: String, // 指令对应 Value 的名称
    private val type: IRType, // 结果类型
    private val ptr: Value // 指针操作数
) : Instruction() {
    init {
        // 检查操作数类型是否为指针
        if (!ptr.myGetType().isPointer()) {
            throw IRException("PtrToIntInst pointer operand must be of pointer type")
        }
        // 检查结果类型是否为整数类型
        if (type !is IntegerType) {
            throw IRException("PtrToIntInst result type must be of integer type")
        }
        addOperand(ptr)
    }

    override fun myGetType(): IRType {
        return type
    }

    override fun myGetName(): String {
        return "%$name"
    }

    override fun toString(): String {
        return "%$name = ptrtoint ptr ${ptr.myGetName()} to $type"
    }
}

class ZExtInst(
    private val name: String, // 指令对应 Value 的名称
    private val type: IRType,   // 结果类型（较大整数类型）
    private val value: Value  // 被扩充的整数操作数
) : Instruction() {
    init {
        // 检查操作数和结果类型是否均为整数类型
        if (value.myGetType() !is IntegerType || type !is IntegerType) {
            throw IRException("ZExtInst operand and result types must be of integer type")
        }
        // 结果类型位数应大于操作数类型位数
        val valueBitWidth = when (value.myGetType()) {
            is I32Type -> 32
            is I8Type -> 8
            is I1Type -> 1
            else -> 0
        }
        val resultBitWidth = when (type) {
            is I32Type -> 32
            is I8Type -> 8
            is I1Type -> 1
        }
        if (resultBitWidth <= valueBitWidth) {
            throw IRException("ZExtInst result must be longer than operand")
        }
        addOperand(value)
    }

    override fun myGetType(): IRType {
        return type
    }

    override fun myGetName(): String {
        return "%$name"
    }

    override fun toString(): String {
        return "%$name = zext ${value.myGetType()} ${value.myGetName()} to $type"
    }
}

// 地址计算指令
class GetElementPtrInst(
    val name: String, // 指令对应 Value 的名称
    val type: IRType, // 结果类型
    val baseType: IRType, // 第一个索引指向的类型
    val ptr: Value, // 指针操作数
    indices: List<Value> // 索引操作数列表
) : Instruction() {
    val indices: List<Value> = indices.toList()

    init {
        if (!ptr.myGetType().isPointer()) {
            throw IRException("GEPInst pointer operand must be of pointer type")
        }
        // 检查所有索引是否为整数类型
        indices.forEach { index ->
            if (index.myGetType() !is IntegerType) {
                throw IRException("GEPInst indices must be of integer type")
            }
        }

        var currentType = baseType
        indices.forEachIndexed { i, index ->
            if (i != indices.size - 1 && !currentType.isAggregate()) {
                throw IRException("GEPInst index applied to non-aggregate type")
            }
            (index as? ConstantInt)?.let { constIndex ->
                if (i == 0) return@let // 第一个索引用于指针算数，跳过检查

                val idx = constIndex.myGetValue().toInt()
                when (currentType) {
                    is ArrayType -> {
                        if (idx < 0 || idx >= (currentType as ArrayType).numElements) {
                            throw IRException("GEPInst array index out of bounds")
                        }
                        currentType = (currentType as ArrayType).elementType
                    }

                    is StructType -> {
                        if (idx < 0 || idx >= (currentType as StructType).myGetElementsNum()) {
                            throw IRException("GEPInst struct index out of bounds")
                        }
                        currentType = (currentType as StructType).myGetElementType(idx)
                    }
                }
            } ?: run {
                return@forEachIndexed // 非常量索引，停止检测
            }
        }

        addOperand(ptr)
        indices.forEach { addOperand(it) }
    }

    override fun myGetType(): IRType {
        return type
    }

    override fun myGetName(): String {
        return "%$name"
    }

    override fun toString(): String {
        val result = StringBuilder("%$name = getelementptr ${baseType}, ptr ${ptr.myGetName()}")
        indices.forEach { index ->
            result.append(", ${index.myGetType()} ${index.myGetName()}")
        }
        return result.toString()
    }
}

// 常量
abstract class Constant : Value()

class ConstantInt(
    val type: IntegerType, // 整数类型
    val value: UInt // 值
) : Constant() {
    override fun myGetType(): IRType {
        return type
    }

    override fun myGetName(): String {
        return value.toString()
    }

    fun myGetValue(): UInt {
        return value
    }

    override fun toString(): String {
        return "$type ${myGetName()}"
    }
}

class ConstantStruct(
    val type: StructType, // 结构体类型
    elements: List<Constant> // 常量字段
) : Constant() {
    val elements: List<Constant> = elements.toList()

    init {
        // 检查字段数量和类型是否与结构体类型内部信息一致
        val structElements = type.elements
        if (elements.size != structElements.size) {
            throw IRException("ConstantStruct element count does not match struct type")
        }
        elements.forEachIndexed { i, element ->
            if (element.myGetType() != structElements[i]) {
                throw IRException("ConstantStruct element type does not match struct type")
            }
        }
    }

    override fun myGetType(): StructType {
        return type
    }

    override fun myGetName(): String {
        val result = StringBuilder("{ ")
        elements.forEachIndexed { i, element ->
            result.append("$element")
            if (i < elements.size - 1) {
                result.append(", ")
            }
        }
        result.append(" }")
        return result.toString()
    }

    override fun toString(): String {
        return "$type ${myGetName()}"
    }
}

// 数组常量
class ConstantArray(
    val type: ArrayType, // 数组类型
    elements: List<Constant> // 数组元素常量列表
) : Constant() {
    private val elements: List<Constant> = elements.toList()

    init {
        // 检查元素数量和类型是否与数组类型内部信息一致
        if (elements.size != type.numElements) {
            throw IRException("ConstantArray element count does not match array type")
        }
        val elementType = type.elementType
        elements.forEach { elem ->
            if (elem.myGetType() != elementType) {
                throw IRException("ConstantArray element type does not match array type")
            }
        }
    }

    override fun myGetType(): ArrayType {
        return type
    }

    override fun myGetName(): String {
        val result = StringBuilder("[ ")
        elements.forEachIndexed { i, element ->
            result.append("$element")
            if (i < elements.size - 1) {
                result.append(", ")
            }
        }
        result.append(" ]")
        return result.toString()
    }

    override fun toString(): String {
        return "$type ${myGetName()}"
    }
}

class ConstantPointerNull(
    val type: PointerType
) : Constant() {
    override fun myGetType(): IRType {
        return type
    }

    override fun myGetName(): String {
        return "null"
    }

    override fun toString(): String {
        return "$type null"
    }
}

class Argument(
    val name: String, // 参数名称
    val type: IRType, // 参数类型
    val parent: Function // 所属函数
) : Value() {
    override fun myGetType(): IRType {
        return type
    }

    override fun myGetName(): String {
        return "%$name"
    }

    override fun toString(): String = "$type %$name"
}
