package dev.jarvis.ui.people

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jarvis.agent.PeopleAnswers
import dev.jarvis.agent.PeopleConnector
import dev.jarvis.agent.PersonBrief
import dev.jarvis.agent.RecentPerson
import dev.jarvis.data.AuditCategory
import dev.jarvis.data.AuditEvent
import dev.jarvis.data.AuditLog
import dev.jarvis.data.SettingsRepository
import dev.jarvis.ui.components.Hint
import dev.jarvis.ui.icons.AppIcons
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.ZonedDateTime
import javax.inject.Inject

@HiltViewModel
class PeopleViewModel @Inject constructor(
    private val people: PeopleConnector,
    private val settings: SettingsRepository,
    private val caller: dev.jarvis.messaging.Caller,
    private val audit: AuditLog,
    private val json: Json,
) : ViewModel() {
    val recent = MutableStateFlow<List<RecentPerson>>(emptyList())
    val brief = MutableStateFlow<Pair<String, String>?>(null)
    val loading = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val privateMode = MutableStateFlow(false)

    fun load() = viewModelScope.launch {
        privateMode.value = settings.current().privateMode
        if (!privateMode.value) recent.value = people.recent()
    }

    fun open(name: String) = viewModelScope.launch {
        if (settings.current().privateMode) { privateMode.value = true; return@launch }
        loading.value = true
        message.value = null
        try {
            val b = people.brief(name.trim())
            audit.record(AuditEvent(AuditCategory.TOOL, "person_brief", "ok", tool = PeopleConnector.BRIEF, detail = "screen"))
            brief.value = name to PeopleAnswers.render(json.encodeToString(PersonBrief.serializer(), b), ZonedDateTime.now())
        } finally {
            loading.value = false
        }
    }

    fun close() { brief.value = null; message.value = null }

    /** Same confirmation as "call X" by voice: the Call dialog shows who and which number. */
    fun call(name: String) = viewModelScope.launch { message.value = caller.handle(name) }
}

/** People (Phase 15.1): who you were in touch with lately, and a brief per person. Read and shown on the phone only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(onBack: () -> Unit, vm: PeopleViewModel = hiltViewModel()) {
    val recent by vm.recent.collectAsStateWithLifecycle()
    val brief by vm.brief.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val privateMode by vm.privateMode.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(brief?.first ?: "People") },
                navigationIcon = {
                    IconButton(onClick = { if (brief != null) vm.close() else onBack() }) { Icon(AppIcons.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (privateMode) {
                Hint("Private mode is on, so JARVIS isn't reading your calls, messages or email. Turn it off in Settings.")
                return@Column
            }
            val b = brief
            if (b != null) {
                Card(Modifier.fillMaxWidth()) {
                    Text(b.second, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.call(b.first) }) { Text("Call") }
                    OutlinedButton(onClick = vm::close) { Text("Done") }
                }
                message?.let { Hint(it) }
                Hint("Tip: say \"prep me for my call with ${b.first}\" for this brief by voice.")
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = { vm.open(query) }, enabled = query.isNotBlank() && !loading) { Text("Brief") }
            }
            Hint("Calls, texts, chats, email and the next meeting with someone, gathered on the phone. Nothing is sent to Claude.")
            if (recent.isEmpty() && !loading) Hint("No one yet: allow call log, SMS or notification access in Settings → Phone data.")
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(recent, key = { it.name }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.open(p.name) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.Person, null)
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge)
                            Hint(p.via.joinToString(" · "))
                        }
                        Icon(AppIcons.KeyboardArrowRight, null)
                    }
                }
            }
        }
    }
}
