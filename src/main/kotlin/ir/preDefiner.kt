package ir

import ast.ASTVisitor
import ast.ArrayLengthExprNode
import ast.ArrayListExprNode
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
import ast.FunctionSymbol
import ast.GroupedExprNode
import ast.IdentifierPatternNode
import ast.IfExprNode
import ast.ImplItemNode
import ast.IndexExprNode
import ast.InfiniteLoopExprNode
import ast.IntLiteralExprNode
import ast.LazyBooleanExprNode
import ast.LetStmtNode
import ast.MethodCallExprNode
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
import ast.TraitItemNode
import ast.TypeCastExprNode
import ast.UnknownResolvedType
import exception.IRException
import exception.SemanticException
import llvm.Argument
import llvm.IRBuilder
import llvm.IRType
import llvm.IntegerType
import llvm.LLVMContext
import llvm.Module

class PreDefiner(
    private val scopeTree: ScopeTree,
    private val context: LLVMContext,
    private val module: Module,
    private val builder: IRBuilder
) : ASTVisitor {
    override fun visitCrate(node: CrateNode) {
        scopeTree.currentScope = node.scopePosition!!

        // 声明内置函数（由外部库提供实现）
        // 这些函数不使用ret_ptr约定，直接按原函数签名声明
        declareBuiltinFunctions()

        // 依次visit每个item
        for (item in node.items) {
            item.accept(this)
        }
    }

    /**
     * 声明外部内置函数
     * 这些函数由外部库提供实现，编译器只需要声明它们的签名
     * 注意：这些函数不使用ret_ptr约定，直接按原函数签名声明
     */
    private fun declareBuiltinFunctions() {
        // void printInt(int n)
        val printIntType = context.myGetFunctionType(
            context.myGetVoidType(),
            listOf(context.myGetI32Type())
        )
        module.myGetOrCreateFunction("printInt", printIntType)

        // void printlnInt(int n)
        val printlnIntType = context.myGetFunctionType(
            context.myGetVoidType(),
            listOf(context.myGetI32Type())
        )
        module.myGetOrCreateFunction("printlnInt", printlnIntType)

        // int getInt()
        val getIntType = context.myGetFunctionType(
            context.myGetI32Type(),
            emptyList()
        )
        module.myGetOrCreateFunction("getInt", getIntType)
    }

    override fun visitStructItem(node: StructItemNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        // 遇到 struct 就做定义
        val structName = node.structName.value
        val structSymbol = scopeTree.lookup(structName)
        if (structSymbol == null || structSymbol !is StructSymbol) {
            throw IRException("missing StructSymbol")
        } else {
            val structType = context.myGetStructType(structSymbol.name)
            for ((_, resolvedType) in structSymbol.fields) {
                if (resolvedType is UnknownResolvedType) {
                    throw IRException("Struct field type is not resolved: $resolvedType")
                }
                structType.elements.add(getIRType(context, resolvedType))
            }

            // 生成一个求该结构体大小的函数
            val sizeFuncType = context.myGetFunctionType(
                returnType = context.myGetI32Type(),
                paramTypes = listOf()
            )
            val sizeFunc = module.createFunction("${structSymbol.name}.size", sizeFuncType)
            val bodyBB = sizeFunc.createBasicBlock("body")
            builder.setInsertPoint(bodyBB)
            val gepInst = builder.createGEP(
                structType,
                context.myGetNullPtrConstant(),
                listOf(context.myGetIntConstant(context.myGetI32Type(), 1U))
            ) // GEP
            val sizeInst = builder.createPtrToInt(
                context.myGetI32Type(),
                gepInst,
            ) // PtrToInt
            builder.createRet(sizeInst) // Ret
        }

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

        // 遇到常量直接注册，只支持Int
        val constantName = node.constantName.value
        val constantSymbol = scopeTree.lookup(constantName)
        if (constantSymbol == null || constantSymbol !is ConstantSymbol) {
            throw IRException("missing ConstantSymbol")
        } else {
            val name = constantSymbol.name
            val type = getIRType(context, constantSymbol.type) as? IntegerType
                ?: throw IRException("only support int constant")
            val value = constantSymbol.value as? Int
                ?: throw IRException("only support int constant")
            val intConstant = context.myGetIntConstant(type, value.toUInt()) // 只保存32位，具体符号由它参与的指令来决定
            module.createGlobalVariable(name, type, true, intConstant)
        }

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
            // 特殊处理 main 函数：
            // - main 函数不使用 ret_ptr 参数
            // - main 函数返回 i32 类型
            val isMainFunction = fnName == "main"

            // 切换到函数体的作用域（参数在这个作用域中定义）
            val bodyScope = node.body.scopePosition!!
            scopeTree.currentScope = bodyScope

            if (isMainFunction) {
                // main 函数：返回 i32，无 ret_ptr 参数
                val paramTypes = mutableListOf<IRType>()

                // 添加原始参数（但不添加 ret_ptr）
                for (param in funcSymbol.parameters) {
                    paramTypes.add(getIRType(context, param.paramType))
                }

                // 创建函数类型（返回 i32）
                val funcType = context.myGetFunctionType(context.myGetI32Type(), paramTypes)

                // 创建函数
                val func = module.myGetOrCreateFunction(fnName, funcType)

                // 设置函数参数
                val arguments = mutableListOf<Argument>()
                node.params.forEachIndexed { i, param ->
                    val pattern = param.paramPattern as IdentifierPatternNode
                    val paramType = paramTypes[i]
                    arguments.add(Argument(pattern.name.value, paramType, func))
                }
                func.setArguments(arguments)

                visitBlockExpr(node.body, createScope = false)
            } else {
                // 非 main 函数：使用统一返回约定
                // 第一个参数：返回缓冲区指针（ptr 类型）
                val paramTypes = mutableListOf<IRType>()
                paramTypes.add(context.myGetPointerType())  // ret_ptr

                // 使用 funcSymbol.isMethod 来判断是否是方法
                val isMethod = funcSymbol.isMethod

                // 如果是方法，添加 self 参数（作为指针传递）
                if (isMethod) {
                    paramTypes.add(context.myGetPointerType())  // self_ptr
                }

                // 添加原始参数
                // 注意：对于聚合类型（struct/array），参数以指针形式传递
                for (param in funcSymbol.parameters) {
                    val originalType = getIRType(context, param.paramType)
                    // 如果是聚合类型，参数类型为指针；否则为原始类型
                    if (originalType.isAggregate()) {
                        paramTypes.add(context.myGetPointerType())
                    } else {
                        paramTypes.add(originalType)
                    }
                }

                // 创建函数类型（返回 void）
                val funcType = context.myGetFunctionType(context.myGetVoidType(), paramTypes)

                // 创建函数
                val func = module.myGetOrCreateFunction(fnName, funcType)

                // 设置函数参数
                val arguments = mutableListOf<Argument>()
                // 第一个参数：ret_ptr
                arguments.add(Argument("ret_ptr", context.myGetPointerType(), func))

                // 如果是方法，添加 self 参数
                if (isMethod) {
                    arguments.add(Argument("self", context.myGetPointerType(), func))
                }

                // 原始参数
                // 计算偏移量：ret_ptr 占 1 个位置，如果有 self 则占 2 个位置
                val paramOffset = if (isMethod) 2 else 1
                node.params.forEachIndexed { i, param ->
                    val pattern = param.paramPattern as IdentifierPatternNode
                    val paramType = paramTypes[i + paramOffset]
                    arguments.add(Argument(pattern.name.value, paramType, func))
                }
                func.setArguments(arguments)

                visitBlockExpr(node.body, createScope = false)
            }
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

        node.value.accept(this)

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
        // nothing to do
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitCharLiteralExpr(node: CharLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        // nothing to do
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
        // nothing to do
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

        node.left.accept(this)
        node.right.accept(this)

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitComparisonExpr(node: ComparisonExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.left.accept(this)
        node.right.accept(this)

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

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitMethodCallExpr(node: MethodCallExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.receiver.accept(this)
        for (param in node.params) {
            param.accept(this)
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

        scopeTree.currentScope = previousScope // 还原scope状态
    }
}