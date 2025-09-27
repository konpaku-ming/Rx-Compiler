package ast

import exception.SyntaxException

fun stringToInt(str: String): Int {
    var tempStr = str.replace("_", "")
    var base = 10
    if (tempStr.startsWith("0b")) {
        base = 2
        tempStr = tempStr.substring(2)
    } else if (tempStr.startsWith("0o")) {
        base = 8
        tempStr = tempStr.substring(2)
    } else if (tempStr.startsWith("0x")) {
        base = 16
        tempStr = tempStr.substring(2)
    }
    return tempStr.toInt(base)
}

fun stringToChar(str: String): Char {
    val tempStr = str.substring(1, str.length - 1)
    return if (tempStr.length == 1) tempStr[0]
    else {
        if (tempStr.startsWith("\\n")) '\n'
        else if (tempStr.startsWith("\\r")) '\r'
        else if (tempStr.startsWith("\\t")) '\t'
        else if (tempStr.startsWith("\\'")) '\''
        else if (tempStr.startsWith("\\\"")) '\"'
        else if (tempStr.startsWith("\\\\")) '\\'
        else if (tempStr.startsWith("\\0")) '\u0000'
        else if (tempStr.startsWith("\\x"))
            tempStr.substring(2).toInt(16).toChar()
        else throw SyntaxException("invalid char '$str'")
    }
}

fun stringToString(str: String): String {
    val tempStr = str.substring(1, str.length - 1)
    val builder = StringBuilder()
    var i = 0
    while (i < tempStr.length) {
        val c = tempStr[i]
        i++
        if (c != '\\') {
            builder.append(c)
        } else {
            when (tempStr[i]) {
                'n' -> builder.append('\n')
                'r' -> builder.append('\r')
                't' -> builder.append('\t')
                '"' -> builder.append('"')
                '\'' -> builder.append('\'')
                '\\' -> builder.append('\\')
                '0' -> builder.append('\u0000')
                'x' -> {
                    builder.append(tempStr.substring(i + 1, i + 3).toInt(16).toChar())
                    i += 2
                }

                else -> throw SyntaxException("invalid escape")
            }
            i++
        }
    }
    return builder.toString()
}

fun getPrecedence(tokenType: TokenType): Int {
    return when (tokenType) {
        TokenType.DoubleColon -> 15
        TokenType.Dot -> 14
        TokenType.LeftParen,
        TokenType.LeftBracket -> 13
        // - ! & && as prefix -> 12
        TokenType.AS -> 11
        TokenType.Mul,
        TokenType.Div,
        TokenType.Mod -> 10

        TokenType.Add,
        TokenType.SubNegate -> 9

        TokenType.Shl,
        TokenType.Shr -> 8

        TokenType.BitAnd -> 7
        TokenType.BitXor -> 6
        TokenType.BitOr -> 5
        TokenType.Eq,
        TokenType.Neq,
        TokenType.Lt,
        TokenType.Gt,
        TokenType.Le,
        TokenType.Ge -> 4

        TokenType.And -> 3
        TokenType.Or -> 2
        TokenType.Assign,
        TokenType.AddAssign,
        TokenType.SubAssign,
        TokenType.MulAssign,
        TokenType.DivAssign,
        TokenType.ModAssign,
        TokenType.ShlAssign,
        TokenType.ShrAssign,
        TokenType.AndAssign,
        TokenType.OrAssign,
        TokenType.XorAssign -> 1

        else -> 0
    }
}


class Parser(private val tokens: List<Token>) {
    private var position = 0
    private val tokenNum = tokens.size

    private fun peek(): Token {
        if (position >= tokenNum)
            throw SyntaxException("expect token but found none")
        else return tokens[position]
    }

    private fun consume(): Token {
        if (position >= tokenNum)
            throw SyntaxException("expect token but found none")
        else return tokens[position++]
    }

    private fun ahead(offset: Int): Token {
        if (position + offset >= tokenNum)
            throw SyntaxException("expect token but found none")
        else return tokens[position + offset]
    }

    private fun match(type: TokenType, value: String? = null): Boolean {
        if (position >= tokenNum)
            throw SyntaxException("expect token but found none")
        val cur = tokens[position]
        if (type == cur.type && (value == null || value == cur.value)) {
            position++
            return true
        } else return false
    }

    fun parse(): CrateNode {
        val items = mutableListOf<ItemNode>()
        while (!(position >= tokenNum || peek().type == TokenType.EOF)) {
            items.add(parseItem())
        }
        return CrateNode(items)
    }

    fun isExpr(): Boolean {
        return when (peek().type) {
            TokenType.INTEGER_LITERAL,
            TokenType.CHAR_LITERAL,
            TokenType.STRING_LITERAL,
            TokenType.TRUE,
            TokenType.FALSE,
            TokenType.C_STRING_LITERAL,
            TokenType.RAW_STRING_LITERAL,
            TokenType.RAW_C_STRING_LITERAL,
            TokenType.IDENTIFIER,
            TokenType.SELF,
            TokenType.SELF_CAP,
            TokenType.LeftParen,
            TokenType.LeftBracket,
            TokenType.LeftBrace,
            TokenType.Not,
            TokenType.SubNegate,
            TokenType.BitAnd,
            TokenType.And,
            TokenType.Mul,
            TokenType.IF,
            TokenType.LOOP,
            TokenType.WHILE,
            TokenType.BREAK,
            TokenType.CONTINUE,
            TokenType.RETURN,
            TokenType.MATCH -> true

            else -> false
        }
    }

