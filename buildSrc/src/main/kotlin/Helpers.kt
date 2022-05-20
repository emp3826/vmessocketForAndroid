import com.android.build.gradle.AbstractAppExtension
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import com.github.triplet.gradle.play.PlayPublisherExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.*
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions
import java.io.File
import java.security.MessageDigest
import java.util.*
import kotlin.system.exitProcess

fun sha256Hex(bytes: ByteArray): String {
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(bytes)
    return digest.fold("") { str, it -> str + "%02x".format(it) }
}

private val Project.android get() = extensions.getByName<BaseExtension>("android")

private val javaVersion = JavaVersion.VERSION_1_8
private lateinit var metadata: Properties
private lateinit var localProperties: Properties
private lateinit var flavor: String

fun Project.requireFlavor(): String {
    if (::flavor.isInitialized) return flavor
    if (gradle.startParameter.taskNames.isNotEmpty()) {
        val taskName = gradle.startParameter.taskNames[0]
        when {
            taskName.contains("assemble") -> {
                flavor = taskName.substringAfter("assemble")
                return flavor
            }
            taskName.contains("install") -> {
                flavor = taskName.substringAfter("install")
                return flavor
            }
            taskName.contains("publish") -> {
                flavor = taskName.substringAfter("publish").substringBefore("Bundle")
                return flavor
            }
        }
    }

    flavor = ""
    return flavor
}

fun Project.requireMetadata(): Properties {
    if (!::metadata.isInitialized) {
        metadata = Properties().apply {
            load(rootProject.file("sager.properties").inputStream())
        }
    }
    return metadata
}

fun Project.requireLocalProperties(): Properties {
    if (!::localProperties.isInitialized) {
        localProperties = Properties()

        val base64 = System.getenv("LOCAL_PROPERTIES")
        if (!base64.isNullOrBlank()) {

            localProperties.load(Base64.getDecoder().decode(base64).inputStream())
        } else if (project.rootProject.file("local.properties").exists()) {
            localProperties.load(rootProject.file("local.properties").inputStream())
        }
    }
    return localProperties
}

fun Project.requireTargetAbi(): String {
    var targetAbi = ""
    if (gradle.startParameter.taskNames.isNotEmpty()) {
        if (gradle.startParameter.taskNames.size == 1) {
            val targetTask = gradle.startParameter.taskNames[0].toLowerCase(Locale.ROOT).trim()
            when {
                targetTask.contains("arm64") -> targetAbi = "arm64-v8a"
                targetTask.contains("arm") -> targetAbi = "armeabi-v7a"
                targetTask.contains("x64") -> targetAbi = "x86_64"
            }
        }
    }
    return targetAbi
}

fun Project.setupCommon() {
    android.apply {
        buildToolsVersion("30.0.3")
        compileSdkVersion(31)
        defaultConfig {
            minSdk = 21
            targetSdk = 31
        }
        buildTypes {
            getByName("release") {
                isMinifyEnabled = true
            }
        }
        compileOptions {
            sourceCompatibility = javaVersion
            targetCompatibility = javaVersion
        }
        lintOptions {
            isShowAll = true
            isCheckAllWarnings = true
            isCheckReleaseBuilds = false
            isWarningsAsErrors = true
            textOutput = project.file("build/lint.txt")
            htmlOutput = project.file("build/lint.html")
        }
        packagingOptions {
            excludes.addAll(
                listOf(
                    "**/*.kotlin_*",
                    "/META-INF/*.version",
                    "/META-INF/native/**",
                    "/META-INF/native-image/**",
                    "/META-INF/INDEX.LIST",
                    "DebugProbesKt.bin",
                    "com/**",
                    "org/**",
                    "**/*.java",
                    "**/*.proto",
                    "okhttp3/**"
                )
            )
        }
        packagingOptions {
            jniLibs.useLegacyPackaging = true
        }
        (this as? AbstractAppExtension)?.apply {
            buildTypes {
                getByName("release") {
                    isShrinkResources = true
                    // TODO nkmr
                    if (System.getenv("nkmr_minify") == "0") {
                        isShrinkResources = false
                        isMinifyEnabled = false
                    }
                }
                getByName("debug") {
                    applicationIdSuffix = "debug"
                    debuggable(true)
                    jniDebuggable(true)
                }
            }
            applicationVariants.forEach { variant ->
                variant.outputs.forEach {
                    it as BaseVariantOutputImpl
                    it.outputFileName = it.outputFileName.replace(
                        "app", "${project.name}-" + variant.versionName
                    ).replace("-release", "").replace("-oss", "")
                }
            }
        }
    }
}

