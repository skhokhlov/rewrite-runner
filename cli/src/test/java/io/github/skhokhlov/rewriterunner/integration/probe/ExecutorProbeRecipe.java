package io.github.skhokhlov.rewriterunner.integration.probe;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test-only recipe packaged into a temporary Maven artifact by the real-plugin suite. It writes
 * one compact row from the JVM that actually executes the OpenRewrite recipe.
 */
public class ExecutorProbeRecipe extends Recipe {
    private static final AtomicBoolean RECORDED = new AtomicBoolean();

    @Option(
            displayName = "Probe output file",
            description = "Where the test-only JVM probe writes its row.",
            example = "/tmp/rewrite-runner-probe.csv"
    )
    private String outputFile;

    @Override
    public String getDisplayName() {
        return "Executor JVM probe";
    }

    @Override
    public String getDescription() {
        return "Records the JVM executing this recipe for integration-test verification.";
    }

    public String getOutputFile() {
        return outputFile;
    }

    public void setOutputFile(String outputFile) {
        this.outputFile = outputFile;
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        if (RECORDED.compareAndSet(false, true)) {
            record();
        }
        return TreeVisitor.noop();
    }

    private void record() {
        if (outputFile == null || outputFile.isBlank()) {
            throw new IllegalStateException("outputFile must be configured for ExecutorProbeRecipe");
        }
        String row = ProcessHandle.current().pid()
                + "|"
                + Runtime.getRuntime().maxMemory()
                + "|"
                + String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments())
                + System.lineSeparator();
        try {
            Files.writeString(
                    Path.of(outputFile),
                    row,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            throw new IllegalStateException("Could not write executor probe output", e);
        }
    }
}
