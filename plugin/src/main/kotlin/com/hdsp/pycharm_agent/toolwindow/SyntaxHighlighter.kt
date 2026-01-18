package com.hdsp.pycharm_agent.toolwindow

/**
 * Multi-language syntax highlighter for code blocks.
 * Generates HTML spans with CSS classes for syntax highlighting.
 * Based on PyCharm Darcula theme colors.
 */
object SyntaxHighlighter {

    // Inline styles for different token types (PyCharm Darcula theme)
    private const val STYLE_KEYWORD = "color:#CC7832;font-weight:bold"      // Orange
    private const val STYLE_STRING = "color:#6A8759"                         // Green
    private const val STYLE_NUMBER = "color:#6897BB"                         // Blue
    private const val STYLE_COMMENT = "color:#808080;font-style:italic"      // Gray
    private const val STYLE_FUNCTION = "color:#FFC66D"                       // Yellow
    private const val STYLE_TYPE = "color:#A9B7C6"                           // Light gray
    private const val STYLE_OPERATOR = "color:#A9B7C6"                       // Light gray
    private const val STYLE_PROPERTY = "color:#9876AA"                       // Purple
    private const val STYLE_ANNOTATION = "color:#BBB529"                     // Yellow-green
    private const val STYLE_BRACKET = "color:#A9B7C6"                        // Light gray
    private const val STYLE_PUNCTUATION = "color:#CC7832"                    // Orange

    // JSON-specific styles
    private const val STYLE_JSON_KEY = "color:#9CDCFE"
    private const val STYLE_JSON_STRING = "color:#CE9178"
    private const val STYLE_JSON_NUMBER = "color:#B5CEA8"
    private const val STYLE_JSON_BOOLEAN = "color:#569CD6"
    private const val STYLE_JSON_NULL = "color:#569CD6"
    private const val STYLE_JSON_BRACKET = "color:#FFD700"
    private const val STYLE_JSON_COLON = "color:#D4D4D4"
    private const val STYLE_JSON_COMMA = "color:#D4D4D4"

    // Helper to wrap text in styled span
    private fun span(style: String, text: String) = """<span style="$style">$text</span>"""

    /**
     * Main entry point - highlight code based on language
     */
    fun highlight(code: String, language: String): String {
        return when (language.lowercase()) {
            "python", "py" -> highlightPython(code)
            "kotlin", "kt" -> highlightKotlin(code)
            "java" -> highlightJava(code)
            "javascript", "js" -> highlightJavaScript(code)
            "typescript", "ts" -> highlightTypeScript(code)
            "json" -> highlightJson(code)
            "yaml", "yml" -> highlightYaml(code)
            "bash", "sh", "shell", "zsh" -> highlightBash(code)
            "xml", "html" -> highlightXml(code)
            "sql" -> highlightSql(code)
            "go", "golang" -> highlightGo(code)
            "rust", "rs" -> highlightRust(code)
            "css" -> highlightCss(code)
            "auto", "" -> highlightWithAutoDetect(code)
            else -> escapeHtml(code)  // No highlighting for unknown languages
        }
    }

    /**
     * Auto-detect language and apply highlighting
     */
    fun highlightWithAutoDetect(code: String): String {
        val detectedLang = detectLanguage(code)
        return if (detectedLang != null) {
            highlight(code, detectedLang)
        } else {
            escapeHtml(code)
        }
    }

    /**
     * Detect programming language from code content using heuristics
     */
    fun detectLanguage(code: String): String? {
        val lines = code.lines().take(20)  // Check first 20 lines
        val content = lines.joinToString("\n")

        // Python indicators
        if (content.contains("import ") && (content.contains("from ") || content.contains(" as "))) return "python"
        if (content.contains("def ") && content.contains(":")) return "python"
        if (content.contains("print(") || content.contains("print (")) return "python"
        if (Regex("""^\s*(import|from)\s+\w+""", RegexOption.MULTILINE).containsMatchIn(content)) return "python"
        if (content.contains("elif ") || content.contains("self.") || content.contains("__init__")) return "python"

        // Kotlin indicators
        if (content.contains("fun ") && content.contains("{")) return "kotlin"
        if (content.contains("val ") || content.contains("var ")) {
            if (content.contains("fun ") || content.contains("class ") || content.contains("->")) return "kotlin"
        }
        if (content.contains("?.") || content.contains("!!")) return "kotlin"
        if (content.contains("companion object") || content.contains("data class")) return "kotlin"

        // Java indicators
        if (content.contains("public class ") || content.contains("private class ")) return "java"
        if (content.contains("public static void main")) return "java"
        if (content.contains("System.out.println")) return "java"
        if (Regex("""@Override|@Autowired|@Component""").containsMatchIn(content)) return "java"

        // JavaScript/TypeScript indicators
        if (content.contains("const ") || content.contains("let ")) {
            if (content.contains("=>") || content.contains("function ")) return "javascript"
            if (content.contains(": string") || content.contains(": number") || content.contains("interface ")) return "typescript"
        }
        if (content.contains("console.log(") || content.contains("document.")) return "javascript"
        if (content.contains("async ") && content.contains("await ")) return "javascript"

        // JSON indicators
        if (content.trim().startsWith("{") && content.contains("\":")) return "json"
        if (content.trim().startsWith("[") && content.contains("{")) return "json"

        // YAML indicators
        if (Regex("""^\s*\w+:\s*$""", RegexOption.MULTILINE).containsMatchIn(content)) return "yaml"
        if (content.contains("- ") && Regex("""^\s+\w+:""", RegexOption.MULTILINE).containsMatchIn(content)) return "yaml"

        // Bash/Shell indicators
        if (content.startsWith("#!/bin/") || content.startsWith("#!")) return "bash"
        if (Regex("""^\s*(if|for|while)\s+\[""", RegexOption.MULTILINE).containsMatchIn(content)) return "bash"
        if (content.contains("echo ") || content.contains("export ")) return "bash"
        if (content.contains(" | ") && content.contains("grep ")) return "bash"

        // SQL indicators
        if (Regex("""(?i)\b(SELECT|INSERT|UPDATE|DELETE|CREATE|ALTER|DROP)\b""").containsMatchIn(content)) return "sql"
        if (Regex("""(?i)\bFROM\s+\w+\s+(WHERE|JOIN|LEFT|RIGHT)""").containsMatchIn(content)) return "sql"

        // Go indicators
        if (content.contains("package ") && content.contains("func ")) return "go"
        if (content.contains("fmt.") || content.contains(":= ")) return "go"

        // Rust indicators
        if (content.contains("fn ") && content.contains("->")) return "rust"
        if (content.contains("let mut ") || content.contains("impl ")) return "rust"

        // XML/HTML indicators
        if (content.trim().startsWith("<") && content.contains("</")) return "xml"
        if (content.contains("<!DOCTYPE") || content.contains("<html")) return "html"

        // CSS indicators
        if (Regex("""\{[^}]*:\s*[^;]+;""").containsMatchIn(content)) {
            if (content.contains("color:") || content.contains("margin:") || content.contains("padding:")) return "css"
        }

        return null  // Unknown language
    }

