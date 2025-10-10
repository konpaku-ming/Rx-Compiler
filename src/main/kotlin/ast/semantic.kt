package ast

import exception.SemanticException
import exception.SyntaxException

sealed interface ResolvedType {
    val name: String
}

data class NamedResolvedType(
    override val name: String,
    var symbol: Symbol = UnknownSymbol() // 之后要链接到对应的Symbol
) : ResolvedType

data class PrimitiveResolvedType(
    override val name: String
) : ResolvedType

data class ReferenceResolvedType(
    val inner: ResolvedType,
    val isMut: Boolean
) : ResolvedType {
    override val name: String = if (isMut) {
        "&mut " + inner.name
    } else {
        "&" + inner.name
    }
}

data class ArrayResolvedType(
    val elementType: ResolvedType,
    val lengthExpr: ExprNode,
) : ResolvedType {
    var length: Int = -1 // -1 表示未求值
    override var name: String = "[${elementType.name};?]"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ArrayResolvedType) return false
        return typeCheck(elementType, other.elementType)
                && length == other.length && length != -1
    }

    override fun toString(): String {
        return "Array(elementType='$name', length=$length)"
    }

    override fun hashCode(): Int {
        var result = length
        result = 31 * result + elementType.hashCode()
        result = 31 * result + lengthExpr.hashCode()
        result = 31 * result + name.hashCode()
        return result
    }
}

object UnitResolvedType : ResolvedType {
    override val name: String = "()"
}

object NeverResolvedType : ResolvedType {
    override val name: String = "bottom"
}

// 未知类型，待推断
object UnknownResolvedType : ResolvedType {
    override val name: String = "<unknown>"

    override fun equals(other: Any?): Boolean {
        return false
    }
}

class FirstVisitor(private val scopeTree: ScopeTree) : ASTVisitor {
    fun resolveType(node: TypeNode): ResolvedType {
        when (node) {
            is TypePathNode -> {
                when (val name = node.path.segment.value) {
                    "Self" -> {
                        var targetScope = scopeTree.currentScope
                        while (targetScope.kind != ScopeKind.Impl &&
                            targetScope.kind != ScopeKind.Trait &&
                            targetScope.parent != null
                        ) {
                            targetScope = targetScope.parent!!
                        }
                        when (targetScope) {
                            is ImplScope -> {
                                return targetScope.implType
                            }

                            is TraitScope -> {
                                val name = targetScope.traitSymbol.name
                                return NamedResolvedType(name, targetScope.traitSymbol)
                                // trait的self类型
                            }

                            else -> throw SemanticException(
                                "self should be in impl/trait scope"
                            )
                        }
                    }

                    "u32" -> return PrimitiveResolvedType("u32")
                    "i32" -> return PrimitiveResolvedType("i32")
                    "usize" -> return PrimitiveResolvedType("usize")
                    "isize" -> return PrimitiveResolvedType("isize")
                    "bool" -> return PrimitiveResolvedType("bool")
                    "char" -> return PrimitiveResolvedType("char")
                    "str" -> return PrimitiveResolvedType("str")
                    else -> return NamedResolvedType(name = name) // 只记录名字
                }
            }

            is ReferenceTypeNode -> {
                val inner = resolveType(node.inner)
                return ReferenceResolvedType(inner, node.isMut)
            }

            is ArrayTypeNode -> {
                val element = resolveType(node.elementType)
                node.length.accept(this)
                return ArrayResolvedType(element, node.length)
            }

            is UnitTypeNode -> return UnitResolvedType
        }
    }

    override fun visitCrate(node: CrateNode) {
        var hasMain = false
        println("visiting Crate")
        node.scopePosition = scopeTree.currentScope

        // 添加builtin
        val exitFunction = FunctionSymbol(
            name = "exit",
            selfParameter = null,
            parameters = listOf(
                Parameter(
                    name = "code",
                    paramType = PrimitiveResolvedType("i32"),
                    isMut = false
                )
            ),
            returnType = UnitResolvedType,
            isMethod = false,
            isAssociated = false,
            isDefined = true
        )
        scopeTree.define(exitFunction)

        val printFunction = FunctionSymbol(
            name = "print",
            selfParameter = null,
            parameters = listOf(
                Parameter(
                    name = "s",
                    paramType = ReferenceResolvedType(
                        inner = PrimitiveResolvedType("str"),
                        isMut = false
                    ),
                    isMut = false
                )
            ),
            returnType = UnitResolvedType,
            isMethod = false,
            isAssociated = false,
            isDefined = true
        )
        scopeTree.define(printFunction)

        val printlnFunction = FunctionSymbol(
            name = "println",
            selfParameter = null,
            parameters = listOf(
                Parameter(
                    name = "s",
                    paramType = ReferenceResolvedType(
                        inner = PrimitiveResolvedType("str"),
                        isMut = false
                    ),
                    isMut = false
                )
            ),
            returnType = UnitResolvedType,
            isMethod = false,
            isAssociated = false,
            isDefined = true
        )
        scopeTree.define(printlnFunction)

        val printIntFunction = FunctionSymbol(
            name = "printInt",
            selfParameter = null,
            parameters = listOf(
                Parameter(
                    name = "n",
                    paramType = PrimitiveResolvedType("i32"),
                    isMut = false
                )
            ),
            returnType = UnitResolvedType,
            isMethod = false,
            isAssociated = false,
            isDefined = true
        )
        scopeTree.define(printIntFunction)

        val printlnIntFunction = FunctionSymbol(
            name = "printlnInt",
            selfParameter = null,
            parameters = listOf(
                Parameter(
                    name = "n",
                    paramType = PrimitiveResolvedType("i32"),
                    isMut = false
                )
            ),
            returnType = UnitResolvedType,
            isMethod = false,
            isAssociated = false,
            isDefined = true
        )
        scopeTree.define(printlnIntFunction)

        val stringStruct = StructSymbol(
            name = "String",
            fields = mutableMapOf(),
            functions = mutableMapOf(),
            methods = mutableMapOf(),
            constants = mutableMapOf(),
        )
        val fromFunction = FunctionSymbol(
            name = "from",
            selfParameter = null,
            parameters = listOf(
                Parameter(
                    name = "s",
                    paramType = ReferenceResolvedType(
                        inner = PrimitiveResolvedType("str"),
                        isMut = false
                    ),
                    isMut = false
                )
            ),
            returnType = NamedResolvedType(name = "String", symbol = stringStruct),
            isMethod = false,
            isAssociated = true,
            isDefined = true
        )
        stringStruct.functions[fromFunction.name] = fromFunction
        val appendMethod = FunctionSymbol(
            name = "from",
            selfParameter = SelfParameter(
                paramType = NamedResolvedType(name = "String", symbol = stringStruct),
                isMut = true,
                isRef = true
            ),
            parameters = listOf(
                Parameter(
                    name = "s",
                    paramType = ReferenceResolvedType(
                        inner = PrimitiveResolvedType("str"),
                        isMut = false
                    ),
                    isMut = false
                )
            ),
            returnType = UnitResolvedType,
            isMethod = true,
            isAssociated = true,
            isDefined = true
        )
        stringStruct.methods[appendMethod.name] = appendMethod
        val lenMethod = FunctionSymbol(
            name = "len",
            selfParameter = SelfParameter(
                paramType = NamedResolvedType(name = "String", symbol = stringStruct),
                isMut = false,
                isRef = true
            ),
            parameters = emptyList(),
            returnType = PrimitiveResolvedType("usize"),
            isMethod = true,
            isAssociated = true,
            isDefined = true
        )
        stringStruct.methods[lenMethod.name] = lenMethod
        val asStrMethod = FunctionSymbol(
            name = "as_str",
            selfParameter = SelfParameter(
                paramType = NamedResolvedType(name = "String", symbol = stringStruct),
                isMut = false,
                isRef = true
            ),
            parameters = emptyList(),
            returnType = ReferenceResolvedType(
                inner = PrimitiveResolvedType("str"),
                isMut = false
            ),
            isMethod = true,
            isAssociated = true,
            isDefined = true
        )
        stringStruct.methods[asStrMethod.name] = asStrMethod
        val asMutStrMethod = FunctionSymbol(
            name = "as_mut_str",
            selfParameter = SelfParameter(
                paramType = NamedResolvedType(name = "String", symbol = stringStruct),
                isMut = true,
                isRef = true
            ),
            parameters = emptyList(),
            returnType = ReferenceResolvedType(
                inner = PrimitiveResolvedType("str"),
                isMut = true
            ),
            isMethod = true,
            isAssociated = true,
            isDefined = true
        )
        stringStruct.methods[asMutStrMethod.name] = asMutStrMethod
        scopeTree.define(stringStruct)

        val getStringFunction = FunctionSymbol(
            name = "getString",
            selfParameter = null,
            parameters = emptyList(),
            returnType = NamedResolvedType(name = "String", symbol = stringStruct),
            isMethod = false,
            isAssociated = false,
            isDefined = true
        )
        scopeTree.define(getStringFunction)

        val getIntFunction = FunctionSymbol(
            name = "getInt",
            selfParameter = null,
            parameters = emptyList(),
            returnType = PrimitiveResolvedType("i32"),
            isMethod = false,
            isAssociated = false,
            isDefined = true
        )
        scopeTree.define(getIntFunction)
        // TODO more builtin

        // 依次visit每个item
        for (item in node.items) {
            if (item is FunctionItemNode && item.fnName.value == "main") {
                hasMain = true
                if (item.returnType != null) throw SemanticException(
                    "main function must return unit type"
                )
            }
            item.accept(this)
        }
        if (!hasMain) throw SemanticException("missing main function")
    }

