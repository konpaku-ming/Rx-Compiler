# Rx 编译器 AST 到 LLVM IR 生成计划

## 1. 概述

本文档描述了如何将经过语义检查和名称重命名的 Rx 编译器 AST 高效地编译为 LLVM IR。生成的 IR 应该简洁无冗余，仅包含程序执行所需的必要信息。

## 2. 基本架构

### 2.1 IR 生成流程

```
AST (已通过语义检查)
  ↓
IR Generator (遍历 AST)
  ↓
LLVM IR (.ll 文件)
  ↓
LLVM 工具链处理
```

### 2.2 核心组件

- **LLVMIRGenerator**: 访问者模式实现，遍历 AST 节点
- **LLVMEmitter**: 生成和管理 IR 指令
- **IRContext**: 维护变量名到临时寄存器的映射
- **TypeResolver**: 解析 ResolvedType 到 LLVM 类型

### 2.3 Rx 语言语法概述

#### 2.3.1 语言定位

Rx 是一个基于 Rust 语言的编程语言，保留了 Rust 的核心特性（所有权、借用、模式匹配等），同时进行了必要的简化和改进。本节详细阐述 Rx 语言的完整语法规则。

#### 2.3.2 基本概念

Rx 采用 **表达式优先** 的设计哲学，几乎所有的语言构造都是表达式，包括 if/else、循环、块等。

#### 2.3.3 词汇与符号

##### 关键字
| 关键字 | 用途 |
|--------|------|
| `fn` | 函数定义 |
| `let` | 变量声明 |
| `mut` | 可变修饰符 |
| `if`, `else` | 条件分支 |
| `loop` | 无限循环 |
| `while` | 条件循环 |
| `break` | 循环中断 |
| `continue` | 循环继续 |
| `return` | 函数返回 |
| `struct` | 结构体定义 |
| `enum` | 枚举定义 |
| `trait` | 特征定义 |
| `impl` | 实现块 |
| `const` | 常量定义 |
| `true`, `false` | 布尔字面值 |
| `as` | 类型转换 |
| `Self`, `self` | 类型/实例 self 引用 |

##### 符号

| 类别 | 符号 | 说明 |
|------|------|------|
| **算术** | `+`, `-`, `*`, `/`, `%` | 加、减、乘、除、取模 |
| **位运算** | `&`, `\|`, `^`, `<<`, `>>` | 与、或、异或、左移、右移 |
| **逻辑** | `&&`, `\|\|`, `!` | 逻辑与、逻辑或、逻辑非 |
| **比较** | `==`, `!=`, `<`, `>`, `<=`, `>=` | 相等、不等、小于、大于、小等、大等 |
| **赋值** | `=` | 简单赋值 |
| **复合赋值** | `+=`, `-=`, `*=`, `/=`, `%=`, `&=`, `\|=`, `^=`, `<<=`, `>>=` | 复合赋值操作 |
| **引用** | `&`, `&mut` | 不可变引用、可变引用 |
| **解引用** | `*` | 指针解引用 |
| **路径** | `::` | 路径分隔符 |
| **成员访问** | `.` | 字段/方法访问 |
| **箭头** | `->` | 函数返回类型 |
| **分隔** | `,`, `;`, `:` | 逗号、分号、冒号 |
| **分组** | `()`, `{}`, `[]` | 括号、花括号、方括号 |

#### 2.3.4 类型系统

##### 基本类型

Rx 支持以下基本类型：

```
基本类型 ::= i32 | u32 | usize | isize | bool | char | str | ()
```

| 类型 | 说明 | 示例 |
|------|------|------|
| `i32` | 32 位有符号整数 | `42`, `0xFF`, `0b1010` |
| `u32` | 32 位无符号整数 | `42u32` |
| `usize` | 无符号指针大小整数 | 平台相关 |
| `isize` | 有符号指针大小整数 | 平台相关 |
| `bool` | 布尔值 | `true`, `false` |
| `char` | 单个 Unicode 字符 | `'a'`, `'\n'` |
| `str` | 字符串类型 | 字符串字面值 |
| `()` | 单元类型（空值） | `()` |

##### 复合类型

```
复合类型 ::= 引用类型 | 数组类型 | 结构体类型 | 枚举类型 | 自定义类型

引用类型 ::= & [mut] 类型
数组类型 ::= [ 类型 ; 表达式 ]
结构体类型 ::= struct 标识符
枚举类型 ::= enum 标识符
```

**引用类型**：
- 不可变引用：`&T` - 只读访问，通过 `ReferenceResolvedType` 表示
- 可变引用：`&mut T` - 读写访问，通过 `ReferenceResolvedType(isMut=true)` 表示

**数组类型**：
- 定长数组：`[i32; 5]` - 5 个 i32 的数组
- 长度通过常量表达式指定
- 通过 `ArrayResolvedType` 表示

**结构体类型**：
- 用户定义的复合类型，包含多个字段
- 每个字段有名称和类型
- 通过 `NamedResolvedType` 表示，链接到 `StructSymbol`
- 结构体可以包含关联函数、方法和常量

**示例**：
```rust
struct Point {
    x: i32,
    y: i32,
}

struct Person {
    name: str,
    age: i32,
}
```

**枚举类型**：
- 用户定义的求和类型，包含多个变体
- 每个变体是一个可选的选项
- 通过 `NamedResolvedType` 表示，链接到 `EnumSymbol`
- 当前 Rx 支持简单的无关联数据的枚举

