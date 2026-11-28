package com.shopflow.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PhiAccrualFailureDetectorTest {

    @Test
    void phiIsZeroBeforeAnyHeartbeat() {
        var d = new PhiAccrualFailureDetector(200, 100, 0, 1000);
        assertThat(d.phi(123_456)).isZero();
        assertThat(d.isAvailable(123_456, 8.0)).isTrue();
    }

    @Test
    void regularHeartbeatsKeepPhiLowThenItClimbsWhenOverdue() {
        var d = new PhiAccrualFailureDetector(200, 100, 0, 1000);
        long t = 0;
        d.heartbeat(t);
        for (int i = 0; i < 20; i++) {        // a steady 1s heartbeat; last beat at t=20000
            t += 1000;
            d.heartbeat(t);
        }
        // exactly one interval later = right on time -> barely any suspicion
        assertThat(d.phi(21_000)).isLessThan(1.0);
        assertThat(d.isAvailable(21_000, 8.0)).isTrue();
        // 2s of silence from a node that beats every 1s -> clearly convicted
        assertThat(d.phi(22_000)).isGreaterThan(8.0);
        assertThat(d.isAvailable(22_000, 8.0)).isFalse();
    }

    @Test
    void aJitteryHistoryGetsMoreSlackThanARegularOneAtTheSameLateness() {
        // same lateness (1500ms since last beat, mean 1000ms): a metronome-regular peer (tight σ) is
        // convicted, a jittery peer (loose σ) is given the benefit of the doubt.
        double tight = PhiAccrualFailureDetector.phi(1500, 1000, 50);
        double loose = PhiAccrualFailureDetector.phi(1500, 1000, 300);
        assertThat(tight).isGreaterThan(loose);
        assertThat(tight).isGreaterThan(8.0);   // regular peer 0.5s late -> dead
        assertThat(loose).isLessThan(2.0);       // jittery peer 0.5s late -> still trusted
    }

    @Test
    void acceptableHeartbeatPauseSuppressesSuspicionForAnExpectedGap() {
        var strict = new PhiAccrualFailureDetector(200, 100, 0, 1000);
        var lenient = new PhiAccrualFailureDetector(200, 100, 3000, 1000); // tolerate a 3s pause
        for (var d : new PhiAccrualFailureDetector[] {strict, lenient}) {
            long t = 0;
            d.heartbeat(t);
            for (int i = 0; i < 20; i++) {
                t += 1000;
                d.heartbeat(t);
            }
        }
        // 2.5s overdue: strict convicts, lenient (expects pauses up to 3s) does not
        assertThat(strict.isAvailable(22_500, 8.0)).isFalse();
        assertThat(lenient.isAvailable(22_500, 8.0)).isTrue();
    }

    @Test
    void rejectsBadConfig() {
        assertThatThrownBy(() -> new PhiAccrualFailureDetector(0, 100, 0, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhiAccrualFailureDetector(200, 0, 0, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhiAccrualFailureDetector(200, 100, -1, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhiAccrualFailureDetector(200, 100, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