    fun isStmt(): Boolean {
        return when (peek().type) {
            TokenType.Semicolon,
            TokenType.LET -> true

            else -> false
        }
    }

    fun isItem(): Boolean {
        return when (peek().type) {
            TokenType.FN,
            TokenType.STRUCT,
            TokenType.ENUM,
            TokenType.TRAIT,
            TokenType.CONST,
            TokenType.IMPL -> true

            else -> false
        }
    }

    fun parseExpr(precedence: Int): ExprNode {
        var left = parsePrefix()
        while (true) {
            val cur = peek()
            val curPrecedence = getPrecedence(cur.type)
            if (curPrecedence <= precedence) break
            left = parseInfix(left)
        }
        return left
    }

    fun parsePrefix(): ExprNode {
        val token = peek()
        return when (token.type) {
            TokenType.INTEGER_LITERAL,
            TokenType.CHAR_LITERAL,
            TokenType.STRING_LITERAL,
            TokenType.TRUE,
            TokenType.FALSE,
            TokenType.C_STRING_LITERAL,
            TokenType.RAW_STRING_LITERAL,
            TokenType.RAW_C_STRING_LITERAL -> parseLiteralExpr(consume())

            TokenType.IDENTIFIER,
            TokenType.SELF,
            TokenType.SELF_CAP -> {
                val path = parsePathExpr()
                if (peek().type == TokenType.LeftBrace) parseStructExpr(path)
                else path
            }

            TokenType.LeftBrace -> parseBlockExpr(consume())
            TokenType.SubNegate -> parsePrefixNegate(consume())
            TokenType.Not -> parsePrefixNot(consume())
            TokenType.BitAnd -> parsePrefixBorrow(consume())
            TokenType.And -> parsePrefixAnd(consume())
            TokenType.Mul -> parsePrefixDeref(consume())
            TokenType.LeftParen -> parseGroupedExpr(consume())
            TokenType.LeftBracket -> parseArrayExpr(consume())
            TokenType.IF -> parseIfExpr(consume())
            TokenType.LOOP -> parseInfiniteLoopExpr(consume())
            TokenType.WHILE -> parsePredicateLoopExpr(consume())
            TokenType.BREAK -> parseBreakExpr(consume())
            TokenType.CONTINUE -> parseContinueExpr(consume())
            TokenType.RETURN -> parseReturnExpr(consume())

            else -> throw SyntaxException("unexpected prefix token")
        }
    }

    fun parseInfix(left: ExprNode): ExprNode {
        val token = peek()
        return when (token.type) {
            TokenType.Add -> parseInfixAdd(left, consume())
            TokenType.SubNegate -> parseInfixSub(left, consume())
            TokenType.Mul -> parseInfixMul(left, consume())
            TokenType.Div -> parseInfixDiv(left, consume())
            TokenType.Mod -> parseInfixMod(left, consume())
            TokenType.Shl -> parseInfixShl(left, consume())
            TokenType.Shr -> parseInfixShr(left, consume())
            TokenType.BitAnd -> parseInfixBitAnd(left, consume())
            TokenType.BitOr -> parseInfixBitOr(left, consume())
            TokenType.BitXor -> parseInfixBitXor(left, consume())
            TokenType.Lt -> parseInfixLt(left, consume())
            TokenType.Gt -> parseInfixGt(left, consume())
            TokenType.Le -> parseInfixLe(left, consume())
            TokenType.Ge -> parseInfixGe(left, consume())
            TokenType.Eq -> parseInfixEq(left, consume())
            TokenType.Neq -> parseInfixNeq(left, consume())
            TokenType.And -> parseInfixAnd(left, consume())
            TokenType.Or -> parseInfixOr(left, consume())
            TokenType.AS -> parseInfixAs(left, consume())
            TokenType.Assign -> parseInfixAssign(left, consume())
            TokenType.AddAssign -> parseInfixAddAssign(left, consume())
            TokenType.SubAssign -> parseInfixSubAssign(left, consume())
            TokenType.MulAssign -> parseInfixMulAssign(left, consume())
            TokenType.DivAssign -> parseInfixDivAssign(left, consume())
            TokenType.ModAssign -> parseInfixModAssign(left, consume())
            TokenType.ShlAssign -> parseInfixShlAssign(left, consume())
            TokenType.ShrAssign -> parseInfixShrAssign(left, consume())
            TokenType.AndAssign -> parseInfixAndAssign(left, consume())
            TokenType.OrAssign -> parseInfixOrAssign(left, consume())
            TokenType.XorAssign -> parseInfixXorAssign(left, consume())

            TokenType.LeftBracket -> parseIndexExpr(left, consume())
            TokenType.LeftParen -> parseCallExpr(left, consume())

            TokenType.Dot -> {
                consume()
                val next = peek() //token
                val method = parsePathSegment() //segment
                if (peek().type == TokenType.LeftParen) {
                    parseMethodCallExpr(left, method)
                } else {
                    parseFieldExpr(left, next)
                }
            }

            else -> throw SyntaxException("unexpected infix token")
        }
    }

