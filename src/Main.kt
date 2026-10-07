import r3.content.BinaryContent
import r3.content.Content
import r3.content.FileContent
import r3.encryption.EncryptedSink
import r3.encryption.EncryptedSource
import r3.hash.hash256
import r3.http.HandlerFactory
import r3.http.WebServer
import r3.math.EncryptedSequence
import r3.pack.*
import r3.pke.Password256
import r3.source.FileSink
import r3.source.FileSource
import r3.source.Sink
import r3.source.Source
import java.awt.Desktop
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import kotlin.concurrent.thread

fun main(args: Array<String>) {
	if (args.isEmpty()) {
		printUsage()
		return
	}

	when (args[0]) {
		"--create" -> {
			val createArgs = args.drop(1)
			if (createArgs.size < 2) {
				println("Error: Missing required arguments for --create")
				printUsage()
				return
			}
			val outputFile: File
			val passwordStr: String?
			val inputFiles: List<File>

			if (createArgs.size >= 3 && (createArgs[createArgs.size - 2].endsWith(
					".pack",
					ignoreCase = true
				) || createArgs[createArgs.size - 2].endsWith(".epack", ignoreCase = true))
			) {
				outputFile = File(createArgs[createArgs.size - 2])
				passwordStr = createArgs.last()
				inputFiles = createArgs.take(createArgs.size - 2).map { File(it) }
			} else {
				outputFile = File(createArgs.last())
				passwordStr = null
				inputFiles = createArgs.take(createArgs.size - 1).map { File(it) }
			}
			val packs = inputFiles.mapNotNull { loadInputAsPack(it) }
			if (packs.isEmpty()) {
				println("Error: No valid inputs provided for archive creation.")
				return
			}
			val contentList = ArrayList<Content>()
			for (p in packs) {
				contentList.addAll(p)
			}
			val sink = FileSink(outputFile, append = false)
			val isEncrypted = outputFile.name.endsWith(".epack", ignoreCase = true) || !passwordStr.isNullOrEmpty()
			if (isEncrypted) {
				val finalPasswordStr = passwordStr.takeIf { !it.isNullOrEmpty() }
					?: readPassword("Enter password for encrypted pack creation: ")
				val password = Password256(finalPasswordStr.toByteArray().hash256())
				createEncryptedPack(RAMPack(contentList), password, sink)
			} else {
				BinaryPack.create(RAMPack(contentList), sink)
			}
			println("Archive created successfully.")
		}

		"--view" -> {
			var packFile: File? = null
			var passwordStr: String? = null
			var templatePack: Pack? = null
			var overrideTemplate = false
			var i = 1
			while (i < args.size) {
				when (args[i]) {
					"-t", "--template" -> {
						if (i + 1 < args.size) {
							val tFile = File(args[++i])
							templatePack = loadTemplatePack(tFile)
						}
					}

					"-o", "--override" -> {
						overrideTemplate = true
					}

					else -> {
						if (packFile == null) {
							packFile = File(args[i])
						} else if (passwordStr == null) {
							passwordStr = args[i]
						}
					}
				}
				i++
			}

			if (packFile == null) {
				println("Error: Missing required arguments for --view")
				printUsage()
				return
			}

			if (!packFile.exists()) {
				println("Error: Pack file ${packFile.path} does not exist.")
				return
			}
			val pack = openPackFile(packFile, passwordStr, "view")
			viewPack(pack, templatePack, overrideTemplate)
		}

		"--list" -> {
			if (args.size < 2) {
				println("Error: Missing required arguments for --list")
				printUsage()
				return
			}
			val packFile = File(args[1])
			val passwordStr = if (args.size >= 3) args[2] else null

			if (!packFile.exists()) {
				println("Error: Pack file ${packFile.path} does not exist.")
				return
			}
			val pack = openPackFile(packFile, passwordStr, "list")
			pack.forEach { println(it) }
		}

		"--extract" -> {
			if (args.size < 3) {
				println("Error: Missing required arguments for --extract")
				printUsage()
				return
			}
			val packFile = File(args[1])
			val outputDir = File(args[2])
			val passwordStr = if (args.size >= 4) args[3] else null

			if (!packFile.exists()) {
				println("Error: Pack file ${packFile.path} does not exist.")
				return
			}
			val pack = openPackFile(packFile, passwordStr, "extract")

			for (content in pack) {
				val path = content.path
				val destFile = File(outputDir, path)
				destFile.parentFile?.mkdirs()
				content.createInputStream().use { input ->
					FileOutputStream(destFile).use { output ->
						input.copyTo(output)
					}
				}
				println("Extracted: $path")
			}
			println("Extraction completed.")
		}

		else -> {
			println("Unknown option: ${args[0]}")
			printUsage()
		}
	}
}

