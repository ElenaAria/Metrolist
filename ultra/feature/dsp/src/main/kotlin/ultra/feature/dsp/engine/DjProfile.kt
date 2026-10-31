package ultra.feature.dsp.engine

/** The presets a transition can be shaped by. Chosen automatically from the output device, or pinned by the user. */
enum class ProfileId { SPEAKER, WIRED, BLUETOOTH, CAR, STUDIO }

/**
 * Everything a deck needs to shape one transition. Immutable; the engine hands the current instance to
 * the audio thread on every block, so a profile switch takes effect within one block (a few ms) and
 * never mid-chunk.
 *
 * @property swapHz crossover of the "bass swap": the outgoing deck's high-pass rises to it while the
 *   incoming deck's falls from it, so the two tracks never stack their low end.
 * @property echoWet echo-out level of the outgoing deck, 0 disables the echo.
 * @property ceilingDb where the soft ceiling starts to bend peaks (negative dBFS). Active only while a
 *   transition is shaping the audio; outside one nothing is touched.
 */
data class DjProfile(
    val id: ProfileId,
    val bassSwap: Boolean,
    val swapHz: Float,
    val echoWet: Float,
    val echoDelayMs: Int,
    val echoFeedback: Float,
    val echoDampingHz: Float,
    val ceilingDb: Float,
    val dither: Boolean,
) {
    init {
        require(swapHz in 40f..2000f) { "swapHz out of range: $swapHz" }
        require(echoWet in 0f..1f) { "echoWet out of range: $echoWet" }
        require(echoDelayMs in 50..MAX_ECHO_DELAY_MS) { "echoDelayMs out of range: $echoDelayMs" }
        // Strictly below 1 or the repeats never die out.
        require(echoFeedback in 0f..0.7f) { "echoFeedback out of range: $echoFeedback" }
        require(echoDampingHz in 500f..16000f) { "echoDampingHz out of range: $echoDampingHz" }
        require(ceilingDb in -12f..0f) { "ceilingDb out of range: $ceilingDb" }
    }

    companion object {
        const val MAX_ECHO_DELAY_MS = 1000
    }
}

/**
 * The built-in presets.
 *
 * These are sensible starting points, not tuned values: they encode reasoning (a phone speaker cannot
 * reproduce a bass swap, Bluetooth codecs overshoot so they get more headroom, a car's road noise
 * makes long echoes muddy), but nobody has auditioned them. Treat them as defaults to adjust by ear.
 */
object DjProfiles {
    private val speaker =
        DjProfile(ProfileId.SPEAKER, bassSwap = true, swapHz = 180f, echoWet = 0.30f, echoDelayMs = 375, echoFeedback = 0.40f, echoDampingHz = 3500f, ceilingDb = -1.0f, dither = true)

    private val wired =
        DjProfile(ProfileId.WIRED, bassSwap = true, swapHz = 220f, echoWet = 0.30f, echoDelayMs = 375, echoFeedback = 0.45f, echoDampingHz = 4000f, ceilingDb = -1.0f, dither = true)

    // Lossy BT codecs (SBC/AAC) overshoot full scale; extra headroom is standard practice.
    private val bluetooth =
        DjProfile(ProfileId.BLUETOOTH, bassSwap = true, swapHz = 200f, echoWet = 0.25f, echoDelayMs = 375, echoFeedback = 0.40f, echoDampingHz = 3500f, ceilingDb = -1.5f, dither = true)

    // Road noise masks a long echo into mud and car systems tend to be bass-heavy: shorter, drier, higher swap.
    private val car =
        DjProfile(ProfileId.CAR, bassSwap = true, swapHz = 260f, echoWet = 0.12f, echoDelayMs = 300, echoFeedback = 0.35f, echoDampingHz = 3000f, ceilingDb = -1.5f, dither = true)

    // Wired/USB DACs: filter sweep only (no echo colouring), the least intrusive ceiling.
    private val studio =
        DjProfile(ProfileId.STUDIO, bassSwap = true, swapHz = 220f, echoWet = 0.0f, echoDelayMs = 375, echoFeedback = 0.40f, echoDampingHz = 4000f, ceilingDb = -0.5f, dither = true)

    fun forId(id: ProfileId): DjProfile =
        when (id) {
            ProfileId.SPEAKER -> speaker
            ProfileId.WIRED -> wired
            ProfileId.BLUETOOTH -> bluetooth
            ProfileId.CAR -> car
            ProfileId.STUDIO -> studio
        }

    val all: List<DjProfile> = ProfileId.entries.map(::forId)
}