    fun parsePathExpr(): PathExprNode {
        val first = parsePathSegment()
        val second = if (match(TokenType.DoubleColon)) {
            parsePathSegment()
        } else {
            null
        }
        return PathExprNode(first, second)
    }


    fun parsePathSegment(): PathSegment {
        if (peek().type != TokenType.IDENTIFIER &&
            peek().type != TokenType.SELF &&
            peek().type != TokenType.SELF_CAP
        ) throw SyntaxException("unexpected token in PathExprSegment")
        val identSegment = consume()
        return PathSegment(identSegment)
    }

    fun parseTypePath(): TypePathNode {
        val path = parsePathSegment()
        return TypePathNode(path)
    }

    fun parseType(): TypeNode {
        if (peek().type == TokenType.IDENTIFIER ||
            peek().type == TokenType.SELF ||
            peek().type == TokenType.SELF_CAP
        ) return parseTypePath()

        val cur = consume()
        return when (cur.type) {
            TokenType.BitAnd -> parseReferenceType(cur)
            TokenType.LeftBracket -> parseArrayType(cur)
            TokenType.LeftParen -> parseUnitType(cur)
            else -> throw SyntaxException("unexpected token in TypeNoBounds")
        }
    }

    fun parseReferenceType(cur: Token): ReferenceTypeNode {
        if (cur.type != TokenType.BitAnd) throw SyntaxException("expected &")
        val isMut = match(TokenType.MUT)
        val tar = parseType()
        return ReferenceTypeNode(isMut, tar)
    }

    fun parseArrayType(cur: Token): TypeNode {
        if (cur.type != TokenType.LeftBracket)
            throw SyntaxException("expected [")
        val elementType = parseType()
        if (!match(TokenType.Semicolon))
            throw SyntaxException("expected ;")
        val length = parseExpr(0)
        if (!match(TokenType.RightBracket))
            throw SyntaxException("expected ]")
        return ArrayTypeNode(elementType, length)
    }

    fun parseUnitType(cur: Token): TypeNode {
        if (cur.type != TokenType.LeftParen)
            throw SyntaxException("expected (")
        if (!match(TokenType.RightParen))
            throw SyntaxException("expected )")
        return UnitTypeNode()
    }

    fun parseLiteralExpr(cur: Token): LiteralExprNode {
        when (cur.type) {
            TokenType.INTEGER_LITERAL ->
                return IntLiteralExprNode(cur.value)

            TokenType.TRUE ->
                return BooleanLiteralExprNode(cur.value)

            TokenType.FALSE ->
                return BooleanLiteralExprNode(cur.value)

            TokenType.CHAR_LITERAL ->
                return CharLiteralExprNode(cur.value)

            TokenType.STRING_LITERAL ->
                return StringLiteralExprNode(cur.value)

            TokenType.C_STRING_LITERAL ->
                return CStringLiteralExprNode(cur.value)

            TokenType.RAW_STRING_LITERAL ->
                return RawStringLiteralExprNode(cur.value)

            TokenType.RAW_C_STRING_LITERAL ->
                return RawCStringLiteralExprNode(cur.value)

            else -> throw SyntaxException("unexpected token")
        }
    }

    fun parseBlockExpr(cur: Token): BlockExprNode {
        if (cur.type != TokenType.LeftBrace)
            throw SyntaxException("expected {")
        val items = mutableListOf<ItemNode>()
        val statements = mutableListOf<StmtNode>()
        var tailExpr: ExprNode? = null
        while (!match(TokenType.RightBrace)) {
            if (isItem()) {
                val item = parseItem()
                items.add(item)
            } else if (isStmt()) {
                val stmt = parseStmt()
                statements.add(stmt)
            } else {
                val expr = parseExpr(0)
                if (match(TokenType.Semicolon)) {
                    statements.add(ExprStmtNode(expr))
                } else if (peek().type != TokenType.LeftBrace) {
                    if (expr is ExprWithoutBlockNode)
                        throw SyntaxException("ExprStmt must have block")
                    statements.add(ExprStmtNode(expr))
                } else {
                    tailExpr = expr
                }
            }
        }
        return BlockExprNode(items, statements, tailExpr)
    }

    fun parsePrefixNegate(cur: Token): NegationExprNode {
        // - as prefix
        if (cur.type != TokenType.SubNegate)
            throw SyntaxException("expected -")
        val right = parseExpr(12)
        return NegationExprNode(cur, right)
    }

    fun parsePrefixNot(cur: Token): NegationExprNode {
        // ! as prefix
        if (cur.type != TokenType.Not)
            throw SyntaxException("expected !")
        val right = parseExpr(12)
        return NegationExprNode(cur, right)
    }

    fun parseInfixAdd(left: ExprNode, cur: Token): BinaryExprNode {
        // + as infix
        if (cur.type != TokenType.Add)
            throw SyntaxException("expected +")
        val right = parseExpr(9)
        return BinaryExprNode(left, cur, right)
    }

    fun parseInfixSub(left: ExprNode, cur: Token): BinaryExprNode {
        // - as infix
        if (cur.type != TokenType.SubNegate)
            throw SyntaxException("expected -")
        val right = parseExpr(9)
        return BinaryExprNode(left, cur, right)
    }

