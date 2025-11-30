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
import ir.IntTypeConfirmer
import llvm.LLVMContext
import llvm.Module
import llvm.IRBuilder
import ir.StructDefiner

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
        val intTypeConfirmer = IntTypeConfirmer(semanticScopeTree)
        intTypeConfirmer.visitCrate(node = ast) // 确认整数类型

        /*
        val context = LLVMContext()
        val module = Module("main", context)
        val builder = IRBuilder(context)
        val structDefiner = StructDefiner(semanticScopeTree, context, module, builder)
        structDefiner.visitCrate(node = ast)
         */
        /*
                // 生成LLVM IR
                println("\n--- generating IR ---")
                val emitter = LLVMEmitter()
                val irGenerator = LLVMIRGenerator(emitter, semanticScopeTree)
                irGenerator.visitCrate(ast)

                println("\n--- generated IR ---")
                println(emitter.getIR())
                println("-------------------")
        */
        println("success")
    } catch (e: CompilerException) {
        println("failed")
        System.err.println(e.message)
        exitProcess(1)
    }
}