    /**
     * Python syntax highlighting
     */
    fun highlightPython(code: String): String {
        val keywords = setOf(
            "def", "class", "if", "elif", "else", "for", "while", "return",
            "import", "from", "as", "try", "except", "finally", "raise",
            "with", "lambda", "pass", "break", "continue", "yield",
            "async", "await", "global", "nonlocal", "assert", "del", "in",
            "is", "not", "and", "or", "True", "False", "None"
        )
        val builtins = setOf(
            "print", "len", "range", "str", "int", "float", "list", "dict",
            "set", "tuple", "bool", "type", "isinstance", "hasattr", "getattr",
            "setattr", "open", "input", "super", "self", "cls", "enumerate",
            "zip", "map", "filter", "sorted", "reversed", "any", "all", "sum",
            "min", "max", "abs", "round", "format", "repr", "id", "hex", "bin",
            "oct", "ord", "chr", "bytes", "bytearray", "memoryview", "object",
            "staticmethod", "classmethod", "property", "Exception", "BaseException"
        )

        return highlightGeneric(
            code = code,
            keywords = keywords,
            builtins = builtins,
            singleLineComment = "#",
            multiLineCommentStart = "\"\"\"",
            multiLineCommentEnd = "\"\"\"",
            stringDelimiters = listOf("\"\"\"", "'''", "\"", "'"),
            annotationPrefix = "@"
        )
    }

    /**
     * Kotlin syntax highlighting
     */
    fun highlightKotlin(code: String): String {
        val keywords = setOf(
            "fun", "val", "var", "class", "interface", "object", "data", "sealed",
            "enum", "annotation", "companion", "if", "else", "when", "for", "while",
            "do", "return", "break", "continue", "throw", "try", "catch", "finally",
            "import", "package", "as", "is", "in", "out", "by", "where", "init",
            "constructor", "get", "set", "field", "suspend", "inline", "crossinline",
            "noinline", "reified", "external", "tailrec", "operator", "infix",
            "override", "open", "final", "abstract", "private", "protected",
            "public", "internal", "lateinit", "const", "typealias", "this", "super",
            "true", "false", "null", "it"
        )
        val builtins = setOf(
            "println", "print", "listOf", "mutableListOf", "setOf", "mutableSetOf",
            "mapOf", "mutableMapOf", "arrayOf", "intArrayOf", "emptyList", "emptySet",
            "emptyMap", "String", "Int", "Long", "Double", "Float", "Boolean", "Char",
            "Unit", "Any", "Nothing", "Array", "List", "Set", "Map", "Pair", "Triple",
            "apply", "also", "let", "run", "with", "takeIf", "takeUnless", "repeat",
            "require", "check", "error", "TODO", "lazy", "Sequence"
        )

        return highlightGeneric(
            code = code,
            keywords = keywords,
            builtins = builtins,
            singleLineComment = "//",
            multiLineCommentStart = "/*",
            multiLineCommentEnd = "*/",
            stringDelimiters = listOf("\"\"\"", "\""),
            annotationPrefix = "@"
        )
    }

    /**
     * Java syntax highlighting
     */
    fun highlightJava(code: String): String {
        val keywords = setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "const", "continue", "default", "do", "double",
            "else", "enum", "extends", "final", "finally", "float", "for",
            "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while", "true", "false", "null",
            "var", "record", "sealed", "permits", "non-sealed", "yield"
        )
        val builtins = setOf(
            "String", "System", "Integer", "Long", "Double", "Float", "Boolean",
            "Character", "Object", "Class", "Exception", "RuntimeException",
            "Thread", "Runnable", "List", "ArrayList", "LinkedList", "Map",
            "HashMap", "TreeMap", "Set", "HashSet", "TreeSet", "Optional",
            "Stream", "Collectors", "Arrays", "Collections", "Math", "StringBuilder"
        )

