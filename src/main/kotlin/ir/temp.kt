package ir

class LLVMEmitter {
    private val instructions = mutableListOf<String>()
    private var tempCounter = 1

    fun nextTemp(): String = "%${tempCounter++}"

    fun emit(instruction: String): String {
        instructions += instruction
        return instruction
    }

    fun getIR(): String = instructions.joinToString("\n")
}

class IRContext {
    private val variableMap = mutableMapOf<String, String>()

    fun assign(name: String, temp: String) {
        variableMap[name] = temp
    }

    fun current(name: String): String {
        return variableMap[name] ?: error("Variable '$name' not defined")
    }
}
