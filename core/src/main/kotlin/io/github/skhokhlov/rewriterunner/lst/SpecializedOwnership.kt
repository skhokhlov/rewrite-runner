package io.github.skhokhlov.rewriterunner.lst

object SpecializedOwnership {
    val extensions = setOf(
        ".hcl",
        ".tf",
        ".tfvars",
        ".proto",
        ".dockerfile",
        ".containerfile"
    )

    val stage0ExcludeGlobs = listOf(
        "**/*.hcl",
        "**/*.tf",
        "**/*.tfvars",
        "**/*.proto",
        "**/*.dockerfile",
        "**/*.containerfile",
        "**/Dockerfile*",
        "**/Containerfile*"
    )
}
