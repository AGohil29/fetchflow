import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform

/**
 * Publishing convention for all library modules, targeting Maven Central via the Central Portal.
 *
 * Credentials / signing are read from Gradle properties (or ORG_GRADLE_PROJECT_* env vars on CI):
 *   mavenCentralUsername, mavenCentralPassword      -> Central Portal *user token* (not your login)
 *   signingInMemoryKey, signingInMemoryKeyPassword  -> ASCII-armored secret key (+ optional passphrase)
 *   signingInMemoryKeyId                            -> optional, key id
 */
plugins {
    id("com.vanniktech.maven.publish")
}

// Published artifact base name: fetchflow-runtime -> fetchflow; other modules keep their
// project name (fetchflow-core, fetchflow-network, ...). The plugin appends target suffixes
// itself (-desktop, -android, -iosarm64, ...).
val publishedBaseName = if (project.name == "fetchflow-runtime") "fetchflow" else project.name

// Ship the LICENSE inside META-INF of every published jar.
tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE")) {
        into("META-INF")
    }
}

val hasSigningKey: Boolean =
    providers.gradleProperty("signingInMemoryKey").orNull?.isNotBlank() == true

mavenPublishing {
    publishToMavenCentral()        // Central Portal. Release is reviewed/published in the portal UI.
    if (hasSigningKey) {
        signAllPublications()      // Required by Central; skipped locally so publishToMavenLocal works unsigned.
    }

    coordinates(
        groupId = project.group.toString(),
        artifactId = publishedBaseName,
        version = project.version.toString()
    )

    // Central requires a javadoc jar; an empty one is accepted. Switch to
    // JavadocJar.Dokka("dokkaHtml") later if you want real API docs in it.
    configure(
        KotlinMultiplatform(
            javadocJar = JavadocJar.Empty(),
            androidVariantsToPublish = listOf("release")
        )
    )

    pom {
        name.set("FetchFlow")
        description.set("Resumable file downloads for Kotlin Multiplatform: Android, iOS and Desktop.")
        inceptionYear.set("2026")
        url.set("https://github.com/AGohil29/fetchflow")

        licenses {
            license {
                name.set("The Apache Software License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("AGohil29")
                name.set("Arun Gohil")
                url.set("https://github.com/AGohil29")
            }
        }

        scm {
            connection.set("scm:git:git://github.com/AGohil29/fetchflow.git")
            developerConnection.set("scm:git:ssh://github.com/AGohil29/fetchflow.git")
            url.set("https://github.com/AGohil29/fetchflow")
        }
    }
}

// Keep the Maven Central bundle small. Maven Central counts every uploaded file against the
// organization's monthly file limit, but only needs .md5 and .sha1 checksums for each artifact:
// .sha256/.sha512 are optional and .asc signatures need no checksums at all
// (https://central.sonatype.org/publish/requirements/). Gradle generates all of them, so trim
// the plugin's staging repository (build/publishing/mavenCentral) right after each publish task;
// the plugin zips that folder at the end of the build. This cuts one release from 1,700 to 680 files.
val mavenCentralStagingDir = layout.buildDirectory.dir("publishing/mavenCentral")
val unneededChecksum = Regex("""(\.sha256|\.sha512|\.asc\.md5|\.asc\.sha1)$""")
tasks.withType<PublishToMavenRepository>().configureEach {
    if (name.endsWith("ToMavenCentralRepository")) {
        val stagingDir = mavenCentralStagingDir
        doLast {
            stagingDir.get().asFile.walkTopDown()
                .filter { it.isFile && !it.name.startsWith("maven-metadata") && unneededChecksum.containsMatchIn(it.name) }
                .forEach { it.delete() }
        }
    }
}

// Fail loudly instead of uploading unsigned artifacts that Central would reject anyway.
val requireSigningKeyForRelease = tasks.register("requireSigningKeyForRelease") {
    val present = hasSigningKey
    doLast {
        check(present) {
            "Refusing to publish to Maven Central without a signing key. " +
                "Set ORG_GRADLE_PROJECT_signingInMemoryKey (and ...KeyPassword / ...KeyId if needed)."
        }
    }
}
tasks.matching { it.name.contains("MavenCentral") }.configureEach {
    dependsOn(requireSigningKeyForRelease)
}
