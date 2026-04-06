# Spec: Fix SIRENE Lookup by SIREN to Return Address

## Status: Draft
## Date: 2026-04-06

---

## 1. Problem

The `lookupBySiren` method in `SireneApiClient` calls the `/siren/{siren}` endpoint. This endpoint returns the legal unit (`uniteLegale`) but **no establishment-level data** — specifically, no address. The method hardcodes `address = null` on every result.

This creates a functional gap:

| Lookup path | Endpoint | Returns address? |
|-------------|----------|-----------------|
| User enters SIREN directly | `GET /siren/{siren}` | No — `address` is always `null` |
| User searches by name + city | `GET /siret?q=...` | Yes — `adresseEtablissement` is present |

**Impact:**

- Address is a legal requirement on French invoices (Article L441-9 Code de commerce). Users who enter their SIREN directly at onboarding never get their address auto-populated.
- `PdfGenerator` silently skips the address block when `null`, producing non-compliant invoices.
- The existing `lookup_success.json` test fixture already contains address data in an `etablissementSiege` sub-object, but the `parseSirenResponse` method ignores it entirely — the fixture does not match the real `/siren` response structure.

---

## 2. Solution

Replace the `/siren/{siren}` call with a `/siret` search filtered by SIREN number and restricted to the siege (headquarters) establishment:

```
GET /siret?q=siren:{siren} AND etablissementSiege:true&nombre=1
```

This is a single HTTP call that returns the same data as `searchByNameAndCity` — full address, legal name, NAF code, and SIRET — but filtered to exactly one result (the siege establishment for the given SIREN).

**Why this works:**

- The `/siret` search endpoint returns `etablissements[]`, each containing `adresseEtablissement`, `uniteLegale`, and `siret`.
- Filtering by `siren:{siren}` matches all establishments for that SIREN.
- Adding `etablissementSiege:true` restricts to the headquarters.
- `nombre=1` limits to one result (there is exactly one siege per SIREN).
- The `parseSearchResponse` and `parseAddress` methods already exist and handle this response format.

---

## 3. API Details

### 3.1 Request

```
GET {baseUrl}/siret?q=siren:{siren}%20AND%20etablissementSiege:true&nombre=1
```

The query string must be URL-encoded. The `q` parameter value before encoding:

```
siren:{siren} AND etablissementSiege:true
```

### 3.2 Response — SIREN Found (HTTP 200)

```json
{
  "header": {"total": 1, "debut": 0, "nombre": 1},
  "etablissements": [
    {
      "siret": "12345678900012",
      "uniteLegale": {
        "denominationUniteLegale": "MA SOCIETE SAS",
        "activitePrincipaleUniteLegale": "62.01Z"
      },
      "adresseEtablissement": {
        "numeroVoieEtablissement": "12",
        "typeVoieEtablissement": "RUE",
        "libelleVoieEtablissement": "DE LA PAIX",
        "codePostalEtablissement": "75001",
        "libelleCommuneEtablissement": "PARIS"
      }
    }
  ]
}
```

### 3.3 Response — SIREN Not Found (HTTP 200, Empty List)

Unlike the `/siren/{siren}` endpoint which returns HTTP 404 for unknown SIRENs, the `/siret?q=...` search endpoint returns HTTP 200 with an empty result set:

```json
{
  "header": {"total": 0, "debut": 0, "nombre": 0},
  "etablissements": []
}
```

This is the critical behavioral difference: "not found" is **not** signaled by HTTP status code. The code must check for an empty `etablissements` array.

### 3.4 Field Mapping

| `/siret` response field | Maps to | Notes |
|------------------------|---------|-------|
| `etablissements[0].siret` | `SireneResult.siret` | Full 14-digit SIRET |
| `etablissements[0].siret.take(9)` | `SireneResult.siren` | First 9 digits = SIREN |
| `etablissements[0].uniteLegale.denominationUniteLegale` | `SireneResult.legalName` | Company name; falls back to `nom + prenom` for EI |
| `etablissements[0].uniteLegale.activitePrincipaleUniteLegale` | `SireneResult.activityType` | NAF code, mapped via `nafToActivityType()` |
| `etablissements[0].adresseEtablissement.*` | `SireneResult.address` | Parsed via existing `parseAddress()` |

---

## 4. Implementation

### 4.1 `SireneApiClient.lookupBySiren` — Rewrite

Replace the current implementation:

```kotlin
override suspend fun lookupBySiren(siren: Siren): DomainResult<SireneResult> =
    try {
        val rawQuery = "siren:${siren.value} AND etablissementSiege:true"
        val encodedQuery = URLEncoder.encode(rawQuery, StandardCharsets.UTF_8)
        val url = "$baseUrl/siret?q=$encodedQuery&nombre=1"
        val response = httpExecutor.get(url)
        when {
            response.statusCode == 200 -> parseLookupResponse(response.body, siren)
            else -> {
                log.warn("SIRENE lookup failed for {}: HTTP {}", siren.value, response.statusCode)
                DomainResult.Err(DomainError.SireneLookupFailed("HTTP ${response.statusCode}: ${response.body}"))
            }
        }
    } catch (e: SireneTokenRefreshException) {
        DomainResult.Err(DomainError.SireneLookupFailed(e.message ?: "Token refresh failed"))
    }
```