fun Project.setupKotlinCommon() {
    setupCommon()
    (android as ExtensionAware).extensions.getByName<KotlinJvmOptions>("kotlinOptions").apply {
        jvmTarget = javaVersion.toString()
    }
    dependencies.apply {
        add("implementation", kotlin("stdlib-jdk8"))
    }
}

fun Project.setupNdk() {
    android.ndkVersion = "23.1.7779620"
}

fun Project.setupNdkLibrary() {
    setupCommon()
    setupNdk()
    android.apply {
        defaultConfig {
            externalNativeBuild.ndkBuild {
                val targetAbi = requireTargetAbi()
                if (targetAbi.isNotBlank()) {
                    abiFilters(targetAbi)
                } else {
                    abiFilters("armeabi-v7a", "arm64-v8a", "x86_64")
                }
                arguments("-j${Runtime.getRuntime().availableProcessors()}")
            }
        }

        externalNativeBuild.ndkBuild.path("src/main/jni/Android.mk")
    }
}

fun Project.setupCMakeLibrary() {
    setupCommon()
    setupNdk()
    android.apply {
        defaultConfig {
            externalNativeBuild.cmake {
                val targetAbi = requireTargetAbi()
                if (targetAbi.isNotBlank()) {
                    abiFilters(targetAbi)
                } else {
                    abiFilters("armeabi-v7a", "arm64-v8a", "x86_64")
                }
                arguments("-j${Runtime.getRuntime().availableProcessors()}")
            }
        }

        externalNativeBuild.cmake.path("src/main/cpp/CMakeLists.txt")
    }
}


fun Project.setupPlay() {
    val serviceAccountCredentialsFile = rootProject.file("service_account_credentials.json")
    if (serviceAccountCredentialsFile.isFile) {
        setupPlayInternal().serviceAccountCredentials.set(serviceAccountCredentialsFile)
    } else if (System.getenv().containsKey("ANDROID_PUBLISHER_CREDENTIALS")) {
        setupPlayInternal()
    }
}

private fun Project.setupPlayInternal(): PlayPublisherExtension {
    apply(plugin = "com.github.triplet.play")
    return (extensions.getByName("play") as PlayPublisherExtension).apply {
        if (android.defaultConfig.versionName?.contains("beta") == true) {
            track.set("beta")
        } else {
            track.set("production")
        }
        defaultToAppBundles.set(true)
    }
}