    fun parseInfixMul(left: ExprNode, cur: Token): BinaryExprNode {
        // * as infix
        if (cur.type != TokenType.Mul)
            throw SyntaxException("expected *")
        val right = parseExpr(10)
        return BinaryExprNode(left, cur, right)
    }

    fun parseInfixDiv(left: ExprNode, cur: Token): BinaryExprNode {
        // / as infix
        if (cur.type != TokenType.Div)
            throw SyntaxException("expected /")
        val right = parseExpr(10)
        return BinaryExprNode(left, cur, right)
    }

    fun parseInfixMod(left: ExprNode, cur: Token): BinaryExprNode {
        // % as infix
        if (cur.type != TokenType.Mod)
            throw SyntaxException("expected %")
        val right = parseExpr(10)
        return BinaryExprNode(left, cur, right)
    }

    fun parseInfixShl(left: ExprNode, cur: Token): BinaryExprNode {
        // << as infix
        if (cur.type != TokenType.Shl)
            throw SyntaxException("expected <<")
        val right = parseExpr(8)
        return BinaryExprNode(left, cur, right)
    }

    fun parseInfixShr(left: ExprNode, cur: Token): BinaryExprNode {
        // >> as infix
        if (cur.type != TokenType.Shr)
            throw SyntaxException("expected >>")
        val right = parseExpr(8)
        return BinaryExprNode(left, cur, right)
    }

    fun parseInfixBitAnd(left: ExprNode, cur: Token): BinaryExprNode {
        // & as infix
        if (cur.type != TokenType.BitAnd)
            throw SyntaxException("expected &")
        val right = parseExpr(7)
        return BinaryExprNode(left, cur, right)
    }

    fun parseInfixBitOr(left: ExprNode, cur: Token): BinaryExprNode {
        // | as infix
        if (cur.type != TokenType.BitOr)
            throw SyntaxException("expected |")
        val right = parseExpr(5)
        return BinaryExprNode(left, cur, right)
    }

    fun parseInfixBitXor(left: ExprNode, cur: Token): BinaryExprNode {
        // ^ as infix
        if (cur.type != TokenType.BitXor)
            throw SyntaxException("expected ^")
        val right = parseExpr(6)
        return BinaryExprNode(left, cur, right)
    }

    fun parseInfixLt(left: ExprNode, cur: Token): ComparisonExprNode {
        // < as infix
        if (cur.type != TokenType.Lt)
            throw SyntaxException("expected <")
        val right = parseExpr(4)
        return ComparisonExprNode(left, cur, right)
    }

    fun parseInfixGt(left: ExprNode, cur: Token): ComparisonExprNode {
        // > as infix
        if (cur.type != TokenType.Gt) throw SyntaxException("expected >")
        val right = parseExpr(4)
        return ComparisonExprNode(left, cur, right)
    }

    fun parseInfixLe(left: ExprNode, cur: Token): ComparisonExprNode {
        // <= as infix
        if (cur.type != TokenType.Le)
            throw SyntaxException("expected <=")
        val right = parseExpr(4)
        return ComparisonExprNode(left, cur, right)
    }

    fun parseInfixGe(left: ExprNode, cur: Token): ComparisonExprNode {
        // >= as infix
        if (cur.type != TokenType.Ge)
            throw SyntaxException("expected >=")
        val right = parseExpr(4)
        return ComparisonExprNode(left, cur, right)
    }

    fun parseInfixEq(left: ExprNode, cur: Token): ComparisonExprNode {
        // == as infix
        if (cur.type != TokenType.Eq)
            throw SyntaxException("expected ==")
        val right = parseExpr(4)
        return ComparisonExprNode(left, cur, right)
    }

    fun parseInfixNeq(left: ExprNode, cur: Token): ComparisonExprNode {
        // != as infix
        if (cur.type != TokenType.Neq)
            throw SyntaxException("expected !=")
        val right = parseExpr(4)
        return ComparisonExprNode(left, cur, right)
    }

    fun parseInfixAnd(left: ExprNode, cur: Token): LazyBooleanExprNode {
        // && as infix
        if (cur.type != TokenType.And)
            throw SyntaxException("expected &&")
        val right = parseExpr(3)
        return LazyBooleanExprNode(left, cur, right)
    }

    fun parseInfixOr(left: ExprNode, cur: Token): LazyBooleanExprNode {
        // || as infix
        if (cur.type != TokenType.Or)
            throw SyntaxException("expected ||")
        val right = parseExpr(2)
        return LazyBooleanExprNode(left, cur, right)
    }

    fun parseInfixAs(left: ExprNode, cur: Token): TypeCastExprNode {
        // TypeCast
        if (cur.type != TokenType.AS)
            throw SyntaxException("expected as")
        val targetType = parseType()
        return TypeCastExprNode(left, targetType)
    }

    fun parsePrefixBorrow(cur: Token): BorrowExprNode {
        // & as prefix
        if (cur.type != TokenType.BitAnd)
            throw SyntaxException("expected &")
        val isMut = match(TokenType.MUT)
        val expr = parseExpr(12)
        return BorrowExprNode(isMut, expr)
    }

