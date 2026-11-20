package dev.jarvis.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Carries the outcome of a browser sign-in back to whichever screen started it. */
@Singleton
class SignInEvents @Inject constructor() {
    private val _last = MutableStateFlow<String?>(null)
    val last: StateFlow<String?> = _last

    fun post(message: String) { _last.value = message }
    fun consume() { _last.value = null }
}
