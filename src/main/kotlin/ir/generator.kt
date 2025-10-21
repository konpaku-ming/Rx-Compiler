package ir

import exception.SemanticException
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
import ast.ContinueExprNode
import ast.CrateNode
import ast.DerefExprNode
import ast.EmptyStmtNode
import ast.EnumItemNode
import ast.ExprStmtNode
import ast.FieldExprNode
import ast.FunctionItemNode
import ast.GroupedExprNode
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
import ast.StringLiteralExprNode
import ast.StructExprNode
import ast.StructItemNode
import ast.TraitItemNode
import ast.TypeCastExprNode
import ast.stringToUInt
import ast.stringToString
import ast.stringToChar
import ast.stringToRawString
import ast.stringToCString
import ast.stringToRawCString

class LLVMIRGenerator(
    private val emitter: LLVMEmitter,
    private val context: IRContext
) : ASTVisitor {

    lateinit var result: String

    override fun visitCrate(node: CrateNode) {
        TODO("Not yet implemented")
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
        TODO("Not yet implemented")
    }

    override fun visitBlockExpr(node: BlockExprNode, createScope: Boolean) {
        TODO("Not yet implemented")
    }

    override fun visitPredicateLoopExpr(node: PredicateLoopExprNode) {
        TODO("Not yet implemented")
    }

    override fun visitInfiniteLoopExpr(node: InfiniteLoopExprNode) {
        TODO("Not yet implemented")
    }

    override fun visitEmptyStmt(node: EmptyStmtNode) {
        TODO("Not yet implemented")
    }

    override fun visitLetStmt(node: LetStmtNode) {
        TODO("Not yet implemented")
    }

    override fun visitExprStmt(node: ExprStmtNode) {
        TODO("Not yet implemented")
    }

    override fun visitIntLiteralExpr(node: IntLiteralExprNode) {
        val temp = emitter.nextTemp()
        emitter.emit("$temp = add i32 0, ${stringToUInt(node.raw).toInt()}")
        result = temp
    }

    override fun visitCharLiteralExpr(node: CharLiteralExprNode) {
        TODO("Not yet implemented")
    }

    override fun visitStringLiteralExpr(node: StringLiteralExprNode) {
        TODO("Not yet implemented")
    }

    override fun visitBooleanLiteralExpr(node: BooleanLiteralExprNode) {
        val temp = emitter.nextTemp()
        val boolVal = if (node.raw == "true") 1 else 0
        emitter.emit("$temp = add i1 0, $boolVal")
        result = temp
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
        TODO("Not yet implemented")
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
        node.left.accept(this)
        val left = result
        node.right.accept(this)
        val right = result
        val temp = emitter.nextTemp()
        val op = when (node.operator.value) {
            "+" -> "add"
            "-" -> "sub"
            "*" -> "mul"
            "/" -> "sdiv"
            "%" -> "srem"
            "&" -> "and"
            "|" -> "or"
            "^" -> "xor"
            "<<" -> "shl"
            ">>" -> "ashr"
            else -> throw SemanticException(
                "Unsupported binary operator: ${node.operator.value}"
            )
        }
        emitter.emit("$temp = $op i32 $left, $right")
        result = temp
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
        node.inner.accept(this)
    }

    override fun visitArrayListExpr(node: ArrayListExprNode) {
        TODO("Not yet implemented")
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
        TODO("Not yet implemented")
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

    override fun visitBreakExpr(node: BreakExprNode) {
        TODO("Not yet implemented")
    }

    override fun visitContinueExpr(node: ContinueExprNode) {
        TODO("Not yet implemented")
    }

    override fun visitReturnExpr(node: ReturnExprNode) {
        TODO("Not yet implemented")
    }
}
