package ir

import ast.ASTVisitor
import ast.ArrayLengthExprNode
import ast.ArrayListExprNode
import ast.ArrayResolvedType
import ast.AssignExprNode
import ast.BinaryExprNode
import ast.BlockExprNode
import ast.BooleanLiteralExprNode
import ast.BorrowExprNode
import ast.BreakExprNode
import ast.CStringLiteralExprNode
import ast.CallExprNode
import ast.CharLiteralExprNode
import ast.ComparisonExprNode
import ast.CompoundAssignExprNode
import ast.ConstantItemNode
import ast.ConstantSymbol
import ast.ContinueExprNode
import ast.CrateNode
import ast.DerefExprNode
import ast.EmptyStmtNode
import ast.EnumItemNode
import ast.ExprStmtNode
import ast.FieldExprNode
import ast.FunctionItemNode
import ast.FunctionScope
import ast.FunctionSymbol
import ast.GroupedExprNode
import ast.IfExprNode
import ast.ImplItemNode
import ast.IndexExprNode
import ast.InfiniteLoopExprNode
import ast.IntLiteralExprNode
import ast.LazyBooleanExprNode
import ast.LetStmtNode
import ast.MethodCallExprNode
import ast.NamedResolvedType
import ast.NegationExprNode
import ast.PathExprNode
import ast.PredicateLoopExprNode
import ast.PrimitiveResolvedType
import ast.RawCStringLiteralExprNode
import ast.RawStringLiteralExprNode
import ast.ResolvedType
import ast.ReturnExprNode
import ast.ScopeTree
import ast.StringLiteralExprNode
import ast.StructExprNode
import ast.StructItemNode
import ast.StructSymbol
import ast.TokenType
import ast.TraitItemNode
import ast.TypeCastExprNode
import ast.UnknownResolvedType
import ast.VariableSymbol
import ast.stringToChar
import ast.stringToUInt
import exception.IRException
import exception.SemanticException
import llvm.Argument
import llvm.ArrayType
import llvm.Function
import llvm.I1Type
import llvm.IRBuilder
import llvm.IntegerType
import llvm.LLVMContext
import llvm.Module
import llvm.IRType
import llvm.StructType
import llvm.I32Type
import llvm.I8Type
import llvm.PointerType
import llvm.Value
import llvm.VoidType

