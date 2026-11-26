package dev.hermeskotlin.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronsUpDown
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Square
import dev.hermeskotlin.core.settings.AppSettings
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.DropdownMenu
import dev.hermeskotlin.designsystem.components.MenuChoice
import dev.hermeskotlin.designsystem.components.Status
import dev.hermeskotlin.designsystem.components.StatusDot
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import dev.hermeskotlin.ui.resources.*
import dev.hermeskotlin.ui.voice.PhoneWake
import dev.hermeskotlin.ui.voice.PhoneWakeState
import dev.hermeskotlin.ui.voice.rememberMicrophonePermission
import dev.hermeskotlin.wearlink.WakeEndSilence
import dev.hermeskotlin.wearlink.WakePhrases
import org.jetbrains.compose.resources.stringResource

/**
 * Fork (requirements §5): Settings › Wake word on the phone. Both switches start off. The status under Start says what
 * the phone's listening actually does ([PhoneWake.state]), never what was asked for: a phone without an on-device
 * recognizer says it can't listen outside the app. The watch's rows are stored here and sent to it; it starts listening
 * outside its app the next time its app is opened.
 */
@Composable
internal fun WakeWordSettings(settings: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit) {
    Section(stringResource(Res.string.settings_section_wake)) {
        SwitchRow(
            title = stringResource(Res.string.settings_phone_wake),
            detail = stringResource(Res.string.settings_phone_wake_detail),
            checked = settings.phoneWake,
            onCheckedChange = { on -> onUpdate { it.copy(phoneWake = on) } },
        )
        Divider()
        SwitchRow(
            title = stringResource(Res.string.settings_watch_wake),
            detail = stringResource(Res.string.settings_watch_wake_detail),
            checked = settings.watchWake,
            onCheckedChange = { on -> onUpdate { it.copy(watchWake = on) } },
        )
        Divider()
        WakePhrasesField(settings.wakePhrases) { phrases -> onUpdate { it.copy(wakePhrases = phrases) } }
        Divider()
        WakeEndField(WakeEndSilence.clamp(settings.wakeEndSilenceMs)) { ms -> onUpdate { it.copy(wakeEndSilenceMs = ms) } }
        Divider()
        PhoneListeningControl(settings)
    }
    Section(stringResource(Res.string.settings_wake_phone_listening)) {
        SwitchRow(stringResource(Res.string.settings_wake_outside), null, settings.phoneWakeBackground, { on -> onUpdate { it.copy(phoneWakeBackground = on) } })
        Divider()
        SwitchRow(stringResource(Res.string.settings_wake_screen_off), null, settings.phoneWakeScreenOff, { on -> onUpdate { it.copy(phoneWakeScreenOff = on) } })
        Divider()
        SwitchRow(stringResource(Res.string.settings_wake_charging), null, settings.phoneWakeCharging, { on -> onUpdate { it.copy(phoneWakeCharging = on) } })
        Note(stringResource(Res.string.settings_wake_phone_note))
    }
    Section(stringResource(Res.string.settings_wake_watch_listening)) {
        SwitchRow(stringResource(Res.string.settings_wake_outside), null, settings.watchWakeBackground, { on -> onUpdate { it.copy(watchWakeBackground = on) } })
        Divider()
        SwitchRow(stringResource(Res.string.settings_wake_screen_off), null, settings.watchWakeScreenOff, { on -> onUpdate { it.copy(watchWakeScreenOff = on) } })
        Divider()
        SwitchRow(stringResource(Res.string.settings_wake_charging), null, settings.watchWakeCharging, { on -> onUpdate { it.copy(watchWakeCharging = on) } })
        Note(stringResource(Res.string.settings_wake_watch_note))
    }
    val info = stringResource(Res.string.settings_wake_notification_note)
    if (settingShown(info)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            UnstyledIcon(Lucide.Info, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.padding(top = 2.dp))
            Text(info, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary], modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 13.dp))
}

