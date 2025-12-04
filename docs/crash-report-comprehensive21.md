# Comprehensive21.rx 程序崩溃分析报告

## 问题描述

将 `comprehensive21.rx` 编译到 LLVM IR (`main.ll`) 后，使用 clang-15 编译成可执行文件运行时，程序在输出部分正确结果后崩溃，显示 `Segmentation fault (core dumped)`。

## 崩溃现象

程序成功输出以下内容后崩溃：
```
21001
21010
... (多行输出)
21310
Segmentation fault (core dumped)
```

程序在输出 `21310` 后崩溃，此时正在处理 `test_sizes[3] = 2000` 的排序测试。

## 根本原因

**栈溢出 (Stack Overflow)**

通过 AddressSanitizer 分析确认：

```
==3531==ERROR: AddressSanitizer: stack-overflow on address 0x7ffc89b97ff0
    #0 in bubble_sort
    #1 in test_large_scale_performance
    #2 in main
```

## 详细分析

### 1. 大型数据结构的栈分配

生成的 LLVM IR 中存在多个大型栈分配：

```llvm
; DataSet 结构体定义 - 约 40,028 字节
%struct.DataSet = type { [10000 x i32], i32, i1, i32, i32, i32, i32 }

; 大型数组分配 - 40,000 字节
%tmp.1902 = alloca [10000 x i32]
%tmp.1898 = alloca %struct.DataSet
```

### 2. 循环内部的栈分配问题

关键问题在于 `test_large_scale_performance` 函数的循环体内部存在大量 `alloca` 指令：

```llvm
while_body.1:
  %tmp.1898 = alloca %struct.DataSet      ; ~40KB
  %tmp.1902 = alloca [10000 x i32]         ; ~40KB
  ; ... 更多分配 ...
```

**问题**：LLVM IR 的 `alloca` 指令在运行时执行时会分配栈空间，且这些空间在函数返回前不会释放。当这些分配出现在循环体内时，每次迭代都会分配新的栈空间。

### 3. 内存使用累积

程序执行流程：
1. 外层循环 4 次迭代 (`test_sizes = [100, 500, 1000, 2000]`)
2. 内层循环 8 次迭代（8 种数据模式）
3. 每次迭代分配 ~80KB+ 栈空间

当处理到 `size=2000` 时（第4次外层迭代）：
- 累积的栈分配已经接近或超过默认栈大小限制（本环境为 16MB）
- 调用 `bubble_sort` 时，额外的栈分配导致栈溢出

### 4. Bubble Sort 内部的栈分配

`bubble_sort` 函数内部也存在循环内的栈分配：

```llvm
while_body.0:
  %tmp.181 = alloca i32
  %tmp.182 = alloca i1
  ; 对于 size=2000，循环执行约 2000 次
```

## 影响

- 程序在处理较大数据集（size >= 2000）时必然崩溃
- 对于小数据集可以正常运行
- 默认栈大小（本环境为 16MB）不足以支持完整测试

## 解决方案建议

### 方案 1：编译器级别修复（推荐）

修改 Rx-Compiler 的代码生成逻辑，将 `alloca` 指令提升到函数入口块：

```llvm
; 修改前：alloca 在循环体内
while_body.1:
  %tmp.1898 = alloca %struct.DataSet

; 修改后：alloca 在函数入口
entry.0:
  %tmp.1898 = alloca %struct.DataSet
  br label %while_cond.0
```

这需要修改编译器的 IR 生成逻辑，确保所有 `alloca` 指令都放在函数的入口基本块中。

### 方案 2：使用堆内存分配

对于大型数组和结构体，改用动态内存分配（`malloc`/`free`）而非栈分配：

```llvm
; 使用 malloc 代替 alloca
%ptr = call ptr @malloc(i64 40028)
; ... 使用内存 ...
call void @free(ptr %ptr)
```

### 方案 3：优化数据结构大小

减少 `MAX_ARRAY_SIZE` 常量值，或者使用更紧凑的数据表示方式。

### 方案 4：运行时增加栈大小

临时解决方案（不推荐作为最终解决）：

```bash
ulimit -s unlimited  # 或设置更大的值
./main_test
```

## 技术细节

### 栈内存使用估算

在 `test_large_scale_performance` 函数中：

| 分配类型 | 大小 | 数量 | 总计 |
|---------|------|------|------|
| `[10000 x i32]` | 40,000 B | 6 | 240,000 B |
| `%struct.DataSet` | ~40,028 B | 4 | ~160,112 B |
| 其他小型分配 | ~100 B | ~50 | ~5,000 B |

**单次函数调用最低栈需求**: ~405 KB

由于循环内部的 `alloca`，实际使用量会随迭代次数倍增。

### 调用栈深度

```
main()
└── test_large_scale_performance()
    └── bubble_sort() / quick_sort() / merge_sort()
        └── [递归调用] (quick_sort_range, merge_sort_range, heapify)
```

深层递归调用（如快速排序、堆排序）进一步增加了栈压力。

## 结论

**根本原因**：Rx-Compiler 生成的 LLVM IR 将大型数组和结构体的 `alloca` 指令放置在循环体内部，导致每次循环迭代都分配新的栈空间，最终导致栈溢出。

**建议修复**：修改编译器的 IR 生成逻辑，将所有 `alloca` 指令提升到函数入口块，这是 LLVM IR 的标准做法，也是大多数编译器前端采用的策略。

## 验证方法

使用 AddressSanitizer 编译并运行：

```bash
clang -fsanitize=address -o main_test_asan main.ll runtime.o
./main_test_asan
```

输出将清晰显示 `stack-overflow` 错误及其调用栈。

---

*报告生成日期: 2025-12-04*
