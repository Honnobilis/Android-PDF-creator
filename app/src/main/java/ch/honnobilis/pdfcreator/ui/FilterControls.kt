package ch.honnobilis.pdfcreator.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.honnobilis.pdfcreator.model.FilterMode
import kotlin.math.roundToInt

/** Row of filter chips. [autoResult] is shown next to "Auto" (e.g. "Auto · B&W"). */
@Composable
fun FilterChips(selected: FilterMode?, autoResult: FilterMode?, onSelect: (FilterMode) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterMode.entries.forEach { mode ->
            FilterChip(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                label = {
                    Text(if (mode == FilterMode.AUTO && autoResult != null) "Auto · ${autoResult.label}" else mode.label)
                },
            )
        }
    }
}

/**
 * Strength slider. Reports the value only when the finger is lifted
 * (re-rendering on every move would be too slow).
 */
@Composable
fun StrengthSlider(
    effective: FilterMode?,
    strength: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    /** Value while dragging; null when the finger is lifted. */
    onLiveChange: (Float?) -> Unit = {},
) {
    var value by remember(strength) { mutableFloatStateOf(strength) }
    val enabled = effective != FilterMode.ORIGINAL
    val label = when (effective) {
        FilterMode.BW -> "Background cleanup"
        FilterMode.ORIGINAL -> "Strength (not used for Original)"
        else -> "Strength"
    }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (enabled) Text("${(value * 100).roundToInt()} %", style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value,
            onValueChange = {
                value = it
                onLiveChange(it)
            },
            onValueChangeFinished = {
                onChange(value)
                onLiveChange(null)
            },
            enabled = enabled,
            modifier = Modifier.padding(top = 0.dp),
        )
    }
}
