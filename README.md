# Rx-Compiler

Rx 语言编译器 - 一个用 Kotlin 实现的编程语言编译器，支持词法分析、语法分析、语义检查和 LLVM IR 代码生成。

## 项目状态

✅ 本项目已基本完成，实现了完整的编译器前端功能：

- **词法分析器 (Lexer)** - 将源代码转换为 token 流
- **语法分析器 (Parser)** - 生成抽象语法树 (AST)
- **语义检查** - 多遍扫描实现类型检查、作用域解析等
- **LLVM IR 生成** - 将 AST 降低为 LLVM IR

## 语言特性

Rx 语言支持以下特性：

- **基本类型**: `i32`, `u32`, `i8`, `bool`, `char`, `usize` 等
- **复合类型**: 结构体 (`struct`)、数组 (`[T; N]`)
- **引用类型**: 不可变引用 (`&T`)、可变引用 (`&mut T`)
- **控制流**: `if/else`, `while`, `loop`, `break`, `continue`, `return`
- **函数**: 带参数和返回值的函数定义
- **方法**: 结构体的 `impl` 块和方法
- **常量**: `const` 全局常量声明

### 示例代码

```rust
// 计算 1 到 n 的和
fn calculate_sum(n: i32) -> i32 {
    let mut sum: i32 = 0;
    let mut i: i32 = 1;
    while (i <= n) {
        sum += i;
        i += 1;
    }
    return sum;
}

fn main() {
    let n: i32 = getInt();
    let result: i32 = calculate_sum(n);
    printInt(result);
    exit(0);
}
```

```rust
// 结构体与方法示例
struct Point {
    x: i32,
    y: i32,
}

impl Point {
    fn new(x: i32, y: i32) -> Point {
        Point { x: x, y: y }
    }
}

fn main() {
    let p: Point = Point { x: 10, y: 20 };
    printlnInt(p.x);
    printlnInt(p.y);
    exit(0);
}
```

## 环境要求

**运行编译器（OJ 环境）：**
- **JDK 21** 或更高版本（仅需 Java 运行时，无需 Gradle 或 Kotlin 编译器）
- 项目包含预编译的 JAR 文件，可直接使用 Makefile 运行

**开发环境（修改源码）：**
- **JDK 21** 或更高版本
- **Gradle** (项目包含 Gradle Wrapper，无需单独安装)
- **LLVM** (如需执行生成的 IR，需安装 LLVM 工具链)
  - Linux/macOS: 直接安装 clang
  - Windows: 可以安装 clang 或在 WSL 中安装（测试工具支持自动检测和使用 WSL 中的 clang）

## 使用 Makefile（推荐用于 OJ 环境）

项目提供了 Makefile，可以在没有 Gradle 的环境中使用：

```bash
# 构建（使用预编译的 JAR，无需实际编译）
make build

# 运行编译器（从标准输入读取源代码）
make run < source.rx

# 或使用管道
cat source.rx | make run
```

编译器会将 LLVM IR 输出到标准输出，将 builtin.c 输出到标准错误。

## 使用 Gradle 构建（开发环境）

如果需要修改源码并重新编译：

```bash
# 使用 Gradle Wrapper 构建项目
./gradlew build

# Windows 用户
gradlew.bat build
```

### 重新生成预编译的 JAR 文件

**注意**：只有在修改了 Kotlin 源码后才需要重新生成 JAR 文件。正常使用编译器不需要此步骤。

重新生成 JAR 需要完整的开发环境（JDK 21+ 和 Gradle）：

```bash
# 1. 使用 Gradle 构建并生成发行版
./gradlew installDist

# 2. 将生成的 JAR 文件复制到 lib 目录
mkdir -p lib
cp build/install/Rx-Compiler/lib/*.jar lib/

# 3. 提交更新后的 JAR 文件
git add lib/*.jar
git commit -m "Update pre-built JARs"
```

## 使用方法

### 编译 Rx 源代码

```bash
# 运行编译器，将 Rx 源代码编译为 LLVM IR
./gradlew run --args="<source_file.rx>"

# 示例
./gradlew run --args="tests/pass/misc1.rx"
```

编译成功后，会在当前目录生成 `main.ll` 文件，包含 LLVM IR 代码。

### 执行生成的 IR

如果安装了 LLVM 工具链，可以进一步编译和执行：

```bash
# 使用 clang-15 编译 LLVM IR 到可执行文件
clang-15 -o program main.ll builtin

# 运行程序
./program
```

## 项目结构

