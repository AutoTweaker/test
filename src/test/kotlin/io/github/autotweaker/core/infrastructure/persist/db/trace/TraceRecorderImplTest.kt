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

package io.github.autotweaker.core.infrastructure.persist.db.trace

import io.github.autotweaker.api.types.KebabCase.Companion.toKebab
import io.github.autotweaker.api.types.UpperSnakeCase
import io.github.autotweaker.api.types.UpperSnakeCase.Companion.toUpperSnake
import io.github.autotweaker.core.test.TestServices
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class TraceRecorderImplTest {
	companion object {
		init {
			TestServices.init()
		}
	}
	
	private class Sample
	
	private fun recorderWith(store: TraceStore) =
		TraceRecorderImpl(store, mockk(relaxed = true))
	
	@Test
	fun `recorder returns the same instance for the same class`() {
		val recorder = recorderWith(mockk(relaxed = true))
		try {
			assertSame(recorder.recorder(Sample::class), recorder.recorder(Sample::class))
		} finally {
			recorder.shutdown()
		}
	}
	
	@Test
	fun `recorder returns a different instance for another class`() {
		val recorder = recorderWith(mockk(relaxed = true))
		try {
			assertNotSame(recorder.recorder(Sample::class), recorder.recorder(String::class))
		} finally {
			recorder.shutdown()
		}
	}
	
	@Test
	fun `add stores the string form of the content`() = runBlocking {
		val store = mockk<TraceStore>(relaxed = true)
		val recorder = recorderWith(store)
		recorder.init()
		try {
			recorder.recorder(Sample::class).add("some-ns".toKebab(), 42)
			
			coVerify(timeout = 5_000) {
				store.insert(Sample::class.java.name, "some-ns", "42")
			}
		} finally {
			recorder.shutdown()
		}
	}
	
	@Test
	fun `add flattens a map into key=value lines`() = runBlocking {
		val store = mockk<TraceStore>(relaxed = true)
		val recorder = recorderWith(store)
		recorder.init()
		try {
			val content: Map<UpperSnakeCase, Any> = linkedMapOf(
				"FIRST".toUpperSnake() to 1,
				"SECOND".toUpperSnake() to "value",
			)
			
			recorder.recorder(Sample::class).add("some-ns".toKebab(), content)
			
			coVerify(timeout = 5_000) {
				store.insert(Sample::class.java.name, "some-ns", "FIRST=1\nSECOND=value")
			}
		} finally {
			recorder.shutdown()
		}
	}
}