fun Project.setupAppCommon() {
    setupKotlinCommon()

    val lp = requireLocalProperties()
    val keystorePwd = lp.getProperty("KEYSTORE_PASS") ?: System.getenv("KEYSTORE_PASS")
    val alias = lp.getProperty("ALIAS_NAME") ?: System.getenv("ALIAS_NAME")
    val pwd = lp.getProperty("ALIAS_PASS") ?: System.getenv("ALIAS_PASS")

    android.apply {
        if (keystorePwd != null) {
            signingConfigs {
                create("release") {
                    storeFile(rootProject.file("release.keystore"))
                    storePassword(keystorePwd)
                    keyAlias(alias)
                    keyPassword(pwd)
                }
            }
        } else if (requireFlavor().contains("(Oss|Expert|Play)Release".toRegex())) {
            exitProcess(0)
        }
        buildTypes {
            val key = signingConfigs.findByName("release")
            if (key != null) {
                if (requireTargetAbi().isBlank()) {
                    getByName("release").signingConfig = key
                }
                getByName("debug").signingConfig = key
            }
        }
        val calculateTaskName = "calculate${requireFlavor()}APKsSHA256"
        (this as? AbstractAppExtension)?.apply {
            tasks.register(calculateTaskName) {
                val githubEnv = File(System.getenv("GITHUB_ENV") ?: "this-file-does-not-exist")

                doLast {
                    applicationVariants.all {
                        if (name.equals(requireFlavor(), ignoreCase = true)) outputs.all {
                            if (outputFile.isFile) {
                                val sha256 = sha256Hex(outputFile.readBytes())
                                val sum = File(
                                    outputFile.parentFile,
                                    outputFile.nameWithoutExtension + ".sha256sum.txt"
                                )
                                sum.writeText(sha256)
                                if (githubEnv.isFile) when {
                                    outputFile.name.contains("-arm64") -> {
                                        githubEnv.appendText("SUM_ARM64=${sum.absolutePath}\n")
                                        githubEnv.appendText("SHA256_ARM64=$sha256\n")
                                    }
                                    outputFile.name.contains("-armeabi") -> {
                                        githubEnv.appendText("SUM_ARM=${sum.absolutePath}\n")
                                        githubEnv.appendText("SHA256_ARM=$sha256\n")
                                    }
                                    outputFile.name.contains("-x86_64") -> {
                                        githubEnv.appendText("SUM_X64=${sum.absolutePath}\n")
                                        githubEnv.appendText("SHA256_X64=$sha256\n")
                                    }
                                }
                            }
                        }
                    }
                }
                dependsOn("package${requireFlavor()}")
            }
            val assemble = "assemble${requireFlavor()}"
            tasks.whenTaskAdded {
                if (name == assemble) dependsOn(calculateTaskName)
            }
        }
    }
}

fun Project.setupApp() {
    val pkgName = requireMetadata().getProperty("PACKAGE_NAME")
    val verName = requireMetadata().getProperty("VERSION_NAME")
    val verCode = (requireMetadata().getProperty("VERSION_CODE").toInt()) * 5
    android.apply {
        defaultConfig {
            applicationId = pkgName
            versionCode = verCode
            versionName = verName
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }
    setupAppCommon()

    val targetAbi = requireTargetAbi()

    android.apply {
        this as AbstractAppExtension

        buildTypes {
            getByName("release") {
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    file("proguard-rules.pro")
                )
            }
        }

        splits.abi {
            isEnable = true
            isUniversalApk = false

            if (targetAbi.isNotBlank()) {
                reset()
                include(targetAbi)
            }
        }

        flavorDimensions("vendor")
        productFlavors {
            create("oss")
            create("play") {
                versionCode = verCode - 4
            }
        }

        applicationVariants.all {
            outputs.all {
                this as BaseVariantOutputImpl
                outputFileName = outputFileName.replace(project.name, "Matsuri-$versionName")
                    .replace("-release", "")
                    .replace("-oss", "")

            }
        }

        tasks.register("downloadAssets") {
            outputs.upToDateWhen {
                requireFlavor().endsWith("Debug")
            }
            doLast {
                downloadAssets()
            }
        }
        tasks.whenTaskAdded {
            if (name == "pre${requireFlavor()}Build") {
                dependsOn("downloadAssets")
            }
        }
    }

    dependencies {
        add("implementation", kotlin("stdlib", "${rootProject.extra["kotlinVersion"]}"))
        add("implementation", project(":plugin:api"))
        add("testImplementation", "junit:junit:4.13.2")
        add("androidTestImplementation", "androidx.test.ext:junit:1.1.3")
        add("androidTestImplementation", "androidx.test:runner:1.4.0")
        add("androidTestImplementation", "androidx.test.espresso:espresso-core:3.4.0")
    }

    setupPlay()
}
