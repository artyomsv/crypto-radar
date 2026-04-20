package com.cryptoradar.execution.security;

import com.cryptoradar.execution.client.bybit.dto.ApiKeyPermissionsV5;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PermissionValidatorTest {

    private ApiKeyPermissionsV5 perms(Map<String, List<String>> permissions) {
        return new ApiKeyPermissionsV5("1", "note", "key", 0, permissions);
    }

    @Test
    void acceptsDerivativesOrderPositionWithoutWithdraw() {
        assertDoesNotThrow(() -> PermissionValidator.validate(perms(Map.of(
                "Derivatives", List.of("Order", "Position"),
                "Wallet", List.of("AccountTransfer"),
                "Withdraw", List.of()
        ))));
    }

    @Test
    void rejectsIfWithdrawPresent() {
        assertThrows(IllegalStateException.class, () -> PermissionValidator.validate(perms(Map.of(
                "Derivatives", List.of("Order", "Position"),
                "Withdraw", List.of("Asset")
        ))));
    }

    @Test
    void rejectsIfMissingDerivativesOrder() {
        assertThrows(IllegalStateException.class, () -> PermissionValidator.validate(perms(Map.of(
                "Derivatives", List.of("Position"),
                "Withdraw", List.of()
        ))));
    }

    @Test
    void rejectsIfMissingDerivativesPosition() {
        assertThrows(IllegalStateException.class, () -> PermissionValidator.validate(perms(Map.of(
                "Derivatives", List.of("Order"),
                "Withdraw", List.of()
        ))));
    }

    @Test
    void rejectsNullPermissionsMap() {
        assertThrows(IllegalStateException.class, () -> PermissionValidator.validate(
                new ApiKeyPermissionsV5("1", "", "k", 0, null)));
    }
}
