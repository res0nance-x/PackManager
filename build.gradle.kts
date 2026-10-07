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

tasks.jar {
	manifest {
		attributes["Main-Class"] = "MainKt"
	}
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
	from(configurations.runtimeClasspath.map { config ->
		config.map { if (it.isDirectory) it else zipTree(it) }
	})
}
