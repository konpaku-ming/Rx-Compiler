import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
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

// Constants
private const val TEST_DATA_REPO_URL = "git@github.com:peterzheng98/RCompiler-Testcases.git"
private const val TEST_DATA_REPO_NAME = "RCompiler-Testcases"

// WSL-related utilities
data class ClangConfig(
    val command: String,
    val useWSL: Boolean = false
)

data class GitConfig(
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

    // Automatically fetch test data if IR-1 directory doesn't exist or is empty
    if (!Files.isDirectory(ir1Dir) || isDirectoryEmpty(ir1Dir)) {
        println("Fetching test data from external repository...")
        
        // Check for git availability
        val gitConfig = findGit()
        if (gitConfig == null) {
            println("Error: git not found. Please install git.")
            exitProcess(1)
        }
        
        if (gitConfig.useWSL) {
            println("Using git from WSL: ${gitConfig.command}")
        } else {
            println("Using native git: ${gitConfig.command}")
        }
        
        val fetchResult = fetchTestData(projectRoot, ir1Dir, gitConfig)
        if (!fetchResult) {
            println("Failed to fetch test data. Please check your internet connection.")
            exitProcess(1)
        }
        println("Test data fetched successfully.")
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

private fun findGit(): GitConfig? {
    // On Windows, we prefer WSL git
    if (isWindows()) {
        // Try WSL git first
        try {
            val process = ProcessBuilder("wsl", "which", "git")
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()
            process.waitFor()
            if (process.exitValue() == 0) {
                return GitConfig("git", useWSL = true)
            }
        } catch (e: Exception) {
            // WSL not available, try Windows git
        }
        
        // Try native Windows git (check if the command exists by running it with --version)
        try {
            val process = ProcessBuilder("git", "--version")
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()
            process.waitFor()
            if (process.exitValue() == 0) {
                return GitConfig("git", useWSL = false)
            }
        } catch (e: Exception) {
            // Git not found
        }
    } else {
        // On Unix-like systems, use 'which' to find git
        try {
            val process = ProcessBuilder("which", "git")
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()
            process.waitFor()
            if (process.exitValue() == 0) {
                return GitConfig("git", useWSL = false)
            }
        } catch (e: Exception) {
            // Git not found
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
            Files.walk(tempDir).use { paths ->
                paths.sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            }
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

private fun isDirectoryEmpty(directory: Path): Boolean {
    if (!Files.isDirectory(directory)) return true
    Files.list(directory).use { stream ->
        return !stream.findFirst().isPresent
    }
}

private fun fetchTestData(projectRoot: Path, ir1Dir: Path, gitConfig: GitConfig): Boolean {
    val tempDir = Files.createTempDirectory("ir_testdata_fetch")
    try {
        // Use git sparse-checkout to fetch only IR-1/src from the external repository
        val cloneDir = tempDir.resolve(TEST_DATA_REPO_NAME)
        
        // Step 1: Initialize sparse checkout
        // Note: The clone command uses TEST_DATA_REPO_NAME as a relative path, which creates
        // a subdirectory within tempDir (the working directory)
        val initResult = runGitCommand(
            listOf("git", "clone", "--depth", "1", "--filter=blob:none", "--sparse", TEST_DATA_REPO_URL, TEST_DATA_REPO_NAME),
            tempDir,
            gitConfig
        )
        if (!initResult) {
            return false
        }
        
        // Step 2: Configure sparse-checkout
        val sparseCheckoutResult = runGitCommand(
            listOf("git", "sparse-checkout", "init", "--cone"),
            cloneDir,
            gitConfig
        )
        if (!sparseCheckoutResult) {
            return false
        }
        
        // Step 3: Set sparse-checkout to IR-1/src
        val setPathResult = runGitCommand(
            listOf("git", "sparse-checkout", "set", "IR-1/src"),
            cloneDir,
            gitConfig
        )
        if (!setPathResult) {
            return false
        }
        
        // Step 4: Copy test data to project IR-1 directory
        val srcDir = cloneDir.resolve("IR-1/src")
        if (!Files.isDirectory(srcDir)) {
            println("Error: IR-1/src directory not found in fetched repository")
            return false
        }
        
        // Create IR-1 directory if it doesn't exist
        if (!Files.exists(ir1Dir)) {
            Files.createDirectories(ir1Dir)
        }
        
        // Copy files from subdirectories to flat structure
        // External structure: IR-1/src/<testname>/<testname>.{rx,in,out}
        // Target structure: IR-1/<testname>.{rx,in,out}
        // Only process directories starting with "comprehensive"
        Files.list(srcDir).use { testDirs ->
            testDirs.filter { Files.isDirectory(it) && it.fileName.toString().startsWith("comprehensive") }
                .forEach { testDir ->
                    val testName = testDir.fileName.toString()
                    
                    // Copy .rx, .in, and .out files
                    for (ext in listOf(".rx", ".in", ".out")) {
                        val sourceFile = testDir.resolve("$testName$ext")
                        if (Files.exists(sourceFile)) {
                            val targetFile = ir1Dir.resolve("$testName$ext")
                            Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING)
                        }
                    }
                }
        }
        
        return true
    } catch (e: Exception) {
        println("Error fetching test data: ${e.message}")
        return false
    } finally {
        // Clean up temporary directory
        try {
            Files.walk(tempDir).use { paths ->
                paths.sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            }
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }
}

private fun runGitCommand(command: List<String>, workingDir: Path, gitConfig: GitConfig): Boolean {
    return try {
        // Use WSL's git if configured to do so
        val actualCommand = if (gitConfig.useWSL) {
            // Prepend "wsl" to the command list
            listOf("wsl") + command
        } else {
            command
        }
        
        val process = ProcessBuilder(actualCommand)
            .directory(workingDir.toFile())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            val errorOutput = process.errorStream.bufferedReader().readText()
            println("Git command failed: ${actualCommand.joinToString(" ")}")
            println("Error: $errorOutput")
            false
        } else {
            true
        }
    } catch (e: Exception) {
        println("Exception running git command: ${e.message}")
        false
    }
}