```
Rx-Compiler/
├── src/
│   ├── main/kotlin/           # 主要源代码
│   │   ├── Main.kt            # 编译器入口
│   │   ├── ast/               # AST 相关
│   │   │   ├── lexer.kt       # 词法分析器
│   │   │   ├── parser.kt      # 语法分析器
│   │   │   ├── astNode.kt     # AST 节点定义
│   │   │   ├── token.kt       # Token 定义
│   │   │   ├── scope.kt       # 作用域管理
│   │   │   ├── semantic.kt    # 语义分析
│   │   │   ├── visitor.kt     # AST visitor-accept的接口
│   │   │   └── preprocess.kt  # 预处理
│   │   ├── ir/                # IR 生成
│   │   │   ├── astLower.kt    # AST 到 IR 降低
│   │   │   ├── preDefiner.kt  # 预定义处理
│   │   │   ├── typeConverter.kt # 类型转换
│   │   │   └── IntegerConfirmer.kt # 整数类型确认
│   │   ├── llvm/              # LLVM IR 组件
│   │   │   ├── irContext.kt   # LLVM 上下文
│   │   │   ├── irType.kt      # IR 类型定义
│   │   │   ├── irBuilder.kt   # IR 构建器
│   │   │   └── irComponents.kt # IR 组件
│   │   └── exception/         # 异常处理
│   └── test/kotlin/           # 测试代码
│       └── TestRunner.kt      # 测试运行器
├── tests/                     # 测试用例
│   ├── pass/                  # 应通过的测试
│   └── fail/                  # 应失败的测试
├── docs/                      # 文档
│   ├── Borrow_Deref_IR_Generation.md            # 借用和解引用的 IR 生成
│   ├── FunctionItem_ReturnExpr_Implementation_Plan.md # 函数和返回表达式实现计划
│   ├── Loop_BreakContinue_Implementation_Plan.md      # 循环和 break/continue 实现计划
│   ├── Signed_vs_Unsigned_Instructions.md             # 有符号与无符号指令
│   └── Testing_Guide.md                               # 测试指南
├── build.gradle.kts           # Gradle 构建配置
├── settings.gradle.kts        # Gradle 设置
├── gradlew                    # Unix Gradle Wrapper
├── gradlew.bat                # Windows Gradle Wrapper
└── README.md                  # 本文件
```

## 测试

项目包含大量测试用例，位于 `tests/` 和 `IR-1/` 目录下：

- `tests/pass/` - 应当编译成功的测试用例（语义检查阶段）
- `tests/fail/` - 应当编译失败的测试用例（用于验证错误检测）
- `IR-1/` - 端到端测试用例，包含输入输出文件

### 批量测试

#### 1. 语义阶段测试 (tests 目录)

对 `tests/` 目录中的文件运行到语义分析阶段，验证编译器能否正确接受或拒绝源代码。

```bash
# 运行所有语义测试
./gradlew runTestRunner

# 静默模式运行
./gradlew runTestRunner -PtrArgs="--quiet"

# 遇到第一个失败时停止
./gradlew runTestRunner -PtrArgs="--fail-fast"

# 显示源代码预览
./gradlew runTestRunner -PtrArgs="--show-source"
```

#### 2. LLVM IR 端到端测试 (IR-1 目录)

对 `IR-1/` 目录中的文件完整编译到 LLVM IR，与 `builtin.c` 一起使用 clang 编译，运行并比较标准输出。

```bash
# 运行所有 IR 测试
./gradlew runIRTestRunner

# 静默模式运行（仅显示摘要）
./gradlew runIRTestRunner -PirArgs="--quiet"

# 遇到第一个失败时停止
./gradlew runIRTestRunner -PirArgs="--fail-fast"
```

**注意**: 
- **测试数据自动获取**：首次运行 IRTestRunner 时，测试工具会自动从 [peterzheng98/RCompiler-Testcases](https://github.com/peterzheng98/RCompiler-Testcases) 仓库的 `IR-1/src` 目录获取测试数据，使用 Git Sparse Checkout 仅下载必要的文件。
- IR 测试需要系统安装 clang（推荐 clang-15 或更高版本）。
- Windows 用户可以在 WSL 中安装 clang，测试工具会自动检测并使用。

## 编译器架构

编译过程分为以下阶段：

1. **预处理** - 移除注释
2. **词法分析** - 源代码 → Token 流
3. **语法分析** - Token 流 → AST
4. **语义分析** - 多遍扫描
   - 第一遍：收集声明
   - 第二遍：解析类型
   - 第三遍：解析常量上下文、控制流检查
   - 第四遍：Impl-Trait检查
   - 第五遍：类型检查、其余的语义检查
5. **类型确认** - 确认整数字面量类型
6. **预定义** - 定义结构体和全局常量，声明函数
7. **IR 生成** - AST → LLVM IR
8. **输出** - 写入 `main.ll` 文件

## 内置函数

Rx 语言提供以下内置函数：

| 函数                   | 描述        |
|----------------------|-----------|
| `getInt() -> i32`    | 从标准输入读取整数 |
| `printInt(n: i32)`   | 输出整数      |
| `printlnInt(n: i32)` | 输出整数并换行   |
| `exit(code: i32)`    | 退出程序      |

## 已知限制

- 仅支持整数类型的 `const` 常量 (语义检查支持其他类型常量)
- 不支持浮点数类型 (语义检查支持)
- 不支持泛型 (语义检查支持)
- 不支持闭包和匿名函数
- 不支持模块系统

## 文档

更多技术文档请参阅 `docs/` 目录：

- [借用和解引用的 IR 生成](docs/Borrow_Deref_IR_Generation.md) - 借用和解引用相关的 IR 生成实现
- [函数和返回表达式实现计划](docs/FunctionItem_ReturnExpr_Implementation_Plan.md) - 函数项和返回表达式的实现设计
- [循环和 break/continue 实现计划](docs/Loop_BreakContinue_Implementation_Plan.md) - 循环控制流的实现设计
- [有符号与无符号指令](docs/Signed_vs_Unsigned_Instructions.md) - 有符号和无符号整数的 LLVM 指令选择
- [测试指南](docs/Testing_Guide.md) - 测试系统使用指南

## 许可证

本项目仅供学习和研究使用。

## 致谢

- LLVM 项目 - https://llvm.org/
- Kotlin - https://kotlinlang.org/
- Rust 语言设计 - 本项目的语法设计参考了 Rust，详细语法见[The Rx Reference](https://github.com/peterzheng98/RCompiler-Spec)
- 特别感谢[@algebraic-arima](https://github.com/algebraic-arima) - 为本项目的实现提供了很多建议与帮助
