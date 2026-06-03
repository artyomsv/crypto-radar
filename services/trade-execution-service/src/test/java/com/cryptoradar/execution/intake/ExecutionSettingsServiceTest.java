package com.cryptoradar.execution.intake;

import com.cryptoradar.execution.intake.ExecutionSettingsService.Snapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure unit tests for the validation rules guarding execution_settings
 * updates. No CDI, no DB — exercises the static validator directly.
 */
class ExecutionSettingsServiceTest {

    @Test
    void defaultsAreValid() {
        assertDoesNotThrow(() -> ExecutionSettingsService.validate(Snapshot.defaults()));
    }

    @Test
    void alignmentFloorAtBoundsAccepted() {
        assertDoesNotThrow(() -> ExecutionSettingsService.validate(withAlignmentFloor(0)));
        assertDoesNotThrow(() -> ExecutionSettingsService.validate(withAlignmentFloor(100)));
    }

    @Test
    void alignmentFloorBelowZeroRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ExecutionSettingsService.validate(withAlignmentFloor(-1)));
        assertEquals(true, ex.getMessage().contains("alignmentFloor"));
    }

    @Test
    void alignmentFloorAbove100Rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionSettingsService.validate(withAlignmentFloor(101)));
    }

    @Test
    void zeroLookbackRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionSettingsService.validate(gates(70, true, 0, -3.0, 30, true, 15, 60)));
    }

    @Test
    void negativeLookbackRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionSettingsService.validate(gates(70, true, -5, -3.0, 30, true, 15, 60)));
    }

    @Test
    void zeroCacheTtlRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionSettingsService.validate(gates(70, true, 10, -3.0, 0, true, 15, 60)));
    }

    @Test
    void zeroConfluenceWindowRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionSettingsService.validate(gates(70, true, 10, -3.0, 30, true, 0, 60)));
    }

    @Test
    void zeroDailyPnlTtlRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionSettingsService.validate(gates(70, true, 10, -3.0, 30, true, 15, 0)));
    }

    @Test
    void negativeThresholdRAccepted() {
        // -3.0R is the default — symbols accumulate losses, so a NEGATIVE
        // threshold is expected. Validator must not reject it.
        assertDoesNotThrow(() -> ExecutionSettingsService.validate(gates(70, true, 10, -10.0, 30, true, 15, 60)));
    }

    @Test
    void disabledGateStillValidates() {
        assertDoesNotThrow(() -> ExecutionSettingsService.validate(gates(70, false, 10, -3.0, 30, true, 15, 60)));
    }

    @Test
    void telegramEnabledWithoutChatIdRejected() {
        Snapshot s = new Snapshot(70, true, 10, -3.0, 30, true, 15, 60,
                true, null, List.of(), false, null, false);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ExecutionSettingsService.validate(s));
        assertEquals(true, ex.getMessage().contains("telegramChatId"));
    }

    @Test
    void telegramEnabledWithChatIdAccepted() {
        Snapshot s = new Snapshot(70, true, 10, -3.0, 30, true, 15, 60,
                true, "123456789", ExecutionSettingsService.DEFAULT_NOTIFIED_EVENTS, false, null, false);
        assertDoesNotThrow(() -> ExecutionSettingsService.validate(s));
    }

    @Test
    void unknownEventTypeRejected() {
        Snapshot s = new Snapshot(70, true, 10, -3.0, 30, true, 15, 60,
                false, null, List.of("NOT_A_REAL_EVENT"), false, null, false);
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionSettingsService.validate(s));
    }

    private static Snapshot withAlignmentFloor(int floor) {
        return gates(floor, true, 10, -3.0, 30, true, 15, 60);
    }

    private static Snapshot gates(int alignmentFloor, boolean symbolGateEnabled, int lookback,
                                  double thresholdR, int cacheTtl, boolean confluenceReq,
                                  int windowMin, int dailyTtl) {
        return new Snapshot(alignmentFloor, symbolGateEnabled, lookback, thresholdR, cacheTtl,
                confluenceReq, windowMin, dailyTtl,
                false, null, ExecutionSettingsService.DEFAULT_NOTIFIED_EVENTS, false, null, false);
    }
}
