package exception

abstract class CompilerException(message: String) : RuntimeException(message)

data class SyntaxException(
    val originalMessage: String,
) : CompilerException("Syntax Error: $originalMessage")

data class SemanticException(
    val originalMessage: String,
) : CompilerException("Semantic Error: $originalMessage")

data class IRException(
    val originalMessage: String,
) : CompilerException("Generating IR Error: $originalMessage")