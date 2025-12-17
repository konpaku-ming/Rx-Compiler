# Testing Guide

本文档介绍 Rx-Compiler 项目中的两种批量测试方式。

## 概述

项目提供两种批量测试：

1. **语义阶段测试** - 测试 `tests/` 目录中的文件，验证编译器在语义分析阶段的正确性
2. **LLVM IR 端到端测试** - 测试 `IR-1/` 目录中的文件，验证完整的编译、链接、执行流程

## 1. 语义阶段测试

### 测试内容

- 位置：`tests/pass/` 和 `tests/fail/` 目录
- 测试阶段：词法分析 → 语法分析 → 语义分析（五遍扫描）
- 测试目标：
  - `tests/pass/` 中的文件应该通过所有语义检查
  - `tests/fail/` 中的文件应该在语义检查阶段报错

### 运行方式

```bash
# 基本运行（显示所有测试）
./gradlew runTestRunner

# 静默模式（仅显示失败和摘要）
./gradlew runTestRunner -PtrArgs="--quiet"

# 遇到第一个失败时停止
./gradlew runTestRunner -PtrArgs="--fail-fast"

# 显示源代码预览
./gradlew runTestRunner -PtrArgs="--show-source"

# 组合使用
./gradlew runTestRunner -PtrArgs="--quiet --fail-fast"
```

### 测试结果示例

```
Discovered cases: PASS dir 217 cases, FAIL dir 119 cases
======== Test Summary ========
Total cases: 336
Expected PASS: 217  Expected FAIL: 119
Matches with expectation: 336
Mismatches with expectation: 0
All cases behaved as expected.
```

## 2. LLVM IR 端到端测试

### 测试内容

- 位置：`IR-1/` 目录
- 测试文件组：每个测试包含三个文件
  - `testname.rx` - Rx 源代码
  - `testname.in` - 标准输入
  - `testname.out` - 期望的标准输出
- 测试流程：
  1. 编译 `.rx` 文件生成 LLVM IR
  2. 使用 clang 将 IR 与 `builtin.c` 编译为可执行文件
  3. 运行可执行文件，提供 `.in` 文件作为输入
  4. 比较实际输出与 `.out` 文件内容

### 环境要求

- 必须安装 clang（推荐 clang-15 或更高版本）
- 测试会自动查找 `clang-15` 或 `clang` 命令

### 运行方式

```bash
# 基本运行（显示所有测试）
./gradlew runIRTestRunner

# 静默模式（仅显示摘要）
./gradlew runIRTestRunner -PirArgs="--quiet"

# 遇到第一个失败时停止
./gradlew runIRTestRunner -PirArgs="--fail-fast"

# 组合使用
./gradlew runIRTestRunner -PirArgs="--quiet --fail-fast"
```

### 测试结果示例

```
Discovered 50 test cases in IR-1 directory
[PASS] 1/50  comprehensive1
[PASS] 2/50  comprehensive10
...
[PASS] 50/50  comprehensive9

======== IR Test Summary ========
Total tests: 50
Passed: 50
Failed: 0

All tests passed!
```

## 测试实现细节

### 语义阶段测试（TestRunner.kt）

实现位于 `src/test/kotlin/TestRunner.kt`，主要功能：

- 遍历 `tests/pass` 和 `tests/fail` 目录
- 对每个 `.rx` 文件执行编译流程直到语义分析完成
- 捕获 `CompilerException` 异常
- 比较实际结果与预期：
  - `pass` 目录文件应成功通过
  - `fail` 目录文件应失败

### LLVM IR 端到端测试（IRTestRunner.kt）

实现位于 `src/test/kotlin/IRTestRunner.kt`，主要功能：

- 遍历 `IR-1` 目录中的所有 `.rx` 文件
- 对每个测试：
  1. 执行完整的编译流程生成 LLVM IR
  2. 调用 clang 编译 IR 与 builtin.c
  3. 在临时目录中运行可执行文件
  4. 比较输出与期望结果
- 自动清理临时文件

## 添加新测试

### 添加语义测试

1. 在 `tests/pass/` 或 `tests/fail/` 目录添加 `.rx` 文件
2. 运行测试验证

### 添加 IR 测试

1. 在 `IR-1/` 目录添加三个文件：
   - `testname.rx` - 源代码
   - `testname.in` - 输入数据
   - `testname.out` - 期望输出
2. 运行测试验证

## 持续集成

两种测试都可以集成到 CI/CD 流程中：

```bash
# 在 CI 中运行所有测试
./gradlew runTestRunner -PtrArgs="--quiet --fail-fast"
./gradlew runIRTestRunner -PirArgs="--quiet --fail-fast"
```

两个命令的退出代码为 0 表示所有测试通过，非 0 表示有测试失败。
