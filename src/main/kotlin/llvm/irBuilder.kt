package llvm

import exception.IRException
import kotlin.text.ifEmpty

class IRBuilder(val context: LLVMContext) {
    private var insertBlock: BasicBlock? = null  // 当前插入的基本块
    private var regCounter: Int = 0

    private fun genLLVMReg(): String {
        // 生成唯一寄存器名称
        return "tmp.${regCounter++}"
    }

    private fun createBinaryOp(lhs: Value, rhs: Value, name: String, opName: String): BinaryOperator {
        if (insertBlock == null) {
            throw IRException("No insert block")
        }
        val actualName = name.ifEmpty { genLLVMReg() }
        val binOp = BinaryOperator(actualName, opName, lhs.myGetType(), lhs, rhs)
        insertBlock!!.addInstruction(binOp)
        return binOp
    }

    private fun createICmp(lhs: Value, rhs: Value, name: String, pred: String): ICmpInst {
        if (insertBlock == null) {
            throw IRException("No insert block")
        }
        val actualName = name.ifEmpty { genLLVMReg() }
        val icmp = ICmpInst(actualName, pred, context.myGetI1Type(), lhs, rhs)
        insertBlock!!.addInstruction(icmp)
        return icmp
    }

    fun setInsertPoint(point: BasicBlock) { // 设置插入的BB
        insertBlock = point
    }

    fun myGetInsertBlock(): BasicBlock? = insertBlock // 获取当前BB

    fun myGetInsertFunction(): Function? = insertBlock?.parent // 获取当前函数

    fun createAdd(lhs: Value, rhs: Value, name: String = ""): BinaryOperator {
        return createBinaryOp(lhs, rhs, name, "add")
    }

    fun createSub(lhs: Value, rhs: Value, name: String = ""): BinaryOperator {
        return createBinaryOp(lhs, rhs, name, "sub")
    }

    fun createMul(lhs: Value, rhs: Value, name: String = ""): BinaryOperator {
        return createBinaryOp(lhs, rhs, name, "mul")
    }

    fun createSDiv(lhs: Value, rhs: Value, name: String = ""): BinaryOperator {
        return createBinaryOp(lhs, rhs, name, "sdiv")
    }

    fun createUDiv(lhs: Value, rhs: Value, name: String = ""): BinaryOperator {
        return createBinaryOp(lhs, rhs, name, "udiv")
    }

    fun createSRem(lhs: Value, rhs: Value, name: String = ""): BinaryOperator {
        return createBinaryOp(lhs, rhs, name, "srem")
    }

    fun createURem(lhs: Value, rhs: Value, name: String = ""): BinaryOperator {
        return createBinaryOp(lhs, rhs, name, "urem")
    }

    fun createShl(lhs: Value, rhs: Value, name: String = ""): BinaryOperator {
        return createBinaryOp(lhs, rhs, name, "shl")
    }

    fun createAShr(lhs: Value, rhs: Value, name: String = ""): BinaryOperator {
        return createBinaryOp(lhs, rhs, name, "ashr")
    }

    fun createLShr(lhs: Value, rhs: Value, name: String = ""): BinaryOperator {
        return createBinaryOp(lhs, rhs, name, "lshr")
    }

    fun createAnd(lhs: Value, rhs: Value, name: String = ""): BinaryOperator {
        return createBinaryOp(lhs, rhs, name, "and")
    }

    fun createOr(lhs: Value, rhs: Value, name: String = ""): BinaryOperator {
        return createBinaryOp(lhs, rhs, name, "or")
    }

    fun createXor(lhs: Value, rhs: Value, name: String = ""): BinaryOperator {
        return createBinaryOp(lhs, rhs, name, "xor")
    }

    fun createAlloca(type: IRType, name: String = ""): AllocaInst {
        if (insertBlock == null) {
            throw IRException("No insert block")
        }
        val actualName = name.ifEmpty { genLLVMReg() }
        val alloca = AllocaInst(actualName, type)
        insertBlock!!.addInstruction(alloca)
        return alloca
    }

    fun createLoad(type: IRType, ptr: Value, name: String = ""): LoadInst {
        if (insertBlock == null) {
            throw IRException("No insert block")
        }
        val actualName = name.ifEmpty { genLLVMReg() }
        val load = LoadInst(actualName, type, ptr)
        insertBlock!!.addInstruction(load)
        return load
    }

    fun createStore(value: Value, ptr: Value): StoreInst {
        if (insertBlock == null) {
            throw IRException("No insert block")
        }
        val store = StoreInst(value.myGetType(), ptr, value)
        insertBlock!!.addInstruction(store)
        return store
    }

    fun createRet(value: Value): ReturnInst {
        if (insertBlock == null) {
            throw IRException("No insert block")
        }
        val ret = ReturnInst(value)
        insertBlock!!.addInstruction(ret)
        return ret
    }

    fun createBr(dest: BasicBlock): BrInst {
        if (insertBlock == null) {
            throw IRException("No insert block")
        }
        val br = BrInst(dest)
        insertBlock!!.addInstruction(br)
        return br
    }

