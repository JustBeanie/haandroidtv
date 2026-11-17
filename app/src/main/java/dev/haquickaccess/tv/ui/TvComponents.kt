package dev.haquickaccess.tv.ui

import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Surface
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .then(if (upFocusRequester == null) Modifier else Modifier.focusProperties { up = upFocusRequester })
            .focusRequester(activeFocusRequester)
            .onFocusChanged { focused = it.isFocused }
            .heightIn(min = minimumHeight)
            .semantics { this.contentDescription = contentDescription; role = Role.Button },
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = background,
            focusedContainerColor = focusedBackground,
            contentColor = HaText,
            focusedContentColor = HaText,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, border), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, focusedBorder), shape = shape),
        ),
    ) { content(focused) }
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
    val localRequester = remember { FocusRequester() }
    val requester = focusRequester ?: localRequester
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp).focusRequester(requester)
            .then(if (upFocusRequester == null) Modifier else Modifier.focusProperties { up = upFocusRequester }),
        colors = ButtonDefaults.colors(
            containerColor = when { destructive -> HaRed.copy(alpha = .2f); primary -> HaBlue; else -> HaSurface },
            contentColor = if (primary) HaBackground else HaText,
            focusedContainerColor = HaText,
            focusedContentColor = HaBackground,
        ),
        scale = ButtonDefaults.scale(focusedScale = 1.03f),
    ) { HaText(label, 16.sp, androidx.tv.material3.LocalContentColor.current) }
    LaunchedEffect(requestInitialFocus, enabled) {
        if (requestInitialFocus && enabled) requester.requestFocus()
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
            androidx.tv.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = HaMuted)
        }
    }
}

@Composable
internal fun TvDialog(
    contentDescription: String,
    width: Dp = 600.dp,
    onDismissRequest: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val maximumHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * .85f }
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.widthIn(max = width).fillMaxWidth(.9f).heightIn(max = maximumHeight)
                .semantics { this.contentDescription = contentDescription }
                .background(HaSurface, RoundedCornerShape(16.dp))
                .verticalScroll(rememberScrollState()).padding(24.dp),
            content = content,
        )
    }
}
