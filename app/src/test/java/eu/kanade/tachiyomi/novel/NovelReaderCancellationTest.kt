package eu.kanade.tachiyomi.novel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NovelReaderCancellationTest {

    @Test
    fun cancellationIsRethrownInsteadOfBecomingARegularFailure() {
        val expected = CancellationException("chapter request superseded")

        val actual = assertThrows(CancellationException::class.java) {
            runBlocking {
                runCatchingCancellable<String> { throw expected }
            }
        }

        assertSame(expected, actual)
    }

    @Test
    fun ordinaryReadFailureRemainsAvailableToTheReaderErrorUi() {
        val result = runBlocking {
            runCatchingCancellable<String> { error("chapter unavailable") }
        }

        assertEquals("chapter unavailable", result.exceptionOrNull()?.message)
    }
}
