# R134O — UI bug surfaced during smoke: AI-Suggest-Flow contract mismatch

**Cycle:** R134O (Silver)
**Date:** 2026-04-21
**Reporter:** tester (user navigated to Processing Flows → New Flow → clicked "AI Suggest" button)
**Impact:** Admin UI's AI Suggest feature is broken on every flow-create form.

---

## The error as the admin sees it

Navigated to: `Processing Flows → New Flow → Flow Details (name=hhggh) → Processing Pipeline with 3 steps (PGP Encrypt, GZIP Compress, Route) → clicked "AI Suggest"`.

Response at the bottom of the page:

```
Invalid request body: Cannot deserialize value of type `java.lang.String`
from Array value (token `JsonToken.START_ARRAY`)
at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled);
   line: 1, column: 120]
(through reference chain: java.util.LinkedHashMap["existingSteps"])
```

The admin has no indication of which field is problematic, no retry guidance, and no graceful fallback — the AI Suggest button is unusable until a server-side API change.

---

## Root cause

**UI side** — [ui-service/src/pages/Flows.jsx:2089-2094](../../ui-service/src/pages/Flows.jsx#L2089-L2094) sends a structured payload:

```js
aiSuggestMut.mutate({
  sourceAccountId: form.sourceAccountId || null,
  filenamePattern: form.filenamePattern || null,
  direction: form.direction || null,
  existingSteps: form.steps.map(s => s.type),   // ← ARRAY of step type strings
})
```

**API side** — [ai-engine/src/main/java/com/filetransfer/ai/controller/AiController.java:81-85](../../ai-engine/src/main/java/com/filetransfer/ai/controller/AiController.java#L81-L85):

```java
@PostMapping("/nlp/suggest-flow")
public ResponseEntity<NlpService.FlowSuggestion> suggestFlow(
        @RequestBody Map<String, String> body) {       // ← expects Map<String, String>
    return ResponseEntity.ok(nlpService.suggestFlow(body.get("description")));
}
```

The API signature is `Map<String, String>` — which Jackson interprets as: every value in the map must be a String. When the UI sends `existingSteps: ["ENCRYPT_PGP", "COMPRESS_GZIP", "ROUTE"]`, Jackson attempts to deserialize the JSON Array into a String for that key and throws the error above.

The API only actually consumes `body.get("description")` — every other key in the request body is ignored. The UI's attempt to give it structured context (source account, filename pattern, direction, existing steps) is both rejected AND unused.

**Corroborating evidence** — the original UI API client at [ui-service/src/api/ai.js:15-16](../../ui-service/src/api/ai.js#L15-L16) still sends the old, correct shape:

```js
export const suggestFlow = (description) =>
  sharedAiApi.post('/api/v1/ai/nlp/suggest-flow', { description }).then(r => r.data)
```

So the old UI code path works; the new structured-payload path in Flows.jsx:2089 diverged from the API contract.

---

## Severity

- **UI-visible:** the AI Suggest button is the only interaction available — it throws on every click from this form
- **Functional:** the form is still usable WITHOUT AI Suggest (user can manually add steps + click Create Flow), so flow creation isn't fully broken
- **Quality signal:** Distributed + Integrated vision axes take a small hit because the admin UX has a dead button

Grade this as a **user-facing functional regression** — caught before Silver product-state would otherwise allow Gold.

---

## Recommended fix (dev-side, two options)

### Option A — change the UI to match the old contract (quick)

Revert [Flows.jsx:2089](../../ui-service/src/pages/Flows.jsx#L2089) to send only `{ description: string }` where `description` is a natural-language synthesis of the form state (`"Flow matching ${filenamePattern} from ${sourceAccount}, direction ${direction}, existing steps: ${existingSteps.join(', ')}"`).

Pro: no server change. UI gets a hint.
Con: throws away structured context the server could exploit.

### Option B — expand the API contract (richer)

Change [AiController.java:83](../../ai-engine/src/main/java/com/filetransfer/ai/controller/AiController.java#L83) from `Map<String, String>` to `Map<String, Object>`, and teach `NlpService.suggestFlow` to accept a richer input:

```java
@PostMapping("/nlp/suggest-flow")
public ResponseEntity<NlpService.FlowSuggestion> suggestFlow(
        @RequestBody Map<String, Object> body) {
    // legacy path — old UI
    String description = (String) body.get("description");
    if (description != null) {
        return ResponseEntity.ok(nlpService.suggestFlow(description));
    }
    // structured path — Flows.jsx
    return ResponseEntity.ok(nlpService.suggestFlowStructured(
        (String) body.get("sourceAccountId"),
        (String) body.get("filenamePattern"),
        (String) body.get("direction"),
        (List<String>) body.get("existingSteps")
    ));
}
```

Pro: UI gets real AI suggestions based on form context. Both UI paths keep working.
Con: requires new method on `NlpService`. Bigger diff.

Option B is the right long-term fix — the UI clearly wants smart suggestions, not just a freetext description paraphrase.

---

## Prevention — habit for future cycles

The contract mismatch went un-caught because:
- `mvn test` on ai-engine tests `suggestFlow(String description)` with the old call shape — the UI's new call shape was never exercised in a test
- UI typecheck (if any) doesn't validate against the Java controller's `@RequestBody` type

Simple guard: add a `FlowSuggestRequest` DTO on the Java side (record with explicit fields), and a matching `FlowSuggestRequest` TypeScript type on the UI side imported from a shared schema. OpenAPI docgen would also catch this. Either way, any future payload drift becomes a compile-time failure instead of a runtime 400.

---

## Other UI observations while I was there (side quest — for later cycles)

Not part of this bug; noting so they're not forgotten:

- Flow creation form has "Legacy: Source Path" label — if "Legacy" means deprecated, it should show a deprecation hint rather than just a prefixed label
- `Execution History` row for `TRZMQPQB9JGC` (my R134O test upload) shows `Started: 1/21/1970, 6:32:46 AM` — timestamp epoch-offset bug (should be 2026-04-21)
- Counter "File Processing Flows 7 flows configured(7 active)" — spacing nit before the parenthesis
- `Priority: 100` in Flow Details form — should have a helper showing how priority 100 compares to the seed flows (Archive 50 / EDI Processing 10 / X12 12 / Encrypted 20 / Healthcare 15 / Mailbox 999 / Regression 10). Just "lower numbers match first" isn't enough context.

---

**Report author:** Claude (tester smoke, 2026-04-21).
