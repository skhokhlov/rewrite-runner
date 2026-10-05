package io.github.skhokhlov.rewriterunner.lst;

import io.github.skhokhlov.rewriterunner.AetherContext;
import io.github.skhokhlov.rewriterunner.RunnerLogger;
import io.github.skhokhlov.rewriterunner.config.ToolConfig;
import java.nio.file.Path;

/** Compile-time coverage of the constructor available to existing Java clients. */
public final class JavaLstBuilderFixture {
    private JavaLstBuilderFixture() {}

    public static LstBuilder create(
            RunnerLogger logger,
            Path cacheDir,
            ToolConfig toolConfig,
            AetherContext aetherContext,
            ProjectBuildStage projectBuildStage,
            DependencyResolutionStage dependencyResolutionStage,
            BuildFileParseStage buildFileParseStage) {
        return new LstBuilder(
                logger, cacheDir, toolConfig, aetherContext,
                projectBuildStage, dependencyResolutionStage, buildFileParseStage);
    }
}