    fun parsePrefixAnd(cur: Token): BorrowExprNode {
        // && as prefix
        if (cur.type != TokenType.And)
            throw SyntaxException("expected &&")
        val isMut = match(TokenType.MUT)
        val expr = parseExpr(12)
        return BorrowExprNode(isMut, BorrowExprNode(isMut, expr))
    }

    fun parsePrefixDeref(cur: Token): DerefExprNode {
        // * as prefix
        if (cur.type != TokenType.Mul)
            throw SyntaxException("expected *")
        val expr = parseExpr(12)
        return DerefExprNode(expr)
    }

    fun parseInfixAssign(left: ExprNode, cur: Token): AssignExprNode {
        // = as prefix
        if (cur.type != TokenType.Assign)
            throw SyntaxException("expected =")
        val right = parseExpr(1)
        return AssignExprNode(left, right)
    }

    fun parseInfixAddAssign(left: ExprNode, cur: Token): CompoundAssignExprNode {
        // += as infix
        if (cur.type != TokenType.AddAssign)
            throw SyntaxException("expected +=")
        val right = parseExpr(1)
        return CompoundAssignExprNode(left, cur, right)
    }

    fun parseInfixSubAssign(left: ExprNode, cur: Token): CompoundAssignExprNode {
        // -= as infix
        if (cur.type != TokenType.SubAssign)
            throw SyntaxException("expected -=")
        val right = parseExpr(1)
        return CompoundAssignExprNode(left, cur, right)
    }

    fun parseInfixMulAssign(left: ExprNode, cur: Token): CompoundAssignExprNode {
        // *= as infix
        if (cur.type != TokenType.MulAssign)
            throw SyntaxException("expected *=")
        val right = parseExpr(1)
        return CompoundAssignExprNode(left, cur, right)
    }

    fun parseInfixDivAssign(left: ExprNode, cur: Token): CompoundAssignExprNode {
        // /= as infix
        if (cur.type != TokenType.DivAssign)
            throw SyntaxException("expected /=")
        val right = parseExpr(1)
        return CompoundAssignExprNode(left, cur, right)
    }

    fun parseInfixModAssign(left: ExprNode, cur: Token): CompoundAssignExprNode {
        // %= as infix
        if (cur.type != TokenType.ModAssign)
            throw SyntaxException("expected %=")
        val right = parseExpr(1)
        return CompoundAssignExprNode(left, cur, right)
    }

    fun parseInfixShlAssign(left: ExprNode, cur: Token): CompoundAssignExprNode {
        // <<= as infix
        if (cur.type != TokenType.ShlAssign)
            throw SyntaxException("expected <<=")
        val right = parseExpr(1)
        return CompoundAssignExprNode(left, cur, right)
    }

    fun parseInfixShrAssign(left: ExprNode, cur: Token): CompoundAssignExprNode {
        // >>= as infix
        if (cur.type != TokenType.ShrAssign)
            throw SyntaxException("expected >>=")
        val right = parseExpr(1)
        return CompoundAssignExprNode(left, cur, right)
    }

    fun parseInfixAndAssign(left: ExprNode, cur: Token): CompoundAssignExprNode {
        // &= as infix
        if (cur.type != TokenType.AndAssign)
            throw SyntaxException("expected &=")
        val right = parseExpr(1)
        return CompoundAssignExprNode(left, cur, right)
    }

    fun parseInfixOrAssign(left: ExprNode, cur: Token): CompoundAssignExprNode {
        // |= as infix
        if (cur.type != TokenType.OrAssign)
            throw SyntaxException("expected |=")
        val right = parseExpr(1)
        return CompoundAssignExprNode(left, cur, right)
    }

    fun parseInfixXorAssign(left: ExprNode, cur: Token): CompoundAssignExprNode {
        // ^= as infix
        if (cur.type != TokenType.XorAssign)
            throw SyntaxException("expected ^=")
        val right = parseExpr(1)
        return CompoundAssignExprNode(left, cur, right)
    }

    fun parseGroupedExpr(cur: Token): GroupedExprNode {
        if (cur.type != TokenType.LeftParen)
            throw SyntaxException("expected (")
        val expr = parseExpr(0)
        if (!match(TokenType.RightParen))
            throw SyntaxException("expected )")
        return GroupedExprNode(expr)
    }

    fun parseArrayExpr(cur: Token): ArrayExprNode {
        if (cur.type != TokenType.LeftBracket)
            throw SyntaxException("expected [")
        val element = mutableListOf<ExprNode>()
        if (match(TokenType.RightBracket)) {
            // []
            return ArrayListExprNode(element)
        }
        val first = parseExpr(0)
        element.add(first)
        if (match(TokenType.RightBracket)) {
            // [expr]
            return ArrayListExprNode(element)
        }
        if (match(TokenType.Comma)) {
            if (match(TokenType.RightBracket))
                return ArrayListExprNode(element)
            else element.add(parseExpr(0))
            while (match(TokenType.Comma)) {
                if (peek().type == TokenType.RightBracket) {
                    break
                } else element.add(parseExpr(0))
            }
            if (!match(TokenType.RightBracket))
                throw SyntaxException("expected ]")
            return ArrayListExprNode(element)
        }
        if (match(TokenType.Semicolon)) {
            val length = parseExpr(0)
            if (!match(TokenType.RightBracket))
                throw SyntaxException("expected ]")
            return ArrayLengthExprNode(first, length)
        }
        throw SyntaxException("unexpected token in ArrayExpr")
    }

