import ast.FifthVisitor
import ast.Lexer
import ast.Parser
import ast.ScopeTree
import ast.FirstVisitor
import ast.FourthVisitor
import ast.SecondVisitor
import ast.ThirdVisitor
import ast.removeComments
import java.io.File
import kotlin.system.exitProcess
import exception.CompilerException
import ir.ASTLower
import ir.PreDefiner
import ir.IntegerConfirmer
import llvm.LLVMContext
import llvm.Module
import llvm.IRBuilder

fun main(args: Array<String>) {
    if (args.size != 1) {
        System.err.println("expected source code file")
        exitProcess(1)
    }

    val filePath = args[0]
    val sourceCode: String
    try {
        val file = File(filePath)
        sourceCode = file.readText(Charsets.UTF_8)
    } catch (e: Exception) {
        System.err.println("error: cannot read file '$filePath': ${e.message}")
        exitProcess(1)
    }

    // println("--- source file ---")
    // println(sourceCode)
    // println("-------------------")

    try {
        val lexer = Lexer(removeComments(sourceCode))
        val parser = Parser(lexer.getTokens())
        val ast = parser.parse()
        val semanticScopeTree = ScopeTree()
        val firstVisitor = FirstVisitor(semanticScopeTree)
        firstVisitor.visitCrate(node = ast) // 第一次pass
        val secondVisitor = SecondVisitor(semanticScopeTree)
        secondVisitor.visitCrate(node = ast) // 第二次pass
        val thirdVisitor = ThirdVisitor(semanticScopeTree)
        thirdVisitor.visitCrate(node = ast) // 第三次pass
        val fourthVisitor = FourthVisitor(semanticScopeTree)
        fourthVisitor.visitCrate(node = ast) // 第四次pass
        val fifthVisitor = FifthVisitor(semanticScopeTree)
        fifthVisitor.visitCrate(node = ast) // 第五次pass
        val intTypeConfirmer = IntegerConfirmer(semanticScopeTree)
        intTypeConfirmer.visitCrate(node = ast) // 确认整数类型

        val context = LLVMContext()
        val module = Module("main", context)
        val builder = IRBuilder(context)
        val structDefiner = PreDefiner(semanticScopeTree, context, module, builder)
        structDefiner.visitCrate(node = ast)
        val astLower = ASTLower(semanticScopeTree, context, module, builder)
        astLower.visitCrate(node = ast)

        // 生成LLVM IR并写入文件
        val irContent = module.print()
        try {
            File("main.ll").writeText(irContent, Charsets.UTF_8)
            println("IR has been written in main.ll")
        } catch (e: Exception) {
            System.err.println("error: cannot write file 'main.ll': ${e.message}")
            exitProcess(1)
        }


        println("success")
    } catch (e: CompilerException) {
        println("failed")
        System.err.println(e.message)
        exitProcess(1)
    }
}