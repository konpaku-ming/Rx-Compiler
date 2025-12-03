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
import ast.LoopScope
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
import ast.NamedResolvedType
import ast.NegationExprNode
import ast.NeverResolvedType
import ast.PathExprNode
import ast.PredicateLoopExprNode
import ast.PrimitiveResolvedType
import ast.RawCStringLiteralExprNode
import ast.RawStringLiteralExprNode
import ast.ReferenceResolvedType
import ast.ResolvedType
import ast.ReturnExprNode
import ast.Scope
import ast.ScopeTree
import ast.StringLiteralExprNode
import ast.StructExprNode
import ast.StructItemNode
import ast.StructSymbol
import ast.TokenType
import ast.TraitItemNode
import ast.TypeCastExprNode
import ast.UnitResolvedType
import ast.UnknownResolvedType
import ast.VariableSymbol
import ast.stringToUInt
import exception.IRException
import exception.SemanticException
import llvm.Argument
import llvm.ArrayType
import llvm.BasicBlock
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

    // 当前函数的返回块
    private var currentReturnBlock: BasicBlock? = null

    // 当前函数的名称（用于判断是否是 main 函数）
    private var currentFunctionName: String? = null

    // 当前处理的 struct 名称（在 visitImplItem 中设置）
    private var currentStructName: String? = null

    /**
     * 获取函数的 IR 名称
     * 对于 associated function/method，名称为 StructName.funcName
     * 对于普通函数，名称为 funcName
     */
    private fun getIRFunctionName(funcName: String, funcSymbol: FunctionSymbol): String {
        return if (funcSymbol.isAssociated && currentStructName != null) {
            "${currentStructName}.${funcName}"
        } else {
            funcName
        }
    }

    /**
     * 获取调用函数的 IR 名称
     * 对于 associated function，从 path 中获取 struct 名称
     * 对于普通函数，直接返回函数名
     */
    private fun getCallIRFunctionName(funcPath: PathExprNode, funcSymbol: FunctionSymbol): String {
        return if (funcSymbol.isAssociated && funcPath.second != null) {
            // 有两段的 path，如 Type::method
            val structName = funcPath.first.segment.value
            val funcName = funcPath.second!!.segment.value
            "${structName}.${funcName}"
        } else {
            funcSymbol.name
        }
    }

    /**
     * 获取方法调用的 IR 函数名称
     * 从 receiver 的类型中获取 struct 名称
     */
    private fun getMethodCallIRFunctionName(receiver: ResolvedType, methodName: String): String {
        val structName = when (receiver) {
            is NamedResolvedType -> receiver.name
            is ReferenceResolvedType -> (receiver.inner as? NamedResolvedType)?.name
            else -> null
        }
        return if (structName != null) {
            "${structName}.${methodName}"
        } else {
            methodName
        }
    }

    /**
     * 循环上下文类，管理循环的控制流信息
     */
    private data class LoopContext(
        /** 条件检查块（仅 while 循环有），while 循环的 continue 跳转到此块 */
        val condBB: BasicBlock?,
        /** 循环体块，loop 循环的 continue 跳转到此块（while 循环不使用此块作为 continue 目标）*/
        val bodyBB: BasicBlock,
        /** 循环后续块，break 跳转到此块 */
        val afterBB: BasicBlock,
        /** break 表达式的类型 */
        val breakType: ResolvedType,
        /** break 的值和来源块（用于创建 PHI 节点） */
        val breakIncomings: MutableList<Pair<Value, BasicBlock>> = mutableListOf()
    ) {
        /** 获取 continue 应该跳转到的块：while 循环跳转到 condBB，loop 循环跳转到 bodyBB */
        fun getContinueTarget(): BasicBlock = condBB ?: bodyBB
    }

    // 循环上下文栈，用于处理嵌套循环中的 break/continue
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

    private fun getArrayCopySize(arrayType: ArrayType): Value {
        // 使用 GEP + PtrToInt 来计算数组的字节大小
        // 这种方法可以正确处理 struct 数组等复杂元素类型
        val arrayLength = arrayType.numElements
        val lengthConst = context.myGetIntConstant(context.myGetI32Type(), arrayLength.toUInt())
        val gepInst = builder.createGEP(
            arrayType.elementType,
            context.myGetNullPtrConstant(),
            listOf(lengthConst)
        )
        return builder.createPtrToInt(context.myGetI32Type(), gepInst)
    }

    private fun isUnsignedType(type: ResolvedType): Boolean {
        return when (type) {
            is PrimitiveResolvedType -> type.name in listOf("u32", "usize")
            else -> false
        }
    }

    /**
     * 处理隐式返回（块的尾表达式或 Unit）
     */
    private fun handleImplicitReturn(body: BlockExprNode, returnValueIRType: IRType) {
        val retPtr = currentReturnBufferPtr ?: return

        if (body.tailExpr != null && body.irValue != null) {
            // 有尾表达式，将其值写入返回缓冲区
            val tailValue = body.irValue!!
            when (returnValueIRType) {
                is StructType -> {
                    // 结构体：使用 memcpy
                    val structName = (body.tailExpr.resolvedType as? NamedResolvedType)?.name
                        ?: throw IRException("Expected NamedResolvedType for struct return")
                    val sizeFunc = module.myGetFunction("${structName}.size")
                        ?: throw IRException("missing sizeFunc for struct '$structName'")
                    val size = builder.createCall(sizeFunc, emptyList())
                    builder.createMemCpy(retPtr, tailValue, size, false)
                }

                is ArrayType -> {
                    // 数组：使用 memcpy
                    val size = getArrayCopySize(returnValueIRType)
                    builder.createMemCpy(retPtr, tailValue, size, false)
                }

                else -> {
                    // 标量：使用 store
                    builder.createStore(tailValue, retPtr)
                }
            }
        } else {
            // 无尾表达式或尾表达式为 Unit
            // 写入 i8 0 作为 Unit 的返回值
            val zero = context.myGetIntConstant(context.myGetI8Type(), 0U)
            builder.createStore(zero, retPtr)
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
            // 保存当前函数名（用于恢复嵌套函数后的状态）
            val previousFunctionName = currentFunctionName
            val previousReturnBufferPtr = currentReturnBufferPtr
            val previousReturnBlock = currentReturnBlock
            currentFunctionName = fnName

            // 获取原始返回类型
            val originalReturnType = funcSymbol.returnType

            // 计算实际的返回值 IR 类型，其中 UnitType 会被当作 i8 处理
            val returnValueIRType = getIRType(context, originalReturnType)

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

                // 获取函数
                val func = module.myGetFunction(fnName)
                    ?: throw IRException("missing Function '$fnName'")

                // 设置函数参数
                val arguments = func.myGetArguments()

                // 创建入口块
                val entryBB = func.createBasicBlock("entry")
                builder.setInsertPoint(entryBB)

                // main 函数不使用 currentReturnBufferPtr
                currentReturnBufferPtr = null

                // 为每个参数创建 alloca 并 store
                for (i in arguments.indices) {
                    val arg = arguments[i]
                    val paramType = paramTypes[i]
                    val alloca = builder.createAlloca(paramType)
                    builder.createStore(arg, alloca)

                    // 查找对应的 VariableSymbol 并绑定 irValue
                    val paramName = arg.name
                    val paramSymbol = bodyScope.lookupLocal(paramName) as? VariableSymbol
                    paramSymbol?.irValue = alloca
                }

                // 创建返回块
                val returnBB = func.createBasicBlock("return")
                currentReturnBlock = returnBB

                // 生成函数体
                visitBlockExpr(node.body, createScope = false)

                // 如果函数体没有以终结指令结束，需要处理隐式返回
                val currentBB = builder.myGetInsertBlock()
                if (currentBB != null && !currentBB.isTerminated()) {
                    // main 函数隐式返回 0
                    builder.createBr(returnBB)
                }

                // 生成返回块
                builder.setInsertPoint(returnBB)
                // main 函数返回 i32 0
                val zero = context.myGetIntConstant(context.myGetI32Type(), 0U)
                builder.createRet(zero)
            } else {
                // 非 main 函数：使用统一返回约定

                // 使用 funcSymbol.isMethod 来判断是否是方法
                val isMethod = funcSymbol.isMethod

                // self 参数的索引常量（如果是方法，self 在 ret_ptr 之后，索引为 1）
                val selfArgIndex = 1

                // 添加原始参数
                // 注意：对于聚合类型（struct/array），参数以指针形式传递
                // 保存原始IR类型，用于后续在函数入口处（callee prologue）进行memcpy
                val originalParamIRTypes = mutableListOf<IRType>()
                for (param in funcSymbol.parameters) {
                    val originalType = getIRType(context, param.paramType)
                    originalParamIRTypes.add(originalType)
                }

                // 获取函数（使用 mangled name）
                val irFuncName = getIRFunctionName(fnName, funcSymbol)
                val func = module.myGetFunction(irFuncName)
                    ?: throw IRException("missing Function '$irFuncName'")

                // 获取函数参数
                val arguments = func.myGetArguments()
                // 计算偏移量：ret_ptr 占 1 个位置，如果有 self 则占 2 个位置
                val paramOffset = if (isMethod) 2 else 1

                // 创建入口块
                val entryBB = func.createBasicBlock("entry")
                builder.setInsertPoint(entryBB)

                // 保存返回缓冲区指针（第一个参数）
                currentReturnBufferPtr = arguments[0]

                // 如果是方法，处理 self 参数
                if (isMethod) {
                    val selfArg = arguments[selfArgIndex]  // self 参数（指针类型）
                    // self 在语义分析中已经注册为 VariableSymbol
                    // 对于 &self / &mut self，self 参数是指向结构体的指针
                    // 在 IR 中，self 的 irValue 直接使用这个指针
                    // （不需要额外的 alloca，因为我们只需要读取 self.field，不会修改 self 本身）
                    val selfSymbol = bodyScope.lookupLocal("self") as? VariableSymbol
                        ?: throw IRException("Method '$fnName' is missing 'self' VariableSymbol in scope")
                    // self 的类型是结构体类型本身
                    // 但我们传入的是指向结构体的指针，这正是 visitFieldExpr 等需要的
                    // 直接将指针参数赋给 irValue
                    selfSymbol.irValue = selfArg
                }

                // 为每个原始参数创建本地存储（跳过 ret_ptr 和 self）
                // - 聚合类型参数以指针形式传入，需要 alloca + memcpy 复制到本地存储
                // - 标量类型参数直接 alloca + store
                for (i in paramOffset until arguments.size) {
                    val arg = arguments[i]
                    val originalIndex = i - paramOffset

                    // 获取原始IR类型和参数符号
                    val originalIRType = originalParamIRTypes[originalIndex]
                    val paramSymbol = funcSymbol.parameters[originalIndex]

                    // 查找对应的 VariableSymbol 并绑定 irValue
                    val paramName = arg.name
                    val varSymbol = bodyScope.lookupLocal(paramName) as? VariableSymbol

                    if (originalIRType.isAggregate()) {
                        // 聚合类型：arg 是指向外部数据的指针
                        // 需要分配本地存储并 memcpy 以获取值语义
                        val alloca = builder.createAlloca(originalIRType)

                        when (originalIRType) {
                            is StructType -> {
                                // 结构体：使用 sizeFunc 获取大小
                                val structName = (paramSymbol.paramType as? NamedResolvedType)?.name
                                    ?: throw IRException("Expected NamedResolvedType for struct parameter, got: ${paramSymbol.paramType}")
                                val sizeFunc = module.myGetFunction("${structName}.size")
                                    ?: throw IRException("missing sizeFunc for struct '$structName'")
                                val size = builder.createCall(sizeFunc, emptyList())
                                builder.createMemCpy(alloca, arg, size, false)
                            }

                            is ArrayType -> {
                                // 数组：计算大小
                                val size = getArrayCopySize(originalIRType)
                                builder.createMemCpy(alloca, arg, size, false)
                            }

                            else -> throw IRException("Unexpected aggregate type: $originalIRType")
                        }

                        varSymbol?.irValue = alloca
                    } else {
                        // 标量类型：直接 alloca 并 store
                        val alloca = builder.createAlloca(originalIRType)
                        builder.createStore(arg, alloca)
                        varSymbol?.irValue = alloca
                    }
                }

                // 创建返回块
                val returnBB = func.createBasicBlock("return")
                currentReturnBlock = returnBB

                // 生成函数体
                visitBlockExpr(node.body, createScope = false)

                // 如果函数体没有以终结指令结束，需要处理隐式返回
                val currentBB = builder.myGetInsertBlock()
                if (currentBB != null && !currentBB.isTerminated()) {
                    // 隐式返回：尾表达式或 Unit
                    handleImplicitReturn(node.body, returnValueIRType)
                    builder.createBr(returnBB)
                }

                // 生成返回块
                builder.setInsertPoint(returnBB)
                builder.createRet(null)  // ret void
            }

            // 恢复状态（用于支持嵌套函数）
            currentReturnBufferPtr = previousReturnBufferPtr
            currentReturnBlock = previousReturnBlock
            currentFunctionName = previousFunctionName
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

        // 获取 impl 的 struct 名称
        val implScope = node.implScopePosition
        val structName = (implScope?.implType as? NamedResolvedType)?.name
        val previousStructName = currentStructName
        currentStructName = structName

        for (item in node.associatedItems) {
            item.accept(this)
        }

        currentStructName = previousStructName // 还原 struct 名称
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

        // 设置BlockExpr的irValue
        // 如果有尾表达式，块的值就是尾表达式的值
        // 如果没有尾表达式，块的值为Unit，irValue为null
        if (node.tailExpr != null) {
            node.irValue = node.tailExpr.irValue
            node.irAddr = null // BlockExpr 不能做左值
        } else {
            node.irValue = null
            node.irAddr = null
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

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
            bodyBB = bodyBB,
            afterBB = afterBB,
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

        scopeTree.currentScope = previousScope // 还原scope状态
    }

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
        // 注意：loop 循环的 breakType 在语义分析阶段初始为 UnknownResolvedType，
        // 如果没有 break 表达式，则保持为 UnknownResolvedType（此时 needsPhi 为 false）
        // 如果有 break 表达式，则由 break 表达式的值类型决定
        val loopScope = node.block.scopePosition as? LoopScope
        val breakType = loopScope?.breakType ?: UnknownResolvedType

        // 4. 判断是否需要 PHI 节点
        val needsPhi = breakType !is UnitResolvedType
                && breakType !is UnknownResolvedType
                && breakType !is NeverResolvedType

        // 5. 创建循环上下文并入栈
        val loopContext = LoopContext(
            condBB = null,  // loop 没有条件块
            bodyBB = bodyBB,
            afterBB = afterBB,
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

        // 注意：如果 needsPhi 为 true，语义分析已确保 breakIncomings 不会为空
        // 因为 breakType 不是 Unit/Unknown/Never 意味着至少有一个带值的 break 表达式
        if (needsPhi && loopContext.breakIncomings.isNotEmpty()) {
            val phiType = getIRType(context, breakType)
            val phi = builder.createPHI(phiType)
            for ((value, block) in loopContext.breakIncomings) {
                phi.addIncoming(value, block)
            }
            node.irValue = phi
        } else {
            node.irValue = null
        }
        node.irAddr = null

        // 9. 出栈循环上下文
        popLoopContext()

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
        node.irAddr = null // 整数字面量没有地址

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
        node.irValue = boolConstant // 保存IR Value
        node.irAddr = null // 布尔字面量没有地址

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

        // PathExprNode 在语义分析阶段已经绑定了 symbol
        // 这里根据 symbol 类型生成对应的 IR
        when (val symbol = node.symbol) {
            is VariableSymbol -> {
                val symbolAddr = symbol.irValue
                    ?: throw IRException(
                        "Variable '${symbol.name}' has no IR value," +
                                " in function '${currentFunctionName}'"
                    )

                val varType = getIRType(context, symbol.type)
                if (varType.isAggregate()) {
                    // 结构体/数组：返回地址（指针）
                    node.irValue = symbolAddr
                    node.irAddr = symbolAddr // 变量的IR地址
                } else {
                    // 标量类型（整数/布尔等）：load 出实际值
                    val loadedValue = builder.createLoad(varType, symbolAddr)
                    node.irValue = loadedValue
                    node.irAddr = symbolAddr // 变量的IR地址
                }
            }

            is ConstantSymbol -> {
                // 常量在 IR 定义阶段已被注册为全局变量
                val constName = symbol.name
                val globalVar = module.myGetGlobalVariable(constName)
                    ?: throw IRException("Constant '$constName' is not defined as global variable")

                val constType = getIRType(context, symbol.type)
                // 全局常量需要 load 出值，必定为整型
                val loadedValue = builder.createLoad(constType, globalVar)
                node.irValue = loadedValue
                node.irAddr = null // 常量没有地址
            }

            null -> throw IRException("missing symbol in PathExprNode '$node'")

            else -> {
                // function / struct 不会作为值处理
                node.irValue = null
                node.irAddr = null
            }
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitBorrowExpr(node: BorrowExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        // 借用表达式（&expr 或 &mut expr）
        // 语义：获取内部表达式的地址，生成一个指向该地址的引用
        node.expr.accept(this)

        // 获取内部表达式的地址
        // 对于左值表达式（变量、字段访问、索引访问等），irAddr 包含其存储地址
        // 对于右值表达式（字面量、临时值等），irAddr 为 null，需要先分配空间再借用
        val innerAddr = if (node.expr.irAddr != null) {
            // 左值：直接使用其地址
            node.expr.irAddr!!
        } else {
            // 右值：需要先分配空间，初始化后再借用
            val innerValue = node.expr.irValue
                ?: throw IRException("Cannot borrow rvalue: expression has no value: ${node.expr}")
            val innerType = getIRType(context, node.expr.resolvedType)

            // 在栈上分配空间
            val tempAlloca = builder.createAlloca(innerType)

            // 根据类型选择初始化策略
            when (innerType) {
                is StructType -> {
                    // 结构体：使用 memcpy
                    val structName = (node.expr.resolvedType as? NamedResolvedType)?.name
                        ?: throw IRException("Cannot borrow struct rvalue: expected NamedResolvedType but got ${node.expr.resolvedType}")
                    val sizeFunc = module.myGetFunction("${structName}.size")
                        ?: throw IRException("missing sizeFunc for struct '$structName'")
                    val size = builder.createCall(sizeFunc, emptyList())
                    builder.createMemCpy(tempAlloca, innerValue, size, false)
                }

                is ArrayType -> {
                    // 数组：使用 memcpy
                    val size = getArrayCopySize(innerType)
                    builder.createMemCpy(tempAlloca, innerValue, size, false)
                }

                else -> {
                    // 标量类型：使用 store
                    builder.createStore(innerValue, tempAlloca)
                }
            }

            tempAlloca
        }

        // 借用表达式的值就是内部表达式的地址（指针）
        node.irValue = innerAddr
        // 借用表达式本身没有地址（不能对借用再取地址，除非先存储到变量）
        node.irAddr = null

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitDerefExpr(node: DerefExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        // 解引用表达式（*expr）
        // 语义：从指针/引用中读取值，或获取指向的内存位置
        node.expr.accept(this)

        // 获取内部表达式的值（应该是一个指针）
        val ptrValue = node.expr.irValue
            ?: throw IRException("Operand of dereference expression has no IR value")

        // 获取解引用后的类型
        val innerType = node.resolvedType // 语义分析阶段已解析
        val derefType = getIRType(context, innerType)

        if (derefType.isAggregate()) {
            // 结构体/数组：直接返回指针，不进行 load
            // 因为聚合类型始终以指针形式传递
            node.irValue = ptrValue
            node.irAddr = ptrValue // 解引用后的地址就是指针的值
        } else {
            // 标量类型：从指针中 load 出实际值
            val loadedValue = builder.createLoad(derefType, ptrValue)
            node.irValue = loadedValue
            node.irAddr = ptrValue // 解引用后的地址就是指针的值
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitNegationExpr(node: NegationExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        // 递归处理子表达式
        node.expr.accept(this)
        val operandValue = node.expr.irValue
            ?: throw IRException("Operand of negation expression has no IR value")

        // 获取操作数的类型
        val operandType = operandValue.myGetType()
        if (operandType !is IntegerType) throw IRException(
            "Negation operand must be of integer type"
        )

        // 根据运算符类型生成对应的 IR
        val result = when (node.operator.type) {
            TokenType.SubNegate -> {
                // 算术取负: -x 等价于 0 - x
                // 使用操作数的类型创建零常量，按模运算语义处理
                val zero = context.myGetIntConstant(operandType, 0U)
                builder.createSub(zero, operandValue)
            }

            TokenType.Not -> {
                // 逻辑非 / 位取反: !x
                // 对于布尔值 (i1)，使用 xor with 1 实现取反
                // 对于整数类型，使用 xor with -1 (0xFFFFFFFF) 实现位取反
                when (operandType) {
                    is I1Type -> {
                        val one = context.myGetIntConstant(operandType, 1U)
                        builder.createXor(operandValue, one)
                    }

                    else -> {
                        // 对于整数类型，使用 -1 (全1位模式) 进行 XOR 实现位取反
                        // 0xFFFFFFFF as UInt 表示 32 位全 1
                        val allOnes = context.myGetIntConstant(operandType, 0xFFFFFFFFU)
                        builder.createXor(operandValue, allOnes)
                    }
                }
            }

            else -> throw IRException("Unknown negation operator: ${node.operator.type}")
        }

        node.irValue = result
        node.irAddr = null // 一元运算表达式没有地址（不是左值）

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
        node.irAddr = null // 二元表达式没有地址

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
        node.irAddr = null

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

            node.irValue = phi // 设置结果为 PHI 节点
            node.irAddr = null // 短路布尔表达式没有地址
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
            node.irAddr = null
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
                node.irAddr = null
            }

            // i32/u32/isize/usize 之间互转：位宽相同，不需要变换
            srcIsInteger && dstIsInteger -> {
                // 位宽都是 32 位，直接使用原值
                node.irValue = srcValue
                node.irAddr = null
            }

            else -> throw IRException(
                "Unsupported type cast from ${srcType.name} to ${dstType.name}"
            )
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitAssignExpr(node: AssignExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        // 先求值左侧表达式获取地址，再求值右侧表达式获取值
        node.left.accept(this)
        node.right.accept(this)

        // 获取左值地址
        val dstPtr = node.left.irAddr
            ?: throw IRException("Left side of assignment has no address")

        // 获取赋值类型
        when (val assignType = getIRType(context, node.left.resolvedType)) {
            is StructType -> {
                // 结构体：使用 memcpy
                val srcPtr = node.right.irValue // 应为一个 ptr
                    ?: throw IRException("Right side of struct assignment has no IR value")
                // 从左侧表达式的 resolvedType 获取结构体名称
                val structName = (node.left.resolvedType as? NamedResolvedType)?.name
                    ?: throw IRException("Left side of assignment is not a struct type")
                val sizeFunc = module.myGetFunction("${structName}.size")
                    ?: throw IRException("missing sizeFunc for struct '$structName'")
                val size = builder.createCall(sizeFunc, emptyList())
                builder.createMemCpy(dstPtr, srcPtr, size, false)
            }

            is ArrayType -> {
                // 数组：使用 memcpy
                val srcPtr = node.right.irValue // 应为一个 ptr
                    ?: throw IRException("Right side of array assignment has no IR value")
                val size = getArrayCopySize(assignType) // i32的Value
                builder.createMemCpy(dstPtr, srcPtr, size, false)
            }

            else -> {
                // 标量类型：使用 store
                val value = node.right.irValue
                    ?: throw IRException("Right side of assignment has no IR value")
                builder.createStore(value, dstPtr)
            }
        }

        node.irValue = null // 无值
        node.irAddr = null // 无地址

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitCompoundAssignExpr(node: CompoundAssignExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        // 先求值左侧表达式获取地址，再求值右侧表达式获取值
        node.left.accept(this)
        node.right.accept(this)

        // 获取左值地址
        val leftPtr = node.left.irAddr
            ?: throw IRException("Left side of compound assignment has no address")

        // 获取赋值类型
        val assignType = getIRType(context, node.left.resolvedType)
        // 复合赋值仅支持标量类型（整数、布尔等），不支持结构体和数组
        if (assignType.isAggregate()) {
            throw IRException("Compound assignment is not supported for aggregate types")
        }

        val leftValue = node.left.irValue // 左操作数的值
            ?: throw IRException("Right side of compound assignment has no IR value")
        val rightValue = node.right.irValue // 右操作数的值
            ?: throw IRException("Right side of compound assignment has no IR value")

        // 根据运算符执行二元运算
        val result = when (node.operator.type) {
            TokenType.AddAssign -> builder.createAdd(leftValue, rightValue)
            TokenType.SubAssign -> builder.createSub(leftValue, rightValue)
            TokenType.MulAssign -> builder.createMul(leftValue, rightValue)

            // 除法：根据符号选择指令
            TokenType.DivAssign -> if (isUnsignedType(node.left.resolvedType)) {
                builder.createUDiv(leftValue, rightValue)
            } else {
                builder.createSDiv(leftValue, rightValue)
            }

            // 取余：根据符号选择指令
            TokenType.ModAssign -> if (isUnsignedType(node.left.resolvedType)) {
                builder.createURem(leftValue, rightValue)
            } else {
                builder.createSRem(leftValue, rightValue)
            }

            // 右移：根据符号选择指令
            TokenType.ShrAssign -> if (isUnsignedType(node.left.resolvedType)) {
                builder.createLShr(leftValue, rightValue)  // 逻辑右移
            } else {
                builder.createAShr(leftValue, rightValue)  // 算术右移
            }

            // 位运算：符号无关
            TokenType.AndAssign -> builder.createAnd(leftValue, rightValue)
            TokenType.OrAssign -> builder.createOr(leftValue, rightValue)
            TokenType.XorAssign -> builder.createXor(leftValue, rightValue)
            TokenType.ShlAssign -> builder.createShl(leftValue, rightValue)

            else -> throw IRException("Unknown compound assignment operator: ${node.operator.type}")
        }

        // 将结果存回左值地址
        builder.createStore(result, leftPtr)

        node.irValue = null // 无值
        node.irAddr = null // 无地址

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitGroupedExpr(node: GroupedExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        // 分组表达式（括号表达式）直接传递内部表达式的值和地址
        // 括号只影响解析优先级，不影响求值语义
        node.inner.accept(this)
        node.irValue = node.inner.irValue
        node.irAddr = node.inner.irAddr

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitArrayListExpr(node: ArrayListExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        // 获取数组类型
        val arrayResolvedType = node.resolvedType as? ArrayResolvedType
            ?: throw IRException("ArrayListExprNode's resolvedType is not ArrayResolvedType")
        val arrayType = getIRType(context, arrayResolvedType) as ArrayType
        val elementType = arrayType.elementType

        // 在栈上分配数组空间
        val arrayAlloca = builder.createAlloca(arrayType) // ptr类型

        // 对每个元素求值并存储到数组中
        node.elements.forEachIndexed { index, element ->
            element.accept(this)

            // 计算元素地址：使用 GEP 获取 array[index] 的地址
            val zero = context.myGetIntConstant(context.myGetI32Type(), 0U)
            val indexConst = context.myGetIntConstant(context.myGetI32Type(), index.toUInt())
            val elementPtr = builder.createGEP(arrayType, arrayAlloca, listOf(zero, indexConst))

            when (elementType) {
                is StructType -> {
                    // 结构体元素：使用 memcpy
                    val srcPtr = element.irValue // 应为一个 ptr
                        ?: throw IRException("Array element at index $index has no IR value")
                    val structName = (arrayResolvedType.elementType as? NamedResolvedType)?.name
                        ?: throw IRException("Array element is not a NamedResolvedType")
                    val sizeFunc = module.myGetFunction("${structName}.size")
                        ?: throw IRException("missing sizeFunc for struct '$structName'")
                    val size = builder.createCall(sizeFunc, emptyList())
                    builder.createMemCpy(elementPtr, srcPtr, size, false)
                }

                is ArrayType -> {
                    // 嵌套数组元素：使用 memcpy
                    val srcPtr = element.irValue // 应为一个 ptr
                        ?: throw IRException("Array element at index $index has no IR value")
                    val size = getArrayCopySize(elementType)
                    builder.createMemCpy(elementPtr, srcPtr, size, false)
                }

                else -> {
                    // 标量元素：使用 store
                    val value = element.irValue
                        ?: throw IRException("Array element at index $index has no IR value")
                    builder.createStore(value, elementPtr)
                }
            }
        }

        node.irValue = arrayAlloca // 数组表达式的值是数组的地址（指针）
        node.irAddr = null // 数组字面量没有地址（不是左值）

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitArrayLengthExpr(node: ArrayLengthExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        // 获取数组类型
        val arrayResolvedType = node.resolvedType as? ArrayResolvedType
            ?: throw IRException("ArrayLengthExprNode's resolvedType is not ArrayResolvedType")
        val arrayType = getIRType(context, arrayResolvedType) as ArrayType
        val elementType = arrayType.elementType
        val length = arrayType.numElements

        // 在栈上分配数组空间
        val arrayAlloca = builder.createAlloca(arrayType)

        node.element.accept(this)

        // 将重复元素存储到数组的每个位置
        for (index in 0 until length) {
            // 计算元素地址：使用 GEP 获取 array[index] 的地址
            val zero = context.myGetIntConstant(context.myGetI32Type(), 0U)
            val indexConst = context.myGetIntConstant(context.myGetI32Type(), index.toUInt())
            val elementPtr = builder.createGEP(arrayType, arrayAlloca, listOf(zero, indexConst))

            when (elementType) {
                is StructType -> {
                    // 结构体元素：使用 memcpy
                    val srcPtr = node.element.irValue // 应为一个 ptr
                        ?: throw IRException("Repeat element has no IR value")
                    val structName = (arrayResolvedType.elementType as? NamedResolvedType)?.name
                        ?: throw IRException("Array element is not a NamedResolvedType")
                    val sizeFunc = module.myGetFunction("${structName}.size")
                        ?: throw IRException("missing sizeFunc for struct '$structName'")
                    val size = builder.createCall(sizeFunc, emptyList())
                    builder.createMemCpy(elementPtr, srcPtr, size, false)
                }

                is ArrayType -> {
                    // 嵌套数组元素：使用 memcpy
                    val srcPtr = node.element.irValue // 应为一个 ptr
                        ?: throw IRException("Repeat element has no IR value")
                    val size = getArrayCopySize(elementType)
                    builder.createMemCpy(elementPtr, srcPtr, size, false)
                }

                else -> {
                    // 标量元素：使用 store
                    val value = node.element.irValue
                        ?: throw IRException("Repeat element has no IR value")
                    builder.createStore(value, elementPtr)
                }
            }
        }

        // 数组表达式的值是数组的地址（指针）
        node.irValue = arrayAlloca
        node.irAddr = null // 数组字面量没有地址（不是左值）

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitIndexExpr(node: IndexExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        // 求值基址表达式和索引表达式
        node.base.accept(this)
        node.index.accept(this)

        // 获取基址（数组的地址）
        val basePtr = node.base.irValue
            ?: throw IRException("Base of index expression has no IR value")
        val indexValue = node.index.irValue
            ?: throw IRException("Index of index expression has no IR value")

        // 获取数组类型和元素类型
        // 自动解引用：如果 base 的类型是引用类型，且其内部是数组类型，则提取内部数组类型
        // 参照 visitFieldExpr 的实现模式
        val arrayResolvedType = when (val baseType = node.base.resolvedType) {
            is ArrayResolvedType -> baseType
            is ReferenceResolvedType -> baseType.inner as? ArrayResolvedType
                ?: throw IRException("Reference inner type is not an ArrayResolvedType")

            else -> throw IRException("Base of index expression is not an array type: $baseType")
        }
        val arrayType = getIRType(context, arrayResolvedType) as ArrayType
        val elementType = arrayType.elementType

        // 计算元素地址：使用 GEP 获取 array[index] 的地址
        val zero = context.myGetIntConstant(context.myGetI32Type(), 0U)
        val elementPtr = builder.createGEP(arrayType, basePtr, listOf(zero, indexValue))

        // 根据元素类型设置 irValue 和 irAddr
        if (elementType.isAggregate()) {
            // 结构体/数组：返回地址（指针）
            node.irValue = elementPtr
            node.irAddr = elementPtr // 索引表达式的地址是元素的地址
        } else {
            // 标量类型：load 出实际值
            val loadedValue = builder.createLoad(elementType, elementPtr)
            node.irValue = loadedValue
            node.irAddr = elementPtr // 索引表达式的地址是元素的地址（用于赋值）
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitStructExpr(node: StructExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        // 获取结构体符号
        val structResolvedType = node.resolvedType as? NamedResolvedType
            ?: throw IRException("StructExprNode's resolvedType is not NamedResolvedType")
        val structSymbol = structResolvedType.symbol as? StructSymbol
            ?: throw IRException("StructExprNode's symbol is not StructSymbol")
        val structType = getIRType(context, structResolvedType) as StructType

        // 在栈上分配结构体空间
        val structAlloca = builder.createAlloca(structType)

        // 构建 field name -> index 映射（按定义顺序）
        val fieldIndexMap = structSymbol.fields.keys.withIndex().associate { (index, name) -> name to index }

        // 对每个字段求值并存储到结构体中
        for (exprField in node.fields) {
            exprField.value.accept(this)

            val fieldName = exprField.name.value
            val fieldIndex = fieldIndexMap[fieldName]
                ?: throw IRException("Unknown field '$fieldName' in struct '${structSymbol.name}'")
            val fieldType = structType.myGetElementType(i = fieldIndex)

            // 计算字段地址：使用 GEP 获取 struct.field 的地址
            val zero = context.myGetIntConstant(context.myGetI32Type(), 0U)
            val indexConst = context.myGetIntConstant(context.myGetI32Type(), fieldIndex.toUInt())
            val fieldPtr = builder.createGEP(structType, structAlloca, listOf(zero, indexConst))

            when (fieldType) {
                is StructType -> {
                    // 嵌套结构体：使用 memcpy
                    val srcPtr = exprField.value.irValue
                        ?: throw IRException("Field '$fieldName' has no IR value")
                    // 从字段的 resolvedType 获取结构体名称
                    val innerStructName = (structSymbol.fields[fieldName] as? NamedResolvedType)?.name
                        ?: throw IRException("Field '$fieldName' is not a NamedResolvedType")
                    val sizeFunc = module.myGetFunction("${innerStructName}.size")
                        ?: throw IRException("missing sizeFunc for struct '$innerStructName'")
                    val size = builder.createCall(sizeFunc, emptyList())
                    builder.createMemCpy(fieldPtr, srcPtr, size, false)
                }

                is ArrayType -> {
                    // 数组字段：使用 memcpy
                    val srcPtr = exprField.value.irValue
                        ?: throw IRException("Field '$fieldName' has no IR value")
                    val size = getArrayCopySize(fieldType)
                    builder.createMemCpy(fieldPtr, srcPtr, size, false)
                }

                else -> {
                    // 标量字段：使用 store
                    val value = exprField.value.irValue
                        ?: throw IRException("Field '$fieldName' has no IR value")
                    builder.createStore(value, fieldPtr)
                }
            }
        }

        node.irValue = structAlloca // 结构体表达式的值是结构体的地址
        node.irAddr = null // 结构体字面量没有地址（不是左值）

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitCallExpr(node: CallExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        // 获取被调用的函数符号
        val funcPath = node.func as? PathExprNode
            ?: throw IRException("CallExpr func is not a PathExprNode")

        // 从 PathExprNode 获取函数符号（语义分析阶段已绑定）
        val funcSymbol = funcPath.symbol as? FunctionSymbol
            ?: throw IRException("CallExpr func does not refer to a FunctionSymbol")

        // 获取函数名
        val funcName = funcSymbol.name

        // 特判：当调用的函数名为 exit 且出现在 main 函数中时，让 main 函数返回 0
        // exit 只会在 main 函数中出现，此时直接跳转到 main 的返回块（返回块已设置返回 0）
        if (funcName == "exit" && currentFunctionName == "main") {
            val returnBB = currentReturnBlock
                ?: throw IRException("exit called in main but no return block available")
            builder.createBr(returnBB)
            // exit 表达式的类型是 Never，不会产生值
            node.irValue = null
            node.irAddr = null
            scopeTree.currentScope = previousScope
            return
        }

        // 获取 IR 函数（使用 mangled name）
        val irFuncName = getCallIRFunctionName(funcPath, funcSymbol)
        val func = module.myGetFunction(irFuncName)
            ?: throw IRException("Function '$irFuncName' not found in module")

        // 特判：内建I/O函数不使用ret_ptr约定，直接按原函数签名调用
        // printInt(int n) -> void
        // printlnInt(int n) -> void
        // getInt() -> i32
        if (funcName in listOf("printInt", "printlnInt", "getInt")) {
            // 构建参数列表（不包含ret_ptr）
            val args = mutableListOf<Value>()
            for (param in node.params) {
                param.accept(this)
                val paramValue = param.irValue
                    ?: throw IRException("Call parameter has no IR value")
                args.add(paramValue)
            }

            // 调用函数
            val callResult = builder.createCall(func, args)

            // 设置 irValue
            if (funcName == "getInt") {
                // getInt 返回 i32
                node.irValue = callResult
            } else {
                // printInt 和 printlnInt 返回 void
                node.irValue = null
            }
            node.irAddr = null
            scopeTree.currentScope = previousScope
            return
        }

        // 获取调用的返回类型
        val returnType = getIRType(context, node.resolvedType)

        // 分配返回缓冲区（统一返回约定：所有非 main 函数都使用 ret_ptr）
        val retAlloca = builder.createAlloca(returnType)

        // 构建参数列表
        val args = mutableListOf<Value>()
        args.add(retAlloca)  // ret_ptr 作为第一个参数

        // 处理参数（方法调用和普通函数调用参数处理相同）
        // 注意：方法调用通过 Type::method(&self, ...) 形式时，self 参数已在 node.params[0] 中
        for (param in node.params) {
            param.accept(this)
            val paramValue = param.irValue
                ?: throw IRException("Call parameter has no IR value")
            // 聚合类型（struct/array）以指针形式传递，标量类型传值
            // 但在 IR 层面，两者都是直接添加 irValue（聚合类型的 irValue 就是指针）
            args.add(paramValue)
        }

        // 调用函数（返回 void）
        builder.createCall(func, args)

        // 设置 irValue
        if (node.resolvedType is UnitResolvedType) {
            // Unit 类型不需要读取返回值
            node.irValue = null
        } else if (returnType.isAggregate()) {
            // 聚合类型：返回缓冲区指针
            node.irValue = retAlloca
        } else {
            // 标量类型：从缓冲区 load 出值
            node.irValue = builder.createLoad(returnType, retAlloca)
        }
        node.irAddr = null

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitMethodCallExpr(node: MethodCallExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        // 先访问 receiver（语义分析阶段可能已将其包装为 BorrowExprNode）
        node.receiver.accept(this)

        val methodName = node.method.segment.value
        val receiverType = node.receiver.resolvedType

        // 检查是否是内置方法
        // 数组的 .len() 方法返回数组长度
        val isArrayLen = when (receiverType) {
            is ArrayResolvedType -> methodName == "len"
            is ReferenceResolvedType -> receiverType.inner is ArrayResolvedType && methodName == "len"
            else -> false
        }

        if (isArrayLen) {
            // 数组的 .len() 方法是内置的，直接返回编译时已知的长度
            val arrayType = when (receiverType) {
                is ArrayResolvedType -> receiverType
                is ReferenceResolvedType -> receiverType.inner as ArrayResolvedType
                else -> throw IRException("Unexpected type for array.len()")
            }
            val lengthConst = context.myGetIntConstant(context.myGetI32Type(), arrayType.length.toUInt())
            node.irValue = lengthConst
            node.irAddr = null
            scopeTree.currentScope = previousScope
            return
        }

        // 获取 IR 函数名（使用 mangled name）
        val irFuncName = getMethodCallIRFunctionName(receiverType, methodName)

        // 获取 IR 函数
        val func = module.myGetFunction(irFuncName)
            ?: throw IRException("Method function '$irFuncName' not found in module")

        // 获取调用的返回类型
        val returnType = getIRType(context, node.resolvedType)

        // 分配返回缓冲区
        val retAlloca = builder.createAlloca(returnType)

        // 构建参数列表
        val args = mutableListOf<Value>()
        args.add(retAlloca)  // ret_ptr 作为第一个参数

        // 添加 self 参数（receiver）
        // receiver 在语义分析阶段已经被适当处理（如自动借用）
        val selfValue = node.receiver.irValue
            ?: throw IRException("Method receiver has no IR value")
        args.add(selfValue)

        // 添加其他参数
        // 聚合类型（struct/array）以指针形式传递，标量类型传值
        // 但在 IR 层面，两者都是直接添加 irValue（聚合类型的 irValue 就是指针）
        for (param in node.params) {
            param.accept(this)
            val paramValue = param.irValue
                ?: throw IRException("Method parameter has no IR value")
            args.add(paramValue)
        }

        // 调用方法（返回 void）
        builder.createCall(func, args)

        // 设置 irValue
        if (node.resolvedType is UnitResolvedType) {
            node.irValue = null
        } else if (returnType.isAggregate()) {
            node.irValue = retAlloca
        } else {
            node.irValue = builder.createLoad(returnType, retAlloca)
        }
        node.irAddr = null

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitFieldExpr(node: FieldExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.struct.accept(this)

        // 获取结构体表达式的地址
        val structPtr = node.struct.irValue
            ?: throw IRException("Struct expression has no IR value")

        // 从 struct 表达式的类型获取结构体符号
        val structResolvedType = when (val structType = node.struct.resolvedType) {
            is NamedResolvedType -> structType
            is ReferenceResolvedType -> structType.inner as? NamedResolvedType
                ?: throw IRException("Reference inner type is not a NamedResolvedType")

            else -> throw IRException("FieldExprNode's struct is not a struct type: $structType")
        }
        val structSymbol = structResolvedType.symbol as? StructSymbol
            ?: throw IRException("FieldExprNode's symbol is not StructSymbol")
        val structType = getIRType(context, structResolvedType) as StructType

        // 获取字段索引
        val fieldName = node.field.value
        val fieldIndexMap = structSymbol.fields.keys.withIndex().associate { (index, name) -> name to index }
        val fieldIndex = fieldIndexMap[fieldName]
            ?: throw IRException("Unknown field '$fieldName' in struct '${structSymbol.name}'")
        val fieldType = structType.myGetElementType(fieldIndex)

        // 计算字段地址：使用 GEP 获取 struct.field 的地址
        val zero = context.myGetIntConstant(context.myGetI32Type(), 0U)
        val indexConst = context.myGetIntConstant(context.myGetI32Type(), fieldIndex.toUInt())
        val fieldPtr = builder.createGEP(structType, structPtr, listOf(zero, indexConst))

        // 根据字段类型设置 irValue 和 irAddr
        if (fieldType.isAggregate()) {
            // 结构体/数组：返回地址
            node.irValue = fieldPtr
            node.irAddr = fieldPtr // 字段表达式的地址是字段的地址
        } else {
            // 标量类型：load 出实际值
            val loadedValue = builder.createLoad(fieldType, fieldPtr)
            node.irValue = loadedValue
            node.irAddr = fieldPtr // 字段表达式的地址是字段的地址（用于赋值）
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitIfExpr(node: IfExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        // If表达式的控制流实现：
        // entry:
        //   %cond = ... (求值条件表达式)
        //   br i1 %cond, label %then, label %else
        // then:
        //   %then_val = ... (求值then分支)
        //   br label %merge
        // else:
        //   %else_val = ... (求值else分支，如果有的话)
        //   br label %merge
        // merge:
        //   %result = phi [%then_val, %then_end], [%else_val, %else_end]

        // 首先求值条件表达式
        node.condition.accept(this)
        val condValue = node.condition.irValue
            ?: throw IRException("Condition of if expression has no IR value")

        // 获取当前函数以创建新的基本块
        val currentFunc = builder.myGetInsertFunction()
            ?: throw IRException("the if expr '$node' is not in a Function")

        // 获取if表达式的返回类型
        val resultType = getIRType(context, node.resolvedType)
        val isAggregate = resultType.isAggregate()
        val isUnitType = node.resolvedType is UnitResolvedType

        // 创建基本块
        val thenBB = currentFunc.createBasicBlock("if_then")
        val elseBB = currentFunc.createBasicBlock("if_else")
        val mergeBB = currentFunc.createBasicBlock("if_merge")

        // 条件跳转
        builder.createCondBr(condValue, thenBB, elseBB)

        // ===== Then 分支 =====
        builder.setInsertPoint(thenBB)
        node.thenBranch.accept(this)
        val thenValue = node.thenBranch.irValue
        val thenEndBB = builder.myGetInsertBlock() ?: thenBB // then分支结束块
        // 仅在当前块未终结时添加跳转（分支可能以 return/break/continue 结束）
        // 记录是否添加了跳转到 merge 块的指令
        val thenJumpsToMerge = !thenEndBB.isTerminated()
        if (thenJumpsToMerge) {
            builder.createBr(mergeBB)
        }

        // ===== Else 分支 =====
        builder.setInsertPoint(elseBB)
        val elseValue: Value?
        if (node.elseBranch != null) {
            node.elseBranch.accept(this)
            elseValue = node.elseBranch.irValue
        } else {
            // 没有else分支时，返回Unit类型，irValue为null
            elseValue = null
        }
        val elseEndBB = builder.myGetInsertBlock() ?: elseBB // else分支结束块
        // 仅在当前块未终结时添加跳转（分支可能以 return/break/continue 结束）
        // 记录是否添加了跳转到 merge 块的指令
        val elseJumpsToMerge = !elseEndBB.isTerminated()
        if (elseJumpsToMerge) {
            builder.createBr(mergeBB)
        }

        // ===== Merge 块 =====
        builder.setInsertPoint(mergeBB)

        // 根据返回类型设置 irValue
        if (isUnitType) {
            // Unit类型不需要PHI节点
            node.irValue = null
            node.irAddr = null
        } else if (isAggregate) {
            // 聚合类型（struct/array）使用PHI节点返回指针
            // 只有实际跳转到 merge 块的分支才参与 PHI
            val thenIncoming = if (thenJumpsToMerge && thenValue != null) thenValue to thenEndBB else null
            val elseIncoming = if (elseJumpsToMerge && elseValue != null) elseValue to elseEndBB else null

            if (thenIncoming != null && elseIncoming != null) {
                val phi = builder.createPHI(context.myGetPointerType())
                phi.addIncoming(thenIncoming.first, thenIncoming.second)
                phi.addIncoming(elseIncoming.first, elseIncoming.second)
                node.irValue = phi
            } else {
                // 只有一个分支跳转到 merge 块，或都不跳转
                node.irValue = thenIncoming?.first ?: elseIncoming?.first
            }
            node.irAddr = null // IfExpr 不能做左值
        } else {
            // 标量类型使用PHI节点
            // 只有实际跳转到 merge 块的分支才参与 PHI
            val thenIncoming = if (thenJumpsToMerge && thenValue != null) thenValue to thenEndBB else null
            val elseIncoming = if (elseJumpsToMerge && elseValue != null) elseValue to elseEndBB else null

            if (thenIncoming != null && elseIncoming != null) {
                val phi = builder.createPHI(resultType)
                phi.addIncoming(thenIncoming.first, thenIncoming.second)
                phi.addIncoming(elseIncoming.first, elseIncoming.second)
                node.irValue = phi
            } else {
                // 只有一个分支跳转到 merge 块，或都不跳转
                node.irValue = thenIncoming?.first ?: elseIncoming?.first
            }
            node.irAddr = null // IfExpr 不能做左值
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

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

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitContinueExpr(node: ContinueExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!!

        // 1. 获取当前循环上下文
        val loopContext = currentLoopContext()
            ?: throw IRException("continue outside loop")

        // 2. 跳转到目标块
        // - while 循环：跳转到条件检查块
        // - loop 循环：跳转到循环体块开始
        builder.createBr(loopContext.getContinueTarget())

        // 3. continue 表达式的类型是 Never
        node.irValue = null
        node.irAddr = null

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    /**
     * 向上查找 FunctionScope
     */
    private fun findFunctionScope(scope: Scope): FunctionScope? {
        var current: Scope? = scope
        while (current != null) {
            if (current is FunctionScope) {
                return current
            }
            current = current.parent
        }
        return null
    }

    override fun visitReturnExpr(node: ReturnExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        // 获取返回块
        val returnBB = currentReturnBlock
            ?: throw IRException("ReturnExpr not in a function with return block")

        // 判断是否为 main 函数
        val isMainFunction = currentFunctionName == "main"

        if (isMainFunction) {
            throw IRException("main function must end with a exit(0)")
        } else {
            // 非 main 函数：使用统一返回约定
            val retPtr = currentReturnBufferPtr
                ?: throw IRException("ReturnExpr not in a function with return buffer")

            // 获取当前函数的返回类型
            val funcScope = findFunctionScope(scopeTree.currentScope)
                ?: throw IRException("ReturnExpr not in a function scope")
            val originalReturnType = funcScope.returnType

            // 计算返回值的 IR 类型（UnitType会被当作i8处理）
            val returnValueIRType = getIRType(context, originalReturnType)

            if (node.value != null) {
                // 有返回值表达式
                node.value.accept(this)
                val returnValue = node.value.irValue
                    ?: throw IRException("Return value expression has no IR value")

                // 将返回值写入返回缓冲区
                when (returnValueIRType) {
                    is StructType -> {
                        // 结构体：使用 memcpy
                        val structName = (node.value.resolvedType as? NamedResolvedType)?.name
                            ?: throw IRException("Expected NamedResolvedType for struct return")
                        val sizeFunc = module.myGetFunction("${structName}.size")
                            ?: throw IRException("missing sizeFunc for struct '$structName'")
                        val size = builder.createCall(sizeFunc, emptyList())
                        builder.createMemCpy(retPtr, returnValue, size, false)
                    }

                    is ArrayType -> {
                        // 数组：使用 memcpy
                        val size = getArrayCopySize(returnValueIRType)
                        builder.createMemCpy(retPtr, returnValue, size, false)
                    }

                    else -> {
                        // 标量类型：使用 store
                        builder.createStore(returnValue, retPtr)
                    }
                }
            } else {
                // 无返回值表达式（隐式返回 Unit）
                // 写入 i8 0 作为 Unit 的返回值
                val zero = context.myGetIntConstant(context.myGetI8Type(), 0U)
                builder.createStore(zero, retPtr)
            }

            // 跳转到返回块
            builder.createBr(returnBB)
        }

        // return 表达式的类型是 Never，没有值
        node.irValue = null
        node.irAddr = null

        scopeTree.currentScope = previousScope // 还原scope状态
    }
}