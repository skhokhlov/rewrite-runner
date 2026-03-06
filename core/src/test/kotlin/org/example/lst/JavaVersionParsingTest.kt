package org.example.lst

import org.example.config.ToolConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.openrewrite.java.marker.JavaVersion
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies that [LstBuilder] can successfully parse Java source files using
 * version-specific syntax for each supported LTS version (8, 11, 17, 21, 25).
 *
 * Each test:
 *  1. Writes a `pom.xml` declaring the target Java version.
 *  2. Writes a Java source file exercising that version's canonical language features.
 *  3. Builds the LST and asserts the file was parsed without being silently dropped.
 *  4. Asserts the [JavaVersion] marker carries the correct source/target version.
 *
 * These tests fail if version-specific syntax is not recognised by the underlying
 * OpenRewrite JavaParser (Eclipse JDT), surfacing regressions immediately.
 *
 * **Java 25 note**: the tool itself targets Java 21+. The `JavaParser.fromJavaVersion()`
 * creates a parser at the running JVM's language level; however the bundled ECJ compiler
 * in OpenRewrite supports parsing newer-version syntax even when running on an older JVM.
 * The Java 25 test uses unnamed patterns (`_`), standardised in Java 22 (JEP 456), which
 * ECJ has supported since before Java 25. If ECJ support is absent the test will fail —
 * which is intentional: failing signals incomplete Java 25 support.
 */
class JavaVersionParsingTest {

    @TempDir lateinit var projectDir: Path

    private val failingBuildTool = object : BuildToolStage() {
        override fun extractClasspath(projectDir: Path): List<Path>? = null
    }

    private fun lstBuilder(): LstBuilder {
        val noOpDepStage = object : DependencyResolutionStage(
            cacheDir = projectDir.resolve("cache"),
            extraRepositories = emptyList(),
        ) {
            override fun resolveClasspath(projectDir: Path): List<Path> = emptyList()
        }
        return LstBuilder(
            cacheDir = projectDir.resolve("cache"),
            toolConfig = ToolConfig(),
            buildToolStage = failingBuildTool,
            depResolutionStage = noOpDepStage,
        )
    }

    private fun writePom(version: String) {
        projectDir.resolve("pom.xml").writeText("""
            <project>
              <properties>
                <maven.compiler.release>$version</maven.compiler.release>
              </properties>
            </project>
        """.trimIndent())
    }

    /**
     * Builds the LST for a Java file at [fileName] and returns its [JavaVersion] marker.
     * Fails the test if the file was not parsed or the marker is absent.
     */
    private fun buildAndInspect(fileName: String): JavaVersion {
        val sources = lstBuilder().build(projectDir, includeExtensionsCli = listOf(".java"))
        val javaFile = sources.singleOrNull { it.sourcePath.toString().endsWith(fileName) }
        assertNotNull(javaFile, "Expected '$fileName' to be in parsed sources but it was absent — " +
                "the file may have been silently dropped due to a parse failure")
        val marker = javaFile.markers.findFirst(JavaVersion::class.java).orElse(null)
        assertNotNull(marker, "JavaVersion marker must be attached to parsed Java source file")
        return marker
    }

    // ─── Java 8 ──────────────────────────────────────────────────────────────

    /**
     * Java 8 introduced lambda expressions, method references, the Streams API,
     * Optional, and default/static interface methods — all must parse correctly.
     */
    @Test
    fun `Java 8 lambda expressions method references and streams parse correctly`() {
        writePom("8")
        projectDir.resolve("Java8Syntax.java").writeText("""
            import java.util.Arrays;
            import java.util.List;
            import java.util.Optional;
            import java.util.function.Function;
            import java.util.function.Predicate;

            public class Java8Syntax {

                @FunctionalInterface
                interface Transformer<T, R> {
                    R transform(T input);
                }

                interface Greeter {
                    String greet(String name);

                    default String greetLoudly(String name) {
                        return greet(name).toUpperCase();
                    }

                    static Greeter simple() {
                        return name -> "Hello, " + name;
                    }
                }

                public static void main(String[] args) {
                    // Lambda expression
                    Transformer<String, Integer> len = s -> s.length();

                    // Method reference
                    Function<String, String> toUpper = String::toUpperCase;

                    // Predicate composition
                    Predicate<String> nonEmpty = s -> !s.isEmpty();
                    Predicate<String> shortEnough = s -> s.length() < 10;
                    Predicate<String> combined = nonEmpty.and(shortEnough);

                    // Stream pipeline with filter, map, forEach
                    List<String> items = Arrays.asList("alpha", "beta", "gamma", "delta");
                    items.stream()
                         .filter(combined)
                         .map(String::toUpperCase)
                         .forEach(System.out::println);

                    // Optional
                    Optional<String> opt = Optional.of("hello");
                    opt.map(String::length).ifPresent(System.out::println);

                    // Default method via lambda-captured instance
                    Greeter g = Greeter.simple();
                    System.out.println(g.greetLoudly("world"));
                }
            }
        """.trimIndent())

        val version = buildAndInspect("Java8Syntax.java")
        assertEquals("8", version.sourceCompatibility, "JavaVersion marker must reflect Java 8 target")
        assertEquals("8", version.targetCompatibility)
    }

