package com.tapnex.autoclicker.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun FloatingSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf(FloatingSettings.load(context)) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TextButton(onClick = onBack) { Text("Back") }
        Text(
            "Floating control settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Changes save automatically. If the floating control is already showing, speed and size update within a moment.",
            style = MaterialTheme.typography.bodySmall
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tap speed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Slow (5 s)", style = MaterialTheme.typography.bodySmall)
                    Text("Fast (0.1 s)", style = MaterialTheme.typography.bodySmall)
                }
                Slider(
                    value = snapshot.speed.toFloat(),
                    onValueChange = { v ->
                        val speed = v.roundToInt()
                        FloatingSettings.saveSpeed(context, speed)
                        snapshot = snapshot.copy(speed = speed)
                    },
                    valueRange = 0f..100f
                )
                Text("Current: ${snapshot.intervalMs} ms between taps", style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Icon size", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Small (${FloatingSettings.MIN_SIZE_DP} dp)", style = MaterialTheme.typography.bodySmall)
                    Text("Large (${FloatingSettings.MAX_SIZE_DP} dp)", style = MaterialTheme.typography.bodySmall)
                }
                Slider(
                    value = snapshot.sizeDp.toFloat(),
                    onValueChange = { v ->
                        val size = v.roundToInt()
                        FloatingSettings.saveSizeDp(context, size)
                        snapshot = snapshot.copy(sizeDp = size)
                    },
                    valueRange = FloatingSettings.MIN_SIZE_DP.toFloat()..FloatingSettings.MAX_SIZE_DP.toFloat()
                )
                Text("Current: ${snapshot.sizeDp} dp", style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Position", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (snapshot.hasPosition) {
                        "Saved position: x ${snapshot.x}, y ${snapshot.y}. Drag the floating icon to change it."
                    } else {
                        "Using the default position. Drag the floating icon once to save a position."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    onClick = {
                        FloatingSettings.clearPosition(context)
                        snapshot = FloatingSettings.load(context)
                        message = "Saved position cleared. The default position will be used."
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Reset saved position") }
            }
        }

        var secondary by remember { mutableStateOf(SecondaryControlSettings.load(context)) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Second floating button", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    SecondaryAction.DESCRIPTION,
                    style = MaterialTheme.typography.bodySmall
                )
                Text("Size: ${secondary.sizeDp} dp", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = secondary.sizeDp.toFloat(),
                    onValueChange = { v ->
                        val size = v.roundToInt()
                        SecondaryControlSettings.saveSizeDp(context, size)
                        secondary = secondary.copy(sizeDp = size)
                    },
                    valueRange = SecondaryControlSettings.MIN_SIZE_DP.toFloat()..SecondaryControlSettings.MAX_SIZE_DP.toFloat()
                )
                Text(
                    if (secondary.hasPosition) "Saved position: x ${secondary.x}, y ${secondary.y}. Drag the button to change it."
                    else "Using the default position.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    onClick = {
                        SecondaryControlSettings.clearPosition(context)
                        secondary = SecondaryControlSettings.load(context)
                        message = "Second button position cleared."
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Reset second button position") }
            }
        }

        Button(
            onClick = {
                FloatingSettings.restoreDefaults(context)
                SecondaryControlSettings.restoreDefaults(context)
                snapshot = FloatingSettings.load(context)
                secondary = SecondaryControlSettings.load(context)
                message = "Defaults restored for both floating buttons."
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Restore default settings") }

        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}
