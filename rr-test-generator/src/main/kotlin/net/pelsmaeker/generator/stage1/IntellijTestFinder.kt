package net.pelsmaeker.generator.stage1

import net.pelsmaeker.generator.cli.Cli
import java.nio.file.Path
import kotlin.io.path.*

/** Finds Java projects in the Intellij refactoring test directory. */
object IntellijTestFinder {

    /**
     * Finds all Java projects in the given directory structure.
     *
     * @param directory the directory structure
     * @param root the root path relative to which the name of the test is determined
     * @return a list of Java projects
     */
    fun findAllJavaProjects(directory: Path, root: Path): List<JavaProject> {
        val entries = directory.listDirectoryEntries()

        return if (entries.any { it.name == "before" } || entries.any { it.name == "after" }) {
            // If the directory contains a directory `before` and/or a directory `after`, we have a java project in each directory
            val remainingEntries = entries.filter { !it.isDirectory() }
            if (remainingEntries.isNotEmpty()) {
                Cli.warn {
                    "Directory with `before`/`after` directories also contains files, skipped:\n  " +
                    remainingEntries.joinToString("\n  ")
                }
            }
            entries.filter { it.isDirectory() }.map { readJavaProjectFromDirectory(it, root, false) }
        } else if (entries.any { it.name.endsWith("_after.java") } || entries.any { it.name.endsWith(".java.after") }) {
            // If the directory contains one or more files that end in `.java` and `_after.java`/`.java.after`, then each is a java project
            val entriesAndProjects = entries.map { it to readJavaProjectFromFile(it, root) }
            val skippedEntries = entriesAndProjects.filter { it.second == null }.map { it.first }
            if (skippedEntries.isNotEmpty()) {
                Cli.warn {
                    "Test suite name could not be determined from file, skipped:\n  " +
                    skippedEntries.joinToString("\n  ")
                }
            }
            entriesAndProjects.mapNotNull { it.second }
        } else {
            // Recurse if it is a directory.
            entries.filter { it.isDirectory() }.flatMap { entry -> findAllJavaProjects(entry, root) }
        }
    }

    /**
     * Reads a Java project from a single file with the naming pattern `Class_testName_in.java`
     * or `Class_testName_out.java`.
     *
     * @param file the file path
     * @param root the root path relative to which the name of the test is determined
     * @return the Java project; or `null` if it was skipped
     */
    fun readJavaProjectFromFile(file: Path, root: Path): JavaProject? {
        val (testName, testQualifier) = getTestSuiteName(file.fileName.toString()) ?: return null  // Skipped

        val text = file.readText()
        val packageName = getPackageName(text) ?: ""
        val unitName = getUnitName(text) ?: "Test"

        val pathComponents = root.relativize(file).map { it.toString() }.toList()
        val testDir = pathComponents.dropLast(1).joinToString("/")

        return JavaProject(
            testName,
            testQualifier,
            testDir,
            listOf(JavaPackage(
                    packageName,
                    listOf(JavaUnit(
                            unitName,
                            text,
                    ))
            ))
        )
    }

    /**
     * Reads a Java project from the files in a directory, such as an `out` directory.
     *
     * @param directory the directory path, ending with a directory such as `out`
     * @param root the root path relative to which the name of the test is determined
     * @param flat `true` when all the files are directly in the directory,
     * `false` when they are in subdirectories named after the packages
     * @return the Java project
     */
    @OptIn(ExperimentalPathApi::class)
    fun readJavaProjectFromDirectory(directory: Path, root: Path, flat: Boolean): JavaProject {
        val packages = mutableMapOf<String, MutableList<JavaUnit>>()
        directory.walk().forEach { javaFile ->
            if (javaFile.isDirectory() || !javaFile.name.endsWith(".java")) return@forEach

            val text = javaFile.readText()
            val packageName = if (flat) { getPackageName(text) ?: "" } else { directory.relativize(javaFile).toList().dropLast(1).joinToString(".") }
            val unitsInPackage = packages.computeIfAbsent(packageName) { mutableListOf() }
            unitsInPackage.add(JavaUnit(javaFile.fileName.nameWithoutExtension, text))
        }

        val pathComponents = root.relativize(directory).map { it.toString() }.toList()
        val testDir = pathComponents.dropLast(2).joinToString("/")
        val testName = pathComponents.dropLast(1).last()
        val testQualifier = pathComponents.last()

        return JavaProject(
            testName,
            testQualifier,
            testDir,
            packages.map { (packageName, units) ->
                JavaPackage(packageName, units)
            }
        )
    }

    /** Regex for finding the package name in a Java file. */
    private val packageRegex = Regex("""^\s*package\s+([^;]+);\s*${'$'}""", RegexOption.MULTILINE)
    /** Regex for finding the name of the first public class in the Java file. */
    private val unitRegex = Regex("""^\s*public\s+class\s+(\w+)""", RegexOption.MULTILINE)

    /**
     * Reads the Java package name from the Java code.
     *
     * @return the package name; or `null` if it could not be determined
     */
    private fun getPackageName(text: String): String? {
        return packageRegex.find(text)?.let { it.groups[1]?.value }
    }

    /**
     * Reads the name of the first public class in the Java code.
     *
     * @return the class name; or `null` if it could not be determined
     */
    private fun getUnitName(text: String): String? {
        return unitRegex.find(text)?.let { it.groups[1]?.value }
    }


    /**
     * Determines the unit name and test suite name and qualifier.
     *
     * @param filename the filename to parse
     * @return the name and qualifier; or `null` if they could not be determined
     */
    private fun getTestSuiteName(filename: String): Pair<String, String?>? {
        return when {
            filename.endsWith("_after.java") -> filename.removeSuffix("_after.java") to "after"
            filename.endsWith(".java.after") -> filename.removeSuffix(".java.after") to "after"
            filename.endsWith("_in.java") -> filename.removeSuffix("_in.java") to "in"
            filename.endsWith("_out.java") -> filename.removeSuffix("_out.java") to "out"
            filename.endsWith(".java") -> filename.removeSuffix(".java") to null
            else -> null
        }
    }
}