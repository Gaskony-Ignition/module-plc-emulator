plugins {
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

// Output path for generated resources (using layout.buildDirectory for Gradle 9.0 compatibility)
val projectOutput: String by extra("${layout.buildDirectory.get().asFile}/generated-resources/")

/**
 * Check if npm/node is available on the system.
 */
fun isNpmAvailable(): Boolean {
    return try {
        val process = ProcessBuilder("npm", "--version").start()
        process.waitFor() == 0
    } catch (e: Exception) {
        false
    }
}

/**
 * Task: Install npm dependencies.
 * Only runs if node_modules doesn't exist.
 */
val npmInstall by tasks.registering(Exec::class) {
    group = "build"
    description = "Install npm dependencies"

    workingDir = project.projectDir
    commandLine = if (System.getProperty("os.name").lowercase().contains("windows")) {
        listOf("cmd", "/c", "npm", "install")
    } else {
        listOf("npm", "install")
    }

    inputs.files(
        fileTree(project.projectDir).matching {
            include("**/package.json", "**/package-lock.json")
        }
    )
    outputs.dirs(file("node_modules"))

    onlyIf {
        !file("${project.projectDir}/node_modules").exists() && isNpmAvailable()
    }

    doFirst {
        if (!isNpmAvailable()) {
            throw GradleException("npm is not available. Please install Node.js and npm to build the web UI.")
        }
        logger.lifecycle("Installing npm dependencies...")
    }
}

/**
 * Task: Build React component with webpack.
 */
val webpack by tasks.registering(Exec::class) {
    group = "Ignition Module"
    description = "Build React component with webpack"

    workingDir = project.projectDir
    commandLine = if (System.getProperty("os.name").lowercase().contains("windows")) {
        listOf("cmd", "/c", "npm", "run", "build:dev")
    } else {
        listOf("npm", "run", "build:dev")
    }

    dependsOn(npmInstall)

    inputs.files(project.fileTree(project.projectDir).matching {
        exclude("**/node_modules/**", "**/dist/**", "**/build/**")
    }.toList())
    outputs.files(fileTree(projectOutput))

    onlyIf { isNpmAvailable() }
}

/**
 * Task: Copy standalone page and React UMD libs into mounted resources.
 */
val copyStandaloneResources by tasks.registering(Copy::class) {
    group = "Ignition Module"
    description = "Copy standalone.html and React UMD libraries for dedicated page popout"

    dependsOn(webpack)

    // standalone.html from gateway static resources
    from(project(":gateway").file("src/main/resources/static/standalone.html"))

    // React UMD production builds from node_modules
    from(file("node_modules/react/umd/react.production.min.js"))
    from(file("node_modules/react-dom/umd/react-dom.production.min.js"))

    into("${projectOutput}mounted/")
}

tasks {
    processResources {
        dependsOn(webpack, npmInstall, copyStandaloneResources)
    }

    clean {
        delete(file("build"))
    }
}

val deepClean by tasks.registering {
    doLast {
        delete(file(".gradle"))
        delete(file("node_modules"))
    }

    dependsOn(project.tasks.named("clean"))
}

// Make gateway processResources wait for webpack + standalone resources
project(":gateway")?.tasks?.named("processResources")?.configure {
    dependsOn(webpack, copyStandaloneResources)
}

sourceSets {
    main {
        output.dir(projectOutput, "builtBy" to listOf(webpack, copyStandaloneResources))
    }
}
