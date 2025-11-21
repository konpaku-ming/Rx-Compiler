package ir

import exception.SemanticException
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
import ast.EnumSymbol
import ast.ExprStmtNode
import ast.FieldExprNode
import ast.FunctionItemNode
import ast.FunctionSymbol
import ast.GroupedExprNode
import ast.IdentifierPatternNode
import ast.IfExprNode
import ast.ImplItemNode
import ast.ImplScope
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
import ast.ReturnExprNode
import ast.StringLiteralExprNode
import ast.StructExprNode
import ast.StructItemNode
import ast.TraitItemNode
import ast.TypeCastExprNode
import ast.VariableSymbol
import ast.ResolvedType
import ast.ScopeKind
import ast.ScopeTree
import ast.StructSymbol
import ast.Symbol
import ast.TokenType
import ast.TraitScope
import ast.UnitResolvedType
import ast.VariantSymbol
import ast.stringToUInt
import exception.IRException

class LLVMIRGenerator(
    private val emitter: LLVMEmitter,
    private val scopeTree: ScopeTree
) : ASTVisitor {

    fun resolvePath(path: PathExprNode): Symbol {
        val firstSegment = path.first.segment
        val secondSegment = path.second?.segment
        when (firstSegment.type) {
            TokenType.IDENTIFIER -> {
                val symbol = scopeTree.lookup(firstSegment.value)
                    ?: throw SemanticException("cannot resolve path '$path'")
                // 解析第一层
                when (symbol) {
                    is VariableSymbol,
                    is ConstantSymbol,
                    is FunctionSymbol -> {
                        if (secondSegment == null) return symbol
                        else throw SemanticException("cannot resolve path '$path'")
                    }

                    is StructSymbol -> {
                        if (secondSegment == null) {
                            return symbol
                        } else {
                            val secondName = secondSegment.value
                            return symbol.constants[secondName]
                                ?: symbol.functions[secondName]
                                ?: symbol.methods[secondName]
                                ?: throw SemanticException("cannot resolve path '$path'")
                        }
                    }

                    is EnumSymbol -> {
                        if (secondSegment == null) {
                            throw SemanticException("cannot resolve path '$path'")
                        } else {
                            val secondName = secondSegment.value
                            if (symbol.variants.contains(secondName)) {
                                return VariantSymbol(
                                    name = secondName,
                                    type = NamedResolvedType(symbol.name, symbol),
                                )
                            } else throw SemanticException("cannot resolve path '$path'")
                        }
                    }

                    else -> throw SemanticException("cannot resolve path '$path'")
                }
            }

            TokenType.SELF -> {
                // self
                val symbol = scopeTree.lookup("self")
                    ?: throw SemanticException("cannot resolve path '$path'")
                if (symbol !is VariableSymbol)
                    throw SemanticException("cannot resolve path '$path'")
                if (secondSegment != null)
                    throw SemanticException("cannot resolve path '$path'")
                return symbol
            }

            TokenType.SELF_CAP -> {
                if (secondSegment == null)
                    throw SemanticException("cannot resolve path '$path'")
                // Self
                var targetScope = scopeTree.currentScope
                while (targetScope.kind != ScopeKind.Impl &&
                    targetScope.kind != ScopeKind.Trait &&
                    targetScope.parent != null
                ) {
                    targetScope = targetScope.parent!!
                }
                when (targetScope) {
                    is ImplScope -> {
                        val symbol = (targetScope.implType as NamedResolvedType).symbol as StructSymbol
                        val secondName = secondSegment.value
                        return if (symbol.constants[secondName] != null)
                            symbol.constants[secondName]!!
                        else if (symbol.functions[secondName] != null)
                            symbol.functions[secondName]!!
                        else if (symbol.methods[secondName] != null)
                            symbol.methods[secondName]!!
                        else throw SemanticException("cannot resolve path '$path'")
                    }

                    is TraitScope -> {
                        val symbol = targetScope.traitSymbol
                        val secondName = secondSegment.value
                        return if (symbol.constants[secondName] != null)
                            symbol.constants[secondName]!!
                        else if (symbol.functions[secondName] != null)
                            symbol.functions[secondName]!!
                        else if (symbol.methods[secondName] != null)
                            symbol.methods[secondName]!!
                        else throw SemanticException("cannot resolve path '$path'")
                    }

                    else -> throw SemanticException(
                        "self should be in impl/trait scope"
                    )
                }
            }

            else -> throw SemanticException("cannot resolve path '$path'")
        }
    }

    lateinit var result: String

    override fun visitCrate(node: CrateNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!!

        // 遍历所有顶级项（函数等）
        for (item in node.items) {
            item.accept(this)
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitStructItem(node: StructItemNode) {
        TODO("Not yet implemented")
    }

    override fun visitConstantItem(node: ConstantItemNode) {
        TODO("Not yet implemented")
    }

    override fun visitEnumItem(node: EnumItemNode) {
        TODO("Not yet implemented")
    }

    override fun visitTraitItem(node: TraitItemNode) {
        TODO("Not yet implemented")
    }

    override fun visitImplItem(node: ImplItemNode) {
        TODO("Not yet implemented")
    }

    override fun visitFunctionItem(node: FunctionItemNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!!

        val fnName = node.fnName.value
        val functionSymbol = scopeTree.lookup(fnName)
        if (functionSymbol == null || functionSymbol !is FunctionSymbol) {
            throw SemanticException("missing FunctionSymbol")
        }
        // 获取返回类型的 LLVM 类型
        val returnType = if (functionSymbol.returnType == UnitResolvedType) "void" else {
            getLLVMType(functionSymbol.returnType)
        }

        // 构建参数列表
        val params = mutableListOf<String>()
        for (param in functionSymbol.parameters) {
            val paramType = getLLVMType(param.paramType)
            val paramName = param.name
            params.add("$paramType %$paramName")
        }

        // 定义函数
        emitter.startFunction(fnName = fnName, retType = returnType, params = params)
        if (node.body == null) throw IRException("Function body is null")
        node.body.accept(this)
        if (!node.body.isBottom) {
            // 依赖尾表达式返回时
            val bodyType = getLLVMType(node.body.resolvedType)
            if (emitter.currentRetType == "void") {
                emitter.emit("br label %end")
            } else {
                val temp = emitter.newTemp()
                emitter.emit("%$temp = load ${bodyType}, ptr ${node.body.irAddress}")
                emitter.emit("store $bodyType $temp, ptr ${fnName}.retval_ptr")
                emitter.emit("br label %end")
            }
        }
        emitter.endFunction()

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitEmptyStmt(node: EmptyStmtNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitBlockExpr(node: BlockExprNode, createScope: Boolean) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!!

        val blockType = getLLVMType(node.resolvedType)
        val blockAddr = emitter.newTemp()
        node.irAddress = blockAddr
        emitter.emit("$blockAddr = alloca $blockType")

        // 处理块内的项
        for (item in node.items) {
            item.accept(this)
        }
        // 处理语句
        for (stmt in node.statements) {
            stmt.accept(this)
        }
        // 处理尾表达式
        if (node.tailExpr != null) {
            node.tailExpr.accept(this)
            val blockValue = result
            emitter.emit("store $blockType $blockValue, ptr $blockAddr")
        } else {
            emitter.emit("store $blockType 0, ptr $blockAddr")
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitLetStmt(node: LetStmtNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!!

        // 获取变量名
        val varName = when (val pattern = node.pattern) {
            is IdentifierPatternNode -> pattern.name.value
            else -> throw IRException("invalid parameter name: '$pattern'")
        }
        // 获取变量类型的 LLVM 类型
        val varType = getLLVMType(node.variableResolvedType)
        // 分配栈空间
        val varAddress = "%$varName." + emitter.newVarCount()
        emitter.emit("$varAddress = alloca $varType")
        // 计算初始值
        node.value.accept(this)
        val initValue = result
        // 存储初始值
        emitter.emit("store $varType $initValue, ptr $varAddress")
        val symbol = scopeTree.lookup(varName) ?: throw IRException("variable symbol not found")
        if (symbol !is VariableSymbol) throw IRException("symbol is not variable symbol")
        symbol.irAddress = varAddress

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitIntLiteralExpr(node: IntLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        result = stringToUInt(node.raw).toInt().toString()

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitCharLiteralExpr(node: CharLiteralExprNode) {
        TODO("Not yet implemented")
    }

    override fun visitStringLiteralExpr(node: StringLiteralExprNode) {
        TODO("Not yet implemented")
    }

    override fun visitBooleanLiteralExpr(node: BooleanLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!!

        result = if (node.raw == "true") "1" else "0"

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitCStringLiteralExpr(node: CStringLiteralExprNode) {
        TODO("Not yet implemented")
    }

    override fun visitRawStringLiteralExpr(node: RawStringLiteralExprNode) {
        TODO("Not yet implemented")
    }

    override fun visitRawCStringLiteralExpr(node: RawCStringLiteralExprNode) {
        TODO("Not yet implemented")
    }

    override fun visitPathExpr(node: PathExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        when (val symbol = resolvePath(node)) {
            is VariableSymbol -> {
                val varType = getLLVMType(symbol.type)
                val temp = emitter.newTemp()
                emitter.emit("$temp = load $varType, ptr ${symbol.irAddress}")
                result = temp
            }

            is ConstantSymbol -> {
                result = symbol.irValue ?: throw IRException("constant IR value is null")
            }

            // TODO:more cases
            else -> throw SemanticException("cannot resolve path expression")
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitBorrowExpr(node: BorrowExprNode) {
        TODO("Not yet implemented")
    }

    override fun visitDerefExpr(node: DerefExprNode) {
        TODO("Not yet implemented")
    }

    override fun visitNegationExpr(node: NegationExprNode) {
        TODO("Not yet implemented")
    }

    override fun visitBinaryExpr(node: BinaryExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        
        // 访问左操作数并获取其结果
        node.left.accept(this)
        val leftValue = result
        
        // 访问右操作数并获取其结果
        node.right.accept(this)
        val rightValue = result
        
        // 获取操作数的LLVM类型
        val resultType = getLLVMType(node.resolvedType)
        
        // 根据操作符类型生成对应的LLVM指令
        val temp = emitter.newTemp()
        when (node.operator.type) {
            TokenType.Add -> {
                emitter.emit("$temp = add $resultType $leftValue, $rightValue")
            }
            TokenType.SubNegate -> {
                emitter.emit("$temp = sub $resultType $leftValue, $rightValue")
            }
            TokenType.Mul -> {
                emitter.emit("$temp = mul $resultType $leftValue, $rightValue")
            }
            TokenType.Div -> {
                // 使用sdiv进行有符号除法
                emitter.emit("$temp = sdiv $resultType $leftValue, $rightValue")
            }
            TokenType.Mod -> {
                // 使用srem进行有符号取模
                emitter.emit("$temp = srem $resultType $leftValue, $rightValue")
            }
            TokenType.BitAnd -> {
                emitter.emit("$temp = and $resultType $leftValue, $rightValue")
            }
            TokenType.BitOr -> {
                emitter.emit("$temp = or $resultType $leftValue, $rightValue")
            }
            TokenType.BitXor -> {
                emitter.emit("$temp = xor $resultType $leftValue, $rightValue")
            }
            TokenType.Shl -> {
                emitter.emit("$temp = shl $resultType $leftValue, $rightValue")
            }
            TokenType.Shr -> {
                // 使用ashr进行算术右移
                emitter.emit("$temp = ashr $resultType $leftValue, $rightValue")
            }
            else -> throw IRException("Unsupported binary operator: ${node.operator}")
        }
        
        result = temp
        
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitComparisonExpr(node: ComparisonExprNode) {
        TODO("Not yet implemented")
    }

    override fun visitLazyBooleanExpr(node: LazyBooleanExprNode) {
        TODO("Not yet implemented")
    }

    override fun visitTypeCastExpr(node: TypeCastExprNode) {
        TODO("Not yet implemented")
    }

    override fun visitAssignExpr(node: AssignExprNode) {
        TODO("Not yet implemented")
    }

    override fun visitCompoundAssignExpr(node: CompoundAssignExprNode) {
        TODO("Not yet implemented")
    }

    override fun visitGroupedExpr(node: GroupedExprNode) {
        TODO("Not yet implemented")
    }

    override fun visitArrayListExpr(node: ArrayListExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        // 获取数组元素类型
        val arrayType = getLLVMType(node.resolvedType)
        val elementType = getLLVMType(
            type = (node.resolvedType as? ArrayResolvedType)?.elementType
                ?: throw SemanticException("array type is not array")
        )
        // 分配数组空间
        val arrayAddr = emitter.newTemp()
        emitter.emit("$arrayAddr = alloca $arrayType")
        // 计算每个元素并存储
        node.elements.forEachIndexed { index, element ->
            element.accept(this)
            val elementValue = result
            val elemAddr = emitter.newTemp()
            emitter.emit("$elemAddr = getelementptr $arrayType, ptr $arrayAddr, i32 0, i32 $index")
            emitter.emit("store $elementType $elementValue, ptr $elemAddr")
        }
        val arrayTemp = emitter.newTemp()
        emitter.emit("$arrayTemp = load $arrayType, ptr $arrayAddr")
        result = arrayTemp

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitArrayLengthExpr(node: ArrayLengthExprNode) {
        TODO("Not yet implemented")
    }

    override fun visitIndexExpr(node: IndexExprNode) {
        TODO("Not yet implemented")
    }

    override fun visitStructExpr(node: StructExprNode) {
        TODO("Not yet implemented")
    }

    override fun visitCallExpr(node: CallExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        val funcSymbol = if (node.func is PathExprNode) {
            resolvePath(node.func) as? FunctionSymbol
                ?: throw SemanticException("${node.func} does not refer to function")
        } else throw SemanticException(
            "wanted function path"
        )
        if (funcSymbol.name == "exit") {
            emitter.emit("br label %end")
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitMethodCallExpr(node: MethodCallExprNode) {
        TODO("Not yet implemented")
    }

    override fun visitFieldExpr(node: FieldExprNode) {
        TODO("Not yet implemented")
    }

    override fun visitIfExpr(node: IfExprNode) {
        TODO("Not yet implemented")
    }

    override fun visitInfiniteLoopExpr(node: InfiniteLoopExprNode) {
        TODO("Not yet implemented")
    }

    override fun visitPredicateLoopExpr(node: PredicateLoopExprNode) {
        TODO("Not yet implemented")
    }

    override fun visitBreakExpr(node: BreakExprNode) {
        TODO("Not yet implemented")
    }

    override fun visitContinueExpr(node: ContinueExprNode) {
        TODO("Not yet implemented")
    }

    override fun visitReturnExpr(node: ReturnExprNode) {
        TODO("Not yet implemented")
    }

    override fun visitExprStmt(node: ExprStmtNode) {
        node.expr.accept(this)
    }

    // 辅助函数：从 ResolvedType 获取基础元素类型
    private fun getLLVMType(type: ResolvedType): String {
        return when (type) {
            is PrimitiveResolvedType -> {
                when (type.name) {
                    "i32", "u32" -> "i32"
                    "isize", "usize" -> "i32"
                    "int", "signed int", "unsigned int" -> "i32"
                    "bool" -> "i1"
                    "char" -> "i32"
                    "str" -> throw IRException("str type in IR")
                    else -> throw IRException("unknown type '$type'")
                }
            }

            is ArrayResolvedType -> {
                val elementType = getLLVMType(type.elementType)
                "[${type.length} x $elementType]"
            }

            is UnitResolvedType -> "i8" // unit type as i8 0
            is NeverResolvedType -> "i8" // unit type as i8 0

            is ReferenceResolvedType -> "ptr"

            else -> throw IRException("unknown type '$type'") // TODO: more types
        }
    }
}