**示例**：
```rust
enum Color {
    Red,
    Green,
    Blue,
}

enum Status {
    Active,
    Inactive,
    Suspended,
}
```

**Trait 类型**：
- 定义方法和常量的接口
- 通过 `TraitSymbol` 表示
- 支持通过 `impl Trait for Type` 实现

##### ResolvedType 体系

Rx 的类型系统在语义分析后生成 `ResolvedType`，包含以下几种：

| ResolvedType | 说明 | 关联 Symbol |
|------------|------|-----------|
| `PrimitiveResolvedType` | 基本类型（i32、bool 等） | 无 |
| `ReferenceResolvedType` | 引用类型 `&T` 或 `&mut T` | 无，包含内部类型 |
| `ArrayResolvedType` | 定长数组 `[T; n]` | 无，包含元素类型 |
| `NamedResolvedType` | 自定义类型（结构体、枚举、trait） | `StructSymbol`, `EnumSymbol`, `TraitSymbol` |
| `UnitResolvedType` | 单元类型 `()` | 无 |
| `NeverResolvedType` | 底部类型，表示不会返回 | 无 |
| `UnknownResolvedType` | 未知类型（类型推断中） | 无 |

##### 类型在 AST中的表示

在 AST 中，类型通过 `TypeNode` 表示：

| TypeNode | 对应 ResolvedType |
|----------|-----------------|
| `TypePathNode` | `PrimitiveResolvedType` 或 `NamedResolvedType` |
| `ReferenceTypeNode` | `ReferenceResolvedType` |
| `ArrayTypeNode` | `ArrayResolvedType` |
| `UnitTypeNode` | `UnitResolvedType` |

**示例**：
```rust
let x: i32 = 42;              // TypePathNode("i32") → PrimitiveResolvedType("i32")
let p: &Point = &point;       // ReferenceTypeNode → ReferenceResolvedType
let arr: [i32; 5] = ...;      // ArrayTypeNode → ArrayResolvedType
let unit: () = ();            // UnitTypeNode → UnitResolvedType
let p: Point = ...;           // TypePathNode("Point") → NamedResolvedType("Point", StructSymbol)
```

##### 字面值

```
字面值 ::= 整数字面值 | 布尔字面值 | 字符字面值 | 字符串字面值

整数字面值 ::= 十进制 | 十六进制 | 二进制 | 八进制 [类型后缀]
              十进制 ::= [0-9][0-9_]*
              十六进制 ::= 0x[0-9a-fA-F_]*
              二进制 ::= 0b[01_]*
              八进制 ::= 0o[0-7_]*
              类型后缀 ::= i32 | i64 | u32 | usize | isize
              
布尔字面值 ::= true | false

字符字面值 ::= ' 字符 '

字符串字面值 ::= " 字符串 "
               | c" 字符串 "      // C-string (with null terminator)
               | r# 字符串 #      // raw string
               | cr# 字符串 #     // raw C-string
```

**示例**：
```rust
let a: i32 = 42;           // 十进制
let b: i32 = 0xFF;         // 十六进制
let c: i32 = 0b1010;       // 二进制
let d: i64 = 100i64;       // 带类型后缀
let e: bool = true;        // 布尔值
let f: char = 'A';         // 字符
let s1: str = "hello";     // 字符串
let s2: str = c"hello";    // C 字符串
let s3: str = r"raw\nstring"; // 原始字符串
```

#### 2.3.5 表达式

##### 字面值表达式
```
字面值表达式 ::= 整数 | 布尔值 | 字符 | 字符串
```

##### 路径表达式
```
路径表达式 ::= 标识符 [ :: 标识符 ]
            | self
            | Self
```

**示例**：
```rust
x              // 变量
Point::new     // 结构体关联函数
self           // 当前实例
Self           // 当前类型
```

##### 操作符表达式

**前缀操作符**：
```
前缀表达式 ::= - 表达式          // 算术否定
            | ! 表达式          // 逻辑否定
            | & [mut] 表达式    // 借用（引用）
            | * 表达式          // 解引用
```

**中缀操作符**（按优先级从高到低）：
```
优先级 15: ::                    // 路径分隔
优先级 14: .                     // 字段/方法访问
优先级 13: () []                 // 函数调用、索引
优先级 12: 前缀操作符
优先级 11: as                    // 类型转换
优先级 10: * / %                 // 乘、除、取模
优先级 9:  + -                   // 加、减
优先级 8:  << >>                 // 位移
优先级 7:  &                     // 位与
优先级 6:  ^                     // 位异或
优先级 5:  |                     // 位或
优先级 4:  == != < > <= >=       // 比较
优先级 3:  &&                    // 逻辑与
优先级 2:  ||                    // 逻辑或
优先级 1:  = += -= *= /= %= &= |= ^= <<= >>= // 赋值
```

##### 块表达式
```
块表达式 ::= { [项]* [语句]* [表达式] }

项 ::= 函数定义 | 结构体定义 | 常量定义 | ...
语句 ::= let 声明 | 表达式 ;
表达式 ::= （不带分号的表达式，作为块的返回值）
```

**块的特性**：
- 块是表达式，可以返回值
- 块内最后一个表达式（无分号）是块的返回值
- 表达式后加分号变为语句，块返回 `()`
- 块可以包含项定义（函数、结构体等）

