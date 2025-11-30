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
import ast.RawCStringLiteralExprNode
import ast.RawStringLiteralExprNode
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

    // 判断返回类型是否需要使用 caller-allocated buffer ABI
    // 结构体和数组类型需要通过指针返回，而非直接通过值返回
    private fun isAggregateReturnType(returnType: IRType): Boolean {
        return returnType is StructType || returnType is ArrayType
    }

    private fun getArrayCopySize(arrayType: ArrayType): Value {
        val elementSize = getElementSize(arrayType.elementType)
        val arrayLength = arrayType.numElements
        val totalSize = elementSize * arrayLength
        return context.myGetIntConstant(context.myGetI32Type(), totalSize)
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
        val funcScope = node.scopePosition as? FunctionScope
        val funcSymbol = funcScope?.functionSymbol

        if (funcSymbol != null && node.body != null) {
            // 获取原始返回类型的 IR 类型
            val originalReturnType = getIRType(context, funcSymbol.returnType)

            // 检查是否需要使用 struct/array 返回 ABI
            // 如果返回类型是 struct 或 array，需要：
            // 1. 将返回类型改为 void
            // 2. 添加第一个参数作为返回缓冲区指针（caller-allocated）
            // 3. 在函数体内将结果写入该指针
            // 4. 最后 ret void
            if (isAggregateReturnType(originalReturnType)) {
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
        } else if (node.body != null) {
            visitBlockExpr(node.body, createScope = false)
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
        
        // Convert integer literal to IR constant
        // Remove type suffix (e.g., i32, u32, isize, usize) and parse the value
        // Following the same pattern as semantic.kt to properly handle suffixes
        val rawValue = when {
            node.raw.endsWith("isize") -> node.raw.removeSuffix("isize")
            node.raw.endsWith("usize") -> node.raw.removeSuffix("usize")
            node.raw.endsWith("i32") -> node.raw.removeSuffix("i32")
            node.raw.endsWith("u32") -> node.raw.removeSuffix("u32")
            else -> node.raw
        }
        // Semantic analysis already validates bounds, so we can safely parse here
        val value = stringToUInt(rawValue).toInt()
        val intConstant = context.myGetIntConstant(context.myGetI32Type(), value)
        node.irValue = intConstant
        
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitCharLiteralExpr(node: CharLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        
        // Convert char literal to IR constant (i8)
        val charValue = stringToChar(node.raw)
        val charConstant = context.myGetIntConstant(context.myGetI8Type(), charValue.code)
        node.irValue = charConstant
        
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitStringLiteralExpr(node: StringLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        // nothing to do
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitBooleanLiteralExpr(node: BooleanLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        
        // Convert boolean literal to IR constant (i1)
        val value = if (node.raw == "true") 1 else 0
        val boolConstant = context.myGetIntConstant(context.myGetI1Type(), value)
        node.irValue = boolConstant
        
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitCStringLiteralExpr(node: CStringLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        // nothing to do
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitRawStringLiteralExpr(node: RawStringLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        // nothing to do
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitRawCStringLiteralExpr(node: RawCStringLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        // nothing to do
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitPathExpr(node: PathExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        // nothing to do
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
        
        // Get the IR values for operands
        val leftValue = node.left.irValue
            ?: throw IRException("Left operand of binary expression has no IR value")
        val rightValue = node.right.irValue
            ?: throw IRException("Right operand of binary expression has no IR value")
        
        // Generate the appropriate binary operation based on operator type
        val result = when (node.operator.type) {
            TokenType.Add -> builder.createAdd(leftValue, rightValue)
            TokenType.SubNegate -> builder.createSub(leftValue, rightValue)
            TokenType.Mul -> builder.createMul(leftValue, rightValue)
            TokenType.Div -> builder.createSDiv(leftValue, rightValue)  // Signed division
            TokenType.Mod -> builder.createSRem(leftValue, rightValue)  // Signed remainder
            TokenType.BitAnd -> builder.createAnd(leftValue, rightValue)
            TokenType.BitOr -> builder.createOr(leftValue, rightValue)
            TokenType.BitXor -> builder.createXor(leftValue, rightValue)
            TokenType.Shl -> builder.createShl(leftValue, rightValue)
            TokenType.Shr -> builder.createAShr(leftValue, rightValue)  // Arithmetic right shift
            else -> throw IRException("Unknown binary operator: ${node.operator.type}")
        }
        
        node.irValue = result

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitComparisonExpr(node: ComparisonExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        // First, lower the operands
        node.left.accept(this)
        node.right.accept(this)
        
        // Get the IR values for operands
        val leftValue = node.left.irValue
            ?: throw IRException("Left operand of comparison expression has no IR value")
        val rightValue = node.right.irValue
            ?: throw IRException("Right operand of comparison expression has no IR value")
        
        // Generate the appropriate comparison operation based on operator type
        val result = when (node.operator.type) {
            TokenType.Eq -> builder.createICmpEQ(leftValue, rightValue)
            TokenType.Neq -> builder.createICmpNE(leftValue, rightValue)
            TokenType.Lt -> builder.createICmpSLT(leftValue, rightValue)  // Signed less than
            TokenType.Le -> builder.createICmpSLE(leftValue, rightValue)  // Signed less than or equal
            TokenType.Gt -> builder.createICmpSGT(leftValue, rightValue)  // Signed greater than
            TokenType.Ge -> builder.createICmpSGE(leftValue, rightValue)  // Signed greater than or equal
            else -> throw IRException("Unknown comparison operator: ${node.operator.type}")
        }
        
        node.irValue = result

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitLazyBooleanExpr(node: LazyBooleanExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.left.accept(this)
        node.right.accept(this)

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitTypeCastExpr(node: TypeCastExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.expr.accept(this)

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitAssignExpr(node: AssignExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.left.accept(this)
        node.right.accept(this)

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitCompoundAssignExpr(node: CompoundAssignExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.left.accept(this)
        node.right.accept(this)

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
        if (isAggregateReturnType(returnType)) {
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
        if (isAggregateReturnType(returnType)) {
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