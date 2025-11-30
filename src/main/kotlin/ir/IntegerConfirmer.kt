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
import ast.EnumSymbol
import ast.ExprStmtNode
import ast.FieldExprNode
import ast.FunctionItemNode
import ast.FunctionScope
import ast.FunctionSymbol
import ast.GroupedExprNode
import ast.IfExprNode
import ast.ImplItemNode
import ast.ImplScope
import ast.IndexExprNode
import ast.InfiniteLoopExprNode
import ast.IntLiteralExprNode
import ast.LazyBooleanExprNode
import ast.LetStmtNode
import ast.LoopScope
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
import ast.ScopeKind
import ast.ScopeTree
import ast.StringLiteralExprNode
import ast.StructExprNode
import ast.StructItemNode
import ast.StructSymbol
import ast.Symbol
import ast.TokenType
import ast.TraitItemNode
import ast.TraitScope
import ast.TypeCastExprNode
import ast.VariableSymbol
import ast.VariantSymbol
import ast.isInt
import ast.isSignedInt
import ast.isUnsignedInt
import exception.IRException
import exception.SemanticException

class IntegerConfirmer(
    private val scopeTree: ScopeTree,
) : ASTVisitor {
    fun resolvePath(path: PathExprNode): Symbol {
        val firstSegment = path.first.segment
        val secondSegment = path.second?.segment
        when (firstSegment.type) {
            TokenType.IDENTIFIER -> {
                val symbol = scopeTree.lookup(firstSegment.value)
                    ?: throw IRException("cannot resolve path '$path'")
                // 解析第一层
                when (symbol) {
                    is VariableSymbol,
                    is ConstantSymbol,
                    is FunctionSymbol -> {
                        if (secondSegment == null) return symbol
                        else throw IRException("cannot resolve path '$path'")
                    }

                    is StructSymbol -> {
                        if (secondSegment == null) {
                            return symbol
                        } else {
                            val secondName = secondSegment.value
                            return symbol.constants[secondName]
                                ?: symbol.functions[secondName]
                                ?: symbol.methods[secondName]
                                ?: throw IRException("cannot resolve path '$path'")
                        }
                    }

                    is EnumSymbol -> {
                        if (secondSegment == null) {
                            throw IRException("cannot resolve path '$path'")
                        } else {
                            val secondName = secondSegment.value
                            if (symbol.variants.contains(secondName)) {
                                return VariantSymbol(
                                    name = secondName,
                                    type = NamedResolvedType(symbol.name, symbol),
                                )
                            } else throw IRException("cannot resolve path '$path'")
                        }
                    }

                    else -> throw IRException("cannot resolve path '$path'")
                }
            }

            TokenType.SELF -> {
                // self
                val symbol = scopeTree.lookup("self")
                    ?: throw IRException("cannot resolve path '$path'")
                if (symbol !is VariableSymbol)
                    throw IRException("cannot resolve path '$path'")
                if (secondSegment != null)
                    throw IRException("cannot resolve path '$path'")
                return symbol
            }

            TokenType.SELF_CAP -> {
                if (secondSegment == null)
                    throw IRException("cannot resolve path '$path'")
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
                        else throw IRException("cannot resolve path '$path'")
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
                        else throw IRException("cannot resolve path '$path'")
                    }

                    else -> throw IRException(
                        "self should be in impl/trait scope"
                    )
                }
            }

            else -> throw IRException("cannot resolve path '$path'")
        }
    }

    fun typeUnify(left: ResolvedType, right: ResolvedType): ResolvedType {
        if (left == right) return left
        if (left == NeverResolvedType) return right
        if (right == NeverResolvedType) return left
        if (left == PrimitiveResolvedType("int") && isInt(right)) return right
        if (right == PrimitiveResolvedType("int") && isInt(left)) return left
        if (left == PrimitiveResolvedType("signed int") && isSignedInt(right))
            return right
        if (right == PrimitiveResolvedType("signed int") && isSignedInt(left))
            return left
        if (left == PrimitiveResolvedType("unsigned int") && isUnsignedInt(right))
            return right
        if (right == PrimitiveResolvedType("unsigned int") && isUnsignedInt(left))
            return left
        throw IRException("$left does not match with $right")
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

        val fnName = node.fnName.value
        val functionSymbol = scopeTree.lookup(fnName)
        if (functionSymbol == null || functionSymbol !is FunctionSymbol) {
            throw IRException("missing FunctionSymbol")
        }

        if (node.body != null) {
            if (!node.body.isBottom) node.body.resolvedType = functionSymbol.returnType
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
        if (node.tailExpr != null) {
            node.tailExpr.resolvedType = node.resolvedType
            node.tailExpr.accept(this)
        }

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

        node.value.resolvedType = node.variableResolvedType
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

        if (node.resolvedType != PrimitiveResolvedType("i32") &&
            node.resolvedType != PrimitiveResolvedType("u32") &&
            node.resolvedType != PrimitiveResolvedType("isize") &&
            node.resolvedType != PrimitiveResolvedType("usize")
        ) {
            throw IRException("cannot confirm integer type in literal '${node.raw}'")
        }
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

        node.expr.resolvedType = (node.resolvedType as? ReferenceResolvedType
            ?: throw IRException("invalid resolved type")).inner
        node.expr.accept(this)

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitDerefExpr(node: DerefExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        (node.expr.resolvedType as ReferenceResolvedType).inner = node.resolvedType
        node.expr.accept(this)

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitNegationExpr(node: NegationExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.expr.resolvedType = node.resolvedType
        node.expr.accept(this)

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitBinaryExpr(node: BinaryExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        when (node.operator.type) {
            TokenType.Add, TokenType.SubNegate, TokenType.Mul, TokenType.Div, TokenType.Mod,
            TokenType.BitAnd, TokenType.BitOr, TokenType.BitXor -> {
                node.left.resolvedType = node.resolvedType
                node.right.resolvedType = node.resolvedType
            }

            TokenType.Shl, TokenType.Shr -> {
                node.left.resolvedType = node.resolvedType
            }

            else -> throw SemanticException(
                "Unsupported binary operator '${node.operator}'"
            )
        }

        node.left.accept(this)
        node.right.accept(this)

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitComparisonExpr(node: ComparisonExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        val type = typeUnify(node.left.resolvedType, node.right.resolvedType)
        node.left.resolvedType = type
        node.right.resolvedType = type
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

        node.right.resolvedType = node.left.resolvedType
        node.left.accept(this)
        node.right.accept(this)

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitCompoundAssignExpr(node: CompoundAssignExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.right.resolvedType = node.left.resolvedType
        node.left.accept(this)
        node.right.accept(this)

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitGroupedExpr(node: GroupedExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.inner.resolvedType = node.resolvedType
        node.inner.accept(this)

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitArrayListExpr(node: ArrayListExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        val elementType = (node.resolvedType as? ArrayResolvedType
            ?: throw IRException("invalid resolved type")).elementType
        for (element in node.elements) {
            element.resolvedType = elementType
            element.accept(this)
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitArrayLengthExpr(node: ArrayLengthExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.element.resolvedType = (node.resolvedType as? ArrayResolvedType
            ?: throw IRException("invalid resolved type")).elementType
        node.element.accept(this)
        node.lengthExpr.resolvedType = PrimitiveResolvedType("usize")
        node.lengthExpr.accept(this)

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitIndexExpr(node: IndexExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope\

        when (node.base.resolvedType) {
            is ArrayResolvedType -> {
                (node.base.resolvedType as ArrayResolvedType).elementType = node.resolvedType
            }

            is ReferenceResolvedType -> {
                ((node.base.resolvedType as ReferenceResolvedType).inner as ArrayResolvedType).elementType =
                    node.resolvedType
            }

            else -> {
                throw IRException("invalid resolved type")
            }
        }
        node.base.accept(this)
        node.index.resolvedType = PrimitiveResolvedType("usize")
        node.index.accept(this)

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitStructExpr(node: StructExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.path.accept(this)
        val symbol = resolvePath(node.path)
        if (symbol !is StructSymbol) throw IRException(
            "path '${node.path}' does not refer to a struct"
        )

        for (field in node.fields) {
            val fieldName = field.name.value
            val fieldType = symbol.fields[fieldName] ?: throw IRException(
                "Unknown field '$fieldName' in struct '${symbol.name}'"
            )
            field.value.resolvedType = fieldType
            field.value.accept(this)
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitCallExpr(node: CallExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.func.accept(this)
        val funcSymbol = if (node.func is PathExprNode) {
            resolvePath(node.func) as? FunctionSymbol
                ?: throw IRException("${node.func} does not refer to function")
        } else throw IRException(
            "expected function path"
        )

        if (funcSymbol.isMethod) {
            val selfParam = funcSymbol.selfParameter!!
            val selfType = if (selfParam.isRef) {
                ReferenceResolvedType(inner = selfParam.paramType, isMut = selfParam.isMut)
            } else {
                selfParam.paramType
            }
            node.params[0].resolvedType = selfType
            node.params[0].accept(this)
            for (index in 1..<node.params.size) {
                val param = node.params[index]
                val targetType = funcSymbol.parameters[index - 1].paramType
                param.resolvedType = targetType
                param.accept(this)
            }
        } else {
            for (index in 0..<node.params.size) {
                val param = node.params[index]
                val targetType = funcSymbol.parameters[index].paramType
                param.resolvedType = targetType
                param.accept(this)
            }
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitMethodCallExpr(node: MethodCallExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.receiver.accept(this)
        val methodName = node.method.segment.value
        when (val receiverType = node.receiver.resolvedType) {
            is NamedResolvedType -> {
                val structSymbol = receiverType.symbol as? StructSymbol
                    ?: throw IRException("method receiver should be a struct")
                val method = structSymbol.methods[methodName]
                    ?: throw IRException("undefined method '$methodName'")
                for (index in 0..<node.params.size) {
                    val param = node.params[index]
                    param.resolvedType = method.parameters[index].paramType
                    param.accept(this)
                }
            }

            is ReferenceResolvedType if receiverType.inner is NamedResolvedType -> {
                val structSymbol = (receiverType.inner as NamedResolvedType).symbol as? StructSymbol
                    ?: throw IRException("method receiver should be a struct")
                val method = structSymbol.methods[methodName]
                    ?: throw IRException("undefined method '$methodName'")
                for (index in 0..<node.params.size) {
                    val param = node.params[index]
                    param.resolvedType = method.parameters[index].paramType
                    param.accept(this)
                }
                node.resolvedType = method.returnType
            }

            else -> {}
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
        node.thenBranch.resolvedType = node.resolvedType
        node.elseBranch?.resolvedType = node.resolvedType
        node.thenBranch.accept(this)
        node.elseBranch?.accept(this)

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitBreakExpr(node: BreakExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        var targetScope = scopeTree.currentScope // 检查是否在循环中
        while (targetScope.kind != ScopeKind.Loop &&
            targetScope.kind != ScopeKind.Function &&
            targetScope.parent != null
        ) {
            targetScope = targetScope.parent!!
        }
        if (targetScope is LoopScope) {
            node.value?.resolvedType = targetScope.breakType
        } else throw IRException("break outside loop")
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

        var targetScope = scopeTree.currentScope // 检查是否在循环中
        while (targetScope.kind != ScopeKind.Function && targetScope.parent != null) {
            targetScope = targetScope.parent!!
        }
        if (targetScope is FunctionScope) {
            node.value?.resolvedType = targetScope.returnType
        } else throw IRException("return outside function")
        node.value?.accept(this)

        scopeTree.currentScope = previousScope // 还原scope状态
    }
}