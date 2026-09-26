package com.example

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin

private val BgDark = Color(0xFF090514)
private val PrimaryPurple = Color(0xFFA855F7)
private val PrimaryPink = Color(0xFFEC4899)
private val AccentCyan = Color(0xFF06B6D4)
private val AccentGreen = Color(0xFF10B981)
private val CardBg = Color(0xFF140C26)
private val CardBorder = Color(0xFF2B184A)
private val TextMuted = Color(0xFF94A3B8)

@Composable
fun ArushiScreen(
    viewModel: ArushiViewModel,
    hasContactsPermission: Boolean,
    hasCallPermission: Boolean,
    onRequestPermissions: () -> Unit,
    onStartSpeechRecognition: () -> Unit
) {
    val state by viewModel.assistantState.collectAsState()
    val detectedLang by viewModel.detectedLanguage.collectAsState()
    val voiceExchange by viewModel.voiceExchange.collectAsState()
    val latestAction by viewModel.latestAction.collectAsState()
    val activeTab by viewModel.activeTab.collectAsState()
    val currentPersonality by ArushiVoiceService.currentPersonality.collectAsState()
    val wakeState by ArushiVoiceService.currentWakeState.collectAsState()
    val echoIsolationActive by ArushiVoiceService.echoCancellationActive.collectAsState()

    val bgCommand by ArushiVoiceService.lastVoiceCommand.collectAsState()
    val bgResponse by ArushiVoiceService.lastVoiceResponse.collectAsState()
    val bgAction by ArushiVoiceService.latestActionResult.collectAsState()
    val isBgListening by ArushiVoiceService.isListeningActive.collectAsState()

    val effectiveUserVoice = voiceExchange.userVoiceInput.ifBlank { bgCommand }
    val effectiveVoiceReply = if (voiceExchange.userVoiceInput.isBlank() && bgCommand.isNotBlank()) bgResponse else voiceExchange.arushiVoiceReply
    val effectiveAction = latestAction ?: bgAction

    val isListening = (state is AssistantState.Listening) || isBgListening
    val isSpeaking = state is AssistantState.Speaking
    val isThinking = state is AssistantState.Thinking

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = when (currentPersonality) {
                        MayaPersonality.GF -> listOf(Color(0xFF38103A), BgDark)
                        MayaPersonality.PROFESSIONAL -> listOf(Color(0xFF131D45), BgDark)
                        MayaPersonality.VENOM -> listOf(Color(0xFF260A0A), BgDark)
                    },
                    center = Offset(500f, 300f),
                    radius = 1100f
                )
            )
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Top Header Bar
            MayaTopHeader(
                currentPersonality = currentPersonality,
                wakeState = wakeState,
                echoActive = echoIsolationActive,
                onPersonalitySelect = { viewModel.setPersonality(it) },
                onToggleWake = { ArushiVoiceService.toggleWakeState() }
            )

            // Permissions alert if needed
            if (!hasContactsPermission || !hasCallPermission) {
                PermissionWarningBanner(onGrantClick = onRequestPermissions)
            }

            // 2. Multi-Mode Feature Tabs
            MayaTabsRow(
                activeTab = activeTab,
                onSelectTab = { viewModel.setTab(it) }
            )

            // 3. Tab Contents
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (activeTab) {
                    0 -> VoiceAssistantTab(
                        state = state,
                        isListening = isListening,
                        isSpeaking = isSpeaking,
                        isThinking = isThinking,
                        personality = currentPersonality,
                        wakeState = wakeState,
                        userVoiceInput = effectiveUserVoice,
                        arushiVoiceReply = effectiveVoiceReply,
                        effectiveAction = effectiveAction,
                        onMicClick = {
                            if (isSpeaking) {
                                viewModel.interrupt()
                            } else if (isListening) {
                                viewModel.setListening(false)
                            } else {
                                onStartSpeechRecognition()
                            }
                        },
                        onInterrupt = { viewModel.interrupt() },
                        onQuickPrompt = { prompt -> viewModel.submitQuery(prompt) }
                    )
                    1 -> WhiteboardTutorTab(
                        notes = viewModel.whiteboardNotes.collectAsState().value,
                        onAskVoice = { q -> viewModel.submitQuery(q) }
                    )
                    2 -> MarketsWatchlistTab(
                        onVoiceCheck = { symbol -> viewModel.submitQuery("$symbol price kya hai?") }
                    )
                    3 -> CodeGeneratorTab(
                        generatedCode = viewModel.generatedCode.collectAsState().value,
                        onGenerateWeb = { prompt -> viewModel.submitQuery(prompt) }
                    )
                    4 -> MemoryAndMacrosTab(
                        onRunMacro = { routine -> viewModel.submitQuery(routine) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MayaTopHeader(
    currentPersonality: MayaPersonality,
    wakeState: WakeState,
    echoActive: Boolean,
    onPersonalitySelect: (MayaPersonality) -> Unit,
    onToggleWake: () -> Unit
) {
    Surface(
        color = CardBg.copy(alpha = 0.9f),
        shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Identity
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                when (currentPersonality) {
                                    MayaPersonality.GF -> Brush.linearGradient(listOf(PrimaryPink, PrimaryPurple))
                                    MayaPersonality.PROFESSIONAL -> Brush.linearGradient(listOf(Color(0xFF38BDF8), Color(0xFF2563EB)))
                                    MayaPersonality.VENOM -> Brush.linearGradient(listOf(Color(0xFFEF4444), Color(0xFF09090B)))
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "M",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 17.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Column {
                        Text(
                            text = "Maya AI",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color.White
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "100% Background Voice",
                                fontSize = 10.sp,
                                color = AccentGreen,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (echoActive) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "• Echo-Proof",
                                    fontSize = 10.sp,
                                    color = AccentCyan
                                )
                            }
                        }
                    }
                }

                // Wake/Sleep Pill
                Surface(
                    color = if (wakeState == WakeState.AWAKE) AccentGreen.copy(alpha = 0.15f) else Color(0xFFF59E0B).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(100.dp),
                    modifier = Modifier.clickable { onToggleWake() }
                ) {
                    Text(
                        text = if (wakeState == WakeState.AWAKE) "🟢 Awake" else "🌙 Sleeping",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (wakeState == WakeState.AWAKE) AccentGreen else Color(0xFFFBBF24),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 3 Personalities Switcher
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PersonalityChip(
                    label = "💖 GF Mode",
                    selected = currentPersonality == MayaPersonality.GF,
                    accentColor = PrimaryPink,
                    onClick = { onPersonalitySelect(MayaPersonality.GF) },
                    modifier = Modifier.weight(1f)
                )
                PersonalityChip(
                    label = "💼 Professional",
                    selected = currentPersonality == MayaPersonality.PROFESSIONAL,
                    accentColor = AccentCyan,
                    onClick = { onPersonalitySelect(MayaPersonality.PROFESSIONAL) },
                    modifier = Modifier.weight(1f)
                )
                PersonalityChip(
                    label = "🖤 Venom",
                    selected = currentPersonality == MayaPersonality.VENOM,
                    accentColor = Color(0xFFEF4444),
                    onClick = { onPersonalitySelect(MayaPersonality.VENOM) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun PersonalityChip(
    label: String,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = if (selected) accentColor.copy(alpha = 0.25f) else CardBorder.copy(alpha = 0.4f),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) accentColor else Color.Transparent
        ),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Color.White else TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 5.dp)
        )
    }
}

@Composable
private fun MayaTabsRow(
    activeTab: Int,
    onSelectTab: (Int) -> Unit
) {
    val tabs = listOf(
        "🎙️ Voice AI",
        "🎨 Whiteboard",
        "📈 Markets",
        "💻 Code & Web",
        "🧠 Memories"
    )

    ScrollableTabRow(
        selectedTabIndex = activeTab,
        containerColor = Color.Transparent,
        contentColor = PrimaryPurple,
        edgePadding = 8.dp,
        divider = {}
    ) {
        tabs.forEachIndexed { index, title ->
            Tab(
                selected = activeTab == index,
                onClick = { onSelectTab(index) },
                text = {
                    Text(
                        text = title,
                        fontSize = 12.sp,
                        fontWeight = if (activeTab == index) FontWeight.Bold else FontWeight.Medium,
                        color = if (activeTab == index) Color.White else TextMuted
                    )
                }
            )
        }
    }
}

@Composable
private fun VoiceAssistantTab(
    state: AssistantState,
    isListening: Boolean,
    isSpeaking: Boolean,
    isThinking: Boolean,
    personality: MayaPersonality,
    wakeState: WakeState,
    userVoiceInput: String,
    arushiVoiceReply: String,
    effectiveAction: ActionExecutionResult?,
    onMicClick: () -> Unit,
    onInterrupt: () -> Unit,
    onQuickPrompt: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Scrollable central area
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Animated Voice Orb
            VoiceOrb(
                isListening = isListening,
                isSpeaking = isSpeaking,
                isThinking = isThinking,
                personality = personality,
                wakeState = wakeState,
                onClick = onMicClick
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Acoustic Waveform Visualizer
            AcousticWaveformVisualizer(
                isListening = isListening,
                isSpeaking = isSpeaking,
                isThinking = isThinking,
                personality = personality
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Live Spoken Dialogue Card
            LiveVoiceDialogueCard(
                userVoiceInput = userVoiceInput,
                arushiVoiceReply = arushiVoiceReply,
                state = state,
                latestAction = effectiveAction,
                personality = personality
            )

            Spacer(modifier = Modifier.height(10.dp))
        }

        // Voice Shortcuts & Control Center
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            VoiceShortcutChips(onChipClick = onQuickPrompt)

            Spacer(modifier = Modifier.height(12.dp))

            VoiceControlCenter(
                state = state,
                isListening = isListening,
                isSpeaking = isSpeaking,
                personality = personality,
                onMicClick = onMicClick,
                onInterrupt = onInterrupt
            )
        }
    }
}

@Composable
private fun VoiceOrb(
    isListening: Boolean,
    isSpeaking: Boolean,
    isThinking: Boolean,
    personality: MayaPersonality,
    wakeState: WakeState,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isListening || isSpeaking) 800 else 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val rippleAlpha by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple"
    )

    Box(
        modifier = Modifier
            .size(190.dp)
            .clickable { onClick() }
            .testTag("voice_orb"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val baseRadius = size.width / 2 - 10
            val ringColor = when {
                personality == MayaPersonality.VENOM -> Color(0xFFEF4444)
                personality == MayaPersonality.PROFESSIONAL -> AccentCyan
                isSpeaking -> PrimaryPink
                isListening -> AccentCyan
                else -> PrimaryPurple
            }

            drawCircle(
                color = ringColor.copy(alpha = rippleAlpha),
                radius = baseRadius * (if (isListening || isSpeaking) pulseScale else 1f),
                style = Stroke(width = if (isListening || isSpeaking) 3.dp.toPx() else 1.5.dp.toPx())
            )

            drawCircle(
                color = ringColor.copy(alpha = 0.35f),
                radius = baseRadius * 0.82f,
                style = Stroke(width = 2.dp.toPx())
            )
        }

        Box(
            modifier = Modifier
                .size(110.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = when (personality) {
                            MayaPersonality.GF -> listOf(PrimaryPink, PrimaryPurple, Color(0xFF22082A))
                            MayaPersonality.PROFESSIONAL -> listOf(Color(0xFF38BDF8), Color(0xFF2563EB), Color(0xFF0C132B))
                            MayaPersonality.VENOM -> listOf(Color(0xFFEF4444), Color(0xFF7F1D1D), Color(0xFF180505))
                        }
                    )
                )
                .shadow(elevation = 20.dp, shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when {
                    isSpeaking -> Icons.AutoMirrored.Filled.VolumeUp
                    isListening -> Icons.Default.Mic
                    wakeState == WakeState.SLEEPING -> Icons.Default.MicOff
                    else -> Icons.Default.Mic
                },
                contentDescription = "Voice State",
                tint = Color.White,
                modifier = Modifier.size(34.dp)
            )
        }
    }
}

