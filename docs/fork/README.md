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
   - If those earphones go away mid-chat, what plays stops at once and the chat ends saying why; nothing moves to
     the phone's speaker. A live call routed to earphones hangs up the same way.
   - Play, pause, play/pause, the headset hook and stop are one toggle: start the chat's voice (as `/voice` does),
     send what the chained chat heard, or end a live call or the chat. One press counts once; the next press is its
     own. In front, the activity takes the key (`onKeyDown`/`onKeyUp`); with voice on, a media session takes it out
     of sight too. Read aloud keeps the button while it reads, and other keys go on as before.
   - Rules: `shared/core/.../custom/HeadsetAudio.kt` (route) and `shared/ui/.../voice/VoiceButton.kt` (toggle).
     Seams: `SpeechPlayer.chainedChatAudio(talkOver, onHeadsetLost)` and `headsetMicUnavailable()` (`Speech.kt`),
     their Android player (`AndroidVoice.kt`), `AndroidLiveCall.kt`'s headset watch, `VoiceController.converseChained`
     and `headsetLost`, the `ChatScreen` binding, `androidApp/.../voice/VoiceMediaButtons.kt` and two
     `HeraldActivity` overrides. Two strings, in every language.
   - Tests: `core/custom/HeadsetAudioTest.kt`, `ui/voice/PhoneVoiceControlsTest.kt` (the real `VoiceController`,
     chained and live).

The fork doesn't change the settings screens or layout. The watch app changes only as described above.

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
  earphones stop, never the speaker" rule. If it adds its own media-button handling, route these keys to
  `VoiceButton` from there instead of the fork's session.

Then update the table above and run:

```bash
./gradlew --continue :shared:core:jvmTest :shared:core:testAndroidHostTest :shared:wearlink:testAndroidHostTest :shared:ui:testAndroidHostTest :wearApp:testDebugUnitTest :wearApp:assembleDebug :androidApp:assembleDebug
```

Each new release can conflict in new places. These steps don't promise a clean pick.

## License

Kyllene's license applies to this fork unchanged. See [LICENSE](../../LICENSE).
