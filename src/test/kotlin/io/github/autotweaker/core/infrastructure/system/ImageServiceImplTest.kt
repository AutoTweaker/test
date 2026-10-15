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

package io.github.autotweaker.core.infrastructure.system

import io.github.autotweaker.core.test.TestServices
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import javax.imageio.ImageIO
import kotlin.io.path.writeText
import kotlin.test.*

class ImageServiceImplTest {
	companion object {
		init {
			TestServices.init()
		}
	}
	
	private val limit = 1024L * 1024
	private lateinit var dir: Path
	
	@BeforeTest
	fun setUp() {
		dir = Files.createTempDirectory("image-service-test").toRealPath()
	}
	
	@AfterTest
	fun tearDown() {
		dir.toFile().deleteRecursively()
	}
	
	private fun writeImage(name: String, format: String, type: Int, size: Int, seed: Long): Path {
		val image = BufferedImage(size, size, type)
		val random = Random(seed)
		image.setRGB(0, 0, size, size, IntArray(size * size) { random.nextInt() }, 0, size)
		return dir.resolve(name).also { ImageIO.write(image, format, it.toFile()) }
	}
	
	private fun smallPng(name: String) = writeImage(name, "png", BufferedImage.TYPE_INT_ARGB, 4, 1L)
	
	private fun smallJpeg(name: String) = writeImage(name, "jpg", BufferedImage.TYPE_INT_RGB, 4, 2L)
	
	private fun decode(bytes: ByteArray): BufferedImage? =
		ImageIO.read(ByteArrayInputStream(bytes))
	
	@Test
	fun `detect reports the mime type of a png file`() = runTest {
		assertEquals("image/png", ImageServiceImpl.detect(smallPng("a.png")))
	}
	
	@Test
	fun `detect reports the mime type of a jpeg file`() = runTest {
		assertEquals("image/jpeg", ImageServiceImpl.detect(smallJpeg("a.jpg")))
	}
	
	@Test
	fun `detect on a non image file throws IllegalStateException`() = runTest {
		val path = dir.resolve("note.txt")
		path.writeText("not an image")
		
		assertFailsWith<IllegalStateException> { ImageServiceImpl.detect(path) }
	}
	
	@Test
	fun `read returns the original bytes when the image is within the size limit`() = runTest {
		val path = smallPng("small.png")
		
		val content = ImageServiceImpl.read(path)
		
		assertEquals("image/png", content.mimeType)
		assertContentEquals(Files.readAllBytes(path), content.bytes)
	}
	
	@Test
	fun `read re-encodes an oversized opaque image into a jpeg within the limit`() = runTest {
		val path = writeImage("big.png", "png", BufferedImage.TYPE_INT_RGB, 1200, 3L)
		assertTrue(Files.size(path) > limit, "test fixture should exceed the read limit")
		
		val content = ImageServiceImpl.read(path)
		
		assertEquals("image/jpeg", content.mimeType)
		assertTrue(content.bytes.size in 1..limit, "re-encoded size was ${content.bytes.size}")
		val decoded = assertNotNull(decode(content.bytes))
		assertTrue(maxOf(decoded.width, decoded.height) >= 64)
	}
	
	@Test
	fun `read re-encodes an oversized transparent image into a png within the limit`() = runTest {
		val path = writeImage("big-alpha.png", "png", BufferedImage.TYPE_INT_ARGB, 1200, 4L)
		assertTrue(Files.size(path) > limit, "test fixture should exceed the read limit")
		
		val content = ImageServiceImpl.read(path)
		
		assertEquals("image/png", content.mimeType)
		assertTrue(content.bytes.size in 1..limit, "re-encoded size was ${content.bytes.size}")
		val decoded = assertNotNull(decode(content.bytes))
		assertTrue(maxOf(decoded.width, decoded.height) >= 64)
	}
}