**示例**：
```rust
let x = {
    let y = 5;
    y + 1      // 无分号，是块的返回值，所以 x = 6
};

let z = {
    let y = 5;
    y + 1;     // 有分号，是语句，块返回 ()
};  // z = ()
```

##### 条件表达式
```
if 表达式 ::= if 表达式 { 块 } [else if 表达式 { 块 }]* [else { 块 }]
```

**特性**：
- 条件必须为 `bool` 类型
- 每个分支都是块表达式
- 所有分支的返回类型必须一致
- 可以没有 else 分支（隐式返回 `()`）
- 整个 if 表达式可以作为右值赋给变量

**示例**：
```rust
let x = if condition {
    10
} else {
    20
};

if x > 5 {
    // ...
}
```

##### 循环表达式

**无限循环**：
```
loop 表达式 ::= loop { 块 }
```

**条件循环**：
```
while 表达式 ::= while 表达式 { 块 }
```

**循环控制**：
```
break 表达式 ::= break [表达式]
continue 表达式 ::= continue
```

**示例**：
```rust
loop {
    if x > 10 {
        break;
    }
    x = x + 1;
}

while x < 10 {
    x = x + 1;
}

let result = loop {
    if x > 10 {
        break x;  // 循环返回值
    }
    x = x + 1;
};
```

##### 返回表达式
```
return 表达式 ::= return [表达式]
```

**示例**：
```rust
fn foo() -> i32 {
    return 42;
}
```

##### 数组表达式
```
数组表达式 ::= [ ] 
            | [ 表达式 ( , 表达式 )* [,] ]
            | [ 表达式 ; 表达式 ]
```

**三种形式**：
- 空数组：`[]`
- 元素列表：`[1, 2, 3]`
- 重复模式：`[value; count]` - count 个 value 的数组

**示例**：
```rust
let empty = [];
let arr = [1, 2, 3];
let arr2 = [0; 5];  // [0, 0, 0, 0, 0]
```

##### 索引表达式
```
索引表达式 ::= 表达式 [ 表达式 ]
```

**示例**：
```rust
let elem = arr[2];
arr[0] = 10;
```

##### 函数调用表达式
```
调用表达式 ::= 表达式 ( [表达式 ( , 表达式 )* ] )
```

**示例**：
```rust
foo()
add(1, 2)
point.distance(other)
```

##### 方法调用表达式
```
方法调用 ::= 表达式 . 标识符 ( [表达式 ( , 表达式 )* ] )
```

**示例**：
```rust
obj.method()
point.distance(other)
```

##### 字段访问表达式
```
字段访问 ::= 表达式 . 标识符
```

**示例**：
```rust
point.x
person.name
```

##### 结构体表达式
```
结构体表达式 ::= 路径 { [字段 : 表达式 ( , 字段 : 表达式 )*] }

字段 ::= 标识符
```

**示例**：
```rust
let p = Point { x: 10, y: 20 };
let p2 = Point { x, y };
```

##### 分组表达式
```
分组表达式 ::= ( 表达式 )
```

##### 类型转换表达式
```
类型转换 ::= 表达式 as 类型
```

**示例**：
```rust
let b = a as i64;
let c = x as bool;
```

#### 2.3.6 语句

```
语句 ::= let 声明 | 表达式语句 | 空语句

let 声明 ::= let 模式 : 类型 = 表达式 ;

表达式语句 ::= 表达式 ;

空语句 ::= ;
```

##### 变量声明（let 语句）

```rust
let x: i32 = 5;
let mut y: i32 = 10;
let &mut z: &mut i32 = &mut y;
```

**特性**：
- 必须指定类型
- 必须立即初始化（no uninitialized variables）
- 支持可变性修饰符：`mut`
- 支持引用模式：`&T`、`&mut T`

##### 模式（Pattern）

```
模式 ::= 标识符 [mut]
      | & [mut] 模式
```

**示例**：
```rust
let x = 5;           // 标识符模式
let mut y = 10;      // 可变标识符
let &z = &5;         // 引用模式
let &mut w = &mut 10; // 可变引用模式
```

#### 2.3.7 项定义

##### 函数定义
```
函数 ::= fn 标识符 ( [参数] ) [-> 类型] { 块 }

参数 ::= [SelfParam | Param (',' Param)*]
SelfParam      ::= ['&'] ['mut'] 'self'
Param          ::= 模式 : 类型
```

**示例**：
```rust
fn add(a: i32, b: i32) -> i32 {
    a + b
}

fn main() {
    // ...
}

impl Point {
    fn distance(&self, other: &Point) -> i32 {
        // ...
    }
}
```

**特性**：
- 支持普通函数和方法
- 方法包含 self 参数：`&self`、`&mut self` 或 `self`
- 必须指定所有参数类型
- 必须指定返回类型（无则为 `()`）
- 函数体是块表达式

##### 结构体定义
```
结构体 ::= struct 标识符 { [字段 ( , 字段 )*] }

字段 ::= 标识符 : 类型
```

**示例**：
```rust
struct Point {
    x: i32,
    y: i32,
}

struct Empty {}
```

##### 枚举定义
```
枚举 ::= enum 标识符 { 变体 ( , 变体 )* }

变体 ::= 标识符
```

**示例**：
```rust
enum Color {
    Red,
    Green,
    Blue,
}
```

