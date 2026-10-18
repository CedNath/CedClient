import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.3.0"
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
    id("maven-publish")
}

version = "${project.property("mod_version")}-${project.property("minecraft_version")}"
group = project.property("maven_group") as String

base {
    archivesName.set(project.property("archives_base_name") as String)
}

val targetJavaVersion = 25

java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    withSourcesJar()
}

repositories {
    mavenCentral()

    maven { url = uri("https://maven.fabricmc.net/") }
    maven { url = uri("https://api.modrinth.com/maven") }
    maven { url = uri("https://maven.terraformersmc.com/") }
}
loom {
    runs {
        named("client") {
            // -Pdevauth=0 launches offline (random "Player###" name) instead of your account
            vmArg("-Ddevauth.enabled=" + (findProperty("devauth") ?: "1"))
        }
    }
}
dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")

    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${property("kotlin_loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")

    implementation("org.lwjgl:lwjgl-nanovg:3.4.1")
    runtimeOnly("org.lwjgl:lwjgl-nanovg:3.4.1:natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-nanovg:3.4.1:natives-linux")
    runtimeOnly("org.lwjgl:lwjgl-nanovg:3.4.1:natives-macos")
    localRuntime("maven.modrinth:dev-auth-neo:1.1.0")
}

// ---- Cheat / Legit builds (same idea as NoammAddons) ----
// One codebase, two jars. src/main as written IS the cheat build (runClient, IntelliJ). The legit build compiles a
// preprocessed copy: lines between "//#if CHEAT" and "//#endif" (or "//#else") are dropped, lines in a
// "//#if LEGIT" / "//#else" branch are written as "//$code" and get uncommented. Cheat-only mixins live in
// cedclient.cheat.mixins.json, which the legit fabric.mod.json doesn't list.
val legitJavaDir = layout.buildDirectory.dir("preprocessed/legit/java")
val legitKotlinDir = layout.buildDirectory.dir("preprocessed/legit/kotlin")
val legitOnlyExcludes = listOf("cedclient.cheat.mixins.json", "sea_creatures.json")

fun preprocessLegit(source: List<String>, file: File): List<String> {
    data class Frame(val outer: Boolean, val branch: Boolean)
    val stack = ArrayDeque<Frame>()
    val out = ArrayList<String>()
    var include = true
    source.forEachIndexed { i, line ->
        when (line.trim()) {
            "//#if CHEAT", "//#if LEGIT" -> {
                val branch = line.trim() == "//#if LEGIT"
                stack.addLast(Frame(include, branch)); include = include && branch
            }
            "//#else" -> stack.lastOrNull()?.let { include = !it.branch && it.outer }
                ?: throw GradleException("Unmatched //#else in $file:${i + 1}")
            "//#endif" -> include = (stack.removeLastOrNull() ?: throw GradleException("Unmatched //#endif in $file:${i + 1}")).outer
            else -> if (include) out += if (line.startsWith("//$")) line.substring(3) else line
        }
    }
    if (stack.isNotEmpty()) throw GradleException("Unclosed //#if in $file")
    return out
}

val preprocessLegitSources by tasks.registering {
    val roots = mapOf(file("src/main/java") to legitJavaDir, file("src/main/kotlin") to legitKotlinDir)
    roots.keys.forEach { inputs.dir(it) }
    outputs.dirs(legitJavaDir, legitKotlinDir)
    doLast {
        roots.forEach { (src, outDir) ->
            val out = outDir.get().asFile
            out.deleteRecursively()
            src.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.forEach { f ->
                val lines = preprocessLegit(f.readLines(), f)
                // Files that are cheat-only as a whole come out empty: leave them out entirely.
                if (lines.any { it.isNotBlank() }) {
                    val target = out.resolve(f.relativeTo(src).path)
                    target.parentFile.mkdirs()
                    target.writeText(lines.joinToString("\n", postfix = "\n"))
                }
            }
        }
    }
}

val legitSourceSet = sourceSets.create("legit") {
    java.setSrcDirs(listOf(legitJavaDir))
    resources.setSrcDirs(listOf("src/main/resources"))
    compileClasspath += sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().runtimeClasspath
}
kotlin.sourceSets.named("legit") { kotlin.setSrcDirs(listOf(legitKotlinDir)) }
tasks.named<KotlinCompile>("compileLegitKotlin") {
    dependsOn(preprocessLegitSources)
    // Kotlin refers to the (preprocessed) Java classes too, e.g. mixin accessors.
    compilerOptions.freeCompilerArgs.add("-Xjava-source-roots=${legitJavaDir.get().asFile.invariantSeparatorsPath}")
}
tasks.named("compileLegitJava") { dependsOn(preprocessLegitSources) }

fun ProcessResources.expandModJson(cheat: Boolean) {
    inputs.property("version", project.version)
    filteringCharset = "UTF-8"
    filesMatching("fabric.mod.json") {
        expand(
            mapOf(
                "version" to project.version,
                "minecraft_version" to project.property("minecraft_version"),
                "loader_version" to project.property("loader_version"),
                "kotlin_loader_version" to project.property("kotlin_loader_version"),
                "fabric_version" to project.property("fabric_version"),
                "cheat_mixins" to if (cheat) ", \"cedclient.cheat.mixins.json\"" else ""
            )
        )
    }
}

tasks.processResources { expandModJson(cheat = true) }
tasks.named<ProcessResources>("processLegitResources") {
    expandModJson(cheat = false)
    exclude(legitOnlyExcludes)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(targetJavaVersion.toString()))
}

// CedClient-<mod_version>-<minecraft_version>-cheat.jar / -legit.jar
tasks.jar {
    archiveClassifier.set("cheat")
    from("LICENSE") {
        rename { "${it}_${project.base.archivesName.get()}" }
    }
}

val jarLegit by tasks.registering(Jar::class) {
    archiveClassifier.set("legit")
    from(legitSourceSet.output)
    from("LICENSE") {
        rename { "${it}_${project.base.archivesName.get()}" }
    }
}

tasks.build { dependsOn(jarLegit) }

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = project.property("archives_base_name") as String
            from(components["java"])
        }
    }
}