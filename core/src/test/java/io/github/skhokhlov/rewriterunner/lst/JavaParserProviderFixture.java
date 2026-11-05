package io.github.skhokhlov.rewriterunner.lst;

import io.github.skhokhlov.rewriterunner.RewriteRunner;
import java.util.Map;
import java.util.Set;
import org.openrewrite.Parser;
import org.openrewrite.text.PlainTextParser;

/** Compile-time and runtime coverage of the library API from Java. */
public final class JavaParserProviderFixture implements ParserProvider {
    @Override
    public ParserFileSelection defaultSelection() {
        return new ParserFileSelection(Set.of(".javalang"));
    }

    @Override
    public Parser create(ParserContext context, Map<String, String> options) {
        return PlainTextParser.builder().build();
    }

    public static RewriteRunner.Builder configure(RewriteRunner.Builder builder) {
        return builder.configure(config ->
                config.registerParser("java-client-language", JavaParserProviderFixture.class));
    }
}