    override fun visitStructItem(node: StructItemNode) {
        println("visiting StructItem")
        node.scopePosition = scopeTree.currentScope
        val structName = node.structName.value
        if (scopeTree.lookup(structName) != null) {
            throw SemanticException("struct redeclaration : '$structName'")
        }
        // struct fields
        val fields = mutableMapOf<String, ResolvedType>()
        if (node.fields != null) {
            for (field in node.fields) {
                val fieldName = field.name.value
                if (fields.containsKey(fieldName)) {
                    throw SemanticException(
                        "Duplicate field '$fieldName' in struct '$structName'"
                    )
                } else {
                    val fieldType = resolveType(field.type)
                    fields[fieldName] = fieldType
                }
            }
        }
        // 加入符号表
        val symbol = StructSymbol(name = structName, fields = fields)
        scopeTree.define(symbol)
    }

    override fun visitConstantItem(node: ConstantItemNode) {
        println("visiting ConstantItem")
        node.scopePosition = scopeTree.currentScope
        val constName = node.constantName.value
        var targetScope = scopeTree.currentScope
        while (targetScope.kind != ScopeKind.Impl &&
            targetScope.kind != ScopeKind.Trait &&
            targetScope.parent != null
        ) {
            targetScope = targetScope.parent!!
        }
        val isAssociated = targetScope is ImplScope || targetScope is TraitScope

        // 检查重定义
        val alreadyDefined = when (targetScope) {
            is TraitScope -> {
                targetScope.lookupLocal(constName) // 在trait块里找
            }

            is ImplScope -> {
                targetScope.lookupLocal(constName) // 在impl块里找
            }

            else -> scopeTree.lookup(constName) // 在当前scope里找
        }

        if (alreadyDefined != null) {
            throw SemanticException(
                "Duplicate ConstantItem '$constName'"
            )
        }
        val constType = resolveType(node.constantType)

        node.value?.accept(this)
        val valueExpr = node.value
        val symbol = ConstantSymbol(
            name = constName,
            type = constType,
            valueExpr = valueExpr,
            value = null, // 暂时不能解析出值
            isAssociated = isAssociated,
            isDefined = node.value != null
        )

        when (targetScope) {
            is TraitScope -> {
                targetScope.define(symbol) // 在scope里记录
                targetScope.traitSymbol.constants[constName] = symbol // 挂到trait底下
            }

            is ImplScope -> {
                targetScope.define(symbol)
                // 第二次pass再把constant挂载到struct的底下
            }

            else -> scopeTree.define(symbol)
        }
    }

    override fun visitEnumItem(node: EnumItemNode) {
        println("visiting EnumItem")
        node.scopePosition = scopeTree.currentScope
        val enumName = node.enumName.value
        if (scopeTree.lookup(enumName) != null) {
            throw SemanticException("enum redeclaration: '$enumName'")
        }

        val variants = mutableListOf<String>()

        for (variant in node.variants) {
            val variantName = variant.value
            if (variants.contains(variantName)) {
                throw SemanticException(
                    "Duplicate field '$variantName' in struct '$enumName'"
                )
            } else {
                variants.add(variantName)
            }
        }
        val symbol = EnumSymbol(name = enumName, variants = variants)
        // 加入符号表
        scopeTree.define(symbol)
    }

    override fun visitTraitItem(node: TraitItemNode) {
        println("visiting TraitItem")
        node.scopePosition = scopeTree.currentScope
        val traitName = node.traitName.value
        if (scopeTree.lookup(traitName) != null) {
            throw SemanticException("trait redeclaration: '$traitName'")
        }
        val symbol = TraitSymbol(traitName)
        scopeTree.define(symbol)
        scopeTree.enterTraitScope(traitSymbol = symbol)
        for (item in node.items) {
            item.accept(this)
        }
        scopeTree.exitScope()
    }

    override fun visitImplItem(node: ImplItemNode) {
        println("visiting ImplItem")
        node.scopePosition = scopeTree.currentScope
        val implType = resolveType(node.implType)
        val traitName = node.traitName?.value
        scopeTree.enterImplScope(implType = implType, traitName = traitName)
        node.implScopePosition = scopeTree.currentScope as ImplScope
        for (item in node.associatedItems) {
            item.accept(this)
        }
        scopeTree.exitScope()
    }

    override fun visitFunctionItem(node: FunctionItemNode) {
        println("visiting FunctionItem")
        node.scopePosition = scopeTree.currentScope
        val fnName = node.fnName.value
        var targetScope = scopeTree.currentScope
        while (targetScope.kind != ScopeKind.Impl &&
            targetScope.kind != ScopeKind.Trait &&
            targetScope.parent != null
        ) {
            targetScope = targetScope.parent!!
        }
        val isAssociated = targetScope is ImplScope || targetScope is TraitScope

        // 检查重定义
        val alreadyDefined = when (targetScope) {
            is TraitScope -> {
                targetScope.lookupLocal(fnName) // 在trait块里找
            }

            is ImplScope -> {
                targetScope.lookupLocal(fnName) // 在impl块里找
            }

            else -> scopeTree.lookup(fnName) // 从当前scope往上找
        }
        if (alreadyDefined != null) {
            throw SemanticException(
                "Duplicate function '$fnName'"
            )
        }

        // 处理参数
        val parameters = mutableListOf<Parameter>()
        var isMethod = false
        var selfParameter: SelfParameter? = null
        val paramCheck = mutableSetOf<String>()

        // 处理self param
        if (node.selfParam != null) {
            if (!isAssociated) {
                throw SemanticException(
                    "only associated function have self param"
                )
            }
            isMethod = true
            val selfResolvedType = when (targetScope) {
                is ImplScope -> {
                    targetScope.implType
                }

                is TraitScope -> {
                    val name = targetScope.traitSymbol.name
                    NamedResolvedType(name, targetScope.traitSymbol)
                    // trait的self类型
                }

                else -> throw SemanticException(
                    "self should be in impl/trait scope"
                )
            }

            selfParameter = SelfParameter(
                paramType = selfResolvedType,
                isMut = node.selfParam.isMut,
                isRef = node.selfParam.isRef
            )
        }

        for (param in node.params) {
            val paramType = resolveType(param.type)
            when (val pattern = param.paramPattern) {
                is IdentifierPatternNode -> {
                    val name = pattern.name.value
                    if (paramCheck.add(name)) {
                        parameters.add(
                            Parameter(
                                name,
                                paramType,
                                pattern.isMut,
                            )
                        )
                    } else {
                        error("duplicate parameter name: '$name'")
                    }
                }

                else -> throw SemanticException(
                    "invalid parameter name: '$pattern'"
                )
            }
        }

        val returnType = if (node.returnType != null) {
            resolveType(node.returnType)
        } else {
            UnitResolvedType
        }

        val symbol = FunctionSymbol(
            name = fnName,
            selfParameter = selfParameter,
            parameters = parameters,
            returnType = returnType,
            isMethod = isMethod,
            isAssociated = isAssociated,
            isDefined = node.body != null
        )

        when (targetScope) {
            is TraitScope -> {
                targetScope.define(symbol) // 在scope里记录
                if (isMethod) targetScope.traitSymbol.methods[fnName] = symbol // 挂到trait底下
                else targetScope.traitSymbol.functions[fnName] = symbol // 挂到trait底下
            }

            is ImplScope -> {
                targetScope.define(symbol)
                // 第二次pass再把function挂载到struct的底下
            }

            else -> scopeTree.define(symbol)
        }

        if (node.body != null) {
            scopeTree.enterFunctionScope(returnType = returnType, functionSymbol = symbol)
            visitBlockExpr(node.body, createScope = false) // 这里已经创建作用域了
            scopeTree.exitScope()
        }
    }

    override fun visitBlockExpr(node: BlockExprNode, createScope: Boolean) {
        println("visiting BlockExpr")
        node.scopePosition = scopeTree.currentScope
        if (createScope) scopeTree.enterBlockScope()

        for (item in node.items) {
            item.accept(this)
        }
        for (stmt in node.statements) {
            // 检查exit是否为main的最后一个statement
            if (stmt is ExprStmtNode && stmt.expr is CallExprNode && stmt.expr.func is PathExprNode) {
                val fnName = stmt.expr.func.first.segment.value
                if (fnName == "exit") {
                    if (stmt != node.statements.last()) throw SemanticException(
                        "exit can only be used in the last statement of main"
                    )
                    val scope = node.scopePosition!!
                    if (scope !is FunctionScope || scope.functionSymbol.name != "main") {
                        throw SemanticException(
                            "exit can only be used in the last statement of main"
                        )
                    }
                }
            }
            stmt.accept(this)
        }
        node.tailExpr?.accept(this)

        if (createScope) scopeTree.exitScope()
    }

    override fun visitPredicateLoopExpr(node: PredicateLoopExprNode) {
        println("visiting PredicateLoopExpr")
        node.scopePosition = scopeTree.currentScope
        node.condition.accept(this)
        scopeTree.enterLoopScope(breakType = UnitResolvedType) // while只能为unit
        visitBlockExpr(node.block, createScope = false)
        scopeTree.exitScope()
    }

    override fun visitInfiniteLoopExpr(node: InfiniteLoopExprNode) {
        println("visiting InfiniteLoopExpr")
        node.scopePosition = scopeTree.currentScope
        scopeTree.enterLoopScope(breakType = UnknownResolvedType) // Loop的类型由break来确定
        visitBlockExpr(node.block, createScope = false)
        scopeTree.exitScope()
    }

    override fun visitEmptyStmt(node: EmptyStmtNode) {
        println("visiting EmptyStmt")
        node.scopePosition = scopeTree.currentScope
    }

    override fun visitLetStmt(node: LetStmtNode) {
        println("visiting LetStmt")
        node.scopePosition = scopeTree.currentScope
        resolveType(node.valueType)
        node.value.accept(this)
    }

    override fun visitExprStmt(node: ExprStmtNode) {
        println("visiting ExprStmt")
        node.scopePosition = scopeTree.currentScope
        node.expr.accept(this)
    }

    override fun visitIntLiteralExpr(node: IntLiteralExprNode) {
        println("visiting IntLiteralExpr")
        node.scopePosition = scopeTree.currentScope
    }

