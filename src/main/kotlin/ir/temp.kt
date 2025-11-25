package ir

import exception.IRException

class LLVMEmitter {
    private var tempCounter = 1
    private var blockCounter = 0
    private var fnCounter = 1

    // 模块级：函数表和全局定义
    private val functions = LinkedHashMap<String, LinkedHashMap<String, MutableList<String>>>()
    private val signatures = LinkedHashMap<String, String>()
    private val globals = mutableListOf<String>()

    // 当前上下文
    var currentFunction: String? = null
    var currentBlock = "entry"
    var currentRetType: String? = null
    var importPtr: Boolean = false

    // 临时变量生成
    fun newTemp(): String = "%_${tempCounter++}"

    fun newFnCount(): String = "${fnCounter++}"

    // 开始一个函数
    fun startFunction(fnName: String, retType: String, params: List<String>) {
        currentFunction = fnName
        currentRetType = retType
        functions[fnName] = LinkedHashMap()
        importPtr = isCompositeType(retType) // 判断是否需要传入指针

        val sig = if (importPtr) {
            "define void @$fnName(ptr %ret, ${params.joinToString(", ")}) {"
        } else {
            "define $retType @$fnName(${params.joinToString(", ")}) {"
        }

        signatures[fnName] = sig
        currentBlock = "entry" // 开entry块
        functions[fnName]!![currentBlock] = mutableListOf()
        if (!importPtr && retType != "void") {
            emit("%${currentFunction}.retval_ptr = alloca $currentFunction, align 4") // 返回的值
        }
    }

    // 结束函数
    fun endFunction() {
        currentBlock = "end" // 开end块
        functions[currentFunction]!![currentBlock] = mutableListOf()
        if (importPtr || currentRetType == "void") {
            emit("ret void")
        } else {
            // 先load，再返回
            emit("%${currentFunction}.val = load $currentRetType, ptr %${currentFunction}.retval_ptr")
            emit("ret $currentRetType %${currentFunction}.val")
        }

        currentFunction = null
        currentRetType = null
        importPtr = false
    }

    // 向当前块添加指令
    fun emit(instruction: String) {
        val func = currentFunction ?: throw IRException("No active function")
        functions[func]!!.getOrPut(currentBlock) { mutableListOf() }.add(instruction)
    }

    // 切换块
    fun switchBlock(blockName: String) {
        currentBlock = blockName
        val func = currentFunction ?: throw IRException("No active function")
        functions[func]!!.getOrPut(blockName) { mutableListOf() }
    }

    // 创建块，并返回它的真实label
    fun newBlock(label: String): String {
        blockCounter++
        val blockName = "${label}_${blockCounter}"
        val func = currentFunction ?: throw IRException("No active function")
        functions[func]!![blockName] = mutableListOf()
        return blockName
    }

    // 添加全局定义
    fun emitGlobal(definition: String) {
        globals.add(definition)
    }

    // 输出完整 IR 程序
    fun getIR(): String {
        val result = mutableListOf<String>()
        // 全局定义
        result.addAll(globals)
        // 函数定义
        for ((funcName, blocks) in functions) {
            result.add(signatures[funcName]!!)
            for ((blockName, instructions) in blocks) {
                result.add("$blockName:")
                result.addAll(instructions.map { "    $it" })
            }
            result.add("}")
        }
        return result.joinToString("\n")
    }

    // 清理状态
    fun clear() {
        functions.clear()
        signatures.clear()
        globals.clear()
        tempCounter = 1
        blockCounter = 0
        currentFunction = null
        currentBlock = "entry"
        currentRetType = null
        importPtr = false
    }

    // 判断是否是需要传入ptr来返回的类型
    private fun isCompositeType(retType: String): Boolean {
        //struct and array
        return retType.startsWith("{") || retType.startsWith("[") || retType.startsWith("%")
    }
}


