package exception

abstract class CompilerException(message: String) : RuntimeException(message)

data class SyntaxException(
    val originalMessage: String,
) : CompilerException("Syntax Error: $originalMessage")

data class SemanticException(
    val originalMessage: String,
) : CompilerException("Semantic Error: $originalMessage")