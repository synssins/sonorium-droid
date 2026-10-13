package app.sonorium.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** A server entry follows the server's own name on each connection; a name given in the app wins. */
class ServerRecordTest {
    private val record = ServerRecord(
        id = "s1", label = "Sonorium", fingerprint = "", hosts = listOf("192.0.2.10"), httpPort = 8008, httpsPort = 8808, deviceId = "d",
    )

    @Test
    fun followsTheServersName() {
        val renamed = record.reachedAs("192.0.2.10", "Living room", "1.7.4")
        assertEquals("Living room", renamed.displayName)
        assertEquals("1.7.4", renamed.version)
        // Renamed again on the server: follows again
        assertEquals("Basement", renamed.reachedAs("192.0.2.10", "Basement", "1.7.4").displayName)
    }

    @Test
    fun aNameGivenInTheAppWins() {
        val mine = record.copy(customName = "Kids")
        assertEquals("Kids", mine.reachedAs("192.0.2.10", "Living room", "1.7.4").displayName)
    }

    @Test
    fun theDefaultNameShowsTheAddress() {
        // Every server reports "Sonorium" until it has its own name: the entry shows its address
        assertEquals("192.0.2.11", record.reachedAs("192.0.2.11", "Sonorium", "1.7.3").displayName)
        // No name at all: the last one is kept
        assertEquals("Living room", record.copy(label = "Living room").reachedAs("192.0.2.10", "", "1.7.3").displayName)
    }
}
