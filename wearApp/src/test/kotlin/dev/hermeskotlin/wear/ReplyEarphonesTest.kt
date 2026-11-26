package dev.hermeskotlin.wear

import dev.hermeskotlin.wearlink.ReplyPhase
import dev.hermeskotlin.wearlink.ReplySpeech
import dev.hermeskotlin.wearlink.ReplyUpdate
import dev.hermeskotlin.wearlink.WearJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fork (requirements §6): a reply's voice that came to the watch for its earphones ([ReplyUpdate.earphonesOnly]) may
 * play only through them ([replyNeedsEarphones], which `WatchLink` checks before every clip and the watch's own voice
 * against the earphones connected then); before the reply's update, the watch's own state when it asked stands in.
 */
class ReplyEarphonesTest {
    private fun turn(askId: String, phase: ReplyPhase, earphonesOnly: Boolean = false) =
        WatchTurn(askId, null, ReplyUpdate(askId, phase, speech = ReplySpeech.Coming, earphonesOnly = earphonesOnly))

    @Test
    fun aReplySentForTheWatchsEarphonesNeedsThem() {
        assertTrue(replyNeedsEarphones(turn("a", ReplyPhase.Done, earphonesOnly = true), "a", askedWithEarphones = null))
        // Not for its earphones (none anywhere, both, an older phone): as before.
        assertFalse(replyNeedsEarphones(turn("a", ReplyPhase.Done), "a", askedWithEarphones = "a"))
    }

    @Test
    fun beforeTheRepliesUpdateTheAsksOwnSnapshotStandsIn() {
        // A clip ahead of its Done update (clips come on a channel, updates as messages): asked with earphones on,
        // it needs them; asked without, it doesn't.
        assertTrue(replyNeedsEarphones(turn("a", ReplyPhase.Working), "a", askedWithEarphones = "a"))
        assertFalse(replyNeedsEarphones(turn("a", ReplyPhase.Working), "a", askedWithEarphones = null))
    }

    @Test
    fun anotherAsksReplyNeedsNothingHere() {
        assertFalse(replyNeedsEarphones(turn("b", ReplyPhase.Done, earphonesOnly = true), "a", askedWithEarphones = "a"))
        assertFalse(replyNeedsEarphones(null, "a", askedWithEarphones = "a"))
    }

    @Test
    fun theFieldIsLeftOutUnlessSetSoEitherSideMayBeOlder() {
        val plain = ReplyUpdate("a", ReplyPhase.Done)
        assertFalse("earphonesOnly" in WearJson.encode(plain).decodeToString())
        // An older phone's update, without it, reads as not needing earphones.
        assertEquals(false, WearJson.decode<ReplyUpdate>("""{"askId":"a","phase":"Done"}""".encodeToByteArray())?.earphonesOnly)
        assertTrue(WearJson.decode<ReplyUpdate>(WearJson.encode(plain.copy(earphonesOnly = true)))!!.earphonesOnly)
    }
}
