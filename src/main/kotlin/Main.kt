import ast.Lexer
import ast.Parser
import ast.removeComments
import java.io.File
import kotlin.system.exitProcess
import exception.CompilerException

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

    println("--- 文件内容 ---")
    println(sourceCode)
    println("-------------------")

    try {
        val lexer = Lexer(removeComments(sourceCode))
        /*
        for (item in lexer.getTokens()) {
            item.printToken()
        }
        */
        val parser = Parser(lexer.getTokens())
        val ast = parser.parse()
    } catch (e: CompilerException) {
        System.err.println(e.message)
        exitProcess(1)
    }
}