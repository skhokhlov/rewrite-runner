package io.github.skhokhlov.rewriterunner.plugin

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PatchParserTest :
    FunSpec({
        test("splits git patch into per-file diffs") {
            val patch =
                """
                diff --git a/src/A.java b/src/A.java
                index 1111111..2222222 100644
                --- a/src/A.java
                +++ b/src/A.java
                @@ -1 +1 @@
                -class A {}
                +class A { }
                diff --git a/src/B.java b/src/B.java
                index 3333333..4444444 100644
                --- a/src/B.java
                +++ b/src/B.java
                @@ -1 +1 @@
                -class B {}
                +class B { }
                """.trimIndent()

            val diffs = PatchParser.parse(patch)

            assertEquals(setOf(Path.of("src/A.java"), Path.of("src/B.java")), diffs.keys)
            assertTrue(diffs.getValue(Path.of("src/A.java")).contains("+class A { }"))
            assertTrue(diffs.getValue(Path.of("src/B.java")).contains("+class B { }"))
        }

        test("uses plus header path for newly created file") {
            val patch =
                """
                diff --git a/new.txt b/new.txt
                new file mode 100644
                index 0000000..1111111
                --- /dev/null
                +++ b/new.txt
                @@ -0,0 +1 @@
                +hello
                """.trimIndent()

            val diffs = PatchParser.parse(patch)

            assertEquals(setOf(Path.of("new.txt")), diffs.keys)
        }

        test("uses minus header path for deleted file") {
            val patch =
                """
                diff --git a/old.txt b/old.txt
                deleted file mode 100644
                index 1111111..0000000
                --- a/old.txt
                +++ /dev/null
                @@ -1 +0,0 @@
                -hello
                """.trimIndent()

            val diffs = PatchParser.parse(patch)

            assertEquals(setOf(Path.of("old.txt")), diffs.keys)
        }

        test("empty patch returns empty map") {
            assertEquals(emptyMap(), PatchParser.parse(""))
        }
    })