Key changes:
- URL changed from `/siren/{siren}` to `/siret?q=siren:{siren} AND etablissementSiege:true&nombre=1`
- The `404 → SirenNotFound` branch is removed (the `/siret` search never returns 404 for "not found" — it returns 200 with empty results)
- Delegates parsing to a new `parseLookupResponse` method

### 4.2 New `parseLookupResponse` Method

```kotlin
private fun parseLookupResponse(body: String, requestedSiren: Siren): DomainResult<SireneResult> =
    try {
        val root = json.parseToJsonElement(body).jsonObject
        val etablissements = root["etablissements"]?.jsonArray
        if (etablissements == null || etablissements.isEmpty()) {
            return DomainResult.Err(DomainError.SirenNotFound(requestedSiren))
        }
        val etab = etablissements[0].jsonObject
        val siretValue = etab["siret"]?.jsonPrimitive?.content
            ?: return DomainResult.Err(DomainError.SireneLookupFailed("Missing siret in response"))
        val ul = etab["uniteLegale"]?.jsonObject
            ?: return DomainResult.Err(DomainError.SireneLookupFailed("Missing uniteLegale in response"))
        val legalName = extractLegalName(ul)
        val nafCode = ul["activitePrincipaleUniteLegale"]?.jsonPrimitive?.content
        val address = etab["adresseEtablissement"]?.jsonObject?.let { parseAddress(it) }
        DomainResult.Ok(
            SireneResult(
                legalName = legalName,
                siren = Siren(siretValue.take(9)),
                siret = Siret(siretValue),
                address = address,
                activityType = nafCode?.let { nafToActivityType(it) },
            ),
        )
    } catch (e: Exception) {
        DomainResult.Err(DomainError.SireneLookupFailed("Parse error: ${e.message}"))
    }
```

This method reuses `extractLegalName`, `parseAddress`, and `nafToActivityType` — all of which already exist and are tested via `searchByNameAndCity`.

### 4.3 Delete `parseSirenResponse`

The old `parseSirenResponse` method is no longer called and should be removed.

---

## 5. What Does NOT Change

| Component | Why unchanged |
|-----------|---------------|
| `SirenePort` interface | Method signature `lookupBySiren(Siren): DomainResult<SireneResult>` is the same |
| `SireneResult` data class | Already has `address: PostalAddress?` — it simply gets populated now |
| `SireneHttpExecutor` | Same `get(url)` interface, just called with a different URL |
| `searchByNameAndCity` | Untouched — already uses `/siret` endpoint |
| `parseAddress` | Reused as-is |
| `extractLegalName` | Reused as-is |
| `nafToActivityType` | Reused as-is |
| `SetupProfile` use case | Already reads `sireneResult.address` and stores it — works once it is non-null |
| `PdfGenerator` | Already renders address when present — works once it is non-null |
| `DomainError.SirenNotFound` | Same error type, just triggered by empty result set instead of HTTP 404 |
| `DomainError.SireneLookupFailed` | Same error type |

---

## 6. Error Handling

| Scenario | HTTP Status | `etablissements` | Result |
|----------|-------------|-------------------|--------|
| SIREN found | 200 | `[{...}]` (1 element) | `DomainResult.Ok(SireneResult(...))` with address populated |
| SIREN not found | 200 | `[]` (empty) | `DomainResult.Err(DomainError.SirenNotFound(siren))` |
| `etablissements` key missing | 200 | absent | `DomainResult.Err(DomainError.SirenNotFound(siren))` — treated as empty |
| API error | 500, 503, etc. | — | `DomainResult.Err(DomainError.SireneLookupFailed("HTTP {status}: {body}"))` |
| Token expired | — | — | `DomainResult.Err(DomainError.SireneLookupFailed("Token refresh failed: ..."))` |
| Malformed JSON | 200 | — | `DomainResult.Err(DomainError.SireneLookupFailed("Parse error: ..."))` |

---

## 7. Test Changes

### 7.1 Fixture Update: `lookup_success.json`

Replace the current `/siren` response format with the `/siret` search response format. The fixture must match the actual API response structure:

```json
{
  "header": {"total": 1, "debut": 0, "nombre": 1},
  "etablissements": [
    {
      "siret": "12345678900012",
      "uniteLegale": {
        "denominationUniteLegale": "MA SOCIETE SAS",
        "activitePrincipaleUniteLegale": "62.01Z"
      },
      "adresseEtablissement": {
        "numeroVoieEtablissement": "12",
        "typeVoieEtablissement": "RUE",
        "libelleVoieEtablissement": "DE LA PAIX",
        "codePostalEtablissement": "75001",
        "libelleCommuneEtablissement": "PARIS"
      }
    }
  ]
}
```

### 7.2 Fixture Update: `lookup_not_found.json`

Replace the 404-style response with the empty-list 200 response:

