package com.nestworld.video.client.util;

import com.mojang.logging.LogUtils;
import com.nestworld.video.client.acoustics.AcousticResult;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;
import org.slf4j.Logger;

/**
 * OpenAL EFX for the screen sources: a low-pass on each source's direct sound (walls muffling it) and one reverb effect
 * shared through an auxiliary send. Lives on the audio thread. {@link #create} returns null when the device has no
 * EFX; any later OpenAL error switches the effects off again and the sound simply plays on without them.
 */
final class AlEfx {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Parameters follow the target with this time constant, so a door opening or a step does not click. */
    private static final float SMOOTH_SECONDS = 0.18f;

    private final int[] sources;
    private final int[] directFilters, sendFilters;
    private final int slot, effect;
    private final boolean eax;
    // Current (smoothed) values.
    private final float[] gain, hf;
    private float send = 0f, sendHf = 1f, decay = 1f;
    private float appliedDecay = -1f;
    private boolean broken;

    private AlEfx(int[] sources, int[] directFilters, int[] sendFilters, int slot, int effect, boolean eax) {
        this.sources = sources; this.directFilters = directFilters; this.sendFilters = sendFilters;
        this.slot = slot; this.effect = effect; this.eax = eax;
        this.gain = new float[sources.length]; this.hf = new float[sources.length];
        java.util.Arrays.fill(gain, 1f); java.util.Arrays.fill(hf, 1f);
    }

