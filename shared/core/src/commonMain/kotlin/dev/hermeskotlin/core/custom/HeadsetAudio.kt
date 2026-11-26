package dev.hermeskotlin.core.custom

/**
 * Fork customization: the phone's chained voice chat (its Talk) uses earphones' microphone as a live call does, and a
 * pair that goes away mid-chat silences it rather than moving its sound to the phone's speaker.
 *
 * The platform player says what's connected; these rules say which route the chat takes.
 */
object HeadsetAudio {
    /** What a chained chat's audio is connected to. */
    data class Ports(
        /** Earphones with a microphone the phone can talk through: Bluetooth (SCO or LE), a wired or USB headset. */
        val headsetWithMic: Boolean,
        /** Something other than the phone itself plays: headphones, earbuds, a car, another speaker. */
        val privateOutput: Boolean,
        /** Of those, earphones whose microphone can't be talked through here (music-only Bluetooth, plain headphones). */
        val earphonesWithoutUsableMic: Boolean,
    )

    enum class Route {
        /** Call routing to the earphones: their microphone in, their speaker out. */
        Headset,
        /** Call routing on the phone's own speaker, for talk-over's echo cancelling (stock). */
        Speaker,
        /** The usual route, unchanged (stock). */
        Usual,
    }

    /**
     * The route for the chained chats open now. Earphones with a usable microphone take the chat whether or not
     * talk-over is on. Without them, the stock rules hold: other private outputs keep their usual route, and the
     * phone's speaker takes call routing only for talk-over.
     */
    fun route(ports: Ports, talkOver: Boolean): Route = when {
        ports.headsetWithMic -> Route.Headset
        ports.privateOutput -> Route.Usual
        talkOver -> Route.Speaker
        else -> Route.Usual
    }

    /** Earphones are in, but the chat has to listen through the phone's own microphone: said, so it isn't a surprise. */
    fun micUnavailable(ports: Ports): Boolean = !ports.headsetWithMic && ports.earphonesWithoutUsableMic

    /**
     * The earphones the chat was routed to went away. Its sound must stop there, not go on from the phone's speaker
     * (requirements: a lost headset stops speech).
     */
    fun headsetLost(was: Route, now: Ports): Boolean = was == Route.Headset && !now.headsetWithMic
}
