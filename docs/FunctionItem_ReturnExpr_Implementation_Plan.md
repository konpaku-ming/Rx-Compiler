# visitFunctionItem 与 visitReturnExpr 的 IR 生成实现计划

本文档描述 `visitFunctionItem` 和 `visitReturnExpr` 的 IR 生成实现方案，基于现有代码结构和设计原则。

---

## 目录

1. [设计背景与目标](#设计背景与目标)
2. [现有代码分析](#现有代码分析)
3. [核心设计：统一返回约定](#核心设计统一返回约定)
4. [visitFunctionItem 实现方案](#visitfunctionitem-实现方案)
5. [visitReturnExpr 实现方案](#visitreturnexpr-实现方案)
6. [调用点转换方案](#调用点转换方案)
7. [实现步骤与优先级](#实现步骤与优先级)
8. [示例与预期 IR](#示例与预期-ir)

---

## 设计背景与目标

### 核心需求

根据要求，所有函数都需要遵循统一的返回约定：

1. **所有函数返回类型改为 `void`**
2. **第一个参数变为返回类型的指针**，函数通过该指针返回真实的返回值
3. **对于 `UnitType` 返回类型，当作返回 `i8` 处理**（保持统一性）

### 为什么采用这种设计？

1. **简化 ABI**：无论返回类型是标量还是聚合类型，都使用相同的调用约定
2. **避免复杂的 ABI 处理**：不需要为不同大小的返回类型选择不同的传递方式
3. **便于后续优化**：统一的调用约定便于内联和其他优化
4. **与现有聚合类型返回处理保持一致**：当前代码已经为 struct/array 返回预留了类似的处理框架

---

## 现有代码分析

### AST 节点结构（astNode.kt）

```kotlin
data class FunctionItemNode(
    val fnName: Token,
    val selfParam: SelfParam?,
    val params: List<FunctionParam>,
    val returnType: TypeNode?,
    val body: BlockExprNode?
) : ItemNode()
```

### FunctionSymbol 结构（scope.kt）

```kotlin
data class FunctionSymbol(
    override val name: String,
    val selfParameter: SelfParameter?,
    val parameters: List<Parameter>,
    val returnType: ResolvedType,
    val isMethod: Boolean,
    val isAssociated: Boolean,
    val isDefined: Boolean = true,
) : Symbol()
```

### 现有 visitFunctionItem 实现（astLower.kt, 189-229行）

```kotlin
override fun visitFunctionItem(node: FunctionItemNode) {
    val previousScope = scopeTree.currentScope
    scopeTree.currentScope = node.scopePosition!!

    // 获取函数符号以获取返回类型信息
    val fnName = node.fnName.value
    val funcSymbol = scopeTree.lookup(fnName)
    // ...

    if (node.body != null) {
        val originalReturnType = getIRType(context, funcSymbol.returnType)

        // 检查是否需要使用 struct/array 返回 ABI
        if (originalReturnType.isAggregate()) {
            // TODO: 完整实现
        }

        visitBlockExpr(node.body, createScope = false)
        currentReturnBufferPtr = null
    }

    scopeTree.currentScope = previousScope
}
```

### 现有 visitReturnExpr 实现（astLower.kt, 1614-1651行）

```kotlin
override fun visitReturnExpr(node: ReturnExprNode) {
    // ... 
    node.value?.accept(this)

    // 如果当前函数使用 struct/array 返回 ABI（currentReturnBufferPtr 不为空）
    if (currentReturnBufferPtr != null && node.value != null) {
        // TODO: 完整实现
    } else {
        // 标量类型直接返回
        // TODO: builder.createRet(node.value?.irValue)
    }
    // ...
}
```

### 现有成员变量

```kotlin
// 当前函数的返回缓冲区指针
private var currentReturnBufferPtr: Value? = null
```

---

## 核心设计：统一返回约定

### 函数签名转换规则

| 原始签名 | 转换后签名 |
|---------|-----------|
| `fn foo() -> i32` | `fn foo(ret_ptr: *i32) -> void` |
| `fn bar(x: i32) -> bool` | `fn bar(ret_ptr: *i1, x: i32) -> void` |
| `fn baz() -> Point` | `fn baz(ret_ptr: *Point) -> void` |
| `fn qux()` (Unit返回) | `fn qux(ret_ptr: *i8) -> void` |
| `fn quux() -> [i32; 3]` | `fn quux(ret_ptr: *[3 x i32]) -> void` |

### UnitType 的处理

根据要求，`UnitType` 当作返回 `i8` 处理：

```kotlin
// 对于返回 Unit 的函数
// 原始：fn foo() -> ()
// 转换：define void @foo(ptr %ret_ptr) { ... store i8 0, ptr %ret_ptr; ret void }
```

这样做的好处：
1. **统一性**：所有函数都有相同的结构，便于代码生成
2. **简化逻辑**：不需要特殊处理无返回值的情况
3. **保持一致性**：与现有 `getIRType` 中 `UnitType -> I8Type` 的映射一致

### 新增：函数返回块（Epilogue Block）

为了正确处理多个 return 语句，需要引入一个统一的返回块：

```kotlin
// 需要新增的成员变量
private var currentReturnBlock: BasicBlock? = null  // 函数的返回块
```

**为什么需要返回块？**

1. 函数可能有多个 return 语句
2. 每个 return 都需要将值写入 `ret_ptr`，然后跳转到返回块
3. 返回块统一执行 `ret void`

---

## visitFunctionItem 实现方案

### 完整实现代码

```kotlin
override fun visitFunctionItem(node: FunctionItemNode) {
    val previousScope = scopeTree.currentScope
    scopeTree.currentScope = node.scopePosition!!

    // 1. 获取函数符号
    val fnName = node.fnName.value
    val funcSymbol = scopeTree.lookup(fnName) as? FunctionSymbol
        ?: throw SemanticException("missing FunctionSymbol for '$fnName'")

    if (node.body != null) {
        // 2. 获取原始返回类型
        val originalReturnType = funcSymbol.returnType
        
        // 3. 计算实际的返回值 IR 类型
        // UnitType 当作 i8 处理，其他类型正常转换
        val returnValueIRType = if (originalReturnType is UnitResolvedType) {
            context.myGetI8Type()
        } else {
            getIRType(context, originalReturnType)
        }

        // 4. 构建转换后的参数类型列表
        // 第一个参数：返回缓冲区指针（ptr 类型）
        val paramTypes = mutableListOf<IRType>()
        paramTypes.add(context.myGetPointerType())  // ret_ptr
        
        // 添加原始参数
        for (param in funcSymbol.parameters) {
            paramTypes.add(getIRType(context, param.paramType))
        }

        // 5. 创建函数类型（返回 void）
        val funcType = context.myGetFunctionType(context.myGetVoidType(), paramTypes)

        // 6. 创建函数
        val func = module.myGetOrCreateFunction(fnName, funcType)

        // 7. 设置函数参数
        val arguments = mutableListOf<Argument>()
        // 第一个参数：ret_ptr
        arguments.add(Argument("ret_ptr", context.myGetPointerType(), func))
        // 原始参数
        node.params.forEachIndexed { i, param ->
            val pattern = param.paramPattern as IdentifierPatternNode
            val paramType = paramTypes[i + 1]  // +1 因为第一个是 ret_ptr
            arguments.add(Argument(pattern.name.value, paramType, func))
        }
        func.setArguments(arguments)

        // 8. 创建入口块
        val entryBB = func.createBasicBlock("entry")
        builder.setInsertPoint(entryBB)

        // 9. 保存返回缓冲区指针（第一个参数）
        currentReturnBufferPtr = arguments[0]

        // 10. 为每个参数创建 alloca 并 store
        //     将参数绑定到对应的 VariableSymbol
        for (i in 1 until arguments.size) {
            val arg = arguments[i]
            val paramType = paramTypes[i]
            val alloca = builder.createAlloca(paramType)
            builder.createStore(arg, alloca)

            // 查找对应的 VariableSymbol 并绑定 irValue
            val paramName = arg.name
            val paramSymbol = scopeTree.currentScope.lookupLocal(paramName) as? VariableSymbol
            paramSymbol?.irValue = alloca
        }

        // 11. 创建返回块（epilogue）
        val returnBB = func.createBasicBlock("return")
        currentReturnBlock = returnBB

        // 12. 生成函数体
        visitBlockExpr(node.body, createScope = false)

        // 13. 如果函数体没有以终结指令结束，需要处理隐式返回
        val currentBB = builder.myGetInsertBlock()
        if (currentBB != null && !currentBB.isTerminated()) {
            // 隐式返回：尾表达式或 Unit
            handleImplicitReturn(node.body, returnValueIRType)
            builder.createBr(returnBB)
        }

        // 14. 生成返回块
        builder.setInsertPoint(returnBB)
        builder.createRet(null)  // ret void

        // 15. 清理状态
        currentReturnBufferPtr = null
        currentReturnBlock = null
    } else {
        // 没有函数体，只声明（用于 extern 函数或 trait 方法）
        // 暂不处理
    }

    scopeTree.currentScope = previousScope
}

/**
 * 处理隐式返回（块的尾表达式或 Unit）
 */
private fun handleImplicitReturn(body: BlockExprNode, returnValueIRType: IRType) {
    val retPtr = currentReturnBufferPtr ?: return
    
    if (body.tailExpr != null && body.irValue != null) {
        // 有尾表达式，将其值写入返回缓冲区
        val tailValue = body.irValue!!
        when (returnValueIRType) {
            is StructType -> {
                // 结构体：使用 memcpy
                val structName = (body.tailExpr.resolvedType as? NamedResolvedType)?.name
                    ?: throw IRException("Expected NamedResolvedType for struct return")
                val sizeFunc = module.myGetFunction("${structName}.size")
                    ?: throw IRException("missing sizeFunc for struct '$structName'")
                val size = builder.createCall(sizeFunc, emptyList())
                builder.createMemCpy(retPtr, tailValue, size, false)
            }
            is ArrayType -> {
                // 数组：使用 memcpy
                val size = getArrayCopySize(returnValueIRType)
                builder.createMemCpy(retPtr, tailValue, size, false)
            }
            else -> {
                // 标量：使用 store
                builder.createStore(tailValue, retPtr)
            }
        }
    } else {
        // 无尾表达式或尾表达式为 Unit
        // 写入 i8 0 作为 Unit 的返回值
        val zero = context.myGetIntConstant(context.myGetI8Type(), 0U)
        builder.createStore(zero, retPtr)
    }
}
```

### 需要新增的成员变量

在 `ASTLower` 类中添加：

```kotlin
// 当前函数的返回块
private var currentReturnBlock: BasicBlock? = null
```

---

## visitReturnExpr 实现方案

### 完整实现代码

```kotlin
override fun visitReturnExpr(node: ReturnExprNode) {
    val previousScope = scopeTree.currentScope
    scopeTree.currentScope = node.scopePosition!!

    // 获取返回缓冲区指针和返回块
    val retPtr = currentReturnBufferPtr
        ?: throw IRException("ReturnExpr not in a function with return buffer")
    val returnBB = currentReturnBlock
        ?: throw IRException("ReturnExpr not in a function with return block")

    // 获取当前函数的返回类型
    // 从 FunctionScope 中获取
    val funcScope = findFunctionScope(scopeTree.currentScope)
        ?: throw IRException("ReturnExpr not in a function scope")
    val originalReturnType = funcScope.returnType
    
    // 计算返回值的 IR 类型
    val returnValueIRType = if (originalReturnType is UnitResolvedType) {
        context.myGetI8Type()
    } else {
        getIRType(context, originalReturnType)
    }

    if (node.value != null) {
        // 有返回值表达式
        node.value.accept(this)
        val returnValue = node.value.irValue
            ?: throw IRException("Return value expression has no IR value")

        // 将返回值写入返回缓冲区
        when (returnValueIRType) {
            is StructType -> {
                // 结构体：使用 memcpy
                val structName = (node.value.resolvedType as? NamedResolvedType)?.name
                    ?: throw IRException("Expected NamedResolvedType for struct return")
                val sizeFunc = module.myGetFunction("${structName}.size")
                    ?: throw IRException("missing sizeFunc for struct '$structName'")
                val size = builder.createCall(sizeFunc, emptyList())
                builder.createMemCpy(retPtr, returnValue, size, false)
            }
            is ArrayType -> {
                // 数组：使用 memcpy
                val size = getArrayCopySize(returnValueIRType)
                builder.createMemCpy(retPtr, returnValue, size, false)
            }
            else -> {
                // 标量类型（包括 i8 for Unit）：使用 store
                builder.createStore(returnValue, retPtr)
            }
        }
    } else {
        // 无返回值表达式（隐式返回 Unit）
        // 写入 i8 0 作为 Unit 的返回值
        val zero = context.myGetIntConstant(context.myGetI8Type(), 0U)
        builder.createStore(zero, retPtr)
    }

    // 跳转到返回块
    builder.createBr(returnBB)

    // return 表达式的类型是 Never，没有值
    node.irValue = null
    node.irAddr = null

    scopeTree.currentScope = previousScope
}

/**
 * 向上查找 FunctionScope
 */
private fun findFunctionScope(scope: Scope): FunctionScope? {
    var current: Scope? = scope
    while (current != null) {
        if (current is FunctionScope) {
            return current
        }
        current = current.parent
    }
    return null
}
```

---

## 调用点转换方案

### visitCallExpr 修改

所有函数调用都需要：
1. 在调用前为返回值分配空间
2. 将返回缓冲区指针作为第一个参数传入
3. 调用后从缓冲区读取返回值

```kotlin
override fun visitCallExpr(node: CallExprNode) {
    val previousScope = scopeTree.currentScope
    scopeTree.currentScope = node.scopePosition!!

    // 获取被调用函数
    val funcPath = node.func as? PathExprNode
        ?: throw IRException("CallExpr func is not a PathExprNode")
    val funcName = funcPath.first.segment.value
    val func = module.myGetFunction(funcName)
        ?: throw IRException("Function '$funcName' not found")

    // 获取调用的返回类型
    val returnType = node.resolvedType
    val returnIRType = if (returnType is UnitResolvedType) {
        context.myGetI8Type()
    } else {
        getIRType(context, returnType)
    }

    // 1. 分配返回缓冲区
    val retAlloca = builder.createAlloca(returnIRType)

    // 2. 构建参数列表（第一个参数为返回缓冲区指针）
    val args = mutableListOf<Value>()
    args.add(retAlloca)  // ret_ptr

    // 添加实际参数
    for (param in node.params) {
        param.accept(this)
        val paramValue = param.irValue
            ?: throw IRException("Call parameter has no IR value")
        args.add(paramValue)
    }

    // 3. 调用函数（返回 void）
    builder.createCall(func, args)

    // 4. 设置 irValue
    if (returnType is UnitResolvedType) {
        // Unit 类型不需要读取返回值
        node.irValue = null
    } else if (returnIRType.isAggregate()) {
        // 聚合类型：返回缓冲区指针
        node.irValue = retAlloca
    } else {
        // 标量类型：从缓冲区 load 出值
        node.irValue = builder.createLoad(returnIRType, retAlloca)
    }
    node.irAddr = null

    scopeTree.currentScope = previousScope
}
```

---

## 实现步骤与优先级

### 第一步：添加必要的成员变量和辅助方法

1. 添加 `currentReturnBlock: BasicBlock?` 成员变量
2. 添加 `findFunctionScope()` 辅助方法
3. 添加 `handleImplicitReturn()` 辅助方法

### 第二步：实现 visitFunctionItem

1. 创建转换后的函数签名（void 返回 + ret_ptr 参数）
2. 创建入口块和返回块
3. 设置 `currentReturnBufferPtr` 和 `currentReturnBlock`
4. 为参数创建 alloca 并绑定到 VariableSymbol
5. 生成函数体
6. 处理隐式返回
7. 生成返回块（ret void）

### 第三步：实现 visitReturnExpr

1. 获取返回缓冲区指针和返回块
2. 求值返回表达式
3. 将值写入返回缓冲区
4. 跳转到返回块

### 第四步：修改 visitCallExpr

1. 分配返回缓冲区
2. 将缓冲区指针作为第一个参数传入
3. 调用后处理返回值

### 第五步：测试验证

使用现有测试用例验证实现。

---

## 示例与预期 IR

### 示例 1：返回 i32 的函数

**源代码：**
```rust
fn add(a: i32, b: i32) -> i32 {
    a + b
}

fn main() {
    let x: i32 = add(1, 2);
}
```

**预期 IR：**
```llvm
define void @add(ptr %ret_ptr, i32 %a, i32 %b) {
entry:
  %tmp.0 = alloca i32          ; alloca for param a
  store i32 %a, ptr %tmp.0
  %tmp.1 = alloca i32          ; alloca for param b
  store i32 %b, ptr %tmp.1
  %tmp.2 = load i32, ptr %tmp.0
  %tmp.3 = load i32, ptr %tmp.1
  %tmp.4 = add i32 %tmp.2, %tmp.3
  store i32 %tmp.4, ptr %ret_ptr  ; 写入返回缓冲区
  br label %return

return:
  ret void
}

define void @main(ptr %ret_ptr) {
entry:
  %tmp.0 = alloca i32          ; 返回值缓冲区
  call void @add(ptr %tmp.0, i32 1, i32 2)
  %tmp.1 = load i32, ptr %tmp.0
  %tmp.2 = alloca i32          ; 变量 x
  store i32 %tmp.1, ptr %tmp.2
  store i8 0, ptr %ret_ptr     ; main 返回 Unit
  br label %return

return:
  ret void
}
```

### 示例 2：返回 Unit 的函数

**源代码：**
```rust
fn greet() {
    // 不返回任何值，隐式返回 Unit
}
```

**预期 IR：**
```llvm
define void @greet(ptr %ret_ptr) {
entry:
  store i8 0, ptr %ret_ptr     ; Unit 作为 i8 处理
  br label %return

return:
  ret void
}
```

### 示例 3：返回结构体的函数

**源代码：**
```rust
struct Point { x: i32, y: i32 }

fn createPoint(x: i32, y: i32) -> Point {
    Point { x: x, y: y }
}
```

**预期 IR：**
```llvm
define void @createPoint(ptr %ret_ptr, i32 %x, i32 %y) {
entry:
  %tmp.0 = alloca i32          ; alloca for param x
  store i32 %x, ptr %tmp.0
  %tmp.1 = alloca i32          ; alloca for param y
  store i32 %y, ptr %tmp.1
  
  %tmp.2 = alloca %struct.Point   ; 临时结构体
  ; ... 构建结构体 ...
  
  %size = call i32 @Point.size()
  call void @llvm.memcpy.p0.p0.i32(ptr %ret_ptr, ptr %tmp.2, i32 %size, i1 false)
  br label %return

return:
  ret void
}
```

### 示例 4：显式 return 语句

**源代码：**
```rust
fn abs(x: i32) -> i32 {
    if (x < 0) {
        return -x;
    }
    x
}
```

**预期 IR：**
```llvm
define void @abs(ptr %ret_ptr, i32 %x) {
entry:
  %tmp.0 = alloca i32
  store i32 %x, ptr %tmp.0
  %tmp.1 = load i32, ptr %tmp.0
  %tmp.2 = icmp slt i32 %tmp.1, 0
  br i1 %tmp.2, label %if_then, label %if_else

if_then:
  %tmp.3 = load i32, ptr %tmp.0
  %tmp.4 = sub i32 0, %tmp.3
  store i32 %tmp.4, ptr %ret_ptr   ; return -x
  br label %return

if_else:
  br label %if_merge

if_merge:
  %tmp.5 = load i32, ptr %tmp.0
  store i32 %tmp.5, ptr %ret_ptr   ; 隐式返回 x
  br label %return

return:
  ret void
}
```

---

## 总结

本方案通过统一的返回约定，将所有函数改为返回 void，使用第一个参数作为返回缓冲区指针：

1. **函数签名转换**：所有函数返回 void，第一个参数为 ret_ptr
2. **UnitType 处理**：当作 i8 处理，保持统一性
3. **返回块设计**：使用统一的 epilogue 块处理所有返回路径
4. **调用点修改**：调用方分配返回缓冲区，传入指针，调用后读取值

实现严格遵循现有代码风格和架构，与 `LLVM_IR_Generation_Guide.md` 中描述的设计原则保持一致。
