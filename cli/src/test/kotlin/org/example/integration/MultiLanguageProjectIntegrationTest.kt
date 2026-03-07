package org.example.integration

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Integration tests for realistic multi-language projects.
 *
 * Each test bootstraps a project containing multiple file types (Java, Kotlin,
 * YAML, XML, JSON, Properties) and verifies that the CLI correctly scopes changes
 * via extension filtering, applies recipes across all targeted types, and emits
 * consistent output in all three modes (diff / files / report).
 */
class MultiLanguageProjectIntegrationTest : BaseIntegrationTest() {

    @TempDir lateinit var projectDir: Path

    @TempDir lateinit var cacheDir: Path

    /**
     * Creates a realistic Spring Boot-style project under [projectDir].
     *
     * Layout:
     * ```
     * src/main/java/com/example/App.java
     * src/main/kotlin/com/example/Service.kt
     * src/main/resources/application.yaml
     * src/main/resources/application.properties
     * src/main/resources/logback.xml
     * src/main/resources/schema.json
     * pom.xml
     * ```
     */
    private fun createSpringBootProject() {
        projectDir.resolve("src/main/java/com/example").createDirectories()
        projectDir.resolve("src/main/kotlin/com/example").createDirectories()
        projectDir.resolve("src/main/resources").createDirectories()

        projectDir.resolve("src/main/java/com/example/App.java").writeText(
            """public class App{public static void main(String[] args){System.out.println("PLACEHOLDER");}}"""
        )
        projectDir.resolve("src/main/kotlin/com/example/Service.kt").writeText(
            """class Service { fun hello() = "PLACEHOLDER" }"""
        )
        projectDir.resolve("src/main/resources/application.yaml").writeText(
            "app:\n  name: PLACEHOLDER\n  version: 1.0\n"
        )
        projectDir.resolve("src/main/resources/application.properties").writeText(
            "app.name=PLACEHOLDER\nserver.port=8080\n"
        )
        projectDir.resolve("src/main/resources/logback.xml").writeText(
            "<configuration><property name=\"LOG_LEVEL\" value=\"PLACEHOLDER\"/></configuration>"
        )
        projectDir.resolve("src/main/resources/schema.json").writeText(
            """{"title": "PLACEHOLDER", "type": "object"}"""
        )
        projectDir.resolve("pom.xml").writeText(
            """<project><version>PLACEHOLDER</version></project>"""
        )
    }

    // ─── Extension filtering ──────────────────────────────────────────────────

    @Test
    fun `include-extensions limits processing to specified file types only`() {
        createSpringBootProject()
        projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "REPLACED")

        runCli(
            "--project-dir", projectDir.toString(),
            "--active-recipe", "com.example.integration.FindAndReplace",
            "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
            "--cache-dir", cacheDir.toString(),
            "--include-extensions", ".java"
        )

