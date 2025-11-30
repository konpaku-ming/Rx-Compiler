# 有符号与无符号整数指令处理说明

## 问题背景

在当前的代码生成器实现中，所有的二元运算和比较操作均使用了有符号数指令（如 `sdiv`、`srem`、`icmp slt`、`ashr`），但源语言中存在 `u32`、`usize` 等无符号类型。这份文档将解释：

1. 有符号与无符号整数在语义和指令层面的差别
2. 哪些操作必须区分符号类型，哪些可以共用
3. 如何修正代码生成器以正确处理无符号语义
4. 各种后端架构的具体指令映射

---

## 目录

1. [当前实现分析](#当前实现分析)
2. [有符号与无符号的语义差异](#有符号与无符号的语义差异)
3. [指令分类：必须区分 vs 可以共用](#指令分类必须区分-vs-可以共用)
4. [各后端架构指令映射](#各后端架构指令映射)
5. [修正建议与实现要点](#修正建议与实现要点)
6. [代码示例：正确与错误对比](#代码示例正确与错误对比)
7. [测试建议与常见陷阱](#测试建议与常见陷阱)
8. [总结](#总结)

---

## 当前实现分析

### 类型系统现状

在 `ir/typeConverter.kt` 中，`i32` 和 `u32` 都被映射到相同的 `I32Type`：

```kotlin
// typeConverter.kt
when (type) {
    PrimitiveResolvedType("int"),
    PrimitiveResolvedType("unsigned int"),
    PrimitiveResolvedType("signed int"),
    PrimitiveResolvedType("i32"),
    PrimitiveResolvedType("u32"),      // 无符号类型
    PrimitiveResolvedType("isize"),
    PrimitiveResolvedType("usize") -> context.myGetI32Type()  // 都映射到 I32Type
```

**问题**：IR 类型层面丢失了符号信息。

### 代码生成现状

在 `ir/astLower.kt` 中，二元运算和比较操作均使用有符号指令：

```kotlin
// astLower.kt - visitBinaryExpr 方法
val result = when (node.operator.type) {
    TokenType.Add -> builder.createAdd(leftValue, rightValue)
    TokenType.SubNegate -> builder.createSub(leftValue, rightValue)
    TokenType.Mul -> builder.createMul(leftValue, rightValue)
    TokenType.Div -> builder.createSDiv(leftValue, rightValue)   // ⚠️ 始终使用有符号除法
    TokenType.Mod -> builder.createSRem(leftValue, rightValue)   // ⚠️ 始终使用有符号取余
    TokenType.Shr -> builder.createAShr(leftValue, rightValue)   // ⚠️ 始终使用算术右移
    // ...
}

// astLower.kt - visitComparisonExpr 方法
val result = when (node.operator.type) {
    TokenType.Lt -> builder.createICmpSLT(leftValue, rightValue)  // ⚠️ 始终使用有符号比较
    TokenType.Le -> builder.createICmpSLE(leftValue, rightValue)  // ⚠️ 始终使用有符号比较
    TokenType.Gt -> builder.createICmpSGT(leftValue, rightValue)  // ⚠️ 始终使用有符号比较
    TokenType.Ge -> builder.createICmpSGE(leftValue, rightValue)  // ⚠️ 始终使用有符号比较
    // ...
}
```

### IRBuilder 的能力

值得注意的是，`llvm/irBuilder.kt` **已经实现**了无符号版本的指令：

```kotlin
// irBuilder.kt 已有的无符号指令
fun createUDiv(lhs: Value, rhs: Value, name: String = ""): BinaryOperator  // 无符号除法
fun createURem(lhs: Value, rhs: Value, name: String = ""): BinaryOperator  // 无符号取余
fun createLShr(lhs: Value, rhs: Value, name: String = ""): BinaryOperator  // 逻辑右移
fun createICmpULT(lhs: Value, rhs: Value, name: String = ""): ICmpInst     // 无符号小于
fun createICmpULE(lhs: Value, rhs: Value, name: String = ""): ICmpInst     // 无符号小于等于
fun createICmpUGT(lhs: Value, rhs: Value, name: String = ""): ICmpInst     // 无符号大于
fun createICmpUGE(lhs: Value, rhs: Value, name: String = ""): ICmpInst     // 无符号大于等于
fun createZExt(type: IRType, value: Value, name: String = ""): ZExtInst    // 零扩展
```

**结论**：底层工具已具备，只需在代码生成阶段根据类型选择正确的指令。

---

## 有符号与无符号的语义差异

### 基本概念

| 类型 | 位表示 | 值范围 (32位) | 特点 |
|------|--------|--------------|------|
| `i32` (有符号) | 二进制补码 | -2,147,483,648 ~ 2,147,483,647 | 最高位为符号位 |
| `u32` (无符号) | 纯二进制 | 0 ~ 4,294,967,295 | 所有位均表示数值 |

### 相同位模式的不同解释

以 32 位整数 `0x80000000` 为例：

| 解释方式 | 值 |
|---------|-----|
| 有符号 (`i32`) | -2,147,483,648 |
| 无符号 (`u32`) | 2,147,483,648 |

### 关键语义差异

#### 1. 比较运算

```
设 a = 0x80000000, b = 0x7FFFFFFF

有符号比较 (a < b):
  a 解释为 -2147483648
  b 解释为 2147483647
  结果: true (-2147483648 < 2147483647)

无符号比较 (a < b):
  a 解释为 2147483648
  b 解释为 2147483647
  结果: false (2147483648 > 2147483647)
```

#### 2. 除法运算

```
设 a = 0xFFFFFFF6 (-10 有符号, 4294967286 无符号), b = 0x00000003

有符号除法: a / b = -10 / 3 = -3
无符号除法: a / b = 4294967286 / 3 = 1431655762
```

#### 3. 右移运算

```
设 a = 0x80000000, 右移 1 位

算术右移 (ashr): 0xC0000000 (符号位扩展)
逻辑右移 (lshr): 0x40000000 (补 0)
```

#### 4. 类型扩展

从 8 位扩展到 32 位，值为 `0xFF`：

```
符号扩展 (sext): 0xFF -> 0xFFFFFFFF (-1)
零扩展 (zext):   0xFF -> 0x000000FF (255)
```

---

## 指令分类：必须区分 vs 可以共用

### 必须区分符号类型的操作

| 操作 | 有符号指令 | 无符号指令 | 说明 |
|------|-----------|-----------|------|
| 除法 | `sdiv` | `udiv` | 结果完全不同 |
| 取余 | `srem` | `urem` | 结果完全不同 |
| 右移 | `ashr` | `lshr` | 填充位不同 |
| 小于 | `icmp slt` | `icmp ult` | 比较语义不同 |
| 小于等于 | `icmp sle` | `icmp ule` | 比较语义不同 |
| 大于 | `icmp sgt` | `icmp ugt` | 比较语义不同 |
| 大于等于 | `icmp sge` | `icmp uge` | 比较语义不同 |
| 扩展 | `sext` | `zext` | 填充位不同 |

### 可以共用的操作（符号无关）

| 操作 | 指令 | 说明 |
|------|------|------|
| 加法 | `add` | 模 2^N 算术，结果相同 |
| 减法 | `sub` | 模 2^N 算术，结果相同 |
| 乘法 | `mul` | 模 2^N 算术，低位结果相同 |
| 按位与 | `and` | 纯位运算 |
| 按位或 | `or` | 纯位运算 |
| 按位异或 | `xor` | 纯位运算 |
| 按位取反 | `not` | 纯位运算 |
| 左移 | `shl` | 无符号移位 |
| 相等比较 | `icmp eq` | 位模式比较 |
| 不等比较 | `icmp ne` | 位模式比较 |
| 截断 | `trunc` | 直接丢弃高位 |

---

## 各后端架构指令映射

### LLVM IR

| 操作 | 有符号 | 无符号 |
|------|--------|--------|
| 除法 | `%c = sdiv i32 %a, %b` | `%c = udiv i32 %a, %b` |
| 取余 | `%c = srem i32 %a, %b` | `%c = urem i32 %a, %b` |
| 右移 | `%c = ashr i32 %a, 1` | `%c = lshr i32 %a, 1` |
| 小于比较 | `%cmp = icmp slt i32 %a, %b` | `%cmp = icmp ult i32 %a, %b` |
| 扩展到 64 位 | `%e = sext i32 %a to i64` | `%e = zext i32 %a to i64` |

### WebAssembly

| 操作 | 有符号 | 无符号 |
|------|--------|--------|
| 除法 | `i32.div_s` | `i32.div_u` |
| 取余 | `i32.rem_s` | `i32.rem_u` |
| 右移 | `i32.shr_s` | `i32.shr_u` |
| 小于比较 | `i32.lt_s` | `i32.lt_u` |
| 小于等于 | `i32.le_s` | `i32.le_u` |
| 大于比较 | `i32.gt_s` | `i32.gt_u` |
| 大于等于 | `i32.ge_s` | `i32.ge_u` |
| i32 扩展到 i64 | `i64.extend_i32_s` | `i64.extend_i32_u` |

### x86/x86-64

| 操作 | 有符号 | 无符号 |
|------|--------|--------|
| 除法 | `idiv` | `div` |
| 右移 | `sar` (arithmetic) | `shr` (logical) |
| 扩展加载 | `movsx` | `movzx` |
| 比较跳转 | `jl`, `jle`, `jg`, `jge` | `jb`, `jbe`, `ja`, `jae` |

### ARM/AArch64

| 操作 | 有符号 | 无符号 |
|------|--------|--------|
| 除法 | `SDIV` | `UDIV` |
| 右移 | `ASR` | `LSR` |
| 扩展 | `SXTW`, `SXTH`, `SXTB` | `UXTW`, `UXTH`, `UXTB` |
| 比较条件 | `LT`, `LE`, `GT`, `GE` | `LO`/`CC`, `LS`, `HI`, `HS`/`CS` |

---

## 修正建议与实现要点

### 方案一：在类型系统中保留符号信息

最彻底的解决方案是在 IR 类型中保留符号信息。

#### 1. 修改类型定义

```kotlin
// 在 llvm/irType.kt 或新文件中添加
sealed class I32Type : IntegerType(32) {
    object Signed : I32Type()
    object Unsigned : I32Type()
}

// 或者使用包装类
data class SignedType(val baseType: IntegerType, val isSigned: Boolean)
```

#### 2. 修改类型转换

```kotlin
// ir/typeConverter.kt
fun getIRType(context: LLVMContext, type: ResolvedType): IRType {
    return when (type) {
        is PrimitiveResolvedType -> {
            when (type.name) {
                "i32", "isize" -> context.myGetI32Type(signed = true)
                "u32", "usize" -> context.myGetI32Type(signed = false)
                // ...
            }
        }
        // ...
    }
}
```

### 方案二：在代码生成时检查 AST 类型（推荐）

更简单的方案是在代码生成时直接检查 AST 节点的 `resolvedType` 来判断符号类型。

#### 1. 添加辅助函数

```kotlin
// ir/astLower.kt 中添加
private fun isUnsignedType(type: ResolvedType): Boolean {
    return when (type) {
        is PrimitiveResolvedType -> type.name in listOf("u32", "usize", "unsigned int")
        else -> false
    }
}
```

#### 2. 修改二元运算生成

```kotlin
override fun visitBinaryExpr(node: BinaryExprNode) {
    // ... 前置代码 ...
    
    // 检查操作数类型是否为无符号
    val isUnsigned = isUnsignedType(node.left.resolvedType)
    
    val result = when (node.operator.type) {
        TokenType.Add -> builder.createAdd(leftValue, rightValue)
        TokenType.SubNegate -> builder.createSub(leftValue, rightValue)
        TokenType.Mul -> builder.createMul(leftValue, rightValue)
        
        // 除法：根据符号选择指令
        TokenType.Div -> if (isUnsigned) {
            builder.createUDiv(leftValue, rightValue)
        } else {
            builder.createSDiv(leftValue, rightValue)
        }
        
        // 取余：根据符号选择指令
        TokenType.Mod -> if (isUnsigned) {
            builder.createURem(leftValue, rightValue)
        } else {
            builder.createSRem(leftValue, rightValue)
        }
        
        // 右移：根据符号选择指令
        TokenType.Shr -> if (isUnsigned) {
            builder.createLShr(leftValue, rightValue)  // 逻辑右移
        } else {
            builder.createAShr(leftValue, rightValue)  // 算术右移
        }
        
        // 位运算：符号无关
        TokenType.BitAnd -> builder.createAnd(leftValue, rightValue)
        TokenType.BitOr -> builder.createOr(leftValue, rightValue)
        TokenType.BitXor -> builder.createXor(leftValue, rightValue)
        TokenType.Shl -> builder.createShl(leftValue, rightValue)
        
        else -> throw IRException("Unknown binary operator: ${node.operator.type}")
    }
    
    node.irValue = result
    // ... 后置代码 ...
}
```

#### 3. 修改比较运算生成

```kotlin
override fun visitComparisonExpr(node: ComparisonExprNode) {
    // ... 前置代码 ...
    
    val isUnsigned = isUnsignedType(node.left.resolvedType)
    
    val result = when (node.operator.type) {
        // 相等比较：符号无关
        TokenType.Eq -> builder.createICmpEQ(leftValue, rightValue)
        TokenType.Neq -> builder.createICmpNE(leftValue, rightValue)
        
        // 不等比较：根据符号选择指令
        TokenType.Lt -> if (isUnsigned) {
            builder.createICmpULT(leftValue, rightValue)
        } else {
            builder.createICmpSLT(leftValue, rightValue)
        }
        
        TokenType.Le -> if (isUnsigned) {
            builder.createICmpULE(leftValue, rightValue)
        } else {
            builder.createICmpSLE(leftValue, rightValue)
        }
        
        TokenType.Gt -> if (isUnsigned) {
            builder.createICmpUGT(leftValue, rightValue)
        } else {
            builder.createICmpSGT(leftValue, rightValue)
        }
        
        TokenType.Ge -> if (isUnsigned) {
            builder.createICmpUGE(leftValue, rightValue)
        } else {
            builder.createICmpSGE(leftValue, rightValue)
        }
        
        else -> throw IRException("Unknown comparison operator: ${node.operator.type}")
    }
    
    node.irValue = result
    // ... 后置代码 ...
}
```

### Codegen 检查清单

修改代码生成器时，请确保以下各项：

- [ ] **类型信息获取**：在生成指令前能够获取操作数的符号类型（通过 `resolvedType`）
- [ ] **除法运算**：`i32` 使用 `sdiv`，`u32` 使用 `udiv`
- [ ] **取余运算**：`i32` 使用 `srem`，`u32` 使用 `urem`
- [ ] **右移运算**：`i32` 使用 `ashr`，`u32` 使用 `lshr`
- [ ] **比较运算**：`i32` 使用 `slt/sle/sgt/sge`，`u32` 使用 `ult/ule/ugt/uge`
- [ ] **类型扩展**：从窄类型到宽类型时，有符号用 `sext`，无符号用 `zext`
- [ ] **加载窄类型**：加载 `u8`/`u16` 到 `u32` 时使用零扩展
- [ ] **混合类型操作**：当两个操作数符号不同时，明确转换规则

---

## 代码示例：正确与错误对比

### 示例 1：比较运算

**源代码：**
```rust
fn compare_unsigned(a: u32, b: u32) -> bool {
    a < b
}

fn test() {
    let x: u32 = 0x80000000;  // 2147483648
    let y: u32 = 0x7FFFFFFF;  // 2147483647
    let result = compare_unsigned(x, y);  // 应为 false
}
```

**错误生成（当前行为）：**
```llvm
define i1 @compare_unsigned(i32 %a, i32 %b) {
entry:
  %cmp = icmp slt i32 %a, %b  ; ❌ 错误：使用有符号比较
  ret i1 %cmp
}
; 当 a=0x80000000, b=0x7FFFFFFF 时：
; slt 将 a 解释为 -2147483648，b 为 2147483647
; 结果为 true（错误！）
```

**正确生成：**
```llvm
define i1 @compare_unsigned(i32 %a, i32 %b) {
entry:
  %cmp = icmp ult i32 %a, %b  ; ✓ 正确：使用无符号比较
  ret i1 %cmp
}
; 当 a=0x80000000, b=0x7FFFFFFF 时：
; ult 将 a 解释为 2147483648，b 为 2147483647
; 结果为 false（正确！）
```

### 示例 2：除法运算

**源代码：**
```rust
fn divide_unsigned(a: u32, b: u32) -> u32 {
    a / b
}

fn test() {
    let x: u32 = 0xFFFFFFF6;  // 4294967286 无符号，-10 有符号
    let y: u32 = 3;
    let result = divide_unsigned(x, y);  // 应为 1431655762
}
```

**错误生成（当前行为）：**
```llvm
define i32 @divide_unsigned(i32 %a, i32 %b) {
entry:
  %div = sdiv i32 %a, %b  ; ❌ 错误：使用有符号除法
  ret i32 %div
}
; 当 a=0xFFFFFFF6, b=3 时：
; sdiv 将 a 解释为 -10
; 结果为 -3 = 0xFFFFFFFD（错误！）
```

**正确生成：**
```llvm
define i32 @divide_unsigned(i32 %a, i32 %b) {
entry:
  %div = udiv i32 %a, %b  ; ✓ 正确：使用无符号除法
  ret i32 %div
}
; 当 a=0xFFFFFFF6, b=3 时：
; udiv 将 a 解释为 4294967286
; 结果为 1431655762（正确！）
```

### 示例 3：右移运算

**源代码：**
```rust
fn shift_right_unsigned(a: u32) -> u32 {
    a >> 4
}

fn test() {
    let x: u32 = 0x80000000;  // 最高位为 1
    let result = shift_right_unsigned(x);  // 应为 0x08000000
}
```

**错误生成（当前行为）：**
```llvm
define i32 @shift_right_unsigned(i32 %a) {
entry:
  %shr = ashr i32 %a, 4  ; ❌ 错误：使用算术右移
  ret i32 %shr
}
; 当 a=0x80000000 时：
; ashr 保留符号位，结果为 0xF8000000（错误！）
```

**正确生成：**
```llvm
define i32 @shift_right_unsigned(i32 %a) {
entry:
  %shr = lshr i32 %a, 4  ; ✓ 正确：使用逻辑右移
  ret i32 %shr
}
; 当 a=0x80000000 时：
; lshr 用 0 填充，结果为 0x08000000（正确！）
```

### 示例 4：类型扩展

**源代码：**
```rust
fn widen_unsigned(a: u8) -> u32 {
    a as u32
}

fn test() {
    let x: u8 = 0xFF;  // 255
    let result = widen_unsigned(x);  // 应为 255
}
```

**错误生成：**
```llvm
define i32 @widen_unsigned(i8 %a) {
entry:
  %ext = sext i8 %a to i32  ; ❌ 错误：使用符号扩展
  ret i32 %ext
}
; 当 a=0xFF 时：
; sext 扩展符号位，结果为 0xFFFFFFFF = -1（错误！）
```

**正确生成：**
```llvm
define i32 @widen_unsigned(i8 %a) {
entry:
  %ext = zext i8 %a to i32  ; ✓ 正确：使用零扩展
  ret i32 %ext
}
; 当 a=0xFF 时：
; zext 用 0 填充，结果为 0x000000FF = 255（正确！）
```

---

## 测试建议与常见陷阱

### 边界值测试

针对 `u32` 类型，应测试以下边界值：

| 值 | 十六进制 | 说明 |
|---|----------|------|
| 0 | `0x00000000` | 最小值 |
| 1 | `0x00000001` | 正常小值 |
| 2147483647 | `0x7FFFFFFF` | i32 最大值 |
| 2147483648 | `0x80000000` | i32 溢出，u32 正常 |
| 4294967295 | `0xFFFFFFFF` | u32 最大值 |

### 关键测试用例

#### 1. 比较测试

```rust
#[test]
fn test_unsigned_comparison() {
    let a: u32 = 0x80000000;
    let b: u32 = 0x7FFFFFFF;
    
    // 无符号语义下 a > b
    assert!(a > b);   // 2147483648 > 2147483647
    assert!(!(a < b));
    assert!(!(a == b));
}
```

#### 2. 除法测试

```rust
#[test]
fn test_unsigned_division() {
    let a: u32 = 0xFFFFFFF6;  // 4294967286
    let b: u32 = 3;
    let result = a / b;
    
    assert_eq!(result, 1431655762);  // 不是 -3！
}
```

#### 3. 右移测试

```rust
#[test]
fn test_unsigned_shift() {
    let a: u32 = 0x80000000;
    let result = a >> 1;
    
    assert_eq!(result, 0x40000000);  // 不是 0xC0000000！
}
```

#### 4. 扩展测试

```rust
#[test]
fn test_unsigned_extension() {
    let a: u8 = 0xFF;
    let result: u32 = a as u32;
    
    assert_eq!(result, 255);  // 不是 -1 (0xFFFFFFFF)！
}
```

### 常见陷阱

1. **混合符号运算**
   ```rust
   let a: i32 = -1;
   let b: u32 = 1;
   // a 和 b 进行比较时，类型如何转换？
   // 需要明确语言规范中的转换规则
   ```

2. **隐式类型提升**
   ```rust
   let a: u8 = 255;
   let b: u8 = 1;
   let c = a + b;  // 溢出行为取决于语言规范
   ```

3. **除以零**
   - 有符号和无符号除法对除以零的处理可能不同
   - 某些架构会产生不同的异常

4. **位字面量解释**
   ```rust
   let a = 0x80000000_u32;  // 明确是 u32
   let b = 0x80000000;      // 默认类型是什么？可能是负数！
   ```

---

## 总结

### 问题根源

当前代码生成器使用有符号指令处理所有整数运算，但源语言存在 `u32`、`usize` 等无符号类型。这会在以下场景产生错误结果：

1. **比较运算**：当高位为 1 时，有符号比较会将其解释为负数
2. **除法/取余**：有符号和无符号的结果完全不同
3. **右移运算**：算术右移会保留符号位，逻辑右移用 0 填充
4. **类型扩展**：符号扩展和零扩展的结果不同

### 解决方案

1. 在代码生成时检查 `resolvedType` 判断是否为无符号类型
2. 根据类型选择正确的指令变体（`IRBuilder` 已提供）
3. 添加针对无符号类型的单元测试

### 操作安全性表

| 操作 | 共用安全 | 必须区分 |
|------|----------|----------|
| 加法 `+` | ✓ | |
| 减法 `-` | ✓ | |
| 乘法 `*` | ✓ | |
| 除法 `/` | | ✓ |
| 取余 `%` | | ✓ |
| 左移 `<<` | ✓ | |
| 右移 `>>` | | ✓ |
| 位与 `&` | ✓ | |
| 位或 `|` | ✓ | |
| 位异或 `^` | ✓ | |
| 相等 `==` | ✓ | |
| 不等 `!=` | ✓ | |
| 小于 `<` | | ✓ |
| 大于 `>` | | ✓ |
| 小于等于 `<=` | | ✓ |
| 大于等于 `>=` | | ✓ |
| 类型扩展 | | ✓ |

### 修改优先级

1. **高优先级**：比较运算、除法、右移 — 这些是最容易出错的地方
2. **中优先级**：取余运算、类型扩展
3. **低优先级**：加减乘和位运算 — 在模 2^N 语义下通常是正确的

---

## 附录：相关代码位置

| 文件 | 位置 | 需要修改 |
|------|------|----------|
| `ir/typeConverter.kt` | 类型转换 | 可选：保留符号信息 |
| `ir/astLower.kt:visitBinaryExpr` | 二元运算生成 | 必须：选择正确指令 |
| `ir/astLower.kt:visitComparisonExpr` | 比较运算生成 | 必须：选择正确指令 |
| `ir/astLower.kt:visitTypeCastExpr` | 类型转换生成 | 必须：选择正确扩展 |
| `llvm/irBuilder.kt` | 指令构建器 | 已完成：已有无符号变体 |
