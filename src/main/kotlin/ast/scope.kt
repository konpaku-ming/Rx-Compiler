package ast

import exception.SemanticException
import llvm.Value

// symbol
sealed class Symbol {
    abstract val name: String
}

data class VariableSymbol(
    override val name: String,
    val type: ResolvedType,
    val isMut: Boolean,
) : Symbol() {
    var irValue: Value? = null // IR中对应的 alloca 指令，在后续的IR生成阶段会赋值（也有可能为 argument ）
}

data class SelfParameter(
    val paramType: ResolvedType, // 原始的self类型
    val isMut: Boolean,
    val isRef: Boolean,
)

data class Parameter(
    val name: String,
    val paramType: ResolvedType,
    val isMut: Boolean,
)

data class FunctionSymbol(
    override val name: String,
    val selfParameter: SelfParameter?,
    val parameters: List<Parameter>,
    val returnType: ResolvedType,
    val isMethod: Boolean,
    val isAssociated: Boolean,
    val isDefined: Boolean = true,
) : Symbol() {
    override fun toString(): String {
        return "FunctionSymbol(name='$name', returnType='${returnType.name}')"
    }
}

data class ConstantSymbol(
    override val name: String,
    val type: ResolvedType,
    val valueExpr: ExprNode?,
    var value: Any?,
    val isAssociated: Boolean,
    val isDefined: Boolean = true, // 是否定义
) : Symbol()

data class StructSymbol(
    override val name: String,
    val fields: MutableMap<String, ResolvedType>,
    val functions: MutableMap<String, FunctionSymbol> = mutableMapOf(),
    val methods: MutableMap<String, FunctionSymbol> = mutableMapOf(),
    val constants: MutableMap<String, ConstantSymbol> = mutableMapOf(),
) : Symbol() {
    override fun toString(): String {
        return "StructSymbol(name='$name', fields=${fields.keys})"
    }
}

data class EnumSymbol(
    override val name: String,
    val variants: List<String>, // variants
) : Symbol()

data class VariantSymbol(
    override val name: String,
    val type: ResolvedType,
) : Symbol()

data class TraitSymbol(
    override val name: String,
    val functions: MutableMap<String, FunctionSymbol> = mutableMapOf(),
    val methods: MutableMap<String, FunctionSymbol> = mutableMapOf(),
    val constants: MutableMap<String, ConstantSymbol> = mutableMapOf()
) : Symbol()

class UnknownSymbol : Symbol() {
    override val name: String = "<unknown>" // 表示尚未被解析出的Symbol
}

enum class ScopeKind {
    Crate, Block, Function, Impl, Trait, Loop
}

sealed class Scope {
    abstract val parent: Scope? // 上一层scope
    abstract val children: MutableList<Scope>
    abstract val kind: ScopeKind
    val symbols = mutableMapOf<String, Symbol>()

    fun define(symbol: Symbol) {
        symbols[symbol.name] = symbol
    }

    fun lookup(name: String): Symbol? {
        return symbols[name] ?: parent?.lookup(name)
    }

    fun lookupLocal(name: String): Symbol? {
        return symbols[name]
    }
}

data class CrateScope(
    override val parent: Scope? = null, // 上一层scope
    override val children: MutableList<Scope> = mutableListOf(),
) : Scope() {
    override val kind: ScopeKind = ScopeKind.Crate
}

data class BlockScope(
    override val parent: Scope? = null, // 上一层scope
    override val children: MutableList<Scope> = mutableListOf(),
) : Scope() {
    override val kind: ScopeKind = ScopeKind.Block
}

data class FunctionScope(
    override val parent: Scope? = null, // 上一层scope
    override val children: MutableList<Scope> = mutableListOf(),
    var returnType: ResolvedType,
    val functionSymbol: FunctionSymbol,
) : Scope() {
    override val kind: ScopeKind = ScopeKind.Function
}

data class ImplScope(
    override val parent: Scope? = null, // 上一层scope
    override val children: MutableList<Scope> = mutableListOf(),
    val implType: ResolvedType, // 实现的type
    val traitName: String?, // 第一次pass时只做到记录name
    val implTrait: TraitSymbol? = null // 后续再连到traitSymbol
) : Scope() {
    override val kind: ScopeKind = ScopeKind.Impl
}

data class TraitScope(
    override val parent: Scope? = null, // 上一层scope
    override val children: MutableList<Scope> = mutableListOf(),
    val traitSymbol: TraitSymbol,
) : Scope() {
    override val kind: ScopeKind = ScopeKind.Trait
}

data class LoopScope(
    override val parent: Scope? = null, // 上一层scope
    override val children: MutableList<Scope> = mutableListOf(),
    var breakType: ResolvedType,
) : Scope() {
    override val kind: ScopeKind = ScopeKind.Loop
}

class ScopeTree {
    var currentScope: Scope = CrateScope() // 建一个CrateScope

    fun enterBlockScope() {
        val newScope = BlockScope(parent = currentScope)
        currentScope.children.add(newScope)
        currentScope = newScope
    }

    fun enterFunctionScope(returnType: ResolvedType, functionSymbol: FunctionSymbol) {
        val newScope = FunctionScope(
            parent = currentScope,
            returnType = returnType,
            functionSymbol = functionSymbol
        )
        currentScope.children.add(newScope)
        currentScope = newScope
    }

    fun enterImplScope(implType: ResolvedType, traitName: String?) {
        val newScope = ImplScope(parent = currentScope, implType = implType, traitName = traitName)
        currentScope.children.add(newScope)
        currentScope = newScope
    }

    fun enterTraitScope(traitSymbol: TraitSymbol) {
        val newScope = TraitScope(parent = currentScope, traitSymbol = traitSymbol)
        currentScope.children.add(newScope)
        currentScope = newScope
    }

    fun enterLoopScope(breakType: ResolvedType) {
        val newScope = LoopScope(parent = currentScope, breakType = breakType)
        currentScope.children.add(newScope)
        currentScope = newScope
    }

    fun exitScope() {
        if (currentScope.parent != null) {
            currentScope = currentScope.parent!!
        } else {
            throw SemanticException("error: exit crate scope")
        }
    }

    fun define(symbol: Symbol) {
        currentScope.define(symbol)
    }

    fun lookup(name: String): Symbol? {
        return currentScope.lookup(name)
    }
}