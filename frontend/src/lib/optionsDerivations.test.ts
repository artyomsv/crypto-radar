import { describe, expect, it } from 'vitest';
import {
    formatPrice,
    OPTIONS_CONFIDENCE_THRESHOLD,
} from './optionsDerivations';

// First Vitest suite for the frontend. Targets the pure-function utility
// layer in `src/lib/` since component tests would require setting up
// @testing-library/react + happy-dom (deferred for a later pass).

describe('formatPrice (magnitude-aware price formatter)', () => {
    it('returns em-dash for null/undefined', () => {
        expect(formatPrice(null)).toBe('—');
        expect(formatPrice(undefined)).toBe('—');
    });

    it('returns "0" for zero', () => {
        expect(formatPrice(0)).toBe('0');
    });

    it('uses 0 decimals for large values', () => {
        expect(formatPrice(50_000)).toBe('50000');
        expect(formatPrice(1_500)).toBe('1500');
    });

    it('uses 2 decimals between 1 and 1000', () => {
        // toFixed(2) → "50.00", trailing-zero trim → "50"
        expect(formatPrice(50)).toBe('50');
        expect(formatPrice(123.4)).toBe('123.4');
        expect(formatPrice(123.45)).toBe('123.45');
    });

    it('uses 4 decimals for sub-1 values down to 0.1', () => {
        expect(formatPrice(0.5)).toBe('0.5');
        expect(formatPrice(0.123)).toBe('0.123');
        expect(formatPrice(0.123456)).toBe('0.1235');
    });

    it('uses 5 decimals for values between 0.001 and 0.1', () => {
        expect(formatPrice(0.01)).toBe('0.01');
        expect(formatPrice(0.098)).toBe('0.098');
        // 0.001 falls into the [0.001, 0.1) band → up to 5 decimals
        expect(formatPrice(0.00123)).toBe('0.00123');
    });

    it('uses 8 decimals for very small values', () => {
        // Below 0.001 (e.g. SHIB-tier prices)
        expect(formatPrice(0.00000123)).toBe('0.00000123');
    });

    it('trims trailing zeros', () => {
        expect(formatPrice(100.10)).toBe('100.1');
        expect(formatPrice(50.00)).toBe('50');
    });
});

describe('OPTIONS_CONFIDENCE_THRESHOLD', () => {
    it('mirrors the backend default', () => {
        // Must match `options.opportunity.confidence-threshold` in
        // services/options-service/src/main/resources/application.properties
        expect(OPTIONS_CONFIDENCE_THRESHOLD).toBe(75);
    });

    it('is a positive integer', () => {
        expect(OPTIONS_CONFIDENCE_THRESHOLD).toBeGreaterThan(0);
        expect(Number.isInteger(OPTIONS_CONFIDENCE_THRESHOLD)).toBe(true);
    });
});
