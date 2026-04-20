package com.cryptoradar.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrailConfigTest {

    @Test
    void defaultValuesAreActivation1StepHalfOffsetHalf() {
        TrailConfig config = TrailConfig.DEFAULT;
        assertEquals(1.0, config.activationR());
        assertEquals(0.5, config.stepR());
        assertEquals(0.5, config.offsetR());
    }

    @Test
    void customValuesArePreserved() {
        TrailConfig config = new TrailConfig(2.0, 1.0, 0.25);
        assertEquals(2.0, config.activationR());
        assertEquals(1.0, config.stepR());
        assertEquals(0.25, config.offsetR());
    }

    @Test
    void rejectsZeroActivation() {
        assertThrows(IllegalArgumentException.class, () -> new TrailConfig(0.0, 0.5, 0.5));
    }

    @Test
    void rejectsNegativeActivation() {
        assertThrows(IllegalArgumentException.class, () -> new TrailConfig(-0.1, 0.5, 0.5));
    }

    @Test
    void rejectsZeroStep() {
        assertThrows(IllegalArgumentException.class, () -> new TrailConfig(1.0, 0.0, 0.5));
    }

    @Test
    void rejectsNegativeStep() {
        assertThrows(IllegalArgumentException.class, () -> new TrailConfig(1.0, -0.1, 0.5));
    }

    @Test
    void rejectsNegativeOffset() {
        assertThrows(IllegalArgumentException.class, () -> new TrailConfig(1.0, 0.5, -0.1));
    }

    @Test
    void acceptsZeroOffset() {
        TrailConfig config = new TrailConfig(1.0, 0.5, 0.0);
        assertEquals(0.0, config.offsetR());
    }
}
