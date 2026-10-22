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

package io.github.autotweaker.core.infrastructure.persist.db.secret

import io.github.autotweaker.api.types.exception.PasswordInvalidException
import io.github.autotweaker.api.types.exception.SecretStoreLockedException
import io.github.autotweaker.api.types.exception.notfound.SecretNotFoundException
import io.github.autotweaker.core.infrastructure.persist.db.base.DatabaseStore
import io.github.autotweaker.core.test.TestServices
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretManagerTest {
	private fun newDb(): Database =
		Database.connect("jdbc:h2:mem:secret_manager_${counter.getAndIncrement()};DB_CLOSE_DELAY=-1", "org.h2.Driver")
	
	private fun manager(db: Database): SecretManager {
		val store = mockk<DatabaseStore>()
		every { store.connect(any()) } returns db
		return SecretManager(store)
	}
	
	@Test
	fun `init on an empty database auto unlocks with an empty password`() = runTest {
		val manager = manager(newDb())
		
		assertFalse(manager.isUnlocked.value)
		manager.init()
		
		assertTrue(manager.isUnlocked.value)
		assertTrue(manager.isPasswordEmpty)
	}
	
	@Test
	fun `every operation throws SecretStoreLockedException before unlock`() = runTest {
		val manager = manager(newDb())
		val id = UUID.randomUUID()
		
		assertFailsWith<SecretStoreLockedException> { manager.requireUnlocked() }
		assertFailsWith<SecretStoreLockedException> { manager.isPasswordEmpty }
		assertFailsWith<SecretStoreLockedException> { manager.set("secret", id) }
		assertFailsWith<SecretStoreLockedException> { manager.get(id) }
		assertFailsWith<SecretStoreLockedException> { manager.list() }
		assertFailsWith<SecretStoreLockedException> { manager.remove(id) }
	}
	
	@Test
	fun `set then get returns the original secret`() = runTest {
		val manager = manager(newDb()).also { it.init() }
		val id = UUID.randomUUID()
		
		manager.set("hunter2", id)
		
		assertEquals("hunter2", manager.get(id))
	}
	
	@Test
	fun `set with an existing id overwrites the stored secret`() = runTest {
		val manager = manager(newDb()).also { it.init() }
		val id = UUID.randomUUID()
		
		manager.set("first", id)
		manager.set("second", id)
		
		assertEquals("second", manager.get(id))
		assertEquals(listOf(id), manager.list())
	}
	
	@Test
	fun `get of an unknown id throws SecretNotFoundException carrying that id`() = runTest {
		val manager = manager(newDb()).also { it.init() }
		val id = UUID.randomUUID()
		
		val error = assertFailsWith<SecretNotFoundException> { manager.get(id) }
		
		assertEquals(id, error.id)
	}
	
	@Test
	fun `secret copied to another id fails authentication and reports not found`() = runTest {
		val db = newDb()
		val manager = manager(db).also { it.init() }
		val original = UUID.randomUUID()
		val forged = UUID.randomUUID()
		manager.set("payload", original)
		
		transaction(db) {
			val row = SecretTable.selectAll().where { SecretTable.id eq original }.single()
			SecretTable.insert {
				it[SecretTable.id] = forged
				it[SecretTable.nonce] = row[SecretTable.nonce]
				it[SecretTable.content] = row[SecretTable.content]
			}
		}
		
		assertEquals("payload", manager.get(original))
		assertFailsWith<SecretNotFoundException> { manager.get(forged) }
	}
	
	@Test
	fun `list returns stored ids and remove reports whether the row existed`() = runTest {
		val manager = manager(newDb()).also { it.init() }
		val a = UUID.randomUUID()
		val b = UUID.randomUUID()
		manager.set("a", a)
		manager.set("b", b)
		
		assertEquals(setOf(a, b), manager.list().toSet())
		assertTrue(manager.remove(a))
		assertFalse(manager.remove(a))
		assertEquals(listOf(b), manager.list())
	}
	
	@Test
	fun `unlock with a wrong password throws and leaves the store locked`() = runTest {
		val db = newDb()
		manager(db).unlock("correct")
		val manager = manager(db)
		
		assertFailsWith<PasswordInvalidException> { manager.unlock("wrong") }
		
		assertFalse(manager.isUnlocked.value)
		assertFailsWith<SecretStoreLockedException> { manager.requireUnlocked() }
	}
	
	@Test
	fun `unlock with the right password decrypts secrets written by a previous instance`() = runTest {
		val db = newDb()
		val id = UUID.randomUUID()
		manager(db).apply {
			unlock("correct")
			set("persisted", id)
		}
		val manager = manager(db)
		
		manager.unlock("correct")
		
		assertFalse(manager.isPasswordEmpty)
		assertEquals("persisted", manager.get(id))
	}
	
	@Test
	fun `init skips auto unlock when the stored key is password protected`() = runTest {
		val db = newDb()
		manager(db).unlock("correct")
		val manager = manager(db)
		
		manager.init()
		
		assertFalse(manager.isUnlocked.value)
	}
	
	@Test
	fun `unlock is a no-op once the store is already unlocked`() = runTest {
		val manager = manager(newDb())
		manager.unlock("correct")
		
		manager.unlock("wrong")
		
		assertTrue(manager.isUnlocked.value)
		assertFalse(manager.isPasswordEmpty)
	}
	
	@Test
	fun `changePassword with a wrong old password keeps the old password valid`() = runTest {
		val db = newDb()
		val id = UUID.randomUUID()
		val manager = manager(db)
		manager.unlock("old")
		manager.set("secret", id)
		
		assertFailsWith<PasswordInvalidException> { manager.changePassword("wrong", "new") }
		
		assertEquals("secret", manager.get(id))
		assertFalse(manager.isPasswordEmpty)
		val reopened = manager(db)
		reopened.unlock("old")
		assertEquals("secret", reopened.get(id))
	}
	
	@Test
	fun `changePassword re-wraps the master key so only the new password unlocks`() = runTest {
		val db = newDb()
		val id = UUID.randomUUID()
		val manager = manager(db)
		manager.unlock("old")
		manager.set("secret", id)
		
		manager.changePassword("old", "new")
		
		val reopened = manager(db)
		assertFailsWith<PasswordInvalidException> { reopened.unlock("old") }
		val unlocked = manager(db)
		unlocked.unlock("new")
		assertEquals("secret", unlocked.get(id))
	}
	
	@Test
	fun `changePassword to an empty password makes later init auto unlock succeed`() = runTest {
		val db = newDb()
		val id = UUID.randomUUID()
		val manager = manager(db)
		manager.unlock("old")
		manager.set("secret", id)
		
		manager.changePassword("old", "")
		
		assertTrue(manager.isPasswordEmpty)
		val reopened = manager(db)
		reopened.init()
		assertTrue(reopened.isUnlocked.value)
		assertEquals("secret", reopened.get(id))
	}
	
	companion object {
		private val counter = AtomicInteger(0)
		
		init {
			TestServices.init()
		}
	}
}
