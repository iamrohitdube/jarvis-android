package dev.jarvis.ui.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Which conversation the chat screen shows. Null = a new, empty chat. Set by the History screen. */
@Singleton
class CurrentConversation @Inject constructor() {
    private val _id = MutableStateFlow<Long?>(null)
    val id: StateFlow<Long?> = _id

    fun open(id: Long?) { _id.value = id }
}
