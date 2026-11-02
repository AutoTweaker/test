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

package io.github.autotweaker.api.types

import io.github.autotweaker.api.types.SemVer.Companion.toSemVer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SemVerTest {
	
	// region construction & validation
	
	@Test
	fun `construct zero version`() {
		val version = SemVer(0, 0, 0)
		assertEquals("0.0.0", version.toString())
	}
	
	@Test
	fun `reject negative version components`() {
		assertFailsWith<IllegalArgumentException> { SemVer(-1, 0, 0) }
		assertFailsWith<IllegalArgumentException> { SemVer(0, -1, 0) }
		assertFailsWith<IllegalArgumentException> { SemVer(0, 0, -1) }
	}
	
	@Test
	fun `reject empty pre-release identifier`() {
		assertFailsWith<IllegalArgumentException> {
			SemVer(1, 0, 0, preRelease = listOf("alpha", "", "beta"))
		}
	}
	
	@Test
	fun `reject pre-release identifier with illegal characters`() {
		listOf("1.0", "a_b", "a b", "alpha!", "中文").forEach { identifier ->
			assertFailsWith<IllegalArgumentException>("identifier=$identifier") {
				SemVer(1, 0, 0, preRelease = listOf(identifier))
			}
		}
	}
	
	@Test
	fun `reject numeric pre-release identifier with leading zero`() {
		listOf("01", "00", "0123").forEach { identifier ->
			assertFailsWith<IllegalArgumentException>("identifier=$identifier") {
				SemVer(1, 0, 0, preRelease = listOf(identifier))
			}
		}
	}
	
	@Test
	fun `accept single zero pre-release identifier`() {
		assertEquals(listOf("0"), SemVer(1, 0, 0, preRelease = listOf("0")).preRelease)
	}
	
	@Test
	fun `reject empty build metadata identifier`() {
		assertFailsWith<IllegalArgumentException> {
			SemVer(1, 0, 0, buildMetadata = listOf("build", ""))
		}
	}
	
	@Test
	fun `reject build metadata identifier with illegal characters`() {
		listOf("build.1", "a_b", "build+1").forEach { identifier ->
			assertFailsWith<IllegalArgumentException>("identifier=$identifier") {
				SemVer(1, 0, 0, buildMetadata = listOf(identifier))
			}
		}
	}
	
	@Test
	fun `accept numeric build metadata identifier with leading zero`() {
		assertEquals(listOf("007"), SemVer(1, 0, 0, buildMetadata = listOf("007")).buildMetadata)
	}
	
	// endregion
	
	// region equality
	
	@Test
	fun `build metadata is ignored by equality`() {
		val withBuild = SemVer(1, 2, 3, buildMetadata = listOf("exp", "sha", "5114f85"))
		val withoutBuild = SemVer(1, 2, 3)
		
		assertEquals(withoutBuild, withBuild)
		assertEquals(withoutBuild.hashCode(), withBuild.hashCode())
	}
	
	@Test
	fun `different pre-release is not equal`() {
		val alpha = SemVer(1, 2, 3, preRelease = listOf("alpha"))
		val beta = SemVer(1, 2, 3, preRelease = listOf("beta"))
		
		assertNotEquals(alpha, beta)
		assertNotEquals<Any>(alpha, "1.2.3-alpha")
	}
	
	// endregion
	
	// region comparison
	
	private fun version(text: String) = SemVer.parse(text)
	
	@Test
	fun `compare major minor and patch`() {
		assertTrue(version("2.0.0") > version("1.999.999"))
		assertTrue(version("1.2.0") > version("1.1.999"))
		assertTrue(version("1.0.2") > version("1.0.1"))
		assertFalse(version("1.0.1") > version("1.0.1"))
	}
	
	@Test
	fun `release version is greater than its pre-release`() {
		assertTrue(version("1.0.0") > version("1.0.0-rc.1"))
		assertTrue(version("1.0.0-rc.1") < version("1.0.0"))
	}
	
	@Test
	fun `numeric pre-release identifiers compare numerically`() {
		assertTrue(version("1.0.0-2") < version("1.0.0-10"))
		assertTrue(version("1.0.0-alpha.9") < version("1.0.0-alpha.11"))
	}
	
	@Test
	fun `numeric pre-release identifier is lower than alphanumeric`() {
		assertTrue(version("1.0.0-1") < version("1.0.0-alpha"))
		assertTrue(version("1.0.0-alpha.42") < version("1.0.0-alpha.42a"))
	}
	
	@Test
	fun `alphanumeric pre-release identifiers compare lexically`() {
		assertTrue(version("1.0.0-alpha") < version("1.0.0-beta"))
		assertTrue(version("1.0.0-Alpha") < version("1.0.0-alpha"))
	}
	
	@Test
	fun `longer pre-release list wins when prefix is equal`() {
		assertTrue(version("1.0.0-alpha") < version("1.0.0-alpha.1"))
		assertTrue(version("1.0.0-alpha.1") < version("1.0.0-alpha.1.1"))
	}
	
	@Test
	fun `build metadata is ignored by comparison`() {
		assertEquals(0, version("1.0.0+001").compareTo(version("1.0.0+20130313144700")))
		assertEquals(0, version("1.0.0-alpha+exp.sha.5114f85").compareTo(version("1.0.0-alpha")))
	}
	
	@Test
	fun `follows spec precedence chain`() {
		val chain = listOf(
			"1.0.0-alpha",
			"1.0.0-alpha.1",
			"1.0.0-alpha.beta",
			"1.0.0-beta",
			"1.0.0-beta.2",
			"1.0.0-beta.11",
			"1.0.0-rc.1",
			"1.0.0",
		).map(::version)
		
		chain.zipWithNext { lower, higher ->
			assertTrue(lower < higher, "$lower should be lower than $higher")
			assertTrue(higher > lower, "$higher should be greater than $lower")
		}
	}
	
	@Test
	fun `numeric pre-release identifiers beyond int range compare numerically`() {
		val tenBillion = version("1.0.0-10000000000")
		val tenBillionMinusOne = version("1.0.0-9999999999")
		
		assertTrue(tenBillion > tenBillionMinusOne, "$tenBillion should be greater than $tenBillionMinusOne")
	}
	
	// endregion
	
	// region parsing
	
	@Test
	fun `parse plain version`() {
		val version = SemVer.parse("1.22.333")
		
		assertEquals(1, version.major)
		assertEquals(22, version.minor)
		assertEquals(333, version.patch)
		assertEquals(emptyList(), version.preRelease)
		assertEquals(emptyList(), version.buildMetadata)
	}
	
	@Test
	fun `parse version with pre-release and build metadata`() {
		val version = SemVer.parse("1.0.0-alpha.1+build.42")
		
		assertEquals(listOf("alpha", "1"), version.preRelease)
		assertEquals(listOf("build", "42"), version.buildMetadata)
	}
	
	@Test
	fun `toSemVer parses same as parse`() {
		assertEquals(SemVer.parse("3.4.5-rc.2+sha.abc123"), "3.4.5-rc.2+sha.abc123".toSemVer())
	}
	
	@Test
	fun `reject malformed version strings`() {
		listOf(
			"",
			"1",
			"1.2",
			"1.2.3.4",
			"v1.2.3",
			"01.2.3",
			"1.02.3",
			"1.2.03",
			"1.2.3-",
			"1.2.3-01",
			" 1.2.3",
			"1.2.3 ",
			"1.2.3-alpha..1",
		).forEach { text ->
			assertFailsWith<IllegalArgumentException>("text=\"$text\"") { SemVer.parse(text) }
		}
	}
	
	@Test
	fun `parse and toString round trip`() {
		listOf("0.0.0", "1.2.3", "10.20.30-alpha.1", "1.0.0-rc.1+build.7").forEach { text ->
			assertEquals(text, SemVer.parse(text).toString())
		}
	}
	
	// endregion
}
