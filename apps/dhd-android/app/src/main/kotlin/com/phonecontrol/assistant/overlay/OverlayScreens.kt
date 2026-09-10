package com.phonecontrol.assistant.overlay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontrol.assistant.session.DhdToolCall
import com.phonecontrol.assistant.session.DhdToolCallStatus
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.ui.LocalAssistantColors
import kotlinx.coroutines.flow.StateFlow

enum class OverlayPanelMode {
    BUBBLE,
    COMPOSER,
    WORKING,
    ATTENTION,
    RESULT,
}

@Composable
fun OverlayGlow(
    sessionState: StateFlow<SessionState>,
    hidden: StateFlow<Boolean>,
) {
    val state by sessionState.collectAsState()
    val isHidden by hidden.collectAsState()
    val colors = LocalAssistantColors.current
    val active = state is SessionState.Running || state is SessionState.Paused
    if (!active || isHidden) return

    val attention = state.needsAttention()
    val glowColor = if (attention) colors.warningAmber else colors.accentBlue
    val transition = rememberInfiniteTransition(label = "dhd-edge-glow")
    val outerAlpha by transition.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.38f,
        animationSpec = infiniteRepeatable(tween(1_200), RepeatMode.Reverse),
        label = "dhd-edge-glow-alpha",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(2.dp)
            .border(
                BorderStroke(10.dp, glowColor.copy(alpha = outerAlpha)),
                RoundedCornerShape(34.dp),
            )
            .border(
                BorderStroke(2.dp, glowColor.copy(alpha = 0.82f)),
                RoundedCornerShape(34.dp),
            ),
    )
}

@Composable
fun OverlayPanel(
    sessionState: StateFlow<SessionState>,
    toolCalls: StateFlow<List<DhdToolCall>>,
    panelMode: StateFlow<OverlayPanelMode>,
    resultMessage: StateFlow<String?>,
    onExpand: () -> Unit,
    onNewRequest: () -> Unit,
    onSubmit: (String) -> Unit,
    onDrag: (Float, Float) -> Unit,
    onStop: () -> Unit,
    onContinueInDhd: () -> Unit,
) {
    val state by sessionState.collectAsState()
    val calls by toolCalls.collectAsState()
    val mode by panelMode.collectAsState()
    val result by resultMessage.collectAsState()
    val colors = LocalAssistantColors.current
    val active = state is SessionState.Running || state is SessionState.Paused
    val effectiveMode = when {
        active && state.needsAttention() -> OverlayPanelMode.ATTENTION
        active -> OverlayPanelMode.WORKING
        else -> mode
    }

    when (effectiveMode) {
        OverlayPanelMode.BUBBLE -> BubbleButton(
            onClick = onExpand,
            onDrag = onDrag,
        )
        OverlayPanelMode.COMPOSER -> ComposerSurface(
            onSubmit = onSubmit,
        )
        OverlayPanelMode.WORKING -> WorkingSurface(
            state = state,
            calls = calls,
            colors = colors,
            onStop = onStop,
            onContinueInDhd = onContinueInDhd,
        )
        OverlayPanelMode.ATTENTION -> AttentionSurface(
            state = state,
            calls = calls,
            colors = colors,
            onStop = onStop,
            onContinueInDhd = onContinueInDhd,
        )
        OverlayPanelMode.RESULT -> ResultSurface(
            message = result ?: terminalMessage(state),
            colors = colors,
            onNewRequest = onNewRequest,
            onContinueInDhd = onContinueInDhd,
        )
    }
}

