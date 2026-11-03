package eu.kanade.tachiyomi.util.system

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetworkStateTest {

    @Test
    fun internetMustBeValidatedBeforeNetworkIsConsideredOnline() {
        assertTrue(NetworkState(isConnected = true, isValidated = true, isWifi = true).isOnline)
        assertFalse(NetworkState(isConnected = true, isValidated = false, isWifi = true).isOnline)
        assertFalse(NetworkState(isConnected = false, isValidated = true, isWifi = false).isOnline)
    }
}
