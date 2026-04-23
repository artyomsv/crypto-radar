package com.cryptoradar.execution.model;

public enum ExitReason {
    TARGET,
    INITIAL_STOP,
    TRAIL_STOP,
    EXPIRED,
    FLIP_CLOSE,
    MANUAL,
    KILL,
    STAGNATION
}