@Composable
private fun BubbleButton(
    onClick: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    val colors = LocalAssistantColors.current
    Surface(
        modifier = Modifier
            .size(64.dp)
            .semantics { contentDescription = "Open DHD overlay" }
            .clickable(onClick = onClick)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            },
        shape = CircleShape,
        color = colors.composerBackground,
        border = BorderStroke(2.dp, colors.accentBlue.copy(alpha = 0.8f)),
        shadowElevation = 14.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "✦",
                color = colors.accentBlue,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ComposerSurface(
    onSubmit: (String) -> Unit,
) {
    val colors = LocalAssistantColors.current
    val focusManager = LocalFocusManager.current
    var draft by rememberSaveable { mutableStateOf("") }

    fun submit() {
        val request = draft.trim()
        if (request.isBlank()) return
        draft = ""
        focusManager.clearFocus()
        onSubmit(request)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        color = colors.composerBackground.copy(alpha = 0.97f),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, colors.borderColor),
        shadowElevation = 18.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it.take(4_000) },
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp)
                    .semantics { contentDescription = "Ask DHD input" },
                textStyle = TextStyle(
                    color = colors.textPrimary,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                ),
                minLines = 1,
                maxLines = 5,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                decorationBox = { innerTextField ->
                    Box {
                        if (draft.isBlank()) {
                            Text(
                                text = "Ask DHD",
                                color = colors.textSecondary,
                                fontSize = 16.sp,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            Spacer(Modifier.width(10.dp))
            Surface(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .clickable(enabled = draft.isNotBlank(), onClick = ::submit),
                shape = CircleShape,
                color = if (draft.isNotBlank()) colors.sendButtonActiveBg else colors.sendButtonInactiveBg,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "↑",
                        color = if (draft.isNotBlank()) colors.sendButtonActiveIcon else colors.sendButtonInactiveIcon,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkingSurface(
    state: SessionState,
    calls: List<DhdToolCall>,
    colors: com.phonecontrol.assistant.ui.AssistantColorScheme,
    onStop: () -> Unit,
    onContinueInDhd: () -> Unit,
    attentionOverride: Boolean = false,
) {
    val sessionId = state.sessionIdOrNull()
    val sessionCalls = calls.filter { it.sessionId == sessionId }
    val currentCall = sessionCalls.lastOrNull { it.status == DhdToolCallStatus.RUNNING }
    val currentPurpose = currentCall?.purpose ?: state.currentPurposeOrNull() ?: "Working on the phone"
    val attention = attentionOverride || state.needsAttention()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        color = colors.composerBackground.copy(alpha = 0.97f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, if (attention) colors.warningAmber else colors.accentBlue),
        shadowElevation = 18.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (attention) "DHD needs your attention" else "DHD is working",
                    color = if (attention) colors.warningAmber else colors.accentBlue,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "⋯",
                    color = colors.textSecondary,
                    fontSize = 22.sp,
                )
            }
            Text(
                text = currentPurpose,
                color = colors.textPrimary,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (sessionCalls.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 174.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(sessionCalls.takeLast(3), key = { it.id }) { call ->
                        ToolCallRow(call = call, colors = colors)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onContinueInDhd),
                    color = colors.accentBlue,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Continue in DHD",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
                Surface(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onStop),
                    color = colors.surfaceCard,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, colors.borderColor),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "■",
                            color = colors.errorRed,
                            fontSize = 15.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AttentionSurface(
    state: SessionState,
    calls: List<DhdToolCall>,
    colors: com.phonecontrol.assistant.ui.AssistantColorScheme,
    onStop: () -> Unit,
    onContinueInDhd: () -> Unit,
) {
    WorkingSurface(
        state = state,
        calls = calls,
        colors = colors,
        onStop = onStop,
        onContinueInDhd = onContinueInDhd,
        attentionOverride = true,
    )
}

@Composable
private fun ToolCallRow(
    call: DhdToolCall,
    colors: com.phonecontrol.assistant.ui.AssistantColorScheme,
) {
    val statusColor = when (call.status) {
        DhdToolCallStatus.COMPLETED -> colors.accentGreen
        DhdToolCallStatus.FAILED -> colors.errorRed
        DhdToolCallStatus.ATTENTION -> colors.warningAmber
        DhdToolCallStatus.RUNNING -> colors.accentBlue
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceCard.copy(alpha = 0.82f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = when (call.status) {
                DhdToolCallStatus.COMPLETED -> "✓"
                DhdToolCallStatus.FAILED -> "!"
                DhdToolCallStatus.ATTENTION -> "?"
                DhdToolCallStatus.RUNNING -> "•"
            },
            color = statusColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = call.purpose,
                color = colors.textPrimary,
                fontSize = 12.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = call.toolName,
                color = colors.textSecondary,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ResultSurface(
    message: String,
    colors: com.phonecontrol.assistant.ui.AssistantColorScheme,
    onNewRequest: () -> Unit,
    onContinueInDhd: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        color = colors.composerBackground.copy(alpha = 0.97f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, colors.borderColor),
        shadowElevation = 18.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = message,
                color = colors.textPrimary,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onNewRequest),
                    color = colors.accentBlue,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Ask another thing", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onContinueInDhd),
                    color = colors.surfaceCard,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, colors.borderColor),
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Open DHD", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

private fun SessionState.sessionIdOrNull(): String? = when (this) {
    SessionState.Idle -> null
    is SessionState.Running -> sessionId
    is SessionState.Paused -> sessionId
    is SessionState.Stopped -> sessionId
    is SessionState.Completed -> sessionId
}

private fun SessionState.currentPurposeOrNull(): String? = when (this) {
    is SessionState.Running -> currentPurpose
    is SessionState.Paused -> currentPurpose
    else -> null
}

private fun SessionState.needsAttention(): Boolean =
    currentPurposeOrNull()?.equals("Needs your attention", ignoreCase = true) == true

private fun terminalMessage(state: SessionState): String = when (state) {
    is SessionState.Completed -> state.message
    is SessionState.Stopped -> state.reason
    else -> "DHD is ready for another request."
}
