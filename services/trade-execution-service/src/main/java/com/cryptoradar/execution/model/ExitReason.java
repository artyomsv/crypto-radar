package com.cryptoradar.execution.model;

public enum ExitReason {
    TARGET,
    INITIAL_STOP,
    TRAIL_STOP,
    EXPIRED,
    FLIP_CLOSE,
    MANUAL,
    KILL,
    STAGNATION,       // Stagnation monitor triggered
    DONCHIAN_EXIT,    // Reverse Donchian-channel breach (Turtle/Donchian native exit)
    UNKNOWN           // Closed on the exchange but exit metadata is unrecoverable (no fabricated PnL)
}
