package com.carmusic.app.ui

/** Accessed on the UI dispatcher: superseded responses cannot update the page. */
internal class LatestRequest {
    private var generation = 0L
    fun begin(): Long = ++generation
    fun isCurrent(request: Long): Boolean = request == generation
}
