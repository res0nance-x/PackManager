import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import r3.content.BinaryContent
import r3.content.ContentMeta
import r3.pack.BinaryPack
import r3.pack.PackMap
import r3.source.FileSink
import r3.source.FileSource
import java.io.DataOutputStream
import java.io.File

class PackManagerTest {

	@Test
	fun testCreateAndOpenPack(@TempDir tempDir: File) {
		val inputFile1 = File(tempDir, "file1.txt").apply { writeText("Hello World 1") }
		val inputFile2 = File(tempDir, "file2.txt").apply { writeText("Hello World 2") }
		val outputFile = File(tempDir, "output.pack")

		main(arrayOf("--create", inputFile1.absolutePath, inputFile2.absolutePath, outputFile.absolutePath))

		assertTrue(outputFile.exists())
		assertTrue(BinaryPack.isNewFormat(FileSource(outputFile)), "Pack should be created in the new binary format")

		val pack = openPackFile(outputFile)
		val map = PackMap(pack)
		assertEquals(2, pack.count())
		assertEquals("Hello World 1", map["file1.txt"]?.createInputStream()?.use { it.readBytes().decodeToString() })
		assertEquals("Hello World 2", map["file2.txt"]?.createInputStream()?.use { it.readBytes().decodeToString() })
	}

	@Test
	fun testAppendPack(@TempDir tempDir: File) {
		val inputFile1 = File(tempDir, "file1.txt").apply { writeText("Alpha") }
		val targetFile = File(tempDir, "target.pack")

		main(arrayOf("--create", inputFile1.absolutePath, targetFile.absolutePath))
		assertTrue(BinaryPack.isNewFormat(FileSource(targetFile)))

		val inputFile2 = File(tempDir, "file2.txt").apply { writeText("Beta") }
		main(arrayOf("--append", inputFile2.absolutePath, targetFile.absolutePath))

		val pack = openPackFile(targetFile)
		val map = PackMap(pack)
		assertEquals(2, pack.count())
		assertEquals("Alpha", map["file1.txt"]?.createInputStream()?.use { it.readBytes().decodeToString() })
		assertEquals("Beta", map["file2.txt"]?.createInputStream()?.use { it.readBytes().decodeToString() })
	}

	@Test
	fun testEncryptedPackCreateAndAppend(@TempDir tempDir: File) {
		val inputFile1 = File(tempDir, "secret1.txt").apply { writeText("Secret 1") }
		val encryptedFile = File(tempDir, "test.epack")
		val password = "MySecurePassword123"

		main(arrayOf("--create", inputFile1.absolutePath, encryptedFile.absolutePath, password))
		assertTrue(encryptedFile.exists())

		val pack1 = openPackFile(encryptedFile, password)
		val map1 = PackMap(pack1)
		assertEquals(1, pack1.count())
		assertEquals("Secret 1", map1["secret1.txt"]?.createInputStream()?.use { it.readBytes().decodeToString() })

		val inputFile2 = File(tempDir, "secret2.txt").apply { writeText("Secret 2") }
		main(arrayOf("--append", inputFile2.absolutePath, encryptedFile.absolutePath, password))

		val pack2 = openPackFile(encryptedFile, password)
		val map2 = PackMap(pack2)
		assertEquals(2, pack2.count())
		assertEquals("Secret 1", map2["secret1.txt"]?.createInputStream()?.use { it.readBytes().decodeToString() })
		assertEquals("Secret 2", map2["secret2.txt"]?.createInputStream()?.use { it.readBytes().decodeToString() })
	}

	@Test
	fun testBackwardCompatibilityWithLegacyPack(@TempDir tempDir: File) {
		val legacyFile = File(tempDir, "legacy.pack")
		val item1 = BinaryContent("Legacy File 1".toByteArray(), "old1.txt", "txt")
		val item2 = BinaryContent("Legacy File 2".toByteArray(), "old2.txt", "txt")

		// Write in old legacy format using ContentMeta
		FileSink(legacyFile).createOutputStream().use { ostream ->
			DataOutputStream(ostream).use { dos ->
				listOf(item1, item2).forEach { item ->
					ContentMeta(item).write(dos)
					item.createInputStream().use { it.copyTo(dos) }
				}
			}
		}

		assertFalse(BinaryPack.isNewFormat(FileSource(legacyFile)), "Legacy file should not be identified as new format")

		// openPackFile should seamlessly handle legacy pack
		val pack = openPackFile(legacyFile)
		val map = PackMap(pack)
		assertEquals(2, pack.count())
		assertEquals("Legacy File 1", map["old1.txt"]?.createInputStream()?.use { it.readBytes().decodeToString() })
		assertEquals("Legacy File 2", map["old2.txt"]?.createInputStream()?.use { it.readBytes().decodeToString() })

		// Using legacy pack as input to --create produces a new format pack
		val newOutput = File(tempDir, "converted.pack")
		main(arrayOf("--create", legacyFile.absolutePath, newOutput.absolutePath))

		assertTrue(BinaryPack.isNewFormat(FileSource(newOutput)), "Output from legacy input must be new format")
		val convertedPack = openPackFile(newOutput)
		val convertedMap = PackMap(convertedPack)
		assertEquals(2, convertedPack.count())
		assertEquals("Legacy File 1", convertedMap["old1.txt"]?.createInputStream()?.use { it.readBytes().decodeToString() })
	}
}
