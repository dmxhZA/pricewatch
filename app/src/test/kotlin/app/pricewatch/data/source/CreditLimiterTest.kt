package app.pricewatch.data.source

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CreditLimiterTest {

  private fun TestScope.limiter(capacity: Int = 8) = CreditLimiter(capacity, 60_000L, clock = { currentTime })

  @Test
  fun `credits within the allowance are granted immediately`() = runTest {
    val limiter = limiter()

    repeat(8) { limiter.acquire() }

    assertEquals(0L, currentTime)
    assertNull(limiter.waitingUntil.value)
  }

  @Test
  fun `the ninth credit waits until the first is a minute old`() = runTest {
    val limiter = limiter()
    limiter.acquire(3)
    advanceTimeBy(10_000)
    limiter.acquire(5)

    limiter.acquire()

    assertEquals(60_000L, currentTime)
  }

  @Test
  fun `a request for several credits waits until all of them are free`() = runTest {
    val limiter = limiter()
    limiter.acquire(6) // at 0 s
    advanceTimeBy(20_000)
    limiter.acquire(2) // at 20 s

    limiter.acquire(7) // needs all six from 0 s and one from 20 s to expire

    assertEquals(80_000L, currentTime)
  }

  @Test
  fun `a bulk caller takes what is free and leaves the reserve`() = runTest {
    val limiter = limiter()

    assertEquals(6, limiter.acquireUpTo(max = 20, reserve = 2))
    // The reserve is still there for an urgent single request.
    limiter.acquire()
    limiter.acquire()
    assertEquals(0L, currentTime)

    // Nothing is left; the bulk caller resumes only when it can take one and still leave two.
    assertEquals(6, limiter.acquireUpTo(max = 20, reserve = 2))
    assertEquals(60_000L, currentTime)
  }

  @Test
  fun `a bulk caller never takes more than it asked for`() = runTest {
    assertEquals(3, limiter().acquireUpTo(max = 3))
  }

  @Test
  fun `waiting is announced with the time it ends and cleared afterwards`() = runTest {
    val limiter = limiter()
    limiter.acquire(8)
    advanceTimeBy(15_000)

    val waiter = launch { limiter.acquire() }
    runCurrent()
    assertEquals(60_000L, limiter.waitingUntil.value)

    waiter.join()
    assertEquals(60_000L, currentTime)
    assertNull(limiter.waitingUntil.value)
  }

  @Test
  fun `a cancelled waiter does not leave the waiting state behind`() = runTest {
    val limiter = limiter()
    limiter.acquire(8)

    val waiter = launch { limiter.acquire() }
    runCurrent()
    assertTrue(limiter.waitingUntil.value != null)
    waiter.cancelAndJoin()

    assertNull(limiter.waitingUntil.value)
  }

  @Test
  fun `after the provider reports the limit nothing goes out for a full window`() = runTest {
    val limiter = limiter()
    limiter.acquire(2)
    advanceTimeBy(30_000)

    limiter.exhaust()
    limiter.acquire()

    assertEquals(90_000L, currentTime)
  }
}
