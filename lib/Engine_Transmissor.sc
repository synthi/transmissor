// Engine_Transmissor.sc — Transmissor v1.5.1
// Shortwave SSB transmission simulator engine for norns
// Audio input → SSB modulation → RF effects → SSB demodulation → output
//
// Changelog:
//   v1.4.0  Echo Return (radio echo via LocalIn/LocalOut — accumulative degradation),
//           Key click = CombL identical to FX Comb (freq=160, fb=5.12),
//           Noise floor minimum reduced 8-9 dB (floor×0.18),
//           Rename echo FX → delay FX
//   v1.3.4  Route floor→noiseSynth, hum_level→carrierSynth
//   v1.3.0  Cosmic ping, Meteor scatter stable
//   v1.0    Initial release

Engine_Transmissor : CroneEngine {

    var <masterSynth;
    var <carrierSynth;
    var <noiseSynth;
    var <inputSynth;
    var <voiceBus;
    var <ambientBus;
    var distanceVal;
    var floorVal;
    var humLevelVal;

    *new { arg context, doneCallback;
        ^super.new(context, doneCallback);
    }

    alloc {

        voiceBus   = Bus.audio(context.server, 2);
        ambientBus = Bus.audio(context.server, 2);
        distanceVal = 0.0;
        floorVal = 0.0;
        humLevelVal = 0.0;

        // CARRIER SYNTH (pitchLFO and ampLFO correlated — same oscillator)
        carrierSynth = {
            arg vol = 0.0, freq = 4800, pitchLFO = 0.083, ampLFO = 0.149;
            var freqSmooth, volSmooth, pitchMod, ampMod, sig;
            freqSmooth = freq.lag(0.08);
            volSmooth  = vol.lag(0.05);
            pitchMod = LFNoise1.kr(pitchLFO).range(freqSmooth * 0.998, freqSmooth * 1.002);
            ampMod = LFNoise1.kr(ampLFO).range(0.3, 1.0);
            sig = SinOsc.ar(pitchMod) * ampMod * volSmooth;
            sig = BPF.ar(sig, freqSmooth, 0.1);
            Out.ar(ambientBus, sig ! 2);
        }.play(context.server, addAction: \addToHead);

        // NOISE SYNTH
        noiseSynth = {
            arg vol = 0.0, popRate = 1.2, center = 2400;
            var volSmooth, band, pops, sig;
            volSmooth = vol.lag(0.08);
            band = BPF.ar(WhiteNoise.ar(1.0), center, 0.8) * 0.6;
            pops = Dust.ar(popRate) * 0.4;
            pops = HPF.ar(pops, 1200);
            sig = (band + pops) * volSmooth;
            Out.ar(ambientBus, sig ! 2);
        }.play(context.server, addAction: \addToHead);

        // MASTER FX (phaser prime rate: 0.157)
        masterSynth = {
            arg bandwidth = 4000, locut = 80, phaserFreq = 0.157,
                ambientVol = 0.4, trailWet = 0.0, trailTime = 0.45,
                trailFeedback = 0.0, volume = 0.7;
            var voiceIn, ambientIn, sig, trail, wetSmooth, fbSmooth;
            voiceIn   = In.ar(voiceBus, 2);
            ambientIn = LPF.ar(In.ar(ambientBus, 2), bandwidth);
            sig = HPF.ar(voiceIn, locut);
            sig = LPF.ar(sig, bandwidth);
            sig = AllpassN.ar(sig, 0.02, SinOsc.kr(phaserFreq).range(0.001, 0.009), 0.08);
            wetSmooth = trailWet.lag(0.1);
            fbSmooth  = trailFeedback.lag(0.1);
            trail = CombL.ar(sig, 2.0, trailTime, fbSmooth * 8.0);
            sig = sig + (trail * wetSmooth);
            sig = sig + (ambientIn * ambientVol);
            Out.ar(0, Limiter.ar(sig * volume, 0.95));
        }.play(context.server, addAction: \addToTail);

        // INPUT SSB CHAIN
        inputSynth = {
            arg
                tx_freq = 4800, osc_jitter = 0.2, pilot_leak = 0.0,
                saturation = 0.0, harmonic_drive = 0.0,
                multipath = 0.3, doppler = 3.0, fade_rate = 0.3,
                fade_depth = 0.5, smear = 0.2, link_quality = 1.0,
                atmos = 0.2, space_hum = 0.05, whistle = 0.0,
                hum = 0.0, e_skip = 0.0, borealis = 0.0,
                detune = 0.0, rx_drift = 0.1, agc_rate = 0.4,
                agc_breath = 0.1, rx_bw = 2400, adc_depth = 16,
                input_trim = 1.0, blend = 0.7,
                rx_hpf = 60,
                rev_wet = 0.0, rev_decay = 0.3, rev_damp = 0.5,
                ech_wet = 0.0, ech_time = 0.3, ech_fb = 0.3,
                cho_wet = 0.0, cho_rate = 0.5, cho_depth = 0.005,
                com_wet = 0.0, com_freq = 100, com_fb = 0.3,
                dst_wet = 0.0, dst_drive = 3.0, dst_tone = 4000,
                fbn_wet = 0.0, fbn_spread = 0.5, fbn_rate = 0.3,
                ech_rt_wet = 0.0, ech_rt_time = 0.5, ech_rt_fb = 0.4;

            var input, hilbert, rf, rfMultipath, rfEffects;
            var demod, sig, compSig, agcKey;
            var tapDelay, tapGain, harmonicSig;
            var rfReverb, rfEcho, rfChorus, rfComb, rfDist, rfFBank;
            var detuneSmooth, detuneAtten;
            var meteorTrigger, cosmicPing;
            var sigEnv, noiseFloor, eTrig, eEnv, ditherSig;
            var echoReturn;

            // 0. ECHO RETURN — receive from feedback loop (re-transmission)
            echoReturn = LocalIn.ar(1);

            // 1. INPUT + echo return injection (before modulator)
            input = SoundIn.ar(0) * input_trim;
            input = input + (echoReturn * ech_rt_wet);

            // 2. PRE-MOD SATURATION
            input = (input * (1 + saturation * 4.0)).tanh *
                (1.0 / (1.0 + saturation * 4.0).max(0.001));

            // 3. SSB MODULATOR (USB via Hilbert)
            hilbert = Hilbert.ar(input);
            rf = (hilbert[0] * SinOsc.ar(
                    tx_freq + (osc_jitter * 50.0 * LPF.kr(PinkNoise.kr, 50)),
                    pi/2)) -
                 (hilbert[1] * SinOsc.ar(tx_freq));

            // 4. CARRIER LEAK
            rf = rf + (SinOsc.ar(tx_freq, pilot_leak * 0.001));

            // 5. HARMONIC DISTORTION
            harmonicSig = 0.0;
            harmonicSig = harmonicSig + (SinOsc.ar(tx_freq * 2.0) * 0.5);
            harmonicSig = harmonicSig + (SinOsc.ar(tx_freq * 3.0) * 0.3);
            harmonicSig = harmonicSig + (SinOsc.ar(tx_freq * 4.0) * 0.1);
            rf = rf + (harmonic_drive * harmonicSig);

            // 6. MULTIPATH (5 taps — independent reflections, prime rates)
            rfMultipath = rf * 0.85;
            tapDelay = LFNoise1.kr(0.53 + (multipath * 0.5))
                .range(0.002, 0.002 + multipath * 0.015);
            tapGain = 0.35 * multipath;
            rfMultipath = rfMultipath + (DelayC.ar(rf, 0.05, tapDelay) * tapGain);
            tapDelay = LFNoise1.kr(0.71 + (multipath * 0.3))
                .range(0.003, 0.003 + multipath * 0.020);
            tapGain = 0.25 * multipath;
            rfMultipath = rfMultipath + (DelayC.ar(rf, 0.05, tapDelay) * tapGain);
            tapDelay = LFNoise1.kr(0.29 + (multipath * 0.7))
                .range(0.001, 0.001 + multipath * 0.025);
            tapGain = 0.15 * multipath;
            rfMultipath = rfMultipath + (DelayC.ar(rf, 0.05, tapDelay) * tapGain);
            tapDelay = LFNoise1.kr(0.89 + (multipath * 0.1))
                .range(0.004, 0.004 + multipath * 0.018);
            tapGain = 0.08 * multipath;
            rfMultipath = rfMultipath + (DelayC.ar(rf, 0.05, tapDelay) * tapGain);
            rf = rfMultipath;

            // 7. DOPPLER SPREAD (independent objects, prime rates)
            rfEffects = FreqShift.ar(rf, LFNoise1.kr(0.31).range(doppler.neg, doppler));
            rfEffects = rfEffects + (FreqShift.ar(rf,
                LFNoise1.kr(0.73).range(doppler.neg * 0.5, doppler * 0.5)) * 0.3);
            rf = rfEffects;

            // 8. SELECTIVE FADING (rate/depth correlated — same physical process)
            rf = BPF.ar(rf,
                tx_freq + (fade_depth * LFNoise1.kr(fade_rate * 2).range(tx_freq.neg * 0.3, tx_freq * 0.3)),
                LFNoise1.kr(fade_rate).range(0.2, 1.0 - (fade_depth * 0.5)));

            // 9. DISPERSION (ionospheric — partial correlation, prime rates)
            // AP1 = layer F (0.19, slower), AP2 = layer E (0.31, faster)
            // Ratio 0.19/0.31 = 0.613 — irrational, correlated but not aligned
            meteorTrigger = Dust.ar(0.12);
            cosmicPing = Decay.ar(meteorTrigger, 0.05)
                * BPF.ar(WhiteNoise.ar(1.0), tx_freq, 0.2)
                * 0.03 * smear;
            rf = rf + cosmicPing;
            rf = AllpassC.ar(rf, 0.01,
                LFNoise1.kr(0.19).range(0.0005, 0.0005 + smear * 0.005),
                (LFNoise1.kr(0.053).range(0.1, 0.3) * (1.0 + (smear * 2.0))).clip(0.0, 0.5)
            );
            rf = AllpassC.ar(rf, 0.01,
                LFNoise1.kr(0.31).range(0.0003, 0.0003 + smear * 0.004),
                (Decay2.ar(meteorTrigger, 0.5, 2.0).range(0.1, 0.4) * (1.0 + (smear * 2.0))).clip(0.0, 0.5)
            );

            // 10. ATMOSPHERIC NOISE (prime rate: 0.11)
            rf = rf + (atmos * 0.08 * LPF.ar(Dust.ar(LFNoise1.kr(0.11).range(5, 40)), 200));

            // 11. GALACTIC NOISE
            rf = rf + (space_hum * 0.02 * BrownNoise.ar(1.0));

            // 12. HETERODYNE WHISTLE (prime rate: 0.13)
            rf = rf + (whistle * 0.006 * SinOsc.ar(tx_freq + LFNoise1.kr(0.13).range(300, 3000)));

            // 13. POWER LINE HUM (60Hz harmonics — always correlated, correct)
            rf = rf + (hum * 0.04 * (SinOsc.ar(60) * 0.5 + SinOsc.ar(120) * 0.3 + SinOsc.ar(180) * 0.15));

            // 14. SPORADIC E (prime rates: 0.047, 0.23)
            eTrig = Dust.kr(LFNoise1.kr(0.047).range(0.05, 0.2));
            eEnv = EnvGen.kr(Env.perc(0.01, 0.3), eTrig);
            rf = rf * (1 + (e_skip * eEnv * LFNoise1.kr(0.23).range(0.5, 1.5)));

            // 15. AURORAL (prime rate: 0.53)
            rf = rf * (1 + (borealis * 0.3 * LFNoise0.kr(LFNoise1.kr(0.53).range(20, 80)).range(-1, 1)));

            // RF FX CHAIN
            // Reverb
            rfReverb = Select.ar(rev_wet > 0.001, [ rf, FreeVerb.ar(rf, rev_wet, rev_decay, rev_damp) ]);
            // Echo
            rfEcho = rfReverb + (DelayL.ar(rfReverb, 2.0, ech_time, ech_fb * 6.0) * ech_wet);
            rf = Select.ar(ech_wet > 0.001, [ rfReverb, rfEcho ]);
            // Chorus (3 voices, related rates: 1.0 / 1.31 / 0.73 — coherent space)
            rfChorus = rf;
            rfChorus = rfChorus + (DelayC.ar(rf, 0.03, SinOsc.kr(cho_rate).range(0.005, 0.005 + cho_depth)) * cho_wet * 0.4);
            rfChorus = rfChorus + (DelayC.ar(rf, 0.03, SinOsc.kr(cho_rate * 1.31).range(0.008, 0.008 + cho_depth * 0.7)) * cho_wet * 0.3);
            rfChorus = rfChorus + (DelayC.ar(rf, 0.03, SinOsc.kr(cho_rate * 0.73).range(0.003, 0.003 + cho_depth * 0.5)) * cho_wet * 0.2);
            rf = Select.ar(cho_wet > 0.001, [ rf, rfChorus ]);

            // FX Comb
            rfComb = Select.ar(com_wet > 0.001, [ rf, CombL.ar(rf, 0.5, 1.0 / com_freq.max(20), com_fb * 8.0) ]);
            rf = rfComb;
            // Distortion
            rfDist = Select.ar(dst_wet > 0.001, [ rf, LPF.ar((rf * (1 + dst_drive * 2)).tanh / (1 + dst_drive * 2).tanh, dst_tone) ]);
            rf = rfDist;
            // Filter Bank (related rate: 0.73 — same spatial process)
            rfFBank = Select.ar(fbn_wet > 0.001, [ rf,
                (BPF.ar(rf, tx_freq * (1.0 - fbn_spread * 0.15) * (1 + fbn_wet * 0.05 * LFNoise1.kr(fbn_rate).range(-1, 1)), 0.3) * (1.0 - fbn_spread * 0.3)) +
                (BPF.ar(rf, tx_freq, 0.3) * 1.0) +
                (BPF.ar(rf, tx_freq * (1.0 + fbn_spread * 0.15) * (1 + fbn_wet * 0.05 * LFNoise1.kr(fbn_rate * 0.73).range(-1, 1)), 0.3) * (1.0 - fbn_spread * 0.3)) ]);
            rf = rfFBank;

            // 16. SNR (noise fills gaps between words)
            sigEnv = Amplitude.ar(rf, 0.01, 0.1);
            noiseFloor = WhiteNoise.ar(1.0) * (1.0 - link_quality) * 0.1 * (1.0 - sigEnv.min(1.0));
            rf = (rf * link_quality) + noiseFloor;

            // 17. SSB DEMODULATOR (prime drift rate: 0.059)
            demod = rf * SinOsc.ar(tx_freq + (rx_drift * LFNoise1.kr(0.059).range(-5, 5)), pi/2);
            demod = LPF.ar(demod, 5000);

            // 17b. DETUNE
            detuneSmooth = detune.lag(0.05);
            detuneAtten = 1.0 - (detuneSmooth.abs / 50.0).min(0.9);
            demod = FreqShift.ar(demod, detuneSmooth.neg);
            demod = demod * detuneAtten;

            // 18. ADC QUANTIZATION with TPDF dither
            ditherSig = (WhiteNoise.ar(1.0) + WhiteNoise.ar(1.0)) * (0.5 / pow(2, adc_depth));
            sig = (demod + ditherSig).round(2.0 / pow(2, adc_depth));

            // 19. AGC (realistic SSB receiver)
            agcKey = sig.abs;
            compSig = Compander.ar(sig, agcKey, 0.1, 1.0, 0.2, 0.002, agc_rate.max(0.05));
            sig = LeakDC.ar(compSig) * (4.0 + (agc_breath * 6.0));
            sig = sig.clip2(0.95);

            // 20. RX BANDWIDTH
            sig = HPF.ar(sig, rx_hpf);
            sig = LPF.ar(sig, rx_bw);

            // 20b. RECEIVER HUM (50Hz mains harmonics — always correlated, correct)
            sig = sig + (hum * 0.08 * (
                SinOsc.ar(50) * 0.5 + SinOsc.ar(100) * 0.3 + SinOsc.ar(150) * 0.15));

            // 21. BLEND (default 0.7 = always 30% dry audible)
            sig = (input * (1 - blend)) + (sig * blend);

            // 22. OUTPUT + ECHO RETURN FEEDBACK (radio echo)
            Out.ar(voiceBus, sig ! 2);
            LocalOut.ar(Limiter.ar(
                DelayL.ar(sig, 4.0, ech_rt_time.max(0.05)) * ech_rt_fb,
                0.95
            ));

        }.play(context.server, addAction: \addToHead);

        // =========================================================
        // COMMANDS
        // =========================================================

        // TX
        this.addCommand("set_tx_freq", "f", { arg msg; inputSynth.set(\tx_freq, msg[1]); });
        this.addCommand("set_osc_jitter", "f", { arg msg; inputSynth.set(\osc_jitter, msg[1]); });
        this.addCommand("set_pilot_leak", "f", { arg msg; inputSynth.set(\pilot_leak, msg[1]); });
        this.addCommand("set_saturation", "f", { arg msg; inputSynth.set(\saturation, msg[1]); });
        this.addCommand("set_harmonic_drive", "f", { arg msg; inputSynth.set(\harmonic_drive, msg[1]); });
        // AIR
        this.addCommand("set_multipath", "f", { arg msg; inputSynth.set(\multipath, msg[1]); });
        this.addCommand("set_doppler", "f", { arg msg; inputSynth.set(\doppler, msg[1]); });
        this.addCommand("set_fade_rate", "f", { arg msg; inputSynth.set(\fade_rate, msg[1]); });
        this.addCommand("set_fade_depth", "f", { arg msg; inputSynth.set(\fade_depth, msg[1]); });
        this.addCommand("set_smear", "f", { arg msg; inputSynth.set(\smear, msg[1]); });
        this.addCommand("set_link_quality", "f", { arg msg; inputSynth.set(\link_quality, msg[1]); });

        // NOISE
        this.addCommand("set_atmos", "f", { arg msg; inputSynth.set(\atmos, msg[1]); });
        this.addCommand("set_space_hum", "f", { arg msg; inputSynth.set(\space_hum, msg[1]); });
        this.addCommand("set_whistle", "f", { arg msg; inputSynth.set(\whistle, msg[1]); });
        this.addCommand("set_hum", "f", { arg msg; inputSynth.set(\hum, msg[1]); });
        this.addCommand("set_e_skip", "f", { arg msg; inputSynth.set(\e_skip, msg[1]); });
        this.addCommand("set_borealis", "f", { arg msg; inputSynth.set(\borealis, msg[1]); });

        // RX
        this.addCommand("set_detune", "f", { arg msg; inputSynth.set(\detune, msg[1]); });
        this.addCommand("set_rx_drift", "f", { arg msg; inputSynth.set(\rx_drift, msg[1]); });
        this.addCommand("set_agc_rate", "f", { arg msg; inputSynth.set(\agc_rate, msg[1]); });
        this.addCommand("set_agc_breath", "f", { arg msg; inputSynth.set(\agc_breath, msg[1]); });
        this.addCommand("set_rx_bw", "f", { arg msg; inputSynth.set(\rx_bw, msg[1]); });
        this.addCommand("set_adc_depth", "f", { arg msg; inputSynth.set(\adc_depth, msg[1]); });

        // MIX — floor → noiseSynth, hum_level → carrierSynth (additive with distance)
        this.addCommand("set_input_trim", "f", { arg msg; inputSynth.set(\input_trim, msg[1]); });
        this.addCommand("set_blend", "f", { arg msg; inputSynth.set(\blend, msg[1]); });
        this.addCommand("set_floor", "f", { arg msg;
            floorVal = msg[1];
            noiseSynth.set(\vol, floorVal + (distanceVal * 0.15));
            masterSynth.set(\ambientVol, (distanceVal * 0.4).max(floorVal * 0.18));
        });
        this.addCommand("set_hum_level", "f", { arg msg;
            humLevelVal = msg[1];
            carrierSynth.set(\vol, humLevelVal + (distanceVal * 0.06));
        });

        // EQ
        this.addCommand("set_locut", "f", { arg msg; masterSynth.set(\locut, msg[1]); });
        this.addCommand("set_hicut", "f", { arg msg; masterSynth.set(\bandwidth, msg[1]); });
        this.addCommand("set_rx_hpf", "f", { arg msg; inputSynth.set(\rx_hpf, msg[1]); });

        // RF FX — SPACE
        this.addCommand("set_rev_wet", "f", { arg msg; inputSynth.set(\rev_wet, msg[1]); });
        this.addCommand("set_rev_decay", "f", { arg msg; inputSynth.set(\rev_decay, msg[1]); });
        this.addCommand("set_rev_damp", "f", { arg msg; inputSynth.set(\rev_damp, msg[1]); });
        this.addCommand("set_ech_wet", "f", { arg msg; inputSynth.set(\ech_wet, msg[1]); });
        this.addCommand("set_ech_time", "f", { arg msg; inputSynth.set(\ech_time, msg[1]); });
        this.addCommand("set_ech_fb", "f", { arg msg; inputSynth.set(\ech_fb, msg[1]); });

        // RF FX — TEXTURE
        this.addCommand("set_cho_wet", "f", { arg msg; inputSynth.set(\cho_wet, msg[1]); });
        this.addCommand("set_cho_rate", "f", { arg msg; inputSynth.set(\cho_rate, msg[1]); });
        this.addCommand("set_cho_depth", "f", { arg msg; inputSynth.set(\cho_depth, msg[1]); });
        this.addCommand("set_com_wet", "f", { arg msg; inputSynth.set(\com_wet, msg[1]); });
        this.addCommand("set_com_freq", "f", { arg msg; inputSynth.set(\com_freq, msg[1]); });
        this.addCommand("set_com_fb", "f", { arg msg; inputSynth.set(\com_fb, msg[1]); });

        // RF FX — DESTROY
        this.addCommand("set_dst_wet", "f", { arg msg; inputSynth.set(\dst_wet, msg[1]); });
        this.addCommand("set_dst_drive", "f", { arg msg; inputSynth.set(\dst_drive, msg[1]); });
        this.addCommand("set_dst_tone", "f", { arg msg; inputSynth.set(\dst_tone, msg[1]); });
        this.addCommand("set_fbn_wet", "f", { arg msg; inputSynth.set(\fbn_wet, msg[1]); });
        this.addCommand("set_fbn_spread", "f", { arg msg; inputSynth.set(\fbn_spread, msg[1]); });
        this.addCommand("set_fbn_rate", "f", { arg msg; inputSynth.set(\fbn_rate, msg[1]); });

        // ECHO RETURN (Radio Echo)
        this.addCommand("set_ech_rt_wet", "f", { arg msg; inputSynth.set(\ech_rt_wet, msg[1]); });
        this.addCommand("set_ech_rt_time", "f", { arg msg; inputSynth.set(\ech_rt_time, msg[1]); });
        this.addCommand("set_ech_rt_fb", "f", { arg msg; inputSynth.set(\ech_rt_fb, msg[1]); });

        // AMBIENT SYNTH CONTROLS
        this.addCommand("set_carrier_vol", "f", { arg msg; carrierSynth.set(\vol, msg[1]); });
        this.addCommand("set_carrier_freq", "f", { arg msg; carrierSynth.set(\freq, msg[1]); });
        this.addCommand("set_carrier_drift", "f", { arg msg;
            // pitchLFO and ampLFO correlated — same oscillator (ampLFO = pitchLFO × 1.8)
            carrierSynth.set(\pitchLFO, msg[1]); carrierSynth.set(\ampLFO, msg[1] * 1.8); });
        this.addCommand("set_noise_vol", "f", { arg msg; noiseSynth.set(\vol, msg[1]); });
        this.addCommand("set_noise_pops", "f", { arg msg; noiseSynth.set(\popRate, msg[1]); });
        this.addCommand("set_master_bw", "f", { arg msg; masterSynth.set(\bandwidth, msg[1]); });
        this.addCommand("set_master_ambient", "f", { arg msg; masterSynth.set(\ambientVol, msg[1]); });

        // DISTANCE — additive with floor and hum_level
        this.addCommand("set_distance", "f", { arg msg;
            var d = msg[1].clip(0.0, 1.0);
            distanceVal = d;
            carrierSynth.set(\vol, humLevelVal + (d * 0.06));
            noiseSynth.set(\vol, floorVal + (d * 0.15));
            noiseSynth.set(\popRate, d * 4.0 + 0.3);
            masterSynth.set(\bandwidth, (4000 - (d * 2500)).max(1500));
            masterSynth.set(\ambientVol, (d * 0.4).max(floorVal * 0.18));
        });

        // KILL TRAIL
        this.addCommand("kill_trail", "f", { arg msg;
            var dur = msg[1];
            masterSynth.set(\trailWet, 0.9);
            masterSynth.set(\trailFeedback, 0.85);
            SystemClock.sched(dur, {
                masterSynth.set(\trailWet, 0.0);
                masterSynth.set(\trailFeedback, 0.0);
                masterSynth.set(\ambientVol, distanceVal * 0.55);
                nil;
            });
        });

        // TRAIL CLEAR
        this.addCommand("trail_clear", "f", { arg msg;
            masterSynth.set(\trailWet, 0.0);
            masterSynth.set(\trailFeedback, 0.0);
        });
    }

    free {
        masterSynth.free;
        carrierSynth.free;
        noiseSynth.free;
        inputSynth.free;
        voiceBus.free;
        ambientBus.free;
    }
}