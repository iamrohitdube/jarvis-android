package dev.jarvis.ui.common

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import dev.jarvis.ui.chat.UiMessage

/**
 * Copies [text] to the clipboard. [sensitive] text (private answers) is flagged so Android hides it in the
 * clipboard preview (Android 13+). Android 13+ confirms the copy itself; older versions get a short toast.
 */
fun copyToClipboard(context: Context, label: String, text: String, sensitive: Boolean) {
    val clip = ClipData.newPlainText(label, text)
    if (sensitive) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

/** The whole conversation as plain text ("You: …" / "JARVIS: …"). Locked (hidden) replies are left out. */
fun chatTranscript(messages: List<UiMessage>): String =
    messages.filter { it.text.isNotBlank() && !it.hidden && !it.streaming }
        .joinToString("\n\n") { (if (it.fromUser) "You: " else "JARVIS: ") + it.text.trim() }
