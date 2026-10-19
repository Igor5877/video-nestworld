package com.nestworld.video.client.acoustics;

/**
 * What the room does to the sound of one screen, as the audio thread applies it. Immutable.
 *
 * @param directGainL/directHfL  low-pass of the left speaker's direct sound (gain and high-frequency gain, 0..1)
 * @param sendGain               how much of the sound goes into the room reverb (0..1)
 * @param sendHf                 brightness of that reverb (0..1)
 * @param decayTime              reverb decay in seconds
 */
public record AcousticResult(float directGainL, float directHfL, float directGainR, float directHfR,
                             float sendGain, float sendHf, float decayTime) {
    /** Open air: nothing in the way and no reverb. */
    public static final AcousticResult OPEN = new AcousticResult(1f, 1f, 1f, 1f, 0f, 1f, 1.0f);

    public boolean isValid() {
        return ok(directGainL) && ok(directHfL) && ok(directGainR) && ok(directHfR) && ok(sendGain) && ok(sendHf)
                && Float.isFinite(decayTime) && decayTime > 0f;
    }
    private static boolean ok(float v) { return Float.isFinite(v) && v >= 0f && v <= 1f; }
}
