package com.nestworld.video;

/**
 * The clock of the shared video timeline. The server measures a playing screen in real milliseconds (not game ticks,
 * which slow down when the server lags, and which the client's copy of keeps correcting). A client estimates how far
 * the server's clock is from its own, NTP-style: the screen heartbeat carries the client's send time, the server
 * answers with its own time, and the answer with the shortest round trip gives the best estimate.
 */
public final class SyncClock {
    /** A round trip this much shorter than the best one so far replaces it; older samples count as 1 ms worse per second. */
    private static final long AGEING_MS_PER_S = 1;

    private static volatile long offsetMs;          // server clock - client clock
    private static long bestRttMs = Long.MAX_VALUE, bestAtMs;
    private static volatile boolean roundTripKnown;

    private SyncClock() {}

    /** Monotonic milliseconds of this JVM (server or client). */
    public static long nowMs() { return System.nanoTime() / 1_000_000L; }

    /** Client: the server's clock right now, as well as it is known. */
    public static long serverNowMs() { return nowMs() + offsetMs; }

    /** Client: the server sent {@code serverMs}; without a measured round trip yet, assume it arrived instantly. */
    public static synchronized void oneWay(long serverMs) {
        if (!roundTripKnown) offsetMs = serverMs - nowMs();
    }

    /** Client: the client sent at {@code clientSentMs} (its clock) and the server answered at {@code serverMs} (its clock). */
    public static synchronized void roundTrip(long clientSentMs, long serverMs) {
        long now = nowMs(), rtt = now - clientSentMs;
        if (rtt < 0 || rtt > 10_000) return;
        long aged = bestRttMs == Long.MAX_VALUE ? Long.MAX_VALUE : bestRttMs + (now - bestAtMs) / 1000 * AGEING_MS_PER_S;
        if (roundTripKnown && rtt > aged) return;
        offsetMs = serverMs - (clientSentMs + rtt / 2);
        bestRttMs = rtt; bestAtMs = now; roundTripKnown = true;
    }

    /** Client: a new connection, the server's clock is unknown again. */
    public static synchronized void reset() {
        roundTripKnown = false; bestRttMs = Long.MAX_VALUE; offsetMs = 0;
    }
}
