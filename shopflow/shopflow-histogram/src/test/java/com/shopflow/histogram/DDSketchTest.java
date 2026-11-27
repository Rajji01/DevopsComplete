package com.shopflow.histogram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.assertj.core.data.Percentage;
import org.junit.jupiter.api.Test;

class DDSketchTest {

    @Test
    void quantilesAreWithinTheRelativeErrorBound() {
        var sketch = DDSketch.create(0.01); // 1% relative accuracy
        for (int i = 1; i <= 100_000; i++) {
            sketch.add(i);
        }
        // true quantiles of 1..100000; each estimate must be within ~1% (allow 2% for rank granularity)
        assertThat(sketch.quantile(0.50)).isCloseTo(50_000, Percentage.withPercentage(2));
        assertThat(sketch.quantile(0.90)).isCloseTo(90_000, Percentage.withPercentage(2));
        assertThat(sketch.quantile(0.99)).isCloseTo(99_000, Percentage.withPercentage(2));
        assertThat(sketch.quantile(0.999)).isCloseTo(99_900, Percentage.withPercentage(2));
    }

    @Test
    void accuracyHoldsAtBothEndsOfaWideRange() {
        // the relative-error point: same % accuracy whether the value is tiny or huge
        var sketch = DDSketch.create(0.02);
        for (int i = 0; i < 1000; i++) {
            sketch.add(5);        // ~ a "fast" cluster
            sketch.add(5000);     // ~ a "slow" cluster, 1000x larger
        }
        assertThat(sketch.quantile(0.25)).isCloseTo(5, Percentage.withPercentage(3));
        assertThat(sketch.quantile(0.75)).isCloseTo(5000, Percentage.withPercentage(3));
    }

    @Test
    void handlesZeroValues() {
        var sketch = DDSketch.create(0.01);
        for (int i = 0; i < 100; i++) {
            sketch.add(0);
        }
        for (int i = 0; i < 100; i++) {
            sketch.add(1000);
        }
        assertThat(sketch.quantile(0.25)).isZero();                 // lower quarter is the zeros
        assertThat(sketch.quantile(0.75)).isCloseTo(1000, Percentage.withPercentage(2));
    }

    @Test
    void mergeEstimatesTheCombinedQuantiles() {
        var a = DDSketch.create(0.01);
        var b = DDSketch.create(0.01);
        for (int i = 1; i <= 50_000; i++) {
            a.add(i);
        }
        for (int i = 50_001; i <= 100_000; i++) {
            b.add(i);
        }
        a.mergeFrom(b);
        assertThat(a.count()).isEqualTo(100_000);
        assertThat(a.quantile(0.99)).isCloseTo(99_000, Percentage.withPercentage(2));
    }

    @Test
    void reportsItsGuaranteeAndCount() {
        var sketch = DDSketch.create(0.05);
        sketch.add(1);
        sketch.add(2);
        assertThat(sketch.relativeError()).isEqualTo(0.05);
        assertThat(sketch.count()).isEqualTo(2);
    }

    @Test
    void rejectsBadInput() {
        assertThatThrownBy(() -> DDSketch.create(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DDSketch.create(1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DDSketch.create(0.01).add(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DDSketch.create(0.01).quantile(1.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DDSketch.create(0.01).quantile(0.5)).isInstanceOf(IllegalStateException.class);
    }
}