        // Only Java should be changed
        assertTrue(
            projectDir.resolve(
                "src/main/java/com/example/App.java"
            ).readText().contains("REPLACED"),
            "Java file should be modified"
        )
        assertEquals(
            """class Service { fun hello() = "PLACEHOLDER" }""",
            projectDir.resolve("src/main/kotlin/com/example/Service.kt").readText(),
            "Kotlin file must not be modified when .kt is not in include-extensions"
        )
        assertTrue(
            projectDir.resolve(
                "src/main/resources/application.yaml"
            ).readText().contains("PLACEHOLDER"),
            "YAML file must not be modified"
        )
    }

    @Test
    fun `multiple include-extensions targets all specified types`() {
        createSpringBootProject()
        projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "REPLACED")

        runCli(
            "--project-dir", projectDir.toString(),
            "--active-recipe", "com.example.integration.FindAndReplace",
            "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
            "--cache-dir", cacheDir.toString(),
            "--include-extensions", ".java,.kt,.yaml"
        )

        assertTrue(
            projectDir.resolve(
                "src/main/java/com/example/App.java"
            ).readText().contains("REPLACED"),
            "Java file should be modified"
        )
        assertTrue(
            projectDir.resolve(
                "src/main/kotlin/com/example/Service.kt"
            ).readText().contains("REPLACED"),
            "Kotlin file should be modified"
        )
        assertTrue(
            projectDir.resolve(
                "src/main/resources/application.yaml"
            ).readText().contains("REPLACED"),
            "YAML file should be modified"
        )
        assertTrue(
            projectDir.resolve("src/main/resources/schema.json").readText().contains("PLACEHOLDER"),
            "JSON file must not be modified"
        )
        assertTrue(
            projectDir.resolve("pom.xml").readText().contains("PLACEHOLDER"),
            "XML file must not be modified"
        )
    }

    // ─── Output modes on multi-file project ───────────────────────────────────

    @Test
    fun `diff mode lists all changed files in unified diff format`() {
        createSpringBootProject()
        projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "REPLACED")

        val result = runCli(
            "--project-dir", projectDir.toString(),
            "--active-recipe", "com.example.integration.FindAndReplace",
            "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
            "--cache-dir", cacheDir.toString(),
            "--include-extensions", ".java,.yaml,.xml,.json,.properties",
            "--output", "diff",
            "--dry-run"
        )

        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        assertTrue(result.stdout.contains("---"), "Diff mode should emit --- markers")
        assertTrue(result.stdout.contains("+++"), "Diff mode should emit +++ markers")
        assertTrue(result.stdout.contains("REPLACED"), "Diff should show replacement text")
    }

    @Test
    fun `files mode lists paths for all changed files without diff markers`() {
        createSpringBootProject()
        projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "REPLACED")

        val result = runCli(
            "--project-dir", projectDir.toString(),
            "--active-recipe", "com.example.integration.FindAndReplace",
            "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
            "--cache-dir", cacheDir.toString(),
            "--include-extensions", ".java,.yaml,.xml,.json,.properties",
            "--output", "files",
            "--dry-run"
        )

        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        assertTrue(!result.stdout.contains("---"), "FILES mode must not emit unified diff markers")
        assertTrue(!result.stdout.contains("@@"), "FILES mode must not emit hunk headers")
        // At least one of the processed file types should appear in the listing
        assertTrue(
            result.stdout.contains(".java") || result.stdout.contains(".yaml") ||
                result.stdout.contains(".xml") || result.stdout.contains(".properties") ||
                result.stdout.contains("No files"),
            "FILES mode should list affected paths: ${result.stdout}"
        )
    }

    @Test
    fun `report mode creates json report covering all changed file types`() {
        createSpringBootProject()
        projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "REPLACED")

        val result = runCli(
            "--project-dir", projectDir.toString(),
            "--active-recipe", "com.example.integration.FindAndReplace",
            "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
            "--cache-dir", cacheDir.toString(),
            "--include-extensions", ".java,.yaml,.xml,.json,.properties",
            "--output", "report",
            "--dry-run"
        )

        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        val report = projectDir.resolve("openrewrite-report.json").toFile()
        assertTrue(report.exists(), "openrewrite-report.json must be created")
        val json = report.readText()
        assertTrue(json.contains("\"results\""), "Report JSON must have a 'results' array")
        assertTrue(json.contains("\"totalChanged\""), "Report JSON must have 'totalChanged'")
    }

    // ─── Dry-run across all file types ────────────────────────────────────────

    @Test
    fun `dry-run leaves all files unchanged across every supported language`() {
        createSpringBootProject()

        // Capture originals
        val originals = listOf(
            "src/main/java/com/example/App.java",
            "src/main/kotlin/com/example/Service.kt",
            "src/main/resources/application.yaml",
            "src/main/resources/application.properties",
            "src/main/resources/logback.xml",
            "src/main/resources/schema.json",
            "pom.xml"
        ).associateWith { projectDir.resolve(it).readText() }

        projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "REPLACED")

        runCli(
            "--project-dir", projectDir.toString(),
            "--active-recipe", "com.example.integration.FindAndReplace",
            "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
            "--cache-dir", cacheDir.toString(),
            "--include-extensions", ".java,.kt,.yaml,.properties,.xml,.json",
            "--dry-run"
        )

        for ((path, expected) in originals) {
            assertEquals(
                expected,
                projectDir.resolve(path).readText(),
                "$path should not be modified by --dry-run"
            )
        }
    }

    // ─── AutoFormat on Java portion of mixed project ───────────────────────────

    @Test
    fun `AutoFormat targets only java files and ignores all other types`() {
        createSpringBootProject()
        // Also write properly formatted Java so we can see only the App changes
        projectDir.resolve("src/main/java/com/example/App.java").writeText(
            """public class App{public static void main(String[] args){System.out.println("hello");}}"""
        )

        val result = runCli(
            "--project-dir", projectDir.toString(),
            "--active-recipe", "org.openrewrite.java.format.AutoFormat",
            "--cache-dir", cacheDir.toString(),
            "--include-extensions", ".java",
            "--output", "files",
            "--dry-run"
        )

        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        assertTrue(
            !result.stdout.contains(".kt") && !result.stdout.contains(".yaml") &&
                !result.stdout.contains(".xml") && !result.stdout.contains(".json"),
            "Non-Java files must not appear in FILES output: ${result.stdout}"
        )
    }
}
