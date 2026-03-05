import com.github.gradle.node.yarn.task.YarnTask
import com.github.gradle.node.npm.task.NpmTask

plugins {
    java
    id("com.github.node-gradle.node") version("7.0.2")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

// Output path for generated resources (using layout.buildDirectory for Gradle 9.0 compatibility)
val projectOutput: String by extra("${layout.buildDirectory.get().asFile}/generated-resources/")

// Node.js configuration
node {
    version.set("22.16.0")
    yarnVersion.set("1.22.18")
    npmVersion.set("8.5.5")
    download.set(true)
    nodeProjectDir.set(file(project.projectDir))
}

// Install npm dependencies using yarn
val yarnPackages by tasks.registering(YarnTask::class) {
    description = "Install npm dependencies"
    args.set(listOf("install", "--frozen-lockfile", "--verbose"))

    inputs.files(
        fileTree(project.projectDir).matching {
            include("**/package.json", "**/yarn.lock")
        }
    )

    outputs.dirs(file("node_modules"))

    dependsOn("${project.path}:yarn", ":web-ui:npmSetup")
}

// Build with webpack
val webpack by tasks.registering(NpmTask::class) {
    group = "Ignition Module"
    description = "Build React component with webpack"

    args.set(listOf("run", "build-dev"))

    dependsOn(yarnPackages)

    inputs.files(project.fileTree(project.projectDir).matching {
        exclude("**/node_modules/**", "**/dist/**", "**/build/**", "**/yarn-error.log")
    }.toList())

    outputs.files(fileTree(projectOutput))
}

// Copy standalone page and React UMD libs into mounted resources
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
        dependsOn(webpack, yarnPackages, copyStandaloneResources)
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