##### 常量定义
```
常量 ::= const 标识符 : 类型 [= 表达式] ;
```

**示例**：
```rust
const PI: i32 = 3;
const MAX_SIZE: i32 = 100;
```

##### Trait 定义
```
trait ::= trait 标识符 { [项]* }
```

##### Impl 块
```
impl ::= impl [trait_name for] 类型 { [函数]* }
```

**示例**：
```rust
impl Point {
    fn new(x: i32, y: i32) -> Point {
        Point { x, y }
    }
}

impl Add for Point {
    fn add(self, other: Point) -> Point {
        // ...
    }
}
```

#### 2.3.8 作用域与可见性

- **全局作用域**：Crate 级别，所有顶级项
- **函数作用域**：函数内的局部作用域
- **块作用域**：块内的局部作用域
- **变量遮蔽**：内层作用域可以遮蔽外层同名变量
- **生命周期**：变量从声明到块结束

#### 2.3.9 错误处理

Rx 不支持异常，但支持：
- 返回值错误处理
- panic（通过 never 类型）
- Option/Result（future feature）

#### 2.3.10 完整语法 EBNF

```ebnf
Crate          ::= Item*

Item           ::= Function
                 | Struct
                 | Enum
                 | Trait
                 | Impl
                 | Const

Function       ::= 'fn' IDENTIFIER '(' Parameters ')' ['->' Type] Block

Parameters     ::= [SelfParam | Param (',' Param)*]
SelfParam      ::= ['&'] ['mut'] 'self'
Param          ::= Pattern ':' Type

Struct         ::= 'struct' IDENTIFIER '{' [Field (',' Field)*] '}'
Field          ::= IDENTIFIER ':' Type

Enum           ::= 'enum' IDENTIFIER '{' Variant (',' Variant)* '}'
Variant        ::= IDENTIFIER

Const          ::= 'const' IDENTIFIER ':' Type ['=' Expr] ';'

Trait          ::= 'trait' IDENTIFIER '{' Item* '}'

Impl           ::= 'impl' ['trait_name' 'for'] Type '{' Function* '}'

Type           ::= PrimitiveType
                 | StructType
                 | EnumType
                 | '&' ['mut'] Type
                 | '[' Type ';' Expr ']'
                 | '(' ')'

PrimitiveType  ::= 'i32' | 'u32' | 'usize' | 'isize' | 'bool' | 'char' | 'str'
```

#### 2.3.11 语法特性总结

| 特性 | 说明 |
|------|------|
| 表达式优先 | 大多数构造都是表达式 |
| 强类型 | 所有变量必须有明确的类型 |
| 所有权系统 | 变量有明确的所有者 |
| 借用 | 通过引用实现非拥有访问 |
| 可变性 | 通过 mut 显式标记可变性 |
| 模式匹配 | 在变量声明中支持基本模式 |
| 尾部表达式 | 块内最后不带分号的表达式为返回值 |
| 块作用域 | 块是表达式，有自己的作用域 |

---

## 3. 类型映射规则

### Rx 类型 → LLVM 类型映射

| Rx 类型 | LLVM 类型 | 说明 |
|---------|----------|------|
| `i32` | `i32` | 32 位有符号整数 |
| `u32` | `i32` | 32 位无符号整数 |
| `usize` | `i32` | 无符号指针大小整数（32位） |
| `isize` | `i32` | 有符号指针大小整数（32位） |
| `bool` | `i1` | 布尔值 |
| `char` | `i32` | 字符（Unicode） |
| `str` | `i8*` | 字符串指针 |
| `()` | `void` | 单元类型 |
| `&T` | `T*` | 不可变引用 |
| `&mut T` | `T*` | 可变引用 |
| `[T; n]` | `[T × n]` | 定长数组 |

### 基础类型转换函数

```kotlin
fun resolvedTypeToLLVMType(type: ResolvedType): String {
    return when (type) {
        is PrimitiveResolvedType -> {
            when (type.name) {
                "i32", "u32", "isize", "usize" -> "i32"
                "bool" -> "i1"
                "char" -> "i32"
                "str" -> "i8*"
                else -> error("Unknown primitive: ${type.name}")
            }
        }
        is ReferenceResolvedType -> {
            resolvedTypeToLLVMType(type.inner) + "*"
        }
        is ArrayResolvedType -> {
            "[${type.length} × ${resolvedTypeToLLVMType(type.elementType)}]"
        }
        is NamedResolvedType -> {
            when (type.name) {
                "String" -> "%struct.String"
                else -> "%struct.${type.name}"  // 结构体名称
            }
        }
        UnitResolvedType -> "void"
        NeverResolvedType -> "void" // 不会返回
        else -> error("Unknown type: $type")
    }
}

// 判断类型是否为无符号类型
fun isUnsignedType(type: ResolvedType): Boolean {
    return type is PrimitiveResolvedType && 
           (type.name == "u32" || type.name == "usize")
}

// 判断类型是否为有符号类型
fun isSignedType(type: ResolvedType): Boolean {
    return type is PrimitiveResolvedType && 
           (type.name == "i32" || type.name == "isize")
}
```

## 4. 节点到 IR 的映射规则

### 4.1 顶级节点 - CrateNode

**映射规则**：
- 遍历所有顶级项（函数、结构体、常量等）
- 为每个项生成对应的 IR
- 所有结构体定义优先生成，然后是全局常量，最后是函数

