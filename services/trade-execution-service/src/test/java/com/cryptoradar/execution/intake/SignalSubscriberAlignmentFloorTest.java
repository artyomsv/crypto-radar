package com.cryptoradar.execution.intake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vector D — alignment floor gate on overview signals.
 * Pure JSON parsing; no Quarkus context, no DB. Runs as plain JUnit.
 */
class SignalSubscriberAlignmentFloorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SignalSubscriber withFloor(int floor) {
        SignalSubscriber s = new SignalSubscriber();
        s.alignmentFloor = floor;
        return s;
    }

    private JsonNode node(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void missingAlignmentPasses() {
        // Detector-originated alerts lack an alignment field — must NOT be blocked here.
        SignalSubscriber s = withFloor(70);
        assertFalse(s.isBelowAlignmentFloor(
                node("{\"symbol\":\"BTCUSDT\",\"signal\":\"STRONG_BUY\"}")));
    }

    @Test
    void alignmentAtFloorPasses() {
        SignalSubscriber s = withFloor(70);
        assertFalse(s.isBelowAlignmentFloor(
                node("{\"alignment\":70}")));
    }

    @Test
    void alignmentAboveFloorPasses() {
        SignalSubscriber s = withFloor(70);
        assertFalse(s.isBelowAlignmentFloor(
                node("{\"alignment\":85}")));
    }

    @Test
    void alignmentBelowFloorBlocked() {
        SignalSubscriber s = withFloor(70);
        assertTrue(s.isBelowAlignmentFloor(
                node("{\"alignment\":55}")));
        assertTrue(s.isBelowAlignmentFloor(
                node("{\"alignment\":0}")));
    }

    @Test
    void floorOfZeroLetsEverythingThrough() {
        SignalSubscriber s = withFloor(0);
        assertFalse(s.isBelowAlignmentFloor(
                node("{\"alignment\":0}")));
        assertFalse(s.isBelowAlignmentFloor(
                node("{\"alignment\":40}")));
    }

    @Test
    void nonNumericAlignmentPasses() {
        SignalSubscriber s = withFloor(70);
        assertFalse(s.isBelowAlignmentFloor(
                node("{\"alignment\":null}")));
        assertFalse(s.isBelowAlignmentFloor(
                node("{\"alignment\":\"high\"}")));
    }
}
