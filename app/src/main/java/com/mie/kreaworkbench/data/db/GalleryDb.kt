package com.mie.kreaworkbench.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.BitmapFactory

data class JobRow(
    val clientJobId: String,
    val jobId: String,
    val mode: String,
    val state: String,
    val requestJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    val total: Int,
    val done: Int,
    val error: String,
)

data class ImageRow(
    val id: Long,
    val jobId: String,
    val clientJobId: String,
    val idx: Int,
    val mode: String,
    val prompt: String,
    val paramsJson: String,
    val seed: Long,
    val width: Int,
    val height: Int,
    val remoteFilename: String,
    val remoteSubfolder: String,
    val localPath: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val savedToAlbum: Boolean,
    val albumUri: String,
    val kind: String = "image", // image | video | text（round6）
    val text: String = "",      // kind=="text" 的正文
)

class GalleryDb(context: Context) : SQLiteOpenHelper(context, "krea_gallery.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE jobs (
                client_job_id TEXT PRIMARY KEY,
                job_id TEXT,
                mode TEXT,
                state TEXT,
                request_json TEXT,
                created_at INTEGER,
                updated_at INTEGER,
                total INTEGER,
                done INTEGER,
                error TEXT
            )""",
        )
        db.execSQL(
            """CREATE TABLE images (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                job_id TEXT,
                client_job_id TEXT,
                idx INTEGER,
                mode TEXT,
                prompt TEXT,
                params_json TEXT,
                seed INTEGER,
                width INTEGER,
                height INTEGER,
                remote_filename TEXT,
                remote_subfolder TEXT,
                local_path TEXT,
                size_bytes INTEGER,
                created_at INTEGER,
                saved_to_album INTEGER DEFAULT 0,
                album_uri TEXT,
                kind TEXT DEFAULT 'image',
                text TEXT DEFAULT ''
            )""",
        )
        db.execSQL("CREATE INDEX idx_images_created ON images(created_at)")
        createTombstones(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createTombstones(db)
        if (oldVersion < 3) upgradeTo3(db)
    }

    /** v3：images 加 kind/text 列；tombstones 主键带 kind（同 idx 的图/视频/文本互不挡）。 */
    private fun upgradeTo3(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE images ADD COLUMN kind TEXT DEFAULT 'image'")
        db.execSQL("ALTER TABLE images ADD COLUMN text TEXT DEFAULT ''")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS tombstones_v3 (
                job_id TEXT NOT NULL,
                kind TEXT NOT NULL DEFAULT 'image',
                idx INTEGER NOT NULL,
                remote_filename TEXT,
                remote_subfolder TEXT,
                deleted_at INTEGER,
                PRIMARY KEY (job_id, kind, idx)
            )""",
        )
        db.execSQL(
            """INSERT OR REPLACE INTO tombstones_v3
                (job_id, kind, idx, remote_filename, remote_subfolder, deleted_at)
                SELECT job_id, 'image', idx, remote_filename, remote_subfolder, deleted_at FROM tombstones""",
        )
        db.execSQL("DROP TABLE tombstones")
        db.execSQL("ALTER TABLE tombstones_v3 RENAME TO tombstones")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tomb_file ON tombstones(remote_filename, remote_subfolder)")
    }

    private fun createTombstones(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS tombstones (
                job_id TEXT NOT NULL,
                kind TEXT NOT NULL DEFAULT 'image',
                idx INTEGER NOT NULL,
                remote_filename TEXT,
                remote_subfolder TEXT,
                deleted_at INTEGER,
                PRIMARY KEY (job_id, kind, idx)
            )""",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_tomb_file ON tombstones(remote_filename, remote_subfolder)",
        )
    }

    fun insertJob(row: JobRow) {
        val cv = ContentValues().apply {
            put("client_job_id", row.clientJobId)
            put("job_id", row.jobId)
            put("mode", row.mode)
            put("state", row.state)
            put("request_json", row.requestJson)
            put("created_at", row.createdAt)
            put("updated_at", row.updatedAt)
            put("total", row.total)
            put("done", row.done)
            put("error", row.error)
        }
        writableDatabase.insertWithOnConflict("jobs", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateJob(clientJobId: String, block: (JobRow) -> JobRow) {
        val cur = job(clientJobId) ?: return
        insertJob(block(cur).copy(updatedAt = System.currentTimeMillis()))
    }

    fun job(clientJobId: String): JobRow? {
        readableDatabase.rawQuery("SELECT * FROM jobs WHERE client_job_id=?", arrayOf(clientJobId)).use { c ->
            if (!c.moveToFirst()) return null
            return c.toJob()
        }
    }

    fun needsWork(): List<JobRow> {
        val sql = """SELECT * FROM jobs WHERE state NOT IN ('success','partial','failed','cancelled')
            OR client_job_id IN (
                SELECT j.client_job_id FROM jobs j
                WHERE j.state IN ('success','partial') AND j.done > (
                    (SELECT COUNT(*) FROM images i WHERE i.client_job_id = j.client_job_id)
                    + (SELECT COUNT(*) FROM tombstones t WHERE t.job_id != '' AND t.job_id = j.job_id)
                )
            ) ORDER BY created_at ASC"""
        return queryJobs(sql)
    }

    fun images(): List<ImageRow> = queryImages("SELECT * FROM images ORDER BY created_at DESC, id DESC")

    fun imagesForClient(clientJobId: String): List<ImageRow> =
        queryImages("SELECT * FROM images WHERE client_job_id=? ORDER BY idx ASC", arrayOf(clientJobId))

    fun image(id: Long): ImageRow? {
        readableDatabase.rawQuery("SELECT * FROM images WHERE id=?", arrayOf(id.toString())).use { c ->
            if (!c.moveToFirst()) return null
            return c.toImage()
        }
    }

    /** 行已在且（文件类）文件还在才算「已下载」；text 行只要行在即可。 */
    fun hasLocal(jobId: String, idx: Int, kind: String = "image"): Boolean {
        readableDatabase.rawQuery(
            "SELECT kind, local_path FROM images WHERE job_id=? AND idx=? AND kind=?",
            arrayOf(jobId, idx.toString(), kind),
        ).use { c ->
            if (!c.moveToFirst()) return false
            if (c.getString(0).orEmpty() == "text") return true
            val path = c.getString(1).orEmpty()
            return path.isNotBlank() && java.io.File(path).exists()
        }
    }

    fun isTombstoned(
        jobId: String,
        idx: Int,
        kind: String = "image",
        filename: String = "",
        subfolder: String = "",
    ): Boolean {
        if (jobId.isNotBlank()) {
            readableDatabase.rawQuery(
                "SELECT 1 FROM tombstones WHERE job_id=? AND kind=? AND idx=? LIMIT 1",
                arrayOf(jobId, kind, idx.toString()),
            ).use { c ->
                if (c.moveToFirst()) return true
            }
            if (filename.isNotBlank()) {
                readableDatabase.rawQuery(
                    "SELECT 1 FROM tombstones WHERE job_id=? AND remote_filename=? AND remote_subfolder=? LIMIT 1",
                    arrayOf(jobId, filename, subfolder),
                ).use { c ->
                    if (c.moveToFirst()) return true
                }
            }
        }
        return false
    }

    fun addTombstone(row: ImageRow) {
        val jobId = row.jobId.ifBlank { row.clientJobId }
        if (jobId.isBlank()) return
        val cv = ContentValues().apply {
            put("job_id", jobId)
            put("kind", row.kind)
            put("idx", row.idx)
            put("remote_filename", row.remoteFilename)
            put("remote_subfolder", row.remoteSubfolder)
            put("deleted_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("tombstones", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun tombstoneAndDelete(row: ImageRow) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            addTombstone(row)
            db.delete("images", "id=?", arrayOf(row.id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun insertImage(row: ImageRow): Long {
        val cv = ContentValues().apply {
            put("job_id", row.jobId)
            put("client_job_id", row.clientJobId)
            put("idx", row.idx)
            put("mode", row.mode)
            put("prompt", row.prompt)
            put("params_json", row.paramsJson)
            put("seed", row.seed)
            put("width", row.width)
            put("height", row.height)
            put("remote_filename", row.remoteFilename)
            put("remote_subfolder", row.remoteSubfolder)
            put("local_path", row.localPath)
            put("size_bytes", row.sizeBytes)
            put("created_at", row.createdAt)
            put("saved_to_album", if (row.savedToAlbum) 1 else 0)
            put("album_uri", row.albumUri)
            put("kind", row.kind)
            put("text", row.text)
        }
        return writableDatabase.insert("images", null, cv)
    }

    fun markSaved(id: Long, uri: String) {
        val cv = ContentValues().apply {
            put("saved_to_album", 1)
            put("album_uri", uri)
        }
        writableDatabase.update("images", cv, "id=?", arrayOf(id.toString()))
    }

    fun deleteImages(ids: List<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { db.delete("images", "id=?", arrayOf(it.toString())) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteAllImages() {
        writableDatabase.delete("images", null, null)
    }

    /**
     * 文本卡不做长期保留（round7 fix2）：切换工作流时清空全部 kind=text 行，返回清掉的数量。
     * 墓碑必须有——删行后 needsWork 对账会出现「done > 行数」，reconcile 重跑任务时 saveTexts
     * 会从 /history 把文字重新插回来；isTombstoned 挡住它（图片/视频行不碰）。
     */
    fun purgeTextRows(): Int {
        val rows = queryImages("SELECT * FROM images WHERE kind='text'")
        for (row in rows) tombstoneAndDelete(row)
        return rows.size
    }

    /**
     * 启动自愈（direct12）：历史遗留的 kind=image 且 width/height<=0 的行——
     * 本地文件在且解得出像素 → 回填真实宽高；文件在但解不出 → 删文件 + 墓碑删行；文件不在 → 不动。
     * 返回发生变更（回填或删除）的行数；无坏行时近乎零成本，每次启动跑都可以。
     */
    fun healZeroSizedImages(): Int {
        val rows = queryImages("SELECT * FROM images WHERE kind='image' AND width<=0 AND height<=0")
        var changed = 0
        for (row in rows) {
            val f = java.io.File(row.localPath)
            if (row.localPath.isBlank() || !f.exists()) continue
            var w = 0
            var h = 0
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, opts)
                w = opts.outWidth
                h = opts.outHeight
            } catch (_: Exception) {
            }
            if (w > 0 && h > 0) {
                val cv = ContentValues().apply {
                    put("width", w)
                    put("height", h)
                }
                writableDatabase.update("images", cv, "id=?", arrayOf(row.id.toString()))
            } else {
                f.delete()
                tombstoneAndDelete(row)
            }
            changed++
        }
        return changed
    }

    fun oldest(): ImageRow? {
        readableDatabase.rawQuery("SELECT * FROM images ORDER BY created_at ASC, id ASC LIMIT 1", null).use { c ->
            if (!c.moveToFirst()) return null
            return c.toImage()
        }
    }

    fun totalBytes(): Long {
        readableDatabase.rawQuery("SELECT COALESCE(SUM(size_bytes),0) FROM images", null).use { c ->
            c.moveToFirst()
            return c.getLong(0)
        }
    }

    private fun queryJobs(sql: String): List<JobRow> =
        readableDatabase.rawQuery(sql, null).use { c ->
            buildList {
                while (c.moveToNext()) add(c.toJob())
            }
        }

    private fun queryImages(sql: String, args: Array<String>? = null): List<ImageRow> =
        readableDatabase.rawQuery(sql, args).use { c ->
            buildList {
                while (c.moveToNext()) add(c.toImage())
            }
        }
}

private fun android.database.Cursor.toJob() = JobRow(
    clientJobId = getString(0).orEmpty(),
    jobId = getString(1).orEmpty(),
    mode = getString(2).orEmpty(),
    requestJson = getString(4).orEmpty(),
    state = getString(3).orEmpty(),
    createdAt = getLong(5),
    updatedAt = getLong(6),
    total = getInt(7),
    done = getInt(8),
    error = getString(9).orEmpty(),
)

private fun android.database.Cursor.toImage() = ImageRow(
    id = getLong(0),
    jobId = getString(1).orEmpty(),
    clientJobId = getString(2).orEmpty(),
    idx = getInt(3),
    mode = getString(4).orEmpty(),
    prompt = getString(5).orEmpty(),
    paramsJson = getString(6).orEmpty(),
    seed = getLong(7),
    width = getInt(8),
    height = getInt(9),
    remoteFilename = getString(10).orEmpty(),
    remoteSubfolder = getString(11).orEmpty(),
    localPath = getString(12).orEmpty(),
    sizeBytes = getLong(13),
    createdAt = getLong(14),
    savedToAlbum = getInt(15) == 1,
    albumUri = getString(16).orEmpty(),
    kind = getString(17)?.ifBlank { "image" } ?: "image",
    text = getString(18).orEmpty(),
)