    fun parseIndexExpr(base: ExprNode, cur: Token): IndexExprNode {
        if (cur.type != TokenType.LeftBracket)
            throw SyntaxException("expected [")
        val index = parseExpr(0)
        if (!match(TokenType.RightBracket))
            throw SyntaxException("expected ]")
        return IndexExprNode(base, index)
    }

    fun parseStructExpr(path: PathExprNode): StructExprNode {
        if (!match(TokenType.LeftBrace))
            throw SyntaxException("expected {")
        val fields = mutableListOf<StructExprField>()
        if (match(TokenType.RightBrace))
            return StructExprNode(path, emptyList())
        else fields.add(parseStructExprField())
        while (match(TokenType.Comma)) {
            if (peek().type == TokenType.RightBrace) {
                break
            } else fields.add(parseStructExprField())
        }
        if (!match(TokenType.RightBrace))
            throw SyntaxException("expected }")
        return StructExprNode(path, fields)
    }

    fun parseStructExprField(): StructExprField {
        if (peek().type != TokenType.IDENTIFIER)
            throw SyntaxException("expected identifier")
        val name = consume()
        if (!match(TokenType.Colon))
            throw SyntaxException("expected colon")
        val expr = parseExpr(0)
        return StructExprField(name, expr)
    }

    fun parseCallExpr(expr: ExprNode, cur: Token): CallExprNode {
        if (cur.type != TokenType.LeftParen)
            throw SyntaxException("expected (")
        val params = mutableListOf<ExprNode>()
        if (match(TokenType.RightParen))
            return CallExprNode(expr, emptyList())
        else params.add(parseExpr(0))
        while (match(TokenType.Comma)) {
            if (peek().type == TokenType.RightParen) {
                break
            } else params.add(parseExpr(0))
        }
        if (!match(TokenType.RightParen))
            throw SyntaxException("expected )")
        return CallExprNode(expr, params)
    }

    fun parseMethodCallExpr(
        expr: ExprNode,
        method: PathSegment
    ): MethodCallExprNode {
        if (!match(TokenType.LeftParen))
            throw SyntaxException("expected (")
        val params = mutableListOf<ExprNode>()
        if (match(TokenType.RightParen)) {
            return MethodCallExprNode(expr, method, emptyList())
        } else {
            params.add(parseExpr(0))
            while (match(TokenType.Comma)) {
                if (peek().type == TokenType.RightParen) {
                    break
                } else params.add(parseExpr(0))
            }
            if (!match(TokenType.RightParen))
                throw SyntaxException("expected )")
            return MethodCallExprNode(expr, method, params)
        }
    }

    fun parseFieldExpr(expr: ExprNode, id: Token): FieldExprNode {
        if (id.type != TokenType.IDENTIFIER)
            throw SyntaxException("expected id")
        return FieldExprNode(expr, id)
    }

    fun parsePattern(): PatternNode {
        return when (peek().type) {
            TokenType.And -> parseReferencePattern()
            TokenType.BitAnd -> parseReferencePattern()
            TokenType.MUT -> parseIdentifierPattern()
            TokenType.IDENTIFIER -> parseIdentifierPattern()

            else -> throw SyntaxException("unexpected token in pattern")
        }
    }

    fun parseIdentifierPattern(): IdentifierPatternNode {
        val isMut = match(TokenType.MUT)
        if (peek().type != TokenType.IDENTIFIER)
            throw SyntaxException("expected identifier")
        val name = consume()
        return IdentifierPatternNode(name, isMut)
    }

    fun parseReferencePattern(): ReferencePatternNode {
        val ref = consume()
        if (ref.type != TokenType.And && ref.type != TokenType.BitAnd)
            throw SyntaxException("expected & or &&")
        val isMut = match(TokenType.MUT)
        val pattern = parsePattern()
        return if (ref.type == TokenType.BitAnd) {
            ReferencePatternNode(isMut, pattern)
        } else {
            ReferencePatternNode(false, ReferencePatternNode(isMut, pattern))
        }
    }

    fun parseCondition(): Condition {
        if (!match(TokenType.LeftParen))
            throw SyntaxException("expected (")
        val expr = parseExpr(0)
        if (expr is StructExprNode) {
            throw SyntaxException("Excluded expression type in condition")
        }
        if (!match(TokenType.RightParen))
            throw SyntaxException("expected )")
        return Condition(expr)
    }

    fun parseIfExpr(cur: Token): IfExprNode {
        if (cur.type != TokenType.IF)
            throw SyntaxException("expected if")
        val condition = parseCondition()
        val thenBranch = parseBlockExpr(consume())
        var elseBranch: ExprNode? = null
        if (match(TokenType.ELSE)) {
            val token = consume()
            elseBranch = when (token.type) {
                TokenType.IF -> {
                    parseIfExpr(token)
                }

                TokenType.LeftBrace -> {
                    parseBlockExpr(token)
                }

                else -> throw SyntaxException("expected if or {")
            }
        }
        return IfExprNode(condition, thenBranch, elseBranch)
    }

