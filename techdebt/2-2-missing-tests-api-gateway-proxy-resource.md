# Missing integration tests for ProxyResource (api-gateway)

| Field | Value |
|-------|-------|
| Criticality | High |
| Complexity | Small |
| Location | `services/api-gateway/src/main/java/com/cryptoradar/gateway/resource/ProxyResource.java` |
| Found during | QA coverage check — recent commit batch |
| Date | 2026-04-03 |

## Issue

`api-gateway` has zero test files. `ProxyResource` was changed in this commit batch: it now proxies the full crypto-config CRUD surface (add, toggle, delete crypto; update backfill depth; search symbols). There are no tests for any of these new or existing endpoints.

Key untested behaviors:
- `proxyResponse()` returns 502 BAD_GATEWAY when the upstream returns null (ServiceClient failure)
- All new proxy endpoints correctly construct upstream URLs (including query params like `deleteData` and `q`)
- `proxyDelete()` is wired correctly for `removeCrypto`
- `proxyPut()` is wired correctly for `toggleCrypto` and `updateBackfillConfig`

## Risks

- URL construction bugs (e.g., wrong path segment, missing query param) are invisible without tests.
- The 502 fallback path is untested — a regression here would expose raw null pointer exceptions to the frontend instead of a clean error.
- New proxy methods added without tests make future refactors of `ServiceClient` risky.

## Suggested Solutions

1. **Unit-test `ProxyResource`** with a mocked `ServiceClient`. Verify each new endpoint:
   - Calls the correct upstream URL on `serviceClient`
   - Returns 200 when upstream returns a body
   - Returns 502 when upstream returns null

2. Use `@ExtendWith(MockitoExtension.class)` — no Spring/Quarkus context needed since `ProxyResource` only delegates to `ServiceClient`.

Example:
```java
@Test
@DisplayName("removeCrypto passes deleteData flag to upstream URL")
void removeCrypto_withDeleteData_includesQueryParam() {
    when(serviceClient.deleteRaw(contains("deleteData=true"))).thenReturn("{\"deleted\":true}");
    Response response = proxyResource.removeCrypto("BTCUSDT", true);
    assertThat(response.getStatus()).isEqualTo(200);
}
```