    /** Returns null (and says why) when EFX cannot be used. */
    static AlEfx create(long context, int[] sources) {
        try {
            long device = ALC10.alcGetContextsDevice(context);
            if (device == 0L || !ALC10.alcIsExtensionPresent(device, "ALC_EXT_EFX")) {
                LOGGER.info("Nestworld Video: OpenAL EFX is not available, room acoustics are off");
                return null;
            }
            int sends = ALC10.alcGetInteger(device, EXTEfx.ALC_MAX_AUXILIARY_SENDS);
            if (sends < 1) { LOGGER.info("Nestworld Video: the OpenAL context has no auxiliary sends, room acoustics are off"); return null; }
            AL10.alGetError();
            int slot = EXTEfx.alGenAuxiliaryEffectSlots();
            int effect = EXTEfx.alGenEffects();
            boolean eax = true;
            EXTEfx.alEffecti(effect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_EAXREVERB);
            if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                eax = false;
                EXTEfx.alEffecti(effect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_REVERB);
            }
            int[] direct = new int[sources.length], sendFilter = new int[sources.length];
            for (int i = 0; i < sources.length; i++) {
                direct[i] = EXTEfx.alGenFilters(); sendFilter[i] = EXTEfx.alGenFilters();
                EXTEfx.alFilteri(direct[i], EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
                EXTEfx.alFilteri(sendFilter[i], EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
            }
            int error = AL10.alGetError();
            if (error != AL10.AL_NO_ERROR) throw new IllegalStateException("OpenAL error 0x" + Integer.toHexString(error) + " while creating EFX objects");
            AlEfx efx = new AlEfx(sources, direct, sendFilter, slot, effect, eax);
            efx.applyReverb(1.0f, true);
            efx.applyAll();
            error = AL10.alGetError();
            if (error != AL10.AL_NO_ERROR) { efx.close(); throw new IllegalStateException("OpenAL error 0x" + Integer.toHexString(error) + " while setting up the effects"); }
            LOGGER.info("Nestworld Video: room acoustics ready (OpenAL EFX, {} auxiliary sends, {} reverb)", sends, eax ? "EAX" : "standard");
            return efx;
        } catch (Throwable t) {
            LOGGER.info("Nestworld Video: room acoustics are off, EFX setup failed: {}", t.toString());
            return null;
        }
    }

    /** Moves the filters towards {@code target} (null = open air with no reverb) over {@code dtSeconds}. */
    void update(AcousticResult target, float dtSeconds) {
        if (broken) return;
        try {
            AcousticResult t = target == null ? AcousticResult.OPEN : target;
            float alpha = 1f - (float) Math.exp(-Math.max(0.001f, dtSeconds) / SMOOTH_SECONDS);
            boolean changed = false;
            for (int i = 0; i < sources.length; i++) {
                float tg = sources.length == 1 ? (t.directGainL() + t.directGainR()) / 2f : (i == 0 ? t.directGainL() : t.directGainR());
                float th = sources.length == 1 ? (t.directHfL() + t.directHfR()) / 2f : (i == 0 ? t.directHfL() : t.directHfR());
                float ng = gain[i] + (tg - gain[i]) * alpha, nh = hf[i] + (th - hf[i]) * alpha;
                if (Math.abs(ng - gain[i]) > 0.003f || Math.abs(nh - hf[i]) > 0.003f) { gain[i] = ng; hf[i] = nh; changed = true; }
            }
            float ns = send + (t.sendGain() - send) * alpha, nsh = sendHf + (t.sendHf() - sendHf) * alpha, nd = decay + (t.decayTime() - decay) * alpha;
            if (Math.abs(ns - send) > 0.003f || Math.abs(nsh - sendHf) > 0.003f) { send = ns; sendHf = nsh; changed = true; }
            decay = nd;
            if (changed) applyAll();
            if (Math.abs(decay - appliedDecay) > 0.15f) applyReverb(decay, false);
            int error = AL10.alGetError();
            if (error != AL10.AL_NO_ERROR) throw new IllegalStateException("OpenAL error 0x" + Integer.toHexString(error));
        } catch (Throwable error) {
            LOGGER.warn("Nestworld Video: room acoustics switched off for this screen: {}", error.toString());
            broken = true;
            detach();
        }
    }

    private void applyAll() {
        for (int i = 0; i < sources.length; i++) {
            EXTEfx.alFilterf(directFilters[i], EXTEfx.AL_LOWPASS_GAIN, clamp(gain[i]));
            EXTEfx.alFilterf(directFilters[i], EXTEfx.AL_LOWPASS_GAINHF, clamp(hf[i]));
            AL10.alSourcei(sources[i], EXTEfx.AL_DIRECT_FILTER, directFilters[i]);
            EXTEfx.alFilterf(sendFilters[i], EXTEfx.AL_LOWPASS_GAIN, clamp(send));
            EXTEfx.alFilterf(sendFilters[i], EXTEfx.AL_LOWPASS_GAINHF, clamp(sendHf));
            AL11.alSource3i(sources[i], EXTEfx.AL_AUXILIARY_SEND_FILTER, slot, 0, sendFilters[i]);
        }
    }

    /** Reverb character follows the room size; the effect has to be re-attached to the slot for the change to apply. */
    private void applyReverb(float decayTime, boolean first) {
        float time = Math.max(0.3f, Math.min(4.0f, decayTime));
        if (eax) {
            EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DENSITY, 1.0f);
            EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DIFFUSION, 1.0f);
            EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_GAIN, 0.5f);
            EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_GAINHF, 0.89f);
            EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DECAY_TIME, time);
            EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DECAY_HFRATIO, 0.8f);
            EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_REFLECTIONS_GAIN, 0.35f);
            EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_REFLECTIONS_DELAY, 0.012f);
            EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_LATE_REVERB_GAIN, 1.2f);
            EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_LATE_REVERB_DELAY, 0.02f);
            EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_AIR_ABSORPTION_GAINHF, 0.994f);
            EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_ROOM_ROLLOFF_FACTOR, 0f);
        } else {
            EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DECAY_TIME, time);
        }
        EXTEfx.alAuxiliaryEffectSloti(slot, EXTEfx.AL_EFFECTSLOT_EFFECT, effect);
        appliedDecay = time;
    }

    private static float clamp(float v) { return Float.isFinite(v) ? Math.max(0f, Math.min(1f, v)) : 1f; }

    /** Takes the effects off the sources (so they play plain again). */
    private void detach() {
        try {
            for (int source : sources) {
                AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, 0);
                AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, EXTEfx.AL_EFFECTSLOT_NULL, 0, 0);
            }
        } catch (Throwable ignored) { /* the sound engine may already be gone */ }
    }

    /** Frees everything; call before the sources are deleted, in the same OpenAL context. */
    void close() {
        detach();
        try {
            for (int f : directFilters) EXTEfx.alDeleteFilters(f);
            for (int f : sendFilters) EXTEfx.alDeleteFilters(f);
            EXTEfx.alAuxiliaryEffectSloti(slot, EXTEfx.AL_EFFECTSLOT_EFFECT, EXTEfx.AL_EFFECTSLOT_NULL);
            EXTEfx.alDeleteAuxiliaryEffectSlots(slot);
            EXTEfx.alDeleteEffects(effect);
            AL10.alGetError();
        } catch (Throwable ignored) { /* the sound engine may already be gone */ }
    }
}