```json
{
  "header": {"total": 0, "debut": 0, "nombre": 0},
  "etablissements": []
}
```

### 7.3 Fixture Update: `lookup_ceased.json`

A ceased enterprise still exists in the SIRENE database — it returns a result with `etatAdministratifEtablissement: "F"`. For now, treat it the same as a not-found (empty result). Replace with the same empty-list format:

```json
{
  "header": {"total": 0, "debut": 0, "nombre": 0},
  "etablissements": []
}
```

**Note:** distinguishing "ceased" from "not found" is a possible future enhancement (out of scope for this fix).

### 7.4 `FakeSireneHttpExecutor` Update

Change the `LookupNotFound` and `LookupCeased` scenarios from HTTP 404 to HTTP 200 (since the `/siret` search returns 200 with empty results for not-found):

```kotlin
SireneScenario.LookupNotFound -> SireneHttpResponse(200, loadFixture("sirene/lookup_not_found.json"))
SireneScenario.LookupCeased -> SireneHttpResponse(200, loadFixture("sirene/lookup_ceased.json"))
```

### 7.5 `SireneApiClientTest` Updates

| Test | Change |
|------|--------|
| `lookupBySiren returns Ok with company data on 200` | Update inline JSON to `/siret` response format. **Replace `assertNull(data.address)`** with assertions that `address` is populated (street, postalCode, city). |
| `lookupBySiren returns Ok with EI name from nom and prenom` | Same: update inline JSON to `/siret` format with `adresseEtablissement`. |
| `lookupBySiren returns Err SirenNotFound on 404` | **Rename** to `lookupBySiren returns Err SirenNotFound on empty result`. Change from HTTP 404 response to HTTP 200 with `{"etablissements": []}`. |
| `lookupBySiren returns Err SireneLookupFailed on 500` | Unchanged — HTTP 500 is still an error. |
| `lookupBySiren returns Err SireneLookupFailed on 401` | Unchanged. |
| `lookupBySiren calls correct URL` | Update assertion from `contains("/siren/123456789")` to `contains("/siret?q=")` and verify the query contains `siren:123456789`. |
| `lookupBySiren maps manufacturing NAF code to BIC_VENTE` | Update inline JSON to `/siret` format. |
| `lookupBySiren maps legal services NAF code to BNC` | Update inline JSON to `/siret` format. |
| `lookupBySiren returns null activityType when NAF code absent` | Update inline JSON to `/siret` format. |
| `lookupBySiren propagates token refresh failure` | Unchanged — exception handling is the same. |

### 7.6 `SireneApiContractTest` Updates

| Test | Change |
|------|--------|
| `lookupBySiren address is assembled correctly` | This test already asserts `assertNotNull(sireneResult.address)`. It will now **pass** (currently it would fail since the real code returns null — this test was written against the fixture, not the actual behavior). |
| All other contract tests | Should pass after fixture updates with no assertion changes. |

### 7.7 `SireneApiLiveTest` Update

Add an address assertion to the live test:

```kotlin
@Test
fun `live lookup returns valid result for known SIREN`() = runBlocking {
    val result = client.lookupBySiren(Siren("552032534")) // SOCIETE GENERALE
    assertIs<DomainResult.Ok<*>>(result)
    val sireneResult = (result as DomainResult.Ok).value
    assertTrue(sireneResult.legalName.isNotBlank())
    assertNotNull(sireneResult.address)
    assertTrue(sireneResult.address!!.postalCode.isNotBlank())
    assertTrue(sireneResult.address!!.city.isNotBlank())
}
```

---

## 8. Files to Change

| File | Change |
|------|--------|
| `infrastructure/sirene/SireneApiClient.kt` | Rewrite `lookupBySiren` to use `/siret` search. Add `parseLookupResponse`. Delete `parseSirenResponse`. |
| `test/resources/fixtures/sirene/lookup_success.json` | Replace with `/siret` response format (with `etablissements[]` and `adresseEtablissement`) |
| `test/resources/fixtures/sirene/lookup_not_found.json` | Replace with empty-list 200 response |
| `test/resources/fixtures/sirene/lookup_ceased.json` | Replace with empty-list 200 response |
| `test/kotlin/mona/integration/IntegrationTestBase.kt` | Change `LookupNotFound` and `LookupCeased` HTTP status from 404 to 200 |
| `test/kotlin/mona/infrastructure/SireneApiClientTest.kt` | Update all `lookupBySiren` tests: new JSON format, address assertions, URL assertion |
| `test/kotlin/mona/integration/sirene/SireneApiLiveTest.kt` | Add address assertions to live test |

No new files. No new dependencies. No domain changes.

---

## 9. Validation

1. `./gradlew build` — all tests pass.
2. `./gradlew ktlintCheck` — no lint violations.
3. Verify `SireneApiContractTest.lookupBySiren address is assembled correctly` passes (it currently cannot pass with the real `lookupBySiren` code).
4. (Optional) `LIVE_API_TESTS=true ./gradlew test --tests '*SireneApiLiveTest*'` — live test confirms address is populated for a known SIREN.