    fun parseInfiniteLoopExpr(cur: Token): InfiniteLoopExprNode {
        if (cur.type != TokenType.LOOP)
            throw SyntaxException("expected loop")
        val block = parseBlockExpr(consume())
        return InfiniteLoopExprNode(block)
    }

    fun parsePredicateLoopExpr(cur: Token): PredicateLoopExprNode {
        if (cur.type != TokenType.WHILE)
            throw SyntaxException("expected while")
        val condition = parseCondition()
        val block = parseBlockExpr(consume())
        return PredicateLoopExprNode(condition, block)
    }

    fun parseBreakExpr(cur: Token): BreakExprNode {
        if (cur.type != TokenType.BREAK)
            throw SyntaxException("expected break")
        if (isExpr()) {
            val value = parseExpr(0)
            return BreakExprNode(value)
        } else return BreakExprNode(null)
    }

    fun parseContinueExpr(cur: Token): ContinueExprNode {
        if (cur.type != TokenType.CONTINUE)
            throw SyntaxException("expected continue")
        return ContinueExprNode()
    }

    fun parseReturnExpr(cur: Token): ReturnExprNode {
        if (cur.type != TokenType.RETURN)
            throw SyntaxException("expected return")
        if (isExpr()) {
            val value = parseExpr(0)
            return ReturnExprNode(value)
        } else return ReturnExprNode(null)
    }


    fun parseStmt(): StmtNode {
        return when (peek().type) {
            TokenType.Semicolon -> parseEmptyStmt(consume())
            TokenType.LET -> parseLetStmt(consume())
            else -> throw SyntaxException("unexpected token in Stmt")
        }
    }

    fun parseEmptyStmt(cur: Token): EmptyStmtNode {
        if (cur.type != TokenType.Semicolon)
            throw SyntaxException("expected ;")
        return EmptyStmtNode(cur)
    }

    fun parseLetStmt(cur: Token): LetStmtNode {
        if (cur.type != TokenType.LET)
            throw SyntaxException("expected let")
        val pattern = parsePattern()
        if (!match(TokenType.Colon))
            throw SyntaxException("expected :")
        val valueType = parseType()
        if (!match(TokenType.Assign))
            throw SyntaxException("expected =")
        val value = parseExpr(0)
        if (!match(TokenType.Semicolon))
            throw SyntaxException("expected ;")
        return LetStmtNode(pattern, valueType, value)
    }


    fun parseItem(): ItemNode {
        return when (peek().type) {
            TokenType.FN -> parseFunctionItem()
            TokenType.STRUCT -> parseStructItem()
            TokenType.ENUM -> parseEnumItem()
            TokenType.CONST -> parseConstantItem()
            TokenType.TRAIT -> parseTraitItem()
            TokenType.IMPL -> parseImplItem()
            else -> throw SyntaxException("unexpected token in item")
        }
    }

    fun parseFunctionItem(): FunctionItemNode {
        if (!match(TokenType.FN))
            throw SyntaxException("expected fn")
        if (peek().type != TokenType.IDENTIFIER)
            throw SyntaxException("expected id")
        val fnName = consume()
        if (!match(TokenType.LeftParen))
            throw SyntaxException("expected (")
        val (selfParam, params) = parseFunctionParameters()
        if (!match(TokenType.RightParen))
            throw SyntaxException("expected )")
        val returnType = if (match(TokenType.Arrow)) {
            parseType()
        } else null

        if (peek().type == TokenType.LeftBrace) {
            val body = parseBlockExpr(consume())
            return FunctionItemNode(
                fnName, selfParam,
                params, returnType, body
            )
        } else {
            if (!match(TokenType.Semicolon))
                throw SyntaxException("expected ;")
            return FunctionItemNode(
                fnName, selfParam,
                params, returnType, body = null
            )
        }
    }

    fun parseFunctionParameters(): Pair<SelfParam?, List<FunctionParam>> {
        val params = mutableListOf<FunctionParam>()
        if (peek().type == TokenType.RightParen) return null to emptyList()
        if (peek().type == TokenType.SELF ||
            (peek().type == TokenType.BitAnd &&
                    ahead(1).type == TokenType.SELF) ||
            (peek().type == TokenType.MUT &&
                    ahead(1).type == TokenType.SELF) ||
            (peek().type == TokenType.BitAnd &&
                    ahead(1).type == TokenType.MUT &&
                    ahead(2).type == TokenType.SELF)
        ) {
            val selfParam = parseSelfParam()
            if (match(TokenType.Comma)) {
                if (peek().type == TokenType.RightParen) {
                    return selfParam to emptyList()
                } else {
                    params.add(parseFunctionParam())
                    while (match(TokenType.Comma)) {
                        if (peek().type == TokenType.RightParen) break
                        else params.add(parseFunctionParam())
                    }
                    return selfParam to params
                }
            } else {
                if (peek().type == TokenType.RightParen) {
                    return selfParam to emptyList()
                } else throw SyntaxException("expected , or )")
            }
        } else {
            params.add(parseFunctionParam())
            while (match(TokenType.Comma)) {
                if (peek().type == TokenType.RightParen) break
                params.add(parseFunctionParam())
            }
            return null to params
        }
    }

