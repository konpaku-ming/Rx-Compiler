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
import ir.ASTLower
import ir.PreDefiner
import ir.IntegerConfirmer
import llvm.LLVMContext
import llvm.Module
import llvm.IRBuilder

// WSL-related utilities
data class ClangConfig(
    val command: String,
    val useWSL: Boolean = false
)

data class IRTestResult(
    val testName: String,
    val passed: Boolean,
    val errorMsg: String? = null
)

data class IRTestStats(
    var total: Int = 0,
    var passed: Int = 0,
    var failed: Int = 0
)

fun main(args: Array<String>) {
    val verbose = !args.contains("--quiet")
    val failFast = args.contains("--fail-fast")

    val projectRoot = Paths.get("").toAbsolutePath()
    val ir1Dir = projectRoot.resolve("IR-1")
    val builtinFile = projectRoot.resolve("builtin.c")

    if (!Files.isDirectory(ir1Dir)) {
        println("IR-1 directory not found: $ir1Dir")
        exitProcess(1)
    }

    if (!Files.isRegularFile(builtinFile)) {
        println("builtin.c file not found: $builtinFile")
        exitProcess(1)
    }

    // Find all .rx files in IR-1 directory
    val testFiles = Files.list(ir1Dir).use { stream ->
        stream.filter { it.toString().endsWith(".rx") }
            .sorted()
            .collect(java.util.stream.Collectors.toList())
    }

    println("Discovered ${testFiles.size} test cases in IR-1 directory")

    val stats = IRTestStats()
    val failures = mutableListOf<IRTestResult>()

    // Check for clang availability
    val clangConfig = findClang()
    if (clangConfig == null) {
        println("Error: clang not found. Please install clang-15 or clang.")
        exitProcess(1)
    }
    
    if (clangConfig.useWSL) {
        println("Using clang from WSL: ${clangConfig.command}")
    }

    testFiles.forEachIndexed { idx, rxFile ->
        val testName = rxFile.fileName.toString().removeSuffix(".rx")
        val result = runIRTest(rxFile, testName, ir1Dir, builtinFile, clangConfig, projectRoot)

        stats.total++
        if (result.passed) {
            stats.passed++
        } else {
            stats.failed++
            failures.add(result)
        }

        if (verbose || !result.passed) {
            val tag = if (result.passed) "[PASS]" else "[FAIL]"
            println("$tag ${idx + 1}/${testFiles.size}  $testName")
            if (!result.errorMsg.isNullOrBlank()) {
                println("  Error: ${result.errorMsg}")
            }
        }

        if (!result.passed && failFast) {
            println("\nStopping on first failure (--fail-fast)")
            printSummary(stats, failures)
            exitProcess(1)
        }
    }

    printSummary(stats, failures)
    exitProcess(if (stats.failed == 0) 0 else 1)
}

private fun findClang(): ClangConfig? {
    val commands = listOf("clang-15", "clang")
    
    // On Windows, we can't use 'which', so we try WSL first
    if (isWindows()) {
        // Try WSL clang
        for (cmd in commands) {
            try {
                val process = ProcessBuilder("wsl", "which", cmd)
                    .redirectOutput(ProcessBuilder.Redirect.PIPE)
                    .redirectError(ProcessBuilder.Redirect.PIPE)
                    .start()
                process.waitFor()
                if (process.exitValue() == 0) {
                    return ClangConfig(cmd, useWSL = true)
                }
            } catch (e: Exception) {
                // Continue to next command
            }
        }
        
        // Try native Windows clang (check if the command exists by running it with --version)
        for (cmd in commands) {
            try {
                val process = ProcessBuilder(cmd, "--version")
                    .redirectOutput(ProcessBuilder.Redirect.PIPE)
                    .redirectError(ProcessBuilder.Redirect.PIPE)
                    .start()
                process.waitFor()
                if (process.exitValue() == 0) {
                    return ClangConfig(cmd, useWSL = false)
                }
            } catch (e: Exception) {
                // Continue to next command
            }
        }
    } else {
        // On Unix-like systems, use 'which' to find clang
        for (cmd in commands) {
            try {
                val process = ProcessBuilder("which", cmd)
                    .redirectOutput(ProcessBuilder.Redirect.PIPE)
                    .redirectError(ProcessBuilder.Redirect.PIPE)
                    .start()
                process.waitFor()
                if (process.exitValue() == 0) {
                    return ClangConfig(cmd, useWSL = false)
                }
            } catch (e: Exception) {
                // Continue to next command
            }
        }
    }
    
    return null
}

