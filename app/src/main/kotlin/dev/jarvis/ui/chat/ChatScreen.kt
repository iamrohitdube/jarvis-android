package dev.jarvis.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import dev.jarvis.ui.components.IconTile
import dev.jarvis.voice.SttError
import dev.jarvis.voice.VoiceState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jarvis.claude.BudgetLevel
import dev.jarvis.ui.common.FixAction
import dev.jarvis.ui.common.chatTranscript
import dev.jarvis.ui.common.copyToClipboard
import androidx.compose.foundation.combinedClickable
import dev.jarvis.ui.components.GlassIconButton
import dev.jarvis.ui.components.JarvisBackground
import dev.jarvis.ui.components.glass
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import dev.jarvis.ui.components.ChipTone
import dev.jarvis.ui.components.JarvisOrb
import dev.jarvis.ui.components.OrbAvatar
import dev.jarvis.ui.components.OrbState
import dev.jarvis.ui.components.StatusChip
import dev.jarvis.ui.components.TypingDots
import dev.jarvis.ui.theme.Brand
import dev.jarvis.ui.theme.withBrandGradient
import kotlinx.coroutines.launch
import java.time.LocalTime
import dev.jarvis.ui.icons.AppIcons

private val MicPermission = Manifest.permission.RECORD_AUDIO

