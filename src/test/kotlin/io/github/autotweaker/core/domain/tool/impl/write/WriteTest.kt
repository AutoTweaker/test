/*
 * AutoTweaker
 * Copyright (C) 2026  WhiteElephant-abc
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.autotweaker.core.domain.tool.impl.write

import io.github.autotweaker.api.base.unifiedDiff
import io.github.autotweaker.api.generated.tool.args.WriteArgs
import io.github.autotweaker.api.tool.Tool
import io.github.autotweaker.api.types.Sha256
import io.github.autotweaker.api.types.exception.PathOutsideWorkspaceException
import io.github.autotweaker.api.types.llm.ContentPart
import io.github.autotweaker.api.types.tool.write.WriteRequest
import io.github.autotweaker.core.domain.port.FileContent
import io.github.autotweaker.core.domain.port.exception.FileNotFoundException
import io.github.autotweaker.core.domain.tool.ServiceContainer
import io.github.autotweaker.core.domain.tool.port.FileSystemService
import io.github.autotweaker.core.test.TestServices
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WriteTest {
	companion object {
		init {
			TestServices.init()
		}
	}
	
	private val write = Write()
	private val path = Path.of("test.txt")
	
	private fun container(fs: FileSystemService): ServiceContainer {
		val c = ServiceContainer()
		c.register(fs)
		return c
	}
	
	private fun List<ContentPart>.text(): String =
		filterIsInstance<ContentPart.Text>().joinToString("") { it.content }
	
	private fun sha(content: String) = Sha256.hash(content)
	
	private fun args(
		filePath: String = "test.txt",
		sha256: String? = null,
		content: String,
		unescapeUnicode: Boolean? = null,
		lenientUnescape: Boolean? = null,
	) = WriteArgs.Default(filePath, sha256, content, unescapeUnicode, lenientUnescape)
	
	/** [file] 为 null 表示目标文件不存在，读取会抛出 [FileNotFoundException]。 */
	private fun mockFs(file: FileContent?): FileSystemService {
		val fs = mockk<FileSystemService>()
		every { fs.normalize(any()) } returns path
		every { fs.displayPath(any()) } returns path
		if (file == null) coEvery { fs.read(path) } throws FileNotFoundException(NoSuchFileException(path.toFile()))
		else coEvery { fs.read(path) } returns file
		return fs
	}
	
	private fun existing(content: String, truncated: Boolean = false) =
		FileContent(content, truncated, sha(content))
	
	private fun decodeRequest(result: Tool.ResolveResult): WriteRequest {
		val ready = assertIs<Tool.ResolveResult.Ready>(result)
		return Json.decodeFromJsonElement(WriteRequest.serializer(), ready.result)
	}
	
	private fun assertRejected(result: Tool.ResolveResult, reason: String) {
		val rejected = assertIs<Tool.ResolveResult.Rejected>(result)
		assertEquals(reason, rejected.reason)
	}
	
	private fun request(request: WriteRequest): JsonElement =
		Json.encodeToJsonElement(WriteRequest.serializer(), request)
	
	// region resolve - create
	
	@Test
	fun `resolve non existent file without sha256 returns Ready with null expected`() = runTest {
		val result = write.resolve(container(mockFs(null)), args(content = "hello"))
		
		val request = decodeRequest(result)
		assertEquals(path, request.path)
		assertEquals(path, request.displayPath)
		assertNull(request.expected)
		assertEquals("hello", request.content)
	}
	
	@Test
	fun `resolve existing file without sha256 rejected`() = runTest {
		val result = write.resolve(container(mockFs(existing("old"))), args(content = "new"))
		
		assertRejected(result, "文件 $path 已存在，如需覆写请使用read工具读取后提供sha256")
	}
	
	// endregion
	
	// region resolve - update
	
	@Test
	fun `resolve existing file with matching 8 digit sha256 returns Ready with old content`() = runTest {
		val old = "old content"
		val result = write.resolve(
			container(mockFs(existing(old))),
			args(sha256 = sha(old).toString().take(8), content = "new content")
		)
		
		val request = decodeRequest(result)
		assertEquals(old to sha(old), request.expected)
		assertEquals("new content", request.content)
	}
	
	@Test
	fun `resolve truncated existing file returns Ready with null old content`() = runTest {
		val old = "old content"
		val result = write.resolve(
			container(mockFs(FileContent(old, truncated = true, sha256 = sha(old)))),
			args(sha256 = sha(old).toString().take(8), content = "new content")
		)
		
		val request = decodeRequest(result)
		val expected = assertNotNull(request.expected)
		assertNull(expected.first)
		assertEquals(sha(old), expected.second)
	}
	
	// endregion
	
	// region resolve - rejection
	
	@Test
	fun `resolve sha256 shorter than 8 rejected before reading file`() = runTest {
		val fs = mockk<FileSystemService>()
		every { fs.normalize(any()) } returns path
		every { fs.displayPath(any()) } returns path
		
		val result = write.resolve(container(fs), args(sha256 = "1234", content = "x"))
		
		assertRejected(result, "必须提供至少8位的哈希值，你提供的 '1234' 只有 4 位")
		coVerify(exactly = 0) { fs.read(any()) }
	}
	
	@Test
	fun `resolve full sha256 mismatch rejected with short hash hint`() = runTest {
		val wrong = sha("other").toString()
		val result = write.resolve(container(mockFs(existing("line"))), args(sha256 = wrong, content = "x"))
		
		assertRejected(
			result,
			"覆盖文件 $path 失败，文件当前SHA256哈希值不以 '$wrong' 为前缀，文件已被外部更新，请重新读取文件\n" +
					"你提供了64位的哈希字符串，实际上你只需要提供完整哈希的前8位来避免复制错误"
		)
	}
	
	@Test
	fun `resolve 8 digit sha256 mismatch rejected without short hash hint`() = runTest {
		val wrong = sha("other").toString().take(8)
		val result = write.resolve(container(mockFs(existing("line"))), args(sha256 = wrong, content = "x"))
		
		assertRejected(
			result,
			"覆盖文件 $path 失败，文件当前SHA256哈希值不以 '$wrong' 为前缀，文件已被外部更新，请重新读取文件"
		)
	}
	
	@Test
	fun `resolve normalize failure rejected`() = runTest {
		val fs = mockk<FileSystemService>()
		every { fs.normalize(any()) } throws IllegalArgumentException("bad path")
		
		val result = write.resolve(container(fs), args(content = "x"))
		
		assertRejected(result, "提供的路径不合法，请检查提供的路径参数")
	}
	
	@Test
	fun `resolve read failure outside workspace rethrows instead of treating as new file`() = runTest {
		val fs = mockk<FileSystemService>()
		every { fs.normalize(any()) } returns path
		every { fs.displayPath(any()) } returns path
		coEvery { fs.read(path) } throws PathOutsideWorkspaceException(path)
		
		assertFailsWith<PathOutsideWorkspaceException> {
			write.resolve(container(fs), args(content = "x"))
		}
	}
	
	// endregion
	
	// region resolve - unicode escape
	
	@Test
	fun `resolve unicode escape enabled decodes content`() = runTest {
		val result = write.resolve(
			container(mockFs(null)),
			args(content = "\\u4E2D", unescapeUnicode = true)
		)
		
		assertEquals("中", decodeRequest(result).content)
	}
	
	@Test
	fun `resolve unicode escape disabled keeps literal backslash`() = runTest {
		val result = write.resolve(
			container(mockFs(null)),
			args(content = "\\u4E2D", unescapeUnicode = false)
		)
		
		assertEquals("\\u4E2D", decodeRequest(result).content)
	}
	
	@Test
	fun `resolve strict unicode escape with invalid sequence rejected`() = runTest {
		val result = write.resolve(
			container(mockFs(null)),
			args(content = "\\uZZZZ", unescapeUnicode = true)
		)
		
		val rejected = assertIs<Tool.ResolveResult.Rejected>(result)
		assertTrue(rejected.reason.startsWith("未知或不合法的转义："))
	}
	
	@Test
	fun `resolve lenient unicode escape keeps invalid sequence literal`() = runTest {
		val result = write.resolve(
			container(mockFs(null)),
			args(content = "\\uZZZZ", unescapeUnicode = true, lenientUnescape = true)
		)
		
		assertEquals("\\uZZZZ", decodeRequest(result).content)
	}
	
	// endregion
	
	// region execute
	
	@Test
	fun `execute create writes new file and reports created sha256`() = runTest {
		val newSha = sha("hello")
		val fs = mockk<FileSystemService>()
		coEvery { fs.create(path, "hello") } returns newSha
		
		val result = write.execute(
			container(fs),
			request(WriteRequest(path, path, null, "hello")),
			Channel(Channel.UNLIMITED)
		)
		
		assertTrue(result.success)
		assertEquals("创建了文件 $path，新文件 SHA256：$newSha", result.result.text())
		coVerify(exactly = 1) { fs.create(path, "hello") }
	}
	
	@Test
	fun `execute update writes file and reports diff`() = runTest {
		val old = "line1\nline2"
		val new = "line1\nline2x"
		val oldSha = sha(old)
		val newSha = sha(new)
		val fs = mockk<FileSystemService>()
		coEvery { fs.update(path, oldSha, new) } returns newSha
		
		val result = write.execute(
			container(fs),
			request(WriteRequest(path, path, old to oldSha, new)),
			Channel(Channel.UNLIMITED)
		)
		
		assertTrue(result.success)
		assertEquals(
			"覆盖了文件 $path，当前 SHA256：$newSha，文件变更：\n${unifiedDiff(old, new)}",
			result.result.text()
		)
		coVerify(exactly = 1) { fs.update(path, oldSha, new) }
	}
	
	@Test
	fun `execute update with null old content reports too large`() = runTest {
		val old = "line1\nline2"
		val new = "new"
		val oldSha = sha(old)
		val newSha = sha(new)
		val fs = mockk<FileSystemService>()
		coEvery { fs.update(path, oldSha, new) } returns newSha
		
		val result = write.execute(
			container(fs),
			request(WriteRequest(path, path, null to oldSha, new)),
			Channel(Channel.UNLIMITED)
		)
		
		assertTrue(result.success)
		assertEquals(
			"覆盖了文件 $path，当前 SHA256：$newSha，文件变更：\n" +
					"文件的旧内容过大（可能超过了10MB），完整diff无法展示",
			result.result.text()
		)
	}
	
	@Test
	fun `execute update with identical content reports unchanged`() = runTest {
		val old = "line1\nline2"
		val oldSha = sha(old)
		val fs = mockk<FileSystemService>()
		coEvery { fs.update(path, oldSha, old) } returns oldSha
		
		val result = write.execute(
			container(fs),
			request(WriteRequest(path, path, old to oldSha, old)),
			Channel(Channel.UNLIMITED)
		)
		
		assertTrue(result.success)
		assertEquals("覆盖了文件 $path，当前 SHA256：$oldSha，文件变更：\nUNCHANGED", result.result.text())
	}
	
	// endregion
}
