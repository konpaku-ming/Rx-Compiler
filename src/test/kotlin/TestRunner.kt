import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.io.File
import kotlin.system.exitProcess

import exception.CompilerException
import ast.removeComments
import ast.Lexer
import ast.Parser
import ast.ScopeTree
import ast.FirstVisitor
import ast.SecondVisitor
import ast.ThirdVisitor
import ast.FourthVisitor
import ast.FifthVisitor
import ir.PreDefiner
import llvm.IRBuilder
import llvm.LLVMContext
import llvm.Module

data class CaseResult(
    val file: Path,
    val expectedPass: Boolean,
    val actualPass: Boolean,
    val errorMsg: String? = null,
    val sourcePreview: String = ""
)

data class Stats(
    var total: Int = 0,
    var expectedPass: Int = 0,
    var expectedFail: Int = 0,
    var match: Int = 0,
    var mismatch: Int = 0
)

fun main(args: Array<String>) {
    val verbose = !args.contains("--quiet")
    val failFast = args.contains("--fail-fast")
    val showSource = args.contains("--show-source")

    val projectRoot = Paths.get("").toAbsolutePath()
    val testsRoot = projectRoot.resolve("tests")
    val passDir = testsRoot.resolve("pass")
    val failDir = testsRoot.resolve("fail")

    if (!Files.isDirectory(testsRoot)) {
        println("tests directory not found: $testsRoot")
        exitProcess(1)
    }

    val passFiles = listFiles(passDir)
    val failFiles = listFiles(failDir)
    println("Discovered cases: PASS dir ${passFiles.size} cases, FAIL dir ${failFiles.size} cases")

    val stats = Stats()
    val mismatches = mutableListOf<CaseResult>()

    runBatch(passFiles, expectedPass = true, projectRoot, stats, mismatches, verbose, failFast, showSource)
    runBatch(failFiles, expectedPass = false, projectRoot, stats, mismatches, verbose, failFast, showSource)

    println("======== Test Summary ========")
    println("Total cases: ${stats.total}")
    println("Expected PASS: ${stats.expectedPass}  Expected FAIL: ${stats.expectedFail}")
    println("Matches with expectation: ${stats.match}")
    println("Mismatches with expectation: ${stats.mismatch}")

    if (mismatches.isNotEmpty()) {
        println("======== Cases not matching expectation ========")
        mismatches.forEach { r ->
            val rel = projectRoot.relativize(r.file)
            val exp = if (r.expectedPass) "PASS" else "FAIL"
            val act = if (r.actualPass) "PASS" else "FAIL"
            println("File: $rel")
            println("Expected: $exp  Actual: $act")
            if (!r.errorMsg.isNullOrBlank()) {
                println("Error message: ${r.errorMsg}")
            }
            println("---------------------------")
        }
        exitProcess(1)
    } else {
        println("All cases behaved as expected.")
        exitProcess(0)
    }
}

private fun runBatch(
    files: List<Path>,
    expectedPass: Boolean,
    projectRoot: Path,
    stats: Stats,
    mismatches: MutableList<CaseResult>,
    verbose: Boolean,
    failFast: Boolean,
    showSource: Boolean
) {
    files.forEachIndexed { idx, file ->
        val r = compileOne(file, expectedPass, showSource)

        stats.total++
        if (expectedPass) stats.expectedPass++ else stats.expectedFail++
        val ok = r.expectedPass == r.actualPass
        if (ok) stats.match++ else stats.mismatch++

        // Print when verbose, or when there's a mismatch (so expected failures that actually fail
        // won't be printed unless verbose is true)
        if (verbose || !ok) {
            val tag = if (ok) "[OK ]" else "[ERR]"
            val rel = projectRoot.relativize(r.file)
            val exp = if (r.expectedPass) "PASS" else "FAIL"
            val act = if (r.actualPass) "PASS" else "FAIL"
            println("$tag ${idx + 1}/${files.size}  $rel  expected:$exp  actual:$act")
            // only print error details when there is an error message (we suppress messages for
            // expected failures by returning null errorMsg)
            if (!r.errorMsg.isNullOrBlank()) {
                println(" Error: ${r.errorMsg}")
            }
            if (showSource && r.sourcePreview.isNotBlank()) {
                println(" Source preview:")
                println(r.sourcePreview)
            }
        }

        if (!ok) {
            mismatches += r
            if (failFast) return
        }
    }
}

private fun compileOne(file: Path, expectedPass: Boolean, showSource: Boolean): CaseResult {
    val source = try {
        File(file.toString()).readText(Charsets.UTF_8)
    } catch (e: Exception) {
        val msg = if (expectedPass) "Failed to read file: ${e.message}" else null
        return CaseResult(file, expectedPass, actualPass = false, errorMsg = msg)
    }

    val sourcePreview = if (showSource) source.take(2000) else ""

    return try {
        // Reuse the same compilation pipeline as your Main.kt
        val lexer = Lexer(removeComments(source))
        val parser = Parser(lexer.getTokens())
        val ast = parser.parse()

        val semanticScopeTree = ScopeTree()
        FirstVisitor(semanticScopeTree).visitCrate(ast)
        SecondVisitor(semanticScopeTree).visitCrate(ast)
        ThirdVisitor(semanticScopeTree).visitCrate(ast)
        FourthVisitor(semanticScopeTree).visitCrate(ast)
        FifthVisitor(semanticScopeTree).visitCrate(ast)
        val context = LLVMContext()
        val module = Module("main", context)
        val builder = IRBuilder(context)
        PreDefiner(semanticScopeTree, context, module, builder).visitCrate(ast)

        CaseResult(file, expectedPass, actualPass = true, sourcePreview = sourcePreview)
    } catch (e: CompilerException) {
        val msg = if (expectedPass) e.message else null
        CaseResult(file, expectedPass, actualPass = false, errorMsg = msg, sourcePreview = sourcePreview)
    } catch (e: Throwable) {
        // For expected failures, suppress detailed message; for expected passes, give concise info.
        val msg = if (expectedPass) "Unexpected exception: ${e::class.simpleName}: ${e.message}" else null
        CaseResult(
            file,
            expectedPass,
            actualPass = false,
            errorMsg = msg,
            sourcePreview = sourcePreview
        )
    }
}

private fun listFiles(dir: Path): List<Path> {
    if (!Files.isDirectory(dir)) return emptyList()
    return Files.list(dir).use { s ->
        s.filter { Files.isRegularFile(it) }
            .sorted()
            .collect(java.util.stream.Collectors.toList())
    }
}
