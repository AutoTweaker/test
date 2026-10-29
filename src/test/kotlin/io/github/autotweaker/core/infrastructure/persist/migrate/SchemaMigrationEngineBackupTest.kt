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

package io.github.autotweaker.core.infrastructure.persist.migrate

import io.github.autotweaker.api.json
import io.github.autotweaker.core.infrastructure.persist.db.base.DB_PATH
import io.github.autotweaker.core.infrastructure.persist.migrate.model.v0.agent.V0AgentContext
import io.github.autotweaker.core.infrastructure.persist.migrate.model.v0.agent.V0AgentContextIndex
import io.github.autotweaker.core.infrastructure.persist.migrate.model.v0.agent.V0ModelConfig
import io.github.autotweaker.core.infrastructure.persist.migrate.model.v0.config.V0SettingValue
import io.github.autotweaker.core.infrastructure.persist.migrate.model.v0.config.V0SettingsTable
import io.github.autotweaker.core.infrastructure.persist.migrate.model.v0.session.V0AgentTable
import io.github.autotweaker.core.infrastructure.persist.migrate.model.v1.config.V1SettingsTable
import io.github.autotweaker.core.infrastructure.persist.migrate.v1.V1MigrationTestEnv
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.*
import kotlin.test.*

class SchemaMigrationEngineBackupTest {
	private val databaseDir: Path = DB_PATH
	private val backupDir: Path = databaseDir.resolve("backup")
	private val backupTmpDir: Path = databaseDir.resolve("backup.tmp")
	
	@BeforeTest
	fun cleanUp() {
		V1MigrationTestEnv.clean()
	}
	
	@Test
	fun `successful migration clears backup directories and records new version`() = runBlocking {
		V1MigrationTestEnv.buildV0()
		seedVersion(0)
		
		SchemaMigrationEngine.run()
		
		assertEquals(CURRENT_SCHEMA_VERSION, storedVersion())
		assertEquals("hello", typedString("test.string"))
		assertFalse(Files.exists(backupDir), "backup directory must be deleted after a successful migration")
		assertFalse(Files.exists(backupTmpDir), "backup.tmp directory must be deleted after a successful migration")
		assertTrue(Files.exists(databaseDir.resolve("AppConfig.mv.db")))
		assertTrue(Files.exists(databaseDir.resolve("Sessions.mv.db")))
	}
	
	@Test
	fun `stale backup replaces the live database before migrating`() = runBlocking {
		V1MigrationTestEnv.buildV0(withSessions = false)
		seedVersion(0)
		snapshotAppConfig()
		insertV0Setting("stale.extra", V0SettingValue.ValString("should be gone"))
		
		SchemaMigrationEngine.run()
		
		assertEquals(CURRENT_SCHEMA_VERSION, storedVersion())
		val keys = typedSettingKeys()
		assertTrue(keys.containsAll(V1MigrationTestEnv.V0_SETTINGS.keys))
		assertFalse(keys.contains("stale.extra"), "migration must run against the restored backup, not the live database")
		assertEquals("hello", typedString("test.string"))
		assertFalse(Files.exists(backupDir), "restored backup files must be consumed")
	}
	
	@Test
	fun `failed migration restores the pre-migration database`() = runBlocking {
		val fixture = V1MigrationTestEnv.buildV0()
		seedVersion(0)
		insertOrphanAgent(fixture.modelId)
		
		val error = assertFailsWith<IllegalStateException> { SchemaMigrationEngine.run() }
		
		assertTrue(error.message!!.contains("Orphan agent"))
		assertEquals(0, storedVersion())
		assertEquals(V1MigrationTestEnv.V0_SETTINGS.keys, v0SettingKeys())
		assertFalse(Files.exists(backupDir), "restored backup files must be consumed")
		assertFalse(Files.exists(backupTmpDir))
	}
	
	private suspend fun seedVersion(version: Int) = V1MigrationTestEnv.inAppConfig {
		SchemaUtils.create(SchemaMetaTable)
		SchemaMetaTable.upsert {
			it[SchemaMetaTable.key] = SCHEMA_VERSION_KEY
			it[SchemaMetaTable.value] = version
		}
	}
	
	private suspend fun storedVersion(): Int? = V1MigrationTestEnv.inAppConfig {
		SchemaMetaTable.selectAll().where { SchemaMetaTable.key eq SCHEMA_VERSION_KEY }
			.singleOrNull()?.get(SchemaMetaTable.value)
	}
	
	private suspend fun typedSettingKeys(): Set<String> = V1MigrationTestEnv.inAppConfig {
		V1SettingsTable.selectAll().mapTo(mutableSetOf()) { it[V1SettingsTable.keyName] }
	}
	
	private suspend fun typedString(key: String): String? = V1MigrationTestEnv.inAppConfig {
		V1SettingsTable.selectAll().where { V1SettingsTable.keyName eq key }
			.singleOrNull()?.get(V1SettingsTable.stringValue)
	}
	
	private suspend fun v0SettingKeys(): Set<String> = V1MigrationTestEnv.inAppConfig {
		val settings = V0SettingsTable("settings")
		settings.selectAll().mapTo(mutableSetOf()) { it[settings.keyName] }
	}
	
	private suspend fun insertV0Setting(key: String, value: V0SettingValue<*>) =
		V1MigrationTestEnv.inAppConfig {
			val settings = V0SettingsTable("settings")
			settings.insert {
				it[settings.keyName] = key
				it[settings.valJson] = json.encodeToString(V0SettingValue.serializer(), value)
			}
		}
	
	private suspend fun insertOrphanAgent(modelId: UUID) = V1MigrationTestEnv.inSessions {
		val agents = V0AgentTable("agent_data")
		agents.insert {
			it[agents.id] = UUID.randomUUID()
			it[name] = "main"
			it[modelJson] = json.encodeToString(
				V0ModelConfig.serializer(),
				V0ModelConfig(modelId, null, modelId, modelId, emptyList()),
			)
			it[contextJson] = json.encodeToString(
				V0AgentContext.serializer(),
				V0AgentContext("system", null, V0AgentContextIndex(null, null, null), null),
			)
			it[activeToolsJson] = "[]"
		}
	}
	
	private suspend fun snapshotAppConfig() {
		Bridge.close(MigratorBase.APP_CONFIG)
		Files.createDirectories(backupDir)
		Files.copy(
			databaseDir.resolve("AppConfig.mv.db"),
			backupDir.resolve("AppConfig.mv.db"),
			StandardCopyOption.REPLACE_EXISTING,
		)
	}
	
	private object Bridge : MigratorBase() {
		suspend fun close(dbName: String) = shutdown(dbName)
	}
}