private val Suggestions = listOf(
    "What's my net worth?",
    "How are my SIPs doing?",
    "Best large-cap funds?",
    "Explain SIP vs lump sum",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAudit: () -> Unit,
    onOpenSecurity: () -> Unit,
    vm: ChatViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val budget by vm.budget.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val currentId by vm.currentId.collectAsStateWithLifecycle()
    val model by vm.modelLabel.collectAsStateWithLifecycle()
    val voiceState by vm.voiceState.collectAsStateWithLifecycle()
    val partial by vm.voicePartial.collectAsStateWithLifecycle()
    val level by vm.voiceLevel.collectAsStateWithLifecycle()
    val voiceError by vm.voiceError.collectAsStateWithLifecycle()
    val quiet by vm.quiet.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var micRationale by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.micTapped()
    }
    val onMic = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.micTapped() else micRationale = true
    }
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    fun closeDrawerThen(action: () -> Unit) { scope.launch { drawer.close() }; action() }
    val send = { if (input.isNotBlank()) { vm.send(input); input = "" } }

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    if (micRationale) {
        MicRationaleDialog(onAllow = { micPermission.launch(MicPermission) }, onDismiss = { micRationale = false })
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(
                drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
                drawerContainerColor = Color(0xFF060C16),
            ) {
                DrawerContent(
                    recent = recent.map { it.id to it.title }, currentId = currentId,
                    onNewChat = { closeDrawerThen(vm::newChat) },
                    onOpen = { id -> closeDrawerThen { vm.open(id) } },
                    onHistory = { closeDrawerThen(onOpenHistory) },
                    onUsage = { closeDrawerThen(onOpenUsage) },
                    onAudit = { closeDrawerThen(onOpenAudit) },
                    onSecurity = { closeDrawerThen(onOpenSecurity) },
                    onSettings = { closeDrawerThen(onOpenSettings) },
                )
            }
        },
    ) {
        JarvisBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    navigationIcon = {
                        Box(Modifier.padding(start = 8.dp)) {
                            GlassIconButton(AppIcons.Menu, "Menu") { scope.launch { drawer.open() } }
                        }
                    },
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("JARVIS", style = MaterialTheme.typography.titleLarge.withBrandGradient())
                            if (model.isNotEmpty()) Text(model, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = {
                        if (state.messages.any { !it.streaming && it.text.isNotBlank() }) {
                            val copyContext = LocalContext.current
                            GlassIconButton(AppIcons.ContentCopy, "Copy chat") {
                                copyToClipboard(
                                    copyContext, "JARVIS chat", chatTranscript(state.messages),
                                    sensitive = state.messages.any { it.screenOnly },
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        GlassIconButton(
                            if (quiet) AppIcons.VolumeOff else AppIcons.VolumeUp,
                            if (quiet) "Quiet mode on: replies on screen only" else "Spoken replies on",
                        ) { vm.toggleQuiet() }
                        Spacer(Modifier.width(8.dp))
                        GlassIconButton(AppIcons.Add, "New chat", onClick = vm::newChat)
                        Spacer(Modifier.width(8.dp))
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                    AnimatedVisibility(budget == BudgetLevel.WARNING || budget == BudgetLevel.EXCEEDED) {
                        BudgetBanner(exceeded = budget == BudgetLevel.EXCEEDED, onClick = onOpenUsage)
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (state.messages.isEmpty() && voiceState != VoiceState.LISTENING) {
                            HomeState(
                            recent = recent.map { it.id to it.title },
                            onSuggestion = { vm.send(it) },
                            onOpen = vm::open,
                            onSeeAll = onOpenHistory,
                            onMic = onMic,
                            micEnabled = vm.sttAvailable,
                        )
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                items(state.messages, key = { it.id }) { msg ->
                                    Box(Modifier.animateItem()) {
                                        if (msg.fromUser) UserBubble(msg.text)
                                        else AssistantMessage(
                                            msg,
                                            onUnlock = vm::unlock,
                                            onFix = { action ->
                                                when (action) {
                                                    FixAction.OPEN_SETTINGS -> onOpenSettings()
                                                    FixAction.RETRY -> vm.retry()
                                                    FixAction.NONE -> Unit
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    AnimatedVisibility(voiceError != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        VoiceErrorLine(voiceError, onDismiss = vm::dismissVoiceError)
                    }
                    AnimatedVisibility(voiceState == VoiceState.SPEAKING, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        SpeakingPill(onStop = vm::stopSpeaking)
                    }
                    AnimatedVisibility(voiceState == VoiceState.LISTENING, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        ListeningPanel(partial = partial, level = level, onDone = onMic, onCancel = vm::cancelListening)
                    }
                    Composer(
                        value = input, onValueChange = { input = it },
                        busy = state.busy, onSend = send, onStop = vm::stop,
                        micEnabled = vm.sttAvailable, listening = voiceState == VoiceState.LISTENING, onMic = onMic,
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun MicRationaleDialog(onAllow: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { IconTile(AppIcons.Mic, MaterialTheme.colorScheme.primary, size = 48) },
        title = { Text("Talk to JARVIS", textAlign = TextAlign.Center) },
        text = {
            Text(
                "JARVIS needs the microphone to hear you. It listens only after you tap the mic, and stops when you're done. " +
                    "When your phone supports it, speech is recognised on the phone itself.",
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = { Button(onClick = { onDismiss(); onAllow() }) { Text("Allow microphone") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

@Composable
private fun ListeningPanel(partial: String, level: Float, onDone: () -> Unit, onCancel: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).glass(MaterialTheme.shapes.extraLarge),
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            JarvisOrb(150.dp, state = OrbState.LISTENING, level = level)
            Spacer(Modifier.height(12.dp))
            AnimatedContent(partial.ifBlank { "Listening…" }, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "partial") { t ->
                Text(
                    t, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                    color = if (partial.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Button(onClick = onDone) { Text("Done") }
            }
        }
    }
}

@Composable
private fun SpeakingPill(onStop: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, onClick = onStop) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.GraphicEq, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Speaking · tap to stop", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

@Composable
private fun VoiceErrorLine(error: SttError?, onDismiss: () -> Unit) {
    val text = when (error) {
        SttError.NO_SPEECH -> "Didn't hear anything. Tap the mic and speak."
        SttError.NO_MATCH -> "Didn't catch that. Try again."
        SttError.NO_PERMISSION -> "Microphone permission is off. Allow it in Android settings."
        SttError.BUSY -> "Speech recognition is busy. Try again in a moment."
        SttError.NETWORK -> "Speech recognition needs a connection, or offline speech (Android settings → Languages)."
        SttError.UNAVAILABLE -> "Speech recognition isn't available for your language on this phone."
        SttError.OTHER -> "Speech recognition failed. Try again."
        null -> ""
    }
    Surface(
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), onClick = onDismiss,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Icon(AppIcons.Close, "Dismiss", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun DrawerContent(
    recent: List<Pair<Long, String>>,
    currentId: Long?,
    onNewChat: () -> Unit,
    onOpen: (Long) -> Unit,
    onHistory: () -> Unit,
    onUsage: () -> Unit,
    onAudit: () -> Unit,
    onSecurity: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 16.dp)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            JarvisOrb(44.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("JARVIS", style = MaterialTheme.typography.titleLarge.withBrandGradient())
                Text("Your personal assistant", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = onNewChat, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Icon(AppIcons.Add, null); Spacer(Modifier.width(8.dp)); Text("New chat")
        }
        Spacer(Modifier.height(16.dp))
        Text("Recent", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
        Column(Modifier.weight(1f)) {
            if (recent.isEmpty()) {
                Text("No chats yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
            LazyColumn {
                items(recent, key = { it.first }) { (id, title) ->
                    NavigationDrawerItem(
                        label = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        icon = { Icon(AppIcons.ChatBubbleOutline, null, modifier = Modifier.size(20.dp)) },
                        selected = id == currentId,
                        onClick = { onOpen(id) },
                    )
                }
                item {
                    NavigationDrawerItem(
                        label = { Text("All history") }, icon = { Icon(AppIcons.History, null) },
                        selected = false, onClick = onHistory,
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        DrawerLink("Usage", AppIcons.BarChart, onUsage)
        DrawerLink("Audit log", AppIcons.Policy, onAudit)
        DrawerLink("Security", AppIcons.Shield, onSecurity)
        DrawerLink("Settings", AppIcons.Settings, onSettings)
    }
}

@Composable
private fun DrawerLink(label: String, icon: ImageVector, onClick: () -> Unit) =
    NavigationDrawerItem(label = { Text(label) }, icon = { Icon(icon, null) }, selected = false, onClick = onClick)

/** Quick actions on the home screen: label, icon, what to ask. */
private val QuickActions = listOf(
    Triple("Net worth", AppIcons.Savings, "What's my net worth?"),
    Triple("My SIPs", AppIcons.BarChart, "How are my SIPs doing?"),
)

/** Home (no messages yet): greeting with the orb, talk button, quick-action cards and recent chats. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeState(
    recent: List<Pair<Long, String>>,
    onSuggestion: (String) -> Unit,
    onOpen: (Long) -> Unit,
    onSeeAll: () -> Unit,
    onMic: () -> Unit,
    micEnabled: Boolean,
) {
    var shown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val greeting = when (LocalTime.now().hour) {
        in 5..11 -> "Good morning."
        in 12..16 -> "Good afternoon."
        else -> "Good evening."
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AnimatedVisibility(shown, enter = fadeIn() + slideInVertically { it / 3 }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(greeting, style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "What can I do for you?", style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    JarvisOrb(104.dp)
                }
            }
        }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 6.dp),
            ) {
                if (micEnabled) {
                    Surface(
                        onClick = onMic, shape = CircleShape, color = Color.Transparent,
                        modifier = Modifier.height(48.dp).clip(CircleShape)
                            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Brand.Cyan, Brand.Blue))),
                    ) {
                        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(AppIcons.Mic, null, tint = Color(0xFF00121F), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Talk to JARVIS", style = MaterialTheme.typography.labelLarge, color = Color(0xFF00121F))
                        }
                    }
                }
                Surface(
                    onClick = { onSuggestion("What's the time?") }, shape = CircleShape, color = Color.Transparent,
                    modifier = Modifier.size(48.dp).glass(CircleShape),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(AppIcons.Timer, "What's the time?", tint = Brand.Cyan, modifier = Modifier.size(20.dp))
                    }
                }
                Surface(
                    onClick = onSeeAll, shape = CircleShape, color = Color.Transparent,
                    modifier = Modifier.size(48.dp).glass(CircleShape),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(AppIcons.History, "All chats", tint = Brand.Cyan, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickActions.forEach { (label, icon, ask) ->
                    Surface(
                        onClick = { onSuggestion(ask) }, shape = MaterialTheme.shapes.extraLarge, color = Color.Transparent,
                        modifier = Modifier.weight(1f).height(150.dp).glass(MaterialTheme.shapes.extraLarge),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row {
                                Box(Modifier.size(40.dp).glass(CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(icon, null, tint = Brand.Cyan, modifier = Modifier.size(20.dp))
                                }
                                Spacer(Modifier.weight(1f))
                                Icon(AppIcons.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                            }
                            Spacer(Modifier.weight(1f))
                            Text(label, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
        if (recent.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent chats", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onSeeAll) { Text("See all", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            items(recent.take(4), key = { it.first }) { (id, title) ->
                Surface(
                    onClick = { onOpen(id) }, shape = MaterialTheme.shapes.large, color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth().glass(MaterialTheme.shapes.large),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).glass(CircleShape), contentAlignment = Alignment.Center) {
                            Icon(AppIcons.ChatBubbleOutline, null, tint = Brand.Cyan, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(AppIcons.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }
            }
        } else {
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Suggestions.forEach { s ->
                        Surface(onClick = { onSuggestion(s) }, shape = CircleShape, color = Color.Transparent, modifier = Modifier.glass(CircleShape)) {
                            Text(s, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Your message. Long-press copies it. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(text: String) {
    val context = LocalContext.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 6.dp)
        Surface(
            shape = shape,
            color = Color.Transparent,
            modifier = Modifier.widthIn(max = 320.dp).glass(shape, tint = Brand.Cyan, strength = 1.6f)
                .clip(shape)
                .combinedClickable(onClickLabel = null, onLongClickLabel = "Copy", onLongClick = {
                    copyToClipboard(context, "Your message", text, sensitive = false)
                }, onClick = {}),
        ) {
            Text(text, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssistantMessage(msg: UiMessage, onUnlock: () -> Unit, onFix: (FixAction) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        OrbAvatar(thinking = msg.streaming, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                msg.hidden -> HiddenCard(onUnlock)
                msg.text.isNotEmpty() -> {
                    val shape = RoundedCornerShape(topStart = 6.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 22.dp)
                    Box(Modifier.glass(shape)) {
                        SelectionContainer {
                            Text(
                                msg.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
                msg.streaming -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(28.dp)) { TypingDots() }
            }
            val copyable = !msg.streaming && !msg.hidden && msg.text.isNotBlank()
            if (msg.dataLabel != null || (msg.screenOnly && !msg.hidden) || copyable) {
                val context = LocalContext.current
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    msg.dataLabel?.let { StatusChip(it, ChipTone.BRAND, AppIcons.Update) }
                    if (msg.screenOnly && !msg.hidden) StatusChip("Shown on screen only", ChipTone.NEUTRAL, AppIcons.VisibilityOff)
                    if (copyable) {
                        Surface(
                            onClick = { copyToClipboard(context, "JARVIS reply", msg.text, sensitive = msg.screenOnly) },
                            shape = CircleShape, color = Color.Transparent, modifier = Modifier.glass(CircleShape),
                        ) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(AppIcons.ContentCopy, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Copy", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            msg.status?.let {
                AnimatedContent(it, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "status") { s ->
                    Text(s, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            msg.error?.let { err ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer) {
                    Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(err.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                        if (err.fix != FixAction.NONE && err.fixLabel != null) {
                            TextButton(onClick = { onFix(err.fix) }) { Text(err.fixLabel, color = MaterialTheme.colorScheme.onErrorContainer) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HiddenCard(onUnlock: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Lock, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Sensitive", style = MaterialTheme.typography.titleSmall)
                Text("Hidden until you unlock", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = onUnlock) { Text("Unlock") }
        }
    }
}

@Composable
private fun BudgetBanner(exceeded: Boolean, onClick: () -> Unit) {
    val tint = if (exceeded) MaterialTheme.colorScheme.error else Brand.Warning
    Surface(
        shape = MaterialTheme.shapes.medium, color = tint.copy(alpha = 0.14f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Savings, null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (exceeded) "Monthly budget used up" else "Over 80% of this month's budget", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onClick) { Text("View") }
        }
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    micEnabled: Boolean,
    listening: Boolean,
    onMic: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).glass(RoundedCornerShape(28.dp)),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
            TextField(
                value = value, onValueChange = onValueChange,
                placeholder = { Text("Ask JARVIS anything…") },
                modifier = Modifier.weight(1f),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
            val mode = when {
                busy -> 0
                value.isBlank() && micEnabled -> 1
                else -> 2
            }
            AnimatedContent(
                targetState = mode,
                transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) },
                label = "sendStop",
                modifier = Modifier.padding(bottom = 4.dp),
            ) { m ->
                if (m == 1) {
                    FilledIconButton(
                        onClick = onMic, modifier = Modifier.size(48.dp),
                        colors = if (listening) IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)
                        else IconButtonDefaults.filledIconButtonColors(containerColor = Brand.Cyan, contentColor = Color(0xFF00121F)),
                    ) { Icon(AppIcons.Mic, if (listening) "Finish speaking" else "Talk to JARVIS") }
                } else if (m == 0) {
                    FilledIconButton(
                        onClick = onStop, modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurface),
                    ) { Icon(AppIcons.Stop, "Stop") }
                } else {
                    FilledIconButton(
                        onClick = onSend, enabled = value.isNotBlank(), modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Brand.Cyan, contentColor = Color(0xFF00121F)),
                    ) { Icon(AppIcons.Send, "Send") }
                }
            }
        }
    }
}
