package com.soywiz.korlibs

import com.soywiz.korlibs.modules.*
import com.soywiz.korlibs.targets.*
import org.gradle.api.*
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCompilation
import java.io.*
import com.moowork.gradle.node.*
import com.moowork.gradle.node.npm.*

open class KorlibsPluginNoNativeNoAndroid : BaseKorlibsPlugin(nativeEnabled = false, androidEnabled = false)
open class KorlibsPluginNoNative : BaseKorlibsPlugin(nativeEnabled = false, androidEnabled = true)
open class KorlibsPlugin : BaseKorlibsPlugin(nativeEnabled = true, androidEnabled = true)

open class BaseKorlibsPlugin(val nativeEnabled: Boolean, val androidEnabled: Boolean) : Plugin<Project> {
    override fun apply(project: Project) = project {
        val korlibs = KorlibsExtension(this, nativeEnabled, androidEnabled)
        extensions.add("korlibs", korlibs)

        plugins.apply("kotlin-multiplatform")
		plugins.apply("com.moowork.node")

		//println("KotlinVersion.CURRENT: ${KotlinVersion.CURRENT}")
		//println("KORLIBS_KOTLIN_VERSION: $KORLIBS_KOTLIN_VERSION")

		//project.setProperty("KORLIBS_KOTLIN_VERSION", KORLIBS_KOTLIN_VERSION)

        configureKorlibsRepos()

        // Platforms
        configureTargetCommon()
        configureTargetAndroid()
        if (nativeEnabled) {
            configureTargetNative()
        }
        configureTargetJavaScript()
        configureTargetJVM()

        // Publishing
        configurePublishing()
		configureBintrayTools()

		// Create version
		configureCreateVersion()
    }
}

val globalKorlibsDir: File by lazy { File(System.getProperty("user.home"), ".korlibs").apply { mkdirs() } }

class KorlibsExtension(val project: Project, val nativeEnabled: Boolean, val androidEnabled: Boolean) {
    val korlibsDir: File get() = globalKorlibsDir
    //init { println("KorlibsExtension:${project.name},nativeEnabled=$nativeEnabled,androidEnabled=$androidEnabled") }
	val prop_sdk_dir = System.getProperty("sdk.dir")
	val prop_ANDROID_HOME = System.getenv("ANDROID_HOME")
    var hasAndroid = androidEnabled && ((prop_sdk_dir != null) || (prop_ANDROID_HOME != null))
	val tryAndroidSdkDir = File(System.getProperty("user.home"), "/Library/Android/sdk")
	val linuxEnabled get() = com.soywiz.korlibs.targets.linuxEnabled

    init {
        if (!hasAndroid && androidEnabled) {
            if (tryAndroidSdkDir.exists()) {
                File(project.rootDir, "local.properties").writeText("sdk.dir=${tryAndroidSdkDir.absolutePath}")
                hasAndroid = true
            }
        }

		project.logger.info("hasAndroid: $hasAndroid, sdk.dir=$prop_sdk_dir, ANDROID_HOME=$prop_ANDROID_HOME, tryAndroidSdkDir=$tryAndroidSdkDir (${tryAndroidSdkDir.exists()})")
    }

    fun dependencyProject(name: String) = project {
        dependencies {
            add("commonMainApi", project(name))
            add("commonTestImplementation", project(name))
        }
    }

