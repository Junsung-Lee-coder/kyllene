# This fork's changes to Kyllene

A personal fork that keeps changes to upstream [Giton22/kyllene](https://github.com/Giton22/kyllene) small, so they
can be carried onto each new release. It adds voice behaviour for a phone and watch used without looking at the
screen. This page lists what the fork changes and how to move it to a newer release.

## Upstream base

| | |
|---|---|
| Upstream | `Giton22/kyllene` |
| Release | `v0.18.0` (latest stable release when the branch was cut) |
| Commit | `f10db85ff911a5772ac84e0917ce50649d67292e` |
| Branch | `kyllene/v0.18.0-voice-default-and-cache` |

`master` in this fork follows upstream `master` and holds no fork commits.

## What the fork changes

Each change is one commit, so it can be moved to a new release, or dropped, on its own.

1. **Saved chats aren't dropped.** Stock keeps the 50 chats read last and drops older copies. The fork keeps all of
   them. Copies stay separate by gateway, profile and chat.
   - `shared/core/.../cache/OfflineCache.kt`: removes the call that trimmed saved chats, and its constant.
   - Tests: `cache/SavedChatsKeptTest.kt` saves through `ChatSession`; `jvmTest/.../SavedChatsKeptDatabaseTest.kt`
     uses the real Room database. The stock test that checked the limit is removed.
2. **Nothing received is dropped from a saved copy.** Stock replaces a chat's copy with its newest 200 rows, saves a
   row over 1 MB without its text, keeps the newest 100 list entries, and deletes copies on a 404, on signing out
   and on removing a gateway. The fork:
   - adds each read of a chat to its copy (a row read again replaces its saved version; the rest stay), and saves
     the earlier pages read while scrolling back;
   - saves a row too big for one database row whole, as ordered pieces;
   - keeps every list entry, with the chats the gateway stops listing after the ones it lists;
   - keeps the copy of a chat the gateway no longer has (the chat still shows as gone);
   - keeps copies for the account they were read for. Each sign-in checks the account the gateway verifies
     (`GET /api/auth/me`: `provider` and `user_id`) and records it, sealed, with the gateway's copies:
     - copies of another account, or of no known account (saved before this, or while the account couldn't be
       verified), are moved under a `detached:` key that no gateway address can be, before anything reads them;
     - copies detached for the account signing in come back, under what it read since;
     - signing out or removing a gateway detaches its copies too, so the same account gets them back when it signs
       in again;
     - if the copies can't be detached, the sign-in doesn't go on: the new session is signed out again and the
       sign-in screen says why;
     - which account each gateway's stored session was confirmed to be is kept on disk
       (`custom/SessionBindings.kt`). A new sign-in marks it pending before its credentials are stored, and only a
       finished bind records the account; renewing tokens leaves it alone, and signing out clears it. A password
       sign-in's cookies are the host's, whatever the port or path, so it marks every gateway on the same host (or
       under the same domain) pending too; a browser sign-in's tokens are its gateway's alone;
     - every start of a kept session, offline too, gives the saved chats to the recorded account before anything
       reads them. With none recorded (a sign-in that didn't finish, or a session from before this), they're all put
       away until the gateway says whose they are. Once online, a session the gateway says is another account's is
       rebound the same way; wherever another account's chats can't be put away, the session is signed out with
       the same notice;
   - sets aside a saved row, piece or list entry that no longer reads (under an `unreadable:` key, unchanged), and
     saves the readable rest; it never drops the readable rows with it;
   - shows a copy that couldn't be written with the chat's existing error banner, or the list's existing message.
     What was saved before stays.

   Deleting a chat or a bot still drops its copies, and so does deleting a chat the gateway no longer has (404).
   `OfflineCache.forgetPutAway` deletes detached and unreadable copies, per gateway or all; no screen calls it yet.
   - The merge, piece and key rules are in `shared/core/.../custom/RetainedCopies.kt`.
   - `OfflineCache.kt` calls them in `saveList`, `savedTranscript` and `saveTranscript`, and adds `detachGateway`,
     `bindOwner` and `forgetPutAway`. Its writes take one lock, so a save can't straddle a detach.
   - `OfflineDatabase.kt` adds queries (moving, listing and setting aside a gateway key's rows). The owner record
     is a saved "chat" under a profile no gateway profile can be named. There is no new table or column, so the
     schema version is unchanged. A version bump would wipe the cache, because it uses
     `fallbackToDestructiveMigration`.
   - `ChatSession.kt` no longer calls `forgetChat` on a 404, saves older pages, and reports a failed write.
     `SessionsViewModel.kt` reports a failed list write and treats a 404 on delete as deleted. `AppViewModel.kt`
     calls `detachGateway` where it called `forgetGateway`, and `bindOwner` in `onSignedIn` (and, once verified, for
     a kept session in `init` and `open`). `SignInNotice.SavedChatsNotPutAway` has a new string
     (`app_sign_in_saved_chats_not_put_away`, in every language).
   - Tests: `cache/RetainedCopiesTest.kt` (through `ChatSession`) and `jvmTest/.../RetainedCopiesDatabaseTest.kt`
     (the real Room database and AES sealer, across a reopen). These stock tests change to match: five in
     `OfflineCacheTest` that checked the trimming are removed; `ChatSessionTest`, `SessionsViewModelTest` and
     `AppViewModelTest` expect the copies kept or detached; `SessionsViewModelTest` adds the failed-write and 404
     cases; `AppViewModelTest` adds expiry, re-sign-in, unknown-owner and failed-detach cases.
3. **Voice from the watch goes to the default conversation.** A watch ask or live call with no conversation chosen
   goes to the existing session set as the Default Call Target (Settings → Voice), not to a new chat. These keep
   stock behaviour:
   - a conversation or bot chosen on the watch;
   - New chat picked on the watch, until that chat gets its id;
   - every Pebble ask without a chat id, including one where nothing was ever chosen. The default isn't used for
     Pebble.
   - a target of "New session" or a bot;
   - a request made in another profile.
   - The rule is in `shared/core/.../custom/DefaultVoiceConversation.kt`.
   - `WatchRelay.kt` calls it from the ask path and `chatForCall` (new `voiceDefault` parameter).
   - `WatchCalls.kt` passes `voiceDefault = true` and the call's `newChat` for calls started on the watch.
   - Protocol: `WatchAsk` and `CallStart` (`shared/wearlink/.../WearLink.kt`) have an optional `newChat` flag. It is
     left out of the JSON when false, so older watches and phones still read each other. `PebbleLink.toWatchAsk`
     sets it for every Pebble ask without a chat id.
   - Watch app: `WatchStore` saves New chat with the chat choice (`new_chat`). `WatchLink.newChat()` sets it,
     `ask` and `startCall` send it, and picking a chat or bot, or a new chat getting its id, ends it. The rule is in
     `wearApp/.../WatchNewChat.kt`.
   - Tests: `wear/DefaultVoiceConversationTest.kt` uses the real `WatchRelay` with the fake gateway;
     `wearlink/NewChatWireTest.kt` checks the wire format; `wearApp/.../WatchNewChatTest.kt` checks the watch rule.
     The stock `PebbleLinkTest` expects `newChat = true` on a blank-chat Pebble ask.

4. **The phone's voice uses earphones as a call does, and their button is one toggle.**
   - The chained voice chat (talk-over on or off) is routed to earphones with a microphone (Bluetooth SCO or LE, a
     wired or USB headset), on Android 12 and later: it listens through their microphone and plays in them, as a
     live call does. Earphones whose microphone can't be used are said in the voice panel's error line.
   - If the earphones (or any output other than the phone's own) the chat has been playing through go away
     mid-chat, on any route (a call-routed headset, music-only Bluetooth earbuds, wired headphones, Bluetooth on
     Android 8–11), what plays stops and the chat ends saying why. Nothing moves to the phone's speaker, not even
     talk-over's speaker routing. Android's `ACTION_AUDIO_BECOMING_NOISY` warning and the device callback both trigger
     it (`HeadsetAudio.Tracker`). From that moment the player is muted: a hold let go, a reply stream or clip
     started meanwhile, a clip that gets ready, or a stream's play-out makes no sound, and a waiting clip ends
     quietly. The mute is let go only once the silenced chats have closed and their playback has ended; a new chat
     then plays as usual. The voice controller ends the chat and closes its audio before looking up the reason.
     A live call routed to a headset with a microphone hangs up the same way.
   - A live call takes headphones first (`HeadsetAudio.liveOutput`, run by `LiveAudioRoute`): a headset it can be
     routed to as a call (as stock), else any other private output, such as music-only Bluetooth earbuds or a hearing
     aid, and only then the phone's speaker. Stock put everything but a call headset on the speaker. Android only
     routes calls to its communication devices, which music-only earbuds aren't, so the call isn't put in call mode
     there. Its voice plays with media usage, which Android sends where media goes, and the phone's microphone
     listens. The voice panel says so with the existing "earphones' microphone can't be used" line. If those earphones
     go (the device callback, or Android's `ACTION_AUDIO_BECOMING_NOISY` warning first), the call hangs up, silent,
     rather than moving to the speaker. Where the audio really comes out is untested on a device; the tests check the
     routing the app asks Android for.
   - Play, pause, play/pause, the headset hook and stop are one toggle: start the chat's voice (as `/voice` does),
     send what the chained chat heard, or end a live call or the chat. One press counts once; the next press is its
     own. With a chat screen resumed, a press starts voice rather than playing or pausing music, whichever way
     Android hands it over: the activity takes the keys it's given (`onKeyDown`/`onKeyUp`), and the voice media
     session is active for the chat, voice off too (`VoiceButton.holding`). The same press told both ways counts once.
     Holding the button opens no microphone. Paused with voice off, the session lets go, and voice never starts from
     a press out of sight. With voice on, the session stays active, out of sight too. Read aloud keeps the button
     while it reads (the voice session isn't active then), and other keys go on as before. Which way a Bluetooth
     headset's press actually arrives, and whether Android routes it to the session over a music app, is untested on
     a device.
   - Rules: `shared/core/.../custom/HeadsetAudio.kt` (route) and `shared/ui/.../voice/VoiceButton.kt` (toggle).
     Seams: `SpeechPlayer.chainedChatAudio(talkOver, onHeadsetLost)` and `headsetMicUnavailable()` (`Speech.kt`),
     their Android player (`AndroidVoice.kt`), `HeadsetPorts.kt` (what's connected), `LiveAudioRoute.kt` (the live
     call's route, run by `AndroidLiveCall.kt`), the live call's microphone line in `VoiceController.startLive`, `VoiceController.converseChained`
     and `headsetLost`, the `ChatScreen` binding, `shared/ui/src/androidMain/.../voice/VoiceMediaButtons.kt` and two
     `HeraldActivity` overrides. Two strings, in every language.
   - Tests: `core/custom/HeadsetAudioTest.kt` (the routes and the `Tracker` transitions the Android player runs),
     `ui/voice/PhoneVoiceControlsTest.kt` (the real `VoiceController`, chained and live, including one driven by the
     `Tracker`), and `androidHostTest/.../ui/voice/AndroidHeadsetAudioTest.kt` (the real `AndroidSpeechPlayer` on
     Robolectric's audio manager, `BECOMING_NOISY` included), `androidHostTest/.../ui/voice/AndroidSilencedPlaybackTest.kt`
     (its real `AudioTrack` and `MediaPlayer` paths after the output goes), and
     `androidHostTest/.../ui/voice/AndroidLiveRouteTest.kt` (`LiveAudioRoute` on Robolectric's audio manager: earbuds,
     a call headset, the speaker, Android 11, `BECOMING_NOISY`). The stock `AndroidChainedAudioTest` case for unplugged
     headphones now expects the chat silenced rather than speaker call routing.

5. **Talk, a Phone Live switch, and the earphones' button out of sight.**
   - Settings › Voice has two new switches under Default Call target, both off by default. Turning either on or off
     starts nothing: no microphone, no call.
   - **Phone Live off: Talk.** The Voice button, the earphones' button and Call Hermes record one request. No pause,
     silence or time limit ends it. Send (offered from the start, since a quiet voice may never trip the speech
     detector) or an earphone press ends it and sends it once, heard or not, and its reply is read as the chained
     chat reads one. If it held nothing, or the transcript is empty, nothing is sent: the panel says it caught
     nothing and is Ready again. Only End drops a recording unsent. Then the
     voice panel waits at Ready, its microphone shut and no audio routed, until its Mic button or an earphone press
     records the next request. End at Ready closes it. Nothing listens over the reply, and there's no Mute, since
     nothing listens unless asked. End while Hermes works or speaks stops the reply aloud (Hermes's turn isn't
     stopped) and goes back to Ready. "Stop" said, or a microphone that fails, ends the chat as before. Earphones
     with a microphone take each request, and losing them ends the chat, as in 4.
   - **Phone Live on.** The same buttons start a live call. If the gateway's profile doesn't talk live, the panel
     says so rather than starting the chained chat. Calls the agent places keep their own voice, and so does the
     watch.
   - **Background earphone button.** On, Talk stays at Ready when Herald leaves the screen, with its call
     notification and media session, so an earphone press still records the next request out of sight. Off, Talk at
     Ready ends once Herald is out of sight (a request under way finishes first), and the session lets the button
     go to music. Limits, from Android's rules
     ([media buttons](https://developer.android.com/media/legacy/media-buttons),
     [background starts](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)):
     - Out of sight, Android sends a press to the last app with a media session that played audio. Herald gets it
       after it has just read a reply, but music that has played since takes it.
     - Nothing starts Talk out of sight when voice is off. An app in the background can't start the microphone
       service (apps targeting Android 12 and later), and one started from the background gets no microphone access
       (Android 14 refuses to start it). Talk has to be started on screen first; the call notification shows it's
       waiting.
     - None of this is tried on a device yet.
   - The watch's Talk live starts off. The phone's switch is separate.
   - Code: `ui/voice/ManualTalk.kt` (the modes), `VoiceController` (`converseManual`, `awaitTalk`, `talk`, `end`,
     `onForeground`, the Live-or-reason start, and `VoicePhase.Ready`), `VoiceButton` (Ready records), the voice
     panel's and screen's left slot and hints, `ChatViewModel.startVoiceChat`, two `AppSettings` fields and their
     `SettingsScreen` rows, `WatchStore`'s default. Nine strings, in every language.
   - Tests: `ui/voice/ManualTalkTest.kt` (the real `VoiceController` on a real chat over a gateway socket, and the
     button), `androidHostTest/.../ui/voice/AndroidTalkButtonTest.kt` (the real `VoiceMediaSession` on Robolectric and
     the activity's key path, driving the controller), `AndroidVoiceRecorderTest`'s quiet Talk recording, and
     `WatchNewChatTest`'s default.

6. **Live call transcripts are kept on the phone, and the session's next call opens with them.**
   - Every piece of transcript a live call sends (what you said, what the voice said) is kept on the phone, for the
     phone's own calls (a chat's, or one the agent places) and a watch's call, which the phone runs. They're kept by
     the gateway's verified account, the gateway and the chat's stored session, in their own database
     (`live-transcripts.db`), apart from the offline cache: deleting saved chats or messages, signing out or removing
     a gateway doesn't touch them, and nothing trims or expires them. Each piece is sealed with the cache's key.
   - A new chat gets its session with its first request; a call's pieces until then go to that session once it does.
     A gateway whose account isn't verified has its calls kept, but no call is given them.
   - The next live call in the same account's same session, on the phone or the watch, after a restart too, opens
     with them: the gateway's `voice-live/session` takes the chat's history, and the earlier calls go in it where they
     happened among the chat's messages (`withEarlierCalls`), each once; that history keeps the newest that fit, as
     before. Another session's or another account's calls never go in. Nothing is made up from summaries or Talk.
   - The same timed piece told twice is kept once. A piece that can't be sealed or written waits in memory, never in
     the clear, and is tried again with every later piece, an empty one, and the call's end; the voice panel (or the
     watch) says so once. Whatever still can't be saved when the call ends is said then, a hang-up too ("The end of
     this call's transcript couldn't be saved on the phone."): it's gone with the call, and nothing survives the app
     being killed before it's written. A transcript event that can't be read is kept as it came, never used as
     context. Pieces that can't be read later (a lost key) stay on disk, and the panel says so.
   - Code: `core/custom/LiveTranscripts.kt` (the table, its own Room database, the store, `withEarlierCalls`), its
     builders (`androidMain`/`jvmMain`), `PlatformModule.android.kt`, `LiveConversation` (`recording`, `earlier`),
     `VoiceController.startLive`, `WatchCalls.run`, and `ChatViewModel`/`PhoneCallModel`/`WearModule`/`UiModule`
     passing the store. Three strings, in every language.
   - Tests: `core/custom/LiveTranscriptsDatabaseTest.kt` (the real Room database and sealer: after closing, apart by
     account, gateway and session, a call's session found later, saved-chat deletion, duplicates, writes and seals that
     fail then work, unreadable pieces), `core/custom/LiveTranscriptsFinalFlushTest.kt` (the last pieces sealed by the
     final or an empty flush, in order; a sealer that never recovers: nothing in the clear, no loop),
     `ui/voice/LiveTranscriptsEndTest.kt` (a phone call's end writes them, or says what couldn't be),
     `core/custom/LiveContinuityTest.kt`, and `ui/voice/LiveTranscriptsCallTest.kt`
     (the real controller, and `WatchCalls` over the real `WatchRelay`: fixture events kept and given to the next
     call's history only in the same account and session).
   - Not covered: a transcript browser, and anything the gateway itself keeps.

The fork adds two switches to Settings › Voice and the Ready state to the voice panel; it doesn't otherwise change
the settings screens or layout. The watch app changes only as described above.

## Moving to a newer upstream release

```bash
git fetch upstream --tags
git switch -c kyllene/<new-tag>-voice-default-and-cache <new-tag>
git cherry-pick <fork commits, oldest first>   # git log --oneline <old-tag>..kyllene/<old-tag>-...
```

For each conflict or failing test:

- **`OfflineCache`.** If upstream changed how it saves or trims copies, keep their change and drop only the parts
  that remove received rows, list entries or chats because of their count, age or size. Saves must still go through
  `RetainedCopies.mergeRows`/`mergeList`, writes through `split`, and reads through `join`.
- **Deletions.** Wherever upstream deletes saved copies, keep only what the person asked for: deleting a chat (also
  one the gateway answers 404 for) or a bot. A 404 on reading must not delete anything. Signing out and removing a
  gateway must call `detachGateway`.
- **Sign-in.** Every path that finishes a sign-in must call `bindOwner` before anything reads the cache, and must not
  go on when it returns false. Every path that starts a kept session (`AppViewModel` `init` and `open`) must call
  `keptSessionCache` before routing to a chat. Any new way of storing a new account's credentials must go through
  `AuthApi.newCredentials`, so the binding is marked pending first, with `cookies = true` when the credentials are
  cookies (they reach every gateway on the host). If upstream changes `/api/auth/me` or how accounts are named, keep the owner as
  provider and user id.
- **The database.** If upstream bumps the cache database version, add a real migration or the copies are wiped. Keep
  the fork's DAO queries.
- `SavedChatsKeptTest`, `SavedChatsKeptDatabaseTest`, `RetainedCopiesTest` and `RetainedCopiesDatabaseTest` must
  pass.
- **`WatchRelay.answer` and `WatchRelay.chatForCall`.** Find where a watch request with no chat id becomes a new chat
  (`storedId = ... chatId ...`). Put `?: defaultVoiceChat(...)` / `?: fallback` there again. If the Default Call
  Target code moved, update only `defaultVoiceChat`, not the rule in `custom/`.
- **`WatchCalls.kt`.** Pass `voiceDefault = true, newChat = start.newChat` again where the watch's call start calls
  `chatForCall`.
- **Watch New chat.** Keep the `newChat` field last in `WatchAsk`, `CallStart` and `WatchChatChoice`, with default
  false. Wherever upstream changes `WatchLink.newChat()`, `ask`, `startCall` or `WatchStore.setChat`/`adoptChat`,
  New chat must still be sent and must end once a chat or bot is chosen or adopted. If upstream adds its own way to
  tell New chat from no choice, use theirs and drop the fork's flag.

- **Phone voice.** If upstream changes `converseChained`, keep its one `chainedChatAudio(talkOver, …)` call for every
  chained chat. If it changes Android routing in `AndroidSpeechPlayer`, keep the earphone route and the "lost
  earphones stop, never the speaker" rule for every route, the `ACTION_AUDIO_BECOMING_NOISY` receiver, and the player's
  mute on every start and resume path (`hold`, `playPcm`, `play` and its prepared callback). If it adds its own media-button handling, route these keys to
  `VoiceButton` from there instead of the fork's session. If it changes `AndroidLiveCall`'s routing, keep
  `route.route(::outputLost)` before the audio device module is built, `route.playout()` on its builder, and
  `route.unroute()` on close; if it builds the module elsewhere, carry the playout attributes there.
- **Live transcripts.** Wherever upstream builds `LiveConversation`, pass `recording` (begun for the chat's gateway and
  stored session) and `earlier`. Keep `LiveTranscriptDatabase` its own file; if its table changes, add a real
  migration (it has no destructive fallback). If upstream keeps live transcripts itself, compare before dropping these.
- **Talk.** If upstream changes `startChat`, `hear` or `converseOnce`, keep the `mode` branch before the live check,
  `awaitTalk()` at the top of `hear`'s loop, and `MANUAL_TALK` for Talk's recordings. If it adds a `VoicePhase`, give
  it a place in `voicePress` and the panel's label. If upstream adds its own Live switch, use theirs and drop
  `phoneLive`.

Then update the table above and run:

```bash
./gradlew --continue :shared:core:jvmTest :shared:core:testAndroidHostTest :shared:wearlink:testAndroidHostTest :shared:ui:testAndroidHostTest :wearApp:testDebugUnitTest :wearApp:assembleDebug :androidApp:assembleDebug
```

Each new release can conflict in new places. These steps don't promise a clean pick.

## License

Kyllene's license applies to this fork unchanged. See [LICENSE](../../LICENSE).
