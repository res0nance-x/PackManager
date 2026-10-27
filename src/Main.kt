import r3.content.BinaryContent
import r3.content.Content
import r3.content.ContentMeta
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
import r3.util.humanReadableSize
import java.awt.Desktop
import java.io.DataOutputStream
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
			contentList.sortWith { a, b -> a.path.compareTo(b.path) }
			val totalBytes = contentList.sumOf { it.length }
			val isEncrypted = outputFile.name.endsWith(".epack", ignoreCase = true) || !passwordStr.isNullOrEmpty()
			val tracker = ConsoleProgressBar(totalBytes, contentList.size)

			println("Creating archive: ${outputFile.name} (${totalBytes.humanReadableSize()}, ${contentList.size} files)...")

			val sink = FileSink(outputFile, append = false)
			if (isEncrypted) {
				val finalPasswordStr = passwordStr.takeIf { !it.isNullOrEmpty() }
					?: readPassword("Enter password for encrypted pack creation: ")
				val password = Password256(finalPasswordStr.toByteArray().hash256())
				createEncryptedPack(contentList, password, sink) { copied, _, file ->
					tracker.update(copied, file)
				}
			} else {
				createPackWithProgress(contentList, sink) { copied, _, file ->
					tracker.update(copied, file)
				}
			}
			tracker.finish()
		}

		"--append" -> {
			val appendArgs = args.drop(1)
			if (appendArgs.size < 2) {
				println("Error: Missing required arguments for --append")
				printUsage()
				return
			}
			val targetFile: File
			val passwordStr: String?
			val inputFiles: List<File>

			if (appendArgs.size >= 3 && (appendArgs[appendArgs.size - 2].endsWith(
					".pack",
					ignoreCase = true
				) || appendArgs[appendArgs.size - 2].endsWith(".epack", ignoreCase = true))
			) {
				targetFile = File(appendArgs[appendArgs.size - 2])
				passwordStr = appendArgs.last()
				inputFiles = appendArgs.take(appendArgs.size - 2).map { File(it) }
			} else {
				targetFile = File(appendArgs.last())
				passwordStr = null
				inputFiles = appendArgs.take(appendArgs.size - 1).map { File(it) }
			}

			if (!targetFile.exists()) {
				println("Error: Target file ${targetFile.path} does not exist for append operation.")
				return
			}

			val packs = inputFiles.mapNotNull { loadInputAsPack(it) }
			if (packs.isEmpty()) {
				println("Error: No valid inputs provided for append operation.")
				return
			}
			val contentList = ArrayList<Content>()
			for (p in packs) {
				contentList.addAll(p)
			}
			contentList.sortWith { a, b -> a.path.compareTo(b.path) }
			val totalBytes = contentList.sumOf { it.length }
			val isEncrypted = targetFile.name.endsWith(".epack", ignoreCase = true) || !passwordStr.isNullOrEmpty()
			val tracker = ConsoleProgressBar(totalBytes, contentList.size)

			println("Appending to archive: ${targetFile.name} (${totalBytes.humanReadableSize()}, ${contentList.size} files)...")

			if (isEncrypted) {
				val finalPasswordStr = passwordStr.takeIf { !it.isNullOrEmpty() }
					?: readPassword("Enter password for encrypted pack append: ")
				val password = Password256(finalPasswordStr.toByteArray().hash256())
				appendToEncryptedPack(contentList, password, targetFile) { copied, _, file ->
					tracker.update(copied, file)
				}
			} else {
				val sink = FileSink(targetFile, append = true)
				createPackWithProgress(contentList, sink) { copied, _, file ->
					tracker.update(copied, file)
				}
			}
			tracker.finish()
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
			pack.sortedWith { a, b -> a.path.compareTo(b.path) }.forEach { println(it) }
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

			val totalCount = pack.count()
			var extractedCount = 0
			val buffer = ByteArray(PACK_BUFFER_SIZE)
			for (content in pack) {
				val path = content.path
				val destFile = File(outputDir, path)
				destFile.parentFile?.mkdirs()
				content.createInputStream().use { input ->
					FileOutputStream(destFile).buffered(PACK_BUFFER_SIZE).use { output ->
						var read = input.read(buffer)
						while (read >= 0) {
							if (read > 0) output.write(buffer, 0, read)
							read = input.read(buffer)
						}
					}
				}
				extractedCount++
				println("Extracted ($extractedCount/$totalCount): $path")
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
		file.name.endsWith(".mhtml", ignoreCase = true) || file.name.endsWith(".mht", ignoreCase = true) -> MhtmlPack(file)
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
	} else if (packFile.name.endsWith(".mhtml", ignoreCase = true) || packFile.name.endsWith(".mht", ignoreCase = true)) {
		MhtmlPack(packFile)
	} else if (packFile.name.endsWith(".zip", ignoreCase = true)) {
		ZipPack(packFile)
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
		  PackManager --view <packFile|epackFile|mhtmlFile> [password] [--template <dir|zip|pack|epack>] [--override]
		  PackManager --list <packFile|epackFile|mhtmlFile> [password]
		  PackManager --extract <packFile|epackFile|mhtmlFile> <outputDir> [password]
	""".trimIndent()
	)
}

const val PACK_BUFFER_SIZE = 512 * 1024 // Large buffer to minimize HDD seek thrashing

fun createPackWithProgress(
	contentList: List<Content>,
	sink: Sink,
	bufferSize: Int = PACK_BUFFER_SIZE,
	onProgress: (bytesCopied: Long, totalBytes: Long, currentFile: String) -> Unit = { _, _, _ -> }
) {
	val totalBytes = contentList.sumOf { it.length }
	val totalFiles = contentList.size
	val buffer = ByteArray(bufferSize)

	sink.createOutputStream().buffered(bufferSize).use { ostream ->
		DataOutputStream(ostream).use { dos ->
			var bytesCopied = 0L
			var fileCount = 0

			contentList.forEach { item ->
				ContentMeta(item).write(dos)
				item.createInputStream().use { istream ->
					var read = istream.read(buffer)
					while (read >= 0) {
						if (read > 0) {
							dos.write(buffer, 0, read)
							bytesCopied += read
							onProgress(bytesCopied, totalBytes, item.path)
						}
						read = istream.read(buffer)
					}
				}
				fileCount++
				if (totalBytes == 0L) {
					onProgress(fileCount.toLong(), totalFiles.toLong(), item.path)
				} else {
					onProgress(bytesCopied, totalBytes, item.path)
				}
			}
		}
	}
}

class ConsoleProgressBar(private val totalBytes: Long, private val totalFiles: Int) {
	private val startTime = System.currentTimeMillis()
	private var lastPrintTime = 0L
	private var lastLineLen = 0

	fun update(bytesCopied: Long, currentFile: String, force: Boolean = false) {
		val now = System.currentTimeMillis()
		if (!force && now - lastPrintTime < 150) return // Throttle console updates (~7 FPS)
		lastPrintTime = now

		val elapsedSec = (now - startTime).coerceAtLeast(1) / 1000.0
		val speed = bytesCopied / elapsedSec
		val pct = if (totalBytes > 0) {
			((bytesCopied.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100)
		} else {
			100
		}

		val barWidth = 25
		val filled = if (totalBytes > 0) {
			((bytesCopied.toDouble() / totalBytes) * barWidth).toInt().coerceIn(0, barWidth)
		} else {
			barWidth
		}
		val bar = StringBuilder()
		for (i in 0 until barWidth) {
			when {
				i < filled -> bar.append("=")
				i == filled && filled < barWidth -> bar.append(">")
				else -> bar.append(" ")
			}
		}

		val etaStr = if (speed > 1024 && totalBytes > bytesCopied) {
			val remainingSec = ((totalBytes - bytesCopied) / speed).toLong()
			formatDuration(remainingSec)
		} else {
			"--:--"
		}

		val speedStr = "${speed.toLong().humanReadableSize()}/s"
		val copiedStr = bytesCopied.humanReadableSize()
		val totalStr = totalBytes.humanReadableSize()

		val displayFile = if (currentFile.length > 30) {
			"..." + currentFile.takeLast(27)
		} else {
			currentFile
		}

		val line = "\r[%s] %3d%%  %s / %s  (%s, ETA: %s)  %s".format(
			bar.toString(), pct, copiedStr, totalStr, speedStr, etaStr, displayFile
		)
		val padding = " ".repeat((lastLineLen - line.length).coerceAtLeast(0))
		print(line + padding)
		lastLineLen = line.length
	}

	fun finish() {
		val totalSec = ((System.currentTimeMillis() - startTime) / 1000.0).coerceAtLeast(0.001)
		val avgSpeed = (totalBytes / totalSec).toLong().humanReadableSize()
		val clearLine = "\r" + " ".repeat(lastLineLen.coerceAtLeast(80)) + "\r"
		print(clearLine)
		println("Archive created successfully in ${formatDuration(totalSec.toLong())} ($avgSpeed/s avg, $totalFiles files).")
	}

	private fun formatDuration(seconds: Long): String {
		val mins = seconds / 60
		val secs = seconds % 60
		val hours = mins / 60
		return if (hours > 0) {
			"%dh %02dm".format(hours, mins % 60)
		} else {
			"%02dm %02ds".format(mins, secs)
		}
	}
}

fun createEncryptedPack(
	pack: List<Content>,
	pass: Password256,
	sink: Sink,
	onProgress: (bytesCopied: Long, totalBytes: Long, currentFile: String) -> Unit = { _, _, _ -> }
) {
	val sequence = EncryptedSequence.createSequence(pass)
	val encryptedSink = EncryptedSink(sequence, sink)
	createPackWithProgress(pack, encryptedSink, onProgress = onProgress)
}

fun createEncryptedPack(pack: Pack, pass: Password256, sink: Sink) {
	createEncryptedPack(pack.toList(), pass, sink)
}

fun appendToEncryptedPack(
	contentList: List<Content>,
	pass: Password256,
	targetFile: File,
	onProgress: (bytesCopied: Long, totalBytes: Long, currentFile: String) -> Unit = { _, _, _ -> }
) {
	val initialPos = targetFile.length()
	val sequence = EncryptedSequence.createSequence(pass)
	val sink = FileSink(targetFile, append = true)
	val encryptedSink = EncryptedSink(sequence, sink, initialPos = initialPos)
	createPackWithProgress(contentList, encryptedSink, onProgress = onProgress)
}

fun appendToEncryptedPack(pack: Pack, pass: Password256, targetFile: File) {
	appendToEncryptedPack(pack.toList(), pass, targetFile)
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