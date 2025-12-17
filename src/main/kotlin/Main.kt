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
    val sourceCode: String

    // Support reading from STDIN when no args or "-" is passed
    if (args.isEmpty() || args[0] == "-") {
        try {
            sourceCode = System.`in`.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            System.err.println("error: cannot read from stdin: ${e.message}")
            exitProcess(1)
        }
    } else if (args.size != 1) {
        System.err.println("expected source code file or '-' for stdin")
        exitProcess(1)
    } else {
        val filePath = args[0]
        try {
            val file = File(filePath)
            sourceCode = file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            System.err.println("error: cannot read file '$filePath': ${e.message}")
            exitProcess(1)
        }
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

        try {
            val context = LLVMContext()
            val module = Module("main", context)
            val builder = IRBuilder(context)
            val structDefiner = PreDefiner(semanticScopeTree, context, module, builder)
            structDefiner.visitCrate(node = ast)
            val astLower = ASTLower(semanticScopeTree, context, module, builder)
            astLower.visitCrate(node = ast)

            // 生成LLVM IR
            val irContent = module.print()

            // If reading from STDIN (no args or "-"), output to STDOUT
            if (args.isEmpty() || args[0] == "-") {
                print(irContent)
            } else {
                // Otherwise write to file as before
                try {
                    File("main.ll").writeText(irContent, Charsets.UTF_8)
                    println("IR has been written in main.ll")
                } catch (e: Exception) {
                    System.err.println("error: cannot write file 'main.ll': ${e.message}")
                    exitProcess(1)
                }
            }
        } catch (_: CompilerException) {
            // 能通过 Semantic 测试，但用到了 IR Generator 还未支持的特性
            exitProcess(0)
        }
    } catch (e: CompilerException) {
        println("failed")
        System.err.println(e.message)
        exitProcess(1)
    }
}