package sigil.parser

enum class TokenType {
    // Keywords
    FN, TYPE, TRAIT, EFFECT, MODULE, EXPORT, HANDLER,
    LET, MATCH, IF, THEN, ELSE, REQUIRES, ENSURES, PROPERTY,
    // Literals
    INT_LIT, FLOAT_LIT, STRING_LIT, TRUE, FALSE,
    // Identifiers and operators
    IDENT, HASH_REF,
    ARROW,       // ->
    FAT_ARROW,   // =>
    BANG,        // !
    PIPE,        // |
    COLON,       // :
    COMMA,       // ,
    DOT,         // .
    UNDERSCORE,  // _
    // Delimiters
    LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET,
    LANGLE, RANGLE,
    // Operators
    PLUS, MINUS, STAR, SLASH, PERCENT,
    EQ, NEQ, LT, GT, LTE, GTE,
    AND, OR, NOT,
    PLUS_PLUS,   // ++
    EQUALS,      // =
    // Special
    NEWLINE,
    EOF
}

data class Token(val type: TokenType, val value: String, val line: Int, val col: Int)

class SigilLexer(private val source: String) {
    private var pos = 0
    private var line = 1
    private var col = 1
    private val tokens = mutableListOf<Token>()

    private val keywords = mapOf(
        "fn" to TokenType.FN,
        "type" to TokenType.TYPE,
        "trait" to TokenType.TRAIT,
        "effect" to TokenType.EFFECT,
        "module" to TokenType.MODULE,
        "export" to TokenType.EXPORT,
        "handler" to TokenType.HANDLER,
        "let" to TokenType.LET,
        "match" to TokenType.MATCH,
        "if" to TokenType.IF,
        "then" to TokenType.THEN,
        "else" to TokenType.ELSE,
        "requires" to TokenType.REQUIRES,
        "ensures" to TokenType.ENSURES,
        "property" to TokenType.PROPERTY,
        "true" to TokenType.TRUE,
        "false" to TokenType.FALSE,
    )

    fun tokenize(): List<Token> {
        while (pos < source.length) {
            skipWhitespaceAndComments()
            if (pos >= source.length) break

            val startLine = line
            val startCol = col
            val ch = source[pos]

            when {
                ch == '\n' -> {
                    tokens.add(Token(TokenType.NEWLINE, "\\n", startLine, startCol))
                    advance()
                }
                ch == '#' -> readHashRef(startLine, startCol)
                ch == '"' -> readString(startLine, startCol)
                ch.isDigit() -> readNumber(startLine, startCol)
                ch.isLetter() || ch == '_' -> readIdentOrKeyword(startLine, startCol)
                else -> readOperatorOrDelimiter(startLine, startCol)
            }
        }
        tokens.add(Token(TokenType.EOF, "", line, col))
        return tokens
    }

    private fun peek(): Char? = if (pos < source.length) source[pos] else null
    private fun peekAt(offset: Int): Char? = if (pos + offset < source.length) source[pos + offset] else null

    private fun advance(): Char {
        val ch = source[pos]
        pos++
        if (ch == '\n') { line++; col = 1 } else col++
        return ch
    }

    private fun skipWhitespaceAndComments() {
        while (pos < source.length) {
            val ch = source[pos]
            if (ch == ' ' || ch == '\t' || ch == '\r') {
                advance()
            } else if (ch == '/' && peekAt(1) == '/') {
                // line comment
                while (pos < source.length && source[pos] != '\n') advance()
            } else {
                break
            }
        }
    }

    private fun readHashRef(startLine: Int, startCol: Int) {
        val sb = StringBuilder()
        sb.append(advance()) // #
        while (pos < source.length && (source[pos].isLetterOrDigit() || source[pos] == ':' || source[pos] == '_' || source[pos] == '-')) {
            sb.append(advance())
        }
        tokens.add(Token(TokenType.HASH_REF, sb.toString(), startLine, startCol))
    }

    private fun readString(startLine: Int, startCol: Int) {
        advance() // opening "
        val sb = StringBuilder()
        while (pos < source.length && source[pos] != '"') {
            if (source[pos] == '\\') {
                advance()
                if (pos < source.length) {
                    when (source[pos]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        '\\' -> sb.append('\\')
                        '"' -> sb.append('"')
                        else -> { sb.append('\\'); sb.append(source[pos]) }
                    }
                    advance()
                }
            } else {
                sb.append(advance())
            }
        }
        if (pos < source.length) advance() // closing "
        tokens.add(Token(TokenType.STRING_LIT, sb.toString(), startLine, startCol))
    }

