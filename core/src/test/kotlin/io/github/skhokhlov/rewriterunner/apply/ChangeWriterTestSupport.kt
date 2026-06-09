package io.github.skhokhlov.rewriterunner.apply

import java.nio.file.Path
import org.openrewrite.InMemoryExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.Result
import org.openrewrite.SourceFile
import org.openrewrite.text.PlainText
import org.openrewrite.text.PlainTextParser

private val plainTextContext = InMemoryExecutionContext {}

internal fun plainText(path: String, text: String): SourceFile = PlainTextParser()
    .parse(plainTextContext, text)
    .map {
        (it as PlainText).withSourcePath(Path.of(path)) as SourceFile
    }.toList()
    .single()

internal fun rewriteResult(path: String, before: String?, after: String?): Result = Result(
    before?.let { plainText(path, it) },
    after?.let { plainText(path, it) },
    emptyList<List<Recipe>>()
)