@Composable
private fun AcousticWaveformVisualizer(
    isListening: Boolean,
    isSpeaking: Boolean,
    isThinking: Boolean,
    personality: MayaPersonality
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Canvas(
        modifier = Modifier
            .fillMaxWidth(0.72f)
            .height(30.dp)
    ) {
        val barCount = 26
        val spacing = size.width / barCount
        val barWidth = spacing * 0.55f

        for (i in 0 until barCount) {
            val barFactor = if (isListening || isSpeaking) {
                val wave = (sin(phase + i * 0.35f) + 1f) / 2f
                val secondary = (sin(phase * 1.5f + i * 0.7f) + 1f) / 2f
                wave * 0.6f + secondary * 0.4f
            } else if (isThinking) {
                val wave = (sin(phase * 2f + i * 0.5f) + 1f) / 2f
                wave * 0.45f + 0.15f
            } else {
                0.15f + 0.1f * sin(phase * 0.5f + i * 0.2f)
            }

            val barHeight = maxOf(3.dp.toPx(), size.height * barFactor)
            val barColor = when (personality) {
                MayaPersonality.GF -> if (i % 2 == 0) PrimaryPink else PrimaryPurple
                MayaPersonality.PROFESSIONAL -> if (i % 2 == 0) AccentCyan else Color(0xFF38BDF8)
                MayaPersonality.VENOM -> if (i % 2 == 0) Color(0xFFEF4444) else Color(0xFF991B1B)
            }

            val x = i * spacing + (spacing - barWidth) / 2
            val y = (size.height - barHeight) / 2

            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
            )
        }
    }
}

