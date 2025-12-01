# visitPathExpr 实现文档

本文档说明了 AST 降低（AST Lowering）阶段中 `visitPathExpr` 的实现细节，以及如何处理赋值表达式（如 `x = 1`）。

## 目录

1. [概述](#概述)
2. [visitPathExpr 实现](#visitpathexpr-实现)
3. [赋值表达式处理](#赋值表达式处理)
4. [左值（LValue）与右值（RValue）](#左值lvalue与右值rvalue)
5. [示例与边界情况](#示例与边界情况)
6. [设计决策](#设计决策)

---

## 概述

`visitPathExpr` 是 `ASTLower` 类中负责处理路径表达式的方法。路径表达式用于引用变量、常量、函数等符号。

### 主要职责

1. **符号解析**：根据已绑定的符号（`PathExprNode.symbol`）确定如何生成 IR
2. **值加载**：对于变量引用，生成 `load` 指令获取值
3. **类型区分**：区分标量类型和聚合类型（struct/array），采用不同的处理策略
4. **常量处理**：从全局常量加载值

---

## visitPathExpr 实现

### 处理流程

```kotlin
override fun visitPathExpr(node: PathExprNode) {
    when (val symbol = node.symbol) {
        is VariableSymbol -> {
            // 变量引用
            val varAddress = symbol.irValue  // alloca 或 argument 的地址
            val varType = getIRType(context, symbol.type)
            
            when (varType) {
                is StructType, is ArrayType -> {
                    // 聚合类型：直接使用地址
                    node.irValue = varAddress
                }
                else -> {
                    // 标量类型：生成 load 指令
                    val loadInst = builder.createLoad(varType, varAddress)
                    node.irValue = loadInst
                }
            }
        }
        is ConstantSymbol -> {
            // 常量引用：从全局常量 load
            val constType = getIRType(context, symbol.type)
            val globalVar = module.myGetGlobalVariable(symbol.name)
            val loadInst = builder.createLoad(constType, globalVar)
            node.irValue = loadInst
        }
        is FunctionSymbol -> {
            // 函数引用：用于函数调用，不生成值
            node.irValue = null
        }
        // ...
    }
}
```

### 符号类型处理

| 符号类型 | 处理方式 | 生成的 IR |
|---------|---------|----------|
| `VariableSymbol`（标量） | load 变量值 | `%val = load i32, ptr %var` |
| `VariableSymbol`（聚合） | 返回地址 | 直接使用 `%var` |
| `ConstantSymbol` | load 全局常量 | `%val = load i32, ptr @CONST` |
| `FunctionSymbol` | 不生成值 | - |

---

## 赋值表达式处理

### 对于 `x = 1` 这样的表达式

赋值表达式的处理分为三个步骤：

1. **获取左值地址**：左侧表达式被当作左值处理，直接获取其地址而非加载其值
2. **计算右值**：右侧表达式按常规方式求值
3. **执行存储**：根据类型选择存储策略

### 实现代码

```kotlin
override fun visitAssignExpr(node: AssignExprNode) {
    // 1. 获取左值地址（不 load）
    val dstPtr = getLValueAddress(node.left)
    
    // 2. 获取赋值类型
    val assignType = getIRType(context, node.left.resolvedType)
    
    when (assignType) {
        is StructType -> {
            // 结构体：使用 memcpy
            node.right.accept(this)
            val srcAddr = node.right.irValue
            val sizeFunc = module.myGetFunction("${structName}.size")
            val size = builder.createCall(sizeFunc, emptyList())
            builder.createMemCpy(dstPtr, srcAddr, size, false)
        }
        is ArrayType -> {
            // 数组：使用 memcpy
            node.right.accept(this)
            val srcAddr = node.right.irValue
            val size = getArrayCopySize(assignType)
            builder.createMemCpy(dstPtr, srcAddr, size, false)
        }
        else -> {
            // 标量类型：使用 store
            node.right.accept(this)
            val value = node.right.irValue
            builder.createStore(value, dstPtr)
        }
    }
    
    node.irValue = null  // 赋值表达式返回 unit
}
```

### 生成的 IR 示例

对于 `x = 1`：

```llvm
; 假设 x 是 i32 类型的可变变量
; 在 let 语句中已经分配：
; %x = alloca i32
; store i32 0, ptr %x  ; 初始化

; 对于 x = 1：
store i32 1, ptr %x
```

对于结构体赋值 `point = other_point`：

```llvm
%size = call i32 @Point.size()
call void @llvm.memcpy.p0.p0.i32(ptr %point, ptr %other_point, i32 %size, i1 false)
```

---

## 左值（LValue）与右值（RValue）

### 定义

- **左值（LValue）**：可以出现在赋值运算符左侧的表达式，代表一个可写的内存位置
- **右值（RValue）**：代表一个值，可能是常量或计算结果

### 左值地址获取

`getLValueAddress` 方法用于获取左值表达式的地址：

```kotlin
private fun getLValueAddress(node: ExprNode): Value {
    return when (node) {
        is PathExprNode -> {
            // 变量引用：返回变量的 alloca 地址
            val symbol = node.symbol as VariableSymbol
            symbol.irValue
        }
        is FieldExprNode -> {
            // 字段访问：GEP 到字段地址
            // ...
        }
        is IndexExprNode -> {
            // 数组索引：GEP 到元素地址
            // ...
        }
        is DerefExprNode -> {
            // 解引用：表达式的值就是地址
            node.expr.accept(this)
            node.expr.irValue
        }
        else -> throw IRException("Expression is not a valid lvalue")
    }
}
```

### 支持的左值类型

| 表达式类型 | 示例 | 说明 |
|-----------|-----|------|
| 路径表达式 | `x` | 变量引用 |
| 字段访问 | `point.x` | 结构体字段 |
| 索引表达式 | `arr[i]` | 数组元素 |
| 解引用表达式 | `*ptr` | 指针解引用 |

---

## 示例与边界情况

### 示例 1：简单变量赋值

```rust
let mut x: i32 = 0;
x = 1;  // 赋值
```

处理流程：
1. `x` 被解析为 `VariableSymbol`
2. `getLValueAddress(x)` 返回 `%x`（alloca 地址）
3. 右值 `1` 生成常量 `i32 1`
4. 生成 `store i32 1, ptr %x`

### 示例 2：结构体字段赋值

```rust
struct Point { x: i32, y: i32 }
let mut p: Point = Point { x: 0, y: 0 };
p.x = 10;
```

处理流程：
1. `p.x` 被解析为 `FieldExprNode`
2. 生成 GEP 获取字段地址
3. 右值 `10` 生成常量
4. 生成 store 指令

### 示例 3：复合赋值

```rust
let mut x: i32 = 5;
x += 3;  // 等价于 x = x + 3
```

处理流程：
1. 获取 `x` 的地址
2. Load 当前值
3. 计算右值
4. 执行加法运算
5. Store 结果

### 边界情况

1. **未定义变量**：在语义分析阶段检查，IR 生成阶段假设符号已正确绑定
2. **不可写目标**：语义分析阶段检查 `exprType` 是否为 `MutPlace`
3. **只读字段**：当前语言不支持只读字段，所有字段可写
4. **闭包捕获**：当前不支持闭包

---

## 设计决策

### 1. 聚合类型使用地址而非 load

对于结构体和数组，`visitPathExpr` 返回地址而非 load 值：
- 避免对大型结构体的低效复制
- 允许后续操作直接使用地址进行 memcpy
- 与函数返回值的 ABI 保持一致

### 2. 赋值根据类型选择策略

- **标量类型**：使用 `store` 指令
- **聚合类型**：使用 `memcpy`
  - 结构体大小通过 `StructName.size()` 函数获取
  - 数组大小通过 `元素大小 × 长度` 计算

### 3. 左值地址与右值分离

使用 `getLValueAddress` 方法专门处理左值，与常规的 `visit` 方法区分：
- `visit` 方法产生 rvalue（可能包含 load）
- `getLValueAddress` 只返回地址，不 load

### 4. 符号绑定在语义分析阶段完成

`PathExprNode.symbol` 在语义分析阶段绑定，IR 生成阶段直接使用：
- 简化 IR 生成逻辑
- 确保所有类型检查已完成
- 支持复杂的符号解析（如 `Struct::constant`）

---

## 相关文件

- `src/main/kotlin/ir/astLower.kt` - AST 降级实现
- `src/main/kotlin/ast/scope.kt` - 符号定义
- `src/main/kotlin/ast/astNode.kt` - AST 节点定义
- `docs/LLVM_IR_Generation_Guide.md` - LLVM IR 生成指南