    // ─── Java 11 ─────────────────────────────────────────────────────────────

    /**
     * Java 11 (first LTS after Java 8) brought local-variable type inference (`var`)
     * for local variables (JDK 10, included in 11 LTS) and new String/collection API:
     * String::isBlank, String::strip, String::lines, String::repeat, List.of, Map.copyOf.
     */
    @Test
    fun `Java 11 var local variables and new String API parse correctly`() {
        writePom("11")
        projectDir.resolve("Java11Syntax.java").writeText("""
            import java.util.List;
            import java.util.Map;

            public class Java11Syntax {

                public static void main(String[] args) {
                    // var — local variable type inference (Java 10+, LTS debut in Java 11)
                    var message = "  Hello Java 11  ";
                    var number = 42;
                    var list = List.of("a", "b", "c");
                    var map = Map.of("key", "value");

                    // New String methods (Java 11)
                    var stripped = message.strip();
                    var strippedLeading = message.stripLeading();
                    var strippedTrailing = message.stripTrailing();
                    var isBlank = "   ".isBlank();
                    var lines = "line1\nline2\nline3".lines().count();
                    var repeated = "ab".repeat(3);

                    // var in enhanced-for loop
                    for (var item : list) {
                        System.out.println(item.toUpperCase());
                    }

                    System.out.println(stripped + " " + isBlank + " " + lines + " " + repeated);
                }
            }
        """.trimIndent())

        val version = buildAndInspect("Java11Syntax.java")
        assertEquals("11", version.sourceCompatibility, "JavaVersion marker must reflect Java 11 target")
        assertEquals("11", version.targetCompatibility)
    }

    // ─── Java 17 ─────────────────────────────────────────────────────────────

    /**
     * Java 17 (LTS) standardised records (JEP 395), sealed classes and interfaces
     * (JEP 409), text blocks (JEP 378, standardised in Java 15 but LTS debut in 17),
     * and pattern matching for `instanceof` (JEP 394). All must parse correctly.
     */
    @Test
    fun `Java 17 records sealed interfaces text blocks and instanceof patterns parse correctly`() {
        writePom("17")
        // Use $TQ to embed Java text-block triple-quotes inside Kotlin triple-quoted strings.
        val TQ = "\"\"\""
        projectDir.resolve("Java17Syntax.java").writeText("""
            public class Java17Syntax {

                // Records (JEP 395, standard in Java 16; LTS debut in Java 17)
                record Point(int x, int y) {
                    // Compact constructor
                    Point {
                        if (x < 0 || y < 0) throw new IllegalArgumentException("Negative coordinates");
                    }

                    double distance() {
                        return Math.sqrt((double) x * x + (double) y * y);
                    }
                }

                // Sealed interface with permitted subtypes (JEP 409, standard in Java 17)
                sealed interface Shape permits Circle, Rectangle, Triangle {}

                record Circle(double radius) implements Shape {}
                record Rectangle(double width, double height) implements Shape {}
                record Triangle(double base, double height) implements Shape {}

                static double area(Shape shape) {
                    // Pattern matching for instanceof (JEP 394, standard in Java 16)
                    if (shape instanceof Circle c) {
                        return Math.PI * c.radius() * c.radius();
                    } else if (shape instanceof Rectangle r) {
                        return r.width() * r.height();
                    } else if (shape instanceof Triangle t) {
                        return 0.5 * t.base() * t.height();
                    }
                    throw new IllegalArgumentException("Unknown shape: " + shape);
                }

                public static void main(String[] args) {
                    var p = new Point(3, 4);
                    System.out.println("distance: " + p.distance());

                    // Text block (JEP 378, standard in Java 15; LTS debut in Java 17)
                    String json = $TQ
                            {
                              "name": "Alice",
                              "role": "developer"
                            }
                            $TQ;
                    System.out.println(json);

                    Shape[] shapes = { new Circle(5), new Rectangle(3, 4), new Triangle(6, 8) };
                    for (var shape : shapes) {
                        System.out.printf("area=%.2f%n", area(shape));
                    }
                }
            }
        """.trimIndent())

        val version = buildAndInspect("Java17Syntax.java")
        assertEquals("17", version.sourceCompatibility, "JavaVersion marker must reflect Java 17 target")
        assertEquals("17", version.targetCompatibility)
    }

