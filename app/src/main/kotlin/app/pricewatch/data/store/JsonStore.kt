package app.pricewatch.data.store

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * A small value kept in memory and mirrored to one JSON file in the app's private storage.
 *
 * The file is read once, when the store is created; it is tiny, and having the value immediately
 * lets the first frame show real content. Writes happen in the background and replace the file
 * atomically, so a crash mid-write leaves the previous version intact.
 */
class JsonStore<T>(
  private val file: File,
  private val serializer: KSerializer<T>,
  default: T,
  private val scope: CoroutineScope,
  private val ioDispatcher: CoroutineDispatcher,
  /** Delay before writing, so that a burst of updates results in a single write. */
  private val writeDelayMillis: Long = 0,
) {
  private val _value = MutableStateFlow(read() ?: default)
  val value: StateFlow<T> = _value.asStateFlow()

  private val writeMutex = Mutex()
  private var pendingWrite: Job? = null

  fun update(transform: (T) -> T) {
    _value.update(transform)
    // Updates come from both the UI and background refreshes.
    synchronized(this) {
      pendingWrite?.cancel()
      pendingWrite =
        scope.launch(ioDispatcher) {
          if (writeDelayMillis > 0) delay(writeDelayMillis)
          writeMutex.withLock { write(_value.value) }
        }
    }
  }

  private fun read(): T? =
    try {
      if (file.exists()) FORMAT.decodeFromString(serializer, file.readText()) else null
    } catch (_: IOException) {
      null
    } catch (_: SerializationException) {
      // An unreadable file is treated as absent; the next update overwrites it.
      null
    } catch (_: IllegalArgumentException) {
      null
    }

  private fun write(value: T) {
    try {
      file.parentFile?.mkdirs()
      val temp = File(file.parentFile, file.name + ".tmp")
      temp.writeText(FORMAT.encodeToString(serializer, value))
      Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: IOException) {
      // Storage full or unavailable: the in-memory value stays correct for this session.
    }
  }

  private companion object {
    val FORMAT = Json {
      ignoreUnknownKeys = true
      encodeDefaults = false
      prettyPrint = true
    }
  }
}
