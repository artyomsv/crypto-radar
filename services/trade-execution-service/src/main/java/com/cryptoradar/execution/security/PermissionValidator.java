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
    // Bybit V5 exposes perp-futures permissions under the "ContractTrade" key.
    // (The "Derivatives" key here is for their Derivatives Trade product — different.)
    // Verified against live /v5/user/query-api on api-demo.bybit.com.
    List<String> contractTrade = map.getOrDefault("ContractTrade", List.of());
    if (!contractTrade.contains("Order")) {
      throw new IllegalStateException("API key missing ContractTrade:Order permission");
    }
    if (!contractTrade.contains("Position")) {
      throw new IllegalStateException("API key missing ContractTrade:Position permission");
    }
  }
}
