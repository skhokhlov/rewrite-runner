package io.github.skhokhlov.rewriterunner.integration.probe;

import java.nio.file.Files;
import java.io.File;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;

/** A private-repository lifecycle plugin, proving pluginRepositories are available. */
public final class RepositoryProbeMojo extends AbstractMojo {
    private File basedir;
    private String globalSetting;
    @Override
    public void execute() throws MojoExecutionException {
        try {
            if (!"1.0".equals(globalSetting)) {
                throw new IllegalStateException("Global settings profile was not preserved");
            }
            Files.write(basedir.toPath().resolve("repository-plugin-ran"), new byte[] {1});
        } catch (Exception e) {
            throw new MojoExecutionException("Probe could not write receipt", e);
        }
    }
}