    fun parseSelfParam(): SelfParam {
        val isRef = match(TokenType.BitAnd)
        val isMut = match(TokenType.MUT)
        if (!match(TokenType.SELF))
            throw SyntaxException("expected self")
        return SelfParam(isMut, isRef)
    }

    fun parseFunctionParam(): FunctionParam {
        val paramPattern = parsePattern()
        if (!match(TokenType.Colon))
            throw SyntaxException("expected :")
        val type = parseType()
        return FunctionParam(paramPattern, type)
    }

    fun parseStructItem(): StructItemNode {
        if (!match(TokenType.STRUCT))
            throw SyntaxException("expected struct")
        if (peek().type != TokenType.IDENTIFIER)
            throw SyntaxException("expected id")
        val structName = consume()
        if (match(TokenType.LeftBrace)) {
            val fields = parseStructFields()
            if (!match(TokenType.RightBrace))
                throw SyntaxException("expected }")
            return StructItemNode(structName, fields)
        } else {
            if (!match(TokenType.Semicolon))
                throw SyntaxException("expected ;")
            return StructItemNode(structName, null)
        }
    }

    fun parseStructFields(): List<StructField> {
        if (peek().type == TokenType.RightBrace) return emptyList()
        val fields = mutableListOf<StructField>()
        fields.add(parseStructField())
        while (match(TokenType.Comma)) {
            if (peek().type == TokenType.RightBrace) break
            else fields.add(parseStructField())
        }
        if (peek().type != TokenType.RightBrace)
            throw SyntaxException("expected {")
        return fields
    }

    fun parseStructField(): StructField {
        if (peek().type != TokenType.IDENTIFIER)
            throw SyntaxException("expected id")
        val name = consume()
        if (!match(TokenType.Colon))
            throw SyntaxException("expected :")
        val type = parseType()
        return StructField(name, type)
    }

    fun parseEnumItem(): EnumItemNode {
        if (!match(TokenType.ENUM))
            throw SyntaxException("expected enum")
        if (peek().type != TokenType.IDENTIFIER)
            throw SyntaxException("expected id")
        val name = consume()
        if (!match(TokenType.LeftBrace))
            throw SyntaxException("expected {")
        val variants = parseVariants()
        if (!match(TokenType.RightBrace))
            throw SyntaxException("expected }")
        return EnumItemNode(name, variants)
    }

    fun parseVariants(): List<Token> {
        val variants = mutableListOf<Token>()
        if (peek().type != TokenType.IDENTIFIER)
            throw SyntaxException("expected id")
        variants.add(consume())
        while (match(TokenType.Comma)) {
            if (peek().type == TokenType.RightBrace) break
            else {
                if (peek().type != TokenType.IDENTIFIER)
                    throw SyntaxException("expected id")
                else variants.add(consume())
            }
        }
        return variants
    }

    fun parseConstantItem(): ConstantItemNode {
        if (!match(TokenType.CONST))
            throw SyntaxException("expected const")
        if (peek().type != TokenType.IDENTIFIER)
            throw SyntaxException("expected id")
        val constantName = consume()
        if (!match(TokenType.Colon))
            throw SyntaxException("expected :")
        val constantType = parseType()
        val value = if (match(TokenType.Eq)) {
            parseExpr(0)
        } else {
            null
        }
        if (!match(TokenType.Semicolon))
            throw SyntaxException("expected ;")
        return ConstantItemNode(constantName, constantType, value)
    }

    fun parseTraitItem(): TraitItemNode {
        if (!match(TokenType.TRAIT))
            throw SyntaxException("expected trait")
        if (peek().type != TokenType.IDENTIFIER)
            throw SyntaxException("expected id")
        val traitName = consume()
        if (!match(TokenType.LeftBrace))
            throw SyntaxException("expected {")
        val items = mutableListOf<ItemNode>()
        while (peek().type != TokenType.RightBrace) {
            items.add(parseAssociatedItem())
        }
        if (!match(TokenType.RightBrace))
            throw SyntaxException("expected }")
        return TraitItemNode(traitName, items)
    }

    fun parseAssociatedItem(): ItemNode {
        return when (peek().type) {
            TokenType.FN -> parseFunctionItem()
            TokenType.CONST -> {
                if (ahead(1).type == TokenType.FN) parseFunctionItem()
                else parseConstantItem()
            }

            else -> throw SyntaxException("expected associated const or fn")
        }
    }

    fun parseImplItem(): ImplItemNode {
        if (!match(TokenType.IMPL))
            throw SyntaxException("expected impl")
        val implName = if (peek().type == TokenType.IDENTIFIER &&
            ahead(1).type == TokenType.FOR
        ) {
            val implName = consume()
            if (!match(TokenType.FOR))
                throw SyntaxException("expected for")
            implName
        } else null
        val implType = parseType()
        val items = mutableListOf<ItemNode>()
        if (!match(TokenType.LeftBrace))
            throw SyntaxException("expected {")
        while (peek().type != TokenType.RightBrace) {
            items.add(parseAssociatedItem())
        }
        if (!match(TokenType.RightBrace))
            throw SyntaxException("expected }")
        return ImplItemNode(implName, implType, items)
    }
}