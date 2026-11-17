package dev.haquickaccess.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.haquickaccess.tv.domain.model.ControlAction
import dev.haquickaccess.tv.domain.model.alarmArmModes
import dev.haquickaccess.tv.domain.model.alarmCodeIsNumeric
import dev.haquickaccess.tv.domain.model.actionLabel
import dev.haquickaccess.tv.domain.model.capabilities
import dev.haquickaccess.tv.domain.model.climateMaximum
import dev.haquickaccess.tv.domain.model.climateMinimum
import dev.haquickaccess.tv.domain.model.climateStep
import dev.haquickaccess.tv.domain.model.hvacModes
import dev.haquickaccess.tv.domain.model.numberMaximum
import dev.haquickaccess.tv.domain.model.numberMinimum
import dev.haquickaccess.tv.domain.model.numberStep
import dev.haquickaccess.tv.domain.model.selectOptions
import kotlin.math.roundToInt

@Composable
internal fun DetailDialog(detail: DetailState, onEvent: DashboardActions) {
    TvDialog(contentDescription = "Control details", onDismissRequest = onEvent::closeDetails) {
        when (detail) {
            is DetailState.Level -> LevelDetails(detail, onEvent)
            is DetailState.Climate -> ClimateDetails(detail, onEvent)
            is DetailState.Cover -> CoverDetails(detail, onEvent)
            is DetailState.Alarm -> AlarmDetails(detail, onEvent)
            is DetailState.Number -> NumberDetails(detail, onEvent)
            is DetailState.Select -> SelectDetails(detail, onEvent)
            is DetailState.Text -> TextDetails(detail, onEvent)
        }
    }
}

@Composable
internal fun LevelDetails(detail: DetailState.Level, onEvent: DashboardActions) {
    HaText(detail.entity.name, 25.sp)
    Spacer(Modifier.height(8.dp))
    HaText("${if (detail.entity.domain == "fan") "Speed" else "Brightness"}: ${detail.stagedPercent}%", 17.sp, HaMuted)
    Spacer(Modifier.height(20.dp))
    ValueStepper(detail.stagedPercent, 0..100, { onEvent.stageLevel(it) })
    Spacer(Modifier.height(24.dp))
    ActionRow("Cancel", onEvent::closeDetails, "Apply ${detail.stagedPercent}%", onEvent::applyLevel, requestInitialFocus = true)
}

@Composable
internal fun ClimateDetails(detail: DetailState.Climate, onEvent: DashboardActions) {
    HaText(detail.entity.name, 25.sp)
    Spacer(Modifier.height(8.dp))
    HaText("${detail.entity.state.replaceFirstChar(Char::uppercase)} · ${detail.entity.string("hvac_action") ?: "Idle"}", 17.sp, HaMuted)
    Spacer(Modifier.height(14.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        detail.entity.hvacModes().forEach { mode -> HaButton(mode.replace('_', ' '), { onEvent.setClimateMode(detail.entity, mode) }) }
    }
    Spacer(Modifier.height(20.dp))
    detail.stagedTemperature?.let { temperature ->
        HaText("Target: ${"%.1f".format(temperature)}°", 17.sp, HaMuted)
        ValueStepper(
            value = (temperature * 10).roundToInt(),
            range = (detail.entity.climateMinimum() * 10).roundToInt()..(detail.entity.climateMaximum() * 10).roundToInt(),
            onChange = { onEvent.stageClimateTemperature(it / 10.0) },
            increment = (detail.entity.climateStep() * 10).roundToInt().coerceAtLeast(1),
        )
        Spacer(Modifier.height(20.dp))
        ActionRow("Close", onEvent::closeDetails, "Apply temperature", onEvent::applyClimateTemperature, requestInitialFocus = true)
    } ?: HaButton("Close", onEvent::closeDetails, requestInitialFocus = true)
}

@Composable
internal fun CoverDetails(detail: DetailState.Cover, onEvent: DashboardActions) {
    HaText(detail.entity.name, 25.sp)
    Spacer(Modifier.height(10.dp))
    if (detail.pendingCommand != null) {
        HaText("Open this secure cover?", 18.sp, HaRed)
        Spacer(Modifier.height(16.dp))
        ActionRow("Cancel", onEvent::cancelSecureCover, "Open", onEvent::confirmSecureCover, requestInitialFocus = true)
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HaButton("Open cover", { onEvent.coverCommand(ControlAction.CoverCommand.Command.OPEN) }, primary = true)
            HaButton("Close cover", { onEvent.coverCommand(ControlAction.CoverCommand.Command.CLOSE) })
            HaButton("Stop", { onEvent.coverCommand(ControlAction.CoverCommand.Command.STOP) })
        }
        if (detail.entity.capabilities().canSetCoverPosition) {
            Spacer(Modifier.height(20.dp))
            val position = detail.stagedPosition ?: 0
            HaText("Position: $position%", 17.sp, HaMuted)
            ValueStepper(
                value = position,
                range = 0..100,
                onChange = onEvent::stageCoverPosition,
            )
            Spacer(Modifier.height(14.dp))
            HaButton("Apply position", onEvent::applyCoverPosition, primary = true)
        }
        Spacer(Modifier.height(22.dp))
        HaButton("Done", onEvent::closeDetails, requestInitialFocus = true)
    }
}

