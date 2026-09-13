package nyc.curbside.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Explains what Curbside asks for before Android asks for it.
 *
 * Every permission here fails silently when denied — no fix, no pin, no warning, and nothing on
 * screen to say why, since by definition the app is closed when it needed them. The screen exists
 * so the user makes that trade knowingly rather than tapping through four system dialogs whose
 * wording ("Physical activity"? "All the time"?) says nothing about parking.
 *
 * It is shown once, and reachable again from Settings. Declining is a real option and leaves the
 * app working in a reduced form, which the summary spells out rather than hiding.
 */
private enum class Stage { EXPLAIN, BACKGROUND, SUMMARY }

@Composable
fun PermissionsScreen(
    viewModel: PermissionsViewModel = hiltViewModel(),
    onDone: () -> Unit,
) {
    val context = LocalContext.current

    var stage by remember { mutableStateOf(Stage.EXPLAIN) }
    var granted by remember { mutableStateOf(Ask.entries.associateWith { it.isGranted(context) }) }

    // Also the return path from the system settings page, where the background permission has to be
    // granted by hand: there is no result to listen for, only the user coming back.
    LifecycleResumeEffect(Unit) {
        granted = Ask.entries.associateWith { it.isGranted(context) }
        if (granted[Ask.ACTIVITY] == true) viewModel.onPermissionsChanged()
        onPauseOrDispose { }
    }

    val upFrontRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        granted = Ask.entries.associateWith { ask -> ask.isGranted(context) }
        if (granted[Ask.ACTIVITY] == true) viewModel.onPermissionsChanged()
        // Background location is only worth asking about once foreground location is in hand;
        // asked cold it is refused out of hand by the platform.
        stage = if (granted[Ask.LOCATION] == true && granted[Ask.BACKGROUND] == false) {
            Stage.BACKGROUND
        } else {
            Stage.SUMMARY
        }
    }

    val backgroundRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        granted = Ask.entries.associateWith { ask -> ask.isGranted(context) }
        stage = Stage.SUMMARY
    }

    val finish = {
        viewModel.onFinished()
        onDone()
    }

    Column(
        Modifier
            .fillMaxSize()
            // This screen is shown outside the Scaffold, so it owns its own inset handling;
            // without this the heading sits under the status bar clock.
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (stage) {
            Stage.EXPLAIN -> Explain(
                granted = granted,
                onContinue = {
                    val missing = Ask.upFront.filter { granted[it] == false }
                    val toRequest = missing.flatMap { it.request.toList() }
                    if (toRequest.isEmpty()) {
                        stage = if (granted[Ask.BACKGROUND] == false) Stage.BACKGROUND else Stage.SUMMARY
                    } else {
                        upFrontRequest.launch(toRequest.toTypedArray())
                    }
                },
                onSkip = finish,
            )

            Stage.BACKGROUND -> Background(
                onContinue = {
                    if (backgroundNeedsSettings()) {
                        context.startActivity(appSettingsIntent(context))
                        stage = Stage.SUMMARY
                    } else {
                        backgroundRequest.launch(Ask.BACKGROUND.request)
                    }
                },
                onSkip = { stage = Stage.SUMMARY },
            )

            Stage.SUMMARY -> Summary(
                granted = granted,
                onOpenSettings = { context.startActivity(appSettingsIntent(context)) },
                onDone = finish,
            )
        }
    }
}

@Composable
private fun Explain(granted: Map<Ask, Boolean>, onContinue: () -> Unit, onSkip: () -> Unit) {
    Text("Curbside works with the app closed", style = MaterialTheme.typography.headlineSmall)
    Text(
        "You will not have Curbside open at the moment you park — you will be getting out of the " +
            "car. Everything below is what lets it notice that anyway. Android will ask about each " +
            "one in turn; here is what they are for.",
        style = MaterialTheme.typography.bodyMedium,
    )

    Ask.entries.forEach { ask -> AskCard(ask, granted[ask] == true) }

    Text(
        "Curbside never keeps GPS running and never sends your location anywhere. Parking history " +
            "stays on this phone unless you turn on sharing.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )

    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
    TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
}

@Composable
private fun AskCard(ask: Ask, granted: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (granted) "✓ ${ask.title}" else ask.title,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(ask.why, style = MaterialTheme.typography.bodyMedium)
            if (!granted) {
                Text(
                    ask.cost,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/**
 * The one dialog users reliably get wrong, so it gets its own screen.
 *
 * On Android 11 and later there is no "all the time" option in the permission dialog at all — the
 * only way to grant it is the settings page, which is why this sends them there with the exact
 * label to look for rather than firing a request that would come straight back denied.
 */
@Composable
private fun Background(onContinue: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    val label = backgroundOptionLabel(context)

    Text("One more, and it is the important one", style = MaterialTheme.typography.headlineSmall)
    Text(Ask.BACKGROUND.why, style = MaterialTheme.typography.bodyMedium)
    Text(
        if (backgroundNeedsSettings()) {
            "Android does not offer this in a pop-up. The next screen is Curbside's settings page: " +
                "open Permissions, then Location, then choose “$label”."
        } else {
            "When the dialog appears, choose “$label”."
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    // The three things people actually want to know before granting "all the time": when it
    // happens, how long it lasts, and what runs in between. In between, nothing does.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            "Only at the end of a drive. Your phone notices the car has stopped, Curbside checks " +
                "the spot once, and that is it.",
            "A few seconds, once or twice a day. In between it is not running — the GPS stays " +
                "off and nothing is following you around.",
            "On a day you do not drive, Curbside does nothing at all.",
        ).forEach { line ->
            Text(
                "·  $line",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }

    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
        Text(if (backgroundNeedsSettings()) "Open settings" else "Continue")
    }
    TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("Skip this one") }
}

@Composable
private fun Summary(granted: Map<Ask, Boolean>, onOpenSettings: () -> Unit, onDone: () -> Unit) {
    val missing = Ask.entries.filter { granted[it] == false }

    Text(
        if (missing.isEmpty()) "All set" else "Curbside is partly set up",
        style = MaterialTheme.typography.headlineSmall,
    )

    if (missing.isEmpty()) {
        Text(
            "Park anywhere in the city and Curbside will record the spot on its own, then warn you " +
                "before the sweeper is due.",
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        Text(
            "It will still work, with the gaps below. You can change any of these later in " +
                "Android's settings, or from Curbside's own Settings tab.",
            style = MaterialTheme.typography.bodyMedium,
        )
        missing.forEach { ask ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("· ${ask.title}", style = MaterialTheme.typography.titleMedium)
                    Text(ask.cost, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Open Android settings")
        }
    }

    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}
