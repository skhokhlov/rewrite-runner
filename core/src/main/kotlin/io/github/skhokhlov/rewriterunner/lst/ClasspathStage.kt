package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.ParseFailure
import io.github.skhokhlov.rewriterunner.lst.utils.ClasspathResolutionResult
import java.nio.file.Path

/** One stage in the ordered LST classpath-resolution fall-through chain. */
interface ClasspathStage {
    /** Returns null to fall through to the next stage; non-null terminates resolution. */
    fun resolve(
        projectDir: Path,
        parseFailures: MutableList<ParseFailure>
    ): ClasspathResolutionResult?
}
