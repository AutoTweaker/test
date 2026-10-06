import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

plugins {
	kotlin("jvm") version "2.4.20"
	kotlin("plugin.serialization") version "2.4.20"
}

val runtimePaths = providers.gradleProperty("autotweaker.runtime")
	.orElse(providers.environmentVariable("AUTOTWEAKER_RUNTIME"))
	.orNull ?: error("未指定产物目录：-Pautotweaker.runtime=<dir>[:<dir>...] 或环境变量 AUTOTWEAKER_RUNTIME")

val runtimeDirs = runtimePaths.split(File.pathSeparator).map { file(it) }

kotlin {
	jvmToolchain(25)
}

configurations.all {
	resolutionStrategy {
		force("org.jetbrains.kotlin:kotlin-reflect:2.3.20")
	}
}

dependencies {
	testImplementation(
		files(runtimeDirs.flatMap { dir -> fileTree(dir) { include("*.jar") }.files }.distinctBy { it.name })
	)

	testImplementation(kotlin("test"))
	testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
	testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
	testImplementation("io.mockk:mockk:1.14.11")
	testImplementation("io.ktor:ktor-client-mock:3.6.0")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val testPluginInstallDir = layout.buildDirectory.dir("test-plugin-install")

val prepareTestPlugins = tasks.register("prepareTestPlugins") {
	description = "生成测试 JVM 的插件环境：PluginLoader 无插件时直接退出进程"
	val dirs = runtimeDirs
	val installDir = testPluginInstallDir
	outputs.dir(installDir)
	doLast {
		val coreJar = dirs.asSequence()
			.flatMap { dir -> dir.listFiles().orEmpty().asSequence() }
			.first { it.name.startsWith("autotweaker-core-") && it.name.endsWith(".jar") }
		val appVersion = JarFile(coreJar).use { it.manifest.mainAttributes.getValue("Implementation-Version") }
		val pluginJar = installDir.get().file("plugins/test-fixture.jar").asFile
		pluginJar.parentFile.mkdirs()
		JarOutputStream(pluginJar.outputStream()).use { jar ->
			jar.putNextEntry(JarEntry("META-INF/autotweaker/plugin.properties"))
			jar.write("id=test.fixture\nversion=1.0.0\napiVersion=$appVersion\n".toByteArray())
			jar.closeEntry()
		}
	}
}

tasks.test {
	useJUnitPlatform()
	maxHeapSize = "2g"
	dependsOn(prepareTestPlugins)
	environment("AUTOTWEAKER_INSTALL_PATH", testPluginInstallDir.get().asFile.absolutePath)
	jvmArgs(
		"-Dnet.bytebuddy.experimental=true",
		"-Djava.security.egd=file:/dev/./urandom",
		"--add-opens", "java.base/java.util=ALL-UNNAMED",
		"--add-opens", "java.base/java.lang=ALL-UNNAMED",
		"--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
	)
}