    override fun visitCharLiteralExpr(node: CharLiteralExprNode) {
        println("visiting CharLiteralExpr")
        node.scopePosition = scopeTree.currentScope
    }

    override fun visitStringLiteralExpr(node: StringLiteralExprNode) {
        println("visiting StringLiteralExpr")
        node.scopePosition = scopeTree.currentScope
    }

    override fun visitBooleanLiteralExpr(node: BooleanLiteralExprNode) {
        println("visiting BooleanLiteralExpr")
        node.scopePosition = scopeTree.currentScope
    }

    override fun visitCStringLiteralExpr(node: CStringLiteralExprNode) {
        println("visiting CStringLiteralExpr")
        node.scopePosition = scopeTree.currentScope
    }

    override fun visitRawStringLiteralExpr(node: RawStringLiteralExprNode) {
        println("visiting RawStringLiteralExpr")
        node.scopePosition = scopeTree.currentScope
    }

    override fun visitRawCStringLiteralExpr(node: RawCStringLiteralExprNode) {
        println("visiting RawCStringLiteralExpr")
        node.scopePosition = scopeTree.currentScope
    }

    override fun visitPathExpr(node: PathExprNode) {
        println("visiting PathExpr")
        node.scopePosition = scopeTree.currentScope
    }

    override fun visitBorrowExpr(node: BorrowExprNode) {
        println("visiting BorrowExpr")
        node.scopePosition = scopeTree.currentScope
        node.expr.accept(this)
    }

    override fun visitDerefExpr(node: DerefExprNode) {
        println("visiting DerefExpr")
        node.scopePosition = scopeTree.currentScope
        node.expr.accept(this)
    }

    override fun visitNegationExpr(node: NegationExprNode) {
        println("visiting NegationExpr")
        node.scopePosition = scopeTree.currentScope
        node.expr.accept(this)
    }

    override fun visitBinaryExpr(node: BinaryExprNode) {
        println("visiting BinaryExpr")
        node.scopePosition = scopeTree.currentScope
        node.left.accept(this)
        node.right.accept(this)
    }

    override fun visitComparisonExpr(node: ComparisonExprNode) {
        println("visiting ComparisonExpr")
        node.scopePosition = scopeTree.currentScope
        node.left.accept(this)
        node.right.accept(this)
    }

    override fun visitLazyBooleanExpr(node: LazyBooleanExprNode) {
        println("visiting LazyBooleanExpr")
        node.scopePosition = scopeTree.currentScope
        node.left.accept(this)
        node.right.accept(this)
    }

    override fun visitTypeCastExpr(node: TypeCastExprNode) {
        println("visiting TypeCastExpr")
        node.scopePosition = scopeTree.currentScope
        node.expr.accept(this)
    }

    override fun visitAssignExpr(node: AssignExprNode) {
        println("visiting AssignExpr")
        node.scopePosition = scopeTree.currentScope
        node.left.accept(this)
        node.right.accept(this)
    }

    override fun visitCompoundAssignExpr(node: CompoundAssignExprNode) {
        println("visiting CompoundAssignExpr")
        node.scopePosition = scopeTree.currentScope
        node.left.accept(this)
        node.right.accept(this)
    }

    override fun visitGroupedExpr(node: GroupedExprNode) {
        println("visiting GroupedExpr")
        node.scopePosition = scopeTree.currentScope
        node.inner.accept(this)
    }

    override fun visitArrayListExpr(node: ArrayListExprNode) {
        println("visiting ArrayListExpr")
        node.scopePosition = scopeTree.currentScope
        for (element in node.elements) {
            element.accept(this)
        }
    }

    override fun visitArrayLengthExpr(node: ArrayLengthExprNode) {
        println("visiting ArrayLengthExpr")
        node.scopePosition = scopeTree.currentScope
        node.element.accept(this)
        node.lengthExpr.accept(this)
    }

    override fun visitIndexExpr(node: IndexExprNode) {
        println("visiting IndexExpr")
        node.scopePosition = scopeTree.currentScope
        node.base.accept(this)
        node.index.accept(this)
    }

    override fun visitStructExpr(node: StructExprNode) {
        println("visiting StructExpr")
        node.scopePosition = scopeTree.currentScope
        node.path.accept(this)
        for (field in node.fields) {
            field.value.accept(this)
        }
    }

    override fun visitCallExpr(node: CallExprNode) {
        println("visiting CallExpr")
        node.scopePosition = scopeTree.currentScope
        node.func.accept(this)
        for (param in node.params) {
            param.accept(this)
        }
    }

    override fun visitMethodCallExpr(node: MethodCallExprNode) {
        println("visiting MethodCallExpr")
        node.scopePosition = scopeTree.currentScope
        node.receiver.accept(this)
        for (param in node.params) {
            param.accept(this)
        }
    }

    override fun visitFieldExpr(node: FieldExprNode) {
        println("visiting FieldExpr")
        node.scopePosition = scopeTree.currentScope
        node.struct.accept(this)
    }

    override fun visitIfExpr(node: IfExprNode) {
        println("visiting IfExpr")
        node.scopePosition = scopeTree.currentScope
        node.condition.accept(this)
        node.thenBranch.accept(this)
        node.elseBranch?.accept(this)
    }

    override fun visitBreakExpr(node: BreakExprNode) {
        println("visiting BreakExpr")
        node.scopePosition = scopeTree.currentScope
        node.value?.accept(this)
    }

    override fun visitContinueExpr(node: ContinueExprNode) {
        println("visiting ContinueExpr")
        node.scopePosition = scopeTree.currentScope
    }

    override fun visitReturnExpr(node: ReturnExprNode) {
        println("visiting ReturnExpr")
        node.scopePosition = scopeTree.currentScope
        node.value?.accept(this)
    }
}

class SecondVisitor(private val scopeTree: ScopeTree) : ASTVisitor {
    fun checkResolvedType(type: ResolvedType) {
        when (type) {
            is NamedResolvedType -> {
                val symbol = scopeTree.lookup(type.name)
                if (symbol == null) {
                    throw SemanticException(
                        "cannot resolve type '${type.name}'"
                    )
                } else {
                    type.symbol = symbol
                }
            }

            is ReferenceResolvedType -> {
                checkResolvedType(type.inner)
            }

            is ArrayResolvedType -> {
                type.lengthExpr.accept(this)
                checkResolvedType(type.elementType)
            }

            is UnknownResolvedType -> {
                throw SemanticException(
                    "type unknown after first pass"
                )
            }

            else -> {
                // nothing to do
            }
        }
    }

    override fun visitCrate(node: CrateNode) {
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        // 依次visit每个item
        for (item in node.items) {
            item.accept(this)
        }
    }

