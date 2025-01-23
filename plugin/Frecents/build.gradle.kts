import com.google.protobuf.gradle.id
import com.google.protobuf.gradle.proto

plugins {
    id("com.google.protobuf") version "0.9.4"
}

version = "1.0.5"
description = "Adds support for favorite GIFs, emojis, stickers, and recent items"

aliucord.changelog.set(
    """
    # 1.0.5
    - Fix gifs not updating in real time
    
    # 1.0.4
    - Fix preview gif sometimes not appearing
    
    # 1.0.3
    - Fix favorites icon disappearing
    
    # 1.0.2
    - Fix crash caused by having no favorites
    
    # 1.0.1
    - Fix mixup causing favorites category to not appear

    # 1.0.0
    - Initial release
    """.trimIndent()
)

android {
    sourceSets {
        named("main") {
            proto { }

            java {
                srcDirs("${protobuf.generatedFilesBaseDir}/main/javalite")
            }
        }
    }
}

protobuf {
    val version = "4.29.3"
    protoc {
        artifact = "com.google.protobuf:protoc:$version"
    }

    plugins {
        id("javalite") {
            artifact = "com.google.protobuf:protoc-gen-javalite:$version"
        }
    }

    generateProtoTasks {
        all().forEach {
            it.plugins {
                id("java") {
                    option("lite")
                }

                create("kotlin") {
                    option("lite")
                }
            }
        }
    }
}
