# EDI Converter — Maturity Gap Analysis & Map Audit

**Prepared:** 2026-04-22
**Reviewer:** Claude (tester session)
**Scope:** `edi-converter` service (port 8095) + all conversion maps (classpath + DB)
**Stack verified on:** R134AJ (`b8fa5db6`, post-Redis-retirement 4-container default)
**Goal per Roshan:** "Our converter should be a *real* EDI converter for *all* EDI formats for *all* message types."

---

## TL;DR — where we are on the vision

| Axis | State | Grade |
|---|---|---|
| **Format parsing engine** | Real parsing for 11 formats (X12, EDIFACT, HL7, SWIFT MT, NACHA, BAI2, ISO 20022, FIX, PEPPOL, TRADACOMS, JSON/XML fallback) with delimiter auto-detect, release-char handling (EDIFACT/HL7), envelope extraction, loop trees. Unit-tested. | **8 / 10** — Mature |
| **Mapping engine architecture** | Cascade resolver (partner → trained → standard), AI training integration, bidirectional flag, code-table lookups, transform library (COPY/TRIM/DATE_FORMAT/LOOKUP/CONDITIONAL/CONCAT/SUBSTRING/PAD/ZERO_PAD/UPPER/LOWER). Non-empty, type-safe map data model. | **7 / 10** — Sound |
| **Map coverage vs. "all formats / all message types"** | Only **31** standard maps covering **16 unique message-type pairs**. Missing **~80–90%** of production-scope X12 transaction sets, essentially all of HL7 v2, most of SWIFT MT, most of ISO 20022, most of EDIFACT. Zero HIPAA 5010 claim types beyond 837P. | **2 / 10** — Severely inadequate |
| **Map quality (field-level depth)** | No map has literally empty `sourcePath`/`targetPath` — so no "fake-fake" map. BUT almost every map is **under-specified by 30–70%** against the real standard's mandatory-or-commonly-used element set. X12_850 has 31 total field mappings; a production PO needs ~60–80. X12_837P has 54; HIPAA 5010 837P has ~250 data elements. | **3 / 10** — Stub-grade |
| **Acknowledgment generation** | 997 / 999 / CONTRL can be **parsed** (inbound maps exist) but **no outbound generator** for any ack type. | **0 / 10** — Missing |
| **Validation & compliance** | Structural checks (envelope presence, segment order, SE segment-count vs. actual, control-number matching) are real. No code-list enforcement. No per-version cardinality rules. No HIPAA 5010 implementation-guide rules. No date/time range validation. | **3 / 10** — Minimal |
| **Observability** | Zero custom metrics (no `@Timed`/`@Counted`). No MDC/trackId propagation. No DLQ. Health/metrics actuator endpoints are exposed but uninstrumented for conversion workload. | **1 / 10** — Blind |
| **Partner customization** | Per-partner map column exists (`partner_id` on `edi_conversion_maps`). Resolver looks up partner maps first. `partner_profiles` endpoints exist. **Currently 0 partner maps in the DB** (`SELECT COUNT(*) FROM edi_conversion_maps → 0`). | **2 / 10** — Plumbed but unused |

**Bottom line:** The converter has a **well-engineered parser and a plausible mapping framework**, but the actual mapping catalog is a **demo-grade sample set, not a production catalog**. Calling it "real EDI conversion for all formats and message types" today would misrepresent ~85% of the scope.

---

## 1. Service surface (how callers reach it)