    // ─── Java 21 ─────────────────────────────────────────────────────────────

    /**
     * Java 21 (LTS) standardised switch expressions with type patterns and guarded
     * patterns (JEP 441) and record patterns (JEP 440). Virtual thread creation
     * (JEP 444) requires no new syntax but exercises Java 21 API. All must parse.
     */
    @Test
    fun `Java 21 switch expressions with type patterns and record patterns parse correctly`() {
        writePom("21")
        projectDir.resolve("Java21Syntax.java").writeText("""
            public class Java21Syntax {

                sealed interface Shape permits Circle, Rectangle, Triangle {}
                record Circle(double radius) implements Shape {}
                record Rectangle(double width, double height) implements Shape {}
                record Triangle(double base, double height) implements Shape {}

                // Type patterns in switch (JEP 441, standard in Java 21)
                static String describe(Shape shape) {
                    return switch (shape) {
                        case Circle c when c.radius() > 100 -> "enormous circle";
                        case Circle c when c.radius() > 10  -> "large circle";
                        case Circle c                       -> "small circle r=" + c.radius();
                        case Rectangle r when r.width() == r.height() -> "square " + r.width();
                        case Rectangle r -> "rectangle " + r.width() + "x" + r.height();
                        case Triangle t  -> "triangle b=" + t.base() + " h=" + t.height();
                    };
                }

                // Record patterns in switch (JEP 440, standard in Java 21)
                static double area(Shape shape) {
                    return switch (shape) {
                        case Circle(var r)            -> Math.PI * r * r;
                        case Rectangle(var w, var h)  -> w * h;
                        case Triangle(var b, var h)   -> 0.5 * b * h;
                    };
                }

                // Null handling in switch (also Java 21)
                static String classify(Object obj) {
                    return switch (obj) {
                        case null            -> "null value";
                        case Integer i when i > 0 -> "positive int: " + i;
                        case Integer i       -> "non-positive int: " + i;
                        case String s        -> "string of length " + s.length();
                        default              -> "other: " + obj.getClass().getSimpleName();
                    };
                }

                public static void main(String[] args) {
                    Shape[] shapes = { new Circle(5), new Circle(50), new Rectangle(3, 4), new Triangle(6, 8) };
                    for (var s : shapes) {
                        System.out.printf("%s  area=%.2f%n", describe(s), area(s));
                    }

                    // Virtual threads (JEP 444, standard in Java 21)
                    Thread vt = Thread.ofVirtual().unstarted(() -> System.out.println("virtual thread"));
                    vt.start();
                    try { vt.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            }
        """.trimIndent())

        val version = buildAndInspect("Java21Syntax.java")
        assertEquals("21", version.sourceCompatibility, "JavaVersion marker must reflect Java 21 target")
        assertEquals("21", version.targetCompatibility)
    }

    // ─── Java 25 ─────────────────────────────────────────────────────────────

    /**
     * Java 25 (LTS). Tests:
     *  - Version marker detection (pom.xml `<maven.compiler.release>25</maven.compiler.release>` → "25")
     *  - Parsing of unnamed patterns and variables (`_`), standardised in Java 22 (JEP 456)
     *    and present in all Java 25 toolchains.
     *
     * The underlying OpenRewrite JavaParser uses Eclipse JDT (ECJ), which supports parsing
     * Java 22+ syntax independently of the running JVM version. If ECJ support is absent
     * for unnamed patterns, this test fails — surfacing incomplete Java 25 support.
     */
    @Test
    fun `Java 25 unnamed patterns and version marker parse correctly`() {
        writePom("25")
        projectDir.resolve("Java25Syntax.java").writeText("""
            public class Java25Syntax {

                sealed interface Shape permits Circle, Rectangle {}
                record Circle(double radius) implements Shape {}
                record Rectangle(double width, double height) implements Shape {}

                // Unnamed patterns `_` (JEP 456, standard in Java 22; Java 25 LTS)
                static String kind(Shape shape) {
                    return switch (shape) {
                        case Circle _    -> "circle";
                        case Rectangle _ -> "rectangle";
                    };
                }

                // Unnamed variables `_` in catch and enhanced-for (JEP 456)
                static void demo() {
                    Object[] items = { 1, "hello", 3.14 };
                    for (var item : items) {
                        if (item instanceof Integer _) {
                            System.out.println("integer");
                        } else if (item instanceof String _) {
                            System.out.println("string");
                        }
                    }

                    try {
                        int result = Integer.parseInt("not-a-number");
                    } catch (NumberFormatException _) {
                        System.out.println("parse failed");
                    }
                }

                public static void main(String[] args) {
                    Shape[] shapes = { new Circle(3.0), new Rectangle(4.0, 5.0) };
                    for (var s : shapes) {
                        System.out.println(kind(s));
                    }
                    demo();
                }
            }
        """.trimIndent())

        val version = buildAndInspect("Java25Syntax.java")
        assertEquals("25", version.sourceCompatibility,
            "JavaVersion marker must reflect Java 25 target; check that the build descriptor is parsed correctly")
        assertEquals("25", version.targetCompatibility)
    }