class ASTLower(
    private val scopeTree: ScopeTree,
    private val context: LLVMContext,
    private val module: Module,
    private val builder: IRBuilder
) : ASTVisitor {
    // 当前函数的返回缓冲区指针（用于返回 struct/array 的函数）
    // 如果当前函数返回 struct/array，则此变量保存传入的返回缓冲区指针（第一个参数）
    // 否则为 null
    private var currentReturnBufferPtr: Value? = null

    private fun getArrayCopySize(arrayType: ArrayType): Value {
        val elementSize = getElementSize(arrayType.elementType)
        val arrayLength = arrayType.numElements
        val totalSize = elementSize * arrayLength
        return context.myGetIntConstant(context.myGetI32Type(), totalSize.toUInt())
    }

    // 元素大小计算（字节）
    private fun getElementSize(type: IRType): Int {
        return when (type) {
            is I1Type -> 1
            is I8Type -> 1
            is I32Type -> 4
            is PointerType -> 4
            is StructType -> throw IRException("StructType size must be calculated from sizeFunc")
            is ArrayType -> type.numElements * getElementSize(type.elementType)
            else -> throw IRException("Unknown type size: $type")
        }
    }

    private fun isUnsignedType(type: ResolvedType): Boolean {
        return when (type) {
            is PrimitiveResolvedType -> type.name in listOf("u32", "usize")
            else -> false
        }
    }

    /**
     * 获取左值表达式的地址（用于赋值操作）
     * 不同于 visitExpr 会对变量进行 load，此方法直接返回变量的地址
     * 
     * 支持的左值类型：
     * - PathExprNode: 变量引用，返回 alloca/参数地址
     * - FieldExprNode: 字段访问，返回字段的 GEP 地址（TODO: 需完整实现）
     * - IndexExprNode: 数组索引，返回元素的 GEP 地址（TODO: 需完整实现）
     * - DerefExprNode: 解引用，返回解引用后的地址
     */
    private fun getLValueAddress(node: ast.ExprNode): Value {
        return when (node) {
            is PathExprNode -> {
                // 变量引用：直接返回变量的地址
                val symbol = node.symbol
                if (symbol is VariableSymbol) {
                    symbol.irValue
                        ?: throw IRException("Variable '${symbol.name}' has no IR value")
                } else {
                    throw IRException("Cannot get address of non-variable path expression")
                }
            }
            is ast.FieldExprNode -> {
                // 字段访问：获取基础结构体的地址，然后 GEP 到字段
                // TODO: 完整实现需要：
                // 1. 获取结构体的基址（可能需要递归调用 getLValueAddress）
                // 2. 查找字段索引
                // 3. 生成 GEP 指令
                throw IRException("FieldExpr as lvalue is not yet fully implemented")
            }
            is ast.IndexExprNode -> {
                // 数组索引：获取数组基址，然后 GEP 到元素
                // TODO: 完整实现需要：
                // 1. 获取数组的基址
                // 2. 计算索引表达式的值
                // 3. 生成 GEP 指令
                throw IRException("IndexExpr as lvalue is not yet fully implemented")
            }
            is ast.DerefExprNode -> {
                // 解引用：对于 *ptr，ptr 的值就是目标地址
                // 需要先求值 ptr 表达式，其结果就是要写入的地址
                node.expr.accept(this)
                node.expr.irValue 
                    ?: throw IRException("DerefExpr inner expression has no IR value")
            }
            else -> throw IRException("Expression is not a valid lvalue: ${node.type}")
        }
    }

    override fun visitCrate(node: CrateNode) {
        scopeTree.currentScope = node.scopePosition!!

        // 依次visit每个item
        for (item in node.items) {
            item.accept(this)
        }
    }

    override fun visitStructItem(node: StructItemNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        // nothing to do
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitEnumItem(node: EnumItemNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        scopeTree.currentScope = previousScope // 还原scope状态

        throw IRException("Enum not supported yet")
    }

    override fun visitConstantItem(node: ConstantItemNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        // nothing to do

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitFunctionItem(node: FunctionItemNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!!

        // 获取函数符号以获取返回类型信息
        val fnName = node.fnName.value
        val funcSymbol = scopeTree.lookup(fnName)
        if (funcSymbol == null || funcSymbol !is FunctionSymbol) {
            throw SemanticException("missing FunctionSymbol")
        }

        if (node.body != null) {
            // 获取原始返回类型的 IR 类型
            val originalReturnType = getIRType(context, funcSymbol.returnType)

            // 检查是否需要使用 struct/array 返回 ABI
            // 如果返回类型是 struct 或 array，需要：
            // 1. 将返回类型改为 void
            // 2. 添加第一个参数作为返回缓冲区指针（caller-allocated）
            // 3. 在函数体内将结果写入该指针
            // 4. 最后 ret void
            if (originalReturnType.isAggregate()) {
                // TODO: 完整实现函数 IR 生成时：
                // - 生成的函数签名：第一个参数为 ptr 类型（返回缓冲区指针）
                // - 实际返回类型变为 void
                // - 在函数入口保存 ret_ptr 参数到 currentReturnBufferPtr
                // - 在 return 语句处，将值写入 currentReturnBufferPtr 并 ret void

                // 当前仅标记并遍历函数体
                // 保存返回缓冲区指针（实际实现时从第一个参数获取）
                // currentReturnBufferPtr = firstArgument // 需要在函数 IR 生成时设置
            }

            visitBlockExpr(node.body, createScope = false)

            // 清理返回缓冲区指针
            currentReturnBufferPtr = null
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitTraitItem(node: TraitItemNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!!

        for (item in node.items) {
            item.accept(this)
        }

        scopeTree.currentScope = previousScope // 还原scope状态

        throw IRException("Trait not supported yet")
    }

    override fun visitImplItem(node: ImplItemNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!!

        for (item in node.associatedItems) {
            item.accept(this)
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitBlockExpr(node: BlockExprNode, createScope: Boolean) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        for (item in node.items) {
            item.accept(this)
        }
        for (stmt in node.statements) {
            stmt.accept(this)
        }
        node.tailExpr?.accept(this)

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitPredicateLoopExpr(node: PredicateLoopExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.condition.accept(this)
        visitBlockExpr(node.block, createScope = false)

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitInfiniteLoopExpr(node: InfiniteLoopExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        visitBlockExpr(node.block, createScope = false)

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitEmptyStmt(node: EmptyStmtNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        // nothing to do
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitLetStmt(node: LetStmtNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        val varType = getIRType(context, node.variableResolvedType)
        val allocaInst = builder.createAlloca(varType) // 分配变量的栈空间，由builder自己取名

        val variableSymbol = node.symbol as? VariableSymbol
            ?: throw IRException("LetStmtNode's symbol is not VariableSymbol")
        // 根据类型选择初始化策略
        when (varType) {
            is StructType -> {
                // 结构体：使用 memcpy
                node.value.accept(this)
                val srcAddr = node.value.irValue
                    ?: throw IRException("IR Value not initialized in ${node.value}")
                // 从变量的resolved type获取结构体名称
                val structName = (node.variableResolvedType as? NamedResolvedType)?.name
                    ?: throw IRException("LetStmtNode's variableResolvedType is not NamedResolvedType")
                val sizeFunc = module.myGetFunction("${structName}.size")
                    ?: throw IRException("missing sizeFunc for struct '$structName'")
                val size = builder.createCall(sizeFunc, emptyList()) // i32的Value
                builder.createMemCpy(allocaInst, srcAddr, size, false)
            }

            is ArrayType -> {
                // 数组：使用 memcpy
                node.value.accept(this)
                val srcAddr = node.value.irValue
                    ?: throw IRException("IR Value not initialized in ${node.value}")
                val size = getArrayCopySize(varType) // i32的Value
                builder.createMemCpy(allocaInst, srcAddr, size, false)
            }

            else -> {
                // 标量：使用 load + store
                node.value.accept(this)
                val initValue = node.value.irValue // 一般类型会给一个值
                    ?: throw IRException("IR Value not initialized in ${node.value}")
                builder.createStore(initValue, allocaInst)
            }
        }
        // 将生成的 IR Value 绑定到 VariableSymbol
        // LetStmtNode 包含一个 symbol 字段，可绑定到所创建的 VariableSymbol
        // 在 IR 生成阶段，需要把 alloca 的结果（变量的地址）挂到该 symbol 上
        variableSymbol.irValue = allocaInst  // 绑定 IR Value 到 symbol

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitExprStmt(node: ExprStmtNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.expr.accept(this)

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitIntLiteralExpr(node: IntLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        // 去掉类型后缀，得到纯数字部分
        val rawValue = when {
            node.raw.endsWith("isize") -> node.raw.removeSuffix("isize")
            node.raw.endsWith("usize") -> node.raw.removeSuffix("usize")
            node.raw.endsWith("i32") -> node.raw.removeSuffix("i32")
            node.raw.endsWith("u32") -> node.raw.removeSuffix("u32")
            else -> node.raw
        }
        val value = stringToUInt(rawValue)
        val intConstant = context.myGetIntConstant(context.myGetI32Type(), value)
        node.irValue = intConstant // 保存IR Value

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitCharLiteralExpr(node: CharLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        // not support char yet
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitStringLiteralExpr(node: StringLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        // not support str yet
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitBooleanLiteralExpr(node: BooleanLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        // Convert boolean literal to IR constant (i1)
        val value = if (node.raw == "true") 1U else 0U
        val boolConstant = context.myGetIntConstant(context.myGetI1Type(), value)
        node.irValue = boolConstant

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitCStringLiteralExpr(node: CStringLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        // not support str yet
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitRawStringLiteralExpr(node: RawStringLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        // not support str yet
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitRawCStringLiteralExpr(node: RawCStringLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        // not support str yet
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitPathExpr(node: PathExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        // 根据已绑定的符号类型生成相应的 IR
        // PathExprNode.symbol 在语义分析阶段已绑定
        when (val symbol = node.symbol) {
            is VariableSymbol -> {
                // 变量引用：从符号获取变量地址（alloca/argument），生成 load 指令
                val varAddress = symbol.irValue
                    ?: throw IRException("Variable '${symbol.name}' has no IR value (not initialized)")
                val varType = getIRType(context, symbol.type)
                
                // 根据类型决定处理方式：
                // - 标量类型：生成 load 指令获取值
                // - 聚合类型（struct/array）：直接使用地址，不 load（因为聚合类型按地址传递）
                when (varType) {
                    is llvm.StructType, is llvm.ArrayType -> {
                        // 聚合类型：irValue 是地址，直接使用
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
                // 常量引用：常量在 structDefiner 阶段已生成为全局变量
                // 从模块获取全局常量并 load
                val constType = getIRType(context, symbol.type)
                val globalVar = module.myGetGlobalVariable(symbol.name)
                    ?: throw IRException("Constant '${symbol.name}' not found in module")
                val loadInst = builder.createLoad(constType, globalVar)
                node.irValue = loadInst
            }
            is FunctionSymbol -> {
                // 函数引用：用于函数调用，此处不需要生成 load
                // 函数的 IR 处理在 visitCallExpr 中完成
                // 这里可以将函数对象存储，但当前架构中 CallExpr 直接通过名称查找函数
                node.irValue = null
            }
            null -> {
                // 符号未绑定，可能是在 semantic pass 之前调用或其他错误
                throw IRException("PathExpr symbol is not bound: ${node.first.segment.value}")
            }
            else -> {
                // 其他类型符号（如 StructSymbol 用于类型路径），不需要生成值
                node.irValue = null
            }
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitBorrowExpr(node: BorrowExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.expr.accept(this)

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitDerefExpr(node: DerefExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.expr.accept(this)

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitNegationExpr(node: NegationExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.expr.accept(this)

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitBinaryExpr(node: BinaryExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        // First, lower the operands
        node.left.accept(this)
        node.right.accept(this)
        val leftValue = node.left.irValue
            ?: throw IRException("Left operand of binary expression has no IR value")
        val rightValue = node.right.irValue
            ?: throw IRException("Right operand of binary expression has no IR value")

        // Generate the appropriate binary operation based on operator type
        val result = when (node.operator.type) {
            TokenType.Add -> builder.createAdd(leftValue, rightValue)
            TokenType.SubNegate -> builder.createSub(leftValue, rightValue)
            TokenType.Mul -> builder.createMul(leftValue, rightValue)

            // 除法：根据符号选择指令
            TokenType.Div -> if (isUnsignedType(node.left.resolvedType)) {
                builder.createUDiv(leftValue, rightValue)
            } else {
                builder.createSDiv(leftValue, rightValue)
            }

            // 取余：根据符号选择指令
            TokenType.Mod -> if (isUnsignedType(node.left.resolvedType)) {
                builder.createURem(leftValue, rightValue)
            } else {
                builder.createSRem(leftValue, rightValue)
            }

            // 右移：根据符号选择指令
            TokenType.Shr -> if (isUnsignedType(node.left.resolvedType)) {
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

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitComparisonExpr(node: ComparisonExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.left.accept(this)
        node.right.accept(this)

        // Get the IR values for operands
        val leftValue = node.left.irValue
            ?: throw IRException("Left operand of comparison expression has no IR value")
        val rightValue = node.right.irValue
            ?: throw IRException("Right operand of comparison expression has no IR value")

        // Generate the appropriate comparison operation based on operator type
        val result = when (node.operator.type) {
            // 相等比较：符号无关
            TokenType.Eq -> builder.createICmpEQ(leftValue, rightValue)
            TokenType.Neq -> builder.createICmpNE(leftValue, rightValue)

            // 不等比较：根据符号选择指令
            TokenType.Lt -> if (isUnsignedType(node.left.resolvedType)) {
                builder.createICmpULT(leftValue, rightValue)
            } else {
                builder.createICmpSLT(leftValue, rightValue)
            }

            TokenType.Le -> if (isUnsignedType(node.left.resolvedType)) {
                builder.createICmpULE(leftValue, rightValue)
            } else {
                builder.createICmpSLE(leftValue, rightValue)
            }

            TokenType.Gt -> if (isUnsignedType(node.left.resolvedType)) {
                builder.createICmpUGT(leftValue, rightValue)
            } else {
                builder.createICmpSGT(leftValue, rightValue)
            }

            TokenType.Ge -> if (isUnsignedType(node.left.resolvedType)) {
                builder.createICmpUGE(leftValue, rightValue)
            } else {
                builder.createICmpSGE(leftValue, rightValue)
            }

            else -> throw IRException("Unknown comparison operator: ${node.operator.type}")
        }

        node.irValue = result

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitLazyBooleanExpr(node: LazyBooleanExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        // 短路布尔表达式的实现：
        // 对于 && (AND): 如果左侧为 false，直接返回 false，不求值右侧
        // 对于 || (OR): 如果左侧为 true，直接返回 true，不求值右侧
        //
        // 生成的控制流：
        // entry:
        //   %left = ... (求值左表达式)
        //   br i1 %left, label %eval_right_or_short, label %short_or_eval_right
        // eval_right (仅当需要求值右侧时):
        //   %right = ... (求值右表达式)
        //   br label %merge
        // merge:
        //   %result = phi i1 [%short_result, %entry], [%right, %eval_right]

        // 首先求值左侧表达式
        node.left.accept(this)
        val leftValue = node.left.irValue
            ?: throw IRException("Left operand of lazy boolean expression has no IR value")

        // 获取当前函数以创建新的基本块
        val currentFunc = builder.myGetInsertFunction()
        if (currentFunc == null) {
            throw IRException("the lazy boolean expr '$node' is not in a Function")
        } else {
            // 记录短路分支的来源块（当前块，在创建条件分支之前获取）
            val shortCircuitBB = builder.myGetInsertBlock()
                ?: throw IRException("Cannot get current basic block")

            // 创建基本块用于短路求值
            val evalRightBB = currentFunc.createBasicBlock("lazy_eval_right")
            val mergeBB = currentFunc.createBasicBlock("lazy_merge")

            // 根据操作符类型决定短路逻辑
            when (node.operator.type) {
                TokenType.And -> {
                    // AND: 左侧为 true 时求值右侧，否则短路返回 false
                    builder.createCondBr(leftValue, evalRightBB, mergeBB)
                }

                TokenType.Or -> {
                    // OR: 左侧为 false 时求值右侧，否则短路返回 true
                    builder.createCondBr(leftValue, mergeBB, evalRightBB)
                }

                else -> throw IRException("Unknown lazy boolean operator: ${node.operator.type}")
            }

            // 设置插入点到 eval_right 块，求值右侧表达式
            builder.setInsertPoint(evalRightBB)
            node.right.accept(this)
            val rightValue = node.right.irValue
                ?: throw IRException("Right operand of lazy boolean expression has no IR value")

            // 跳转到合并块
            builder.createBr(mergeBB)

            // 记录求值右侧后的块（可能与 evalRightBB 不同，如果右侧表达式创建了新块）
            val evalRightEndBB = builder.myGetInsertBlock() ?: evalRightBB

            // 设置插入点到合并块，创建 PHI 节点
            builder.setInsertPoint(mergeBB)
            val phi = builder.createPHI(context.myGetI1Type())

            // 添加 PHI 的输入值
            when (node.operator.type) {
                TokenType.And -> {
                    // AND 短路: 左侧为 false 时结果为 false
                    val falseConst = context.myGetIntConstant(context.myGetI1Type(), 0U)
                    phi.addIncoming(falseConst, shortCircuitBB) // 直接从左边跳转过来
                    phi.addIncoming(rightValue, evalRightEndBB) // 求完右边值跳转过来
                }

                TokenType.Or -> {
                    // OR 短路: 左侧为 true 时结果为 true
                    val trueConst = context.myGetIntConstant(context.myGetI1Type(), 1U)
                    phi.addIncoming(trueConst, shortCircuitBB) // 直接从左边跳转过来
                    phi.addIncoming(rightValue, evalRightEndBB) // 求完右边值跳转过来
                }

                else -> throw IRException("Unknown lazy boolean operator: ${node.operator.type}")
            }

            node.irValue = phi
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitTypeCastExpr(node: TypeCastExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        // 递归降低被转换的表达式
        node.expr.accept(this)
        val srcValue = node.expr.irValue
            ?: throw IRException("Source expression of type cast has no IR value")

        val srcType = node.expr.resolvedType
        val dstType = node.targetResolvedType

        // 不实现 char 类型转换
        if (srcType == PrimitiveResolvedType("char")) {
            throw IRException("Type cast from char is not supported")
        }
        // 相同类型，直接返回原值
        if (srcType == dstType) {
            node.irValue = srcValue
            scopeTree.currentScope = previousScope
            return
        }

        // 检查源类型是否为 bool
        val srcIsBool = srcType == PrimitiveResolvedType("bool")
        // 检查目标类型是否为整数类型 (i32/u32/isize/usize)
        val dstIsInteger = dstType is PrimitiveResolvedType &&
                dstType.name in listOf("i32", "u32", "isize", "usize")
        // 检查源类型是否为整数类型 (i32/u32/isize/usize)
        val srcIsInteger = srcType is PrimitiveResolvedType &&
                srcType.name in listOf("i32", "u32", "isize", "usize")

        when {
            // bool -> i32/u32/isize/usize: 使用 zext 指令
            srcIsBool && dstIsInteger -> {
                val dstIRType = getIRType(context, dstType)
                val result = builder.createZExt(dstIRType, srcValue)
                node.irValue = result
            }

            // i32/u32/isize/usize 之间互转：位宽相同，不需要变换
            srcIsInteger && dstIsInteger -> {
                // 位宽都是 32 位，直接使用原值
                node.irValue = srcValue
            }

            else -> {
                throw IRException("Unsupported type cast from ${srcType.name} to ${dstType.name}")
            }
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitAssignExpr(node: AssignExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        // 赋值表达式处理流程：
        // 1. 获取左值的地址（不 load，直接取地址）
        // 2. 计算右值
        // 3. 根据类型选择存储策略（标量用 store，聚合用 memcpy）

        // 获取左值地址
        val dstPtr = getLValueAddress(node.left)

        // 获取赋值类型
        val assignType = getIRType(context, node.left.resolvedType)

        when (assignType) {
            is StructType -> {
                // 结构体：使用 memcpy
                node.right.accept(this)
                val srcAddr = node.right.irValue
                    ?: throw IRException("IR Value not initialized in ${node.right}")
                // 从左值的 resolvedType 获取结构体名称
                val structName = (node.left.resolvedType as? NamedResolvedType)?.name
                    ?: throw IRException("AssignExpr left side is not NamedResolvedType")
                val sizeFunc = module.myGetFunction("${structName}.size")
                    ?: throw IRException("missing sizeFunc for struct '$structName'")
                val size = builder.createCall(sizeFunc, emptyList())
                builder.createMemCpy(dstPtr, srcAddr, size, false)
            }

            is ArrayType -> {
                // 数组：使用 memcpy
                node.right.accept(this)
                val srcAddr = node.right.irValue
                    ?: throw IRException("IR Value not initialized in ${node.right}")
                val size = getArrayCopySize(assignType)
                builder.createMemCpy(dstPtr, srcAddr, size, false)
            }

            else -> {
                // 标量类型：使用 store
                node.right.accept(this)
                val value = node.right.irValue
                    ?: throw IRException("IR Value not initialized in ${node.right}")
                builder.createStore(value, dstPtr)
            }
        }

        // 赋值表达式返回 unit 类型，不需要返回有意义的值
        node.irValue = null

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitCompoundAssignExpr(node: CompoundAssignExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        // 复合赋值处理流程（如 x += 1）：
        // 1. 获取左值地址
        // 2. 从左值地址 load 当前值
        // 3. 计算右值
        // 4. 执行运算
        // 5. store 结果到左值地址

        // 获取左值地址
        val ptr = getLValueAddress(node.left)
        val varType = getIRType(context, node.left.resolvedType)

        // Load 当前值
        val currentValue = builder.createLoad(varType, ptr)

        // 计算右值
        node.right.accept(this)
        val rightValue = node.right.irValue
            ?: throw IRException("Right operand of compound assign has no IR value")

        // 执行运算
        val result = when (node.operator.type) {
            TokenType.AddAssign -> builder.createAdd(currentValue, rightValue)
            TokenType.SubAssign -> builder.createSub(currentValue, rightValue)
            TokenType.MulAssign -> builder.createMul(currentValue, rightValue)
            TokenType.DivAssign -> if (isUnsignedType(node.left.resolvedType)) {
                builder.createUDiv(currentValue, rightValue)
            } else {
                builder.createSDiv(currentValue, rightValue)
            }
            TokenType.ModAssign -> if (isUnsignedType(node.left.resolvedType)) {
                builder.createURem(currentValue, rightValue)
            } else {
                builder.createSRem(currentValue, rightValue)
            }
            TokenType.AndAssign -> builder.createAnd(currentValue, rightValue)
            TokenType.OrAssign -> builder.createOr(currentValue, rightValue)
            TokenType.XorAssign -> builder.createXor(currentValue, rightValue)
            TokenType.ShlAssign -> builder.createShl(currentValue, rightValue)
            TokenType.ShrAssign -> if (isUnsignedType(node.left.resolvedType)) {
                builder.createLShr(currentValue, rightValue)
            } else {
                builder.createAShr(currentValue, rightValue)
            }
            else -> throw IRException("Unknown compound assign operator: ${node.operator.type}")
        }

        // Store 结果
        builder.createStore(result, ptr)

        // 复合赋值表达式返回 unit 类型
        node.irValue = null

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitGroupedExpr(node: GroupedExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.inner.accept(this)

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitArrayListExpr(node: ArrayListExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        for (element in node.elements) {
            element.accept(this)
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitArrayLengthExpr(node: ArrayLengthExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.element.accept(this)
        node.lengthExpr.accept(this)

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitIndexExpr(node: IndexExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope\

        node.base.accept(this)
        node.index.accept(this)

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitStructExpr(node: StructExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.path.accept(this)
        for (field in node.fields) {
            field.value.accept(this)
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitCallExpr(node: CallExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.func.accept(this)
        for (param in node.params) {
            param.accept(this)
        }

        // 获取调用的返回类型
        val returnType = getIRType(context, node.resolvedType)

        // 如果返回类型是 struct 或 array，需要使用 caller-allocated buffer ABI：
        // 1. 调用方在调用前 alloca 一块返回空间
        // 2. 将该空间的指针作为第一个参数传入
        // 3. 函数调用返回 void，实际结果写入该缓冲区
        // 4. 调用结束后，缓冲区指针即为结果的地址
        if (returnType.isAggregate()) {
            // TODO: 完整实现调用 IR 生成时：
            // val retAlloca = builder.createAlloca(returnType, "call_ret")
            // val args = mutableListOf<Value>(retAlloca)
            // args.addAll(loweredArgs) // 添加实际参数
            // builder.createCall(func, args) // 调用返回 void
            // node.irValue = retAlloca // 结果位于 retAlloca

            // 当前仅设置 irValue 为 null，表示待实现
            node.irValue = null
        } else {
            // 标量类型直接返回调用结果
            // TODO: val callResult = builder.createCall(func, loweredArgs, "call_result")
            // node.irValue = callResult
            node.irValue = null
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitMethodCallExpr(node: MethodCallExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.receiver.accept(this)
        for (param in node.params) {
            param.accept(this)
        }

        // 方法调用也需要处理 struct/array 返回 ABI
        // 逻辑与 visitCallExpr 类似
        val returnType = getIRType(context, node.resolvedType)
        if (returnType.isAggregate()) {
            // TODO: 完整实现时需要：
            // 1. 分配返回缓冲区
            // 2. 将缓冲区指针作为第一个参数传入
            // 3. 调用方法并设置 irValue 为缓冲区指针
            node.irValue = null
        } else {
            node.irValue = null
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitFieldExpr(node: FieldExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.struct.accept(this)

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitIfExpr(node: IfExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.condition.accept(this)
        node.thenBranch.accept(this)
        node.elseBranch?.accept(this)

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitBreakExpr(node: BreakExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.value?.accept(this)

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitContinueExpr(node: ContinueExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        // nothing to do
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitReturnExpr(node: ReturnExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.value?.accept(this)

        // 如果当前函数使用 struct/array 返回 ABI（currentReturnBufferPtr 不为空）
        // 则需要将返回值写入返回缓冲区，而非直接 ret value
        if (currentReturnBufferPtr != null && node.value != null) {
            val returnValue = node.value.irValue
            if (returnValue != null) {
                val returnType = getIRType(context, node.value.resolvedType)
                // TODO: 完整实现时：
                // 1. 如果是 struct，使用 memcpy 将值复制到 currentReturnBufferPtr
                // 2. 如果是 array，同样使用 memcpy
                // 3. 然后 ret void
                // 示例伪代码：
                // when (returnType) {
                //     is StructType -> {
                //         val structName = (node.value.resolvedType as NamedResolvedType).name
                //         val sizeFunc = module.myGetFunction("$structName.size")!!
                //         val size = builder.createCall(sizeFunc, emptyList())
                //         builder.createMemCpy(currentReturnBufferPtr!!, returnValue, size, false)
                //     }
                //     is ArrayType -> {
                //         val size = getArrayCopySize(returnType)
                //         builder.createMemCpy(currentReturnBufferPtr!!, returnValue, size, false)
                //     }
                // }
                // builder.createRet(null) // ret void
            }
        } else {
            // 标量类型直接返回
            // TODO: builder.createRet(node.value?.irValue)
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }
}