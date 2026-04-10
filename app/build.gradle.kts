import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

abstract class FixSourceSetPathMapTask : DefaultTask() {
    @get:InputFile
    abstract val mappingFile: RegularFileProperty

    @TaskAction
    fun fix() {
        val file = mappingFile.get().asFile
        if (!file.exists()) return

        val originalLines = file.readLines()
        val mergedAliasLines = buildList {
            originalLines.forEach { line ->
                add(line)
                when {
                    line.contains(".app-packageDebugResources-59 ") -> {
                        add(line.replace(".app-packageDebugResources-59 ", ".app-mergeDebugResources-59 "))
                    }

                    line.contains(".app-packageDebugResources-60 ") -> {
                        add(line.replace(".app-packageDebugResources-60 ", ".app-mergeDebugResources-60 "))
                    }
                }
            }
        }.distinct()

        if (mergedAliasLines != originalLines) {
            file.writeText(mergedAliasLines.joinToString(System.lineSeparator()))
        }
    }
}

android {
    namespace = "com.tutu.myblbl"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tutu.myblbl"
        minSdk = 21
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                val appName = "MyBili"
                val versionName = variant.versionName
                val buildType = variant.buildType.name
                val outputFileName = "${appName}-v${versionName}-${buildType}.apk"
                output.outputFileName = outputFileName
            }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = "17"
    }
    
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        disable += setOf(
            "ResourceCycle",
            "MissingDefaultResource",
            "PxUsage",
            "UnusedResources",
            "PrivateResource",
            "NotifyDataSetChanged",
            "RtlHardcoded",
            "RtlSymmetry",
            "HardcodedText",
            "Overdraw",
            "VectorPath",
            "GradleDependency",
            "DrawAllocation",
            "SpUsage",
            "OldTargetApi",
            "DiscouragedApi",
            "IconXmlAndPng",
            "IconLauncherShape",
            "PluralsCandidate",
            "UnusedAttribute",
            "TypographyDashes",
            "UnclosedTrace",
            "ObsoleteSdkInt"
        )
        fatal += setOf("NotSibling")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.google.code.gson:gson:2.10.1")

    implementation("io.insert-koin:koin-android:3.5.3")

    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    implementation("androidx.media3:media3-common:1.2.1")
    implementation("androidx.media3:media3-session:1.2.1")
    implementation("androidx.media3:media3-datasource:1.2.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.2.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.2.1")
    implementation("androidx.media3:media3-database:1.2.1")

    implementation("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.16.0")

    implementation("org.greenrobot:eventbus:3.3.1")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Android TV
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.leanback:leanback-preference:1.0.0")

    // Protobuf (弹幕解析)
    implementation("com.google.protobuf:protobuf-javalite:3.24.0")

    // AkDanmaku 官方弹幕引擎（源码内嵌，见 com.kuaishou.akdanmaku）
    implementation("com.badlogicgames.gdx:gdx:1.10.0")
    implementation("com.badlogicgames.gdx:gdx-backend-android:1.10.0")
    implementation("com.badlogicgames.ashley:ashley:1.7.3")

    // ZXing 二维码
    implementation("com.google.zxing:core:3.5.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

val fixDebugSourceSetPathMap by tasks.registering(FixSourceSetPathMapTask::class) {
    val mapTaskName = "mapDebugSourceSetPaths"
    dependsOn(mapTaskName)
    mappingFile.set(
        layout.buildDirectory.file(
            "intermediates/source_set_path_map/debug/$mapTaskName/file-map.txt"
        )
    )
}

tasks.matching { it.name == "mergeDebugResources" }.configureEach {
    dependsOn(fixDebugSourceSetPathMap)
}
