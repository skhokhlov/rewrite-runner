package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.lst.utils.FileCollector
import org.openrewrite.Parser
import org.openrewrite.docker.DockerParser
import org.openrewrite.gradle.GradleParser
import org.openrewrite.groovy.GroovyParser
import org.openrewrite.hcl.HclParser
import org.openrewrite.java.JavaParser
import org.openrewrite.json.JsonParser
import org.openrewrite.kotlin.KotlinParser
import org.openrewrite.maven.MavenParser
import org.openrewrite.properties.PropertiesParser
import org.openrewrite.protobuf.ProtoParser
import org.openrewrite.text.PlainTextParser
import org.openrewrite.toml.TomlParser
import org.openrewrite.xml.XmlParser
import org.openrewrite.yaml.YamlParser

/** Built-ins use the same provider contract as application-supplied parsers. */
internal object BuiltinParserProviders {
    class Java : ParserProvider {
        override fun requiresClasspath(): Boolean = true
        override fun create(context: ParserContext, options: Map<String, String>): Parser =
            JavaParser.fromJavaVersion().classpath(
                context.classpath
            ).typeCache(context.typeCache).build()
    }
    class Kotlin : ParserProvider {
        override fun requiresClasspath(): Boolean = true
        override fun create(context: ParserContext, options: Map<String, String>): Parser =
            KotlinParser.builder().classpath(context.classpath).typeCache(context.typeCache).build()
    }
    class KotlinScript : ParserProvider {
        override fun requiresClasspath(): Boolean = true
        override fun create(context: ParserContext, options: Map<String, String>): Parser =
            KotlinParser.builder().classpath(context.classpath).typeCache(context.typeCache).build()
    }
    class Groovy : ParserProvider {
        override fun requiresClasspath(): Boolean = true
        override fun create(context: ParserContext, options: Map<String, String>): Parser =
            GroovyParser.builder().classpath(context.classpath).typeCache(context.typeCache).build()
    }
    class GradleKotlin : ParserProvider {
        override fun requiresClasspath(): Boolean = true
        override fun create(context: ParserContext, options: Map<String, String>): GradleParser =
            GradleParser.builder().kotlinParser(
                KotlinParser.builder().classpath(context.classpath + context.gradleDslClasspath)
                    .typeCache(context.typeCache)
            ).buildscriptClasspath(context.gradleDslClasspath).build()
    }
    class GradleGroovy : ParserProvider {
        override fun requiresClasspath(): Boolean = true
        override fun create(context: ParserContext, options: Map<String, String>): GradleParser =
            GradleParser.builder().groovyParser(
                GroovyParser.builder().classpath(context.classpath + context.gradleDslClasspath)
                    .typeCache(context.typeCache)
            ).buildscriptClasspath(context.gradleDslClasspath).build()
    }
    class Maven : ParserProvider {
        override fun create(context: ParserContext, options: Map<String, String>): Parser =
            MavenParser.builder().build()
    }
    class Xml : ParserProvider {
        override fun create(context: ParserContext, options: Map<String, String>): Parser =
            XmlParser()
    }
    class Yaml : ParserProvider {
        override fun create(context: ParserContext, options: Map<String, String>): Parser =
            YamlParser()
    }
    class Json : ParserProvider {
        override fun create(context: ParserContext, options: Map<String, String>): Parser =
            JsonParser()
    }
    class Properties : ParserProvider {
        override fun create(context: ParserContext, options: Map<String, String>): Parser =
            PropertiesParser()
    }
    class Toml : ParserProvider {
        override fun create(context: ParserContext, options: Map<String, String>): Parser =
            TomlParser.builder().build()
    }
    class Hcl : ParserProvider {
        override fun create(context: ParserContext, options: Map<String, String>): Parser =
            HclParser.builder().build()
    }
    class Protobuf : ParserProvider {
        override fun create(context: ParserContext, options: Map<String, String>): Parser =
            ProtoParser.builder().build()
    }
    class Docker : ParserProvider {
        override fun create(context: ParserContext, options: Map<String, String>): Parser =
            DockerParser.builder().build()
    }
    class PlainText : ParserProvider {
        override fun create(context: ParserContext, options: Map<String, String>): Parser =
            PlainTextParser.builder().build()
    }

    fun registrations(): List<ParserRegistration> {
        fun registration(
            id: String,
            provider: Class<out ParserProvider>,
            vararg extensions: String
        ) = ParserRegistration(
            id,
            provider.name,
            ParserFileSelection(extensions = extensions.toSet())
        )
        return listOf(
            registration("java", Java::class.java, ".java"),
            registration("kotlin", Kotlin::class.java, ".kt"),
            registration("kotlin-script", KotlinScript::class.java, ".kts"),
            ParserRegistration(
                "gradle-kotlin",
                GradleKotlin::class.java.name,
                ParserFileSelection(pathGlobs = listOf("**/*.gradle.kts")),
                overrides = setOf("kotlin-script")
            ),
            registration("groovy", Groovy::class.java, ".groovy"),
            registration("gradle", GradleGroovy::class.java, ".gradle"),
            registration("yaml", Yaml::class.java, ".yaml", ".yml"),
            registration("json", Json::class.java, ".json"),
            ParserRegistration(
                "maven",
                Maven::class.java.name,
                ParserFileSelection(fileNames = setOf("pom.xml")),
                overrides = setOf("xml")
            ),
            registration("xml", Xml::class.java, ".xml"),
            registration("properties", Properties::class.java, ".properties"),
            registration("toml", Toml::class.java, ".toml"),
            registration("hcl", Hcl::class.java, ".hcl", ".tf", ".tfvars"),
            registration("protobuf", Protobuf::class.java, ".proto"),
            ParserRegistration(
                "docker",
                Docker::class.java.name,
                ParserFileSelection(
                    extensions = setOf(".dockerfile", ".containerfile"),
                    fileNamePrefixes = setOf("Dockerfile", "Containerfile"),
                    prefixExcludedExtensions = FileCollector.DEFAULT_EXTENSIONS
                )
            ),
            registration("plain-text", PlainText::class.java)
        )
    }
}
