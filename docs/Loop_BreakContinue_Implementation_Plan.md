# 循环与 break/continue 的 IR 生成实现计划

本文档描述 `visitPredicateLoopExpr`、`visitInfiniteLoopExpr` 以及 `breakExpr`、`continueExpr` 的 IR 生成实现方案。

---

## 目录

1. [现有设计分析](#现有设计分析)
2. [循环控制流结构](#循环控制流结构)
3. [实现方案概述](#实现方案概述)
4. [LoopContext 设计](#loopcontext-设计)
5. [visitPredicateLoopExpr 实现](#visitpredicateloopexpr-实现)
6. [visitInfiniteLoopExpr 实现](#visitinfiniteloopexpr-实现)
7. [visitBreakExpr 实现](#visitbreakexpr-实现)
8. [visitContinueExpr 实现](#visitcontinueexpr-实现)
9. [带值的 break 支持](#带值的-break-支持)
10. [实现顺序与步骤](#实现顺序与步骤)

---

## 现有设计分析

### AST 节点结构

根据 `astNode.kt` 中的定义：

```kotlin
// while 循环 (predicate loop)
data class PredicateLoopExprNode(
    val condition: ExprNode,
    val block: BlockExprNode
) : LoopExprNode()

// 无限循环 (infinite loop)
data class InfiniteLoopExprNode(
    val block: BlockExprNode
) : LoopExprNode()

// break 表达式（可以带值）
data class BreakExprNode(val value: ExprNode?) : ExprWithoutBlockNode()

// continue 表达式
object ContinueExprNode : ExprWithoutBlockNode()
```

### 作用域结构

根据 `scope.kt` 中的 `LoopScope` 定义：

```kotlin
data class LoopScope(
    override val parent: Scope? = null,
    override val children: MutableList<Scope> = mutableListOf(),
    var breakType: ResolvedType,  // 记录 break 表达式的类型
) : Scope()
```

关键点：
- `LoopScope` 的 `breakType` 用于记录循环的返回类型
- `PredicateLoopExpr` (while) 的 `breakType` 固定为 `UnitResolvedType`
- `InfiniteLoopExpr` (loop) 的 `breakType` 由 break 表达式的值类型决定

### 语义分析中的循环类型推导

根据 `semantic.kt` 中的 `ThirdVisitor` 实现：

```kotlin
// PredicateLoopExpr (while) - 类型固定为 Unit
override fun visitPredicateLoopExpr(node: PredicateLoopExprNode) {
    node.condition.accept(this)
    visitBlockExpr(node.block, createScope = false)
    node.exprType = ExprType.Value
    if (node.condition.resolvedType != PrimitiveResolvedType("bool")) {
        throw SemanticException("condition is not bool type")
    }
    node.resolvedType = (node.block.scopePosition as LoopScope).breakType
}

// InfiniteLoopExpr (loop) - 类型由 break 决定
override fun visitInfiniteLoopExpr(node: InfiniteLoopExprNode) {
    visitBlockExpr(node.block, createScope = false)
    node.exprType = ExprType.Value
    node.resolvedType = (node.block.scopePosition as LoopScope).breakType
    if (node.resolvedType is UnknownResolvedType) {
        // 不含 break，只含 return —— 永不退出的循环
        node.resolvedType = NeverResolvedType
        node.isBottom = true
    }
}
```

### 现有 IR 生成框架（astLower.kt）

当前 `ASTLower` 中的循环实现仅做了框架：

```kotlin
override fun visitPredicateLoopExpr(node: PredicateLoopExprNode) {
    val previousScope = scopeTree.currentScope
    scopeTree.currentScope = node.scopePosition!!
    node.condition.accept(this)
    visitBlockExpr(node.block, createScope = false)
    scopeTree.currentScope = previousScope
}

override fun visitInfiniteLoopExpr(node: InfiniteLoopExprNode) {
    val previousScope = scopeTree.currentScope
    scopeTree.currentScope = node.scopePosition!!
    visitBlockExpr(node.block, createScope = false)
    scopeTree.currentScope = previousScope
}

override fun visitBreakExpr(node: BreakExprNode) {
    val previousScope = scopeTree.currentScope
    scopeTree.currentScope = node.scopePosition!!
    node.value?.accept(this)
    scopeTree.currentScope = previousScope
}

override fun visitContinueExpr(node: ContinueExprNode) {
    val previousScope = scopeTree.currentScope
    scopeTree.currentScope = node.scopePosition!!
    // nothing to do
    scopeTree.currentScope = previousScope
}
```

---

## 循环控制流结构

### While 循环 (PredicateLoopExpr) 的 CFG

```
                  ┌─────────────┐
                  │   entry     │
                  └──────┬──────┘
                         │
                         ▼
               ┌─────────────────┐
        ┌──────│   while.cond   │◄────┐
        │      └────────┬────────┘    │
        │               │             │
        │     ┌───false─┴─true───┐    │
        │     │                  │    │
        │     ▼                  ▼    │
        │ ┌─────────┐    ┌──────────┐ │
        │ │ after   │    │ body     │─┘ (continue 跳转到 cond)
        │ └─────────┘    └──────────┘
        │                      │
        │                      │ (break 跳转到 after)
        └──────────────────────┘
```

关键基本块：
1. `while.cond` - 条件判断块
2. `while.body` - 循环体块
3. `while.after` - 循环后续块

控制流：
- 条件为 true → 进入 body
- 条件为 false → 跳转到 after
- `continue` → 跳转到 cond
- `break` → 跳转到 after

### Infinite Loop (InfiniteLoopExpr) 的 CFG

```
              ┌─────────────┐
              │   entry     │
              └──────┬──────┘
                     │
                     ▼
            ┌──────────────┐
        ┌───│  loop.body   │◄────┐
        │   └──────┬───────┘     │
        │          │             │
        │          │ (无条件跳回) │
        │          └─────────────┘
        │            (continue 也跳转到 body)
        │
        │ (break 跳转到 after)
        ▼
    ┌─────────┐
    │  after  │
    └─────────┘
```

关键基本块：
1. `loop.body` - 循环体块
2. `loop.after` - 循环后续块

控制流：
- 循环体末尾无条件跳转回 body
- `continue` → 跳转到 body
- `break` → 跳转到 after

---

## 实现方案概述

### 核心思路

1. **使用栈结构管理循环上下文**
   - 嵌套循环时，break/continue 需要知道跳转到哪个循环的哪个基本块
   - 使用栈 (`loopContextStack`) 来管理当前活跃的循环上下文

2. **每个循环维护自己的基本块引用**
   - `condBB` - 条件检查块（仅 while 循环有）
   - `bodyBB` - 循环体块
   - `afterBB` - 循环后续块

3. **break 值的处理**
   - 使用 PHI 节点收集来自不同 break 点的值
   - 在 `afterBB` 开始处创建 PHI 节点

### 数据结构设计

```kotlin
/**
 * 循环上下文，记录一个循环的控制流信息
 */
data class LoopContext(
    val condBB: BasicBlock?,     // 条件块（while 循环有，loop 没有）
    val afterBB: BasicBlock,     // 循环结束后的块
    var breakPhi: PHINode?,      // break 值的 PHI 节点（如果循环有返回值）
    val breakType: ResolvedType  // break 表达式的类型
)

// 在 ASTLower 类中添加
private val loopContextStack = ArrayDeque<LoopContext>()
```

---

## LoopContext 设计

### 为什么需要 LoopContext？

考虑以下嵌套循环代码：

```rust
fn test() -> i32 {
    let mut result: i32 = 0;
    loop {                          // 外层循环
        let mut i: u32 = 0u32;
        while (i < 5) {             // 内层循环
            if (some_condition) {
                continue;           // 继续内层 while
            }
            if (other_condition) {
                break;              // 跳出内层 while
            }
            i += 1;
        }
        if (done) {
            break result;           // 跳出外层 loop，带返回值
        }
    }
}
```

- `continue` 和 `break` 需要知道当前处于哪个循环
- 外层 loop 的 break 带值，内层 while 的 break 不带值
- 使用栈结构自然地处理嵌套关系

### LoopContext 详细设计

```kotlin
/**
 * 循环上下文类，管理循环的控制流信息
 */
data class LoopContext(
    /**
     * 条件检查块（仅 while 循环有）
     * - while 循环：continue 跳转到此块
     * - loop 循环：为 null，continue 跳转到 bodyBB
     */
    val condBB: BasicBlock?,
    
    /**
     * 循环后续块
     * - break 跳转到此块
     */
    val afterBB: BasicBlock,
    
    /**
     * break 值的 PHI 节点（如果循环有返回值）
     * - 当 breakType 不是 UnitResolvedType 或 NeverResolvedType 时需要
     * - 收集来自不同 break 点的值
     */
    var breakPhi: PHINode?,
    
    /**
     * break 表达式的类型
     * - 用于确定是否需要 PHI 节点
     */
    val breakType: ResolvedType
) {
    /**
     * 获取 continue 应该跳转到的块
     * - while 循环：跳转到条件检查块
     * - loop 循环：跳转到循环体块（需要外部传入）
     */
    fun getContinueTarget(bodyBB: BasicBlock): BasicBlock {
        return condBB ?: bodyBB
    }
}
```

### 在 ASTLower 中添加循环上下文栈

```kotlin
class ASTLower(...) : ASTVisitor {
    // 循环上下文栈，用于处理嵌套循环中的 break/continue
    private val loopContextStack = ArrayDeque<LoopContext>()
    
    /**
     * 进入循环时调用
     */
    private fun pushLoopContext(context: LoopContext) {
        loopContextStack.addLast(context)
    }
    
    /**
     * 离开循环时调用
     */
    private fun popLoopContext(): LoopContext {
        return loopContextStack.removeLast()
    }
    
    /**
     * 获取当前循环上下文（最内层循环）
     */
    private fun currentLoopContext(): LoopContext? {
        return loopContextStack.lastOrNull()
    }
}
```

---

## visitPredicateLoopExpr 实现

### 实现步骤

```kotlin
override fun visitPredicateLoopExpr(node: PredicateLoopExprNode) {
    val previousScope = scopeTree.currentScope
    scopeTree.currentScope = node.scopePosition!!
    
    // 1. 获取当前函数
    val currentFunc = builder.myGetInsertFunction()
        ?: throw IRException("PredicateLoopExpr not in a function")
    
    // 2. 创建基本块
    val condBB = currentFunc.createBasicBlock("while_cond")
    val bodyBB = currentFunc.createBasicBlock("while_body")
    val afterBB = currentFunc.createBasicBlock("while_after")
    
    // 3. 获取循环的返回类型
    val loopScope = node.block.scopePosition as? LoopScope
    val breakType = loopScope?.breakType ?: UnitResolvedType
    
    // 4. 创建循环上下文并入栈
    val loopContext = LoopContext(
        condBB = condBB,
        afterBB = afterBB,
        breakPhi = null,  // while 循环返回 Unit，不需要 PHI
        breakType = breakType
    )
    pushLoopContext(loopContext)
    
    // 5. 从当前块跳转到条件检查块
    builder.createBr(condBB)
    
    // 6. 生成条件检查块
    builder.setInsertPoint(condBB)
    node.condition.accept(this)
    val condValue = node.condition.irValue
        ?: throw IRException("Condition has no IR value")
    builder.createCondBr(condValue, bodyBB, afterBB)
    
    // 7. 生成循环体块
    builder.setInsertPoint(bodyBB)
    visitBlockExpr(node.block, createScope = false)
    
    // 如果循环体没有以终结指令结束，跳转回条件检查块
    val currentBB = builder.myGetInsertBlock()
    if (currentBB != null && !currentBB.isTerminated()) {
        builder.createBr(condBB)
    }
    
    // 8. 出栈循环上下文
    popLoopContext()
    
    // 9. 设置插入点到 after 块
    builder.setInsertPoint(afterBB)
    
    // 10. 设置循环表达式的 IR 值
    // while 循环返回 Unit，irValue 为 null
    node.irValue = null
    node.irAddr = null
    
    scopeTree.currentScope = previousScope
}
```

### 生成的 IR 示例

源代码：
```rust
while (i < 10) {
    i += 1;
}
```

生成的 IR：
```llvm
  br label %while_cond

while_cond:
  %i_val = load i32, ptr %i
  %cmp = icmp slt i32 %i_val, 10
  br i1 %cmp, label %while_body, label %while_after

while_body:
  %i_val2 = load i32, ptr %i
  %add = add i32 %i_val2, 1
  store i32 %add, ptr %i
  br label %while_cond

while_after:
  ; 继续后续代码
```

---

## visitInfiniteLoopExpr 实现

### 实现步骤

```kotlin
override fun visitInfiniteLoopExpr(node: InfiniteLoopExprNode) {
    val previousScope = scopeTree.currentScope
    scopeTree.currentScope = node.scopePosition!!
    
    // 1. 获取当前函数
    val currentFunc = builder.myGetInsertFunction()
        ?: throw IRException("InfiniteLoopExpr not in a function")
    
    // 2. 创建基本块
    val bodyBB = currentFunc.createBasicBlock("loop_body")
    val afterBB = currentFunc.createBasicBlock("loop_after")
    
    // 3. 获取循环的返回类型
    val loopScope = node.block.scopePosition as? LoopScope
    val breakType = loopScope?.breakType ?: UnknownResolvedType
    
    // 4. 判断是否需要 PHI 节点
    val needsPhi = breakType !is UnitResolvedType 
                   && breakType !is UnknownResolvedType 
                   && breakType !is NeverResolvedType
    
    // 5. 创建循环上下文并入栈
    val loopContext = LoopContext(
        condBB = null,  // loop 没有条件块
        afterBB = afterBB,
        breakPhi = null,  // 稍后在 afterBB 中创建
        breakType = breakType
    )
    pushLoopContext(loopContext)
    
    // 6. 从当前块跳转到循环体块
    builder.createBr(bodyBB)
    
    // 7. 生成循环体块
    builder.setInsertPoint(bodyBB)
    visitBlockExpr(node.block, createScope = false)
    
    // 如果循环体没有以终结指令结束，跳转回循环体开始
    val currentBB = builder.myGetInsertBlock()
    if (currentBB != null && !currentBB.isTerminated()) {
        builder.createBr(bodyBB)
    }
    
    // 8. 设置插入点到 after 块并创建 PHI（如果需要）
    builder.setInsertPoint(afterBB)
    
    if (needsPhi) {
        val phiType = getIRType(context, breakType)
        val phi = builder.createPHI(phiType)
        loopContext.breakPhi = phi
        node.irValue = phi
    } else {
        node.irValue = null
    }
    node.irAddr = null
    
    // 9. 出栈循环上下文
    popLoopContext()
    
    scopeTree.currentScope = previousScope
}
```

### 问题：PHI 节点的 incoming 值

**问题**：PHI 节点需要在 `afterBB` 开头创建，但 break 表达式在循环体中执行，此时 PHI 已经创建但还没有添加 incoming 值。

**解决方案**：在 `visitBreakExpr` 中动态添加 incoming 值到已创建的 PHI 节点。

### 改进方案：延迟 PHI 创建

更好的方案是先收集所有 break 的值，最后统一创建 PHI：

```kotlin
data class LoopContext(
    val condBB: BasicBlock?,
    val afterBB: BasicBlock,
    var breakPhi: PHINode?,
    val breakType: ResolvedType,
    // 新增：收集 break 的值和来源块
    val breakIncomings: MutableList<Pair<Value, BasicBlock>> = mutableListOf()
)
```

在循环结束后创建 PHI：

```kotlin
// 在 visitInfiniteLoopExpr 的最后
if (needsPhi && loopContext.breakIncomings.isNotEmpty()) {
    builder.setInsertPoint(afterBB)
    val phi = builder.createPHI(getIRType(context, breakType))
    for ((value, block) in loopContext.breakIncomings) {
        phi.addIncoming(value, block)
    }
    node.irValue = phi
}
```

---

## visitBreakExpr 实现

### 实现步骤

```kotlin
override fun visitBreakExpr(node: BreakExprNode) {
    val previousScope = scopeTree.currentScope
    scopeTree.currentScope = node.scopePosition!!
    
    // 1. 获取当前循环上下文
    val loopContext = currentLoopContext()
        ?: throw IRException("break outside loop")
    
    // 2. 如果有值，先求值
    val breakValue: Value? = if (node.value != null) {
        node.value.accept(this)
        node.value.irValue
    } else {
        null
    }
    
    // 3. 如果循环需要收集 break 值，记录当前值和来源块
    if (breakValue != null) {
        val currentBB = builder.myGetInsertBlock()
            ?: throw IRException("No current basic block")
        loopContext.breakIncomings.add(Pair(breakValue, currentBB))
    }
    
    // 4. 跳转到循环的 after 块
    builder.createBr(loopContext.afterBB)
    
    // 5. break 表达式的类型是 Never，不会产生值
    node.irValue = null
    node.irAddr = null
    
    scopeTree.currentScope = previousScope
}
```

### 生成的 IR 示例

源代码（带值的 break）：
```rust
fn test() -> i32 {
    loop {
        if (condition) {
            break 42;
        }
    }
}
```

生成的 IR：
```llvm
  br label %loop_body

loop_body:
  %cond = ... ; 条件求值
  br i1 %cond, label %break_block, label %continue_block

break_block:
  br label %loop_after

continue_block:
  br label %loop_body

loop_after:
  %result = phi i32 [ 42, %break_block ]
  ret i32 %result
```

---

## visitContinueExpr 实现

### 实现步骤

```kotlin
override fun visitContinueExpr(node: ContinueExprNode) {
    val previousScope = scopeTree.currentScope
    scopeTree.currentScope = node.scopePosition!!
    
    // 1. 获取当前循环上下文
    val loopContext = currentLoopContext()
        ?: throw IRException("continue outside loop")
    
    // 2. 确定跳转目标
    // - while 循环：跳转到条件检查块
    // - loop 循环：跳转到循环体块开始（但这需要额外记录 bodyBB）
    val targetBB = loopContext.condBB
        ?: throw IRException("continue target not found")
    // 注意：对于 loop 循环，需要跳转到 bodyBB，这需要改进 LoopContext
    
    // 3. 跳转到目标块
    builder.createBr(targetBB)
    
    // 4. continue 表达式的类型是 Never
    node.irValue = null
    node.irAddr = null
    
    scopeTree.currentScope = previousScope
}
```

### 改进：支持 loop 中的 continue

对于 `loop` 循环，`continue` 应该跳转到循环体开始。需要在 `LoopContext` 中记录 `bodyBB`：

```kotlin
data class LoopContext(
    val condBB: BasicBlock?,     // while 的条件块
    val bodyBB: BasicBlock,      // 循环体块（新增）
    val afterBB: BasicBlock,     // 循环后续块
    var breakPhi: PHINode?,
    val breakType: ResolvedType,
    val breakIncomings: MutableList<Pair<Value, BasicBlock>> = mutableListOf()
) {
    /**
     * 获取 continue 的跳转目标
     * - while: 跳转到 condBB
     * - loop: 跳转到 bodyBB
     */
    fun getContinueTarget(): BasicBlock {
        return condBB ?: bodyBB
    }
}
```

更新后的 `visitContinueExpr`：

```kotlin
override fun visitContinueExpr(node: ContinueExprNode) {
    val previousScope = scopeTree.currentScope
    scopeTree.currentScope = node.scopePosition!!
    
    val loopContext = currentLoopContext()
        ?: throw IRException("continue outside loop")
    
    builder.createBr(loopContext.getContinueTarget())
    
    node.irValue = null
    node.irAddr = null
    
    scopeTree.currentScope = previousScope
}
```

---

## 带值的 break 支持

### 语义说明

Rust 风格的 `loop` 可以通过 `break value` 返回值：

```rust
let result = loop {
    if (condition) {
        break 42;  // 返回 42
    }
};
```

### 类型推导（已在语义分析中实现）

根据 `semantic.kt` 中的实现：

```kotlin
override fun visitBreakExpr(node: BreakExprNode) {
    node.value?.accept(this)
    
    val breakType = if (node.value == null) {
        UnitResolvedType
    } else {
        node.value.resolvedType
    }
    
    // 查找最近的循环作用域
    var targetScope = scopeTree.currentScope
    while (targetScope.kind != ScopeKind.Loop && targetScope.parent != null) {
        targetScope = targetScope.parent!!
    }
    
    if (targetScope is LoopScope) {
        if (targetScope.breakType is UnknownResolvedType) {
            targetScope.breakType = breakType
        } else {
            targetScope.breakType = typeUnify(targetScope.breakType, breakType)
        }
    }
    
    node.resolvedType = NeverResolvedType
}
```

### IR 生成实现

关键点：
1. 多个 break 可能带不同的值，需要使用 PHI 节点合并
2. 每个 break 跳转到 after 块前，需要记录值和来源块
3. 在 after 块开头创建 PHI 节点

```kotlin
// 示例：处理带值的 break
override fun visitBreakExpr(node: BreakExprNode) {
    val loopContext = currentLoopContext()
        ?: throw IRException("break outside loop")
    
    if (node.value != null) {
        node.value.accept(this)
        val breakValue = node.value.irValue
            ?: throw IRException("break value has no IR value")
        
        val currentBB = builder.myGetInsertBlock()!!
        loopContext.breakIncomings.add(Pair(breakValue, currentBB))
    }
    
    builder.createBr(loopContext.afterBB)
}
```

---

## 实现顺序与步骤

### 第一步：添加 LoopContext 数据结构

在 `ASTLower` 类中添加：

```kotlin
// 循环上下文数据类
data class LoopContext(
    val condBB: BasicBlock?,
    val bodyBB: BasicBlock,
    val afterBB: BasicBlock,
    var breakPhi: PHINode?,
    val breakType: ResolvedType,
    val breakIncomings: MutableList<Pair<Value, BasicBlock>> = mutableListOf()
) {
    fun getContinueTarget(): BasicBlock = condBB ?: bodyBB
}

// 循环上下文栈
private val loopContextStack = ArrayDeque<LoopContext>()

private fun pushLoopContext(context: LoopContext) {
    loopContextStack.addLast(context)
}

private fun popLoopContext(): LoopContext {
    return loopContextStack.removeLast()
}

private fun currentLoopContext(): LoopContext? {
    return loopContextStack.lastOrNull()
}
```

### 第二步：实现 visitPredicateLoopExpr

实现 while 循环的 IR 生成，包括：
- 创建 cond/body/after 三个基本块
- 生成条件检查代码
- 生成循环体代码
- 处理控制流跳转

### 第三步：实现 visitInfiniteLoopExpr

实现 loop 循环的 IR 生成，包括：
- 创建 body/after 两个基本块
- 生成循环体代码
- 处理带值 break 的 PHI 节点

### 第四步：实现 visitBreakExpr

实现 break 的 IR 生成，包括：
- 求值 break 的值（如果有）
- 记录到 PHI 的 incoming 列表
- 跳转到 after 块

### 第五步：实现 visitContinueExpr

实现 continue 的 IR 生成，包括：
- 跳转到条件块（while）或循环体块（loop）

### 第六步：测试验证

使用现有测试用例验证实现：
- `tests/pass/loop1.rx` - while 循环 + continue
- `tests/pass/loop2.rx` - 嵌套 while 循环
- `tests/pass/loop3.rx` - while 循环 + 数组
- `tests/pass/loop4.rx` - 嵌套 loop + break/continue
- `tests/pass/loop5.rx` - while 循环 + 数组

---

## 注意事项

### 1. 基本块终结检查

每个基本块必须以终结指令结束。在生成循环体后，需要检查当前块是否已终结：

```kotlin
val currentBB = builder.myGetInsertBlock()
if (currentBB != null && !currentBB.isTerminated()) {
    builder.createBr(targetBB)
}
```

### 2. 嵌套循环处理

使用栈结构自然地处理嵌套循环。内层循环的 break/continue 只影响最内层循环。

### 3. 死代码问题

`break` 和 `continue` 后的代码不会执行，但仍会被编译。这是正常的，后续优化阶段会消除死代码。

### 4. PHI 节点的创建时机

PHI 节点必须在基本块的开头（所有其他指令之前）。由于 break 可能来自循环体的任意位置，建议：
1. 先收集所有 break 的值和来源块
2. 循环体处理完成后，再在 after 块创建 PHI

### 5. 与现有代码的兼容性

实现需要确保：
- 不破坏现有的 `visitBlockExpr` 逻辑
- 正确处理 scope 的进入和退出
- 正确设置 `irValue` 和 `irAddr`

---

## 总结

本方案通过引入 `LoopContext` 数据结构和循环上下文栈，实现了：

1. **while 循环** - 标准的条件-体-跳回结构
2. **loop 循环** - 无条件的循环体-跳回结构
3. **break** - 跳转到 after 块，可选带值
4. **continue** - 跳转到条件块（while）或体块（loop）
5. **嵌套循环** - 使用栈结构管理多层循环

实现严格遵循现有代码风格和架构，与 `LLVM_IR_Generation_Guide.md` 中描述的设计原则保持一致。
