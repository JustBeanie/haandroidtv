package dev.haquickaccess.tv.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun TvFocusSurface(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    requestInitialFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    shape: Shape = RoundedCornerShape(12.dp),
    minimumHeight: Dp = 48.dp,
    focusedScale: Float = 1.03f,
    background: Color = HaSurface,
    focusedBackground: Color = HaSurfaceFocused,
    border: Color = HaBorder,
    focusedBorder: Color = HaBlue,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val localFocusRequester = remember { FocusRequester() }
    val activeFocusRequester = focusRequester ?: localFocusRequester
    val scale by animateFloatAsState(
        targetValue = if (focused) focusedScale else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "$contentDescription focus scale",
    )
    Box(
        modifier
            .then(if (upFocusRequester == null) Modifier else Modifier.focusProperties { up = upFocusRequester })
            .focusRequester(activeFocusRequester)
            .onPreviewKeyEvent { event ->
                if (
                    upFocusRequester != null &&
                    event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP &&
                    event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN
                ) {
                    upFocusRequester.requestFocus()
                    true
                } else {
                    false
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shadowElevation = if (focused) 10.dp.toPx() else 0f
            }
            .heightIn(min = minimumHeight)
            .semantics { this.contentDescription = contentDescription; role = Role.Button }
            .background(if (focused) focusedBackground else background, shape)
            .onFocusChanged { focused = it.isFocused }
            .border(if (focused) 2.dp else 1.dp, if (focused) focusedBorder else border, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content(focused)
    }
    LaunchedEffect(requestInitialFocus, enabled) {
        if (requestInitialFocus && enabled) activeFocusRequester.requestFocus()
    }
}

@Composable
internal fun TvButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    requestInitialFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
) {
    val background = when {
        destructive -> HaRed.copy(alpha = .22f)
        primary && enabled -> HaBlue
        else -> HaSurface
    }
    TvFocusSurface(
        contentDescription = label,
        onClick = onClick,
        enabled = enabled,
        requestInitialFocus = requestInitialFocus,
        focusRequester = focusRequester,
        upFocusRequester = upFocusRequester,
        shape = RoundedCornerShape(8.dp),
        background = background,
        focusedBackground = if (primary && enabled) HaBlue else HaSurfaceFocused,
        border = if (primary) HaBlue else HaBorder,
    ) { focused ->
        val foreground = when {
            !enabled -> HaMuted
            primary -> Color(0xFF082132)
            else -> HaText
        }
        HaText(label, 14.sp, foreground)
    }
}

@Composable
internal fun TvListRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    requestInitialFocus: Boolean = false,
) {
    TvFocusSurface(
        contentDescription = title,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        requestInitialFocus = requestInitialFocus,
        minimumHeight = 82.dp,
        focusedScale = 1.015f,
        shape = RoundedCornerShape(18.dp),
    ) { focused ->
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 17.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                HaText(title, 18.sp)
                androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
                HaText(subtitle, 14.sp, HaMuted)
            }
            HaText("›", 30.sp, if (focused) HaBlue else HaMuted)
        }
    }
}

@Composable
internal fun TvDialog(
    contentDescription: String,
    width: Dp = 600.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = .72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(width)
                .semantics { this.contentDescription = contentDescription }
                .background(HaSurface, RoundedCornerShape(18.dp))
                .border(1.dp, HaBorder, RoundedCornerShape(18.dp))
                .padding(28.dp),
            content = content,
        )
    }
}