    private fun readNumber(startLine: Int, startCol: Int) {
        val sb = StringBuilder()
        while (pos < source.length && source[pos].isDigit()) {
            sb.append(advance())
        }
        if (pos < source.length && source[pos] == '.' && peekAt(1)?.isDigit() == true) {
            sb.append(advance()) // .
            while (pos < source.length && source[pos].isDigit()) {
                sb.append(advance())
            }
            tokens.add(Token(TokenType.FLOAT_LIT, sb.toString(), startLine, startCol))
        } else {
            tokens.add(Token(TokenType.INT_LIT, sb.toString(), startLine, startCol))
        }
    }

    private fun readIdentOrKeyword(startLine: Int, startCol: Int) {
        val sb = StringBuilder()
        // Handle standalone underscore vs identifiers starting with _
        if (source[pos] == '_' && (pos + 1 >= source.length || !(source[pos + 1].isLetterOrDigit() || source[pos + 1] == '_'))) {
            advance()
            tokens.add(Token(TokenType.UNDERSCORE, "_", startLine, startCol))
            return
        }
        while (pos < source.length && (source[pos].isLetterOrDigit() || source[pos] == '_')) {
            sb.append(advance())
        }
        val text = sb.toString()
        val type = keywords[text] ?: TokenType.IDENT
        tokens.add(Token(type, text, startLine, startCol))
    }

    private fun readOperatorOrDelimiter(startLine: Int, startCol: Int) {
        val ch = advance()
        when (ch) {
            '(' -> tokens.add(Token(TokenType.LPAREN, "(", startLine, startCol))
            ')' -> tokens.add(Token(TokenType.RPAREN, ")", startLine, startCol))
            '{' -> tokens.add(Token(TokenType.LBRACE, "{", startLine, startCol))
            '}' -> tokens.add(Token(TokenType.RBRACE, "}", startLine, startCol))
            '[' -> tokens.add(Token(TokenType.LBRACKET, "[", startLine, startCol))
            ']' -> tokens.add(Token(TokenType.RBRACKET, "]", startLine, startCol))
            ':' -> tokens.add(Token(TokenType.COLON, ":", startLine, startCol))
            ',' -> tokens.add(Token(TokenType.COMMA, ",", startLine, startCol))
            '.' -> tokens.add(Token(TokenType.DOT, ".", startLine, startCol))
            '*' -> tokens.add(Token(TokenType.STAR, "*", startLine, startCol))
            '/' -> tokens.add(Token(TokenType.SLASH, "/", startLine, startCol))
            '%' -> tokens.add(Token(TokenType.PERCENT, "%", startLine, startCol))
            '+' -> {
                if (peek() == '+') {
                    advance()
                    tokens.add(Token(TokenType.PLUS_PLUS, "++", startLine, startCol))
                } else {
                    tokens.add(Token(TokenType.PLUS, "+", startLine, startCol))
                }
            }
            '-' -> {
                if (peek() == '>') {
                    advance()
                    tokens.add(Token(TokenType.ARROW, "->", startLine, startCol))
                } else {
                    tokens.add(Token(TokenType.MINUS, "-", startLine, startCol))
                }
            }
            '=' -> {
                if (peek() == '>') {
                    advance()
                    tokens.add(Token(TokenType.FAT_ARROW, "=>", startLine, startCol))
                } else if (peek() == '=') {
                    advance()
                    tokens.add(Token(TokenType.EQ, "==", startLine, startCol))
                } else {
                    tokens.add(Token(TokenType.EQUALS, "=", startLine, startCol))
                }
            }
            '!' -> {
                if (peek() == '=') {
                    advance()
                    tokens.add(Token(TokenType.NEQ, "!=", startLine, startCol))
                } else {
                    tokens.add(Token(TokenType.BANG, "!", startLine, startCol))
                }
            }
            '<' -> {
                if (peek() == '=') {
                    advance()
                    tokens.add(Token(TokenType.LTE, "<=", startLine, startCol))
                } else {
                    tokens.add(Token(TokenType.LT, "<", startLine, startCol))
                }
            }
            '>' -> {
                if (peek() == '=') {
                    advance()
                    tokens.add(Token(TokenType.GTE, ">=", startLine, startCol))
                } else {
                    tokens.add(Token(TokenType.GT, ">", startLine, startCol))
                }
            }
            '&' -> {
                if (peek() == '&') {
                    advance()
                    tokens.add(Token(TokenType.AND, "&&", startLine, startCol))
                } else {
                    throw ParseError("Unexpected character '&'", startLine, startCol)
                }
            }
            '|' -> {
                if (peek() == '|') {
                    advance()
                    tokens.add(Token(TokenType.OR, "||", startLine, startCol))
                } else {
                    tokens.add(Token(TokenType.PIPE, "|", startLine, startCol))
                }
            }
            else -> throw ParseError("Unexpected character '$ch'", startLine, startCol)
        }
    }
}