        return highlightGeneric(
            code = code,
            keywords = keywords,
            builtins = builtins,
            singleLineComment = "//",
            multiLineCommentStart = "/*",
            multiLineCommentEnd = "*/",
            stringDelimiters = listOf("\""),
            annotationPrefix = "@"
        )
    }

    /**
     * JavaScript syntax highlighting
     */
    fun highlightJavaScript(code: String): String {
        val keywords = setOf(
            "function", "const", "let", "var", "if", "else", "for", "while",
            "do", "switch", "case", "break", "continue", "return", "throw",
            "try", "catch", "finally", "class", "extends", "new", "this",
            "super", "import", "export", "default", "from", "as", "async",
            "await", "yield", "typeof", "instanceof", "in", "of", "delete",
            "void", "static", "get", "set", "true", "false", "null", "undefined",
            "NaN", "Infinity"
        )
        val builtins = setOf(
            "console", "document", "window", "fetch", "JSON", "Object", "Array",
            "String", "Number", "Boolean", "Date", "Math", "RegExp", "Error",
            "Promise", "Map", "Set", "Symbol", "Proxy", "Reflect", "WeakMap",
            "WeakSet", "parseInt", "parseFloat", "isNaN", "isFinite",
            "encodeURI", "decodeURI", "setTimeout", "setInterval", "clearTimeout",
            "clearInterval", "require", "module", "exports", "process"
        )

        return highlightGeneric(
            code = code,
            keywords = keywords,
            builtins = builtins,
            singleLineComment = "//",
            multiLineCommentStart = "/*",
            multiLineCommentEnd = "*/",
            stringDelimiters = listOf("`", "\"", "'"),
            annotationPrefix = null
        )
    }

    /**
     * TypeScript syntax highlighting (extends JavaScript)
     */
    fun highlightTypeScript(code: String): String {
        val keywords = setOf(
            "function", "const", "let", "var", "if", "else", "for", "while",
            "do", "switch", "case", "break", "continue", "return", "throw",
            "try", "catch", "finally", "class", "extends", "implements",
            "new", "this", "super", "import", "export", "default", "from", "as",
            "async", "await", "yield", "typeof", "instanceof", "in", "of",
            "delete", "void", "static", "get", "set", "true", "false", "null",
            "undefined", "NaN", "Infinity", "type", "interface", "enum",
            "namespace", "module", "declare", "abstract", "readonly", "private",
            "protected", "public", "keyof", "infer", "never", "unknown", "any",
            "is", "asserts", "satisfies"
        )
        val builtins = setOf(
            "console", "document", "window", "fetch", "JSON", "Object", "Array",
            "String", "Number", "Boolean", "Date", "Math", "RegExp", "Error",
            "Promise", "Map", "Set", "Symbol", "Proxy", "Reflect", "WeakMap",
            "WeakSet", "parseInt", "parseFloat", "isNaN", "isFinite",
            "Partial", "Required", "Readonly", "Record", "Pick", "Omit",
            "Exclude", "Extract", "NonNullable", "ReturnType", "InstanceType",
            "Parameters", "ConstructorParameters", "Awaited"
        )

        return highlightGeneric(
            code = code,
            keywords = keywords,
            builtins = builtins,
            singleLineComment = "//",
            multiLineCommentStart = "/*",
            multiLineCommentEnd = "*/",
            stringDelimiters = listOf("`", "\"", "'"),
            annotationPrefix = "@"
        )
    }

    /**
     * JSON syntax highlighting (preserves existing behavior)
     */
    fun highlightJson(code: String): String {
        val result = StringBuilder()
        var i = 0
        var inString = false
        var stringStart = -1

        while (i < code.length) {
            val c = code[i]

            when {
                c == '"' && (i == 0 || code[i - 1] != '\\') -> {
                    if (!inString) {
                        inString = true
                        stringStart = i
                    } else {
                        inString = false
                        val stringContent = code.substring(stringStart, i + 1)
                        val escaped = escapeHtml(stringContent)

                        val remaining = code.substring(i + 1).trimStart()
                        if (remaining.startsWith(':')) {
                            result.append("""<span style="color:#9CDCFE">$escaped</span>""")
                        } else {
                            result.append("""<span style="color:#CE9178">$escaped</span>""")
                        }
                    }
                    i++
                }
                inString -> i++
                c == '{' || c == '}' || c == '[' || c == ']' -> {
                    result.append("""<span style="color:#FFD700">$c</span>""")
                    i++
                }
                c == ':' -> {
                    result.append("""<span style="color:#D4D4D4">:</span>""")
                    i++
                }
                c == ',' -> {
                    result.append("""<span style="color:#D4D4D4">,</span>""")
                    i++
                }
                code.substring(i).startsWith("true") -> {
                    result.append("""<span style="color:#569CD6">true</span>""")
                    i += 4
                }
                code.substring(i).startsWith("false") -> {
                    result.append("""<span style="color:#569CD6">false</span>""")
                    i += 5
                }
                code.substring(i).startsWith("null") -> {
                    result.append("""<span style="color:#569CD6">null</span>""")
                    i += 4
                }
                c.isDigit() || (c == '-' && i + 1 < code.length && code[i + 1].isDigit()) -> {
                    val numStart = i
                    if (c == '-') i++
                    while (i < code.length && (code[i].isDigit() || code[i] == '.' ||
                                code[i] == 'e' || code[i] == 'E' || code[i] == '+' || code[i] == '-')) {
                        if ((code[i] == '+' || code[i] == '-') && i > numStart &&
                            code[i-1] != 'e' && code[i-1] != 'E') break
                        i++
                    }
                    val numStr = code.substring(numStart, i)
                    result.append("""<span style="color:#B5CEA8">$numStr</span>""")
                }
                else -> {
                    result.append(escapeHtml(c.toString()))
                    i++
                }
            }
        }
        return result.toString()
    }

    /**
     * YAML syntax highlighting
     */
    fun highlightYaml(code: String): String {
        val result = StringBuilder()
        val lines = code.lines()

        for ((lineIndex, line) in lines.withIndex()) {
            if (lineIndex > 0) result.append("<br>")

            // Comment
            val commentIndex = line.indexOf('#')
            val activeLine = if (commentIndex >= 0 && !isInString(line, commentIndex)) {
                val beforeComment = line.substring(0, commentIndex)
                val comment = line.substring(commentIndex)
                highlightYamlLine(beforeComment) +
                        """<span style="color:#808080;font-style:italic">${escapeHtml(comment)}</span>"""
            } else {
                highlightYamlLine(line)
            }
            result.append(activeLine)
        }
        return result.toString()
    }

    private fun highlightYamlLine(line: String): String {
        if (line.isBlank()) return escapeHtml(line)

        val result = StringBuilder()

        // Check for key: value pattern
        val colonIndex = line.indexOf(':')
        if (colonIndex > 0 && !isInString(line, colonIndex)) {
            val key = line.substring(0, colonIndex)
            val rest = line.substring(colonIndex)

            // Highlight key
            result.append("""<span style="color:#9876AA">${escapeHtml(key)}</span>""")

            // Highlight colon and value
            if (rest.length > 1) {
                result.append("""<span style="color:#CC7832">:</span>""")
                val value = rest.substring(1).trim()
                if (value.isNotEmpty()) {
                    result.append(" ")
                    result.append(highlightYamlValue(value))
                }
            } else {
                result.append("""<span style="color:#CC7832">:</span>""")
            }
        } else {
            // Check for list item
            val trimmed = line.trimStart()
            if (trimmed.startsWith("- ")) {
                val indent = line.substring(0, line.length - trimmed.length)
                result.append(escapeHtml(indent))
                result.append("""<span style="color:#CC7832">-</span> """)
                val value = trimmed.substring(2)
                result.append(highlightYamlValue(value))
            } else {
                result.append(highlightYamlValue(line))
            }
        }
        return result.toString()
    }

    private fun highlightYamlValue(value: String): String {
        val trimmed = value.trim()
        return when {
            trimmed == "true" || trimmed == "false" || trimmed == "yes" || trimmed == "no" ->
                """<span style="color:#CC7832;font-weight:bold">${escapeHtml(value)}</span>"""
            trimmed == "null" || trimmed == "~" ->
                """<span style="color:#CC7832;font-weight:bold">${escapeHtml(value)}</span>"""
            trimmed.matches(Regex("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")) ->
                """<span style="color:#6897BB">${escapeHtml(value)}</span>"""
            trimmed.startsWith("\"") || trimmed.startsWith("'") ->
                """<span style="color:#6A8759">${escapeHtml(value)}</span>"""
            else -> escapeHtml(value)
        }
    }

    /**
     * Bash/Shell syntax highlighting
     */
    fun highlightBash(code: String): String {
        val keywords = setOf(
            "if", "then", "else", "elif", "fi", "for", "while", "do", "done",
            "case", "esac", "in", "function", "return", "exit", "local",
            "export", "readonly", "declare", "typeset", "unset", "shift",
            "break", "continue", "source", "alias", "eval", "exec", "trap"
        )
        val builtins = setOf(
            "echo", "printf", "read", "cd", "pwd", "ls", "cp", "mv", "rm",
            "mkdir", "rmdir", "cat", "grep", "sed", "awk", "cut", "sort",
            "uniq", "wc", "head", "tail", "find", "xargs", "test", "true",
            "false", "set", "env", "which", "type", "command", "builtin"
        )

        val result = StringBuilder()
        val lines = code.lines()

        for ((lineIndex, line) in lines.withIndex()) {
            if (lineIndex > 0) result.append("<br>")

            // Comment line
            val trimmed = line.trimStart()
            if (trimmed.startsWith("#") && !trimmed.startsWith("#!")) {
                result.append("""<span style="color:#808080;font-style:italic">${escapeHtml(line)}</span>""")
                continue
            }

            // Shebang
            if (trimmed.startsWith("#!")) {
                result.append("""<span style="color:#BBB529">${escapeHtml(line)}</span>""")
                continue
            }

            // Process line token by token
            result.append(highlightBashLine(line, keywords, builtins))
        }
        return result.toString()
    }

    private fun highlightBashLine(line: String, keywords: Set<String>, builtins: Set<String>): String {
        val result = StringBuilder()
        var i = 0
        var isFirstWord = true

        while (i < line.length) {
            val c = line[i]

            when {
                // Variable $VAR or ${VAR}
                c == '$' -> {
                    if (i + 1 < line.length && line[i + 1] == '{') {
                        val end = line.indexOf('}', i + 2)
                        if (end > 0) {
                            val varExpr = line.substring(i, end + 1)
                            result.append("""<span style="color:#9876AA">${escapeHtml(varExpr)}</span>""")
                            i = end + 1
                        } else {
                            result.append(escapeHtml(c.toString()))
                            i++
                        }
                    } else if (i + 1 < line.length && (line[i + 1].isLetterOrDigit() || line[i + 1] == '_')) {
                        val start = i
                        i++
                        while (i < line.length && (line[i].isLetterOrDigit() || line[i] == '_')) i++
                        val varName = line.substring(start, i)
                        result.append("""<span style="color:#9876AA">${escapeHtml(varName)}</span>""")
                    } else {
                        result.append("""<span style="color:#9876AA">$</span>""")
                        i++
                    }
                    isFirstWord = false
                }
                // Strings
                c == '"' || c == '\'' -> {
                    val quote = c
                    val start = i
                    i++
                    while (i < line.length && line[i] != quote) {
                        if (line[i] == '\\' && i + 1 < line.length) i++
                        i++
                    }
                    if (i < line.length) i++
                    val str = line.substring(start, i)
                    result.append("""<span style="color:#6A8759">${escapeHtml(str)}</span>""")
                    isFirstWord = false
                }
                // Identifiers/words
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < line.length && (line[i].isLetterOrDigit() || line[i] == '_' || line[i] == '-')) i++
                    val word = line.substring(start, i)

                    when {
                        keywords.contains(word) ->
                            result.append("""<span style="color:#CC7832;font-weight:bold">${escapeHtml(word)}</span>""")
                        isFirstWord && builtins.contains(word) ->
                            result.append("""<span style="color:#FFC66D">${escapeHtml(word)}</span>""")
                        isFirstWord ->
                            result.append("""<span style="color:#FFC66D">${escapeHtml(word)}</span>""")
                        else ->
                            result.append(escapeHtml(word))
                    }
                    isFirstWord = false
                }
                // Numbers
                c.isDigit() -> {
                    val start = i
                    while (i < line.length && (line[i].isDigit() || line[i] == '.')) i++
                    val num = line.substring(start, i)
                    result.append("""<span style="color:#6897BB">${escapeHtml(num)}</span>""")
                    isFirstWord = false
                }
                // Operators and punctuation
                c == '|' || c == '&' || c == ';' || c == '>' || c == '<' -> {
                    result.append("""<span style="color:#A9B7C6">${escapeHtml(c.toString())}</span>""")
                    i++
                    isFirstWord = (c == ';' || c == '|' || c == '&')
                }
                // Whitespace
                c.isWhitespace() -> {
                    result.append(c)
                    i++
                }
                else -> {
                    result.append(escapeHtml(c.toString()))
                    i++
                    isFirstWord = false
                }
            }
        }
        return result.toString()
    }

    /**
     * XML/HTML syntax highlighting
     */
    fun highlightXml(code: String): String {
        val result = StringBuilder()
        var i = 0

        while (i < code.length) {
            when {
                // Comment
                code.substring(i).startsWith("<!--") -> {
                    val end = code.indexOf("-->", i + 4)
                    val commentEnd = if (end >= 0) end + 3 else code.length
                    val comment = code.substring(i, commentEnd)
                    result.append("""<span style="color:#808080;font-style:italic">${escapeHtml(comment)}</span>""")
                    i = commentEnd
                }
                // CDATA
                code.substring(i).startsWith("<![CDATA[") -> {
                    val end = code.indexOf("]]>", i + 9)
                    val cdataEnd = if (end >= 0) end + 3 else code.length
                    val cdata = code.substring(i, cdataEnd)
                    result.append("""<span style="color:#6A8759">${escapeHtml(cdata)}</span>""")
                    i = cdataEnd
                }
                // Tag
                code[i] == '<' -> {
                    val (tagHtml, newIndex) = highlightXmlTag(code, i)
                    result.append(tagHtml)
                    i = newIndex
                }
                else -> {
                    result.append(escapeHtml(code[i].toString()))
                    i++
                }
            }
        }
        return result.toString()
    }

    private fun highlightXmlTag(code: String, start: Int): Pair<String, Int> {
        val result = StringBuilder()
        var i = start
        result.append("""<span style="color:#A9B7C6">&lt;</span>""")
        i++

        // Handle closing tag or processing instruction
        if (i < code.length && (code[i] == '/' || code[i] == '?')) {
            result.append("""<span style="color:#A9B7C6">${code[i]}</span>""")
            i++
        }

        // Tag name
        val nameStart = i
        while (i < code.length && (code[i].isLetterOrDigit() || code[i] == ':' || code[i] == '-' || code[i] == '_')) i++
        if (i > nameStart) {
            val tagName = code.substring(nameStart, i)
            result.append("""<span style="color:#CC7832;font-weight:bold">${escapeHtml(tagName)}</span>""")
        }

        // Attributes
        while (i < code.length && code[i] != '>') {
            when {
                code[i].isWhitespace() -> {
                    result.append(code[i])
                    i++
                }
                code[i].isLetter() || code[i] == ':' || code[i] == '_' -> {
                    // Attribute name
                    val attrStart = i
                    while (i < code.length && (code[i].isLetterOrDigit() || code[i] == ':' || code[i] == '-' || code[i] == '_')) i++
                    val attrName = code.substring(attrStart, i)
                    result.append("""<span style="color:#9876AA">${escapeHtml(attrName)}</span>""")
                }
                code[i] == '=' -> {
                    result.append("""<span style="color:#A9B7C6">=</span>""")
                    i++
                }
                code[i] == '"' || code[i] == '\'' -> {
                    val quote = code[i]
                    val valueStart = i
                    i++
                    while (i < code.length && code[i] != quote) i++
                    if (i < code.length) i++
                    val value = code.substring(valueStart, i)
                    result.append("""<span style="color:#6A8759">${escapeHtml(value)}</span>""")
                }
                code[i] == '/' || code[i] == '?' -> {
                    result.append("""<span style="color:#A9B7C6">${code[i]}</span>""")
                    i++
                }
                else -> {
                    result.append(escapeHtml(code[i].toString()))
                    i++
                }
            }
        }

        if (i < code.length && code[i] == '>') {
            result.append("""<span style="color:#A9B7C6">&gt;</span>""")
            i++
        }

        return Pair(result.toString(), i)
    }

    /**
     * SQL syntax highlighting
     */
    fun highlightSql(code: String): String {
        val keywords = setOf(
            "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "IN", "LIKE", "BETWEEN",
            "IS", "NULL", "AS", "ON", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER",
            "FULL", "CROSS", "NATURAL", "USING", "ORDER", "BY", "ASC", "DESC",
            "GROUP", "HAVING", "LIMIT", "OFFSET", "UNION", "ALL", "EXCEPT",
            "INTERSECT", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE",
            "CREATE", "TABLE", "INDEX", "VIEW", "DROP", "ALTER", "ADD", "COLUMN",
            "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "UNIQUE", "CHECK", "DEFAULT",
            "CONSTRAINT", "CASCADE", "RESTRICT", "DISTINCT", "TOP", "CASE", "WHEN",
            "THEN", "ELSE", "END", "EXISTS", "ANY", "SOME", "TRUE", "FALSE",
            "select", "from", "where", "and", "or", "not", "in", "like", "between",
            "is", "null", "as", "on", "join", "left", "right", "inner", "outer",
            "full", "cross", "natural", "using", "order", "by", "asc", "desc",
            "group", "having", "limit", "offset", "union", "all", "except",
            "intersect", "insert", "into", "values", "update", "set", "delete",
            "create", "table", "index", "view", "drop", "alter", "add", "column",
            "primary", "key", "foreign", "references", "unique", "check", "default",
            "constraint", "cascade", "restrict", "distinct", "top", "case", "when",
            "then", "else", "end", "exists", "any", "some", "true", "false"
        )
        val functions = setOf(
            "COUNT", "SUM", "AVG", "MIN", "MAX", "COALESCE", "NULLIF", "CAST",
            "CONVERT", "SUBSTRING", "TRIM", "UPPER", "LOWER", "LENGTH", "CONCAT",
            "NOW", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "DATE",
            "TIME", "YEAR", "MONTH", "DAY", "HOUR", "MINUTE", "SECOND", "ROUND",
            "FLOOR", "CEIL", "ABS", "MOD", "POWER", "SQRT", "IFNULL", "NVL", "IIF",
            "count", "sum", "avg", "min", "max", "coalesce", "nullif", "cast",
            "convert", "substring", "trim", "upper", "lower", "length", "concat",
            "now", "current_date", "current_time", "current_timestamp", "date",
            "time", "year", "month", "day", "hour", "minute", "second", "round",
            "floor", "ceil", "abs", "mod", "power", "sqrt", "ifnull", "nvl", "iif"
        )

        return highlightGeneric(
            code = code,
            keywords = keywords,
            builtins = functions,
            singleLineComment = "--",
            multiLineCommentStart = "/*",
            multiLineCommentEnd = "*/",
            stringDelimiters = listOf("'"),
            annotationPrefix = null
        )
    }

    /**
     * Go syntax highlighting
     */
    fun highlightGo(code: String): String {
        val keywords = setOf(
            "break", "case", "chan", "const", "continue", "default", "defer",
            "else", "fallthrough", "for", "func", "go", "goto", "if", "import",
            "interface", "map", "package", "range", "return", "select", "struct",
            "switch", "type", "var", "true", "false", "nil", "iota"
        )
        val builtins = setOf(
            "append", "cap", "close", "complex", "copy", "delete", "imag", "len",
            "make", "new", "panic", "print", "println", "real", "recover",
            "string", "int", "int8", "int16", "int32", "int64",
            "uint", "uint8", "uint16", "uint32", "uint64", "uintptr",
            "float32", "float64", "complex64", "complex128",
            "byte", "rune", "bool", "error", "any"
        )

        return highlightGeneric(
            code = code,
            keywords = keywords,
            builtins = builtins,
            singleLineComment = "//",
            multiLineCommentStart = "/*",
            multiLineCommentEnd = "*/",
            stringDelimiters = listOf("`", "\""),
            annotationPrefix = null
        )
    }

    /**
     * Rust syntax highlighting
     */
    fun highlightRust(code: String): String {
        val keywords = setOf(
            "as", "async", "await", "break", "const", "continue", "crate", "dyn",
            "else", "enum", "extern", "false", "fn", "for", "if", "impl", "in",
            "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return",
            "self", "Self", "static", "struct", "super", "trait", "true", "type",
            "unsafe", "use", "where", "while", "abstract", "become", "box", "do",
            "final", "macro", "override", "priv", "typeof", "unsized", "virtual",
            "yield"
        )
        val builtins = setOf(
            "println", "print", "eprintln", "eprint", "format", "panic", "assert",
            "debug_assert", "vec", "Box", "Rc", "Arc", "Cell", "RefCell", "Mutex",
            "RwLock", "Option", "Some", "None", "Result", "Ok", "Err",
            "String", "str", "Vec", "HashMap", "HashSet", "BTreeMap", "BTreeSet",
            "i8", "i16", "i32", "i64", "i128", "isize",
            "u8", "u16", "u32", "u64", "u128", "usize",
            "f32", "f64", "bool", "char", "Copy", "Clone", "Send", "Sync",
            "Drop", "Default", "Debug", "Display", "Iterator", "IntoIterator"
        )

        return highlightGeneric(
            code = code,
            keywords = keywords,
            builtins = builtins,
            singleLineComment = "//",
            multiLineCommentStart = "/*",
            multiLineCommentEnd = "*/",
            stringDelimiters = listOf("\""),
            annotationPrefix = "#["
        )
    }

    /**
     * CSS syntax highlighting
     */
    fun highlightCss(code: String): String {
        val result = StringBuilder()
        var i = 0

        while (i < code.length) {
            when {
                // Comment
                code.substring(i).startsWith("/*") -> {
                    val end = code.indexOf("*/", i + 2)
                    val commentEnd = if (end >= 0) end + 2 else code.length
                    val comment = code.substring(i, commentEnd)
                    result.append("""<span style="color:#808080;font-style:italic">${escapeHtml(comment)}</span>""")
                    i = commentEnd
                }
                // Selector or property
                code[i] == '{' -> {
                    result.append("""<span style="color:#A9B7C6">{</span>""")
                    i++
                }
                code[i] == '}' -> {
                    result.append("""<span style="color:#A9B7C6">}</span>""")
                    i++
                }
                code[i] == ':' -> {
                    result.append("""<span style="color:#CC7832">:</span>""")
                    i++
                }
                code[i] == ';' -> {
                    result.append("""<span style="color:#CC7832">;</span>""")
                    i++
                }
                // Selector classes, ids
                code[i] == '.' || code[i] == '#' -> {
                    val start = i
                    i++
                    while (i < code.length && (code[i].isLetterOrDigit() || code[i] == '-' || code[i] == '_')) i++
                    val selector = code.substring(start, i)
                    result.append("""<span style="color:#A9B7C6">${escapeHtml(selector)}</span>""")
                }
                // Strings
                code[i] == '"' || code[i] == '\'' -> {
                    val quote = code[i]
                    val start = i
                    i++
                    while (i < code.length && code[i] != quote) {
                        if (code[i] == '\\' && i + 1 < code.length) i++
                        i++
                    }
                    if (i < code.length) i++
                    val str = code.substring(start, i)
                    result.append("""<span style="color:#6A8759">${escapeHtml(str)}</span>""")
                }
                // Numbers with units
                code[i].isDigit() || (code[i] == '-' && i + 1 < code.length && code[i + 1].isDigit()) -> {
                    val start = i
                    if (code[i] == '-') i++
                    while (i < code.length && (code[i].isDigit() || code[i] == '.')) i++
                    // Unit
                    while (i < code.length && code[i].isLetter()) i++
                    val num = code.substring(start, i)
                    result.append("""<span style="color:#6897BB">${escapeHtml(num)}</span>""")
                }
                // Identifiers
                code[i].isLetter() || code[i] == '-' -> {
                    val start = i
                    while (i < code.length && (code[i].isLetterOrDigit() || code[i] == '-' || code[i] == '_')) i++
                    val word = code.substring(start, i)
                    result.append("""<span style="color:#9876AA">${escapeHtml(word)}</span>""")
                }
                else -> {
                    result.append(escapeHtml(code[i].toString()))
                    i++
                }
            }
        }
        return result.toString()
    }

    /**
     * Generic highlighter for C-family languages
     */
    private fun highlightGeneric(
        code: String,
        keywords: Set<String>,
        builtins: Set<String>,
        singleLineComment: String?,
        multiLineCommentStart: String?,
        multiLineCommentEnd: String?,
        stringDelimiters: List<String>,
        annotationPrefix: String?
    ): String {
        val result = StringBuilder()
        var i = 0

        while (i < code.length) {
            // Multi-line comment
            if (multiLineCommentStart != null && multiLineCommentEnd != null &&
                code.substring(i).startsWith(multiLineCommentStart)) {
                val end = code.indexOf(multiLineCommentEnd, i + multiLineCommentStart.length)
                val commentEnd = if (end >= 0) end + multiLineCommentEnd.length else code.length
                val comment = code.substring(i, commentEnd)
                result.append("""<span style="color:#808080;font-style:italic">${escapeHtml(comment)}</span>""")
                i = commentEnd
                continue
            }

            // Single-line comment
            if (singleLineComment != null && code.substring(i).startsWith(singleLineComment)) {
                val end = code.indexOf('\n', i)
                val commentEnd = if (end >= 0) end else code.length
                val comment = code.substring(i, commentEnd)
                result.append("""<span style="color:#808080;font-style:italic">${escapeHtml(comment)}</span>""")
                i = commentEnd
                continue
            }

            // Annotation/decorator
            if (annotationPrefix != null && code.substring(i).startsWith(annotationPrefix)) {
                val start = i
                i += annotationPrefix.length
                // Handle Rust-style #[...] annotations
                if (annotationPrefix == "#[") {
                    val end = code.indexOf(']', i)
                    if (end >= 0) i = end + 1
                } else {
                    // Handle @-style annotations
                    while (i < code.length && (code[i].isLetterOrDigit() || code[i] == '_' || code[i] == '.')) i++
                }
                val annotation = code.substring(start, i)
                result.append("""<span style="color:#BBB529">${escapeHtml(annotation)}</span>""")
                continue
            }

            // Strings
            var stringMatched = false
            for (delim in stringDelimiters) {
                if (code.substring(i).startsWith(delim)) {
                    val start = i
                    i += delim.length
                    // Find closing delimiter
                    while (i < code.length) {
                        if (code.substring(i).startsWith(delim)) {
                            i += delim.length
                            break
                        }
                        if (code[i] == '\\' && i + 1 < code.length && delim.length == 1) {
                            i += 2
                        } else {
                            i++
                        }
                    }
                    val str = code.substring(start, i)
                    result.append("""<span style="color:#6A8759">${escapeHtml(str)}</span>""")
                    stringMatched = true
                    break
                }
            }
            if (stringMatched) continue

            // Identifiers
            if (code[i].isLetter() || code[i] == '_') {
                val start = i
                while (i < code.length && (code[i].isLetterOrDigit() || code[i] == '_')) i++
                val word = code.substring(start, i)
                when {
                    keywords.contains(word) ->
                        result.append("""<span style="color:#CC7832;font-weight:bold">${escapeHtml(word)}</span>""")
                    builtins.contains(word) ->
                        result.append("""<span style="color:#FFC66D">${escapeHtml(word)}</span>""")
                    word.first().isUpperCase() ->
                        result.append("""<span style="color:#A9B7C6">${escapeHtml(word)}</span>""")
                    else ->
                        result.append(escapeHtml(word))
                }
                continue
            }

            // Numbers
            if (code[i].isDigit() || (code[i] == '.' && i + 1 < code.length && code[i + 1].isDigit())) {
                val start = i
                // Handle hex, octal, binary
                if (code[i] == '0' && i + 1 < code.length) {
                    when (code[i + 1]) {
                        'x', 'X' -> {
                            i += 2
                            while (i < code.length && (code[i].isDigit() || code[i] in 'a'..'f' || code[i] in 'A'..'F' || code[i] == '_')) i++
                        }
                        'b', 'B' -> {
                            i += 2
                            while (i < code.length && (code[i] == '0' || code[i] == '1' || code[i] == '_')) i++
                        }
                        'o', 'O' -> {
                            i += 2
                            while (i < code.length && (code[i] in '0'..'7' || code[i] == '_')) i++
                        }
                        else -> {
                            while (i < code.length && (code[i].isDigit() || code[i] == '.' || code[i] == 'e' || code[i] == 'E' || code[i] == '_' || code[i] == '+' || code[i] == '-')) {
                                if ((code[i] == '+' || code[i] == '-') && i > start && code[i - 1] != 'e' && code[i - 1] != 'E') break
                                i++
                            }
                        }
                    }
                } else {
                    while (i < code.length && (code[i].isDigit() || code[i] == '.' || code[i] == 'e' || code[i] == 'E' || code[i] == '_' || code[i] == '+' || code[i] == '-')) {
                        if ((code[i] == '+' || code[i] == '-') && i > start && code[i - 1] != 'e' && code[i - 1] != 'E') break
                        i++
                    }
                }
                // Type suffix (L, f, u, etc.)
                while (i < code.length && (code[i].isLetter() || code[i] == '_')) i++
                val num = code.substring(start, i)
                result.append("""<span style="color:#6897BB">${escapeHtml(num)}</span>""")
                continue
            }

            // Brackets and operators
            when (code[i]) {
                '{', '}', '[', ']', '(', ')' -> {
                    result.append("""<span style="color:#A9B7C6">${escapeHtml(code[i].toString())}</span>""")
                }
                '+', '-', '*', '/', '%', '=', '<', '>', '!', '&', '|', '^', '~', '?' -> {
                    result.append("""<span style="color:#A9B7C6">${escapeHtml(code[i].toString())}</span>""")
                }
                ';', ',', '.' -> {
                    result.append("""<span style="color:#CC7832">${escapeHtml(code[i].toString())}</span>""")
                }
                else -> {
                    result.append(escapeHtml(code[i].toString()))
                }
            }
            i++
        }

        return result.toString()
    }

    /**
     * Check if an index is inside a string (simple check)
     */
    private fun isInString(line: String, index: Int): Boolean {
        var inSingleQuote = false
        var inDoubleQuote = false
        for (i in 0 until index) {
            when {
                line[i] == '\'' && !inDoubleQuote && (i == 0 || line[i - 1] != '\\') ->
                    inSingleQuote = !inSingleQuote
                line[i] == '"' && !inSingleQuote && (i == 0 || line[i - 1] != '\\') ->
                    inDoubleQuote = !inDoubleQuote
            }
        }
        return inSingleQuote || inDoubleQuote
    }

    /**
     * Escape HTML special characters and convert newlines to <br>
     */
    fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>")
    }
}
