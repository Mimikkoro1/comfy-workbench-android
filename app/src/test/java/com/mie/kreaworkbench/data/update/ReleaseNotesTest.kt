package com.mie.kreaworkbench.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** r40：Release 说明切段与最小 Markdown 渲染。用 v0.4.0 的说明原文测。 */
class ReleaseNotesTest {

    private val v040Notes = """# Comfy工作台 v0.4.0

## 中文

- 内置 6 个模板：Krea2 文生图 / 图生图、Qwen 2.1 文生图 / 图片编辑、Z-Image 文生图、Moss 文生图，预填官方默认模型名，装好官方模型即可出图
- 首次打开有「快速开始」引导：连服务器 → 挑模板；之后可在「设置 › 关于」重新打开
- 局域网自动查找：同一 Wi-Fi 下一键扫出 ComfyUI 和抽卡服务
- 图库收藏，清缓存时保留收藏
- 删掉的内置模板可单个恢复；内置模板「复制并自定义」；导入的工作流可重新选择参数
- 高级抽卡：筛选项不再被重置，「连不上」误报大幅减少
- 服务器地址只在「测试连接」成功后保存
- 不再需要先导出 API 工作流才能用；示例工作流文件夹已移除

## English

- 6 built-in templates (Krea2 T2I / I2I, Qwen 2.1 T2I / Edit, Z-Image T2I, Moss T2I) with official default model names prefilled
- Quick Start guide on first launch (connect a server, pick a template); reopen it from Settings › About
- LAN auto-discovery for ComfyUI and the gacha server on the same Wi-Fi
- Gallery favorites, kept when clearing the cache
- Restore deleted built-ins one by one, "Copy & customize" built-ins, re-pick parameters of imported workflows
- Advanced gacha keeps your filter choices; far fewer false "can't connect" errors
- Server addresses are saved only after "Test connection" succeeds
- Exporting an API workflow is no longer required to get started; the example-workflows folder was removed

APK: `ComfyWorkbench_v0.4.0_release.apk` (versionCode 99) · SHA-256: `4522e46504f5c78257d2fedf9aaa2cdf4a729642a4f750a46c9535f38a91c0a4`"""

    @Test
    fun `中文界面只拿到中文段`() {
        val zh = pickSection(v040Notes, zh = true)
        assertTrue(zh.contains("内置 6 个模板"))
        assertTrue(zh.contains("局域网自动查找"))
        assertFalse(zh.contains("6 built-in templates"))
        assertFalse(zh.contains("## "))
        assertFalse(zh.contains("# Comfy工作台"))
    }

    @Test
    fun `英文界面只拿到英文段`() {
        val en = pickSection(v040Notes, zh = false)
        assertTrue(en.contains("6 built-in templates"))
        assertTrue(en.contains("LAN auto-discovery"))
        assertFalse(en.contains("内置 6 个模板"))
        assertFalse(en.contains("## "))
    }

    @Test
    fun `找不到对应语言段就取另一种语言`() {
        val notes = "# App v1.0\n\n## English\n\n- fixed a crash\n"
        val zh = pickSection(notes, zh = true)
        assertTrue(zh.contains("fixed a crash"))
        assertFalse(zh.contains("## "))
    }

    @Test
    fun `无二级标题返回全文且去掉一级标题`() {
        val notes = "# App v1.0\n\n- one\n- two\n"
        assertEquals("- one\n- two", pickSection(notes, zh = true))
        assertEquals("- one\n- two", pickSection(notes, zh = false))
    }

    @Test
    fun `二级标题别名都能识别`() {
        for (zhTitle in listOf("中文", "简体中文", "Chinese", "chinese")) {
            val notes = "# T\n\n## $zhTitle\n\n- 中文条目\n\n## English\n\n- english item\n"
            assertTrue(zhTitle, pickSection(notes, zh = true).contains("中文条目"))
            assertFalse(zhTitle, pickSection(notes, zh = true).contains("english item"))
        }
        for (enTitle in listOf("English", "english", "英文")) {
            val notes = "# T\n\n## 中文\n\n- 中文条目\n\n## $enTitle\n\n- english item\n"
            assertTrue(enTitle, pickSection(notes, zh = false).contains("english item"))
            assertFalse(enTitle, pickSection(notes, zh = false).contains("中文条目"))
        }
    }

    @Test
    fun `CRLF 行尾也能切段`() {
        val notes = "# T\r\n\r\n## 中文\r\n\r\n- 中文条目\r\n\r\n## English\r\n\r\n- english item\r\n"
        val zh = pickSection(notes, zh = true)
        assertTrue(zh.contains("中文条目"))
        assertFalse(zh.contains("english item"))
    }

    @Test
    fun `列表和小标题识别`() {
        val blocks = parseBlocks("### 修复\n- 第一条\n* 第二条\n普通段落\n")
        assertEquals(4, blocks.size)
        assertTrue(blocks[0] is NoteBlock.Heading)
        assertTrue(blocks[1] is NoteBlock.Bullet)
        assertTrue(blocks[2] is NoteBlock.Bullet)
        assertTrue(blocks[3] is NoteBlock.Paragraph)
        // #### 不是 PLAN 认的记号，按普通段落处理
        val deep = parseBlocks("#### 深层\n正文\n")
        assertTrue(deep[0] is NoteBlock.Paragraph)
    }

    @Test
    fun `粗体与行内记号`() {
        val spans = parseInline("**粗体** 和 `code` 以及 [文字](https://example.com) 尾部")
        val joined = spans.joinToString("") { it.text }
        assertEquals("粗体 和 code 以及 文字 尾部", joined)
        assertTrue(spans.first().bold)
        assertTrue(spans.drop(1).none { it.bold })
    }

    @Test
    fun `未配对的记号原样保留`() {
        assertEquals(1, parseInline("a ** b").size)
        assertEquals("a ** b", parseInline("a ** b").single().text)
        assertEquals("x`y", parseInline("x`y").single().text)
    }
}