private fun isWindows(): Boolean {
    val os = System.getProperty("os.name").lowercase()
    return os.contains("windows")
}

private fun windowsPathToWSL(windowsPath: String): String {
    // Convert Windows path like "C:\Users\..." to WSL path "/mnt/c/Users/..."
    
    // UNC paths (\\server\share) are not well supported in WSL, return as-is
    if (windowsPath.startsWith("\\\\") || windowsPath.startsWith("//")) {
        return windowsPath
    }
    
    // Normalize backslashes to forward slashes
    val path = windowsPath.replace('\\', '/')
    
    // Remove trailing slash if present (except for root paths)
    val normalizedPath = if (path.length > 3 && path.endsWith('/')) {
        path.dropLast(1)
    } else {
        path
    }
    
    // Check if it's an absolute Windows path (e.g., C:/ or D:/)
    val driveLetterRegex = Regex("^([A-Za-z]):/(.*)$")
    val match = driveLetterRegex.matchEntire(normalizedPath)
    
    return if (match != null) {
        val driveLetter = match.groupValues[1].lowercase()
        val restOfPath = match.groupValues[2]
        "/mnt/$driveLetter/$restOfPath"
    } else {
        // If it's not an absolute path with drive letter, return as-is
        // This handles relative paths (though they shouldn't occur in our use case)
        normalizedPath
    }
}

