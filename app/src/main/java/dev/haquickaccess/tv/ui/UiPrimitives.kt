package dev.haquickaccess.tv.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Blinds
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import dev.haquickaccess.tv.R
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.haquickaccess.tv.domain.model.ControlKind
import java.util.Locale

@Composable
internal fun SettingRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    requestInitialFocus: Boolean = false,
) {
    TvListRow(title, subtitle, onClick, requestInitialFocus)
    Spacer(Modifier.height(12.dp))
}

@Composable
internal fun HaTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    secret: Boolean,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    requestInitialFocus: Boolean = false,
    placeholder: String = "Enter value",
    downFocusRequester: FocusRequester? = null,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    Column(modifier.widthIn(max = 520.dp).fillMaxWidth()) {
        HaText(label, 14.sp, HaMuted)
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            cursorBrush = SolidColor(HaCyan),
            textStyle = TextStyle(color = HaText, fontSize = 17.sp),
            keyboardOptions = KeyboardOptions(keyboardType = when { numeric -> KeyboardType.NumberPassword; secret -> KeyboardType.Password; else -> KeyboardType.Text }),
            visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .focusRequester(focusRequester)
                .onFocusChanged {
                    focused = it.isFocused
                    onFocusChanged(it.isFocused)
                }
                .onPreviewKeyEvent { event ->
                    if (
                        downFocusRequester != null &&
                        event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN &&
                        event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN
                    ) {
                        downFocusRequester.requestFocus()
                        true
                    } else {
                        false
                    }
                }
                .semantics { contentDescription = label }
                .background(HaSurface, RoundedCornerShape(10.dp))
                .border(if (focused) 2.dp else 1.dp, if (focused) HaBlue else HaBorder, RoundedCornerShape(10.dp))
                .padding(14.dp),
            decorationBox = { innerTextField ->
                Box {
                    if (value.isBlank()) HaText(placeholder, 17.sp, HaMuted)
                    innerTextField()
                }
            },
        )
    }
    LaunchedEffect(requestInitialFocus) {
        if (requestInitialFocus) focusRequester.requestFocus()
    }
}

@Composable
internal fun HaButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    requestInitialFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
) {
    TvButton(label, onClick, primary, destructive, enabled, requestInitialFocus, focusRequester, upFocusRequester)
}

@Composable
internal fun ErrorMessage(message: String, dismiss: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(12.dp).heightIn(min = 48.dp).background(HaRed.copy(alpha = .22f), RoundedCornerShape(10.dp)).clickable(onClick = dismiss).padding(14.dp), contentAlignment = Alignment.Center) {
        HaText(message, 15.sp, HaText)
    }
}

@Composable
internal fun HaText(
    value: String,
    size: androidx.compose.ui.unit.TextUnit,
    color: Color = HaText,
) {
    HaText(value, size, Modifier, color)
}

@Composable
internal fun HaText(
    value: String,
    size: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier,
    color: Color = HaText,
) {
    Text(value, modifier = modifier, color = color, fontSize = size)
}

@Composable
internal fun tileIcon(kind: ControlKind) = when (kind) {
    ControlKind.LIGHT -> Icons.Outlined.Lightbulb
    ControlKind.FAN -> ImageVector.vectorResource(R.drawable.ic_fan)
    ControlKind.CLIMATE -> Icons.Outlined.Thermostat
    ControlKind.COVER -> Icons.Outlined.Blinds
    ControlKind.ALARM -> Icons.Outlined.Security
    ControlKind.SCENE, ControlKind.SCRIPT -> Icons.Outlined.PlayArrow
    ControlKind.BUTTON -> Icons.Outlined.TouchApp
    ControlKind.NUMBER, ControlKind.SELECT, ControlKind.TEXT -> Icons.Outlined.Settings
    ControlKind.SWITCH, ControlKind.INPUT_BOOLEAN, ControlKind.GROUP -> Icons.Outlined.Power
    ControlKind.UNSUPPORTED -> Icons.Outlined.WarningAmber
}

internal fun formatNumber(value: Double): String = String.format(Locale.US, "%.3f", value)
    .trimEnd('0')
    .trimEnd('.')
