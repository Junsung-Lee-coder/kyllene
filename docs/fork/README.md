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
   them. A copy still goes when you delete the chat or its bot, or sign out. Copies stay separate by gateway, profile
   and chat.
   - `shared/core/.../cache/OfflineCache.kt`: removes the call that trimmed saved chats, and its constant.
   - Tests: `cache/SavedChatsKeptTest.kt` saves through `ChatSession`; `jvmTest/.../SavedChatsKeptDatabaseTest.kt`
     uses the real Room database. The stock test that checked the limit is removed.
2. **Voice from the watch goes to the default conversation.** A watch ask or live call with no conversation chosen
   goes to the existing session set as the Default Call Target (Settings → Voice), not to a new chat. These keep
   stock behaviour:
   - a conversation or bot chosen on the watch;
   - a target of "New session" or a bot;
   - a request made in another profile.
   - The rule is in `shared/core/.../custom/DefaultVoiceConversation.kt`.
   - `WatchRelay.kt` calls it from the ask path and `chatForCall` (new `voiceDefault` parameter).
   - `WatchCalls.kt` passes `voiceDefault = true` for calls started on the watch.
   - Test: `wear/DefaultVoiceConversationTest.kt` uses the real `WatchRelay` with the fake gateway.

The fork doesn't change the settings screens, layout or the watch app.

## Moving to a newer upstream release

```bash
git fetch upstream --tags
git switch -c kyllene/<new-tag>-voice-default-and-cache <new-tag>
git cherry-pick <fork commits, oldest first>   # git log --oneline <old-tag>..kyllene/<old-tag>-...
```

For each conflict or failing test:

- **`OfflineCache.saveTranscript`.** If upstream changed how it trims saved chats, keep their change and drop only
  the part that removes chats because of their count or age. `SavedChatsKeptTest` and
  `SavedChatsKeptDatabaseTest` must pass.
- **`WatchRelay.answer` and `WatchRelay.chatForCall`.** Find where a watch request with no chat id becomes a new chat
  (`storedId = ... chatId ...`). Put `?: defaultVoiceChat(...)` / `?: fallback` there again. If the Default Call
  Target code moved, update only `defaultVoiceChat`, not the rule in `custom/`.
- **`WatchCalls.kt`.** Pass `voiceDefault = true` again where the watch's call start calls `chatForCall`.

Then update the table above and run:

```bash
./gradlew --continue :shared:core:jvmTest :shared:core:testAndroidHostTest :shared:ui:testAndroidHostTest :androidApp:assembleDebug
```

Each new release can conflict in new places. These steps don't promise a clean pick.

## License

Kyllene's license applies to this fork unchanged. See [LICENSE](../../LICENSE).