**示例 IR**：
```llvm
%struct.Point = type { i32, i32 }
@global_const = global i32 42

define i32 @main() {
entry:
    ret i32 0
}
```

### 4.2 函数定义 - FunctionItemNode

**映射规则**：
1. 获取函数签名（参数类型、返回类型）
2. 生成函数定义语句
3. 创建 entry 基本块
4. 为每个参数分配栈空间并存储
5. 生成函数体
6. 若无显式 return，末尾添加默认返回

**示例 IR**：
```llvm
define i32 @add_0(i32 %a, i32 %b) {
entry:
    %a_addr = alloca i32
    store i32 %a, i32* %a_addr
    %b_addr = alloca i32
    store i32 %b, i32* %b_addr
    
    %a_val = load i32, i32* %a_addr
    %b_val = load i32, i32* %b_addr
    %result = add i32 %a_val, %b_val
    ret i32 %result
}
```

**处理细节**：
- self 参数作为隐式第一个参数
- 参数命名遵循语义检查的重命名（如 `param_0_0` 格式）
- 返回类型为 `()` 时用 `void`

### 4.3 结构体定义 - StructItemNode

**映射规则**：
- 结构体生成为 LLVM 类型定义
- 方法通过单独的函数实现

**示例 IR**：
```llvm
%struct.Point_0 = type { i32, i32 }

define i32 @Point_0_method_0(i32* %self, i32 %param_0) {
entry:
    ; method body
    ret i32 0
}
```

### 4.4 变量声明 - LetStmtNode

**映射规则**：
1. 分配栈空间：`alloca`
2. 初始化赋值：`store` 初值到栈空间
3. 记录变量名到临时寄存器的映射

**示例 IR**：
```llvm
%x_addr = alloca i32
store i32 42, i32* %x_addr
```

### 4.5 字面值表达式

#### IntLiteralExprNode
```llvm
; 结果直接为整数常量，在二进制操作时内联
```

#### BooleanLiteralExprNode
```llvm
; true → i1 1, false → i1 0
```

#### StringLiteralExprNode / CharLiteralExprNode
```llvm
@.str_0 = private unnamed_addr constant [5 × i8] c"hello\00"
@.ch_0 = private unnamed_addr constant i32 65
```

### 4.6 路径表达式 - PathExprNode

**映射规则**：
- 若为变量：加载其值到临时寄存器
- 若为常量：返回常量值
- 若为函数：返回函数指针

**示例 IR**：
```llvm
%var_val = load i32, i32* %var_addr
```

### 4.7 二元表达式 - BinaryExprNode

**映射规则**：
1. 递归生成左右操作数
2. 根据操作符类型选择相应的 LLVM 指令
3. 结果存储到新的临时寄存器

**操作符映射**：
| Rx 操作符 | LLVM 指令 |
|---------|----------|
| `+` | `add` |
| `-` | `sub` |
| `*` | `mul` |
| `/` | `sdiv` / `udiv` |
| `%` | `srem` / `urem` |
| `&` | `and` |
| `\|` | `or` |
| `^` | `xor` |
| `<<` | `shl` |
| `>>` | `ashr` / `lshr` |

**示例 IR**：
```llvm
%left_val = load i32, i32* %left_addr
%right_val = load i32, i32* %right_addr
%result = add i32 %left_val, %right_val
```

### 4.8 比较表达式 - ComparisonExprNode

**映射规则**：
- 使用 `icmp` 指令比较整数
- 使用 `fcmp` 指令比较浮点数
- 结果为 i1 类型

**示例 IR**：
```llvm
%cmp = icmp slt i32 %left, %right  ; signed less than
```

### 4.9 逻辑表达式 - LazyBooleanExprNode

**映射规则**：
- `&&` 和 `||` 需要短路求值
- 使用分支和 phi 节点实现

**示例 IR (AND 操作)**：
```llvm
%left_val = load i1, i1* %left_addr
br i1 %left_val, label %and_right, label %and_false

and_right:
    %right_val = load i1, i1* %right_addr
    br label %and_end

and_false:
    br label %and_end

and_end:
    %result = phi i1 [%left_val, %entry], [%right_val, %and_right]
    ; 注意：这里需要调整以正确实现短路
```

### 4.10 赋值表达式 - AssignExprNode

**映射规则**：
1. 生成右值
2. 存储到左值地址（必须是 place）
3. 赋值表达式本身返回 unit

**示例 IR**：
```llvm
%value = load i32, i32* %right_addr
store i32 %value, i32* %left_addr
```

### 4.11 类型转换 - TypeCastExprNode

**映射规则**：
- 根据源类型和目标类型选择转换指令
- 可能需要：`trunc`、`zext`、`sext`、`bitcast` 等

**示例 IR**：
```llvm
%value = load i32, i32* %var_addr
%cast = trunc i32 %value to i1
```

### 4.12 引用表达式 - BorrowExprNode

**映射规则**：
- 不可变引用：返回变量的地址
- 可变引用：需要检查可变性，返回地址

**示例 IR**：
```llvm
; 不需要额外操作，直接返回地址
%ref = load i32*, i32** %var_ptr_addr
```

### 4.13 解引用表达式 - DerefExprNode

**映射规则**：
- 加载指针指向的值

**示例 IR**：
```llvm
%ptr = load i32*, i32** %ref_addr
%value = load i32, i32* %ptr
```

