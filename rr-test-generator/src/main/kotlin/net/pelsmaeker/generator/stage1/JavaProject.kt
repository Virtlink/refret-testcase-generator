package net.pelsmaeker.generator.stage1


/** A Java project. */
data class JavaProject(
    /** The name of the project. */
    val name: String,
    /** The packages in the project. */
    val packages: List<JavaPackage>,
)

/** A Java package. */
data class JavaPackage(
    /** The name of the package. */
    val name: String,
    /** The compilation units in the package. */
    val units: List<JavaUnit>,
)

/** A Java compilation unit. */
data class JavaUnit(
    /** The name of the compilation unit. */
    val name: String,
    /** The text content of the file. */
    val text: String,
)