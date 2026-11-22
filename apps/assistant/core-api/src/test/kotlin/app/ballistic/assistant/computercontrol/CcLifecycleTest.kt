package app.ballistic.assistant.computercontrol

import kotlin.test.*

class CcLifecycleTest {
    @Test fun revokedSessionNeverChangesAnInflightRunsDisplay() {
        val route = CcRunRoute()
        assertTrue(route.ownsScreen("first", true))
        assertTrue(route.ownsScreen("first", false))
        assertFalse(route.ownsScreen(null, false))
        assertFalse(route.ownsScreen("second", false))
        assertFalse(route.ownsScreen("second", true))
        assertTrue(route.ownsScreen("third", true))
    }

    @Test fun cancelledCallbacksCannotOverwriteANewerRequest() {
        val requests = CcGeneration()
        val first = requests.next()
        assertTrue(requests.isCurrent(first))
        requests.invalidate()
        assertFalse(requests.isCurrent(first))
        val second = requests.next()
        assertFalse(requests.isCurrent(first))
        assertTrue(requests.isCurrent(second))
        requests.invalidate()
        assertFalse(requests.isCurrent(second))
    }

    @Test fun versionAndAvailabilityFailClosed() {
        val ready = CcPlatform(true, true, true, true, 5, true)
        assertNull(ready.missing)
        assertEquals(CcReason.NeedsVersion5, ready.copy(version = 4).missing)
        assertEquals(CcReason.NotAllowed, ready.copy(sessionCreationAvailable = null).missing)
        assertEquals(CcReason.NotAllowed, ready.copy(sessionCreationAvailable = false).missing)
        assertEquals(CcReason.NotAssistant, ready.copy(assistantRole = false, permission = false).missing)
        assertEquals(CcReason.NoLibrary, ready.copy(library = false).missing)
    }
}
