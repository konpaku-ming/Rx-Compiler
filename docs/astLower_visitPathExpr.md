# visitPathExpr 实现思路文档

## 1. 目标与背景

### 1.1 PathExpr 简介

`PathExprNode` 是 AST（抽象语法树）中表示路径表达式的节点。路径表达式用于引用变量、常量、函数、结构体类型、关联项（associated items）等符号。

在 Rx-Compiler 中，`PathExprNode` 的结构如下：

```kotlin
data class PathExprNode(
    val first: PathSegment,   // 第一段路径（标识符/self/Self）
    val second: PathSegment?  // 可选的第二段路径（用于 Type::item 形式）
) : ExprWithoutBlockNode() {
    var symbol: Symbol? = null // 对应的Symbol，在语义分析阶段绑定
}
```

### 1.2 下放（Lowering）目标

`visitPathExpr` 方法的目标是将 AST 中的路径表达式下放（lower）到 LLVM IR 层面：

- **变量引用**：生成 `load` 指令从栈上加载变量值
- **常量引用**：从全局常量中加载值
- **函数引用**：在当前实现中不产生值（函数调用由 `visitCallExpr` 处理）
- **结构体/类型引用**：不产生值（结构体构造由 `visitStructExpr` 处理）

## 2. 数据结构依赖

### 2.1 输入数据结构

| 字段 | 类型 | 说明 |
|------|------|------|
| `node.first` | `PathSegment` | 路径的第一段，包含标识符 Token |
| `node.second` | `PathSegment?` | 可选的第二段（用于 `Type::item` 形式） |
| `node.symbol` | `Symbol?` | 语义分析阶段绑定的符号（变量或常量） |
| `node.scopePosition` | `Scope?` | 节点所在的作用域 |
| `node.resolvedType` | `ResolvedType` | 表达式的解析类型 |

### 2.2 输出数据结构

| 字段 | 类型 | 说明 |
|------|------|------|
| `node.irValue` | `Value?` | 生成的 LLVM IR 值（标量为值，聚合类型为指针） |

### 2.3 依赖的符号类型

| 符号类型 | 处理方式 | IR 产出 |
|----------|----------|---------|
| `VariableSymbol` | load 变量值 | 标量值或聚合类型指针 |
| `ConstantSymbol` | load 全局常量 | 常量值 |
| `FunctionSymbol` | 不产生值 | `null` |
| `StructSymbol` | 不产生值 | `null` |
| `null` | 未绑定符号 | `null` |

## 3. 算法步骤

### 3.1 流程图

```
visitPathExpr(node)
    │
    ├── 设置当前作用域
    │
    ├── 获取 node.symbol
    │
    ├── switch symbol 类型:
    │   │
    │   ├── VariableSymbol:
    │   │   ├── 获取变量的 alloca 地址 (irValue)
    │   │   ├── 获取变量的 IR 类型
    │   │   └── 根据类型处理:
    │   │       ├── 聚合类型 (Struct/Array): 返回地址
    │   │       └── 标量类型: load 并返回值
    │   │
    │   ├── ConstantSymbol:
    │   │   ├── 从 module 获取全局变量
    │   │   └── load 常量值
    │   │
    │   ├── FunctionSymbol:
    │   │   └── irValue = null
    │   │
    │   ├── StructSymbol:
    │   │   └── irValue = null
    │   │
    │   └── null / 其他:
    │       └── irValue = null
    │
    └── 还原作用域
```

### 3.2 核心实现代码

```kotlin
override fun visitPathExpr(node: PathExprNode) {
    val previousScope = scopeTree.currentScope
    scopeTree.currentScope = node.scopePosition!!

    val symbol = node.symbol
    when (symbol) {
        is VariableSymbol -> {
            val varAddr = symbol.irValue
                ?: throw IRException("Variable '${symbol.name}' has no IR value")
            val varType = getIRType(context, symbol.type)
            when (varType) {
                is StructType, is ArrayType -> {
                    // 聚合类型返回地址
                    node.irValue = varAddr
                }
                else -> {
                    // 标量类型 load 出值
                    node.irValue = builder.createLoad(varType, varAddr)
                }
            }
        }
        is ConstantSymbol -> {
            val globalVar = module.myGetGlobalVariable(symbol.name)
                ?: throw IRException("Constant not found")
            val constType = getIRType(context, symbol.type)
            node.irValue = builder.createLoad(constType, globalVar)
        }
        is FunctionSymbol, is StructSymbol, null -> {
            node.irValue = null
        }
        else -> {
            node.irValue = null
        }
    }

    scopeTree.currentScope = previousScope
}
```

## 4. 决议规则（Disambiguation Rules）

在 Rx-Compiler 中，路径解析在语义分析阶段（`FifthVisitor`）完成，遵循以下规则：

### 4.1 单段路径解析

1. **变量/常量/函数优先**：查找当前作用域及父作用域
2. **结构体/枚举类型**：如果标识符是类型名

### 4.2 双段路径解析

对于 `Type::item` 形式的路径：

1. 第一段必须是结构体/枚举/Self
2. 第二段查找类型的关联项：
   - 关联常量 (`constants`)
   - 关联函数 (`functions`)
   - 方法 (`methods`)

### 4.3 特殊情况

| 情况 | 处理 |
|------|------|
| `self` | 查找当前 impl/trait 作用域中的 self 变量 |
| `Self` | 必须有第二段，查找关联项 |

## 5. 错误处理策略

### 5.1 运行时错误

