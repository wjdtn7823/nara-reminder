package com.narareminder.support

import kotlinx.coroutines.delay
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

// delays 크기만큼 재시도한다 (최대 시도 횟수 = delays.size + 1)
suspend fun <T> retrying(
    delays: List<Duration>,
    retryOn: (Throwable) -> Boolean = { true },
    block: suspend () -> T,
): T {
    for (backoff in delays) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!retryOn(e)) throw e
            delay(backoff.toMillis())
        }
    }
    return block()
}
