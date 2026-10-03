package io.github.skhokhlov.rewriterunner

import java.security.MessageDigest
import java.util.HexFormat

/** Source identity shared by Maven Stage 0 and the runner's artifact resolvers. */
internal fun repositoryId(url: String): String =
    if (url == "https://repo.maven.apache.org/maven2") {
        "central"
    } else {
        "rewrite-runner-repo-" + HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        )
    }
