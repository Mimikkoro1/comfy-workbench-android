package com.mie.kreaworkbench.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

class GalleryDb(context: Context) : SQLiteOpenHelper(context, "krea_gallery.db", null, 4) {

    init {
        // round19：开 WAL（治本）。
        //
        // 不开 WAL 时 `SQLiteDatabase` 的连接池只有一条连接，整个 App 的读和写全挤在同一条
        // 连接上，`CursorWindow` 也按这条连接共享。于是一个正在遍历全表的游标（2MB 窗口约
        // 装 140 行），只要另一条线程在同一连接上写了库，窗口内容就与游标持有的位置对不上，
        // 下一次 `moveToNext()` / `getLong()` 直接报
        // `IllegalStateException: Couldn't read row N, col 0 from CursorWindow`。
        //
        // 开了 WAL 之后读写走不同连接、游标各用各的窗口，这条崩溃路径从根上消失；
        // 下面的 [dbLock] 是第二道保险（万一哪天漏加锁，或者设备禁用了 WAL）。
        // 必须在第一次 getWritableDatabase()/getReadableDatabase() 之前调用，故放 init。
        setWriteAheadLoggingEnabled(true)
    }

    /**
     * 数据库串行锁（round18 引入，round19 扩到「全部读写」）。
     *
     * round18 只把「遍历式查询」串行化了，漏了一件要命的事：**写操作没有上锁**。
     * 而 round18 又把批量删除改成「删第 1 张就 bump revision」，于是真机时序变成：
     *   删第 1 张 → 写库 → bump → 两个观察者立刻开始全表读 → **删第 2~5 张仍在写库**
     * 读游标与写事务在同一连接上并发 → 窗口错位 → 崩。这也解释了为什么单独一张一张删不崩
     * （bump 之后没有后续写操作了），只有多选删除 100% 崩。
     *
     * 所以现在**所有**数据库访问都走 [read] / [write]：读读、读写、写写全部串行。
     * 不在锁外的另一个理由：非 WAL 下短查询（`image(id)`、`oldest()`、`totalBytes()`）
     * 一样会在这条连接上开游标、一样会顶掉别人正在用的窗口，
     * 所以 round18 注释里「短查询不必走这里」那句是错的，round19 一并纠正。
     */
    private val dbLock = Any()

    /** 所有读的唯一入口：上锁 + 取读连接，避免漏加锁。 */
    private fun <T> read(block: (SQLiteDatabase) -> T): T = synchronized(dbLock) { block(readableDatabase) }

    /**
     * 所有写的唯一入口。`synchronized` 可重入，所以嵌套调用（`tombstoneAndDelete` 内部调
     * `addTombstone`、`updateJob` 内部调 `job`/`insertJob`）不会自锁死。
     */
    private fun <T> write(block: (SQLiteDatabase) -> T): T = synchronized(dbLock) { block(writableDatabase) }

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
        db.execSQL("CREATE INDEX idx_images_client ON images(client_job_id)")
        db.execSQL("CREATE INDEX idx_images_job_idx ON images(job_id, idx, kind)")
        createTombstones(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createTombstones(db)
        if (oldVersion < 3) upgradeTo3(db)
        if (oldVersion < 4) upgradeTo4(db)
    }

