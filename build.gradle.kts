import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

fun properties(key: String) = providers.gradleProperty(key)
fun environment(key: String) = providers.environmentVariable(key)

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
    id("org.jetbrains.intellij.platform.grammarkit") version "2.20.1"
}

group = properties("pluginGroup").get()
version = properties("pluginVersion").get()

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(25)
}

// Configure project's dependencies
repositories {
    mavenCentral()

    // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        create(properties("platformType"), properties("platformVersion"))

        // Plugin Dependencies. Uses `platformBundledPlugins` property from the gradle.properties file for bundled IntelliJ Platform plugins.
        bundledPlugins(properties("platformBundledPlugins").map { it.split(',') })

        // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file for plugin from JetBrains Marketplace.
        plugins(properties("platformPlugins").map { it.split(',') })

        // Spell checking: a platform product module, loaded through an optional <depends>.
        bundledModule("intellij.spellchecker")

        pluginVerifier()
        testFramework(TestFrameworkType.Platform)
    }

    // Known gaps in the Platform test framework: JUnit 4 and opentest4j are not pulled in.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

sourceSets {
    main {
        java.srcDirs("src/main/gen", "src/main/java")
    }
}

// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    pluginConfiguration {
        version = properties("pluginVersion")
        description = file("src/main/html/description.html").inputStream().readBytes().toString(Charsets.UTF_8)
        changeNotes = file("src/main/html/change-notes.html").inputStream().readBytes().toString(Charsets.UTF_8)

        ideaVersion {
            sinceBuild = properties("pluginSinceBuild")
            untilBuild = properties("pluginUntilBuild")
        }
    }

    signing {
        certificateChain = environment("CERTIFICATE_CHAIN")
        privateKey = environment("PRIVATE_KEY")
        password = environment("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = environment("PUBLISH_TOKEN")
        // The pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/deployment.html#specifying-a-release-channel
        channels = properties("pluginVersion").map { listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }) }
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaUltimate, properties("platformVersion").get())
            recommended()
        }
    }

    buildSearchableOptions = false
}

// Ensure signing is not silently skipped when publishing
val hasSigningCreds = listOf("CERTIFICATE_CHAIN", "PRIVATE_KEY", "PRIVATE_KEY_PASSWORD")
    .all { environment(it).isPresent }

tasks {

    // Fail early if trying to publish without signing credentials
    named("publishPlugin") {
        dependsOn("signPlugin")
        doFirst {
            if (!hasSigningCreds) {
                throw GradleException(
                    "Missing signing credentials. Set CERTIFICATE_CHAIN, PRIVATE_KEY, and PRIVATE_KEY_PASSWORD in the environment to sign before publishing."
                )
            }
        }
    }

    wrapper {
        gradleVersion = properties("gradleVersion").get()
    }

    test {
        useJUnit()
        // Corpus of real .fan files to parse. Colon-separated list of folders.
        // Example: FANTOM_CORPUS=~/fantom/fantom-1.0.84/src ./gradlew test
        systemProperty("fantom.corpus", System.getenv("FANTOM_CORPUS") ?: "")
        // The corpus parses with zero errors since wave 2, so a regression now fails the test.
        systemProperty("fantom.corpus.failOnErrors", System.getenv("FANTOM_CORPUS_FAIL") ?: "true")
        systemProperty("fantom.snippets", System.getenv("FANTOM_SNIPPETS") ?: "")
        // Library tests read the real Fantom install outside the test project root.
        systemProperty("NO_FS_ROOTS_ACCESS_CHECK", "true")
        testLogging {
            showStandardStreams = true
            events("passed", "failed", "skipped")
        }
    }

    generateLexer {
        sourceFile.set(file("src/main/grammar/Fantom.flex"))
        // JFlex appends the package from the `package` line in Fantom.flex,
        // so the root is src/main/gen, not the package folder.
        targetRootOutputDir.set(file("src/main/gen"))
        // The root is shared with generateParser; with pathToClass set, purge removes only this file.
        pathToClass.set("co/anbora/labs/fantom/lang/FanLexer.java")
        purgeOldFiles.set(true)
    }

    // Axon (SkySpark/Haxall scripting) lexer. Same root as generateLexer; pathToClass scopes the purge.
    // The class lives outside core/psi, so the generateParser purge never deletes it.
    register<org.jetbrains.intellij.platform.gradle.tasks.GenerateLexerTask>("generateAxonLexer") {
        sourceFile.set(file("src/main/grammar/Axon.flex"))
        targetRootOutputDir.set(file("src/main/gen"))
        pathToClass.set("co/anbora/labs/fantom/lang/axon/_AxonLexer.java")
        purgeOldFiles.set(true)
    }

    generateParser {
        sourceFile.set(file("src/main/grammar/Fantom.bnf"))
        targetRootOutputDir.set(file("src/main/gen"))
        // Scope the purge to the parser and PSI folders. Without these two paths the task
        // empties the whole shared root and deletes FanLexer.java written by generateLexer.
        pathToParser.set("co/anbora/labs/fantom/lang/core/parser/FantomParser.java")
        pathToPsiRoot.set("co/anbora/labs/fantom/lang/core/psi")
        // Removing a BNF rule must also remove its generated PSI class, or compileJava breaks.
        purgeOldFiles.set(true)
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        dependsOn("generateLexer", "generateParser", "generateAxonLexer")
    }
}

tasks {
}