### Module + runtime
- Location: [`edi-converter/`](edi-converter/)
- Spring Boot app: [`edi-converter/src/main/java/com/filetransfer/edi/EdiConverterApplication.java:14-16`](edi-converter/src/main/java/com/filetransfer/edi/EdiConverterApplication.java#L14-L16)
- Port: `8095` (compose)
- Security auto-config disabled per [`edi-converter/src/main/resources/application.yml:31`](edi-converter/src/main/resources/application.yml#L31)
- Max upload size: 100 MB

### REST surface — 40 endpoints
Primary controller: [`edi-converter/src/main/java/com/filetransfer/edi/controller/EdiConverterController.java:21`](edi-converter/src/main/java/com/filetransfer/edi/controller/EdiConverterController.java#L21) under `/api/v1/convert`.

| Group | Endpoints |
|---|---|
| Core conversion | `detect`, `parse`, `convert`, `convert/file` |
| Validation | `validate`, `validate/file` |
| Explanation / diff | `explain`, `diff`, `canonical` |
| Streaming (StAX-style) | `stream`, `stream/file` |
| Self-healing / compliance | `heal`, `compliance` |
| Template library | `templates`, `templates/{id}/generate` |
| Partner profiles (5) | CRUD + `partners/{id}/analyze` + `partners/{id}/apply` |
| AI mapping (3) | `mapping/smart`, `mapping/generate`, `mapping/schema` |
| Trained map flow (8) | `convert/trained`, `convert/trained/check`, `test-mappings`, `invalidate`, `invalidate-all`, `list`, `cache-stats`, `create` |
| Document-type maps (3) | `convert/map`, `maps`, `maps/{mapId}` |
| NL creation | `create` (English → EDI) |
| Metadata | `formats`, `health` |

### Upstream callers
- **flow-engine** — `CONVERT_EDI` step type, dispatched by [`config-service/.../controller/FileFlowController.java`](config-service/src/main/java/com/filetransfer/config/controller/FileFlowController.java) with `targetFormat` parameter
- **screening-service** — uses EDI format detection
- **ai-engine** (port 8091) — trained map lookup via `http://ai-engine:8091/api/v1/edi/training/maps/lookup`, called from [`edi-converter/.../service/TrainedMapConsumer.java:43-44`](edi-converter/src/main/java/com/filetransfer/edi/service/TrainedMapConsumer.java#L43-L44)

---

## 2. Parsing + conversion core — mature

### Formats advertised (from `GET /api/v1/convert/formats`)
```
inputFormats : X12, EDIFACT, TRADACOMS, SWIFT_MT, HL7, NACHA, BAI2, ISO20022, FIX, PEPPOL, AUTO
outputFormats: JSON, XML, CSV, YAML, FLAT, TIF, X12, EDIFACT, HL7, SWIFT_MT
claims       : "11 input × 10 output = 110 paths"
```

Parser: [`edi-converter/.../parser/UniversalEdiParser.java`](edi-converter/src/main/java/com/filetransfer/edi/parser/UniversalEdiParser.java).

### Real parsing, not stubbed
- **X12** (lines 126–231): ISA envelope validated at 106 chars, delimiters extracted from positions 3/104/105 + element 11. Composite-element split. Loop-ID assignment via [`X12LoopDefinitions`](edi-converter/src/main/java/com/filetransfer/edi/parser/X12LoopDefinitions.java).
  - **Gap:** X12 release-character support is absent. If a segment ever legitimately contains the delimiter escaped, it will be mis-parsed.
  - **Gap:** X12 version is hardcoded `"005010"` at line 224 — the parser never reads `GS-08` to infer real release.
- **EDIFACT** (lines 391–478): UNA service-string parsed, release char honored, composites split respecting escapes. UNB/UNH/UNT/UNZ extracted.
- **HL7 v2** (lines 645–715): MSH encoding-characters properly extracted (`^~\&`), component/repetition/escape handling.
- **SWIFT / NACHA / ISO 20022 / FIX / PEPPOL / TRADACOMS**: format-appropriate tag/record/element parsing.

### Validation — structural only
Engine: [`SmartValidator.java:23-213`](edi-converter/src/main/java/com/filetransfer/edi/service/SmartValidator.java#L23) + [`BusinessRuleEngine.java:10-150`](edi-converter/src/main/java/com/filetransfer/edi/service/BusinessRuleEngine.java#L10).
- ✅ Envelope presence (ISA/GS/ST/SE/GE/IEA for X12; UNB/UNH/UNT/UNZ for EDIFACT)
- ✅ Segment-count integrity (SE-01 vs. actual)
- ✅ Control-number matching (X12-001/002/003)
- ❌ Code-list enforcement (no bundled X12 / EDIFACT / HL7 code tables in library form — only embedded in individual maps)
- ❌ Version-specific cardinality rules (only `005010` / `004010` registered in `X12VersionRegistry`)
- ❌ Date / time range + format validation (date validation is delegated to map `DATE_FORMAT` transforms at conversion time, not validation time)
- ❌ HIPAA 5010 implementation-guide rules (mandatory for healthcare in production)
- ❌ ISA / GS / UNB qualifier cross-check against partner profile

### Generation — parser-only today
There is **no X12 / EDIFACT / HL7 emitter** that produces on-the-wire EDI from an internal canonical document. The "bidirectional" flag on maps is honored only via the `BIDI_REV` reverse-direction convention, which simply swaps source/target in the JSON map — it does **not** add the syntactic concerns of re-emitting an EDI envelope (control numbers, segment terminators, delimiter normalization, segment ordering rules).

Consequences:
- `/api/v1/convert/templates/{id}/generate` and `/create` (NL→EDI) are the only "outbound EDI" paths and they use `TemplateLibrary` / `NaturalLanguageEdiCreator`, not the map pipeline.
- **No functional acknowledgment generation** (997 / 999 / CONTRL / APERAK) — critical miss for any production EDI trading-partner relationship.

---

## 3. Mapping engine — sound architecture, thin catalog

### Data model — [`ConversionMapDefinition.java:1-56`](edi-converter/src/main/java/com/filetransfer/edi/model/ConversionMapDefinition.java#L1-L56)
```
ConversionMapDefinition {
  mapId, name, version, sourceType, targetType, sourceStandard, targetStandard,
  bidirectional, description, status, confidence,
  fieldMappings : List<FieldMapping> { sourcePath, targetPath, transform,
                                       transformConfig, defaultValue, condition,
                                       required, confidence }
  loopMappings  : List<LoopMapping>  { sourceLoop, targetArray, fieldMappings, filter }
  codeTables    : Map<String, List<CodeTableEntry>> { sourceCode, targetCode, description }
}
```

### Resolver cascade — [`MapResolver.java:65-96`](edi-converter/src/main/java/com/filetransfer/edi/service/MapResolver.java#L65-L96)
1. **Partner custom** (DB lookup on `edi_conversion_maps` with `partner_id`) — 0 rows in DB today
2. **Trained** (fetched from ai-engine, cached 5 min in-memory + disk fallback at `${EDI_TRAINED_MAPS_DIR:./data/trained-maps}`) — no training pipeline active
3. **Standard** (31 classpath JSONs under `edi-converter/src/main/resources/maps/standard/`)

The resolver is correct and testable. The **problem is the catalog that falls through to tier 3**: it's undersized and under-specified.

---

## 4. Map catalog audit — the core finding

### Runtime exposes 35 maps (31 JSONs + 4 auto-generated `BIDI_REV`)
Listed via `GET /api/v1/convert/maps`. All have `category: STANDARD`, `confidence: 1.0`, `partnerId: null`.

### "Fake map" check — nothing literally empty
Grep-style audit (`sourcePath == "" || targetPath == ""`): **zero** maps fail this. So there is no literally-empty map in the catalog.

### Real risk: **severe under-specification**
A map with 31 total field mappings for an X12 850 Purchase Order is not "fake" in the empty-fields sense, but it also cannot convert a real-world PO. A trading partner's 850 routinely carries:
- **Header:** BEG (6 elements), CUR, REF (multi-occurrence), PER (multi), DTM (multi), FOB, ITD (multi), IT1, N9 references, PID at header, MEA, SAC, TD1/TD5, CTT. Our map covers 13 of these header elements.
- **N1 loop (parties):** N1, N2, N3 (multi), N4, REF, PER, FOB. Multi-instance (BY, SE, ST, BT, RI, SF, …). Our map covers 10 elements, no REF/PER at party level.
- **PO1 loop (line items):** PO1, PID (multi), PO3, MEA, CTP, TAX, REF, SAC, ITD, DTM, FOB, TD1/TD5, SCH (delivery schedule sub-loop), N9. Our map covers 8 elements — no SCH, no CTP, no PID multi.
- **CTT summary + AMT.** Our map covers CTT.01 only.

An X12 850 at an enterprise retailer / 3PL / logistics partner typically has **60–80 mapped elements end-to-end**. Ours has **31**. That's ~40% coverage. Same shape across the catalog.

### Per-map depth (total field mappings, including loop interiors)

| Map | Top | Loop | **Total** | Loops | CodeTbls | Realistic minimum | Verdict |
|---|---:|---:|---:|---:|---:|---:|---|
| BAI2_STATEMENT→INHOUSE_BANK_STATEMENT | 21 | 7 | **28** | 2 | 4 | ~50 | under-spec 45% |
| EDIFACT_CONTRL→INHOUSE_ACK | 9 | 12 | **21** | 2 | 3 | ~30 | acceptable-minimum |
| EDIFACT_DESADV→INHOUSE_SHIP_NOTICE | 21 | 27 | **48** | 3 | 9 | ~80 | under-spec 40% |
| EDIFACT_INVOIC→INHOUSE_INVOICE | 22 | 32 | **54** | 4 | 10 | ~90 | under-spec 40% |
| EDIFACT_ORDERS→INHOUSE_PO | 19 | 25 | **44** | 2 | 8 | ~80 | under-spec 45% |
| EDIFACT_REMADV→INHOUSE_PAYMENT | 19 | 17 | **36** | 2 | 5 | ~55 | under-spec 35% |
| INHOUSE_INVOICE→EDIFACT_INVOIC | 18 | 25 | **43** | 3 | 8 | ~90 | outbound thin |
| INHOUSE_INVOICE→X12_810 | 24 | 18 | **42** | 2 | 5 | ~80 | outbound thin |
| INHOUSE_PO→EDIFACT_ORDERS | 17 | 21 | **38** | 2 | 6 | ~80 | outbound thin |
| INHOUSE_PO→X12_850 | 21 | 18 | **39** | 2 | 5 | ~70 | outbound thin |
| INHOUSE_SHIP_NOTICE→EDIFACT_DESADV | 20 | 22 | **42** | 3 | 9 | ~80 | outbound thin |
| INHOUSE_SHIP_NOTICE→X12_856 | 21 | 21 | **42** | 3 | 7 | ~70 | outbound thin |
| ISO20022_CAMT053→INHOUSE_BANK_STATEMENT | 4 | 35 | **39** | 3 | 3 | ~120 (NtryDtls.TxDtls is rich) | under-spec 65% |
| ISO20022_PACS008→INHOUSE_PAYMENT | 9 | 32 | **41** | 1 | 3 | ~80 | under-spec 50% |
| ISO20022_PAIN001→INHOUSE_PAYMENT | 6 | 36 | **42** | 2 | 3 | ~80 | under-spec 50% |
| NACHA_CCD→INHOUSE_PAYMENT | 23 | 13 | **36** | 2 | 2 | ~50 | acceptable-minimum |
| NACHA_PPD→INHOUSE_PAYMENT | 19 | 13 | **32** | 2 | 2 | ~50 | acceptable-minimum |
| SWIFT_MT103→INHOUSE_PAYMENT | 25 | 0 | **25** | 0 | 2 | ~40 | under-spec 40% |
| SWIFT_MT103↔ISO20022_PACS008 BIDI | 26 | 0 | **26** | 0 | 3 | ~40 | under-spec 35% |
| SWIFT_MT940→INHOUSE_BANK_STATEMENT | 23 | 11 | **34** | 1 | 2 | ~50 | under-spec 30% |
| TRADACOMS_ORDER→INHOUSE_PO | 22 | 18 | **40** | 2 | 3 | ~60 | under-spec 35% |
| X12_810↔EDIFACT_INVOIC BIDI | 14 | 18 | **32** | 2 | 6 | ~70 cross-mapped | under-spec 55% |
| X12_810→INHOUSE_INVOICE | 19 | 19 | **38** | 2 | 5 | ~80 | under-spec 50% |
| X12_820→INHOUSE_PAYMENT | 20 | 18 | **38** | 2 | 7 | ~70 | under-spec 45% |
| **X12_837P→INHOUSE_HEALTHCARE_CLAIM** | 22 | 32 | **54** | 3 | 9 | **~250 (HIPAA 5010)** | **under-spec 80%** |
| X12_850↔EDIFACT_ORDERS BIDI | 13 | 18 | **31** | 2 | 8 | ~70 | under-spec 55% |
| X12_850→INHOUSE_PURCHASE_ORDER | 13 | 18 | **31** | 2 | 5 | ~70 | under-spec 55% |
| X12_856↔EDIFACT_DESADV BIDI | 14 | 20 | **34** | 3 | 9 | ~80 | under-spec 55% |
| X12_856→INHOUSE_SHIP_NOTICE | 18 | 22 | **40** | 3 | 7 | ~80 | under-spec 50% |
| X12_997→INHOUSE_ACKNOWLEDGMENT | 12 | 16 | **28** | 2 | 7 | ~40 | acceptable-minimum |
| X12_999→INHOUSE_ACKNOWLEDGMENT | 9 | 17 | **26** | 2 | 7 | ~40 | acceptable-minimum |

**Worst offender:** `X12_837P→INHOUSE_HEALTHCARE_CLAIM`. HIPAA 5010 837 Professional has **~250 data elements** across ISA / GS / ST / BHT, Loop 1000A/B (submitter/receiver), Loop 2000A/B/C (billing/subscriber/patient), Loop 2010AA/AB/BA/BB/BC/BD, Loop 2300 (claim), Loop 2310A–E (rendering / service facility / supervising / referring / purchased-service providers), Loop 2320 (other subscriber), Loop 2330A–G, Loop 2400 (service line) with its nested SV1/SV5/CR1–CR3/REF/AMT/NTE/LIN/MEA/FRM/K3. Our map hits **54**. In a regulated healthcare payor integration, this is not shippable.

**Also serious:** `ISO20022_CAMT053` top-level `fieldMappings` = 4 (just header). Almost all content is inside the `Stmt` / `Bal` / `Ntry` loops — which themselves undershoot. `NtryDtls.TxDtls` in real camt.053 carries counterparty IDs, remittance structured, mandate, related-parties — our loop has 17 fields there vs. the ~50+ available.

### Where the "fake" perception likely came from
- The top-level `fieldMappings` count visible from `GET /api/v1/convert/maps/{mapId}` can look alarmingly small (4 for CAMT053, 6 for PAIN001, 9 for PACS008, 9 for CONTRL) because **the real content lives inside `loopMappings[*].fieldMappings`**. A UI that shows only top-level `fieldMappings.length` gives a "fake map" impression. This is a UI presentation gap, not a data gap — but the data gap (under-specification) is real too.

Recommended UI fix: show `totalFieldMappings = fieldMappings.length + sum(loopMappings[*].fieldMappings.length)` in the list view.

---

## 5. What's missing — message-type gap analysis

### X12 transaction sets

**Present:** 850, 810, 820, 856, 837P, 997, 999 — 7 transaction types.
**Commonly required in production but absent:**

| Group | Missing | Business impact |
|---|---|---|
| **Healthcare (HIPAA 5010)** | 835 (ERA/payment advice), 834 (benefit enrollment), 270 (eligibility request), 271 (eligibility response), 277 (claim status response), 277CA (claim acknowledgement), 278 (referral/auth), 837I (institutional), 837D (dental), 820-healthcare (premium payment) | **Blocker** for any healthcare trading-partner deal |
| **Supply chain** | 830 (planning schedule), 832 (price/sales catalog), 846 (inventory inquiry/advice), 855 (PO ack), 860 (PO change), 861 (receipt advice), 862 (shipping schedule), 864 (text message), 869 (order status inquiry), 870 (order status report) | **High** — every real retail / wholesale / 3PL relationship needs 855 + 856 + 810 at minimum; 860/865 for change orders |
| **Warehouse / 3PL** | 940 (warehouse shipping order), 943 (warehouse transfer), 944 (warehouse transfer receipt), 945 (warehouse shipping advice), 947 (warehouse inventory adj) | **High** — any 3PL / logistics engagement requires 940/945 round-trip |
| **Transportation** | 204 (motor carrier load tender), 210 (motor carrier invoice), 214 (transportation carrier shipment status), 404 (rail carrier shipment info), 858 (shipment info), 859 (freight invoice), 990 (response to load tender) | **High** — carrier integrations |
| **Retail / POS** | 852 (product activity data), 867 (product transfer/resale report), 877 (manufacturer coupon), 880 (grocery products invoice), 894 (DSD receipt/adjustment advice) | **Medium** — retailer-specific |
| **Finance / utility** | 820 is present; missing 811 (consolidated svc invoice), 824 (application advice), 828 (debit authorization), 829 (payment cancellation), 831 (application control totals), 842 (nonconformance report) | **Medium** |

**Coverage:** **~7 of ~45 common production sets = 16%.**

### EDIFACT message types

**Present:** ORDERS, INVOIC, DESADV, REMADV, CONTRL — 5 messages.
**Missing, commonly required:**
- **Commerce:** ORDRSP (order response / 855-equivalent), ORDCHG (order change), DELFOR (delivery forecast), DELJIT (delivery just-in-time), INVRPT (inventory report), RECADV (receipt advice), PRICAT (price/sales catalog), QUOTES (quotation)
- **Finance:** PAYMUL (multiple payment order), FINPAY, BANSTA (banking status), CREMUL (multi credit advice), DEBMUL (multi debit advice), FINSTA (financial statement)
- **Transport:** IFTMAN (arrival notice), IFTMBC (booking confirmation), IFTMBF (firm booking), IFTMBP (provisional booking), IFTMCS (instruction contract status), IFTMIN (forwarding instructions — **present**), IFTMIT (transport instruction), IFTSTA (multimodal status report), IFCSUM (forwarding summary), COARRI, COPARN, COPRAR
- **Ack / error:** APERAK (application error & acknowledgement), CONTRL — CONTRL is present

**Coverage:** **~5 of ~40 common messages = 12%.**

### HL7 v2 — **zero maps present**

The parser understands MSH-delimited HL7 v2 and extracts sender / receiver / encoding chars. But there are no conversion maps at all. Common HL7 v2 production message types (all missing):
- **ADT** (A01 admit, A02 transfer, A03 discharge, A04 register, A08 update, A11 cancel admit, A13 cancel discharge, A40 merge patient)
- **ORM/OMP/OML/OMI** (order messages)
- **ORU** (observation results — R01 unsolicited)
- **SIU** (scheduling info — S12 new, S14 modify)
- **MDM** (medical document management — T02 notification w/ content)
- **DFT** (detailed financial transactions — P03 post detail)
- **BAR** (billing account — P01 add, P05 update)
- **ACK** (general ack)
- **QRY / DSR** (query / display response)
- FHIR bridging — not in scope today

**Coverage:** **0%.**

### SWIFT MT

**Present:** MT103 (customer credit transfer), MT940 (customer statement).
**Missing:**
- **Payments:** MT101 (request for transfer), MT102 (multiple credit transfer), MT104 (direct debit), MT202 (general FI transfer), MT205 (FI transfer execution), MT210 (notice to receive)
- **Cancel / query:** MT192 / MT292 (request for cancellation), MT196 / MT296 (answers), MT199 / MT299 (free format)
- **FX / treasury:** MT300 (FX confirmation), MT320 (fixed loan/deposit confirmation)
- **Confirm / notify:** MT900 (debit confirmation), MT910 (credit confirmation), MT942 (interim statement), MT950 (statement message)

**Coverage:** **2 of ~15 common production MT types = ~13%.**

### ISO 20022

**Present:** pain.001 (credit transfer init), pacs.008 (FI credit transfer), camt.053 (bank-to-customer statement).
**Missing high-value:**
- **Payments (pacs.*):** pacs.002 (payment status report), pacs.003 (direct debit), pacs.004 (payment return), pacs.009 (FI credit transfer), pacs.028 (status request)
- **Cash mgmt (camt.*):** camt.052 (account report), camt.054 (debit/credit notification), camt.056 (return of funds), camt.029 (resolution), camt.060 (account reporting request)
- **Initiation (pain.*):** pain.002 (status report), pain.007 (reversal), pain.008 (direct debit init)
- **Admin (acmt.*, setr.*):** acmt.023 (identification verification), setr.010 (subscription order)

**Coverage:** **3 of ~25 common ISO 20022 messages in use today = ~12%.**

### Other standards — tangential coverage

- **PEPPOL / UBL** — format detection works, but **no maps**. The EU 2026 e-invoicing mandate requires PEPPOL BIS Billing 3.0 (Invoice, CreditNote, Despatch Advice) — all missing as maps.
- **cXML** (Ariba / SAP procurement) — not parsed, no maps.
- **OAGIS** (Open Applications Group) — not parsed, no maps.
- **EANCOM** (GS1 EDIFACT subset) — would ride on EDIFACT; no maps.

---

## 6. Acknowledgment generation — flagship gap

A real EDI converter emits ACKs on a timer or on-receipt:
- **X12:** TA1 (interchange-level syntax ack), 997 (functional-group-level), 999 (implementation-level for HIPAA)
- **EDIFACT:** CONTRL (syntax & service), APERAK (application error)
- **HL7 v2:** ACK^*^ACK

**Today:** Zero generators. All four of the inbound maps (997, 999, CONTRL, APERAK-parsing) exist to **consume** received acks — none emits. A production trading partner expecting a 997 within 24–72 hours will time out their TPA.

**Required work:**
1. `Ack997Generator` / `Ack999Generator` / `CONTRLGenerator` classes that accept `(originalDocumentEnvelope, validationResult)` → produce serialized EDI with:
   - AK1 (group response header, matching GS-01 / GS-06)
   - AK2 (per-transaction-set response) + AK3 / AK4 (data element notes) if errors
   - AK5 (transaction set response trailer) + AK9 (functional group response trailer)
2. Integration in the CONVERT_EDI flow step (or a new `GENERATE_ACK` step) so the flow engine can enqueue the ack for outbound.
3. Trading-partner profile toggle (some partners don't want 999, only 997).

---

## 7. Validation — not production-grade for regulated industries

Missing for the healthcare vertical (HIPAA 5010):
- Implementation-guide (IG) enforcement — WPC Washington Publishing Company element cardinality and usage rules per loop / per version (004010, 005010).
- TR3 (type 3 technical report) rule evaluation.
- Code-list enforcement from the **bundled** X12 EDI data dictionary for each version (currently maps embed their own mini-lookup-tables only).
- NPI (National Provider Identifier) Luhn checksum check.
- CPT / HCPCS / ICD-10 code validation.
- Date-range sanity (service-from ≤ service-to ≤ statement-to ≤ claim-submission-date).

Missing for supply chain:
- GS1 GTIN (14-digit product identifier) checksum.
- Qualifier-code cross-validation (e.g., for N1-01, allowed qualifiers depend on document type).

Missing generally:
- Cardinality rules ("segment X is mandatory with M>0 occurrence ≤ 3 inside loop Y") per version.
- Syntax rule dependencies ("if element X present then element Y must be present").

### Verdict for the rubric
Good enough for a demo / integration test. Not safe for production compliance-gated data (healthcare, banking audit, DoD logistics).

---

## 8. Observability — operationally blind

Grep results:
```
@Timed / @Counted / MeterRegistry in edi-converter/src/main/java  → 0 matches
MDC.put / trackId / traceId                                       → 0 matches
Counter.builder / Timer.builder                                   → 0 matches
DLQ / deadLetter                                                  → 0 matches
```

Impact:
- **No conversion-count / conversion-duration / error-rate / per-partner / per-message-type metric.** Ops has no signal.
- **No trackId propagation into logs from the flow engine.** Correlating "my 856 didn't convert" to a log line is manual.
- **No DLQ for failed conversions.** A failed map apply emits a log line and returns an error map — the flow engine sees a 500; there's no persistent "here's the document that failed, re-drive this when you fix the map" table.

Minimum to be production-ready:
- Micrometer `Counter`s: `edi_conversions_total{result,source_type,target_type,partner_id}`.
- `Timer`: `edi_conversion_duration_seconds{…}`.
- `Gauge`: `edi_maps_loaded{category}`.
- MDC propagation from `X-Trace-Id` / X-TrackId headers; include `trackId`, `partnerId`, `sourceMessageType` in every log line.
- A `edi_conversion_failures` PG table with `(track_id, partner_id, source_type, target_type, error_message, raw_payload_ref, created_at)` + a replay endpoint.

---

## 9. DB-side state today

```
SELECT COUNT(*) FROM edi_conversion_maps              → 0
SELECT COUNT(*) FROM edi_training_samples             → 0
SELECT COUNT(*) FROM edi_training_sessions            → 0
SELECT COUNT(*) FROM edi_mapping_correction_sessions  → 0
```

**Zero partner maps, zero training samples, zero correction sessions.** Every conversion today falls through to the 31 classpath standard maps. The partner-customization and AI-training plumbing works architecturally but has never been exercised with real data.

Schema references:
- [`V65__create_edi_training_tables.sql`](shared/shared-platform/src/main/resources/db/migration/V65__create_edi_training_tables.sql) — creates the 3 training-related tables
- [`V16__edi_mapping_correction_sessions.sql`](shared/shared-platform/src/main/resources/db/migration/V16__edi_mapping_correction_sessions.sql) — 24-hour-TTL session table for active map edits
- [`V66__partner_map_columns.sql`](shared/shared-platform/src/main/resources/db/migration/V66__partner_map_columns.sql) — adds `parent_map_id`, `status` to `edi_conversion_maps`

---

## 10. Severity-ranked gap list (what to build next)

### Blockers for "real EDI converter for all formats + all message types"

1. **X12 HIPAA 5010 claim set** — 835, 834, 270, 271, 277, 277CA, 278, 837I, 837D. Without this, no healthcare deals are possible. Each map is roughly 3× the depth of a supply-chain PO map (~150–250 data elements).
2. **X12 supply-chain round-trip** — 855, 860, 861, 862, 864, 830, 832, 846, 869/870. Without these, retail / wholesale / 3PL deals can only do a one-way flow.
3. **HL7 v2 coverage from zero** — ADT, ORM/OML, ORU, SIU, MDM, DFT, ACK. Every healthcare integration needs these.
4. **X12 functional acknowledgment *generator*** — 997 + 999 + optionally TA1. Without outbound ack, trading partners time out their TPA on you.
5. **EDIFACT CONTRL + APERAK generators** — equivalent of (4) for European partners.
6. **Per-version X12 code-list library** — the WPC data dictionary content (or a subscribable replacement) bundled so validation can enforce code lists without shipping custom `codeTables` in every map.

### High — required for a credible launch

7. **X12 transportation + warehouse sets** — 204 / 210 / 214 / 940 / 945. Carrier + 3PL integrations.
8. **ISO 20022 pacs.002 + camt.054 + camt.052** — payment status + debit/credit notification + account report. Fundamental for any FI integration.
9. **SWIFT MT202 + MT101 + MT900/910** — financial-institution transfer + request-for-transfer + confirmations.
10. **X12 release-character support** — currently absent; edge input will mis-parse.
11. **Automatic X12 version detection** from `GS-08` — today hardcoded `005010`. Partners on 004010 will have subtle wrong-rule validation.
12. **HIPAA 5010 implementation-guide validation rules** — TR3 rules, code-list enforcement, NPI / CPT / ICD-10 checks. Required to sign a BAA.
13. **Observability instrumentation** — Micrometer metrics, MDC propagation, DLQ, replay endpoint.
14. **Depth upgrade on existing maps** — especially X12_837P (22 top-level + 32 loop = 54 today → must reach ~200+ for HIPAA 5010 production), X12_850 (31 → 70+), ISO 20022 CAMT053 (39 → 120+). Rule of thumb: every currently-shipped map is ~50% of its production target.

### Medium — required for breadth

15. **EDIFACT expansion** — ORDRSP, ORDCHG, DELFOR, DELJIT, INVRPT, RECADV, PRICAT, PAYMUL, IFTMAN/BC/BF/CS/IT, IFTSTA, IFCSUM.
16. **PEPPOL UBL maps** — Invoice, CreditNote, DespatchAdvice — for EU 2026 e-invoicing mandate.
17. **SWIFT expansion** — MT103 STP variant, MT104, MT205, MT300, MT942, MT950.
18. **X12 retail** — 852, 867, 877, 880, 894.
19. **cXML + OAGIS + EANCOM parsers + maps** — for procurement integrations (Ariba / SAP / Oracle).
20. **Trading-partner acknowledgment tracking** — sent/received-ack register, retry / timeout policy, overdue-ack alerting.
21. **Map schema validation at load time** — reject a map if `fieldMappings == []`, if `loopMappings[i].fieldMappings == []`, if a `LOOKUP` transform references a missing `codeTables` key, etc. Today the runtime silently applies an empty map.

### Low — quality of life

22. **UI map detail: compute and show `totalFieldMappings` (top + loops)**, not just `fieldMappings.length` — eliminates the "this map looks fake" false positive.
23. **Map authoring tooling** — some kind of assisted authoring flow (maybe via the existing AI path) that bootstraps a 200-field map from a sample pair instead of hand-writing JSON.
24. **Sample EDI fixture library** — real-world redacted X12 / EDIFACT / HL7 samples under `edi-converter/src/test/resources/fixtures/` for round-trip tests across every message type we claim to support.
25. **Partner profile discovery** — `/api/v1/convert/partners/{id}/analyze` exists; wire it to run on the first file we see from a new partner and auto-draft a partner map.

---

## 11. Recommended work-plan staging

| Stage | Timebox | Scope |
|---|---|---|
| **Foundation** | 1 sprint | Observability (metrics + MDC + DLQ), X12 release-char handling, X12 version auto-detect from GS-08, map schema validation at load time, UI map-detail fix. |
| **Ack generators** | 1 sprint | X12 997, X12 999, EDIFACT CONTRL, optional TA1. Flow step `GENERATE_ACK`. Trading-partner ack preference flag. |
| **Healthcare vertical** | 2–3 sprints | 835, 834, 270, 271, 277, 278 maps at ~200-field depth each + HIPAA 5010 IG validation rules + NPI/CPT/ICD-10 checks. One-by-one; each cycle gets runtime-verified with the same rigor as the R134 series. |
| **Supply chain round-trip** | 2 sprints | 855, 860, 861, 862, 830, 832, 846. Depth-upgrade existing 850 / 856 / 810 to ~70 fields each. |
| **Transportation / 3PL** | 2 sprints | 204, 210, 214, 940, 943, 944, 945. |
| **HL7 v2** | 2 sprints | ADT A01/02/03/04/08, ORM, ORU, SIU, MDM, DFT, ACK. |
| **Financial** | 2 sprints | SWIFT MT101, MT202, MT900/910, MT942, MT950 + ISO 20022 pacs.002, camt.052, camt.054. |
| **European mandate** | 1 sprint | PEPPOL UBL Invoice / CreditNote / DespatchAdvice + CIUS profile mappings. |
| **EDIFACT expansion** | 2 sprints | ORDRSP, ORDCHG, DELFOR, DELJIT, INVRPT, RECADV, PRICAT, PAYMUL, IFTM* family. |
| **Procurement** | 1 sprint | cXML + OAGIS (parser + base maps). |

**Rough total:** 16–20 sprints (4–5 months at sprint=1 week) to get from today's 16%-coverage + 50%-depth catalog to a credible "all common formats, all common message types, production-grade" converter. With two engineers in parallel the critical path shortens to ~3 months.

---

## 12. Closing summary

**What's solid today:**
- Universal parser that actually parses 11 formats correctly, with real envelope and loop handling.
- Mapping engine with a clean cascade resolver, bidirectional-aware, transform library, code-table support, AI training hooks.
- Structural validation and control-number integrity checks.
- 40 REST endpoints covering detect, parse, convert, validate, stream, explain, diff, self-heal, compliance, templates, NL creation, and AI mapping.
- Unit-test coverage on the parser + validator + map resolver + converter.

**What's fragile today:**
- **The catalog.** 31 maps, covering ~16% of the real transaction-set universe, averaging ~50% field-level depth against production requirements. Every map currently loaded could be called "authentic" (no literally-empty sources) but also "demo-grade" (insufficient for a real trading partner).
- **Outbound EDI emitters and acknowledgment generators.** Absent entirely.
- **Observability.** Zero custom metrics, zero MDC, zero DLQ.
- **Validation depth.** Structural only; no code-list enforcement, no version-aware IG rules, no healthcare-specific checks.
- **Empirical usage.** Zero partner maps, zero trained maps, zero correction sessions in the DB. The customization plumbing has never been exercised.

**Recommendation:** treat R134 as the plumbing milestone (external-dep retirement **COMPLETE** at R134AJ — that's real) and open a **new R-series (R135?)** explicitly targeting EDI converter maturity, starting with the **Foundation → Acks → Healthcare** staging above. Grade each R-tag with the same rubric used for R134 (Bronze-cap pre-runtime, runtime exercise to earn Silver, Gold only if zero-deps-and-six-axes-preserved).

---

**Report author:** Claude (2026-04-22). Grounded in code inspection at post-R134AJ HEAD (`b8fa5db6`), runtime queries against the live stack (`/api/v1/convert/formats`, `/api/v1/convert/maps`, PG table counts), and per-map JSON audit of all 31 standard maps.
