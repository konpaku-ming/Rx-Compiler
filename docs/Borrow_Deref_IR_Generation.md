# Borrow 与 Deref 表达式的 IR 生成设计

本文档解释了 `astLower` 中 `visitBorrowExpr` 和 `visitDerefExpr` 的实现思路。

## 背景概念

在 Rx 编译器中，表达式节点有两个重要的 IR 属性：
- `irValue`: 表达式的值（对于标量是实际值，对于聚合类型是指针）
- `irAddr`: 表达式的内存地址（如果表达式是左值）

## visitBorrowExpr 实现

### 语义分析

`BorrowExprNode` 表示借用表达式（`&expr` 或 `&mut expr`）。在语义分析阶段：
- 内部表达式必须是左值（`Place` 或 `MutPlace`）
- 如果是 `&mut`，内部表达式必须是 `MutPlace`
- 结果类型是 `ReferenceResolvedType(inner, isMut)`

### IR 生成策略

```kotlin
override fun visitBorrowExpr(node: BorrowExprNode) {
    // 1. 递归处理内部表达式
    node.expr.accept(this)
    
    // 2. 获取内部表达式的地址
    val innerAddr = node.expr.irAddr
        ?: throw IRException("Cannot borrow a value without an address")
    
    // 3. 借用表达式的值就是内部表达式的地址
    node.irValue = innerAddr
    node.irAddr = null  // 借用表达式本身没有地址
}
```

### 关键点

1. **左值要求**：只有具有地址的表达式才能被借用。语义分析阶段已确保这一点。

2. **地址传递**：借用表达式的 `irValue` 是内部表达式的 `irAddr`。这意味着：
   - 对于 `&x`（x 是变量），结果是 x 的 alloca 地址
   - 对于 `&arr[i]`，结果是数组元素的 GEP 地址
   - 对于 `&s.field`，结果是结构体字段的 GEP 地址

3. **无自身地址**：借用表达式本身是右值，没有地址。要获取借用的地址，需先将其存入变量。

### 示例

```rust
let x: i32 = 42;
let ref_x: &i32 = &x;  // ref_x.irValue = x.irAddr
```

生成的 IR 类似于：
```llvm
%x = alloca i32
store i32 42, ptr %x
%ref_x = alloca ptr
store ptr %x, ptr %ref_x  ; 存储 x 的地址
```

## visitDerefExpr 实现

### 语义分析

`DerefExprNode` 表示解引用表达式（`*expr`）。在语义分析阶段：
- 内部表达式的类型必须是 `ReferenceResolvedType`
- 结果类型是引用的内部类型
- 如果引用是 `&mut T`，解引用结果是 `MutPlace`

### IR 生成策略

```kotlin
override fun visitDerefExpr(node: DerefExprNode) {
    // 1. 递归处理内部表达式（获取指针值）
    node.expr.accept(this)
    
    // 2. 获取指针值
    val ptrValue = node.expr.irValue
        ?: throw IRException("Operand of dereference has no IR value")
    
    // 3. 根据目标类型决定处理方式
    val derefType = getIRType(context, node.resolvedType)
    
    if (derefType.isAggregate()) {
        // 聚合类型：直接使用指针
        node.irValue = ptrValue
        node.irAddr = ptrValue
    } else {
        // 标量类型：从指针 load 值
        val loadedValue = builder.createLoad(derefType, ptrValue)
        node.irValue = loadedValue
        node.irAddr = ptrValue
    }
}
```

### 关键点

1. **指针获取**：内部表达式的 `irValue` 就是要解引用的指针。

2. **类型区分**：
   - **聚合类型**（结构体、数组）：不进行 load，直接传递指针
   - **标量类型**（整数、布尔等）：使用 `load` 指令读取值

3. **地址保留**：无论是否进行 load，`irAddr` 都设置为指针值。这允许：
   - 对解引用结果进行赋值：`*ptr = 10`
   - 对解引用结果再次借用：`&&*ptr`（虽然不常见）

### 示例

```rust
let x: i32 = 42;
let ptr: &i32 = &x;
let y: i32 = *ptr;  // 解引用
```

生成的 IR 类似于：
```llvm
%x = alloca i32
store i32 42, ptr %x
%ptr = alloca ptr
store ptr %x, ptr %ptr
%ptr_val = load ptr, ptr %ptr      ; 加载指针值
%y_val = load i32, ptr %ptr_val    ; 解引用：从指针加载 i32
%y = alloca i32
store i32 %y_val, ptr %y
```

## 边界情况

### 1. 借用临时值

```rust
&42  // 错误：不能借用字面量
```

语义分析阶段已拒绝此类代码。如果到达 IR 生成阶段，`irAddr` 为 null 会触发 `IRException`。

### 2. 多重借用与解引用

```rust
let x: i32 = 1;
let r1: &i32 = &x;
let r2: &&i32 = &r1;
let y: i32 = **r2;
```

- `&x`: 返回 x 的 alloca 地址
- `&r1`: 返回 r1 的 alloca 地址（r1 存储的是 ptr 类型）
- `*r2`: 从 r2 load 出 ptr，得到 r1 的地址
- `**r2`: 再从该 ptr load 出 ptr，得到 x 的地址，然后 load 出 i32

### 3. 可变借用用于赋值

```rust
let mut x: i32 = 1;
let ptr: &mut i32 = &mut x;
*ptr = 42;
```

解引用后的 `irAddr` 允许作为赋值目标。`visitAssignExpr` 会使用左侧的 `irAddr` 进行 store。

## 与其他 visitor 的交互

| Visitor | irValue | irAddr |
|---------|---------|--------|
| visitPathExpr (变量) | load 的值或聚合指针 | alloca 地址 |
| visitBorrowExpr | 内部的 irAddr | null |
| visitDerefExpr | load 的值或聚合指针 | 指针值 |
| visitAssignExpr | null | null |
| visitLetStmt | - | - |

## 总结

- **Borrow** 将左值的地址提升为右值
- **Deref** 将指针右值还原为可寻址的左值
- 两者互为逆操作，共同支持 Rx 语言的引用语义
