package com.cryptoradar.execution.security;

import com.cryptoradar.execution.client.bybit.dto.ApiKeyPermissionsV5;

import java.util.List;
import java.util.Map;

/**
 * Validates that a Bybit API key has ONLY the permissions we need — never the
 * withdrawal permission. Reject at account creation time, refuse to store.
 *
 * <p>Hard guardrail: even if our credential encryption is ever compromised, a
 * validated key physically cannot drain funds — it lacks withdraw permission
 * on Bybit's side.
 */
public final class PermissionValidator {

  private PermissionValidator() {}

  public static void validate(ApiKeyPermissionsV5 perms) {
    Map<String, List<String>> map = perms.permissions();
    if (map == null) {
      throw new IllegalStateException("API key permissions missing — cannot validate");
    }
    List<String> withdraw = map.getOrDefault("Withdraw", List.of());
    if (!withdraw.isEmpty()) {
      throw new IllegalStateException(
          "API key has withdraw permission — refused. Remove ALL 'Withdraw' permissions in Bybit UI and reissue the key.");
    }
    List<String> derivatives = map.getOrDefault("Derivatives", List.of());
    if (!derivatives.contains("Order")) {
      throw new IllegalStateException("API key missing Derivatives:Order permission");
    }
    if (!derivatives.contains("Position")) {
      throw new IllegalStateException("API key missing Derivatives:Position permission");
    }
  }
}