### 4.14 否定表达式 - NegationExprNode

**映射规则**：
- 算术否定：`sub 0, value`
- 逻辑否定：`xor 1, value`

**示例 IR**：
```llvm
%value = load i32, i32* %var_addr
%neg = sub i32 0, %value
```

### 4.15 数组表达式

#### ArrayListExprNode
```llvm
; 元素被顺序生成并存储到分配的数组空间
%arr = alloca [3 × i32]
%elem0_addr = getelementptr [3 × i32], [3 × i32]* %arr, i32 0, i32 0
store i32 10, i32* %elem0_addr
; ... 其他元素
```

#### ArrayLengthExprNode
```llvm
%arr = alloca [5 × i32]
; 长度直接为 5
```

#### IndexExprNode
```llvm
%arr = load [3 × i32]*, [3 × i32]** %arr_addr
%idx = load i32, i32* %index_addr
%elem_addr = getelementptr [3 × i32], [3 × i32]* %arr, i32 0, i32 %idx
%value = load i32, i32* %elem_addr
```

### 4.16 结构体表达式 - StructExprNode

**映射规则**：
1. 分配结构体所需的栈空间
2. 依次初始化每个字段

**示例 IR**：
```llvm
%struct_addr = alloca %struct.Point_0
%field0_addr = getelementptr %struct.Point_0, %struct.Point_0* %struct_addr, i32 0, i32 0
store i32 10, i32* %field0_addr
%field1_addr = getelementptr %struct.Point_0, %struct.Point_0* %struct_addr, i32 0, i32 1
store i32 20, i32* %field1_addr
```

### 4.17 字段访问 - FieldExprNode

**映射规则**：
- 使用 `getelementptr` 获取字段地址
- 加载字段值（若需要值）

**示例 IR**：
```llvm
%struct_val = load %struct.Point_0, %struct.Point_0* %struct_addr
%field_addr = getelementptr %struct.Point_0, %struct.Point_0* %struct_addr, i32 0, i32 0
%field_val = load i32, i32* %field_addr
```

### 4.18 函数调用 - CallExprNode

**映射规则**：
1. 生成所有参数值
2. 发出 call 指令
3. 接收返回值（若有）

**示例 IR**：
```llvm
%param0 = load i32, i32* %arg0_addr
%param1 = load i32, i32* %arg1_addr
%result = call i32 @add_0(i32 %param0, i32 %param1)
```

### 4.19 方法调用 - MethodCallExprNode

**映射规则**：
1. 计算 receiver（self 参数）
2. 作为第一个参数传入方法

**示例 IR**：
```llvm
%self_ptr = load %struct.Point_0*, %struct.Point_0** %receiver_addr
%param = load i32, i32* %arg_addr
%result = call i32 @Point_0_method_0(%struct.Point_0* %self_ptr, i32 %param)
```

### 4.20 条件表达式 - IfExprNode

**映射规则**：
1. 生成条件值
2. 创建 then、else（若有）和 merge 基本块
3. 条件分支到对应块
4. 每个分支末尾分支到 merge 块
5. merge 块使用 phi 节点合并结果

**示例 IR**：
```llvm
%cond = load i1, i1* %cond_addr
br i1 %cond, label %then_0, label %else_0

then_0:
    %then_val = ...
    br label %merge_0

else_0:
    %else_val = ...
    br label %merge_0

merge_0:
    %result = phi <type> [%then_val, %then_0], [%else_val, %else_0]
```

### 4.21 循环表达式

#### InfiniteLoopExprNode
```llvm
br label %loop_0

loop_0:
    ; loop body
    br label %loop_0
```

#### PredicateLoopExprNode (while 循环)
```llvm
br label %cond_0

cond_0:
    %cond = load i1, i1* %cond_addr
    br i1 %cond, label %body_0, label %end_0

body_0:
    ; loop body
    br label %cond_0

end_0:
    ; after loop
```

### 4.22 Break 和 Continue 表达式

**BreakExprNode**：
```llvm
; 分支到循环结束块
br label %loop_end_0
```

**ContinueExprNode**：
```llvm
; 分支到循环条件块（for while）或循环体首（for infinite）
br label %loop_cond_0
```

### 4.23 Return 表达式 - ReturnExprNode

**映射规则**：
1. 若有返回值，生成返回值
2. 发出 ret 指令

**示例 IR**：
```llvm
%retval = load i32, i32* %value_addr
ret i32 %retval
```

### 4.24 Block 表达式 - BlockExprNode

**映射规则**：
1. 创建新作用域（若需要）
2. 处理块内的项（函数、结构体定义等）
3. 顺序执行语句
4. 处理尾表达式（若有，其值为块的值）

**示例 IR**：
```llvm
; 块内语句
%stmt_val = ...
; 尾表达式
%tail_val = ...
; 块的最终值为 %tail_val
```

## 5. 控制流与基本块管理

### 5.1 基本块命名规范

```
<block_type>_<unique_id>

例如：
- entry
- then_0, else_0
- loop_1, cond_1
- merge_2
```

### 5.2 Phi 节点处理

对于需要汇聚多个分支的情况（if/else、循环），使用 phi 节点：

```llvm
%result = phi <type> [value1, block1], [value2, block2], ...
```

### 5.3 控制流图（CFG）构建