| 错误场景 | 错误类型 | 错误消息 |
|----------|----------|----------|
| 变量未分配 IR 值 | `IRException` | "Variable 'name' has no IR value" |
| 全局常量未定义 | `IRException` | "Constant 'name' is not defined as global variable" |

### 5.2 语义阶段错误（由 FifthVisitor 处理）

| 错误场景 | 错误类型 |
|----------|----------|
| 标识符未定义 | `SemanticException` |
| 路径无法解析 | `SemanticException` |
| 类型无关联项 | `SemanticException` |

## 6. 边界/特殊情况

### 6.1 聚合类型处理

对于结构体和数组类型的变量，`visitPathExpr` 返回指针而非值：

```kotlin
when (varType) {
    is StructType, is ArrayType -> {
        // 返回地址，不做 load
        node.irValue = varAddr
    }
    else -> {
        // 标量类型 load 出值
        node.irValue = builder.createLoad(varType, varAddr)
    }
}
```

这遵循 ABI 约定：
- 结构体和数组以指针传递
- 复制操作使用 `memcpy`

### 6.2 函数和结构体引用

函数和结构体引用在表达式位置不产生值：

```kotlin
is FunctionSymbol -> {
    // 函数调用由 visitCallExpr 处理
    node.irValue = null
}
is StructSymbol -> {
    // 结构体构造由 visitStructExpr 处理
    node.irValue = null
}
```

### 6.3 未绑定符号

当 `node.symbol` 为 `null` 时（未在语义分析阶段绑定），`irValue` 保持 `null`：

```kotlin
null -> {
    // 可能是 FunctionSymbol/StructSymbol 等情况
    // 这些在 FifthVisitor 中不绑定到 node.symbol
    node.irValue = null
}
```

## 7. 示例

### 7.1 变量引用

**源代码：**
```rust
let mut x: i32 = 42;
let y: i32 = x + 1;  // x 是 PathExpr
```

**IR 生成：**
```llvm
%x = alloca i32
store i32 42, ptr %x
%1 = load i32, ptr %x      ; visitPathExpr(x) 生成这条 load
%2 = add i32 %1, 1
```

### 7.2 常量引用

**源代码：**
```rust
const MAX: i32 = 100;
let x: i32 = MAX;
```

**IR 生成：**
```llvm
@MAX = constant i32 100
%x = alloca i32
%1 = load i32, ptr @MAX    ; visitPathExpr(MAX) 生成这条 load
store i32 %1, ptr %x
```

### 7.3 结构体变量引用

**源代码：**
```rust
struct Point { x: i32, y: i32 }
let p: Point = Point { x: 1, y: 2 };
let q: Point = p;  // p 是 PathExpr，返回指针
```

**IR 生成：**
```llvm
%p = alloca %Point
; ... 初始化 p ...
%q = alloca %Point
; visitPathExpr(p) 返回 %p 的地址
; 然后使用 memcpy 复制
```

## 8. 测试用例建议

### 8.1 正例（应通过）

| 测试场景 | 示例代码 |
|----------|----------|
| 简单变量引用 | `let x: i32 = y;` |
| 可变变量引用 | `let mut x: i32 = 1; x = x + 1;` |
| 常量引用 | `const C: i32 = 1; let x: i32 = C;` |
| 结构体变量引用 | `let s: S = t;` |
| 数组变量引用 | `let a: [i32; 3] = b;` |
| 函数调用中的路径 | `foo(x);` |

### 8.2 反例（应失败）

| 测试场景 | 示例代码 | 预期错误 |
|----------|----------|----------|
| 未定义变量 | `let x: i32 = undefined_var;` | SemanticException |
| 未定义常量 | `let x: i32 = UNDEFINED_CONST;` | SemanticException |

## 9. 性能注意事项

### 9.1 避免重复 load

当同一变量在表达式中多次出现时，编译器可能生成多条 load 指令。优化器（如 LLVM 的 `mem2reg` pass）可以消除这些冗余。

### 9.2 作用域切换

每次访问都需要保存和恢复作用域状态：

```kotlin
val previousScope = scopeTree.currentScope
scopeTree.currentScope = node.scopePosition!!
// ... 处理 ...
scopeTree.currentScope = previousScope
```

这是 O(1) 操作，不影响性能。

## 10. 与后端组件的契约

### 10.1 符号表契约

- `VariableSymbol.irValue`: 必须在 `visitLetStmt` 中设置为 alloca 结果
- `ConstantSymbol`: 必须在 `StructDefiner` 阶段注册为全局变量

### 10.2 IR 生成契约

| 输出类型 | `irValue` 值 |
|----------|--------------|
| 标量变量 | load 后的 `Value` |
| 聚合变量 | alloca 的 `Value`（指针） |
| 常量 | load 后的 `Value` |
| 函数/结构体 | `null` |

### 10.3 诊断格式

错误消息格式：
```
IRException: "Variable 'varName' has no IR value (alloca not created)"
IRException: "Constant 'constName' is not defined as global variable"
```

## 11. 扩展点

### 11.1 函数指针支持

若需要支持函数作为一等公民（first-class functions），可扩展：

```kotlin
is FunctionSymbol -> {
    val func = module.myGetFunction(symbol.name)
    node.irValue = func  // 函数指针
}
```

### 11.2 泛型支持

当前实现不支持泛型。未来扩展时需要：

1. 在 `PathExprNode` 中添加类型参数字段
2. 实例化泛型类型
3. 生成单态化（monomorphized）的 IR

### 11.3 模块路径支持

当前只支持最多两段路径。未来扩展多模块时需要：

1. 扩展 `PathExprNode` 支持多段路径
2. 实现模块查找逻辑
3. 处理可见性检查