    /**
     * v4（round18）：给 NEEDS_WORK_SQL 的关联子查询补索引。
     * 以前 images 只有 created_at 一个索引，tombstones 只有 (remote_filename, remote_subfolder)，
     * 而 needsWork 每 2 秒要被 GenerationService 调两次，每次都全表扫 jobs×images×tombstones。
     */
    private fun upgradeTo4(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_images_client ON images(client_job_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_images_job_idx ON images(job_id, idx, kind)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tomb_job ON tombstones(job_id)")
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
        // round18：needsWork 按 job_id 数墓碑，原来没有这个索引
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tomb_job ON tombstones(job_id)")
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
        write { it.insertWithOnConflict("jobs", null, cv, SQLiteDatabase.CONFLICT_REPLACE) }
    }

    /** 读-改-写整体留在锁内，避免两条线程交错改同一行 job。 */
    fun updateJob(clientJobId: String, block: (JobRow) -> JobRow) {
        synchronized(dbLock) {
            val cur = job(clientJobId) ?: return
            insertJob(block(cur).copy(updatedAt = System.currentTimeMillis()))
        }
    }

    fun job(clientJobId: String): JobRow? = read { db ->
        db.rawQuery("SELECT * FROM jobs WHERE client_job_id=?", arrayOf(clientJobId)).use { c ->
            if (!c.moveToFirst()) return@read null
            c.toJob()
        }
    }

    suspend fun needsWork(): List<JobRow> {
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

    suspend fun images(): List<ImageRow> = queryImages("SELECT * FROM images ORDER BY created_at DESC, id DESC")

    /** 从旧到新（缓存淘汰用，round18）：淘汰策略 evictionVictims 依赖这个顺序。 */
    suspend fun imagesAscending(): List<ImageRow> = queryImages("SELECT * FROM images ORDER BY created_at ASC, id ASC")

    suspend fun imagesForClient(clientJobId: String): List<ImageRow> =
        queryImages("SELECT * FROM images WHERE client_job_id=? ORDER BY idx ASC", arrayOf(clientJobId))

    /**
     * 只要 local_path 的非挂起版本：引擎在 `put()`/通知这类本来就是同步的路径上按任务取图片路径，
     * 走同一把数据库锁（round18 引入，round19 并入 [read]）。
     */
    fun imagePathsForClient(clientJobId: String): List<String> = read { db ->
        db.rawQuery(
            "SELECT local_path FROM images WHERE client_job_id=? ORDER BY idx ASC",
            arrayOf(clientJobId),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(c.getString(0).orEmpty())
            }
        }
    }

    /**
     * 单行读。round19：以前没上锁——它是 19 列查询，照样会在同一连接上开游标；
     * 画廊批量删除时每张都调它，正好和「bump 之后启动的全表读」叠在一起。
     */
    fun image(id: Long): ImageRow? = read { db ->
        db.rawQuery("SELECT * FROM images WHERE id=?", arrayOf(id.toString())).use { c ->
            if (!c.moveToFirst()) return@read null
            c.toImage()
        }
    }

    /** 行已在且（文件类）文件还在才算「已下载」；text 行只要行在即可。 */
    fun hasLocal(jobId: String, idx: Int, kind: String = "image"): Boolean = read { db ->
        db.rawQuery(
            "SELECT kind, local_path FROM images WHERE job_id=? AND idx=? AND kind=?",
            arrayOf(jobId, idx.toString(), kind),
        ).use { c ->
            if (!c.moveToFirst()) return@read false
            if (c.getString(0).orEmpty() == "text") return@read true
            val path = c.getString(1).orEmpty()
            path.isNotBlank() && java.io.File(path).exists()
        }
    }

    fun isTombstoned(
        jobId: String,
        idx: Int,
        kind: String = "image",
        filename: String = "",
        subfolder: String = "",
    ): Boolean = read { db ->
        if (jobId.isNotBlank()) {
            db.rawQuery(
                "SELECT 1 FROM tombstones WHERE job_id=? AND kind=? AND idx=? LIMIT 1",
                arrayOf(jobId, kind, idx.toString()),
            ).use { c ->
                if (c.moveToFirst()) return@read true
            }
            if (filename.isNotBlank()) {
                db.rawQuery(
                    "SELECT 1 FROM tombstones WHERE job_id=? AND remote_filename=? AND remote_subfolder=? LIMIT 1",
                    arrayOf(jobId, filename, subfolder),
                ).use { c ->
                    if (c.moveToFirst()) return@read true
                }
            }
        }
        false
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
        write { it.insertWithOnConflict("tombstones", null, cv, SQLiteDatabase.CONFLICT_REPLACE) }
    }

    fun tombstoneAndDelete(row: ImageRow) {
        // round19：整个事务都在锁内。以前只锁读不锁写，批量删除时「写第 2~5 张」
        // 与「bump 唤起的全表读」在同一连接上并发，正是本轮真机崩溃的现场。
        write { db ->
            db.beginTransaction()
            try {
                addTombstone(row)
                db.delete("images", "id=?", arrayOf(row.id.toString()))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
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
        return write { it.insert("images", null, cv) }
    }

    fun markSaved(id: Long, uri: String) {
        val cv = ContentValues().apply {
            put("saved_to_album", 1)
            put("album_uri", uri)
        }
        write { it.update("images", cv, "id=?", arrayOf(id.toString())) }
    }

    // round18：删掉 deleteImages / deleteAllImages 两个「删行不留墓碑」的入口。
    // 它们是「重启后 App 变慢」的根因：needsWork 靠
    // 「jobs.done > 图片行数 + 墓碑数」判断任务是否还有产物没拿全，
    // 无墓碑删行会让这个差值永远为正 → 任务永远算「未完成」，
    // 每次冷启动都重新下载刚被淘汰/清掉的文件。所有删行一律走 tombstoneAndDelete。

    /**
     * 文本卡不做长期保留（round7 fix2）：切换工作流时清空全部 kind=text 行，返回清掉的数量。
     * 墓碑必须有——删行后 needsWork 对账会出现「done > 行数」，reconcile 重跑任务时 saveTexts
     * 会从 /history 把文字重新插回来；isTombstoned 挡住它（图片/视频行不碰）。
     */
    suspend fun purgeTextRows(): Int {
        val rows = queryImages("SELECT * FROM images WHERE kind='text'")
        for (row in rows) tombstoneAndDelete(row)
        return rows.size
    }

    /**
     * 启动自愈（direct12）：历史遗留的 kind=image 且 width/height<=0 的行——
     * 本地文件在且解得出像素 → 回填真实宽高；文件在但解不出 → 删文件 + 墓碑删行；文件不在 → 不动。
     * 返回发生变更（回填或删除）的行数；无坏行时近乎零成本，每次启动跑都可以。
     */
    suspend fun healZeroSizedImages(): Int {
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
                write { it.update("images", cv, "id=?", arrayOf(row.id.toString())) }
            } else {
                f.delete()
                tombstoneAndDelete(row)
            }
            changed++
        }
        return changed
    }

    fun oldest(): ImageRow? = read { db ->
        db.rawQuery("SELECT * FROM images ORDER BY created_at ASC, id ASC LIMIT 1", null).use { c ->
            if (!c.moveToFirst()) return@read null
            c.toImage()
        }
    }

    fun totalBytes(): Long = read { db ->
        db.rawQuery("SELECT COALESCE(SUM(size_bytes),0) FROM images", null).use { c ->
            c.moveToFirst()
            c.getLong(0)
        }
    }

    private suspend fun queryJobs(sql: String): List<JobRow> = withContext(Dispatchers.IO) {
        read { db ->
            db.rawQuery(sql, null).use { c ->
                buildList {
                    while (c.moveToNext()) add(c.toJob())
                }
            }
        }
    }

    private suspend fun queryImages(sql: String, args: Array<String>? = null): List<ImageRow> = withContext(Dispatchers.IO) {
        read { db ->
            db.rawQuery(sql, args).use { c ->
                buildList {
                    while (c.moveToNext()) add(c.toImage())
                }
            }
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