- 每个分支/循环结构产生新的基本块
- 确保所有路径都有明确的分支目标
- 底部类型（unreachable）处理：后续代码无法到达

## 6. 变量生命周期管理

### 6.1 栈分配

所有局部变量使用 alloca 分配：
```llvm
%var_addr = alloca <type>
```

### 6.2 加载/存储

- 读取变量：`load <type>, <type>* %var_addr`
- 写入变量：`store <type> %value, <type>* %var_addr`

### 6.3 临时寄存器命名

- 自动生成：`%0`, `%1`, `%2`, ... 或 `%temp_0`, `%temp_1`, ...
- 便于调试和追踪数据流

## 7. 特殊处理

### 7.1 常量

- 编译时已知的常量直接内联
- 字符串常量使用全局私有常量

### 7.2 不可达代码

- 标记具有 `isBottom = true` 的节点后续代码为不可达
- 优化时可直接删除

### 7.3 单元类型 ()

- 函数返回 `()` 时，发出 `ret void`
- 表达式类型为 `()`，结果被忽略

### 7.4 Never 类型

- 用于 panic、return、break 等不返回的表达式
- IR 中标记为底部类型（不产生规则的返回）

## 8. 内置函数与 String 类型处理

### 8.1 内置函数分类

Rx 编译器提供以下内置函数，这些函数需要特殊的 IR 生成处理或外部链接：

#### 系统 I/O 函数
- `exit(code: i32) -> ()` - 以指定代码退出程序
- `print(s: &str) -> ()` - 打印字符串到标准输出
- `println(s: &str) -> ()` - 打印字符串并换行
- `printInt(n: i32) -> ()` - 打印整数
- `printlnInt(n: i32) -> ()` - 打印整数并换行
- `getInt() -> i32` - 从标准输入读取整数
- `getString() -> String` - 从标准输入读取字符串

#### String 内置类型

`String` 是一个内置的可变字符串类型，在 IR 中定义为：

```llvm
%struct.String = type { /* String 的内部表示，实现定义 */ }
```

##### String 关联函数
- `String::from(s: &str) -> String` - 从字符串字面值创建 String

##### String 方法
- `String::append(&mut self, s: &str) -> ()` - 附加字符串
- `String::len(&self) -> usize` - 获取长度
- `String::as_str(&self) -> &str` - 转换为 &str
- `String::as_mut_str(&mut self) -> &mut str` - 转换为 &mut str

### 8.2 内置函数的 IR 生成

**用户定义函数**：生成 `define` 指令

```llvm
define i32 @my_function_0(i32 %param) {
entry:
    ; ...
    ret i32 %result
}
```

**内置系统函数**：生成 `declare` 指令和外部链接

```llvm
declare void @exit(i32)
declare void @print(i8*)
declare void @println(i8*)
declare void @printInt(i32)
declare void @printlnInt(i32)
declare i32 @getInt()
declare %struct.String @getString()
```

**String 方法的 IR 生成**：

```llvm
; String::from - 关联函数
define %struct.String @String_from_0(i8* %s) {
entry:
    ; 构造逻辑
    ret %struct.String %result
}

; String::append - 方法
define void @String_append_0(%struct.String* %self, i8* %s) {
entry:
    ; 附加逻辑
    ret void
}

; String::len - 方法
define i64 @String_len_0(%struct.String* %self) {
entry:
    ; 获取长度逻辑
    ret i64 %length
}

; String::as_str - 方法
define i8* @String_as_str_0(%struct.String* %self) {
entry:
    ; 转换逻辑
    ret i8* %str_ptr
}

; String::as_mut_str - 方法
define i8* @String_as_mut_str_0(%struct.String* %self) {
entry:
    ; 转换逻辑
    ret i8* %str_ptr
}
```

### 8.3 调用内置函数

调用内置函数时，生成对应的 call 指令：

```llvm
; 调用 print
%str_val = load i8*, i8** %str_addr
call void @print(i8* %str_val)

; 调用 String::from
%s_ptr = load i8*, i8** %s_addr
%string_val = call %struct.String @String_from_0(i8* %s_ptr)

; 调用 String 方法
%string_ptr = load %struct.String*, %struct.String** %string_addr
%s_ptr = load i8*, i8** %s_addr
call void @String_append_0(%struct.String* %string_ptr, i8* %s_ptr)
```

## 9. 复合赋值表达式处理

### 9.1 CompoundAssignExprNode 映射

复合赋值表达式（`+=`, `-=`, `*=` 等）在 IR 中转化为对应的二元操作和赋值：

```
a += b  =>  a = a + b
a -= b  =>  a = a - b
a *= b  =>  a = a * b
a /= b  =>  a = a / b
a %= b  =>  a = a % b
a &= b  =>  a = a & b
a |= b  =>  a = a | b
a ^= b  =>  a = a ^ b
a <<= b =>  a = a << b
a >>= b =>  a = a >> b
```

### 9.2 IR 生成规则

```llvm
; a += b 的 IR 生成示例
%a_addr = ...        ; 变量 a 的地址
%b_addr = ...        ; 变量 b 的地址

%a_val = load i32, i32* %a_addr    ; 加载 a 的值
%b_val = load i32, i32* %b_addr    ; 加载 b 的值
%result = add i32 %a_val, %b_val   ; 执行加法操作
store i32 %result, i32* %a_addr    ; 存储回 a
```