fun loadInputAsPack(file: File): Pack? {
	if (!file.exists()) {
		println("Warning: Input path ${file.path} does not exist. Skipping.")
		return null
	}
	return when {
		file.isDirectory -> DirPack(file)
		file.name.endsWith(".zip", ignoreCase = true) -> ZipPack(file)
		file.name.endsWith(".epack", ignoreCase = true) -> openPackFile(file, actionName = "load input pack")
		file.name.endsWith(".pack", ignoreCase = true) -> BinaryPack(FileSource(file))
		file.isFile -> {
			val pack = RAMPack(listOf(FileContent(file, root = "", path = file.name)))
			pack
		}

		else -> {
			println("Warning: Unsupported input type for ${file.name}. Skipping.")
			null
		}
	}
}

fun openPackFile(packFile: File, passwordStr: String? = null, actionName: String = "access"): Pack {
	val source = FileSource(packFile)
	val isEncrypted = packFile.name.endsWith(".epack", ignoreCase = true)
	return if (isEncrypted) {
		val finalPassword = passwordStr.takeIf { !it.isNullOrEmpty() }
			?: readPassword("Enter password to $actionName encrypted pack: ")
		val pass = Password256(finalPassword.toByteArray().hash256())
		accessEncryptedBinaryPack(source, pass)
	} else {
		BinaryPack(source)
	}
}

private fun readPassword(prompt: String = "Enter password: "): String {
	val console = System.console()
	if (console != null) {
		val chars = console.readPassword(prompt)
		if (chars != null) {
			return String(chars)
		}
	}
	print(prompt)
	System.out.flush()
	return readlnOrNull() ?: ""
}

fun getDefaultTemplatePack(): Pack {
	val resourcePath = "playlist/index.html"
	val stream = Thread.currentThread().contextClassLoader?.getResourceAsStream(resourcePath)
		?: ClassLoader.getSystemResourceAsStream(resourcePath)
	val bytes = stream?.use { it.readBytes() }
		?: error("Failed to load default playlist template resource from classpath ($resourcePath)")

	return RAMPack(listOf(BinaryContent(bytes, "index.html", "html")))
}

private fun loadTemplatePack(file: File): Pack? {
	if (file.name.equals("playlist", ignoreCase = true)) {
		return getDefaultTemplatePack()
	}
	if (!file.exists()) {
		println("Warning: Template file/directory ${file.path} does not exist.")
		return null
	}
	return when {
		file.isDirectory -> DirPack(file)
		file.name.endsWith(".zip", ignoreCase = true) -> ZipPack(file)
		file.name.endsWith(".epack", ignoreCase = true) -> openPackFile(file, actionName = "load template")
		file.name.endsWith(".pack", ignoreCase = true) -> BinaryPack(FileSource(file))
		else -> {
			println("Warning: Unsupported template type for ${file.name}")
			null
		}
	}
}

private fun printUsage() {
	println(
		"""
		Usage:
		  PackManager --create <input1> [input2 ...] <output.pack|epack> [password]
		  PackManager --append <input1> [input2 ...] <target.pack|epack> [password]
		  PackManager --view <packFile|epackFile> [password] [--template <dir|zip|pack|epack>] [--override]
		  PackManager --list <packFile|epackFile> [password]
		  PackManager --extract <packFile|epackFile> <outputDir> [password]
	""".trimIndent()
	)
}

fun createEncryptedPack(pack: Pack, pass: Password256, sink: Sink) {
	val sequence = EncryptedSequence.createSequence(pass)
	val encryptedSink = EncryptedSink(sequence, sink)
	BinaryPack.create(pack, encryptedSink)
}

fun accessEncryptedBinaryPack(src: Source, pass: Password256): Pack {
	val sequence = EncryptedSequence.createSequence(pass)
	val encryptedSrc = EncryptedSource(sequence, src)
	return BinaryPack(encryptedSrc)
}

fun viewPack(pack: Pack, templatePack: Pack? = null, override: Boolean = false) {
	val tmpDir = File(System.getProperty("java.io.tmpdir"))
	val ws = WebServer(null, 0, tmpDir)
	ws.handlers.add(HandlerFactory.createLogRouter())
	ws.handlers.add(HandlerFactory.createWelcomeHandler())
	if (override && templatePack != null) {
		ws.handlers.add(HandlerFactory.createPackHandler(templatePack))
		ws.handlers.add(HandlerFactory.createPackHandler(pack))
	} else {
		ws.handlers.add(HandlerFactory.createPackHandler(pack))
		if (templatePack != null) {
			ws.handlers.add(HandlerFactory.createPackHandler(templatePack))
		}
	}
	ws.handlers.add(HandlerFactory.createPackHandler(getDefaultTemplatePack()))
	thread {
		ws.start(0, false)
		val port = ws.listeningPort
		println("Web server started at http://localhost:$port/")
		Desktop.getDesktop().browse(URI("http://localhost:$port/"))
	}
}