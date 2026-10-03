package io.github.skhokhlov.rewriterunner.maven;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;

/** Stops Maven before executing any build goal if settings injection failed. */
public final class RepositoryLifecycleGuard extends AbstractMavenLifecycleParticipant {
    @Override
    public void afterSessionStart(MavenSession session) throws MavenExecutionException {
        Path config = Paths.get(System.getProperty("rewrite.runner.repositories"));
        if (!Files.exists(config.resolveSibling("repositories-ready"))) {
            throw new MavenExecutionException("Maven repository integration did not initialize", (Throwable) null);
        }
    }
}
