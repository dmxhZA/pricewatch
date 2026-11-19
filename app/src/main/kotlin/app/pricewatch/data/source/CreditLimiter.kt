package app.pricewatch.data.source

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Client-side guard for an API that allows [capacity] credits per [windowMillis].
 *
 * Uses a sliding window, which never exceeds what a provider counting per clock minute would allow.
 * Callers suspend instead of being rejected, so a long watchlist is simply fetched over several
 * windows.
 */
class CreditLimiter(
  val capacity: Int,
  private val windowMillis: Long,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  init {
    require(capacity > 0) { "capacity must be positive" }
  }

  private val mutex = Mutex()

  /** Times at which credits were spent, oldest first. Never holds more than [capacity] entries. */
  private val spentAt = ArrayDeque<Long>()
  private var waiters = 0

  private val _waitingUntil = MutableStateFlow<Long?>(null)

  /** When the longest-waiting caller can proceed (epoch millis), or null if nobody is waiting. */
  val waitingUntil: StateFlow<Long?> = _waitingUntil.asStateFlow()

  /** Spends exactly [credits], suspending until that many are free. */
  suspend fun acquire(credits: Int = 1) {
    require(credits in 1..capacity) { "cannot acquire $credits of $capacity credits at once" }
    acquireBetween(min = credits, max = credits, reserve = 0)
  }

  /**
   * Spends as many credits as are free, up to [max], while leaving [reserve] untouched for more
   * urgent callers. Suspends until at least one credit can be taken, and returns how many were.
   */
  suspend fun acquireUpTo(max: Int, reserve: Int = 0): Int {
    require(max >= 1) { "max must be at least 1" }
    require(reserve in 0 until capacity) { "reserve must leave at least one credit" }
    return acquireBetween(min = 1, max = max, reserve = reserve)
  }

  /** The provider reported the limit was hit anyway: treat the whole window as spent from now. */
  suspend fun exhaust() {
    mutex.withLock {
      val now = clock()
      spentAt.clear()
      repeat(capacity) { spentAt.addLast(now) }
    }
  }

  private suspend fun acquireBetween(min: Int, max: Int, reserve: Int): Int {
    var waiting = false
    try {
      while (true) {
        val waitMillis =
          mutex.withLock {
            val now = clock()
            while (spentAt.isNotEmpty() && now - spentAt.first() >= windowMillis) spentAt.removeFirst()
            val usable = capacity - spentAt.size - reserve
            if (usable >= min) {
              val taken = minOf(usable, max)
              repeat(taken) { spentAt.addLast(now) }
              return taken
            }
            // The (min - usable)-th oldest credit has to expire before this caller can go.
            val blocking = spentAt[min - usable - 1]
            val wait = (blocking + windowMillis - now).coerceAtLeast(1)
            if (!waiting) {
              waiting = true
              waiters++
            }
            _waitingUntil.value = now + wait
            wait
          }
        delay(waitMillis)
      }
    } finally {
      if (waiting) {
        // Must run even when the caller was cancelled mid-wait, or the "waiting" state would stick.
        withContext(NonCancellable) {
          mutex.withLock {
            waiters--
            if (waiters == 0) _waitingUntil.value = null
          }
        }
      }
    }
  }
}
