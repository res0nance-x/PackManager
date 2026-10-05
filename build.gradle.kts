plugins {
	kotlin("jvm") version "2.4.0"
	application
}

repositories {
	mavenCentral()
}

dependencies {
	implementation(project(":R3"))
}

sourceSets {
	main {
		kotlin.srcDirs("src")
		resources.srcDirs("resources")
	}
}

kotlin {
	jvmToolchain(25)
	compilerOptions {
		freeCompilerArgs.add("-opt-in=kotlin.ExperimentalStdlibApi")
	}
}

application {
	mainClass.set("MainKt")
}
