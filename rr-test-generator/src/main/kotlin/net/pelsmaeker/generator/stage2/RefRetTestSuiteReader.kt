package net.pelsmaeker.generator.stage2

import net.pelsmaeker.generator.*
import net.pelsmaeker.generator.utils.replaceAll
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/**
 * Reads reference retention test cases from a file.
 *
 * The file should be a Java file with multiple packages and multiple compilation units (as required by Java SPT tests).
 * Relevant references and declarations should be surrounded by `[[ ... ]]`. A declaration starts with an `'@'` symbol,
 * identifier (e.g., a number), followed by a pipe, followed by the name of the declaration. For example, `[[@1|foo]]`
 * is a declaration of a member named `foo`.
 *
 * A reference starts with an arrow, an identifier of the declaration it should resolve to (e.g., a number), followed by
 * a pipe, followed by the input name of the reference, followed by the expected (qualified) name of the reference.
 * If the expected name is omitted, it is assumed to be equal to the input name. Whitespace around the names or code is
 * ignored. For example, `[[->1|foo|B.foo]]` is a reference to the declaration `[[@1|foo]]` in class `B`.
 */
object RefRetTestSuiteReader: TestSuiteReader() {

    /**
     * Reads a test suite from the given text with markers.
     *
     * A declaration should be marked with `[[@id|name]]`, where `id` is an identifier and `name` is the name of the
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
    override fun read(name: String, directory: String, text: String): TestSuite {
        val markers = readMarkers(text) { range, operator, values ->
            when {
                operator.startsWith('@') -> {
                    // Declaration
                    val id = operator.substring(1).trim()
                    val t = values.firstOrNull() ?: error("No name for declaration $operator")
                    return@readMarkers Decl(id, t, range)
                }
                operator.startsWith("->") -> {
                    // Reference
                    val id = operator.substring(2).trim()
                    // Context specifiers
                    val contexts = values.filter { it.startsWith("&") }.map { it.substring(1).trim() }
                    val otherValues = values.filter { !it.startsWith("&") && !it.startsWith("@") && !it.startsWith("->") }
                    val t = otherValues.firstOrNull() ?: error("No initial name for reference $operator")
                    val et = otherValues.drop(1).firstOrNull() ?: t
                    return@readMarkers Ref(id, contexts, t, et, range)
                }
                operator.startsWith("{") -> {
                    // Annotation
                    // Ignored
                    return@readMarkers null
                }
                else -> error("Unknown operator: $operator")
            }
        }
        val refs = markers.filterIsInstance<Ref>()
        val decls = markers.filterIsInstance<Decl>()

        // Build the 'expected text' (the code without any markers and with the expected qualified references).
        // At the same time, convert the markers to JavaIds and build a list of all identifiers in order from first to last.
        val markerToId = mutableMapOf<Marker, Highlight>()
        val highlights = mutableListOf<Highlight>()
        val expectedText = text.replaceAll(refs + decls, { it.range }) { m, _, s ->
            val id = m.toHighlight(s)
            markerToId.compute(m) { _, v -> v?.let { error("Duplicate marker: $it") } ?: id }
            highlights.add(id)
            m.replacementText
        }

        // Process any annotations
        val annotations = annotationRegex.findAll(text).map { it.groups[1]!!.value }.toSet()
        val isDisabled = annotations.contains("disabled")

        // For each reference, find the corresponding declaration and build the test cases.
        val cases = mutableListOf<TestCase>()

        // Parser test
        cases.add(ParseTestCase(
            "$name: parse test",
            isDisabled,
            expectedText,
        ))

        // Analysis tests
        cases.add(AnalysisTestCase(
            "$name: default analysis",
            isDisabled,
            expectedText,
        ))
        cases.add(TestAnalyzeTestCase(
            "$name: test analysis",
            isDisabled,
            expectedText,
        ))

        // Tests for individual references
        refs.forEachIndexed { i, ref ->
            val decl = decls.firstOrNull { it.id == ref.declId } ?: error("No declaration for reference $ref")
            val declIndex = highlights.indexOf(markerToId[decl]).takeIf { it >= 0 } ?: error("Declaration not found: $decl")
            val refIndex = highlights.indexOf(markerToId[ref]).takeIf { it >= 0 } ?: error("Reference not found: $ref")
            val contexts = ref.contextIds.map { ctxId -> decls.first { it.id == ctxId } }
            val contextIndexes = contexts.map { highlights.indexOf(markerToId[it]).takeIf { it >= 0 } ?: error("Context not found: $it") }
            cases.add(RefRetTestCase(
                "$name: refret test ${i + 1}",
                isDisabled,
                expectedText,
                highlights,
                ref.text,
                refIndex,
                declIndex,
                contextIndexes,
            ))
        }

        // TODO: Do something with the contexts

        return TestSuite(
            name = name,
            directory = directory,
            cases = cases,
            isDisabled = isDisabled,
        )
    }

    /** A reference marker. */
    private data class Ref(
        /** The identifier of the declaration to which the reference should resolve. */
        val declId: String,
        /** The identifiers of the context markers. */
        val contextIds: List<String>,
        /** The input text of the reference. */
        val text: String,
        /** The expected text of the reference. */
        val expectedText: String,
        override val range: IntRange,
    ): Marker {
        override val replacementText get() = expectedText
        override fun toString(): String = StringBuilder().apply {
            append("->$declId")
            append(contextIds.joinToString("") { "|&$it" })
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