private fun runIRTest(
    rxFile: Path,
    testName: String,
    ir1Dir: Path,
    builtinFile: Path,
    clangConfig: ClangConfig,
    projectRoot: Path
): IRTestResult {
    val inputFile = ir1Dir.resolve("$testName.in")
    val expectedOutputFile = ir1Dir.resolve("$testName.out")

    // Check if .in and .out files exist
    if (!Files.exists(inputFile)) {
        return IRTestResult(testName, false, "Input file not found: $inputFile")
    }
    if (!Files.exists(expectedOutputFile)) {
        return IRTestResult(testName, false, "Expected output file not found: $expectedOutputFile")
    }

    // Create a temporary directory for this test
    val tempDir = Files.createTempDirectory("ir_test_$testName")
    try {
        // Step 1: Compile .rx to LLVM IR
        val irFile = tempDir.resolve("test.ll")
        val compileResult = compileToIR(rxFile, irFile)
        if (!compileResult.success) {
            return IRTestResult(testName, false, "Compilation failed: ${compileResult.errorMsg}")
        }

        // Step 2: Compile IR with builtin.c using clang
        val executable = tempDir.resolve("test_program")
        val clangResult = compileWithClang(irFile, builtinFile, executable, clangConfig)
        if (!clangResult.success) {
            return IRTestResult(testName, false, "Clang compilation failed: ${clangResult.errorMsg}")
        }

        // Step 3: Run the executable with input
        val actualOutput = runProgram(executable, inputFile, clangConfig.useWSL)
        if (actualOutput == null) {
            return IRTestResult(testName, false, "Program execution failed")
        }

        // Step 4: Compare output
        val expectedOutput = Files.readString(expectedOutputFile)
        // Normalize line endings for cross-platform compatibility (Windows/WSL/Unix)
        val normalizedActual = normalizeLineEndings(actualOutput)
        val normalizedExpected = normalizeLineEndings(expectedOutput)
        if (normalizedActual == normalizedExpected) {
            return IRTestResult(testName, true)
        } else {
            return IRTestResult(testName, false, "Output mismatch")
        }
    } catch (e: Exception) {
        return IRTestResult(testName, false, "Unexpected error: ${e.message}")
    } finally {
        // Clean up temporary directory
        try {
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }
}

data class CompileResult(val success: Boolean, val errorMsg: String? = null)

private fun compileToIR(rxFile: Path, outputIRFile: Path): CompileResult {
    return try {
        val source = Files.readString(rxFile)

        // Run the compilation pipeline
        val lexer = Lexer(removeComments(source))
        val parser = Parser(lexer.getTokens())
        val ast = parser.parse()

        val semanticScopeTree = ScopeTree()
        FirstVisitor(semanticScopeTree).visitCrate(ast)
        SecondVisitor(semanticScopeTree).visitCrate(ast)
        ThirdVisitor(semanticScopeTree).visitCrate(ast)
        FourthVisitor(semanticScopeTree).visitCrate(ast)
        FifthVisitor(semanticScopeTree).visitCrate(ast)

        val intTypeConfirmer = IntegerConfirmer(semanticScopeTree)
        intTypeConfirmer.visitCrate(ast)

        val context = LLVMContext()
        val module = Module("main", context)
        val builder = IRBuilder(context)
        val structDefiner = PreDefiner(semanticScopeTree, context, module, builder)
        structDefiner.visitCrate(ast)
        val astLower = ASTLower(semanticScopeTree, context, module, builder)
        astLower.visitCrate(ast)

        // Generate LLVM IR
        val irContent = module.print()
        Files.writeString(outputIRFile, irContent)

        CompileResult(true)
    } catch (e: CompilerException) {
        CompileResult(false, e.message)
    } catch (e: Exception) {
        CompileResult(false, "Unexpected exception: ${e::class.simpleName}: ${e.message}")
    }
}

private fun compileWithClang(
    irFile: Path,
    builtinFile: Path,
    outputExecutable: Path,
    clangConfig: ClangConfig
): CompileResult {
    return try {
        val commandList = if (clangConfig.useWSL) {
            // Convert Windows paths to WSL paths
            val irFileWSL = windowsPathToWSL(irFile.toAbsolutePath().toString())
            val builtinFileWSL = windowsPathToWSL(builtinFile.toAbsolutePath().toString())
            val outputExecutableWSL = windowsPathToWSL(outputExecutable.toAbsolutePath().toString())
            
            listOf(
                "wsl",
                clangConfig.command,
                "-o", outputExecutableWSL,
                irFileWSL,
                builtinFileWSL
            )
        } else {
            listOf(
                clangConfig.command,
                "-o", outputExecutable.toString(),
                irFile.toString(),
                builtinFile.toString()
            )
        }
        
        val process = ProcessBuilder(commandList)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()

        process.waitFor()
        if (process.exitValue() == 0) {
            CompileResult(true)
        } else {
            val errorOutput = process.errorStream.bufferedReader().readText()
            CompileResult(false, "clang exit code ${process.exitValue()}: $errorOutput")
        }
    } catch (e: Exception) {
        CompileResult(false, "Exception running clang: ${e.message}")
    }
}

private fun runProgram(executable: Path, inputFile: Path, useWSL: Boolean): String? {
    return try {
        if (useWSL) {
            // When using WSL, we need to:
            // 1. Convert executable path to WSL format
            // 2. Use WSL to run the executable
            // 3. Redirect input from the Windows file (WSL can access Windows files)
            val executableWSL = windowsPathToWSL(executable.toAbsolutePath().toString())
            
            // WSL can read from Windows file paths directly via redirectInput
            val process = ProcessBuilder("wsl", executableWSL)
                .redirectInput(inputFile.toFile())
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()

            val exitCode = process.waitFor()
            if (exitCode == 0) {
                process.inputStream.bufferedReader().readText()
            } else {
                null
            }
        } else {
            // Native execution
            val process = ProcessBuilder(executable.toString())
                .redirectInput(inputFile.toFile())
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()

            val exitCode = process.waitFor()
            if (exitCode == 0) {
                process.inputStream.bufferedReader().readText()
            } else {
                null
            }
        }
    } catch (e: Exception) {
        null
    }
}

private fun normalizeLineEndings(text: String): String {
    // Normalize all line ending styles (CRLF, CR, LF) to LF
    return text.replace(Regex("\\r\\n?"), "\n").trim()
}

private fun printSummary(stats: IRTestStats, failures: List<IRTestResult>) {
    println("\n======== IR Test Summary ========")
    println("Total tests: ${stats.total}")
    println("Passed: ${stats.passed}")
    println("Failed: ${stats.failed}")

    if (failures.isNotEmpty()) {
        println("\n======== Failed Tests ========")
        failures.forEach { result ->
            println("${result.testName}: ${result.errorMsg}")
        }
    } else {
        println("\nAll tests passed!")
    }
}
