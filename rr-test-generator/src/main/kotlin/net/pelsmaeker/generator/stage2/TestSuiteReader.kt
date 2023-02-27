package net.pelsmaeker.generator.stage2

import net.pelsmaeker.generator.utils.replaceAll

/**
 * Reads test cases from a file.
 *
 * The file should be a Java file with multiple packages and multiple compilation units (as required by Java SPT tests).
 * Relevant references and declarations should be surrounded by `[[ ... ]]`. A declaration starts with an identifier
 * (e.g., a number), followed by a pipe, followed by the name of the declaration. For example, `[[1|foo]]` is a
 * declaration of a member named `foo`.
 *
 * A reference starts with an arrow, an identifier of the declaration it should resolve to (e.g., a number), followed by
 * a pipe, followed by the input name of the reference, followed by the expected (qualified) name of the reference.
 * If the expected name is omitted, it is assumed to be equal to the input name. Whitespace around the names or code is
 * ignored. For example, `[[->1|foo|B.foo]]` is a reference to the declaration `[[1|foo]]` in class `B`.
 */
object TestSuiteReader {

    // Annotations: "[[{disabled}]]"
    private val annotationRegex = Regex("""\[\[\{([^|\]]+)\}\]\]""")
    private val markerRegex = Regex("""\[\[((?:->|@|&)[^|\]]+)((?:\|[^|\]]+)*)\]\]""")

    /**
     * Reads a test suite from the given text with markers.
     *
     * A declaration should be marked with `[[id|name]]`, where `id` is an identifier and `name` is the name of the
     * declaration. A reference should be marked with `[[->id|initial|expected]]`, where `id` is the identifier of the
     * declaration to which the reference should resolve, `initial` is the initial (unrefactored, possibly wrong, maybe
     * qualified) name of the reference, and `expected` is the expected qualified name of the reference.
     *
     * @param name the test suite name
     * @param name the test suite qualifier; or `null`
     * @param directory the test suite directory
     * @param text the text with markers
     * @return the test suite
     */
    fun readTestSuite(name: String, directory: String, text: String): TestSuite {
        val refs = mutableListOf<Ref>()
        val decls = mutableListOf<Decl>()
        val contexts = mutableListOf<Context>()

        markerRegex.findAll(text).forEach { match ->
            val range = match.groups[0]!!.range
            val operator = match.groups[1]!!.value
            val values = match.groups[2]!!.value.substring(1).split('|').toMutableList()

            when {
                operator.startsWith('@') -> {
                    // Declaration
                    val id = operator.substring(1).trim()
                    val t = values.removeFirstOrNull() ?: error("No name for declaration $operator")
                    val decl = Decl(id, t, range)
                    decls.add(decl)
                }
                operator.startsWith("->") -> {
                    // Reference
                    val id = operator.substring(2).trim()
                    val context = if (values.firstOrNull()?.startsWith("&->") == true) {
                        // Context specifier
                        values.removeFirst().substring(3).trim()
                    } else null
                    val t = values.removeFirstOrNull() ?: error("No initial name for reference $operator")
                    val et = values.removeFirstOrNull() ?: t
                    val ref = Ref(id, context, t, et, range)
                    refs.add(ref)
                }
                operator.startsWith("&") -> {
                    // Context specifier
                    val id = operator.substring(1).trim()
                    val t = values.removeFirstOrNull() ?: error("No name for context $operator")
                    val context = Context(id, t, range)
                    contexts.add(context)
                }
                else -> error("Unknown operator: $operator")
            }
        }

        // Build the 'expected text' (the code without any markers and with the expected qualified references).
        // At the same time, convert the markers to JavaIds and build a list of all identifiers in order from first to last.
        val markerToId = mutableMapOf<Marker, Highlight>()
        val highlights = mutableListOf<Highlight>()
        val expectedText = text.replaceAll(refs + decls + contexts, { it.range }) { m, _, s ->
            val id = m.toId(s)
            markerToId[m] = id
            highlights.add(id)
            m.replacementText
        }

        // For each reference, find the corresponding declaration and build a test case.
        val cases = mutableListOf<TestCase>()
        refs.forEach { ref ->
            val decl = decls.firstOrNull { it.id == ref.declId } ?: error("No declaration for reference $ref")
            val declIndex = highlights.indexOf(markerToId[decl])
            val refIndex = highlights.indexOf(markerToId[ref])
            val context = contexts.firstOrNull { it.id == ref.contextId }
            val contextIndex = context?.let { highlights.indexOf(markerToId[it]) }
            cases.add(TestCase(refIndex, declIndex, contextIndex, ref.text))
        }

        // Process any annotations
        val annotations = annotationRegex.findAll(text).map { it.groups[1]!!.value }.toSet()
        val isDisabled = annotations.contains("disabled")

        // TODO: Do something with the contexts

        return TestSuite(
            name = name,
            directory = directory,
            expectedText = expectedText,
            identifiers = highlights,
            cases = cases,
            isDisabled = isDisabled,
        )
    }

    private fun <T> MutableList<T>.removeFirstOrNull(): T? {
        return if (this.isNotEmpty()) this.removeAt(0) else null
    }

    /** Common interface for markers. */
    private sealed interface Marker {
        /** The replacement text for the marker, which is the actual text for a declaration and the expected text for a reference. */
        val replacementText: String
        /** The range of the marker in the text. */
        val range: IntRange

        /**
         * Converts the marker to a [Highlight].
         *
         * @param start the final start index of the marker in the expected text
         * @return the [Highlight]
         */
        fun toId(start: Int): Highlight {
            return Highlight(start until start + replacementText.length)
        }
    }

    /** A context marker. */
    private data class Context(
        /** The identifier of the context. */
        val id: String,
        /** The text of the context. */
        val text: String,
        override val range: IntRange,
    ): Marker {
        override val replacementText get() = text
        override fun toString(): String = "&$id|$text"
    }

    /** A reference marker. */
    private data class Ref(
        /** The identifier of the declaration to which the reference should resolve. */
        val declId: String,
        /** The identifier of the context marker. */
        val contextId: String?,
        /** The input text of the reference. */
        val text: String,
        /** The expected text of the reference. */
        val expectedText: String,
        override val range: IntRange,
    ): Marker {
        override val replacementText get() = expectedText
        override fun toString(): String = StringBuilder().apply {
            append("->$declId")
            if (contextId != null) append("|&->$contextId")
            append("|$text")
            if (text != expectedText) append("|$expectedText")
        }.toString()
    }

    /** A declaration marker. */
    private data class Decl(
        /** The identifier of the declaration. */
        val id: String,
        /** The text of the declaration. */
        val text: String,
        override val range: IntRange,
    ): Marker {
        override val replacementText get() = text
        override fun toString(): String = "@$id|$text"
    }
}