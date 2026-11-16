package com.example

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.CryptoManager
import com.example.data.local.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaygroundAttachmentBackupTest {

    private lateinit var context: Context
    private lateinit var db: JhGetWayDatabase
    private lateinit var prefs: AppPreferences
    private lateinit var backupManager: BackupManager

    @Before
    fun setUp() {
        CryptoManager.resetTestKeyProvider()
        context = ApplicationProvider.getApplicationContext()
        PlaygroundAttachmentManager.cleanupAllOwnedAttachments(context)
        runBlocking { AppPreferences(context).clearAll() }
        prefs = AppPreferences(context)
        db = Room.inMemoryDatabaseBuilder(context, JhGetWayDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        backupManager = createBackupManager()
    }

    @After
    fun tearDown() {
        runBlocking { prefs.clearAll() }
        db.close()
        PlaygroundAttachmentManager.cleanupAllOwnedAttachments(context)
        CryptoManager.resetTestKeyProvider()
    }

    private fun createBackupManager(
        maxFileBytes: Long = PlaygroundAttachmentManager.MAX_FILE_BYTES,
        maxTotalBytes: Long = BackupManager.MAX_BACKUP_ATTACHMENTS_TOTAL_BYTES
    ): BackupManager = BackupManager(
        db = db,
        prefs = prefs,
        appVersionName = "1.0.12",
        appVersionCode = 112,
        applicationId = "com.aistudio.jhgetway.iffndf",
        context = context,
        maxAttachmentFileBytes = maxFileBytes,
        maxBackupAttachmentsTotalBytes = maxTotalBytes
    )

    private fun createOwnedAttachmentFile(fileName: String, bytes: ByteArray): File {
        val dir = PlaygroundAttachmentManager.getAttachmentsDir(context).apply { mkdirs() }
        return File(dir, fileName).apply { writeBytes(bytes) }
    }

    private fun buildMetaJson(vararg items: PersistedAttachmentMeta): String {
        val arr = JSONArray()
        items.forEach { m ->
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("displayName", m.displayName)
                    .put("mimeType", m.mimeType)
                    .put("sizeBytes", m.sizeBytes)
                    .put("category", m.category.name)
                    .put("localFilePath", m.localFilePath)
            )
        }
        return arr.toString()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun resignBackupJson(root: JSONObject, passphrase: String): String {
        val dataObj = root.getJSONObject("data")
        // Match BackupManager canonicalJson digest helper via public re-import check: compute sorted canonical digest
        root.put("integritySha256", sha256Hex(canonicalJson(dataObj).toByteArray(Charsets.UTF_8)))
        return CryptoManager.encryptBackupArchive(root.toString(), passphrase)
    }

    private fun canonicalJson(obj: JSONObject): String {
        val keys = obj.keys().asSequence().toList().sorted()
        val sb = StringBuilder("{")
        keys.forEachIndexed { idx, key ->
            if (idx > 0) sb.append(',')
            sb.append(JSONObject.quote(key)).append(':').append(canonicalValue(obj.get(key)))
        }
        sb.append('}')
        return sb.toString()
    }

    private fun canonicalValue(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> canonicalJson(value)
        is JSONArray -> {
            val sb = StringBuilder("[")
            for (i in 0 until value.length()) {
                if (i > 0) sb.append(',')
                sb.append(canonicalValue(value.get(i)))
            }
            sb.append(']')
            sb.toString()
        }
        is String -> JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    @Test
    fun exportAndRestore_singleAttachment_roundtripsActualFileBytesAndSanitizesArchivePaths() = runBlocking {
        val fileBytes = "PNG-BINARY-CONTENT-1234567890".toByteArray()
        val srcFile = createOwnedAttachmentFile("src_diagram.png", fileBytes)
        db.playgroundDao().upsertSession(PlaygroundSessionEntity(id = "s1", title = "Diagrams"))
        db.playgroundDao().insertMessage(
            PlaygroundMessageEntity(
                id = "m1",
                sessionId = "s1",
                role = "user",
                content = "See attached",
                attachmentsMetadataJson = buildMetaJson(
                    PersistedAttachmentMeta("att1", "diagram.png", "image/png", fileBytes.size.toLong(), AttachmentCategory.IMAGE, srcFile.absolutePath)
                )
            )
        )

        val passphrase = "AttachmentBackup!2026"
        val archive = backupManager.exportEncryptedBackup(passphrase)
        // Verify decrypted JSON does NOT leak device absolute path
        val plainArchive = CryptoManager.decryptBackupArchive(archive, passphrase).plainJson
        assertFalse(plainArchive.contains(srcFile.absolutePath))

        // Delete original file & wipe DB
        assertTrue(srcFile.delete())
        db.clearAllTables()

        val preview = backupManager.previewEncryptedBackup(archive, passphrase)
        assertEquals(1, preview.attachmentCount)
        assertEquals(fileBytes.size.toLong(), preview.attachmentTotalBytes)
        assertEquals(0, preview.unavailableAttachmentCount)

        val result = backupManager.restoreEncryptedBackupDetailed(archive, passphrase, RestoreConflictStrategy.OVERWRITE_EXISTING)
        assertEquals(1, result.restoredAttachmentCount)
        assertEquals(fileBytes.size.toLong(), result.restoredAttachmentBytes)
        assertEquals(0, result.unavailableAttachmentCount)
        assertTrue(result.warnings.isEmpty())

        val restoredMeta = PlaygroundAttachmentManager.parseMetadataJson(db.playgroundDao().getAllMessagesOnce().single().attachmentsMetadataJson).single()
        assertTrue(restoredMeta.localFilePath.isNotBlank())
        val restoredFile = File(restoredMeta.localFilePath)
        assertTrue(restoredFile.exists())
        assertTrue(PlaygroundAttachmentManager.isFileWithinApprovedRoots(context, restoredFile))
        assertArrayEquals(fileBytes, restoredFile.readBytes())
    }

    @Test
    fun exportAndRestore_multipleAttachmentsAcrossMessages_restoresAllFiles() = runBlocking {
        val bytes1 = "FIRST-IMAGE-BYTES".toByteArray()
        val bytes2 = "{\"hello\":\"world\"}".toByteArray()
        val bytes3 = "PDF-DOC-BYTES-XYZ".toByteArray()
        val f1 = createOwnedAttachmentFile("f1.png", bytes1)
        val f2 = createOwnedAttachmentFile("f2.json", bytes2)
        val f3 = createOwnedAttachmentFile("f3.pdf", bytes3)

        db.playgroundDao().upsertSession(PlaygroundSessionEntity(id = "s1", title = "Multi"))
        db.playgroundDao().insertMessage(
            PlaygroundMessageEntity(
                id = "m1",
                sessionId = "s1",
                role = "user",
                content = "Two files",
                attachmentsMetadataJson = buildMetaJson(
                    PersistedAttachmentMeta("a1", "one.png", "image/png", bytes1.size.toLong(), AttachmentCategory.IMAGE, f1.absolutePath),
                    PersistedAttachmentMeta("a2", "two.json", "application/json", bytes2.size.toLong(), AttachmentCategory.DOCUMENT, f2.absolutePath)
                )
            )
        )
        db.playgroundDao().insertMessage(
            PlaygroundMessageEntity(
                id = "m2",
                sessionId = "s1",
                role = "user",
                content = "Third file",
                attachmentsMetadataJson = buildMetaJson(
                    PersistedAttachmentMeta("a3", "three.pdf", "application/pdf", bytes3.size.toLong(), AttachmentCategory.DOCUMENT, f3.absolutePath)
                )
            )
        )

        val passphrase = "MultiAttachments!2026"
        val archive = backupManager.exportEncryptedBackup(passphrase)
        PlaygroundAttachmentManager.cleanupAllOwnedAttachments(context)
        db.clearAllTables()

        val preview = backupManager.previewEncryptedBackup(archive, passphrase)
        assertEquals(3, preview.attachmentCount)
        assertEquals((bytes1.size + bytes2.size + bytes3.size).toLong(), preview.attachmentTotalBytes)

        val res = backupManager.restoreEncryptedBackupDetailed(archive, passphrase, RestoreConflictStrategy.OVERWRITE_EXISTING)
        assertEquals(3, res.restoredAttachmentCount)

        val restoredMsgs = db.playgroundDao().getAllMessagesOnce()
        val allMetas = restoredMsgs.flatMap { PlaygroundAttachmentManager.parseMetadataJson(it.attachmentsMetadataJson) }
        assertEquals(3, allMetas.size)
        assertArrayEquals(bytes1, File(allMetas.first { it.id == "a1" }.localFilePath).readBytes())
        assertArrayEquals(bytes2, File(allMetas.first { it.id == "a2" }.localFilePath).readBytes())
        assertArrayEquals(bytes3, File(allMetas.first { it.id == "a3" }.localFilePath).readBytes())
    }

    @Test
    fun exportAndRestore_emptyAttachmentList_succeedsCleanly() = runBlocking {
        db.playgroundDao().upsertSession(PlaygroundSessionEntity(id = "s1", title = "Text Only"))
        db.playgroundDao().insertMessage(
            PlaygroundMessageEntity(id = "m1", sessionId = "s1", role = "user", content = "No attachments", attachmentsMetadataJson = "[]")
        )

        val passphrase = "EmptyAttachments!2026"
        val archive = backupManager.exportEncryptedBackup(passphrase)
        db.clearAllTables()

        val preview = backupManager.previewEncryptedBackup(archive, passphrase)
        assertEquals(0, preview.attachmentCount)
        assertEquals(0L, preview.attachmentTotalBytes)

        val res = backupManager.restoreEncryptedBackupDetailed(archive, passphrase, RestoreConflictStrategy.OVERWRITE_EXISTING)
        assertEquals(0, res.restoredAttachmentCount)
        assertEquals("[]", db.playgroundDao().getAllMessagesOnce().single().attachmentsMetadataJson)
    }

    @Test
    fun exportAndRestore_missingSourceAttachmentFile_doesNotFabricateFileAndReportsWarning() = runBlocking {
        val missingPath = File(PlaygroundAttachmentManager.getAttachmentsDir(context), "missing.png").absolutePath
        db.playgroundDao().upsertSession(PlaygroundSessionEntity(id = "s1", title = "Missing"))
        db.playgroundDao().insertMessage(
            PlaygroundMessageEntity(
                id = "m1",
                sessionId = "s1",
                role = "user",
                content = "File was deleted",
                attachmentsMetadataJson = buildMetaJson(
                    PersistedAttachmentMeta("att-miss", "gone.png", "image/png", 512L, AttachmentCategory.IMAGE, missingPath)
                )
            )
        )

        val passphrase = "MissingFile!2026"
        val archive = backupManager.exportEncryptedBackup(passphrase)
        db.clearAllTables()

        val preview = backupManager.previewEncryptedBackup(archive, passphrase)
        assertEquals(0, preview.attachmentCount)
        assertEquals(1, preview.unavailableAttachmentCount)

        val res = backupManager.restoreEncryptedBackupDetailed(archive, passphrase, RestoreConflictStrategy.OVERWRITE_EXISTING)
        assertEquals(0, res.restoredAttachmentCount)
        assertEquals(1, res.unavailableAttachmentCount)
        assertTrue(res.warnings.isNotEmpty())
        val restoredMeta = PlaygroundAttachmentManager.parseMetadataJson(db.playgroundDao().getAllMessagesOnce().single().attachmentsMetadataJson).single()
        assertEquals("", restoredMeta.localFilePath)
        assertEquals("gone.png", restoredMeta.displayName)
    }

    @Test
    fun previewAndRestore_rejectCorruptAttachmentBytesAndWrongSha256() = runBlocking {
        val rawBytes = "AUTHENTIC-BYTES".toByteArray()
        val f = createOwnedAttachmentFile("auth.txt", rawBytes)
        db.playgroundDao().upsertSession(PlaygroundSessionEntity(id = "s1", title = "Integrity"))
        db.playgroundDao().insertMessage(
            PlaygroundMessageEntity(
                id = "m1",
                sessionId = "s1",
                role = "user",
                content = "Check integrity",
                attachmentsMetadataJson = buildMetaJson(
                    PersistedAttachmentMeta("att1", "auth.txt", "text/plain", rawBytes.size.toLong(), AttachmentCategory.DOCUMENT, f.absolutePath)
                )
            )
        )
        val passphrase = "TamperTest!2026"
        val archive = backupManager.exportEncryptedBackup(passphrase)
        val plain = CryptoManager.decryptBackupArchive(archive, passphrase).plainJson
        PlaygroundAttachmentManager.cleanupAllOwnedAttachments(context)
        db.clearAllTables()

        // 1. Wrong attachment SHA-256
        val wrongShaRoot = JSONObject(plain)
        wrongShaRoot.getJSONObject("data").getJSONArray("playgroundAttachments").getJSONObject(0)
            .put("sha256", "0".repeat(64))
        val wrongShaArchive = resignBackupJson(wrongShaRoot, passphrase)
        val shaErr = runCatching { backupManager.restoreEncryptedBackupDetailed(wrongShaArchive, passphrase) }.exceptionOrNull()
        assertTrue(shaErr is IllegalArgumentException)
        assertEquals(0, PlaygroundAttachmentManager.getAttachmentsDir(context).listFiles()?.size ?: 0)

        // 2. Corrupt base64 / mismatched bytes
        val corruptBytesRoot = JSONObject(plain)
        corruptBytesRoot.getJSONObject("data").getJSONArray("playgroundAttachments").getJSONObject(0)
            .put("dataBase64", Base64.encodeToString("CORRUPT!".toByteArray(), Base64.NO_WRAP))
        val corruptArchive = resignBackupJson(corruptBytesRoot, passphrase)
        val corruptErr = runCatching { backupManager.previewEncryptedBackup(corruptArchive, passphrase) }.exceptionOrNull()
        assertTrue(corruptErr is IllegalArgumentException)
    }

    @Test
    fun previewAndRestore_rejectPathTraversalInArchiveEntry() = runBlocking {
        val rawBytes = "SAFE-BYTES".toByteArray()
        val f = createOwnedAttachmentFile("safe.txt", rawBytes)
        db.playgroundDao().upsertSession(PlaygroundSessionEntity(id = "s1", title = "Traversal"))
        db.playgroundDao().insertMessage(
            PlaygroundMessageEntity(
                id = "m1",
                sessionId = "s1",
                role = "user",
                content = "Check traversal",
                attachmentsMetadataJson = buildMetaJson(
                    PersistedAttachmentMeta("att1", "safe.txt", "text/plain", rawBytes.size.toLong(), AttachmentCategory.DOCUMENT, f.absolutePath)
                )
            )
        )
        val passphrase = "TraversalTest!2026"
        val archive = backupManager.exportEncryptedBackup(passphrase)
        val plain = CryptoManager.decryptBackupArchive(archive, passphrase).plainJson
        db.clearAllTables()

        for (badPath in listOf("../escape.txt", "attachments/../../evil.txt", "/etc/passwd", "attachments\\evil.txt", "other/file.txt")) {
            val badRoot = JSONObject(plain)
            badRoot.getJSONObject("data").getJSONArray("playgroundAttachments").getJSONObject(0)
                .put("archivePath", badPath)
            val tampered = resignBackupJson(badRoot, passphrase)
            val err = runCatching { backupManager.restoreEncryptedBackupDetailed(tampered, passphrase) }.exceptionOrNull()
            assertTrue("Path '$badPath' MUST be rejected, got $err", err is IllegalArgumentException)
        }
    }

    @Test
    fun export_neverReadsOrIncludesFilesOutsideApprovedStorageRootsOrSymlinks(): Unit = runBlocking {
        val outsideDir = File(context.cacheDir, "untrusted_outside").apply { mkdirs() }
        try {
            val outsideSecret = File(outsideDir, "secret.txt").apply { writeText("TOP_SECRET_EXTERNAL_CONTENT") }

            val linkTargetDir = PlaygroundAttachmentManager.getAttachmentsDir(context).apply { mkdirs() }
            val symlinkFile = File(linkTargetDir, "sneaky_link.txt")
            symlinkFile.delete()
            val symlinkCreated = runCatching {
                Files.createSymbolicLink(symlinkFile.toPath(), outsideSecret.toPath())
                true
            }.getOrDefault(false)

            db.playgroundDao().upsertSession(PlaygroundSessionEntity(id = "s1", title = "Outside Root"))
            db.playgroundDao().insertMessage(
                PlaygroundMessageEntity(
                    id = "m-out",
                    sessionId = "s1",
                    role = "user",
                    content = "Outside file",
                    attachmentsMetadataJson = buildMetaJson(
                        PersistedAttachmentMeta("att-out", "secret.txt", "text/plain", outsideSecret.length(), AttachmentCategory.DOCUMENT, outsideSecret.absolutePath)
                    )
                )
            )
            if (symlinkCreated) {
                db.playgroundDao().insertMessage(
                    PlaygroundMessageEntity(
                        id = "m-sym",
                        sessionId = "s1",
                        role = "user",
                        content = "Symlink file",
                        attachmentsMetadataJson = buildMetaJson(
                            PersistedAttachmentMeta("att-sym", "link.txt", "text/plain", outsideSecret.length(), AttachmentCategory.DOCUMENT, symlinkFile.absolutePath)
                        )
                    )
                )
            }

            val passphrase = "OutsideRoot!2026"
            val archive = backupManager.exportEncryptedBackup(passphrase)
            val plain = CryptoManager.decryptBackupArchive(archive, passphrase).plainJson
            assertFalse("External file contents must NEVER appear in backup archive", plain.contains("TOP_SECRET_EXTERNAL_CONTENT"))

            val preview = backupManager.previewEncryptedBackup(archive, passphrase)
            assertEquals(0, preview.attachmentCount)
            assertEquals(if (symlinkCreated) 2 else 1, preview.unavailableAttachmentCount)
        } finally {
            outsideDir.deleteRecursively()
        }
    }

    @Test
    fun exportAndImport_enforcePerFileAndTotalArchiveSizeLimits() = runBlocking {
        val smallMgr = createBackupManager(maxFileBytes = 100L, maxTotalBytes = 150L)

        // 1. Per-file oversize on export
        val oversized = createOwnedAttachmentFile("big.bin", ByteArray(101) { 1 })
        db.playgroundDao().upsertSession(PlaygroundSessionEntity(id = "s1", title = "Limits"))
        db.playgroundDao().insertMessage(
            PlaygroundMessageEntity(
                id = "m1",
                sessionId = "s1",
                role = "user",
                content = "Too big",
                attachmentsMetadataJson = buildMetaJson(
                    PersistedAttachmentMeta("a1", "big.bin", "text/plain", 101L, AttachmentCategory.DOCUMENT, oversized.absolutePath)
                )
            )
        )
        val singleErr = runCatching { smallMgr.exportEncryptedBackup("LimitPass!2026") }.exceptionOrNull()
        assertTrue(singleErr is IllegalArgumentException)

        // 2. Cumulative total limit on export (two 80-byte files = 160 > 150)
        db.playgroundDao().clearMessages()
        oversized.delete()
        val ok1 = createOwnedAttachmentFile("ok1.txt", ByteArray(80) { 2 })
        val ok2 = createOwnedAttachmentFile("ok2.txt", ByteArray(80) { 3 })
        db.playgroundDao().insertMessage(
            PlaygroundMessageEntity(
                id = "m2",
                sessionId = "s1",
                role = "user",
                content = "Two 80B files",
                attachmentsMetadataJson = buildMetaJson(
                    PersistedAttachmentMeta("a2", "ok1.txt", "text/plain", 80L, AttachmentCategory.DOCUMENT, ok1.absolutePath),
                    PersistedAttachmentMeta("a3", "ok2.txt", "text/plain", 80L, AttachmentCategory.DOCUMENT, ok2.absolutePath)
                )
            )
        )
        val totalExportErr = runCatching { smallMgr.exportEncryptedBackup("LimitPass!2026") }.exceptionOrNull()
        assertTrue(totalExportErr is IllegalArgumentException)

        // 3. Cumulative limit enforced on import even when exported under larger limits
        val largeExportArchive = backupManager.exportEncryptedBackup("LimitPass!2026")
        val totalImportErr = runCatching { smallMgr.previewEncryptedBackup(largeExportArchive, "LimitPass!2026") }.exceptionOrNull()
        assertTrue(totalImportErr is IllegalArgumentException)
    }

    @Test
    fun exportAndRestore_duplicateAttachmentIdsAndFilenameCollisions_preserveBothDistinctFiles() = runBlocking {
        val dupBytesA = "DUPLICATE-ID-CONTENT-ALPHA".toByteArray()
        val dupBytesB = "DUPLICATE-ID-CONTENT-BETA".toByteArray()
        val colBytesC = "COLLISION-NAME-GAMMA".toByteArray()

        val fA = createOwnedAttachmentFile("a_file.txt", dupBytesA)
        val fB = createOwnedAttachmentFile("b_file.txt", dupBytesB)
        val fC = createOwnedAttachmentFile("c_file.txt", colBytesC)

        db.playgroundDao().upsertSession(PlaygroundSessionEntity(id = "s1", title = "Collisions"))
        db.playgroundDao().insertMessage(
            PlaygroundMessageEntity(
                id = "m1",
                sessionId = "s1",
                role = "user",
                content = "First",
                attachmentsMetadataJson = buildMetaJson(
                    PersistedAttachmentMeta("same-id", "report.txt", "text/plain", dupBytesA.size.toLong(), AttachmentCategory.DOCUMENT, fA.absolutePath)
                )
            )
        )
        db.playgroundDao().insertMessage(
            PlaygroundMessageEntity(
                id = "m2",
                sessionId = "s1",
                role = "user",
                content = "Second with same id and same display name",
                attachmentsMetadataJson = buildMetaJson(
                    PersistedAttachmentMeta("same-id", "report.txt", "text/plain", dupBytesB.size.toLong(), AttachmentCategory.DOCUMENT, fB.absolutePath),
                    PersistedAttachmentMeta("diff-id", "report.txt", "text/plain", colBytesC.size.toLong(), AttachmentCategory.DOCUMENT, fC.absolutePath)
                )
            )
        )

        val passphrase = "CollisionPass!2026"
        val archive = backupManager.exportEncryptedBackup(passphrase)
        PlaygroundAttachmentManager.cleanupAllOwnedAttachments(context)
        db.clearAllTables()

        val res = backupManager.restoreEncryptedBackupDetailed(archive, passphrase, RestoreConflictStrategy.OVERWRITE_EXISTING)
        assertEquals(3, res.restoredAttachmentCount)

        val msgs = db.playgroundDao().getAllMessagesOnce()
        val m1Meta = PlaygroundAttachmentManager.parseMetadataJson(msgs.first { it.id == "m1" }.attachmentsMetadataJson)
        val m2Meta = PlaygroundAttachmentManager.parseMetadataJson(msgs.first { it.id == "m2" }.attachmentsMetadataJson)
        val paths = listOf(m1Meta[0].localFilePath, m2Meta[0].localFilePath, m2Meta[1].localFilePath)
        assertEquals("All 3 restored files must have distinct paths", 3, paths.toSet().size)
        assertArrayEquals(dupBytesA, File(m1Meta[0].localFilePath).readBytes())
        assertArrayEquals(dupBytesB, File(m2Meta[0].localFilePath).readBytes())
        assertArrayEquals(colBytesC, File(m2Meta[1].localFilePath).readBytes())
    }

    @Test
    fun restore_databaseTransactionFailure_cleansAllStagedFilesAndPreservesExistingData() = runBlocking {
        val existingBytes = "EXISTING-FILE-STAY-SAFE".toByteArray()
        val existingFile = createOwnedAttachmentFile("existing.txt", existingBytes)
        db.playgroundDao().upsertSession(PlaygroundSessionEntity(id = "s-existing", title = "Stay"))
        db.playgroundDao().insertMessage(
            PlaygroundMessageEntity(
                id = "m-existing",
                sessionId = "s-existing",
                role = "user",
                content = "Original",
                attachmentsMetadataJson = buildMetaJson(
                    PersistedAttachmentMeta("att-ex", "existing.txt", "text/plain", existingBytes.size.toLong(), AttachmentCategory.DOCUMENT, existingFile.absolutePath)
                )
            )
        )

        // Create backup with another attachment
        val newBytes = "INCOMING-STAGED-BYTES".toByteArray()
        val newFile = createOwnedAttachmentFile("new_src.txt", newBytes)
        db.playgroundDao().insertMessage(
            PlaygroundMessageEntity(
                id = "m-new",
                sessionId = "s-existing",
                role = "user",
                content = "New",
                attachmentsMetadataJson = buildMetaJson(
                    PersistedAttachmentMeta("att-new", "new.txt", "text/plain", newBytes.size.toLong(), AttachmentCategory.DOCUMENT, newFile.absolutePath)
                )
            )
        )
        val passphrase = "RollbackPass!2026"
        val archive = backupManager.exportEncryptedBackup(passphrase)

        // Remove m-new & newFile from current state, keeping ONLY existingFile
        db.playgroundDao().clearMessages()
        db.playgroundDao().insertMessage(
            PlaygroundMessageEntity(
                id = "m-existing",
                sessionId = "s-existing",
                role = "user",
                content = "Original",
                attachmentsMetadataJson = buildMetaJson(
                    PersistedAttachmentMeta("att-ex", "existing.txt", "text/plain", existingBytes.size.toLong(), AttachmentCategory.DOCUMENT, existingFile.absolutePath)
                )
            )
        )
        newFile.delete()

        val err = runCatching {
            backupManager.restoreEncryptedBackupInternal(
                encryptedPayload = archive,
                passphrase = passphrase,
                strategy = RestoreConflictStrategy.REPLACE_ALL,
                beforeCommitHook = { throw IllegalStateException("Simulated SQLite write failure") }
            )
        }.exceptionOrNull()
        assertNotNull(err)

        // Existing DB & file preserved; NO staged or temp dirs left behind
        assertEquals(1, db.playgroundDao().getAllMessagesOnce().size)
        assertTrue(existingFile.exists())
        val attachmentsDir = PlaygroundAttachmentManager.getAttachmentsDir(context)
        val remainingEntries = attachmentsDir.listFiles()?.map { it.name }.orEmpty()
        assertEquals(listOf("existing.txt"), remainingEntries)
    }

    @Test
    fun restore_conflictStrategies_preserveStillReferencedFilesAndAvoidOrphans() = runBlocking {
        val sharedBytes = "SHARED-FILE-CONTENT".toByteArray()
        val newMsg1Bytes = "REPLACED-MSG1-NEW".toByteArray()
        val backupOnlyBytes = "BACKUP-ONLY-MSG2".toByteArray()

        // 1. Seed backup archive with m1 (new version) and m2 (new message)
        val bFile1 = createOwnedAttachmentFile("b1.txt", newMsg1Bytes)
        val bFile2 = createOwnedAttachmentFile("b2.txt", backupOnlyBytes)
        db.playgroundDao().upsertSession(PlaygroundSessionEntity(id = "s1", title = "Conflicts"))
        db.playgroundDao().insertMessage(
            PlaygroundMessageEntity(
                id = "m1",
                sessionId = "s1",
                role = "user",
                content = "Backup M1",
                attachmentsMetadataJson = buildMetaJson(
                    PersistedAttachmentMeta("att1", "m1_new.txt", "text/plain", newMsg1Bytes.size.toLong(), AttachmentCategory.DOCUMENT, bFile1.absolutePath)
                )
            )
        )
        db.playgroundDao().insertMessage(
            PlaygroundMessageEntity(
                id = "m2",
                sessionId = "s1",
                role = "user",
                content = "Backup M2",
                attachmentsMetadataJson = buildMetaJson(
                    PersistedAttachmentMeta("att2", "m2.txt", "text/plain", backupOnlyBytes.size.toLong(), AttachmentCategory.DOCUMENT, bFile2.absolutePath)
                )
            )
        )
        val passphrase = "ConflictStrat!2026"
        val archive = backupManager.exportEncryptedBackup(passphrase)

        // 2. Replace current device state:
        // m1 AND m-local-keeper BOTH reference `sharedFile`, while m1-unique references `orphanCandidateFile`
        PlaygroundAttachmentManager.cleanupAllOwnedAttachments(context)
        db.clearAllTables()
        val sharedFile = createOwnedAttachmentFile("shared.txt", sharedBytes)
        db.playgroundDao().upsertSession(PlaygroundSessionEntity(id = "s1", title = "Local"))
        db.playgroundDao().insertMessage(
            PlaygroundMessageEntity(
                id = "m1",
                sessionId = "s1",
                role = "user",
                content = "Local M1",
                attachmentsMetadataJson = buildMetaJson(
                    PersistedAttachmentMeta("att-shared", "shared.txt", "text/plain", sharedBytes.size.toLong(), AttachmentCategory.DOCUMENT, sharedFile.absolutePath)
                )
            )
        )
        db.playgroundDao().insertMessage(
            PlaygroundMessageEntity(
                id = "m-local-keeper",
                sessionId = "s1",
                role = "user",
                content = "Local Keeper Sharing File",
                attachmentsMetadataJson = buildMetaJson(
                    PersistedAttachmentMeta("att-shared", "shared.txt", "text/plain", sharedBytes.size.toLong(), AttachmentCategory.DOCUMENT, sharedFile.absolutePath)
                )
            )
        )

        // Strategy A: SKIP_CONFLICTS -> m1 is skipped (no orphan staged for m1!), m2 imported, sharedFile preserved
        val skipRes = backupManager.restoreEncryptedBackupDetailed(archive, passphrase, RestoreConflictStrategy.SKIP_CONFLICTS)
        assertEquals(1, skipRes.restoredAttachmentCount) // Only m2's attachment!
        assertTrue(sharedFile.exists())
        val m1AfterSkip = db.playgroundDao().getAllMessagesOnce().first { it.id == "m1" }
        assertEquals("Local M1", m1AfterSkip.content)

        // Strategy B: OVERWRITE_EXISTING -> m1 overwritten, BUT m-local-keeper STILL references sharedFile, so sharedFile MUST survive!
        val overwriteRes = backupManager.restoreEncryptedBackupDetailed(archive, passphrase, RestoreConflictStrategy.OVERWRITE_EXISTING)
        assertEquals(2, overwriteRes.restoredAttachmentCount)
        assertTrue("File still referenced by m-local-keeper must NOT be deleted on overwrite", sharedFile.exists())

        // Strategy C: REPLACE_ALL -> m-local-keeper is wiped, so sharedFile has zero remaining references and IS cleaned up cleanly!
        val replaceRes = backupManager.restoreEncryptedBackupDetailed(archive, passphrase, RestoreConflictStrategy.REPLACE_ALL)
        assertEquals(2, replaceRes.restoredAttachmentCount)
        assertFalse("Unreferenced old file should be cleaned up on REPLACE_ALL", sharedFile.exists())
    }

    @Test
    fun restore_legacyBackupWithoutAttachmentPayloads_remainsBackwardCompatibleWithClearWarning() = runBlocking {
        // Simulate legacy V2 backup exported before attachment file bundling existed
        val legacyMsg = JSONObject()
            .put("id", "msg-legacy")
            .put("sessionId", "sess-legacy")
            .put("role", "user")
            .put("content", "Legacy message with diagram")
            .put(
                "attachmentsMetadataJson",
                JSONArray().put(
                    JSONObject()
                        .put("id", "legacy-att")
                        .put("displayName", "old_diagram.png")
                        .put("mimeType", "image/png")
                        .put("sizeBytes", 2048)
                        .put("category", "IMAGE")
                        .put("localFilePath", "")
                ).toString()
            )
        val dataObj = JSONObject()
            .put("playgroundSessions", JSONArray().put(JSONObject().put("id", "sess-legacy").put("title", "Legacy Session")))
            .put("playgroundMessages", JSONArray().put(legacyMsg))

        val root = JSONObject()
            .put("backupFormatVersion", 2)
            .put("version", 2)
            .put("schemaVersion", 5)
            .put("exportedAt", 1700000000000L)
            .put("kdf", "PBKDF2WithHmacSHA256")
            .put("kdfIterations", CryptoManager.BENCHMARKED_BACKUP_KDF_ITERATIONS)
            .put("manifest", JSONObject().put("applicationId", "com.aistudio.jhgetway.iffndf"))
            .put("data", dataObj)

        val passphrase = "LegacyCompat!2026"
        val legacyArchive = resignBackupJson(root, passphrase)

        val preview = backupManager.previewEncryptedBackup(legacyArchive, passphrase)
        assertEquals(0, preview.attachmentCount)
        assertEquals(1, preview.unavailableAttachmentCount)

        val res = backupManager.restoreEncryptedBackupDetailed(legacyArchive, passphrase, RestoreConflictStrategy.OVERWRITE_EXISTING)
        assertEquals(2, res.importedCount) // session + message
        assertEquals(0, res.restoredAttachmentCount)
        assertEquals(1, res.unavailableAttachmentCount)
        assertTrue(res.warnings.any { it.contains("unavailable", ignoreCase = true) })

        val restoredMeta = PlaygroundAttachmentManager.parseMetadataJson(db.playgroundDao().getAllMessagesOnce().single().attachmentsMetadataJson).single()
        assertEquals("old_diagram.png", restoredMeta.displayName)
        assertEquals("", restoredMeta.localFilePath)
        assertEquals(0, PlaygroundAttachmentManager.getAttachmentsDir(context).listFiles()?.size ?: 0)
    }
}