@Composable
private fun LiveVoiceDialogueCard(
    userVoiceInput: String,
    arushiVoiceReply: String,
    state: AssistantState,
    latestAction: ActionExecutionResult?,
    personality: MayaPersonality
) {
    Surface(
        color = CardBg.copy(alpha = 0.95f),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(18.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            if (userVoiceInput.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Surface(
                        color = Color(0xFF7C3AED).copy(alpha = 0.3f),
                        shape = RoundedCornerShape(100.dp)
                    ) {
                        Text(
                            text = "🗣️ YOU SAID",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFDDD6FE),
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }

                Text(
                    text = "\"$userVoiceInput\"",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(10.dp))
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                Surface(
                    color = when (personality) {
                        MayaPersonality.GF -> PrimaryPink.copy(alpha = 0.2f)
                        MayaPersonality.PROFESSIONAL -> AccentCyan.copy(alpha = 0.2f)
                        MayaPersonality.VENOM -> Color(0xFFEF4444).copy(alpha = 0.2f)
                    },
                    shape = RoundedCornerShape(100.dp)
                ) {
                    Text(
                        text = when (personality) {
                            MayaPersonality.GF -> "💖 MAYA (GF)"
                            MayaPersonality.PROFESSIONAL -> "💼 MAYA (PRO)"
                            MayaPersonality.VENOM -> "🖤 VENOM"
                        },
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (personality) {
                            MayaPersonality.GF -> PrimaryPink
                            MayaPersonality.PROFESSIONAL -> AccentCyan
                            MayaPersonality.VENOM -> Color(0xFFEF4444)
                        },
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = arushiVoiceReply,
                fontSize = 14.sp,
                color = Color(0xFFF1F5F9),
                lineHeight = 20.sp
            )

            AnimatedVisibility(visible = latestAction != null) {
                latestAction?.let { act ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = if (act.success) AccentGreen.copy(alpha = 0.15f) else Color(0xFFEF4444).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = if (act.success) "⚡" else "⚠️", fontSize = 12.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = act.message,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (act.success) AccentGreen else Color(0xFFF87171)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceShortcutChips(onChipClick: (String) -> Unit) {
    val shortcuts = listOf(
        "🔦 Torch on",
        "🔋 Battery kitni hai",
        "💬 WhatsApp kholo",
        "📞 Call Mom",
        "👤 Call Rahul",
        "▶️ Play Arijit Singh",
        "🔊 Volume up",
        "⏰ Set 7 AM alarm",
        "⏱️ 5 min timer",
        "📈 Bitcoin price",
        "🎨 Whiteboard kholo",
        "💻 Website bana do"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        shortcuts.forEach { chipText ->
            Surface(
                color = CardBg,
                shape = RoundedCornerShape(100.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
                modifier = Modifier
                    .clip(RoundedCornerShape(100.dp))
                    .clickable {
                        val clean = chipText.substringAfter(" ").trim()
                        onChipClick(clean.ifEmpty { chipText })
                    }
            ) {
                Text(
                    text = chipText,
                    fontSize = 11.sp,
                    color = Color(0xFFE2E8F0),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun VoiceControlCenter(
    state: AssistantState,
    isListening: Boolean,
    isSpeaking: Boolean,
    personality: MayaPersonality,
    onMicClick: () -> Unit,
    onInterrupt: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (isSpeaking) {
                IconButton(
                    onClick = onInterrupt,
                    modifier = Modifier
                        .padding(end = 14.dp)
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEF4444).copy(alpha = 0.25f))
                        .testTag("stop_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            if (isListening) {
                                listOf(Color(0xFFEF4444), PrimaryPink)
                            } else {
                                when (personality) {
                                    MayaPersonality.GF -> listOf(PrimaryPink, PrimaryPurple, AccentCyan)
                                    MayaPersonality.PROFESSIONAL -> listOf(AccentCyan, Color(0xFF2563EB))
                                    MayaPersonality.VENOM -> listOf(Color(0xFFEF4444), Color(0xFF180505))
                                }
                            }
                        )
                    )
                    .clickable { onMicClick() }
                    .shadow(14.dp, CircleShape)
                    .testTag("mic_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = "Mic",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = when {
                isListening -> "Listening... say 'Torch on' or 'WhatsApp kholo'"
                isSpeaking -> "Speaking... tap red button or say 'Stop' to interrupt"
                else -> "Say 'Hey Maya' or tap mic"
            },
            fontSize = 11.sp,
            color = TextMuted,
            textAlign = TextAlign.Center
        )
    }
}

// ---------------- TAB 1: WHITEBOARD TUTOR ----------------
@Composable
private fun WhiteboardTutorTab(
    notes: List<String>,
    onAskVoice: (String) -> Unit
) {
    var paths by remember { mutableStateOf(listOf<Path>()) }
    var currentPath by remember { mutableStateOf<Path?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🎨 Interactive Study Whiteboard",
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 15.sp
            )
            IconButton(onClick = { paths = emptyList(); currentPath = null }) {
                Icon(Icons.Default.Delete, contentDescription = "Clear", tint = TextMuted)
            }
        }

        // Drawing board canvas
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0F172A))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val p = Path().apply { moveTo(offset.x, offset.y) }
                            currentPath = p
                        },
                        onDrag = { change, _ ->
                            currentPath?.lineTo(change.position.x, change.position.y)
                        },
                        onDragEnd = {
                            currentPath?.let { paths = paths + it }
                            currentPath = null
                        }
                    )
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                paths.forEach { path ->
                    drawPath(path, color = Color(0xFF38BDF8), style = Stroke(width = 4.dp.toPx()))
                }
                currentPath?.let { path ->
                    drawPath(path, color = Color(0xFF38BDF8), style = Stroke(width = 4.dp.toPx()))
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Lesson & Tutor Notes:",
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFE2E8F0),
            fontSize = 13.sp
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(notes) { note ->
                Surface(
                    color = CardBg,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = note,
                        color = Color(0xFFCBD5E1),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }

        Button(
            onClick = { onAskVoice("Explain photosynthesis on whiteboard") },
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Ask Maya to Teach Something on Board")
        }
    }
}

// ---------------- TAB 2: LIVE MARKETS ----------------
@Composable
private fun MarketsWatchlistTab(
    onVoiceCheck: (String) -> Unit
) {
    val items = listOf(
        MarketItem("NIFTY 50", "NSE India", "25,372.40", "+0.78%", true),
        MarketItem("SENSEX", "BSE India", "82,888.15", "+0.65%", true),
        MarketItem("BTC/USD", "Bitcoin", "$64,280.00", "+2.45%", true),
        MarketItem("ETH/USD", "Ethereum", "$2,680.50", "-0.82%", false),
        MarketItem("GOLD 24K", "MCX 10g", "₹75,420", "+0.35%", true),
        MarketItem("AAPL", "Apple Inc.", "$228.40", "+1.12%", true)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "📈 Live Markets & Watchlist",
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 15.sp
            )
            Icon(Icons.AutoMirrored.Filled.TrendingUp, contentDescription = null, tint = AccentGreen)
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items) { item ->
                Surface(
                    color = CardBg,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onVoiceCheck(item.name) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = item.symbol, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                            Text(text = item.name, color = TextMuted, fontSize = 11.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(text = item.price, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                            Text(
                                text = item.change,
                                fontWeight = FontWeight.Bold,
                                color = if (item.isPositive) AccentGreen else Color(0xFFEF4444),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------- TAB 3: CODE & WEBSITES ----------------
@Composable
private fun CodeGeneratorTab(
    generatedCode: String?,
    onGenerateWeb: (String) -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    val codeText = generatedCode ?: """
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <title>Maya AI Generated Web App</title>
  <style>
    body { background: #0f172a; color: #fff; font-family: sans-serif; text-align: center; padding: 40px; }
    h1 { color: #a855f7; }
    .card { background: #1e293b; padding: 24px; border-radius: 12px; display: inline-block; }
  </style>
</head>
<body>
  <h1>Maya AI Dynamic Web Studio</h1>
  <div class="card">
    <p>Voice-driven website generation is ready!</p>
    <p>Say "Website bana do" to write new custom web apps.</p>
  </div>
</body>
</html>
    """.trimIndent()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "💻 Voice Code & Website Studio",
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 15.sp
            )
            TextButton(onClick = {
                clipboardManager.setText(AnnotatedString(codeText))
                copied = true
            }) {
                Text(if (copied) "✓ Copied!" else "Copy Code", color = AccentCyan)
            }
        }

        Surface(
            color = Color(0xFF0F172A),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = codeText,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color(0xFF7DD3FC),
                modifier = Modifier.padding(12.dp)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Button(
            onClick = { onGenerateWeb("Portfolio website bana do") },
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryPink),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Voice Command: 'Website bana do'")
        }
    }
}

// ---------------- TAB 4: MEMORIES & ROUTINES ----------------
@Composable
private fun MemoryAndMacrosTab(
    onRunMacro: (String) -> Unit
) {
    val memories = listOf(
        "Favorite color" to "Sky Blue",
        "User nickname" to "Boss",
        "Primary city" to "Mumbai",
        "Emergency contact" to "Mom (Saved in contacts)"
    )

    val macros = listOf(
        "Morning Routine" to "Battery check + Daily News on YouTube + WhatsApp check",
        "Night Routine" to "Turn off torch + Mute volume + Set 7 AM alarm",
        "Study Focus" to "Open Whiteboard + Set 25 min timer"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "🧠 Memory That Lasts",
            fontWeight = FontWeight.Bold,
            color = Color.White,
            fontSize = 15.sp
        )
        Text(
            text = "Maya remembers your preferences across sessions.",
            color = TextMuted,
            fontSize = 11.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        memories.forEach { (k, v) ->
            Surface(
                color = CardBg,
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = k, color = TextMuted, fontSize = 12.sp)
                    Text(text = v, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "⚡ Task Macros & Routines",
            fontWeight = FontWeight.Bold,
            color = Color.White,
            fontSize = 15.sp
        )
        Text(
            text = "Say routine name to execute multiple phone actions at once.",
            color = TextMuted,
            fontSize = 11.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        macros.forEach { (name, desc) ->
            Surface(
                color = CardBg,
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onRunMacro("Run $name") }
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(text = desc, color = TextMuted, fontSize = 11.sp)
                    }
                    Icon(Icons.Default.PlayArrow, contentDescription = "Run", tint = AccentCyan)
                }
            }
        }
    }
}

@Composable
private fun PermissionWarningBanner(onGrantClick: () -> Unit) {
    Surface(
        color = Color(0xFFF59E0B).copy(alpha = 0.15f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "🛡️ Grant Contacts & Calls for hands-free calling.",
                fontSize = 11.sp,
                color = Color(0xFFFDE68A),
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onGrantClick) {
                Text("Grant", color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }
    }
}