@Composable
internal fun AlarmDetails(detail: DetailState.Alarm, onEvent: DashboardActions) {
    HaText(detail.entity.name, 25.sp)
    Spacer(Modifier.height(8.dp))
    HaText(detail.entity.state.replace('_', ' ').replaceFirstChar(Char::uppercase), 17.sp, HaRed)
    Spacer(Modifier.height(18.dp))
    if (detail.entity.state == "disarmed") {
        if (detail.entity.capabilities().alarmCodeRequired) {
            HaTextField(
                "Alarm code",
                detail.disarmCode,
                onEvent::updateAlarmCode,
                true,
                numeric = detail.entity.alarmCodeIsNumeric(),
            )
            Spacer(Modifier.height(16.dp))
            HaText("The code is used once and never stored.", 14.sp, HaMuted)
            Spacer(Modifier.height(16.dp))
        }
        val supportedModes = detail.entity.alarmArmModes()
        if (supportedModes.isEmpty()) {
            HaText("This alarm did not report any supported arm modes.", 15.sp, HaMuted)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                supportedModes.forEach { mode ->
                    HaButton("Arm ${mode.replace('_', ' ')}", { onEvent.armAlarm(mode) })
                }
            }
        }
    } else {
        HaTextField(
            "Alarm code",
            detail.disarmCode,
            onEvent::updateAlarmCode,
            true,
            numeric = detail.entity.alarmCodeIsNumeric(),
        )
        Spacer(Modifier.height(16.dp))
        HaText("The code is used once and never stored.", 14.sp, HaMuted)
        Spacer(Modifier.height(18.dp))
        ActionRow("Cancel", onEvent::closeDetails, "Disarm", onEvent::disarmAlarm, destructive = true, requestInitialFocus = true)
    }
    Spacer(Modifier.height(20.dp))
    HaButton("Close", onEvent::closeDetails, requestInitialFocus = detail.entity.state == "disarmed")
}

@Composable
internal fun NumberDetails(detail: DetailState.Number, onEvent: DashboardActions) {
    val minimum = detail.entity.numberMinimum()
    val maximum = detail.entity.numberMaximum()
    HaText(detail.entity.name, 25.sp)
    Spacer(Modifier.height(8.dp))
    HaText("Value: ${formatNumber(detail.stagedValue)}", 17.sp, HaMuted)
    Spacer(Modifier.height(4.dp))
    HaText("Range ${formatNumber(minimum)}–${formatNumber(maximum)} · step ${formatNumber(detail.entity.numberStep())}", 14.sp, HaMuted)
    Spacer(Modifier.height(20.dp))
    DecimalStepper(
        value = detail.stagedValue,
        minimum = minimum,
        maximum = maximum,
        step = detail.entity.numberStep(),
        onChange = onEvent::stageNumberValue,
    )
    Spacer(Modifier.height(24.dp))
    ActionRow("Cancel", onEvent::closeDetails, "Apply", onEvent::applyNumberValue, requestInitialFocus = true)
}

@Composable
internal fun SelectDetails(detail: DetailState.Select, onEvent: DashboardActions) {
    HaText(detail.entity.name, 25.sp)
    Spacer(Modifier.height(8.dp))
    HaText("Choose an option", 17.sp, HaMuted)
    Spacer(Modifier.height(18.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        detail.entity.selectOptions().forEach { option ->
            HaButton(
                label = option,
                onClick = { onEvent.selectOption(option) },
                primary = option == detail.selectedOption,
            )
        }
    }
    Spacer(Modifier.height(22.dp))
    HaButton("Cancel", onEvent::closeDetails, requestInitialFocus = true)
}

@Composable
internal fun TextDetails(detail: DetailState.Text, onEvent: DashboardActions) {
    HaText(detail.entity.name, 25.sp)
    Spacer(Modifier.height(8.dp))
    HaText("Update the text value", 17.sp, HaMuted)
    Spacer(Modifier.height(18.dp))
    HaTextField(
        label = "Value",
        value = detail.stagedValue,
        onValueChange = onEvent::updateTextValue,
        secret = false,
        placeholder = "Enter value",
    )
    Spacer(Modifier.height(24.dp))
    ActionRow("Cancel", onEvent::closeDetails, "Apply", onEvent::applyTextValue, requestInitialFocus = true)
}

@Composable
internal fun ValueStepper(value: Int, range: IntRange, onChange: (Int) -> Unit, increment: Int = 5) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HaButton("−", { onChange((value - increment).coerceAtLeast(range.first)) })
        Box(Modifier.weight(1f).height(12.dp).background(HaBorder, RoundedCornerShape(8.dp))) {
            Box(Modifier.fillMaxWidth(((value - range.first).toFloat() / (range.last - range.first).coerceAtLeast(1)).coerceIn(0f, 1f)).height(12.dp).background(HaAmber, RoundedCornerShape(8.dp)))
        }
        HaButton("+", { onChange((value + increment).coerceAtMost(range.last)) })
    }
}

@Composable
internal fun DecimalStepper(
    value: Double,
    minimum: Double,
    maximum: Double,
    step: Double,
    onChange: (Double) -> Unit,
) {
    val span = (maximum - minimum).takeIf { it > 0 } ?: 1.0
    val progress = ((value - minimum) / span).toFloat().coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HaButton("−", { onChange((value - step).coerceAtLeast(minimum)) })
        Box(Modifier.weight(1f).height(12.dp).background(HaBorder, RoundedCornerShape(8.dp))) {
            Box(Modifier.fillMaxWidth(progress).height(12.dp).background(HaAmber, RoundedCornerShape(8.dp)))
        }
        HaButton("+", { onChange((value + step).coerceAtMost(maximum)) })
    }
}

@Composable
internal fun ActionRow(
    cancelLabel: String,
    onCancel: () -> Unit,
    actionLabel: String,
    onAction: () -> Unit,
    destructive: Boolean = false,
    requestInitialFocus: Boolean = false,
) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HaButton(cancelLabel, onCancel, requestInitialFocus = requestInitialFocus)
        Spacer(Modifier.width(10.dp))
        HaButton(actionLabel, onAction, primary = !destructive, destructive = destructive)
    }
}