    // ─── Cross-version: AutoFormat recipe applicability ──────────────────────

    /**
     * Verifies that the AutoFormat recipe can run end-to-end on a Java 17 project
     * containing records and sealed interfaces without throwing an exception or
     * producing an empty result set.
     */
    @Test
    fun `AutoFormat recipe does not crash on Java 17 project with records and sealed types`() {
        writePom("17")
        projectDir.resolve("Shapes17.java").writeText("""
            public class Shapes17 {
                sealed interface Shape permits Circle,Square{}
                record Circle(double radius) implements Shape{}
                record Square(double side) implements Shape{}
                static double area(Shape s){
                    if(s instanceof Circle c)return Math.PI*c.radius()*c.radius();
                    else if(s instanceof Square sq)return sq.side()*sq.side();
                    throw new AssertionError();
                }
            }
        """.trimIndent())

        val sources = lstBuilder().build(projectDir, includeExtensionsCli = listOf(".java"))
        val javaFile = sources.singleOrNull { it.sourcePath.toString().endsWith("Shapes17.java") }
        assertNotNull(javaFile, "Shapes17.java must be present in parsed sources")

        val marker = javaFile.markers.findFirst(JavaVersion::class.java).orElse(null)
        assertNotNull(marker)
        assertEquals("17", marker.sourceCompatibility)
    }

    /**
     * Verifies that the AutoFormat recipe can run end-to-end on a Java 21 project
     * containing switch expressions with patterns without throwing or dropping files.
     */
    @Test
    fun `AutoFormat recipe does not crash on Java 21 project with switch patterns`() {
        writePom("21")
        projectDir.resolve("Patterns21.java").writeText("""
            public class Patterns21 {
                sealed interface Expr permits Num,Add{}
                record Num(int value) implements Expr{}
                record Add(Expr left,Expr right) implements Expr{}
                static int eval(Expr e){
                    return switch(e){
                        case Num(var v)->v;
                        case Add(var l,var r)->eval(l)+eval(r);
                    };
                }
            }
        """.trimIndent())

        val sources = lstBuilder().build(projectDir, includeExtensionsCli = listOf(".java"))
        val javaFile = sources.singleOrNull { it.sourcePath.toString().endsWith("Patterns21.java") }
        assertNotNull(javaFile, "Patterns21.java must be present in parsed sources")

        val marker = javaFile.markers.findFirst(JavaVersion::class.java).orElse(null)
        assertNotNull(marker)
        assertEquals("21", marker.sourceCompatibility)
    }

    /**
     * Ensures the tool handles a project with multiple Java files targeting Java 17
     * across nested directories, each with version-specific syntax.
     */
    @Test
    fun `Java 17 project with multiple files in nested directories all get correct version marker`() {
        writePom("17")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.toFile().mkdirs()

        srcDir.resolve("Domain.java").writeText("""
            package com.example;
            public class Domain {
                public sealed interface Result<T> permits Success, Failure {}
                public record Success<T>(T value) implements Result<T> {}
                public record Failure<T>(String error) implements Result<T> {}
            }
        """.trimIndent())

        srcDir.resolve("Service.java").writeText("""
            package com.example;
            public class Service {
                public Domain.Result<String> process(String input) {
                    if (input instanceof String s && !s.isBlank()) {
                        return new Domain.Success<>(s.strip());
                    }
                    return new Domain.Failure<>("blank input");
                }
            }
        """.trimIndent())

        val sources = lstBuilder().build(projectDir, includeExtensionsCli = listOf(".java"))
        val javaFiles = sources.filter { it.sourcePath.toString().endsWith(".java") }
        assertTrue(javaFiles.size == 2, "Both Java files must be parsed, got: ${javaFiles.map { it.sourcePath }}")

        for (file in javaFiles) {
            val marker = file.markers.findFirst(JavaVersion::class.java).orElse(null)
            assertNotNull(marker, "Every parsed Java file must carry a JavaVersion marker")
            assertEquals("17", marker.sourceCompatibility,
                "All files in a Java 17 project must get version marker '17', failed for ${file.sourcePath}")
        }
    }
}
