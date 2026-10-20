package com.rafi.glasshome;

import android.animation.TimeInterpolator;

/** Under-damped spring step response: settles by t=1 with a small overshoot. */
final class SpringInterpolator implements TimeInterpolator {
    private final double zeta;
    private final double omega;
    private final double omegaD;

    SpringInterpolator(double dampingRatio, double stiffness) {
        zeta = dampingRatio;
        omega = stiffness;
        omegaD = omega * Math.sqrt(1 - zeta * zeta);
    }

    @Override
    public float getInterpolation(float t) {
        if (t >= 1f) return 1f;
        double decay = Math.exp(-zeta * omega * t);
        return (float) (1 - decay * (Math.cos(omegaD * t) + (zeta * omega / omegaD) * Math.sin(omegaD * t)));
    }
}
