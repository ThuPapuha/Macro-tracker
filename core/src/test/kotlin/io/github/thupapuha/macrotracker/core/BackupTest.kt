package io.github.thupapuha.macrotracker.core

import io.github.thupapuha.macrotracker.core.backup.BackupEntry
import io.github.thupapuha.macrotracker.core.backup.BackupFile
import io.github.thupapuha.macrotracker.core.backup.Backups
import io.github.thupapuha.macrotracker.core.model.MacroTargets
import io.github.thupapuha.macrotracker.core.supabase.AuthSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupTest {

    private val avena = BackupEntry("2026-10-21", "BREAKFAST", "Avena", "60 g", 230.0, 8.0, 40.0, 4.0, "ai", 1_000)
    private val pollo = BackupEntry("2026-10-21", "LUNCH", "Pollo", "150 g", 250.0, 45.0, 0.0, 6.0, "manual", 2_000)

    @Test
    fun roundTripsEverything() {
        val file = BackupFile(
            exportedAt = "2026-10-21T18:00:00Z",
            entries = listOf(avena, pollo),
            targets = MacroTargets(2000.0, 150.0, 200.0, 60.0, "Plan"),
            displayName = "Juan",
            lastGroupId = "g1",
            hadGroups = true,
            session = AuthSession("access", "refresh", 5_000, "user-1"),
        )
        assertEquals(file, Backups.decode(Backups.encode(file)))
    }

    @Test
    fun onlyImportsMissingEntriesOnce() {
        val incoming = listOf(avena, pollo, pollo.copy(createdAt = 3_000), avena.copy(name = " avena "))
        val missing = Backups.missingEntries(existing = listOf(avena), incoming = incoming)
        assertEquals(listOf(pollo, pollo.copy(createdAt = 3_000)), missing)
    }

    @Test
    fun rejectsOtherFiles() {
        assertThrows(IllegalArgumentException::class.java) { Backups.decode("""{"hello":"world"}""") }
        assertThrows(IllegalArgumentException::class.java) {
            Backups.decode("""{"app":"otra-app","exportedAt":"x"}""")
        }
        assertThrows(IllegalArgumentException::class.java) { Backups.decode("no es json") }
    }
}