## 10. 逻辑表达式短路求值

### 10.1 &&（逻辑与）的短路求值

对于 `a && b`，正确的 IR 生成应该：
1. 先计算 a
2. 若 a 为 false，直接返回 false，不计算 b
3. 若 a 为 true，再计算 b，返回 b 的值

**正确的 IR 实现**：

```llvm
; 假设 %a_addr 和 %b_addr 是 a、b 的地址
%a_val = load i1, i1* %a_addr
br i1 %a_val, label %and_right, label %and_false

and_right:
    %right_val = load i1, i1* %right_addr
    br label %and_end

and_false:
    br label %and_end

and_end:
    %result = phi i1 [%left_val, %entry], [%right_val, %and_right]
    ; 注意：这里需要调整以正确实现短路
```

### 10.2 ||（逻辑或）的短路求值

对于 `a || b`，正确的 IR 生成应该：
1. 先计算 a
2. 若 a 为 true，直接返回 true，不计算 b
3. 若 a 为 false，再计算 b，返回 b 的值

**正确的 IR 实现**：

```llvm
; 假设 %a_addr 和 %b_addr 是 a、b 的地址
%a_val = load i1, i1* %a_addr
br i1 %a_val, label %or_true, label %or_left

or_left:
    %b_val = load i1, i1* %b_addr
    br label %or_end

or_true:
    br label %or_end

or_end:
    %result = phi i1 [1, %or_true], [%b_val, %or_left]
```

### 10.3 关键点

- 使用条件分支保证短路语义
- 在 phi 节点中正确处理常量值（false 对应 0，true 对应 1）
- 两个分支都必须收束到 merge 块

## 11. 模式处理与变量绑定

### 11.1 IdentifierPatternNode 处理

基本的标识符模式绑定变量：

```rust
let x: i32 = 5;           // 标识符模式，x 绑定到值 5
let mut y: i32 = 10;      // 可变标识符模式
```

**IR 生成**：

```llvm
%x_addr = alloca i32
store i32 5, i32* %x_addr

%y_addr = alloca i32
store i32 10, i32* %y_addr
```

### 11.2 ReferencePatternNode 处理

引用模式在变量声明中自动解引用：

```rust
let &z = &5;              // 引用模式，z 直接绑定到值（解引用）
let &mut w = &mut var;    // 可变引用模式
```

**IR 生成**：

```llvm
; let &z = &5 的处理
; 右侧 &5 计算为地址，引用模式自动解引用
%z_addr = alloca i32
store i32 5, i32* %z_addr

; let &mut w = &mut var 的处理
; 右侧返回 var 的地址，左侧绑定该地址
%w_addr = alloca i32*
store i32* %var_addr, i32** %w_addr
```

## 12. 实现步骤建议

1. **第一阶段**：基本类型和字面值
   - 实现类型映射
   - 实现字面值表达式生成

2. **第二阶段**：基本操作
   - 变量声明和访问
   - 基本算术和比较操作
   - 赋值和复合赋值

3. **第三阶段**：函数
   - 函数定义
   - 函数调用
   - 返回表��式
   - 内置函数调用

4. **第四阶段**：控制流
   - If 表达式
   - 循环表达式
   - Break/Continue
   - 短路求值

5. **第五阶段**：复杂类型
   - 数组
   - 结构体
   - 引用
   - String 类型

6. **第六阶段**：优化和完善
   - Phi 节点优化
   - 死代码消除
   - 常量折叠

## 13. 调试建议

1. **打印 IR**：在每个访问方法后输出生成的 IR
2. **验证 IR**：使用 `llvm-as` 验证生成的 IR 语法
3. **逐步测试**：用简单的测试用例逐步验证各功能
4. **追踪变量**：记录变量的地址和值的映射
5. **验证短路**：对逻辑表达式的多个分支进行测试

## 14. 示例：完整的简单程序

**Rx 源代码**：
```rust
fn add(a: i32, b: i32) -> i32 {
    a + b
}

fn main() {
    let x: i32 = 5;
    let y: i32 = 3;
    let z: i32 = add(x, y);
    z
}
```

**生成的 LLVM IR**：
```llvm
define i32 @add_0(i32 %a_0, i32 %b_0) {
entry:
    %a_0_addr = alloca i32
    store i32 %a_0, i32* %a_0_addr
    %b_0_addr = alloca i32
    store i32 %b_0, i32* %b_0_addr
    
    %a_val = load i32, i32* %a_0_addr
    %b_val = load i32, i32* %b_0_addr
    %result = add i32 %a_val, %b_val
    ret i32 %result
}

define i32 @main() {
entry:
    %x_addr = alloca i32
    store i32 5, i32* %x_addr
    
    %y_addr = alloca i32
    store i32 3, i32* %y_addr
    
    %x_val = load i32, i32* %x_addr
    %y_val = load i32, i32* %y_addr
    %z_val = call i32 @add_0(i32 %x_val, i32 %y_val)
    
    %z_addr = alloca i32
    store i32 %z_val, i32* %z_addr
    
    %ret = load i32, i32* %z_addr
    ret i32 %ret
}
```

## 15. 优化机会

- **常量折叠**：编译时计算常量表达式
- **死代码消除**：移除不可达代码
- **变量合并**：避免冗余的 alloca/load/store
- **内联**：简单函数的内联优化
- **短路优化**：对逻辑表达式的分支预测优化