    fun createCondBr(cond: Value, thenBB: BasicBlock, elseBB: BasicBlock): ConBrInst {
        if (insertBlock == null) {
            throw IRException("No insert block")
        }
        val condBr = ConBrInst(cond, thenBB, elseBB)
        insertBlock!!.addInstruction(condBr)
        return condBr
    }

    fun createICmpEQ(lhs: Value, rhs: Value, name: String = ""): ICmpInst {
        return createICmp(lhs, rhs, name, "eq")
    }

    fun createICmpNE(lhs: Value, rhs: Value, name: String = ""): ICmpInst {
        return createICmp(lhs, rhs, name, "ne")
    }

    fun createICmpSLT(lhs: Value, rhs: Value, name: String = ""): ICmpInst {
        return createICmp(lhs, rhs, name, "slt")
    }

    fun createICmpSLE(lhs: Value, rhs: Value, name: String = ""): ICmpInst {
        return createICmp(lhs, rhs, name, "sle")
    }

    fun createICmpSGT(lhs: Value, rhs: Value, name: String = ""): ICmpInst {
        return createICmp(lhs, rhs, name, "sgt")
    }

    fun createICmpSGE(lhs: Value, rhs: Value, name: String = ""): ICmpInst {
        return createICmp(lhs, rhs, name, "sge")
    }

    fun createICmpULT(lhs: Value, rhs: Value, name: String = ""): ICmpInst {
        return createICmp(lhs, rhs, name, "ult")
    }

    fun createICmpULE(lhs: Value, rhs: Value, name: String = ""): ICmpInst {
        return createICmp(lhs, rhs, name, "ule")
    }

    fun createICmpUGT(lhs: Value, rhs: Value, name: String = ""): ICmpInst {
        return createICmp(lhs, rhs, name, "ugt")
    }

    fun createICmpUGE(lhs: Value, rhs: Value, name: String = ""): ICmpInst {
        return createICmp(lhs, rhs, name, "uge")
    }

    // PHI节点
    fun createPHI(type: IRType, name: String = ""): PHINode {
        if (insertBlock == null) {
            throw IRException("No insert block")
        }
        val actualName = name.ifEmpty { genLLVMReg() }
        val phi = PHINode(actualName, type)
        insertBlock!!.addInstruction(phi)
        return phi
    }

    // 其他指令
    fun createCall(func: Function, args: List<Value>, name: String = ""): CallInst {
        if (insertBlock == null) {
            throw IRException("No insert block")
        }
        val actualName = name.ifEmpty { genLLVMReg() }
        val call = CallInst(actualName, func, args)
        insertBlock!!.addInstruction(call)
        return call
    }

    fun createPtrToInt(type: IRType, ptr: Value, name: String = ""): PtrToIntInst {
        if (insertBlock == null) {
            throw IRException("No insert block")
        }
        val actualName = name.ifEmpty { genLLVMReg() }
        val ptrToInt = PtrToIntInst(actualName, type, ptr)
        insertBlock!!.addInstruction(ptrToInt)
        return ptrToInt
    }

    fun createZExt(type: IRType, value: Value, name: String = ""): ZExtInst {
        if (insertBlock == null) {
            throw IRException("No insert block")
        }
        val actualName = name.ifEmpty { genLLVMReg() }
        val zExt = ZExtInst(actualName, type, value)
        insertBlock!!.addInstruction(zExt)
        return zExt
    }

    fun createGEP(baseType: IRType, ptr: Value, indices: List<Value>, name: String = ""): GetElementPtrInst {
        if (insertBlock == null) {
            throw IRException("No insert block")
        }
        val actualName = name.ifEmpty { genLLVMReg() }
        val pointerType = context.myGetPointerType()
        val gep = GetElementPtrInst(actualName, pointerType, baseType, ptr, indices)
        insertBlock!!.addInstruction(gep)
        return gep
    }

    fun createMemCpy(dest: Value, src: Value, size: Value, isVolatile: Boolean): CallInst {
        if (insertBlock == null) {
            throw IRException("No insert block")
        }
        // 获取当前函数所属的Module
        val currentFunc = myGetInsertFunction() ?: throw IRException("No insert function")
        val module = currentFunc.parent
        // 检测size类型是否为i32整数类型
        if (size.myGetType() != context.myGetI32Type()) {
            throw IRException("Size type must be i32")
        }
        // memcpy函数类型：void (ptr, ptr, i32, i1)
        val voidType = context.myGetVoidType()
        val ptrType = context.myGetPointerType()
        val i1Type = context.myGetI1Type()

        val paramTypes = listOf(ptrType, ptrType, context.myGetI32Type(), i1Type)
        val memcpyType = context.myGetFunctionType(voidType, paramTypes)
        val memcpyFunc = module.myGetOrCreateFunction("llvm.memcpy.p0.p0.i32", memcpyType)
        val args = mutableListOf<Value>().apply {
            add(dest)
            add(src)
            add(size)
            val volatileConst = context.myGetIntConstant(
                context.myGetI1Type(),
                if (isVolatile) 1U else 0U,
            )
            add(volatileConst)
        }

        return createCall(memcpyFunc, args, "")
    }
}
