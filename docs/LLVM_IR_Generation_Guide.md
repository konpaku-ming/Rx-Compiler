# LLVM IR 生成指南

本文档介绍如何使用 `src/main/kotlin/llvm` 目录下的小型 LLVM-like Kotlin 库，将 AST 节点降低（lower）为 LLVM IR。

## 目录

1. [概述](#概述)
2. [核心组件](#核心组件)
3. [类型系统](#类型系统)
4. [基本代码生成](#基本代码生成)
5. [语句降低](#语句降低)
6. [表达式降低](#表达式降低)
7. [控制流](#控制流)
8. [函数与调用](#函数与调用)
9. [结构体与数组](#结构体与数组)
10. [完整示例](#完整示例)
11. [调试与验证](#调试与验证)

---

## 概述

本项目实现了一个类似 LLVM 的 IR 生成库，主要包含以下文件：

| 文件 | 描述 |
|------|------|
| `irContext.kt` | `LLVMContext` 类，管理类型和常量的创建与缓存 |
| `irType.kt` | IR 类型定义（`VoidType`, `IntegerType`, `StructType`, `ArrayType`, `FunctionType`, `PointerType`） |
| `irComponents.kt` | IR 组件（`Module`, `Function`, `BasicBlock`, 各种 `Instruction`, `Value`, `Constant`） |
| `irBuilder.kt` | `IRBuilder` 类，提供创建指令的便捷方法 |

### 架构图

```
┌─────────────────────────────────────────────────────────────┐
│                         Module                               │
│  ┌─────────────────┐  ┌─────────────────────────────────┐   │
│  │ GlobalVariables │  │           Functions              │   │
│  └─────────────────┘  │  ┌───────────────────────────┐  │   │
│                       │  │        Function           │  │   │
│                       │  │  ┌─────────────────────┐  │  │   │
│                       │  │  │    BasicBlock       │  │  │   │
│                       │  │  │  ┌───────────────┐  │  │  │   │
│                       │  │  │  │ Instructions  │  │  │  │   │
│                       │  │  │  └───────────────┘  │  │  │   │
│                       │  │  └─────────────────────┘  │  │   │
│                       │  └───────────────────────────┘  │   │
│                       └─────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

---

## 核心组件

### LLVMContext

`LLVMContext` 是类型和常量的工厂类，确保类型的唯一性。

```kotlin
import llvm.LLVMContext

val context = LLVMContext()

// 获取基本类型
val i32Type = context.myGetI32Type()      // i32
val i8Type = context.myGetI8Type()        // i8
val i1Type = context.myGetI1Type()        // i1 (bool)
val voidType = context.myGetVoidType()    // void
val ptrType = context.myGetPointerType()  // ptr (opaque pointer)

// 获取复合类型
val arrayType = context.myGetArrayType(i32Type, 10)  // [10 x i32]
val structType = context.myGetStructType("Point")     // %struct.Point
val funcType = context.myGetFunctionType(i32Type, listOf(i32Type, i32Type))  // i32 (i32, i32)

// 创建常量
val intConst = context.myGetIntConstant(i32Type, 42)  // i32 42
val nullPtr = context.myGetNullPtrConstant()          // ptr null
```

### Module

`Module` 是 IR 的顶层容器，包含函数和全局变量。

```kotlin
import llvm.Module

val module = Module("myModule", context)

// 创建函数
val funcType = context.myGetFunctionType(context.myGetI32Type(), emptyList())
val mainFunc = module.createFunction("main", funcType)

// 创建全局变量
val initValue = context.myGetIntConstant(context.myGetI32Type(), 0)
val globalVar = module.createGlobalVariable("counter", context.myGetI32Type(), false, initValue)

// 获取已有函数
val existingFunc = module.myGetFunction("main")
val orCreateFunc = module.myGetOrCreateFunction("helper", funcType)

// 打印模块 IR
println(module.print())
```

### Function

`Function` 代表一个函数定义或声明。

```kotlin
// 创建带参数的函数
val paramTypes = listOf(context.myGetI32Type(), context.myGetI32Type())
val funcType = context.myGetFunctionType(context.myGetI32Type(), paramTypes)
val addFunc = module.createFunction("add", funcType)

// 设置参数
val arg0 = llvm.Argument("a", context.myGetI32Type(), addFunc)
val arg1 = llvm.Argument("b", context.myGetI32Type(), addFunc)
addFunc.setArguments(listOf(arg0, arg1))

// 创建基本块
val entryBB = addFunc.createBasicBlock("entry")

// 获取函数信息
val args = addFunc.myGetArguments()
val returnType = addFunc.myGetType().returnType
val basicBlocks = addFunc.myGetBasicBlocks()
```

### BasicBlock

`BasicBlock` 是指令的容器，每个基本块以终结指令（terminator）结束。

```kotlin
// 创建基本块
val entry = func.createBasicBlock("entry")
val thenBB = func.createBasicBlock("then")
val elseBB = func.createBasicBlock("else")
val mergeBB = func.createBasicBlock("merge")

// 获取基本块信息
val name = entry.myGetName()
val parent = entry.myGetParent()
val instructions = entry.myGetInstructions()
val isTerminated = entry.isTerminated()
val terminator = entry.myGetTerminator()
```

### IRBuilder

`IRBuilder` 是创建指令的主要接口。

```kotlin
import llvm.IRBuilder

val builder = IRBuilder(context)
builder.setInsertPoint(entryBB)  // 设置插入点

// 现在可以使用 builder 创建指令
val sum = builder.createAdd(arg0, arg1, "sum")
builder.createRet(sum)
```

---

## 类型系统

### 基本类型

```kotlin
// 整数类型
val i1 = context.myGetI1Type()   // 1-bit integer (boolean)
val i8 = context.myGetI8Type()   // 8-bit integer (char)
val i32 = context.myGetI32Type() // 32-bit integer

// 空类型
val void = context.myGetVoidType()

// 指针类型（不透明指针）
val ptr = context.myGetPointerType()
```

### 复合类型

```kotlin
// 数组类型
val arrayType = context.myGetArrayType(context.myGetI32Type(), 5)  // [5 x i32]

// 结构体类型
val pointType = context.myGetStructType("Point")
pointType.elements.add(context.myGetI32Type())  // x: i32
pointType.elements.add(context.myGetI32Type())  // y: i32
// 产生: %struct.Point = type { i32, i32 }

// 函数类型
val funcType = context.myGetFunctionType(
    returnType = context.myGetI32Type(),
    paramTypes = listOf(context.myGetI32Type(), context.myGetI32Type())
)
// 产生: i32 (i32, i32)
```

### AST 类型到 IR 类型的映射

使用 `ir/typeConverter.kt` 中的 `getIRType` 函数进行转换：

```kotlin
import ir.getIRType
import ast.PrimitiveResolvedType
import ast.ArrayResolvedType
import ast.ReferenceResolvedType

// 原始类型
getIRType(context, PrimitiveResolvedType("i32"))   // -> I32Type
getIRType(context, PrimitiveResolvedType("bool"))  // -> I1Type
getIRType(context, PrimitiveResolvedType("char"))  // -> I8Type
getIRType(context, PrimitiveResolvedType("str"))   // -> PointerType

// 引用类型（总是变成指针）
getIRType(context, ReferenceResolvedType(inner, isMut))  // -> PointerType

// 数组类型
getIRType(context, ArrayResolvedType(elementType, lengthExpr))  // -> ArrayType
```

---

## 基本代码生成

### 创建一个简单函数

```kotlin
// 对应源代码: fn add(a: i32, b: i32) -> i32 { a + b }

val context = LLVMContext()
val module = Module("example", context)
val builder = IRBuilder(context)

// 1. 定义函数类型
val i32 = context.myGetI32Type()
val funcType = context.myGetFunctionType(i32, listOf(i32, i32))

// 2. 创建函数
val addFunc = module.createFunction("add", funcType)

// 3. 设置参数
val argA = Argument("a", i32, addFunc)
val argB = Argument("b", i32, addFunc)
addFunc.setArguments(listOf(argA, argB))

// 4. 创建入口基本块
val entry = addFunc.createBasicBlock("entry")
builder.setInsertPoint(entry)

// 5. 生成加法指令
val sum = builder.createAdd(argA, argB, "sum")

// 6. 生成返回指令
builder.createRet(sum)

// 打印生成的 IR
println(module.print())
```

输出：
```llvm
define i32 @add(i32 %a, i32 %b) {
entry:
  %sum = add i32 %a, %b
  ret i32 %sum
}
```

---

## 语句降低

### LetStmtNode（变量声明）

```kotlin
// 对应: let x: i32 = 10;

fun lowerLetStmt(node: LetStmtNode, builder: IRBuilder, context: LLVMContext) {
    // 获取变量类型
    val varType = getIRType(context, node.variableResolvedType)
    
    // 分配栈空间
    val allocaInst = builder.createAlloca(varType, "x")
    
    // 生成初始化值
    val initValue = lowerExpr(node.value, builder, context)
    
    // 存储到分配的地址
    builder.createStore(initValue, allocaInst)
    
    // 记录变量地址到符号表（实际代码中 node.irAddress 已经被设置）
    // symbolTable["x"] = allocaInst
}
```

生成的 IR：
```llvm
%x = alloca i32
store i32 10, ptr %x
```

### ExprStmtNode（表达式语句）

```kotlin
// 对应: foo();

fun lowerExprStmt(node: ExprStmtNode, builder: IRBuilder, context: LLVMContext) {
    // 简单地求值表达式，丢弃结果
    lowerExpr(node.expr, builder, context)
}
```

### AssignExprNode（赋值表达式）

```kotlin
// 对应: x = 20;

fun lowerAssignExpr(node: AssignExprNode, builder: IRBuilder, context: LLVMContext): Value {
    // 获取左值地址
    val ptr = getLValueAddress(node.left, builder, context)
    
    // 生成右值
    val value = lowerExpr(node.right, builder, context)
    
    // 存储
    builder.createStore(value, ptr)
    
    // 赋值表达式返回 unit 类型，不需要返回有意义的值
    return context.myGetIntConstant(context.myGetI8Type(), 0)
}
```

---

## 表达式降低

### 字面量

```kotlin
// IntLiteralExprNode
fun lowerIntLiteral(node: IntLiteralExprNode, context: LLVMContext): ConstantInt {
    val value = stringToUInt(node.raw).toInt()
    return context.myGetIntConstant(context.myGetI32Type(), value)
}

// BooleanLiteralExprNode
fun lowerBoolLiteral(node: BooleanLiteralExprNode, context: LLVMContext): ConstantInt {
    val value = if (node.raw == "true") 1 else 0
    return context.myGetIntConstant(context.myGetI1Type(), value)
}

// CharLiteralExprNode
fun lowerCharLiteral(node: CharLiteralExprNode, context: LLVMContext): ConstantInt {
    val charValue = stringToChar(node.raw)
    return context.myGetIntConstant(context.myGetI8Type(), charValue.code)
}
```

### PathExprNode（变量引用）

```kotlin
// 对应: x （读取变量 x 的值）

fun lowerPathExpr(node: PathExprNode, builder: IRBuilder, context: LLVMContext): Value {
    // 获取变量地址（从符号表或 node.irAddress）
    val varAddress = node.irAddress  // 例如 "%x.0"
    
    // 获取变量类型
    val varType = getIRType(context, node.resolvedType)
    
    // 加载值
    // 注意：这里需要构建一个代表地址的 Value 对象
    // 实际实现中，需要维护一个从名称到 Value 的映射
    return builder.createLoad(varType, addressValue, "load_x")
}
```

### BinaryExprNode（二元运算）

```kotlin
// 对应: a + b, a - b, a * b, etc.

fun lowerBinaryExpr(node: BinaryExprNode, builder: IRBuilder, context: LLVMContext): Value {
    val left = lowerExpr(node.left, builder, context)
    val right = lowerExpr(node.right, builder, context)
    
    return when (node.operator.type) {
        TokenType.Add -> builder.createAdd(left, right, "add")
        TokenType.SubNegate -> builder.createSub(left, right, "sub")
        TokenType.Mul -> builder.createMul(left, right, "mul")
        TokenType.Div -> builder.createSDiv(left, right, "div")  // 有符号除法
        TokenType.Mod -> builder.createSRem(left, right, "rem")  // 有符号取余
        TokenType.BitAnd -> builder.createAnd(left, right, "and")
        TokenType.BitOr -> builder.createOr(left, right, "or")
        TokenType.BitXor -> builder.createXor(left, right, "xor")
        TokenType.Shl -> builder.createShl(left, right, "shl")
        TokenType.Shr -> builder.createAShr(left, right, "shr")  // 算术右移
        else -> throw IRException("Unknown binary operator: ${node.operator}")
    }
}
```

### ComparisonExprNode（比较运算）

```kotlin
// 对应: a < b, a == b, etc.

fun lowerComparisonExpr(node: ComparisonExprNode, builder: IRBuilder, context: LLVMContext): Value {
    val left = lowerExpr(node.left, builder, context)
    val right = lowerExpr(node.right, builder, context)
    
    return when (node.operator.type) {
        TokenType.Eq -> builder.createICmpEQ(left, right, "eq")
        TokenType.Neq -> builder.createICmpNE(left, right, "ne")
        TokenType.Lt -> builder.createICmpSLT(left, right, "lt")
        TokenType.Le -> builder.createICmpSLE(left, right, "le")
        TokenType.Gt -> builder.createICmpSGT(left, right, "gt")
        TokenType.Ge -> builder.createICmpSGE(left, right, "ge")
        else -> throw IRException("Unknown comparison operator: ${node.operator}")
    }
}
```

### NegationExprNode（一元运算）

```kotlin
// 对应: -x, !x

fun lowerNegationExpr(node: NegationExprNode, builder: IRBuilder, context: LLVMContext): Value {
    val operand = lowerExpr(node.expr, builder, context)
    
    return when (node.operator.type) {
        TokenType.SubNegate -> builder.createNeg(operand, "neg")
        TokenType.Not -> builder.createNot(operand, "not")
        else -> throw IRException("Unknown negation operator: ${node.operator}")
    }
}
```

---

## 控制流

### IfExprNode（if-else）

```kotlin
// 对应:
// if condition {
//     thenBranch
// } else {
//     elseBranch
// }

fun lowerIfExpr(
    node: IfExprNode,
    builder: IRBuilder,
    context: LLVMContext,
    currentFunc: Function
): Value? {
    // 1. 生成条件值
    val condValue = lowerExpr(node.condition, builder, context)
    
    // 2. 创建基本块
    val thenBB = currentFunc.createBasicBlock("if.then")
    val elseBB = currentFunc.createBasicBlock("if.else")
    val mergeBB = currentFunc.createBasicBlock("if.merge")
    
    // 3. 条件跳转
    builder.createCondBr(condValue, thenBB, elseBB)
    
    // 4. 生成 then 分支
    builder.setInsertPoint(thenBB)
    val thenValue = lowerBlockExpr(node.thenBranch, builder, context)
    if (!thenBB.isTerminated()) {
        builder.createBr(mergeBB)
    }
    val thenEndBB = builder.myGetInsertFunction()!!.myGetBasicBlocks().last()
    
    // 5. 生成 else 分支
    builder.setInsertPoint(elseBB)
    val elseValue = if (node.elseBranch != null) {
        lowerExpr(node.elseBranch, builder, context)
    } else {
        null
    }
    if (!elseBB.isTerminated()) {
        builder.createBr(mergeBB)
    }
    val elseEndBB = builder.myGetInsertFunction()!!.myGetBasicBlocks().last()
    
    // 6. 合并点
    builder.setInsertPoint(mergeBB)
    
    // 7. 如果 if 有返回值，创建 PHI 节点
    if (thenValue != null && elseValue != null && node.resolvedType !is UnitResolvedType) {
        val phi = builder.createPHI(getIRType(context, node.resolvedType), "if.result")
        phi.addIncoming(thenValue, thenEndBB)
        phi.addIncoming(elseValue, elseEndBB)
        return phi
    }
    
    return null
}
```

生成的 IR：
```llvm
  %cond = icmp eq i32 %x, 0
  br i1 %cond, label %if.then, label %if.else

if.then:
  ; then 分支代码
  br label %if.merge

if.else:
  ; else 分支代码
  br label %if.merge

if.merge:
  %if.result = phi i32 [ %thenVal, %if.then ], [ %elseVal, %if.else ]
```

### PredicateLoopExprNode（while 循环）

```kotlin
// 对应:
// while condition {
//     body
// }

fun lowerWhileLoop(
    node: PredicateLoopExprNode,
    builder: IRBuilder,
    context: LLVMContext,
    currentFunc: Function
) {
    // 1. 创建基本块
    val condBB = currentFunc.createBasicBlock("while.cond")
    val bodyBB = currentFunc.createBasicBlock("while.body")
    val afterBB = currentFunc.createBasicBlock("while.after")
    
    // 2. 跳转到条件检查
    builder.createBr(condBB)
    
    // 3. 条件检查块
    builder.setInsertPoint(condBB)
    val condValue = lowerExpr(node.condition, builder, context)
    builder.createCondBr(condValue, bodyBB, afterBB)
    
    // 4. 循环体
    builder.setInsertPoint(bodyBB)
    lowerBlockExpr(node.block, builder, context)
    if (!bodyBB.isTerminated()) {
        builder.createBr(condBB)  // 返回条件检查
    }
    
    // 5. 循环后
    builder.setInsertPoint(afterBB)
}
```

生成的 IR：
```llvm
  br label %while.cond

while.cond:
  %cond = icmp slt i32 %i, 10
  br i1 %cond, label %while.body, label %while.after

while.body:
  ; 循环体代码
  br label %while.cond

while.after:
  ; 循环后代码
```

### InfiniteLoopExprNode（loop 循环）

```kotlin
// 对应:
// loop {
//     body
//     if condition { break value; }
// }

fun lowerInfiniteLoop(
    node: InfiniteLoopExprNode,
    builder: IRBuilder,
    context: LLVMContext,
    currentFunc: Function,
    loopContext: LoopContext  // 记录 break/continue 目标
): Value? {
    // 1. 创建基本块
    val bodyBB = currentFunc.createBasicBlock("loop.body")
    val afterBB = currentFunc.createBasicBlock("loop.after")
    
    // 2. 设置循环上下文（用于 break/continue）
    loopContext.push(afterBB, bodyBB)
    
    // 3. 跳转到循环体
    builder.createBr(bodyBB)
    
    // 4. 循环体
    builder.setInsertPoint(bodyBB)
    lowerBlockExpr(node.block, builder, context)
    if (!bodyBB.isTerminated()) {
        builder.createBr(bodyBB)  // 无限循环
    }
    
    // 5. 循环后
    loopContext.pop()
    builder.setInsertPoint(afterBB)
    
    // 返回 break 的值（如果有）
    return loopContext.breakValue
}
```

### Break 和 Continue

```kotlin
// break value;
fun lowerBreakExpr(
    node: BreakExprNode,
    builder: IRBuilder,
    context: LLVMContext,
    loopContext: LoopContext
) {
    val value = node.value?.let { lowerExpr(it, builder, context) }
    loopContext.setBreakValue(value)
    builder.createBr(loopContext.afterBB)
}

// continue;
fun lowerContinueExpr(builder: IRBuilder, loopContext: LoopContext) {
    builder.createBr(loopContext.condBB ?: loopContext.bodyBB)
}
```

---

## 函数与调用

### FunctionItemNode（函数定义）

```kotlin
// 对应:
// fn foo(x: i32, y: i32) -> i32 {
//     x + y
// }

fun lowerFunctionItem(
    node: FunctionItemNode,
    module: Module,
    context: LLVMContext
) {
    val builder = IRBuilder(context)
    
    // 1. 构建参数类型列表
    val paramTypes = node.params.map { param ->
        getIRType(context, firstVisitor.resolveType(param.type))
    }
    
    // 2. 构建返回类型
    val returnType = if (node.returnType != null) {
        getIRType(context, firstVisitor.resolveType(node.returnType))
    } else {
        context.myGetVoidType()
    }
    
    // 3. 创建函数类型和函数
    val funcType = context.myGetFunctionType(returnType, paramTypes)
    val func = module.createFunction(node.fnName.value, funcType)
    
    // 4. 设置参数
    val arguments = node.params.mapIndexed { i, param ->
        val pattern = param.paramPattern as IdentifierPatternNode
        Argument(pattern.name.value, paramTypes[i], func)
    }
    func.setArguments(arguments)
    
    // 5. 创建入口块
    if (node.body != null) {
        val entry = func.createBasicBlock("entry")
        builder.setInsertPoint(entry)
        
        // 6. 为参数分配栈空间并存储（按需）
        arguments.forEachIndexed { i, arg ->
            val alloca = builder.createAlloca(paramTypes[i], arg.name)
            builder.createStore(arg, alloca)
            // 记录到符号表
        }
        
        // 7. 生成函数体
        val result = lowerBlockExpr(node.body, builder, context)
        
        // 8. 添加返回指令
        if (!entry.isTerminated()) {
            if (returnType is VoidType) {
                builder.createRet(/* void return */)
            } else if (result != null) {
                builder.createRet(result)
            }
        }
    }
}
```

### CallExprNode（函数调用）

```kotlin
// 对应: foo(1, 2)

fun lowerCallExpr(
    node: CallExprNode,
    builder: IRBuilder,
    context: LLVMContext,
    module: Module
): Value {
    // 1. 解析函数
    val funcPath = node.func as PathExprNode
    val funcName = funcPath.first.segment.value
    val func = module.myGetFunction(funcName)
        ?: throw IRException("Undefined function: $funcName")
    
    // 2. 生成参数值
    val args = node.params.map { param ->
        lowerExpr(param, builder, context)
    }
    
    // 3. 创建调用指令
    return builder.createCall(func, args, "call_$funcName")
}
```

### ReturnExprNode（返回语句）

```kotlin
// 对应: return x;

fun lowerReturnExpr(
    node: ReturnExprNode,
    builder: IRBuilder,
    context: LLVMContext
) {
    val value = node.value?.let { lowerExpr(it, builder, context) }
    if (value != null) {
        builder.createRet(value)
    } else {
        // void 返回
        builder.createRet(ReturnInst(null))
    }
}
```

---

## 结构体与数组

### StructExprNode（结构体构造）

```kotlin
// 对应:
// struct Point { x: i32, y: i32 }
// Point { x: 1, y: 2 }

fun lowerStructExpr(
    node: StructExprNode,
    builder: IRBuilder,
    context: LLVMContext
): Value {
    // 1. 获取结构体类型
    val structName = (node.path.first.segment.value)
    val structType = context.myGetStructType(structName)
    
    // 2. 分配栈空间
    val alloca = builder.createAlloca(structType, "struct_tmp")
    
    // 3. 逐字段赋值
    node.fields.forEachIndexed { i, field ->
        val fieldValue = lowerExpr(field.value, builder, context)
        
        // GEP 获取字段地址
        val indices = listOf(
            context.myGetIntConstant(context.myGetI32Type(), 0),
            context.myGetIntConstant(context.myGetI32Type(), i)
        )
        val fieldPtr = builder.createGEP(structType, alloca, indices, "field_$i")
        
        // 存储字段值
        builder.createStore(fieldValue, fieldPtr)
    }
    
    // 4. 加载整个结构体
    return builder.createLoad(structType, alloca, "struct_val")
}
```

### FieldExprNode（字段访问）

```kotlin
// 对应: point.x

fun lowerFieldExpr(
    node: FieldExprNode,
    builder: IRBuilder,
    context: LLVMContext
): Value {
    // 1. 获取结构体地址
    val structPtr = getLValueAddress(node.struct, builder, context)
    
    // 2. 获取结构体类型和字段索引
    val structType = getIRType(context, node.struct.resolvedType) as StructType
    val fieldName = node.field.value
    val fieldIndex = getFieldIndex(structType, fieldName)
    
    // 3. GEP 获取字段地址
    val indices = listOf(
        context.myGetIntConstant(context.myGetI32Type(), 0),
        context.myGetIntConstant(context.myGetI32Type(), fieldIndex)
    )
    val fieldPtr = builder.createGEP(structType, structPtr, indices, "field_ptr")
    
    // 4. 加载字段值
    val fieldType = structType.myGetElementType(fieldIndex)
    return builder.createLoad(fieldType, fieldPtr, "field_val")
}
```

### ArrayListExprNode（数组字面量）

```kotlin
// 对应: [1, 2, 3]

fun lowerArrayListExpr(
    node: ArrayListExprNode,
    builder: IRBuilder,
    context: LLVMContext
): Value {
    val elemType = getIRType(context, (node.resolvedType as ArrayResolvedType).elementType)
    val arrayType = context.myGetArrayType(elemType, node.elements.size)
    
    // 分配栈空间
    val alloca = builder.createAlloca(arrayType, "array_tmp")
    
    // 逐元素赋值
    node.elements.forEachIndexed { i, elem ->
        val elemValue = lowerExpr(elem, builder, context)
        
        val indices = listOf(
            context.myGetIntConstant(context.myGetI32Type(), 0),
            context.myGetIntConstant(context.myGetI32Type(), i)
        )
        val elemPtr = builder.createGEP(arrayType, alloca, indices, "elem_$i")
        builder.createStore(elemValue, elemPtr)
    }
    
    return builder.createLoad(arrayType, alloca, "array_val")
}
```

### IndexExprNode（数组索引）

```kotlin
// 对应: arr[i]

fun lowerIndexExpr(
    node: IndexExprNode,
    builder: IRBuilder,
    context: LLVMContext
): Value {
    // 1. 获取数组地址
    val arrayPtr = getLValueAddress(node.base, builder, context)
    
    // 2. 生成索引值
    val indexValue = lowerExpr(node.index, builder, context)
    
    // 3. 获取数组类型
    val arrayType = getIRType(context, node.base.resolvedType) as ArrayType
    
    // 4. GEP 获取元素地址
    val indices = listOf(
        context.myGetIntConstant(context.myGetI32Type(), 0),
        indexValue
    )
    val elemPtr = builder.createGEP(arrayType, arrayPtr, indices, "elem_ptr")
    
    // 5. 加载元素值
    return builder.createLoad(arrayType.elementType, elemPtr, "elem_val")
}
```

---

## 完整示例

### 示例：斐波那契函数

源代码：
```rust
fn fib(n: i32) -> i32 {
    if n <= 1 {
        n
    } else {
        fib(n - 1) + fib(n - 2)
    }
}
```

生成 IR 的代码：

```kotlin
fun generateFibonacci(): String {
    val context = LLVMContext()
    val module = Module("fib_module", context)
    val builder = IRBuilder(context)
    
    val i32 = context.myGetI32Type()
    val i1 = context.myGetI1Type()
    
    // 创建函数
    val funcType = context.myGetFunctionType(i32, listOf(i32))
    val fibFunc = module.createFunction("fib", funcType)
    
    // 参数
    val nArg = Argument("n", i32, fibFunc)
    fibFunc.setArguments(listOf(nArg))
    
    // 基本块
    val entry = fibFunc.createBasicBlock("entry")
    val thenBB = fibFunc.createBasicBlock("then")
    val elseBB = fibFunc.createBasicBlock("else")
    
    // entry: if n <= 1
    builder.setInsertPoint(entry)
    val one = context.myGetIntConstant(i32, 1)
    val cond = builder.createICmpSLE(nArg, one, "cmp")
    builder.createCondBr(cond, thenBB, elseBB)
    
    // then: return n
    builder.setInsertPoint(thenBB)
    builder.createRet(nArg)
    
    // else: return fib(n-1) + fib(n-2)
    builder.setInsertPoint(elseBB)
    val nMinus1 = builder.createSub(nArg, one, "n_minus_1")
    val two = context.myGetIntConstant(i32, 2)
    val nMinus2 = builder.createSub(nArg, two, "n_minus_2")
    
    val call1 = builder.createCall(fibFunc, listOf(nMinus1), "fib1")
    val call2 = builder.createCall(fibFunc, listOf(nMinus2), "fib2")
    val sum = builder.createAdd(call1, call2, "sum")
    builder.createRet(sum)
    
    return module.print()
}
```

生成的 IR：
```llvm
define i32 @fib(i32 %n) {
entry:
  cmp = icmp sle i32 %n, 1
  br i1 %cmp, label %then, label %else

then:
  ret i32 %n

else:
  %n_minus_1 = sub i32 %n, 1
  %n_minus_2 = sub i32 %n, 2
  %fib1 = call i32 @fib(i32 %n_minus_1)
  %fib2 = call i32 @fib(i32 %n_minus_2)
  %sum = add i32 %fib1, %fib2
  ret i32 %sum
}
```

### 示例：带循环的函数

源代码：
```rust
fn sum_to_n(n: i32) -> i32 {
    let mut sum: i32 = 0;
    let mut i: i32 = 1;
    while i <= n {
        sum = sum + i;
        i = i + 1;
    }
    sum
}
```

生成 IR 的代码：

```kotlin
fun generateSumToN(): String {
    val context = LLVMContext()
    val module = Module("sum_module", context)
    val builder = IRBuilder(context)
    
    val i32 = context.myGetI32Type()
    val i1 = context.myGetI1Type()
    
    // 创建函数
    val funcType = context.myGetFunctionType(i32, listOf(i32))
    val sumFunc = module.createFunction("sum_to_n", funcType)
    
    val nArg = Argument("n", i32, sumFunc)
    sumFunc.setArguments(listOf(nArg))
    
    // 基本块
    val entry = sumFunc.createBasicBlock("entry")
    val condBB = sumFunc.createBasicBlock("while.cond")
    val bodyBB = sumFunc.createBasicBlock("while.body")
    val afterBB = sumFunc.createBasicBlock("while.after")
    
    // entry: 初始化变量
    builder.setInsertPoint(entry)
    val sumPtr = builder.createAlloca(i32, "sum")
    val iPtr = builder.createAlloca(i32, "i")
    builder.createStore(context.myGetIntConstant(i32, 0), sumPtr)
    builder.createStore(context.myGetIntConstant(i32, 1), iPtr)
    builder.createBr(condBB)
    
    // while.cond: 检查 i <= n
    builder.setInsertPoint(condBB)
    val iVal = builder.createLoad(i32, iPtr, "i_val")
    val cond = builder.createICmpSLE(iVal, nArg, "cmp")
    builder.createCondBr(cond, bodyBB, afterBB)
    
    // while.body: sum += i; i += 1
    builder.setInsertPoint(bodyBB)
    val sumVal = builder.createLoad(i32, sumPtr, "sum_val")
    val iValBody = builder.createLoad(i32, iPtr, "i_val_body")
    val newSum = builder.createAdd(sumVal, iValBody, "new_sum")
    builder.createStore(newSum, sumPtr)
    val one = context.myGetIntConstant(i32, 1)
    val newI = builder.createAdd(iValBody, one, "new_i")
    builder.createStore(newI, iPtr)
    builder.createBr(condBB)
    
    // while.after: return sum
    builder.setInsertPoint(afterBB)
    val result = builder.createLoad(i32, sumPtr, "result")
    builder.createRet(result)
    
    return module.print()
}
```

---

## 调试与验证

### 打印生成的 IR

```kotlin
val module = Module("test", context)
// ... 生成代码 ...

// 打印 IR
val irString = module.print()
println(irString)

// 或者保存到文件
File("output.ll").writeText(irString)
```

### 使用 LLVM 工具验证

如果安装了 LLVM 工具链，可以使用以下命令验证生成的 IR：

```bash
# 验证 IR 语法
llvm-as output.ll -o output.bc

# 反汇编查看
llvm-dis output.bc -o output_check.ll

# 解释执行
lli output.bc

# 编译为目标代码
llc output.bc -o output.s

# 链接为可执行文件
clang output.s -o output
```

### 常见问题

1. **类型不匹配**：确保操作数类型相同
   ```kotlin
   // 错误：i32 和 i8 不能直接相加
   builder.createAdd(i32Value, i8Value)
   
   // 正确：先扩展类型
   val extended = builder.createZExt(context.myGetI32Type(), i8Value)
   builder.createAdd(i32Value, extended)
   ```

2. **缺少终结指令**：每个基本块必须以终结指令结束
   ```kotlin
   if (!bb.isTerminated()) {
       builder.createBr(nextBB)  // 或 builder.createRet(value)
   }
   ```

3. **PHI 节点位置**：PHI 节点必须在基本块的开头
   ```kotlin
   builder.setInsertPoint(mergeBB)
   val phi = builder.createPHI(i32, "result")
   phi.addIncoming(val1, bb1)
   phi.addIncoming(val2, bb2)
   // 之后再添加其他指令
   ```

---

## API 快速参考

### LLVMContext 方法

| 方法 | 返回类型 | 描述 |
|------|---------|------|
| `myGetVoidType()` | `VoidType` | 获取 void 类型 |
| `myGetI32Type()` | `I32Type` | 获取 i32 类型 |
| `myGetI8Type()` | `I8Type` | 获取 i8 类型 |
| `myGetI1Type()` | `I1Type` | 获取 i1 类型 |
| `myGetPointerType()` | `PointerType` | 获取指针类型 |
| `myGetArrayType(elem, n)` | `ArrayType` | 获取 [n x elem] 类型 |
| `myGetStructType(name)` | `StructType` | 获取结构体类型 |
| `myGetFunctionType(ret, params)` | `FunctionType` | 获取函数类型 |
| `myGetIntConstant(type, value)` | `ConstantInt` | 创建整数常量 |
| `myGetNullPtrConstant()` | `ConstantPointerNull` | 创建空指针常量 |

### IRBuilder 方法

| 方法 | 返回类型 | 描述 |
|------|---------|------|
| `setInsertPoint(bb)` | `Unit` | 设置插入点 |
| `createAdd(lhs, rhs, name)` | `BinaryOperator` | 加法 |
| `createSub(lhs, rhs, name)` | `BinaryOperator` | 减法 |
| `createMul(lhs, rhs, name)` | `BinaryOperator` | 乘法 |
| `createSDiv(lhs, rhs, name)` | `BinaryOperator` | 有符号除法 |
| `createSRem(lhs, rhs, name)` | `BinaryOperator` | 有符号取余 |
| `createAnd(lhs, rhs, name)` | `BinaryOperator` | 按位与 |
| `createOr(lhs, rhs, name)` | `BinaryOperator` | 按位或 |
| `createXor(lhs, rhs, name)` | `BinaryOperator` | 按位异或 |
| `createShl(lhs, rhs, name)` | `BinaryOperator` | 左移 |
| `createAShr(lhs, rhs, name)` | `BinaryOperator` | 算术右移 |
| `createNeg(operand, name)` | `UnaryOperator` | 取负 |
| `createNot(operand, name)` | `UnaryOperator` | 按位取反 |
| `createICmpEQ(lhs, rhs, name)` | `ICmpInst` | 等于比较 |
| `createICmpNE(lhs, rhs, name)` | `ICmpInst` | 不等于比较 |
| `createICmpSLT(lhs, rhs, name)` | `ICmpInst` | 有符号小于 |
| `createICmpSLE(lhs, rhs, name)` | `ICmpInst` | 有符号小于等于 |
| `createICmpSGT(lhs, rhs, name)` | `ICmpInst` | 有符号大于 |
| `createICmpSGE(lhs, rhs, name)` | `ICmpInst` | 有符号大于等于 |
| `createAlloca(type, name)` | `AllocaInst` | 分配栈空间 |
| `createLoad(type, ptr, name)` | `LoadInst` | 加载值 |
| `createStore(value, ptr)` | `StoreInst` | 存储值 |
| `createGEP(type, ptr, indices, name)` | `GetElementPtrInst` | 获取元素指针 |
| `createBr(dest)` | `BrInst` | 无条件跳转 |
| `createCondBr(cond, then, else)` | `ConBrInst` | 条件跳转 |
| `createRet(value)` | `ReturnInst` | 返回 |
| `createCall(func, args, name)` | `CallInst` | 函数调用 |
| `createPHI(type, name)` | `PHINode` | PHI 节点 |
| `createZExt(type, value, name)` | `ZExtInst` | 零扩展 |

---

## 附录：AST 节点到 IR 的映射表

| AST 节点 | IR 生成策略 |
|----------|------------|
| `IntLiteralExprNode` | `ConstantInt` |
| `BooleanLiteralExprNode` | `ConstantInt` (i1) |
| `CharLiteralExprNode` | `ConstantInt` (i8) |
| `LetStmtNode` | `alloca` + `store` |
| `PathExprNode` (变量) | `load` |
| `BinaryExprNode` | `add/sub/mul/sdiv/srem/and/or/xor/shl/ashr` |
| `ComparisonExprNode` | `icmp` |
| `NegationExprNode` | `neg/not` |
| `AssignExprNode` | `store` |
| `IfExprNode` | `br` + `cond br` + `phi` |
| `PredicateLoopExprNode` | `br` + `cond br` (循环结构) |
| `InfiniteLoopExprNode` | `br` (无条件循环) |
| `BreakExprNode` | `br` 到 after 块 |
| `ContinueExprNode` | `br` 到 cond/body 块 |
| `ReturnExprNode` | `ret` |
| `CallExprNode` | `call` |
| `StructExprNode` | `alloca` + `gep` + `store` |
| `FieldExprNode` | `gep` + `load` |
| `ArrayListExprNode` | `alloca` + `gep` + `store` |
| `IndexExprNode` | `gep` + `load` |
| `BorrowExprNode` | 返回地址（不生成指令） |
| `DerefExprNode` | `load` |
