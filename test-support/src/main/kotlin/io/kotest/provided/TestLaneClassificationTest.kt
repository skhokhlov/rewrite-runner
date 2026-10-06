package io.kotest.provided

import io.kotest.core.spec.style.FunSpec
import kotlin.test.assertFailsWith

class TestLaneClassificationTest :
    FunSpec({
        test("ordinary specs remain untagged by default") {
            validateLaneClassification("example.DefaultSpec", emptyList())
        }
        test("every supported lane is valid") {
            listOf("integration", "worker", "real-plugin", "container").forEach {
                validateLaneClassification("example.Spec", listOf(it))
            }
        }
        test("misspelled and OS tags fail rather than falling into the default lane") {
            listOf("intergation", "windows", "default", "").forEach {
                assertFailsWith<IllegalStateException> {
                    validateLaneClassification("example.Spec", listOf(it))
                }
            }
        }
        test("a spec cannot belong to multiple lanes") {
            assertFailsWith<IllegalStateException> {
                validateLaneClassification("example.Spec", listOf("integration", "worker"))
            }
        }
        test("integration package specs require a class lane tag") {
            assertFailsWith<IllegalStateException> {
                validateLaneClassification(
                    "io.github.skhokhlov.rewriterunner.integration.NewSpec",
                    emptyList()
                )
            }
            validateLaneClassification(
                "io.github.skhokhlov.rewriterunner.integration.NewSpec",
                listOf("worker")
            )
        }
        test("integration class names do not choose a lane") {
            validateLaneClassification("example.NewIntegrationTest", emptyList())
        }
    })
