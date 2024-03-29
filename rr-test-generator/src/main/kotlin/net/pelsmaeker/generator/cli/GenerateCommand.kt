package net.pelsmaeker.generator.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.path
import net.pelsmaeker.generator.RefRetTestCase
import net.pelsmaeker.generator.TestKind
import net.pelsmaeker.generator.stage2.RefRetTestSuiteReader
import net.pelsmaeker.generator.stage2.RefactoringTestSuiteReader
import net.pelsmaeker.generator.stage2.SptTestGenerator
import java.nio.file.Files
import java.nio.file.Path

/** Generate SPT test files command. */
class GenerateCommand: CliktCommand(
    name = "generate",
    help = "Generate reference retention SPT test files"
) {

    /** The main kind of tests to generate. */
    val kind by argument()
        .enum<TestKind>()
        .default(TestKind.RefRet)

    /** The input paths. These directories are searched for subdirectories and files. */
    private val inputs: List<Path> by argument(help="Directory with input directories and files")
        .path(mustExist = true, canBeFile = false, canBeDir = true, mustBeReadable = true)
        .multiple()

    /** The output path. This directory is used to create subdirectories and write the generated test suite files. */
    private val output: Path by option("-o", "--out", help="Directory for output directories and files")
        .path(mustExist = false, canBeFile = false, canBeDir = true)
        .required()

    /** The module prefix for SPT tests. */
    private val modulePrefix: String by option("--module", help="Module prefix for SPT tests")
        .default("refret")

    /** Whether to force overwriting existing generated files. */
    private val force: Boolean by option("-f", "--force", help="Force overwrite of existing files")
        .flag(default = false)

    /** Whether to also include test that have no references/declarations. */
    private val all: Boolean by option("-a", "--all", help="Include empty tests")
        .flag(default = false)

    /** The kinds of SPT tests to generate. */
    private val kinds: List<TestKind> by option("-k", "--kind", help="Kind of test to generate")
        .enum<TestKind>()
        .multiple(default = listOf(
            // All
            TestKind.Parsing,
            TestKind.Analysis,
            TestKind.RefRet,
            TestKind.MoveClass,
        ))

    override fun run() {
        // Gather all test suites
        val testSuites = inputs.flatMap { input ->
            Cli.info("Finding test suites in: $input")
            when (kind) {
                TestKind.RefRet -> RefRetTestSuiteReader.readAll(input, input)
                TestKind.MoveClass -> RefactoringTestSuiteReader.readAll(input, input)
                else -> {
                    Cli.warn("Test kind $kind not yet supported.")
                    emptyList()
                }
            }
        }
        Cli.info("Found ${testSuites.size} test suites.")
        val actualSuites = if (all) testSuites else {
            testSuites.filter { it.cases.any { case -> case.isSpecial } }
        }
        if (actualSuites.size != testSuites.size) {
            Cli.warn("Filtered out ${testSuites.size - actualSuites.size} empty test suites.")
        }

        if (actualSuites.isNotEmpty()) {
            // Ensure the output directory exists
            Cli.info("Creating output directory: $output")
            Files.createDirectories(output)

            // Write each SPT test out to a file
            Cli.info("Generating SPT test files in: $output")
            var count = 0
            for (testSuite in actualSuites) {
                for (kind in kinds) {
                    if (testSuite.cases.none { it.isAcceptable(listOf(kind)) }) continue
                    SptTestGenerator.writeToFile(modulePrefix, kind.name.lowercase(), testSuite, output, force, kind)
                    count += 1
                }
                Cli.info("  ${testSuite.name}")
            }
            Cli.info("Generated $count SPT test files for ${actualSuites.size} tests.")
        }

        Cli.info("Done!")
    }
}