/** The phrases as typed; Save keeps what fits the limits. Empty shows, and uses, the whole default list. */
@Composable
private fun WakePhrasesField(saved: String, onSave: (String) -> Unit) {
    val title = stringResource(Res.string.settings_wake_phrases)
    val helper = stringResource(Res.string.settings_wake_phrases_detail)
    val state = rememberTextFieldState(saved.ifBlank { WakePhrases.DEFAULT })
    Field(title) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(state, supportingText = helper, singleLine = false, maxLines = 4)
            Button(
                stringResource(Res.string.settings_wake_save),
                onClick = {
                    val typed = WakePhrases.normalize(state.text.toString())
                    // The default list as it stands is saved as "use the default".
                    onSave(if (typed == WakePhrases.DEFAULT) "" else typed)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The quiet spell that ends a wake-started request: 0.5–10 s in 0.5 steps. */
@Composable
private fun WakeEndField(ms: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val labels = WakeEndSilence.choices.associateWith { stringResource(Res.string.settings_wake_seconds, seconds(it)) }
    Field(stringResource(Res.string.settings_wake_end), detail = stringResource(Res.string.settings_wake_end_detail)) {
        DropdownMenu(
            expanded = open,
            onExpandedChange = { open = it },
            maxHeight = 320.dp,
            items = {
                WakeEndSilence.choices.forEach { choice ->
                    MenuChoice(labels.getValue(choice), Lucide.Clock, selected = choice == ms, onClick = {
                        open = false
                        onPick(choice)
                    })
                }
            },
        ) {
            Button(labels.getValue(ms), onClick = { open = true }, variant = ButtonVariant.Outline, trailingIcon = Lucide.ChevronsUpDown)
        }
    }
}

/** "2.0", "0.5", "10.0": the setting's seconds as Settings shows them. */
internal fun seconds(ms: Int): String = "${ms / 1000}.${ms % 1000 / 100}"

/**
 * Start listening outside the app, or Stop it: started only here, by a tap with the app on screen, the microphone
 * allowed; the status says what's actually going on.
 */
@Composable
private fun PhoneListeningControl(settings: AppSettings) {
    val state by PhoneWake.state.collectAsState()
    val onDevice by PhoneWake.onDevice.collectAsState()
    val microphone = rememberMicrophonePermission()
    val running = state == PhoneWakeState.Listening || state == PhoneWakeState.InApp
    val canStart = settings.phoneWake && settings.phoneWakeBackground && onDevice != false
    val status = when (state) {
        PhoneWakeState.Stopped -> if (onDevice == false && settings.phoneWakeBackground) Status.Warning to stringResource(Res.string.settings_wake_state_no_on_device)
            else Status.Neutral to stringResource(Res.string.settings_wake_state_stopped)
        PhoneWakeState.Listening -> Status.Ok to stringResource(Res.string.settings_wake_state_listening)
        PhoneWakeState.InApp -> Status.Ok to stringResource(Res.string.settings_wake_state_in_app)
        PhoneWakeState.NoOnDevice -> Status.Warning to stringResource(Res.string.settings_wake_state_no_on_device)
        PhoneWakeState.NoMicrophone -> Status.Warning to stringResource(Res.string.settings_wake_state_no_microphone)
        PhoneWakeState.Refused -> Status.Error to stringResource(Res.string.settings_wake_state_refused)
    }
    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (running) {
            Button(stringResource(Res.string.settings_wake_stop), onClick = { PhoneWake.stop() }, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Outline, leadingIcon = Lucide.Square)
        } else {
            Button(
                stringResource(Res.string.settings_wake_start),
                onClick = { microphone.withMicrophone(onDenied = { PhoneWake.state.value = PhoneWakeState.NoMicrophone }) { PhoneWake.start() } },
                modifier = Modifier.fillMaxWidth(),
                variant = ButtonVariant.Outline,
                enabled = canStart,
                leadingIcon = Lucide.Mic,
            )
        }
        StatusDot(status.first, status.second)
    }
}