    override fun visitStructItem(node: StructItemNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        val structName = node.structName.value
        val structSymbol = scopeTree.lookup(structName)
        if (structSymbol == null || structSymbol !is StructSymbol) {
            throw SemanticException("missing StructSymbol")
        } else {
            for ((name, type) in structSymbol.fields) {
                checkResolvedType(type) // 检查每个field的类型是否存在
                structSymbol.fields[name] = type
            }
            scopeTree.define(structSymbol)
        }
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitEnumItem(node: EnumItemNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!!
        // nothing to do
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitConstantItem(node: ConstantItemNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        val constantName = node.constantName.value
        val constantSymbol = scopeTree.lookup(constantName)
        if (constantSymbol == null || constantSymbol !is ConstantSymbol) {
            throw SemanticException("missing ConstantSymbol")
        } else {
            checkResolvedType(constantSymbol.type) // 检查constant的类型是否存在
            scopeTree.define(constantSymbol) // 替换符号表
        }
        node.value?.accept(this)
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitFunctionItem(node: FunctionItemNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!!
        val fnName = node.fnName.value
        val functionSymbol = scopeTree.lookup(fnName)
        if (functionSymbol == null || functionSymbol !is FunctionSymbol) {
            throw SemanticException("missing FunctionSymbol")
        } else {
            // 检查所有参数的类型
            if (functionSymbol.selfParameter != null) {
                checkResolvedType(functionSymbol.selfParameter.paramType)
            }
            for (parameter in functionSymbol.parameters) {
                checkResolvedType(parameter.paramType)
            }
            checkResolvedType(functionSymbol.returnType)
            scopeTree.define(functionSymbol)

            if (node.body != null) {
                scopeTree.currentScope = node.body.scopePosition!!
                checkResolvedType((scopeTree.currentScope as FunctionScope).returnType) // check return type
                // 等到 array type 长度可知时再注册参数
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
    }

    override fun visitImplItem(node: ImplItemNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!!
        // 找到impl对应的struct和trait
        checkResolvedType(node.implScopePosition!!.implType)
        // 先visit associated items
        for (item in node.associatedItems) {
            item.accept(this)
        }
        // 进入ImplScope
        scopeTree.currentScope = node.implScopePosition!!

        // 先把associated item挂到struct下面去，第四遍pass时再做trait-impl检查
        val implStruct = (node.implScopePosition!!.implType as NamedResolvedType).symbol as StructSymbol
        for ((name, associatedItem) in node.implScopePosition!!.symbols) {
            if (associatedItem is FunctionSymbol) {
                if (associatedItem.isMethod) {
                    // method
                    if (implStruct.methods[name] != null
                        || implStruct.functions[name] != null
                        || implStruct.constants[name] != null
                    ) throw SemanticException("Multiple Definition")
                    implStruct.methods[name] = associatedItem
                } else {
                    // function
                    if (implStruct.methods[name] != null
                        || implStruct.functions[name] != null
                        || implStruct.constants[name] != null
                    ) throw SemanticException("Multiple Definition")
                    implStruct.functions[name] = associatedItem
                }
            } else if (associatedItem is ConstantSymbol) {
                // constant
                if (implStruct.methods[name] != null
                    || implStruct.functions[name] != null
                    || implStruct.constants[name] != null
                ) throw SemanticException("Multiple Definition")
                implStruct.constants[name] = associatedItem
            }
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
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitCharLiteralExpr(node: CharLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitStringLiteralExpr(node: StringLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitBooleanLiteralExpr(node: BooleanLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitCStringLiteralExpr(node: CStringLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitRawStringLiteralExpr(node: RawStringLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitRawCStringLiteralExpr(node: RawCStringLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitPathExpr(node: PathExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
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
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
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
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitReturnExpr(node: ReturnExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        node.value?.accept(this)
        scopeTree.currentScope = previousScope // 还原scope状态
    }
}

fun stringToInt(str: String): Int {
    var tempStr = str.replace("_", "")
    var base = 10
    if (tempStr.startsWith("0b")) {
        base = 2
        tempStr = tempStr.substring(2)
    } else if (tempStr.startsWith("0o")) {
        base = 8
        tempStr = tempStr.substring(2)
    } else if (tempStr.startsWith("0x")) {
        base = 16
        tempStr = tempStr.substring(2)
    }
    return try {
        tempStr.toInt(base)
    } catch (_: NumberFormatException) {
        throw SemanticException("Invalid integer literal: '$str'")
    }
}

fun stringToChar(str: String): Char {
    val content = str.substring(1, str.length - 1)
    return when {
        content.length == 1 -> content[0]
        content == "\\n" -> '\n'
        content == "\\r" -> '\r'
        content == "\\t" -> '\t'
        content == "\\'" -> '\''
        content == "\\\"" -> '\"'
        content == "\\\\" -> '\\'
        content == "\\0" -> '\u0000'
        content.startsWith("\\x") -> content.substring(2).toInt(16).toChar()
        else -> throw SyntaxException("invalid char '$str'")
    }
}

fun stringToString(str: String): String {
    val tempStr = str.substring(1, str.length - 1)
    val builder = StringBuilder()
    var i = 0
    while (i < tempStr.length) {
        val c = tempStr[i]
        if (c != '\\') {
            builder.append(c)
            i++
        } else {
            // 转义
            i++
            if (i >= tempStr.length)
                throw SemanticException("Invalid escape in string: '$str'")
            when (tempStr[i]) {
                'n' -> builder.append('\n')
                'r' -> builder.append('\r')
                't' -> builder.append('\t')
                '\"' -> builder.append('\"')
                '\'' -> builder.append('\'')
                '\\' -> builder.append('\\')
                '0' -> builder.append('\u0000')
                'x' -> {
                    if (i + 2 >= tempStr.length)
                        throw SemanticException("Invalid \\x escape in string: '$str'")
                    val hex = tempStr.substring(i + 1, i + 3)
                    val value = hex.toIntOrNull(16)
                        ?: throw SemanticException("Invalid hex digits in \\x escape: '$hex'")
                    builder.append(value.toChar())
                    i += 2
                }

                '\r' -> {}
                else -> throw SyntaxException("Unknown escape sequence: '\\${tempStr[i]}'")
            }
            i++
        }
    }
    return builder.toString()
}

fun rawStringToString(str: String): String {
    val hashCount = str.indexOf('"') - 1
    val prefix = "r" + "#".repeat(hashCount)
    val suffix = "\"${"#".repeat(hashCount)}"

    if (!str.startsWith(prefix + "\"") || !str.endsWith(suffix)) {
        throw SemanticException("Invalid raw string literal: '$str'")
    }

    val contentStart = prefix.length + 1
    val contentEnd = str.length - suffix.length
    return str.substring(contentStart, contentEnd)
}

fun cStringToString(str: String): String {
    if (!str.startsWith("c\"") || !str.endsWith("\"")) {
        throw SemanticException("Invalid C string literal: '$str'")
    }
    val content = str.substring(2, str.length - 1)
    val builder = StringBuilder()
    var i = 0

    while (i < content.length) {
        val c = content[i]
        if (c != '\\') {
            builder.append(c)
            i++
        } else {
            i++
            if (i >= content.length)
                throw SemanticException("Incomplete escape sequence in: '$str'")
            when (content[i]) {
                'n' -> builder.append('\n')
                'r' -> builder.append('\r')
                't' -> builder.append('\t')
                '\'' -> builder.append('\'')
                '\"' -> builder.append('\"')
                '\\' -> builder.append('\\')
                '0' -> builder.append('\u0000')
                'x' -> {
                    if (i + 2 >= content.length)
                        throw SemanticException("Invalid \\x escape in: '$str'")
                    val hex = content.substring(i + 1, i + 3)
                    val value = hex.toIntOrNull(16)
                        ?: throw SemanticException("Invalid hex digits in \\x escape: '$hex'")
                    builder.append(value.toChar())
                    i += 2
                }

                '\r' -> {}
                else -> throw SemanticException(
                    "Unknown escape sequence: '\\${content[i]}'"
                )
            }
            i++
        }
    }
    return builder.toString()
}

fun rawCStringToString(str: String): String {
    if (!str.startsWith("cr")) throw SemanticException(
        "Not a raw C string literal: $str"
    )
    val firstQuote = str.indexOf('"', 2)
    if (firstQuote == -1) throw SemanticException("Missing opening quote in: $str")
    val hashCount = firstQuote - 2
    val prefix = "cr" + "#".repeat(hashCount) + "\""
    val suffix = "\"" + "#".repeat(hashCount)

    if (!str.startsWith(prefix) || !str.endsWith(suffix)) {
        throw SemanticException("Invalid raw C string literal: $str")
    }

    val contentStart = prefix.length
    val contentEnd = str.length - suffix.length
    return str.substring(contentStart, contentEnd)
}


class ThirdVisitor(private val scopeTree: ScopeTree) : ASTVisitor {
    fun resolveType(node: TypeNode): ResolvedType {
        when (node) {
            is TypePathNode -> {
                when (val name = node.path.segment.value) {
                    "Self" -> {
                        var targetScope = scopeTree.currentScope
                        while (targetScope.kind != ScopeKind.Impl &&
                            targetScope.kind != ScopeKind.Trait &&
                            targetScope.parent != null
                        ) {
                            targetScope = targetScope.parent!!
                        }
                        when (targetScope) {
                            is ImplScope -> {
                                return targetScope.implType
                            }

                            is TraitScope -> {
                                val name = targetScope.traitSymbol.name
                                return NamedResolvedType(name, targetScope.traitSymbol)
                                // trait的self类型
                            }

                            else -> throw SemanticException(
                                "self should be in impl/trait scope"
                            )
                        }
                    }

                    "u32" -> return PrimitiveResolvedType("u32")
                    "i32" -> return PrimitiveResolvedType("i32")
                    "usize" -> return PrimitiveResolvedType("usize")
                    "isize" -> return PrimitiveResolvedType("isize")
                    "bool" -> return PrimitiveResolvedType("bool")
                    "char" -> return PrimitiveResolvedType("char")
                    "str" -> return PrimitiveResolvedType("str")
                    else -> {
                        val symbol = scopeTree.lookup(name)
                            ?: throw SemanticException("undefined symbol: $name")
                        return NamedResolvedType(name = name, symbol = symbol)
                    }
                }
            }

            is ReferenceTypeNode -> {
                val inner = resolveType(node.inner)
                return ReferenceResolvedType(inner, node.isMut)
            }

            is ArrayTypeNode -> {
                val element = resolveType(node.elementType)
                val arrayType = ArrayResolvedType(element, node.length)
                getArrayTypeLength(arrayType)
                return arrayType
            }

            is UnitTypeNode -> return UnitResolvedType
        }
    }

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
                            throw SemanticException("cannot resolve path '$path'")
                        } else {
                            val secondName = secondSegment.value
                            return if (symbol.constants[secondName] != null)
                                symbol.constants[secondName]!!
                            else if (symbol.functions[secondName] != null)
                                symbol.functions[secondName]!!
                            else if (symbol.methods[secondName] != null)
                                symbol.methods[secondName]!!
                            else throw SemanticException("cannot resolve path '$path'")
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

            else -> error("cannot resolve path '$path'")
        }
    }

    fun evaluate(expr: ExprNode): Any {
        return when (expr) {
            is LiteralExprNode -> evaluateLiteral(expr)
            is NegationExprNode -> evaluateNegation(expr)
            is BinaryExprNode -> evaluateBinary(expr)
            is PathExprNode -> evaluateConstPath(expr)
            is GroupedExprNode -> evaluateGrouped(expr)
            else -> throw SemanticException("non-const value in const context")
        }
    }

    fun evaluateLiteral(expr: LiteralExprNode): Any {
        return when (expr) {
            is IntLiteralExprNode -> stringToInt(expr.raw)
            is CharLiteralExprNode -> stringToChar(expr.raw)
            is BooleanLiteralExprNode -> {
                if (expr.raw == "true") true
                else if (expr.raw == "false") false
                else throw SemanticException("invalid bool '${expr.raw}'")
            }

            is StringLiteralExprNode -> stringToString(expr.raw)
            is RawStringLiteralExprNode -> rawStringToString(expr.raw)
            is CStringLiteralExprNode -> cStringToString(expr.raw)
            is RawCStringLiteralExprNode -> rawCStringToString(expr.raw)
        }
    }

    fun evaluateNegation(expr: NegationExprNode): Any {
        val operandValue = evaluate(expr.expr) // 递归求值
        return when (expr.operator.type) {
            TokenType.SubNegate -> {
                if (operandValue is Int) -operandValue
                else throw SemanticException(
                    "Unary '-' applied to non-integer constant: $operandValue"
                )
            }

            TokenType.Not -> {
                if (operandValue is Boolean) !operandValue
                else throw SemanticException(
                    "Unary '!' applied to non-boolean constant: $operandValue"
                )
            }

            else -> throw SemanticException(
                "Unknown unary operator: '${expr.operator.value}'"
            )
        }
    }

    fun evaluateBinary(expr: BinaryExprNode): Any {
        val left = evaluate(expr.left)
        val right = evaluate(expr.right)
        val op = expr.operator.value

        return when (expr.operator.type) {
            TokenType.Add -> {
                if (left is Int && right is Int) left + right
                else throw SemanticException(
                    "Type mismatch for operator '$op': left=${left}, right=${right}"
                )
            }

            TokenType.SubNegate -> {
                if (left is Int && right is Int) left - right
                else throw SemanticException(
                    "Type mismatch for operator '$op': left=${left}, right=${right}"
                )
            }

            TokenType.Mul -> {
                if (left is Int && right is Int) left * right
                else throw SemanticException(
                    "Type mismatch for operator '$op': left=${left}, right=${right}"
                )
            }

            TokenType.Div -> {
                if (left is Int && right is Int) left / right
                else throw SemanticException(
                    "Type mismatch for operator '$op': left=${left}, right=${right}"
                )
            }

            TokenType.Mod -> {
                if (left is Int && right is Int) left % right
                else throw SemanticException(
                    "Type mismatch for operator '$op': left=${left}, right=${right}"
                )
            }

            TokenType.BitAnd -> {
                if (left is Int && right is Int) left and right
                else throw SemanticException(
                    "Type mismatch for operator '$op': left=${left}, right=${right}"
                )
            }

            TokenType.BitOr -> {
                if (left is Int && right is Int) left or right
                else throw SemanticException(
                    "Type mismatch for operator '$op': left=${left}, right=${right}"
                )
            }

            TokenType.BitXor -> {
                if (left is Int && right is Int) left xor right
                else throw SemanticException(
                    "Type mismatch for operator '$op': left=${left}, right=${right}"
                )
            }

            TokenType.Shl -> {
                if (left is Int && right is Int) left shl right
                else throw SemanticException(
                    "Type mismatch for operator '$op': left=${left}, right=${right}"
                )
            }

            TokenType.Shr -> {
                if (left is Int && right is Int) left shr right
                else throw SemanticException(
                    "Type mismatch for operator '$op': left=${left}, right=${right}"
                )
            }

            else -> throw SemanticException(
                "Unsupported binary operator: '${expr.operator.value}'"
            )
        }
    }

    fun evaluateConstPath(expr: PathExprNode): Any {
        val symbol = resolvePath(path = expr)
        if (symbol !is ConstantSymbol) throw SemanticException(
            "Path does not refer to a constant: '${expr}'"
        )
        if (symbol.value == null && symbol.valueExpr != null) {
            symbol.value = evaluate(symbol.valueExpr) // 递归求值
        }
        return symbol.value ?: throw SemanticException("missing value")
    }

    fun evaluateGrouped(expr: GroupedExprNode): Any {
        return evaluate(expr.inner)
    }

    fun getArrayTypeLength(type: ResolvedType) {
        if (type is ReferenceResolvedType) {
            getArrayTypeLength(type.inner)
        } else if (type is ArrayResolvedType) {
            getArrayTypeLength(type.elementType)
            type.lengthExpr.accept(this)
            val length = evaluate(type.lengthExpr)
            if (length is Int) {
                type.length = length
                type.name = "[${type.elementType.name};${length}]" // 改名
            } else {
                throw SemanticException("array length is not Int")
            }
        } // const context求值
    }

    override fun visitCrate(node: CrateNode) {
        node.scopePosition = scopeTree.currentScope
        // 依次visit每个item
        for (item in node.items) {
            item.accept(this)
        }
    }

    override fun visitStructItem(node: StructItemNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        val structName = node.structName.value
        val structSymbol = scopeTree.lookup(structName)
        if (structSymbol == null || structSymbol !is StructSymbol) {
            throw SemanticException("missing StructSymbol")
        } else {
            for ((name, type) in structSymbol.fields) {
                getArrayTypeLength(type)
                structSymbol.fields[name] = type
            }
            scopeTree.define(structSymbol)
        }
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitEnumItem(node: EnumItemNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        // nothing to do
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitConstantItem(node: ConstantItemNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        val constantName = node.constantName.value
        val constantSymbol = scopeTree.lookup(constantName)
        if (constantSymbol == null || constantSymbol !is ConstantSymbol) {
            throw SemanticException("missing ConstantSymbol")
        } else {
            getArrayTypeLength(constantSymbol.type)
            constantSymbol.value = if (constantSymbol.valueExpr != null) {
                evaluate(constantSymbol.valueExpr)
            } else {
                null
            }
        }
        node.value?.accept(this)
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitFunctionItem(node: FunctionItemNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!!
        val fnName = node.fnName.value
        val functionSymbol = scopeTree.lookup(fnName)
        if (functionSymbol == null || functionSymbol !is FunctionSymbol) {
            throw SemanticException("missing FunctionSymbol")
        } else {
            // 检查所有参数的类型
            if (functionSymbol.selfParameter != null) {
                getArrayTypeLength(functionSymbol.selfParameter.paramType)
            }
            for (parameter in functionSymbol.parameters) {
                getArrayTypeLength(parameter.paramType)
            }
            if (functionSymbol.returnType is ArrayResolvedType) {
                getArrayTypeLength(functionSymbol.returnType)
            }

            if (node.body != null) {
                scopeTree.currentScope = node.body.scopePosition!!
                getArrayTypeLength((scopeTree.currentScope as FunctionScope).returnType)

                // 注册参数
                if (functionSymbol.selfParameter != null) {
                    scopeTree.define(
                        VariableSymbol(
                            name = "self",
                            type = functionSymbol.selfParameter.paramType,
                            isMut = functionSymbol.selfParameter.isMut
                        )
                    )
                }
                for (parameter in functionSymbol.parameters) {
                    scopeTree.define(
                        VariableSymbol(
                            name = parameter.name,
                            type = parameter.paramType,
                            isMut = parameter.isMut
                        )
                    )
                }
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
    }

    override fun visitImplItem(node: ImplItemNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!!
        getArrayTypeLength(node.implScopePosition!!.implType)
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
        // 控制流检查
        if (node.tailExpr != null && node.tailExpr.isBottom) {
            node.isBottom = true
        } else if (node.statements.isNotEmpty()) {
            val lastStatement = node.statements.last()
            if (lastStatement.isBottom) {
                node.isBottom = true
            }
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
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitLetStmt(node: LetStmtNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        val pattern = node.pattern
        if (pattern is IdentifierPatternNode) {
            val variable = VariableSymbol(
                name = pattern.name.value,
                type = resolveType(node.valueType),
                isMut = pattern.isMut
            )
            node.variableResolvedType = variable.type // 记录解析的类型，方便类型检查
            val symbol = scopeTree.lookup(variable.name)
            if (symbol != null && symbol !is VariableSymbol) {
                throw SemanticException("refutable pattern in local binding")
            } else {
                scopeTree.define(variable) // 注册local variable（可能造成遮蔽）
            }
        } else throw SemanticException(
            "invalid parameter name: '$pattern'"
        )
        node.value.accept(this)
        if (node.value.isBottom) node.isBottom = true
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitExprStmt(node: ExprStmtNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        node.expr.accept(this)
        if (node.expr.isBottom) node.isBottom = true
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitIntLiteralExpr(node: IntLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitCharLiteralExpr(node: CharLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitStringLiteralExpr(node: StringLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitBooleanLiteralExpr(node: BooleanLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitCStringLiteralExpr(node: CStringLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitRawStringLiteralExpr(node: RawStringLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitRawCStringLiteralExpr(node: RawCStringLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitPathExpr(node: PathExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
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

        node.targetResolvedType = resolveType(node.targetType) // 解析想要转成的类型
        getArrayTypeLength(node.targetResolvedType) // 可能求长度

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitAssignExpr(node: AssignExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        node.left.accept(this)
        node.right.accept(this)
        if (node.right.isBottom) node.isBottom = true
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
        if (node.inner.isBottom) node.isBottom = true
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

        val len = evaluate(node.lengthExpr)
        if (len !is Int) throw SemanticException("array length must be int")
        else node.length = len

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitIndexExpr(node: IndexExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
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
        if (node.thenBranch.isBottom && node.elseBranch != null && node.elseBranch.isBottom) {
            node.isBottom = true // 每个分支都是bottom
        }
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitBreakExpr(node: BreakExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        node.value?.accept(this)

        var targetScope = scopeTree.currentScope // 检查是否在循环中
        while (targetScope.kind != ScopeKind.Loop &&
            targetScope.kind != ScopeKind.Function &&
            targetScope.parent != null
        ) {
            targetScope = targetScope.parent!!
        }
        if (targetScope.kind != ScopeKind.Loop) {
            throw SemanticException("break outside loop")
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitContinueExpr(node: ContinueExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        var targetScope = scopeTree.currentScope // 检查是否在循环中
        while (targetScope.kind != ScopeKind.Loop &&
            targetScope.kind != ScopeKind.Function &&
            targetScope.parent != null
        ) {
            targetScope = targetScope.parent!!
        }
        if (targetScope.kind != ScopeKind.Loop) {
            throw SemanticException("continue outside loop")
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitReturnExpr(node: ReturnExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        node.value?.accept(this)

        var targetScope = scopeTree.currentScope // 检查是否在循环中
        while (targetScope.kind != ScopeKind.Function && targetScope.parent != null) {
            targetScope = targetScope.parent!!
        }
        if (targetScope.kind != ScopeKind.Function) {
            throw SemanticException("return outside function")
        }

        node.isBottom = true
        scopeTree.currentScope = previousScope // 还原scope状态
    }
}

class FourthVisitor(private val scopeTree: ScopeTree) : ASTVisitor {
    override fun visitCrate(node: CrateNode) {
        node.scopePosition = scopeTree.currentScope
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
        // nothing to do
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
        if (node.body != null) visitBlockExpr(node.body, createScope = false)
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
        // 找到impl对应的struct和trait
        val implTrait: TraitSymbol? = if (node.traitName != null) {
            val symbol = scopeTree.lookup(node.traitName.value)
            if (symbol == null || symbol !is TraitSymbol) {
                throw SemanticException("impl trait ${node.traitName} not found")
            } else {
                symbol
            }
        } else null

        // visit associated items
        for (item in node.associatedItems) {
            item.accept(this)
        }
        // 进入ImplScope
        scopeTree.currentScope = node.implScopePosition!!
        if (implTrait != null) {
            // trait-impl检查
            for ((name, associatedItem) in node.implScopePosition!!.symbols) {
                if (associatedItem is FunctionSymbol) {
                    if (associatedItem.isMethod) {
                        // 找到trait里对应的method
                        val traitMethod = implTrait.methods[name] ?: throw SemanticException(
                            "impl method $name not found in trait"
                        )

                        // 检查函数签名是否正确
                        var isSame: Boolean = (traitMethod.returnType == associatedItem.returnType)
                                && (traitMethod.selfParameter!!.isMut == associatedItem.selfParameter!!.isMut)
                                && (traitMethod.selfParameter.isRef != associatedItem.selfParameter.isRef)
                                && (traitMethod.parameters.size == associatedItem.parameters.size)
                        for (index in 0..<associatedItem.parameters.size) {
                            val traitParam = traitMethod.parameters[index]
                            val associatedParam = associatedItem.parameters[index]
                            if (traitParam.paramType != associatedParam.paramType) {
                                isSame = false
                            }
                        }

                        // 不匹配就报错
                        if (!isSame) throw SemanticException(
                            "impl method $name not found in trait"
                        )

                    } else {
                        // 找到trait里对应的function
                        val traitFunction = implTrait.functions[name] ?: throw SemanticException(
                            "impl function $name not found in trait"
                        )

                        // 检查函数签名是否正确
                        var isSame: Boolean = (traitFunction.returnType == associatedItem.returnType)
                                && (traitFunction.parameters.size == associatedItem.parameters.size)
                        for (index in 0..<associatedItem.parameters.size) {
                            val traitParam = traitFunction.parameters[index]
                            val associatedParam = associatedItem.parameters[index]
                            if (traitParam.paramType != associatedParam.paramType) {
                                isSame = false
                            }
                        }

                        // 不匹配就报错
                        if (!isSame) throw SemanticException(
                            "impl function $name not found in trait"
                        )
                    }
                } else if (associatedItem is ConstantSymbol) {
                    // 找到trait里对应的constant
                    val traitConstant = implTrait.constants[name] ?: throw SemanticException(
                        "impl constant $name not found in trait"
                    )

                    // 检查常量是否匹配
                    if (traitConstant.type != associatedItem.type) {
                        throw SemanticException(
                            "impl constant $name not found in trait"
                        )
                    }
                }
            }

            // 检查所有undefined是否都实现了
            val implStruct = (node.implScopePosition!!.implType as NamedResolvedType).symbol as StructSymbol
            for ((name, method) in implTrait.methods) {
                if (!method.isDefined && implStruct.methods[name] == null) {
                    throw SemanticException(
                        "method $name haven't been impled"
                    )
                }
            }
            for ((name, function) in implTrait.functions) {
                if (!function.isDefined && implStruct.functions[name] == null) {
                    throw SemanticException(
                        "function $name haven't been impled"
                    )
                }
            }
            for ((name, constant) in implTrait.constants) {
                if (!constant.isDefined && implStruct.constants[name] == null) {
                    throw SemanticException(
                        "constant $name haven't been impled"
                    )
                }
            }
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
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitCharLiteralExpr(node: CharLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitStringLiteralExpr(node: StringLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitBooleanLiteralExpr(node: BooleanLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitCStringLiteralExpr(node: CStringLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitRawStringLiteralExpr(node: RawStringLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitRawCStringLiteralExpr(node: RawCStringLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitPathExpr(node: PathExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
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
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
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
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitReturnExpr(node: ReturnExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        node.value?.accept(this)
        scopeTree.currentScope = previousScope // 还原scope状态
    }
}

fun typeCheck(left: ResolvedType, right: ResolvedType): Boolean {
    // 检查右类型是否能匹配上左类型
    return if (left == right) true
    else if (right == NeverResolvedType) true
    else if (right == PrimitiveResolvedType("int")) {
        when (left) {
            PrimitiveResolvedType("i32") -> true
            PrimitiveResolvedType("isize") -> true
            PrimitiveResolvedType("u32") -> true
            PrimitiveResolvedType("usize") -> true
            else -> false
        }
    } else if (left is ReferenceResolvedType && right is ReferenceResolvedType) {
        if (!typeCheck(left.inner, right.inner)) return false
        if (left.isMut == right.isMut) return true
        return !left.isMut
    } else false
}

class FifthVisitor(private val scopeTree: ScopeTree) : ASTVisitor {
    fun isAssignee(expr: ExprNode): Boolean {
        if (expr.exprType == ExprType.MutPlace) return true
        else if (expr is StructExprNode) {
            for (field in expr.fields) {
                if (!isAssignee(field.value)) return false
            }
            return true
        } else return false
    }

    fun typeAddSubMulDivMod(left: ResolvedType, right: ResolvedType): ResolvedType {
        if (!isInt(left) || !isInt(right)) throw SemanticException(
            "Arithmetic operator '+ - * /' requires numeric or bool operands" +
                    "\nleft:'$left right:'$right'"
        )
        if (left == right) return left
        if (left == PrimitiveResolvedType("int")) return right
        if (right == PrimitiveResolvedType("int")) return left
        throw SemanticException("cannot +-*/ $left to $right")
    }

    fun typeBitAndOrXor(left: ResolvedType, right: ResolvedType): ResolvedType {
        if (isInt(left) && isInt(right)) {
            if (left == right) return left
            if (left == PrimitiveResolvedType("int")) return right
            if (right == PrimitiveResolvedType("int")) return left
            throw SemanticException("cannot &|^ $left to $right")
        }

        if (left == PrimitiveResolvedType("bool")
            && right == PrimitiveResolvedType("bool")
        ) return PrimitiveResolvedType("bool")

        throw SemanticException("cannot &|^ $left to $right")
    }

    fun typeShlShr(left: ResolvedType, right: ResolvedType): ResolvedType {
        if (isInt(left) && isInt(right)) return left
        else throw SemanticException("cannot << >> $left to $right")
    }

    fun typeCompare(left: ResolvedType, right: ResolvedType): ResolvedType {
        if (left == right) return PrimitiveResolvedType("bool")
        if (left == PrimitiveResolvedType("int") && isInt(right))
            return PrimitiveResolvedType("bool")
        if (isInt(left) && right == PrimitiveResolvedType("int"))
            return PrimitiveResolvedType("bool")

        throw SemanticException("cannot compare $left with $right")
    }

    fun typeUnify(left: ResolvedType, right: ResolvedType): ResolvedType {
        if (left == right) return left
        if (left == NeverResolvedType) return right
        if (right == NeverResolvedType) return left
        if (left == PrimitiveResolvedType("int") && isInt(right)) return right
        if (right == PrimitiveResolvedType("int") && isInt(left)) return left
        throw SemanticException("$left does not match with $right")
    }

    fun isInt(resolvedType: ResolvedType): Boolean {
        return when (resolvedType) {
            PrimitiveResolvedType("i32"),
            PrimitiveResolvedType("u32"),
            PrimitiveResolvedType("isize"),
            PrimitiveResolvedType("usize"),
            PrimitiveResolvedType("int") -> true

            else -> false
        }
    }

    fun isSignedInt(resolvedType: ResolvedType): Boolean {
        return when (resolvedType) {
            PrimitiveResolvedType("i32"),
            PrimitiveResolvedType("isize"),
            PrimitiveResolvedType("int") -> true

            else -> false
        }
    }

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

            else -> error("cannot resolve path '$path'")
        }
    }

    override fun visitCrate(node: CrateNode) {
        node.scopePosition = scopeTree.currentScope
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
        // nothing to do
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
            throw SemanticException("missing FunctionSymbol")
        } else {
            if (node.body != null) {
                visitBlockExpr(node.body, createScope = false)
                if (!(typeCheck(functionSymbol.returnType, node.body.resolvedType)
                            || node.body.isBottom)
                ) throw SemanticException(
                    "no return in function '$fnName'" +
                            " or type mismatch: left'${functionSymbol.returnType}' " +
                            "right'${node.body.resolvedType}'"
                )
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

        node.exprType = ExprType.Value // 只能为value
        if (node.tailExpr != null) {
            node.resolvedType = node.tailExpr.resolvedType
        } else if (node.statements.isNotEmpty()) {
            when (val lastStmt = node.statements.last()) {
                is ExprStmtNode if lastStmt.expr.resolvedType == NeverResolvedType -> {
                    node.resolvedType = NeverResolvedType
                }

                is LetStmtNode if lastStmt.value.resolvedType == NeverResolvedType -> {
                    node.resolvedType = NeverResolvedType
                }

                else -> node.resolvedType = UnitResolvedType
            }
        } // 确定类型

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitPredicateLoopExpr(node: PredicateLoopExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        node.condition.accept(this)
        visitBlockExpr(node.block, createScope = false)

        node.exprType = ExprType.Value
        if (node.condition.resolvedType != PrimitiveResolvedType("bool")) {
            throw SemanticException("condition '${node.condition}' is not bool type")
        }
        node.resolvedType = (node.block.scopePosition as LoopScope).breakType

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitInfiniteLoopExpr(node: InfiniteLoopExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        visitBlockExpr(node.block, createScope = false)

        node.exprType = ExprType.Value
        node.resolvedType = (node.block.scopePosition as LoopScope).breakType

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitEmptyStmt(node: EmptyStmtNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitLetStmt(node: LetStmtNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        node.value.accept(this)
        if (!typeCheck(node.variableResolvedType, node.value.resolvedType)) {
            throw SemanticException(
                "Type Mismatch in LetStmt: " +
                        "expected ${node.variableResolvedType} found ${node.value.resolvedType}"
            )
        }
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

        node.exprType = ExprType.Value
        node.resolvedType = if (node.raw.endsWith("i32")) {
            PrimitiveResolvedType("i32")
        } else if (node.raw.endsWith("u32")) {
            PrimitiveResolvedType("u32")
        } else if (node.raw.endsWith("isize")) {
            PrimitiveResolvedType("isize")
        } else if (node.raw.endsWith("usize")) {
            PrimitiveResolvedType("usize")
        } else {
            PrimitiveResolvedType("int")
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitCharLiteralExpr(node: CharLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.exprType = ExprType.Value
        node.resolvedType = PrimitiveResolvedType("char")

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitStringLiteralExpr(node: StringLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.exprType = ExprType.Value
        node.resolvedType = PrimitiveResolvedType("str")

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitBooleanLiteralExpr(node: BooleanLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.exprType = ExprType.Value
        node.resolvedType = PrimitiveResolvedType("bool")

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitCStringLiteralExpr(node: CStringLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.exprType = ExprType.Value
        node.resolvedType = PrimitiveResolvedType("str")

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitRawStringLiteralExpr(node: RawStringLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.exprType = ExprType.Value
        node.resolvedType = PrimitiveResolvedType("str")

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitRawCStringLiteralExpr(node: RawCStringLiteralExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.exprType = ExprType.Value
        node.resolvedType = PrimitiveResolvedType("str")

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitPathExpr(node: PathExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        val symbol = resolvePath(node)
        node.exprType = if (symbol is VariableSymbol) {
            if (symbol.isMut) ExprType.MutPlace
            else ExprType.Place
        } else {
            ExprType.Value
        }
        node.resolvedType = when (symbol) {
            is VariableSymbol -> symbol.type
            is ConstantSymbol -> symbol.type
            is VariantSymbol -> symbol.type
            is FunctionSymbol -> UnknownResolvedType
            is StructSymbol -> UnknownResolvedType
            else -> error("cannot resolve path expression")
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitBorrowExpr(node: BorrowExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        node.expr.accept(this)

        node.exprType = ExprType.Value
        node.resolvedType = ReferenceResolvedType(
            inner = node.expr.resolvedType,
            isMut = node.isMut
        )

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitDerefExpr(node: DerefExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        node.expr.accept(this)

        val type = node.expr.resolvedType
        if (type is ReferenceResolvedType) {
            node.resolvedType = type.inner
            node.exprType = if (type.isMut) {
                ExprType.MutPlace
            } else {
                ExprType.Place
            }
        } else {
            error("Type '$type' cannot be dereferenced")
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitNegationExpr(node: NegationExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        node.expr.accept(this)

        node.exprType = ExprType.Value
        val type = node.expr.resolvedType
        when (node.operator.type) {
            TokenType.SubNegate -> {
                if (isSignedInt(type)) node.resolvedType = type
                else throw SemanticException(
                    "Negation operator '-' requires signed int operands"
                )
            }

            TokenType.Not -> {
                if (isInt(type) || type == PrimitiveResolvedType("bool"))
                    node.resolvedType = type
                else throw SemanticException(
                    "Negation operator '!' requires int or bool operands"
                )
            }

            else -> throw SemanticException(
                "Unsupported negation operator '${node.operator}'"
            )
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitBinaryExpr(node: BinaryExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        node.left.accept(this)
        node.right.accept(this)

        node.exprType = ExprType.Value
        val leftType = node.left.resolvedType
        val rightType = node.right.resolvedType
        when (node.operator.type) {
            TokenType.Add, TokenType.SubNegate, TokenType.Mul, TokenType.Div, TokenType.Mod -> {
                node.resolvedType = typeAddSubMulDivMod(leftType, rightType)
            }

            TokenType.BitAnd, TokenType.BitOr, TokenType.BitXor -> {
                node.resolvedType = typeBitAndOrXor(leftType, rightType)
            }

            TokenType.Shl, TokenType.Shr -> {
                node.resolvedType = typeShlShr(leftType, rightType)
            }

            else -> throw SemanticException(
                "Unsupported binary operator '${node.operator}'"
            )
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitComparisonExpr(node: ComparisonExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        node.left.accept(this)
        node.right.accept(this)

        node.exprType = ExprType.Value
        val leftType = node.left.resolvedType
        val rightType = node.right.resolvedType
        when (node.operator.type) {
            TokenType.Eq, TokenType.Neq, TokenType.Gt, TokenType.Lt, TokenType.Ge, TokenType.Le -> {
                node.resolvedType = typeCompare(leftType, rightType)
            }

            else -> throw SemanticException(
                "Unsupported comparison operator '${node.operator}'"
            )
        }
        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitLazyBooleanExpr(node: LazyBooleanExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        node.left.accept(this)
        node.right.accept(this)

        node.exprType = ExprType.Value
        val leftType = node.left.resolvedType
        val rightType = node.right.resolvedType
        when (node.operator.type) {
            TokenType.And, TokenType.Or -> {
                if (leftType == PrimitiveResolvedType("bool")
                    && rightType == PrimitiveResolvedType("bool")
                ) node.resolvedType = PrimitiveResolvedType("bool")
                else throw SemanticException(
                    "LazyBoolean operator '${node.operator}' requires bool operands"
                )
            }

            else -> throw SemanticException(
                "Unsupported lazy boolean operator '${node.operator}'"
            )
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitTypeCastExpr(node: TypeCastExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        node.expr.accept(this)

        node.exprType = ExprType.Value
        val currentType = node.expr.resolvedType
        val targetType = node.targetResolvedType
        if ((currentType == PrimitiveResolvedType("bool")
                    || currentType == PrimitiveResolvedType("char")
                    || isInt(currentType)
                    ) && isInt(targetType)
        ) node.resolvedType = targetType // int/bool/char ->int
        else throw SemanticException("Cannot cast '$currentType' to $targetType'")

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitAssignExpr(node: AssignExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        node.left.accept(this)
        node.right.accept(this)

        node.exprType = ExprType.Value
        val leftType = node.left.resolvedType
        val rightType = node.right.resolvedType
        if (isAssignee(node.left) && typeCheck(leftType, rightType)) {
            node.resolvedType = UnitResolvedType // AssignExpr is always unitType
        } else throw SemanticException(
            "cannot assign to a immutable variable or type mismatch: " +
                    "left:'$leftType' right:'$rightType'"
        )

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitCompoundAssignExpr(node: CompoundAssignExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        node.left.accept(this)
        node.right.accept(this)

        node.exprType = ExprType.Value
        val leftType = node.left.resolvedType
        val rightType = node.right.resolvedType
        if (node.left.exprType != ExprType.MutPlace) throw SemanticException(
            "Cannot assign '${node.right}' to '${node.left}'"
        )

        when (node.operator.type) {
            TokenType.AddAssign, TokenType.SubAssign, TokenType.MulAssign,
            TokenType.DivAssign, TokenType.ModAssign -> {
                if (isInt(leftType) && isInt(rightType) && (leftType == rightType
                            || leftType == PrimitiveResolvedType("int")
                            || rightType == PrimitiveResolvedType("int"))
                ) node.resolvedType = UnitResolvedType
                else throw SemanticException(
                    "operator '${node.operator}' requires numeric operands"
                )
            }

            TokenType.AndAssign, TokenType.OrAssign, TokenType.XorAssign -> {
                if (isInt(leftType) && isInt(rightType) && (leftType == rightType
                            || leftType == PrimitiveResolvedType("int")
                            || rightType == PrimitiveResolvedType("int"))
                ) node.resolvedType = UnitResolvedType
                else if (leftType == PrimitiveResolvedType("bool")
                    && rightType == PrimitiveResolvedType("bool")
                ) node.resolvedType = UnitResolvedType
                else throw SemanticException(
                    "operator '${node.operator}' requires numeric or bool operands"
                )
            }

            TokenType.ShlAssign, TokenType.ShrAssign -> {
                if (isInt(leftType) && isInt(rightType))
                    node.resolvedType = UnitResolvedType
                else throw SemanticException(
                    "operator '${node.operator}' requires numeric operands"
                )
            }

            else -> throw SemanticException(
                "Unsupported compound assign operator '${node.operator}'"
            )
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitGroupedExpr(node: GroupedExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        node.inner.accept(this)

        node.exprType = node.inner.exprType
        node.resolvedType = node.inner.resolvedType

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitArrayListExpr(node: ArrayListExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        for (element in node.elements) {
            element.accept(this)
        }

        node.exprType = ExprType.Value
        if (node.elements.firstOrNull() == null) throw SemanticException(
            "Empty array literal is not allowed"
        )
        var elementType = node.elements.first().resolvedType
        for (element in node.elements) {
            elementType = typeUnify(element.resolvedType, elementType)
        }
        val length = node.elements.size

        node.resolvedType = ArrayResolvedType(
            elementType = elementType,
            lengthExpr = IntLiteralExprNode(length.toString()),
        )
        (node.resolvedType as ArrayResolvedType).length = length // 记录长度
        (node.resolvedType as ArrayResolvedType).name = "[${elementType.name};$length]"

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitArrayLengthExpr(node: ArrayLengthExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        node.element.accept(this)
        node.lengthExpr.accept(this)

        node.exprType = ExprType.Value
        if (!typeCheck(PrimitiveResolvedType("usize"), node.lengthExpr.resolvedType)) {
            throw SemanticException("Array length must be usize")
        }
        val elementType = node.element.resolvedType
        node.resolvedType = ArrayResolvedType(
            elementType = elementType,
            lengthExpr = node.lengthExpr
        )
        (node.resolvedType as ArrayResolvedType).length = node.length
        (node.resolvedType as ArrayResolvedType).name = "[${elementType.name};${node.length}]"

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitIndexExpr(node: IndexExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        node.base.accept(this)
        node.index.accept(this)

        if (!typeCheck(PrimitiveResolvedType("usize"), node.index.resolvedType)) {
            throw SemanticException("Array length must be usize")
        }
        val elementType = when (val baseType = node.base.resolvedType) {
            is ArrayResolvedType -> {
                node.exprType = when (node.base.exprType) {
                    ExprType.MutPlace -> ExprType.MutPlace
                    ExprType.Place -> ExprType.Place
                    ExprType.Value -> ExprType.MutPlace
                    ExprType.Unknown -> throw SemanticException(
                        "index base expr cannot be resolved"
                    )
                }
                baseType.elementType
            }

            is ReferenceResolvedType -> {
                node.exprType = if (baseType.isMut) {
                    ExprType.MutPlace
                } else {
                    ExprType.Place
                }
                val inner = baseType.inner as? ArrayResolvedType
                    ?: throw SemanticException("Cannot index into type $baseType")
                inner.elementType
            } // 自动解引用

            else -> throw SemanticException("Cannot index into type $baseType")
        }
        node.resolvedType = elementType

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitStructExpr(node: StructExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        node.path.accept(this)

        val symbol = resolvePath(node.path)
        if (symbol !is StructSymbol) throw SemanticException(
            "path '${node.path}' does not refer to a struct"
        )
        if (node.fields.size != symbol.fields.size) throw SemanticException(
            "fields number cannot match"
        )
        val seenFields = mutableSetOf<String>() // 记录用过的field
        for (field in node.fields) {
            field.value.accept(this)
            val fieldName = field.name.value
            val fieldType = symbol.fields[fieldName] ?: throw SemanticException(
                "Unknown field '$fieldName' in struct '${symbol.name}'"
            )
            if (!seenFields.add(fieldName)) throw SemanticException(
                "Duplicate field '$fieldName' in struct '${symbol.name}' expression"
            )
            if (!typeCheck(fieldType, field.value.resolvedType)) {
                throw SemanticException("Type mismatch for field '$fieldName'")
            }
        }
        node.exprType = ExprType.Value
        node.resolvedType = NamedResolvedType(name = symbol.name, symbol = symbol)

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitCallExpr(node: CallExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        node.func.accept(this)

        val funcSymbol = if (node.func is PathExprNode) {
            resolvePath(node.func) as? FunctionSymbol
                ?: throw SemanticException("${node.func} does not refer to function")
        } else throw SemanticException(
            "expected function path"
        )

        if (funcSymbol.isMethod) {
            // 有self时
            if (funcSymbol.parameters.size != node.params.size - 1) throw SemanticException(
                "params number cannot match"
            )
            val selfParam = funcSymbol.selfParameter!!
            val selfType = if (selfParam.isRef) {
                ReferenceResolvedType(inner = selfParam.paramType, isMut = selfParam.isMut)
            } else {
                selfParam.paramType
            }
            node.params[0].accept(this)
            if (!typeCheck(selfType, node.params[0].resolvedType)) {
                throw SemanticException(
                    "parameter 'self' type mismatch:" +
                            " left:'$selfType' right:'${node.params[0].resolvedType}'"
                )
            }
            for (index in 1..<node.params.size) {
                val param = node.params[index]
                param.accept(this)
                val targetType = funcSymbol.parameters[index - 1].paramType
                if (!typeCheck(targetType, param.resolvedType)) {
                    throw SemanticException(
                        "parameter '${funcSymbol.parameters[index - 1].name}' " +
                                "type mismatch: left:'$targetType' right:'${param.resolvedType}'"
                    )
                }
            }
        } else {
            // 无self时
            if (funcSymbol.parameters.size != node.params.size) throw SemanticException(
                "params number cannot match"
            )
            for (index in 0..<node.params.size) {
                val param = node.params[index]
                param.accept(this)
                val targetType = funcSymbol.parameters[index].paramType
                if (!typeCheck(targetType, param.resolvedType)) {
                    throw SemanticException(
                        "parameter '${funcSymbol.parameters[index].name}' " +
                                "type mismatch: left:'$targetType' right:'${param.resolvedType}'"
                    )
                }
            }
        }

        node.exprType = ExprType.Value
        node.resolvedType = funcSymbol.returnType

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitMethodCallExpr(node: MethodCallExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        node.receiver.accept(this)

        node.exprType = ExprType.Value
        val methodName = node.method.segment.value
        val receiverType = node.receiver.resolvedType
        if (receiverType is NamedResolvedType) {
            val structSymbol = receiverType.symbol as? StructSymbol
                ?: throw SemanticException("method receiver should be a struct")
            val method = structSymbol.methods[methodName]
                ?: throw SemanticException("undefined method '$methodName'")

            if (method.parameters.size != node.params.size) throw SemanticException(
                "params number cannot match"
            )
            for (index in 0..<node.params.size) {
                val param = node.params[index]
                param.accept(this)
                if (!typeCheck(method.parameters[index].paramType, param.resolvedType)) {
                    throw SemanticException(
                        "parameter '${method.parameters[index].name}' " +
                                "type mismatch: left:'${method.parameters[index].paramType}' " +
                                "right:'${param.resolvedType}'"
                    )
                }
            }
            node.resolvedType = method.returnType
        } else {
            // 内置method
            when (receiverType) {
                PrimitiveResolvedType("u32") if methodName == "to_string" -> {
                    val symbol = scopeTree.lookup("String")
                        ?: throw SemanticException("undefined struct 'String'")
                    node.resolvedType = NamedResolvedType(name = symbol.name, symbol = symbol)
                }

                PrimitiveResolvedType("usize") if methodName == "to_string" -> {
                    val symbol = scopeTree.lookup("String")
                        ?: throw SemanticException("undefined struct 'String'")
                    node.resolvedType = NamedResolvedType(name = symbol.name, symbol = symbol)
                }

                is ArrayResolvedType if methodName == "len" -> {
                    node.resolvedType = PrimitiveResolvedType("usize")
                }

                is ReferenceResolvedType if receiverType.inner is ArrayResolvedType
                        && methodName == "len"
                    -> node.resolvedType = PrimitiveResolvedType("usize")

                is ReferenceResolvedType if receiverType.inner == PrimitiveResolvedType("str")
                        && methodName == "len"
                    -> node.resolvedType = PrimitiveResolvedType("usize")

                else -> throw SemanticException(
                    "undefined method '$methodName' for type '$receiverType'"
                )
            }
        }

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitFieldExpr(node: FieldExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        node.struct.accept(this)

        val receiverType = when (val structType = node.struct.resolvedType) {
            is NamedResolvedType -> structType
            is ReferenceResolvedType -> structType.inner as? NamedResolvedType
                ?: throw SemanticException("the expr of FieldExpr must be a struct")

            else -> throw SemanticException("the expr of FieldExpr must be a struct")
        }

        val structSymbol = receiverType.symbol as? StructSymbol
            ?: throw SemanticException("the expr of FieldExpr must be a struct")

        val fieldName = node.field.value
        val fieldType = structSymbol.fields[fieldName] ?: throw SemanticException(
            "Unknown field '$fieldName' in struct '${structSymbol.name}'"
        )

        node.exprType = when (node.struct.exprType) {
            ExprType.MutPlace -> ExprType.MutPlace
            ExprType.Place -> ExprType.Place
            ExprType.Value -> ExprType.MutPlace
            ExprType.Unknown -> throw SemanticException(
                "struct expr cannot be resolved"
            )
        }
        node.resolvedType = fieldType

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitIfExpr(node: IfExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        node.condition.accept(this)
        node.thenBranch.accept(this)
        node.elseBranch?.accept(this)

        node.exprType = ExprType.Value
        if (node.condition.resolvedType != PrimitiveResolvedType("bool")) {
            throw SemanticException("condition must be bool type")
        }
        val thenType = node.thenBranch.resolvedType
        val elseType = if (node.elseBranch == null) {
            UnitResolvedType
        } else {
            node.elseBranch.resolvedType
        }
        node.resolvedType = typeUnify(thenType, elseType)

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitBreakExpr(node: BreakExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        node.value?.accept(this)

        val breakType = if (node.value == null) {
            UnitResolvedType
        } else {
            node.value.resolvedType
        }
        var targetScope = scopeTree.currentScope // 检查是否在循环中
        while (targetScope.kind != ScopeKind.Loop &&
            targetScope.kind != ScopeKind.Function &&
            targetScope.parent != null
        ) {
            targetScope = targetScope.parent!!
        }
        if (targetScope is LoopScope) {
            if (targetScope.breakType == UnknownResolvedType) targetScope.breakType = breakType
            else targetScope.breakType = typeUnify(targetScope.breakType, breakType)
        } else throw SemanticException("break outside loop")

        node.exprType = ExprType.Value
        node.resolvedType = NeverResolvedType

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitContinueExpr(node: ContinueExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope

        node.exprType = ExprType.Value
        node.resolvedType = NeverResolvedType

        scopeTree.currentScope = previousScope // 还原scope状态
    }

    override fun visitReturnExpr(node: ReturnExprNode) {
        val previousScope = scopeTree.currentScope
        scopeTree.currentScope = node.scopePosition!! // 找到所在的scope
        node.value?.accept(this)

        val returnType = if (node.value == null) {
            UnitResolvedType
        } else {
            node.value.resolvedType
        }
        var targetScope = scopeTree.currentScope // 检查是否在循环中
        while (targetScope.kind != ScopeKind.Function && targetScope.parent != null) {
            targetScope = targetScope.parent!!
        }
        if (targetScope is FunctionScope) {
            targetScope.returnType = typeUnify(targetScope.returnType, returnType)
        } else throw SemanticException("return outside function")

        node.exprType = ExprType.Value
        node.resolvedType = NeverResolvedType

        scopeTree.currentScope = previousScope // 还原scope状态
    }
}