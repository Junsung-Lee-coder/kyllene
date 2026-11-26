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

    /** What the player does next for the chained chats open, as [Tracker] decides it. */
    sealed interface Step {
        /** Route the chats this way. */
        data class Use(val route: Route) : Step

        /**
         * What the chats were playing through went away: stop what plays now, let the routing go and end the chats.
         * Nothing is routed to the phone's speaker for them.
         */
        data object Silence : Step
    }

    /**
     * The chained chats' audio as their outputs come and go, on every route. Android moves a stream to the phone's
     * speaker when headphones are unplugged or a Bluetooth sink disconnects
     * (https://developer.android.com/media/platform/output#becoming-noisy), so a chat that has been playing through
     * earphones (or any output other than the phone's own) is silenced the moment that output is gone, before any
     * route is chosen, whether it was routed to them as a call or played on its usual route. Android's warning that
     * this is about to happen ([becomingNoisy]) silences it too. Once silenced, the chats are only let go: nothing is
     * routed for them again until they've closed.
     *
     * The player tells it every change and every route it holds; it holds no Android types, so its rules are tested
     * as the player runs them.
     */
    class Tracker {
        private var held = Route.Usual
        /** The open chats have played through an output other than the phone's own since it was last silenced. */
        private var privateInUse = false
        private var silenced = false

        /** The route the player holds now, after applying a [Step.Use] (which can fail back to [Route.Usual]). */
        fun held(route: Route) {
            held = route
        }

        /** The outputs changed, or a chat opened or closed: [open] chats, any of them with [talkOver], on [ports]. */
        fun change(open: Boolean, talkOver: Boolean, ports: Ports): Step {
            if (!open) {
                privateInUse = false
                silenced = false
                return Step.Use(Route.Usual)
            }
            if (silenced) return Step.Use(Route.Usual)
            if (headsetLost(held, ports) || privateInUse && !ports.privateOutput) return silence()
            if (ports.privateOutput) privateInUse = true
            return Step.Use(route(ports, talkOver))
        }

        /** Android says audio is about to move to the phone's speaker (`ACTION_AUDIO_BECOMING_NOISY`). */
        fun becomingNoisy(open: Boolean): Step? = if (open && !silenced) silence() else null

        private fun silence(): Step {
            silenced = true
            privateInUse = false
            return Step.Silence
        }
    }
}
