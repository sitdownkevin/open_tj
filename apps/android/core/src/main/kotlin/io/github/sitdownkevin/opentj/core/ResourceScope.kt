package io.github.sitdownkevin.opentj.core

import java.io.Closeable

class ResourceScope : Closeable {
    private val resources = LinkedHashSet<Closeable>()
    @Volatile var isClosed = false
        private set

    @Synchronized
    fun <T : Closeable> own(resource: T): T {
        if (isClosed) {
            runCatching { resource.close() }
            throw SessionCancelledException()
        }
        resources.add(resource)
        return resource
    }

    @Synchronized
    fun forget(resource: Closeable) {
        resources.remove(resource)
    }

    fun checkActive() {
        if (isClosed || Thread.currentThread().isInterrupted) throw SessionCancelledException()
    }

    override fun close() {
        val pending = synchronized(this) {
            if (isClosed) return
            isClosed = true
            resources.toList().also { resources.clear() }
        }
        // Closing sockets interrupts blocking TLS reads and writes on cancellation.
        pending.forEach { runCatching { it.close() } }
    }
}