	val KORLIBS_KOTLIN_VERSION get() = com.soywiz.korlibs.KORLIBS_KOTLIN_VERSION
	val isKotlinDev get() = KORLIBS_KOTLIN_VERSION.contains("-release")
	val LINUX_DESKTOP_NATIVE_TARGETS = if (linuxEnabled) listOf("linuxX64") else listOf()
    val MACOS_DESKTOP_NATIVE_TARGETS = listOf("macosX64")
    //val WINDOWS_DESKTOP_NATIVE_TARGETS = listOf("mingwX64", "mingwX86")
    val WINDOWS_DESKTOP_NATIVE_TARGETS = listOf("mingwX64")
    val DESKTOP_NATIVE_TARGETS = LINUX_DESKTOP_NATIVE_TARGETS + MACOS_DESKTOP_NATIVE_TARGETS + WINDOWS_DESKTOP_NATIVE_TARGETS
    val BASE_IOS_TARGETS = listOf("iosArm64", "iosArm32", "iosX64")
	val WATCHOS_TARGETS = listOf("watchosArm64", "watchosArm32", "watchosX64")
	val TVOS_TARGETS = listOf("tvosArm64", "tvosX64")
	val IOS_TARGETS = BASE_IOS_TARGETS + WATCHOS_TARGETS + TVOS_TARGETS
    val ALL_NATIVE_TARGETS = IOS_TARGETS + DESKTOP_NATIVE_TARGETS
    val ALL_ANDROID_TARGETS = if (hasAndroid) listOf("android") else listOf()
    val JS_TARGETS = listOf("js")
    val JVM_TARGETS = listOf("jvm")
    val COMMON_TARGETS = listOf("metadata")
    val ALL_TARGETS = ALL_ANDROID_TARGETS + JS_TARGETS + JVM_TARGETS + COMMON_TARGETS + ALL_NATIVE_TARGETS

    @JvmOverloads
    fun dependencyMulti(group: String, name: String, version: String, targets: List<String> = ALL_TARGETS, suffixCommonRename: Boolean = false, androidIsJvm: Boolean = false) = project {
        dependencies {
            for (target in targets) {
                val base = when (target) {
                    "metadata" -> "common"
                    else -> target
                }
                val suffix = when {
                    target == "android" && androidIsJvm -> "-jvm"
                    target == "metadata" && suffixCommonRename -> "-common"
                    else -> "-${target.toLowerCase()}"
                }

                val packed = "$group:$name$suffix:$version"
                add("${base}MainApi", packed)
                add("${base}TestImplementation", packed)
            }
        }
    }

    @JvmOverloads
    fun dependencyMulti(dependency: String, targets: List<String> = ALL_TARGETS) {
        val (group, name, version) = dependency.split(":", limit = 3)
        return dependencyMulti(group, name, version, targets)
    }

	@JvmOverloads
	fun dependencyNodeModule(name: String, version: String) = project {
		val node = extensions.getByType(NodeExtension::class.java)

		val installNodeModule = tasks.create<NpmTask>("installJs${name.capitalize()}") {
			onlyIf { !File(node.nodeModulesDir, name).exists() }
			setArgs(arrayListOf("install", "$name@$version"))
		}

		tasks.getByName("jsNodeTest").dependsOn(installNodeModule)
	}

    data class CInteropTargets(val name: String, val targets: List<String>)

    val cinterops = arrayListOf<CInteropTargets>()


    fun dependencyCInterops(name: String, targets: List<String>) = project {
        cinterops += CInteropTargets(name, targets)
        for (target in targets) {
            (kotlin.targets[target].compilations["main"] as KotlinNativeCompilation).apply {
                cinterops.apply {
                    maybeCreate(name).apply {
                    }
                }
            }
        }
    }

    @JvmOverloads
    fun dependencyCInteropsExternal(dependency: String, cinterop: String, targets: List<String> = ALL_NATIVE_TARGETS) {
        dependencyMulti("$dependency:cinterop-$cinterop@klib", targets)
    }

    @JvmOverloads
    fun exposeVersion(name: String = project.name) {
        project.projectDir["src/commonMain/kotlin/com/soywiz/$name/internal/${name.capitalize()}Version.kt"].text = """
            package com.soywiz.$name.internal

            internal const val ${name.toUpperCase()}_VERSION = "${project.version}"
        """.trimIndent()
    }
}

val Project.korlibs get() = extensions.getByType(KorlibsExtension::class.java)
fun Project.korlibs(callback: KorlibsExtension.() -> Unit) = korlibs.apply(callback)
val Project.hasAndroid get() = korlibs.hasAndroid
