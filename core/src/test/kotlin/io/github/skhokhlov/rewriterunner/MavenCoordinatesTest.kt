package io.github.skhokhlov.rewriterunner

import io.kotest.core.spec.style.FunSpec
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MavenCoordinatesTest :
    FunSpec({

        test("tryParse returns a DefaultArtifact for a well-formed coordinate") {
            val artifact = MavenCoordinates.tryParse("com.example:lib:1.2.3")
            assertNotNull(artifact, "Well-formed coordinate should parse")
            assertEquals("com.example", artifact.groupId)
            assertEquals("lib", artifact.artifactId)
            assertEquals("1.2.3", artifact.version)
        }

        test("tryParse returns null for a coordinate with an illegal URI character") {
            // Space in artifactId — DefaultArtifact runs URI.create internally and rejects it.
            val artifact = MavenCoordinates.tryParse("com.example:bad name:1.0")
            assertNull(artifact, "Coordinate with illegal URI char must return null")
        }

        test("tryParse returns null for a structurally malformed coordinate") {
            // Single segment — not a valid groupId:artifactId[:version] form.
            val artifact = MavenCoordinates.tryParse("notacoord")
            assertNull(artifact, "Structurally malformed coordinate must return null")
        }

        test("tryParse handles blank input by returning null") {
            assertNull(MavenCoordinates.tryParse(""))
        }
    })
