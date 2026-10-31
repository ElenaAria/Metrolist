package ultra.core.resilience

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BackoffTest {
    private fun policy(jitter: Jitter) = BackoffPolicy(initialDelay = 500.milliseconds, maxDelay = 30.seconds, multiplier = 2.0, jitter = jitter)

    @Test
    fun `without jitter the delay doubles and then stops at the cap`() {
        val policy = policy(Jitter.None)

        val delays = (1..8).map { policy.delayFor(it) }

        assertEquals(
            listOf(500.milliseconds, 1.seconds, 2.seconds, 4.seconds, 8.seconds, 16.seconds, 30.seconds, 30.seconds),
            delays,
        )
    }

    @Test
    fun `full jitter stays between zero and the exponential delay and actually varies`() {
        val policy = policy(Jitter.Full)
        val random = Random(1)

        val samples = List(1000) { policy.delayFor(4, random) } // exponential delay is 4s

        assertTrue(samples.all { it >= Duration.ZERO && it <= 4.seconds })
        assertTrue(samples.toSet().size > 900, "jitter should produce mostly distinct values")
    }

    @Test
    fun `equal jitter keeps at least half of the delay`() {
        val policy = policy(Jitter.Equal)
        val random = Random(2)

        val samples = List(1000) { policy.delayFor(4, random) }

        assertTrue(samples.all { it >= 2.seconds && it <= 4.seconds })
    }

    @Test
    fun `the same seed gives the same sequence`() {
        val policy = policy(Jitter.Full)

        val first = (1..10).map { policy.delayFor(it, Random(7)) }
        val second = (1..10).map { policy.delayFor(it, Random(7)) }

        assertEquals(first, second)
        assertNotEquals(first, (1..10).map { policy.delayFor(it, Random(8)) })
    }

    @Test
    fun `huge retry counts neither overflow nor exceed the cap`() {
        assertEquals(30.seconds, policy(Jitter.None).delayFor(100_000))
        assertTrue(policy(Jitter.Full).delayFor(Int.MAX_VALUE, Random(3)) <= 30.seconds)
    }

    @Test
    fun `nonsensical parameters are rejected`() {
        assertFailsWith<IllegalArgumentException> { BackoffPolicy(initialDelay = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { BackoffPolicy(initialDelay = 2.seconds, maxDelay = 1.seconds) }
        assertFailsWith<IllegalArgumentException> { BackoffPolicy(multiplier = 0.5) }
        assertFailsWith<IllegalArgumentException> { BackoffPolicy().delayFor(0) }
    }
}
