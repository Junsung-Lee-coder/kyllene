package dev.hermeskotlin.android.wear

import dev.hermeskotlin.android.wake.PhoneWakeController
import dev.hermeskotlin.android.wake.WakeVoice
import dev.hermeskotlin.android.pebble.PebbleOutbox
import dev.hermeskotlin.android.pebble.PebbleRequests
import dev.hermeskotlin.android.pebble.PebbleRinger
import dev.hermeskotlin.android.pebble.RoutingOutbox
import dev.hermeskotlin.core.wear.WatchOutbox
import dev.hermeskotlin.core.wear.WatchRelay
import dev.hermeskotlin.ui.voice.CatalogueWatchCallWords
import dev.hermeskotlin.ui.voice.WatchCalls
import dev.hermeskotlin.ui.calls.PhoneCallModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * The watch companion's phone side: the relay to Hermes, its live calls, the Data Layer and the Pebble link under them,
 * its request notifications and the accent it follows.
 */
val wearModule = module {
    single { PebbleOutbox(androidContext(), get(), get(), get()) }
    single<WatchOutbox> { RoutingOutbox(PhoneOutbox(androidContext()), get()) }
    single { PebbleRequests(get(), get(), get(), get(), get()) }
    single { PebbleRinger(androidContext(), get(), get(), get(), get()) }
    // Fork: a watch's spoken ask is a voice request; its reply isn't read after text sent on the phone (SpeechGate).
    single { WatchRelay(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), CatalogueWatchCallWords, speechGate = get()) }
    // Fork: the watch's live calls are kept with the phone's (LiveTranscripts).
    single { WatchCalls(get(), get(), get(), get(), get(), transcripts = get(), speechGate = get(), settings = get()) }
    single { WearRequests(get(), get()) }
    single(createdAtStart = true) { WatchProfileNotifier(androidContext(), get(), get(), get()).also { it.start() } }
    single { WatchRinger(androidContext(), get(), get(), get(), get(), get()) }
    single { WatchAccentSync(androidContext(), get(), get()) }
    // Fork (requirements §5): the phone's wake word.
    single { WakeVoice(get(), get(), get(), get(), get(), get(), get(), get(), liveTranscripts = get(), speechGate = get()) }
    single { PhoneWakeController(androidContext(), get(), get(), get(), get()) }
    // A call for an answer picked up on the phone holds its chat as a call from the watch does.
    // Fork: spelled out, for the live transcripts it keeps.
    viewModel { PhoneCallModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), liveTranscripts = get(), speechGate = get()) }
}
