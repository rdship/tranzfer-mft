# EDI Converter Maturity Plan

**Status:** PLANNING — Do not implement until Roshan approves  
**Author:** Claude Code / Roshan Dubey  
**Created:** 2026-04-10  
**Service:** edi-converter (port 8095, stateless, NO DB) + ai-engine (port 8091, PostgreSQL)  
**Priority:** CRITICAL — this is the revenue-generating feature

---

## Table of Contents

1. [Part 1: The Problem with the Current Approach](#part-1-the-problem-with-the-current-approach)
2. [Part 2: The Right Mental Model](#part-2-the-right-mental-model)
3. [Part 3: Standard Map Library](#part-3-standard-map-library)
4. [Part 4: Map Architecture](#part-4-map-architecture)
5. [Part 5: AI-Powered Map Creation](#part-5-ai-powered-map-creation)
6. [Part 6: Partner Map Customization](#part-6-partner-map-customization)
7. [Part 7: Comparison Suite Maturity](#part-7-comparison-suite-maturity)
8. [Part 8: UI Redesign for EDI](#part-8-ui-redesign-for-edi)
9. [Part 9: Flow Integration Enhancement](#part-9-flow-integration-enhancement)
10. [Part 10: Implementation Phases](#part-10-implementation-phases)
11. [Part 11: File Inventory](#part-11-file-inventory)

---

## Part 1: The Problem with the Current Approach

### 1.1 Generic Format Conversion Is Not EDI Conversion

The current system treats EDI conversion as a generic format-transform problem. The `/convert`
endpoint accepts raw content and a target format string like `"JSON"`, `"XML"`, or `"CSV"`. This
is fundamentally wrong for production EDI.

**What the current code does:**

```
POST /api/v1/convert/convert
{ "content": "<X12 850>", "target": "JSON" }
```

This parses the X12 850, builds an `EdiDocument` (raw segments), and serializes it to JSON. The
JSON output is a mechanical representation of the segments — it is NOT a business document. No
customer's ERP system can consume a raw segment dump.

**What customers actually need:**

```
POST /api/v1/convert/map
{
  "content": "<X12 850>",
  "sourceType": "X12_850",
  "targetType": "PURCHASE_ORDER_INH",
  "partnerId": "ACME_CORP"
}
```

This takes an X12 850 Purchase Order, applies a field-by-field map, and produces a structured
internal purchase order document that the customer's ERP can import.

**The gap is in the mental model, not just code.** The UI (`Edi.jsx`) has a dropdown for target
format (`JSON`, `XML`, `CSV`, `YAML`, `FLAT`, `TIF`) but no dropdown for document type. A user
converting an X12 850 to "JSON" gets a JSON blob of segments. They need to convert an X12 850 to
their company's purchase order format — and that requires a MAP.

### 1.2 The Canonical Model Is a Bridge, Not a Destination

`CanonicalDocument` is a well-designed internal model. It has `DocumentType` (PURCHASE_ORDER,
INVOICE, SHIPMENT_NOTICE, etc.), `Header`, `LineItem`, `Party`, `MonetaryTotal`, `Reference`,
`DateInfo`, `Contact`, and `Note`. The `CanonicalMapper` converts from any EDI format to this
model and back.

**The problem:** The canonical model is currently exposed to users. The `/canonical` endpoint
returns a `CanonicalDocument` directly. Users should never see the word "canonical" — they should
see their target document type.

The canonical model should be an internal implementation detail. Externally, the system should
speak in terms of document types: "Convert my X12 850 to our internal purchase order format" or
"Convert this EDIFACT ORDERS to an X12 850."

The flow should be:

```
Source Document  -->  [Parser]  -->  EdiDocument
                                         |
                                   [Source Map]
                                         |
                                         v
                                  CanonicalDocument  (internal, invisible to user)
                                         |
                                   [Target Map]
                                         |
                                         v
                                  Target Document  -->  [Serializer]  -->  Output
```

Users configure source type + target type. The system finds the right maps. The canonical model
is just the bridge that makes N-to-M conversion possible without N*M maps.

### 1.3 Map Training Produces Maps but Lacks Production Maturity

The `EdiMapTrainingEngine` in ai-engine implements 5 training strategies:

1. **Exact Value Alignment** — matches identical values between source and target (99% confidence)
2. **Statistical Correlation** — frequency-based matching across 3+ samples
3. **Structural Position Mapping** — same position implies same meaning
4. **Semantic Field Embedding** — n-gram/synonym similarity via `FieldEmbeddingEngine`
5. **Transform Detection** — detects DATE_FORMAT, PAD, TRIM, CONCAT patterns

The training pipeline works: upload samples, train, get a `ConversionMap` with confidence scores.
But production maturity requires:

- **No confidence threshold enforcement.** A map with 40% average confidence is activated with no
  warning. There should be a minimum threshold (e.g., 80%) before a map is production-ready, with
  lower-confidence maps marked as DRAFT.
- **No standard maps ship with the product.** Every customer starts from zero — uploading samples
  and training maps manually. Competitors like Sterling Integrator and Axway ship with hundreds of
  pre-built maps.
- **No map versioning UI.** The `TrainedMapStore` has versioning (version number, active flag,
  rollback) but there is no UI to browse versions, compare them, or promote a specific version.
- **No validation test suite per map.** After training, the system should automatically run the map
  against all training samples and report field-level accuracy. Currently, `testAccuracy` is
  computed during training on the held-out set, but there is no ongoing regression test.
- **No map certification workflow.** Maps should go through DRAFT -> TESTED -> CERTIFIED -> ACTIVE
  states. Currently they go straight to ACTIVE.

### 1.4 Partner Customization Is Manual

The `PartnerProfileManager` in edi-converter stores partner profiles in a `ConcurrentHashMap` — 
no persistence, no DB. Profiles include sender/receiver IDs, format preferences, element rules,
and transform rules. But:

- Profiles are lost on restart (in-memory only)
- There is no link between partner profiles and conversion maps
- Partner-specific map overrides are stored in ai-engine's DB (`ConversionMap.partnerId`) but the
  edi-converter's `PartnerProfileManager` does not know about them
- The override chain (partner custom > trained > standard) is not implemented end-to-end

### 1.5 The Compare Function Is Phase-1

The `ComparisonSuiteService` is well-architected (directory scanning, file pairing, session
management, report generation) but operates at the semantic-diff level via `SemanticDiffEngine`.
It needs:

- Field-level accuracy scoring (not just "changed/unchanged" but "85% of fields match")
- Regression test persistence (save test suites, re-run after map changes)
- Batch conversion + comparison in one workflow
- Summary statistics aggregated across hundreds of file pairs

### 1.6 Performance Baseline Unknown

There is no benchmark comparing TranzFer's conversion speed against Sterling Integrator, Axway,
or IBM DataPower. Roshan's requirement is "must be faster than legacy proprietary mapping tools."
We need a performance test suite and published numbers.

---

## Part 2: The Right Mental Model

### 2.1 How EDI Conversion Actually Works in Enterprise MFT

Every real-world EDI conversion follows this pattern:

```
INBOUND FLOW (Partner sends document to company):

  Partner A sends X12 850 Purchase Order
          |
          v
  [Auto-detect: format=X12, type=850]
          |
          v
  [Map Lookup: X12_850 -> PURCHASE_ORDER_INH @ PartnerA]
          |
          v
  [Map Engine applies field-by-field translation]
          |
          v
  Internal purchase order document (PURCHASE_ORDER_INH format)
          |
          v
  Company's ERP system imports the order


OUTBOUND FLOW (Company sends document to partner):

  ERP system generates internal invoice
          |
          v
  [Map Lookup: INVOICE_INH -> X12_810 @ PartnerA]
          |
          v
  [Map Engine applies field-by-field translation]
          |
          v
  X12 810 Invoice in Partner A's expected format
          |
          v
  Partner A receives the invoice


CROSS-FORMAT FLOW (Mediation between two partners):

  Partner A sends EDIFACT ORDERS
          |
          v
  [Map: EDIFACT_ORDERS -> CanonicalDocument (internal)]
          |
          v
  [Map: CanonicalDocument -> X12_850 @ PartnerB]
          |
          v
  Partner B receives X12 850
```

### 2.2 The Three Required Inputs for Every Conversion

Every conversion requires exactly three things:

1. **SOURCE document type** — the format and transaction set of the input  
   Examples: `X12_850`, `EDIFACT_ORDERS`, `HL7_ADT_A01`, `SWIFT_MT103`

2. **TARGET document type** — the format and transaction set of the desired output  
   Examples: `PURCHASE_ORDER_INH`, `X12_810`, `EDIFACT_INVOIC`, `JSON_FLAT`

3. **A MAP** — the field-by-field translation rules between source and target  
   The map defines: which source field maps to which target field, what transformations
   to apply, what defaults to use for missing fields, how to handle loops/arrays

### 2.3 Maps Are the Product

The converter engine (parser + serializer) is infrastructure. The AI training pipeline is tooling.
**Maps are the product.** A customer evaluating TranzFer MFT asks:

- "Do you support X12 850 to our internal format?" — They are asking if we have a map.
- "Can you convert EDIFACT ORDERS to X12 850?" — They are asking if we have a cross-format map.
- "How long to onboard a new partner?" — They are asking how fast we can build/customize a map.

The answers should be:

- "Yes, we ship a standard map for X12 850 that covers 95% of fields. You can customize it for
  your specific ERP in minutes using our visual map editor."
- "Yes, X12 850 to EDIFACT ORDERS is a standard map. Partner-specific customizations take 10
  minutes with our AI assistant."
- "Under an hour. Upload 5 sample documents, our AI builds the map, you review and approve."

### 2.4 The Converter Is the Engine, the AI Is the Map Builder

Clear separation of concerns:

| Component | Role | Lives In |
|-----------|------|----------|
| Parsers (X12Parser, EdifactParser, etc.) | Parse raw EDI to EdiDocument | edi-converter |
| Serializers (toX12, toEdifact, etc.) | Render CanonicalDocument to output format | edi-converter |
| CanonicalMapper | Bridge: EdiDocument <-> CanonicalDocument | edi-converter |
| Map Engine | Apply field-by-field maps with transforms | edi-converter |
| Standard Map Library | Pre-built maps bundled in JAR | edi-converter |
| Map Cache | ConcurrentHashMap + TTL for hot maps | edi-converter |
| Training Engine | 5-strategy ML training from samples | ai-engine |
| Map Store | Versioned persistence in PostgreSQL | ai-engine |
| Map Correction | Natural-language map editing | ai-engine |
| AI Map Generator | Schema-based map generation | ai-engine |

---

## Part 3: Standard Map Library

### 3.1 Transaction Sets to Support

The following transaction sets represent approximately 98% of B2B EDI, financial messaging,
healthcare, and retail traffic worldwide. Organized by standard and industry.

#### X12 Transaction Sets (14 + 6 HIPAA)

| Code | Name | Business Function |
|------|------|-------------------|
| 810 | Invoice | Billing for goods/services |
| 820 | Payment Order/Remittance Advice | Payment instructions and details |
| 824 | Application Advice | Acceptance/rejection of a transaction |
| 830 | Planning Schedule with Release Capability | Demand forecast/planning |
| 840 | Request for Quotation | Soliciting price quotes |
| 843 | Response to Request for Quotation | Price quote response |
| 850 | Purchase Order | Ordering goods/services |
| 855 | Purchase Order Acknowledgment | Confirming receipt of PO |
| 856 | Ship Notice / Manifest (ASN) | Shipment details |
| 860 | Purchase Order Change Request | Modifying an existing PO |
| 861 | Receiving Advice | Confirming receipt of goods |
| 864 | Text Message | Free-form text communication |
| 997 | Functional Acknowledgment | Confirming receipt of EDI transaction |
| 999 | Implementation Acknowledgment | Enhanced 997 with implementation details |

#### EDIFACT Message Types (9)

| Code | Name | Business Function |
|------|------|-------------------|
| ORDERS | Purchase Order | Ordering goods/services |
| ORDRSP | Order Response | Confirming/rejecting an order |
| DESADV | Despatch Advice | Shipment notification (equivalent to 856) |
| INVOIC | Invoice | Billing for goods/services |
| REMADV | Remittance Advice | Payment notification |
| APERAK | Application Error and Acknowledgement | Error reporting |
| CONTRL | Syntax and Service Report | Syntax-level acknowledgment |
| IFTMIN | Instruction to Forward (Transport) | Logistics/shipping instructions |
| CUSREP | Customs Report | Customs declaration/clearance |

#### TRADACOMS Message Types (6) — UK/Retail Standard

TRADACOMS is the dominant B2B standard in UK retail (Tesco, Sainsbury's, Marks & Spencer).
It predates EDIFACT and uses a fixed-field format with STX/END envelope.

| Code | Name | Business Function |
|------|------|-------------------|
| ORDHDR / ORDDET | Purchase Order (Header + Detail) | Retail ordering |
| INVFIL | Invoice File | Supplier invoicing |
| DLCFIL | Delivery Confirmation | Delivery/receipt notification |
| AVLFIL | Availability Report | Stock availability |
| PRIHDR / PRIDET | Price Information (Header + Detail) | Price catalogue updates |
| ACKHDR | Order Acknowledgment | PO confirmation/rejection |

#### SWIFT MT Message Types (8) — Financial Messaging

SWIFT (Society for Worldwide Interbank Financial Telecommunication) messages for
banking, payments, securities, and trade finance. Used by 11,000+ financial institutions.

| Code | Name | Business Function |
|------|------|-------------------|
| MT103 | Single Customer Credit Transfer | Wire transfers / cross-border payments |
| MT202 | General Financial Institution Transfer | Bank-to-bank transfers |
| MT199 | Free Format Message | Free-form banking communication |
| MT300 | Foreign Exchange Confirmation | FX trade confirmation |
| MT502 | Order to Buy or Sell | Securities trading |
| MT535 | Statement of Holdings | Portfolio/custody positions |
| MT940 | Customer Statement | Account statement (end-of-day) |
| MT942 | Interim Transaction Report | Intraday account statement |

#### SWIFT MX (ISO 20022) Message Types (6) — Next-Gen Financial

ISO 20022 XML-based messages replacing MT messages globally. US FedNow and
CHIPS adopted ISO 20022 in 2023-2025. Mandatory for cross-border by 2025.

| Code | Name | Business Function |
|------|------|-------------------|
| pacs.008 | FI to FI Customer Credit Transfer | Payment instruction (replaces MT103) |
| pacs.009 | FI to FI Financial Institution Credit Transfer | Interbank (replaces MT202) |
| pacs.002 | Payment Status Report | Payment status / rejection |
| pain.001 | Customer Credit Transfer Initiation | Batch payment file (corporate → bank) |
| pain.002 | Customer Payment Status Report | Payment batch status |
| camt.053 | Bank to Customer Statement | Account statement (replaces MT940) |

#### NACHA / ACH File Formats (3) — US Payments

NACHA (National Automated Clearing House Association) processes $72+ trillion/year
in US electronic payments. ACH files are the backbone of US payroll, direct deposit,
and vendor payments.

| Code | Name | Business Function |
|------|------|-------------------|
| ACH_PPD | Prearranged Payment and Deposit | Payroll, direct deposit, consumer payments |
| ACH_CCD | Cash Concentration or Disbursement | Corporate-to-corporate payments |
| ACH_CTX | Corporate Trade Exchange | EDI payment with addenda (remittance advice) |

#### HIPAA Transaction Sets (6) — US Healthcare

HIPAA mandates specific X12 transaction sets for all US healthcare entities.
$4+ trillion US healthcare industry — these are required by law.

| Code | Name | Business Function |
|------|------|-------------------|
| 270/271 | Eligibility Inquiry / Response | Insurance eligibility verification |
| 276/277 | Claim Status Inquiry / Response | Check claim processing status |
| 278 | Health Care Services Review | Prior authorization requests |
| 835 | Health Care Claim Payment/Advice | Remittance advice (ERA) |
| 837P | Health Care Claim — Professional | Provider claim submission |
| 837I | Health Care Claim — Institutional | Hospital/facility claim submission |

#### NCPDP (4) — US Pharmacy

National Council for Prescription Drug Programs. Standard for US pharmacy
claims, eligibility, and prior authorization. Used by every US pharmacy.

| Code | Name | Business Function |
|------|------|-------------------|
| NCPDP D.0 | Telecommunications Standard | Real-time pharmacy claims at POS |
| NCPDP SCRIPT | Electronic Prescribing | e-Prescriptions (NewRx, RxRenewal, CancelRx) |
| NCPDP POST | Post Adjudication | Rebate and pricing adjustments |
| NCPDP Formulary | Formulary and Benefit | Drug coverage and cost information |

#### FIX Protocol (4) — US/Global Capital Markets

Financial Information eXchange — dominant in equities, futures, options trading.
Used by NYSE, NASDAQ, CME, and every major broker-dealer.

| Code | Name | Business Function |
|------|------|-------------------|
| FIX_NewOrderSingle | New Order (D) | Submit buy/sell order |
| FIX_ExecutionReport | Execution Report (8) | Fill/partial fill/reject notification |
| FIX_OrderCancelRequest | Cancel Request (F) | Cancel a pending order |
| FIX_MarketDataSnapshot | Market Data (W) | Real-time quote/price data |

#### BAI2 (2) — US Banking

Bank Administration Institute format. Universal standard for US bank
cash management reporting. Every US bank supports BAI2.

| Code | Name | Business Function |
|------|------|-------------------|
| BAI2_STATEMENT | Account Statement | Daily bank statement with transactions |
| BAI2_LOCKBOX | Lockbox Report | Lockbox payment detail (check processing) |

#### INHOUSE Document Types (8)

Standard internal format for each major business document. These serve as the default target
for companies that do not have a custom ERP schema.

| Code | Name | Typical Use |
|------|------|-------------|
| PURCHASE_ORDER_INH | Internal Purchase Order | ERP import of POs |
| INVOICE_INH | Internal Invoice | ERP import of invoices |
| SHIP_NOTICE_INH | Internal Ship Notice | WMS/TMS import of ASNs |
| PAYMENT_INH | Internal Payment/Remittance | Treasury/AP import |
| ACKNOWLEDGMENT_INH | Internal Acknowledgment | Status tracking |
| HEALTHCARE_CLAIM_INH | Internal Healthcare Claim | Claims processing import |
| BANK_STATEMENT_INH | Internal Bank Statement | Treasury/cash management import |
| TRADE_ORDER_INH | Internal Trade Order | Trading system import |

### 3.2 INHOUSE Format Specification

Each INHOUSE document type is a JSON schema with a flat, human-readable structure. These are
the "golden" internal formats that any ERP can consume with minimal transformation.

#### PURCHASE_ORDER_INH Schema

```json
{
  "$schema": "https://tranzfer.io/schemas/inhouse/purchase-order-v1.json",
  "type": "object",
  "properties": {
    "documentType": { "const": "PURCHASE_ORDER" },
    "documentNumber": { "type": "string", "description": "PO number" },
    "documentDate": { "type": "string", "format": "date", "description": "ISO-8601 date" },
    "buyer": {
      "type": "object",
      "properties": {
        "id": { "type": "string" },
        "name": { "type": "string" },
        "qualifier": { "type": "string", "description": "ID type: DUNS, GLN, EIN" },
        "address": { "$ref": "#/$defs/address" }
      },
      "required": ["id", "name"]
    },
    "seller": {
      "type": "object",
      "properties": {
        "id": { "type": "string" },
        "name": { "type": "string" },
        "qualifier": { "type": "string" },
        "address": { "$ref": "#/$defs/address" }
      },
      "required": ["id", "name"]
    },
    "shipTo": { "$ref": "#/$defs/party" },
    "billTo": { "$ref": "#/$defs/party" },
    "currency": { "type": "string", "default": "USD" },
    "lineItems": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "lineNumber": { "type": "integer" },
          "itemId": { "type": "string" },
          "description": { "type": "string" },
          "quantity": { "type": "number" },
          "unitOfMeasure": { "type": "string" },
          "unitPrice": { "type": "number" },
          "lineTotal": { "type": "number" },
          "productCode": { "type": "string" },
          "codeQualifier": { "type": "string", "description": "VP, UP, EN, SK" },
          "requestedDeliveryDate": { "type": "string", "format": "date" }
        },
        "required": ["lineNumber", "quantity", "unitPrice"]
      }
    },
    "totals": {
      "type": "object",
      "properties": {
        "totalAmount": { "type": "number" },
        "taxAmount": { "type": "number", "default": 0 },
        "shippingAmount": { "type": "number", "default": 0 },
        "lineItemCount": { "type": "integer" }
      }
    },
    "references": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "qualifier": { "type": "string" },
          "value": { "type": "string" },
          "description": { "type": "string" }
        }
      }
    },
    "dates": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "qualifier": { "type": "string", "description": "002=delivery, 010=requested ship" },
          "value": { "type": "string", "format": "date" }
        }
      }
    },
    "notes": {
      "type": "array",
      "items": { "type": "object", "properties": { "type": { "type": "string" }, "text": { "type": "string" } } }
    }
  },
  "required": ["documentType", "documentNumber", "documentDate", "buyer", "seller", "lineItems"],
  "$defs": {
    "address": {
      "type": "object",
      "properties": {
        "line1": { "type": "string" },
        "line2": { "type": "string" },
        "city": { "type": "string" },
        "state": { "type": "string" },
        "postalCode": { "type": "string" },
        "country": { "type": "string", "default": "US" }
      }
    },
    "party": {
      "type": "object",
      "properties": {
        "id": { "type": "string" },
        "name": { "type": "string" },
        "qualifier": { "type": "string" },
        "address": { "$ref": "#/$defs/address" }
      }
    }
  }
}
```

Similar schemas will be defined for INVOICE_INH, SHIP_NOTICE_INH, PAYMENT_INH, and
ACKNOWLEDGMENT_INH. Each follows the same pattern: flat, human-readable, ISO-8601 dates,
standard party/address structures.

### 3.3 Standard Maps to Ship

Every map listed below is a JSON file bundled in the edi-converter JAR under
`src/main/resources/maps/standard/`. Maps are bidirectional where indicated with arrows.

#### Purchase Order Maps (6)

| Map ID | Source | Target | Direction | Priority |
|--------|--------|--------|-----------|----------|
| `STD-PO-001` | X12 850 | PURCHASE_ORDER_INH | one-way | P0 |
| `STD-PO-002` | EDIFACT ORDERS | PURCHASE_ORDER_INH | one-way | P0 |
| `STD-PO-003` | X12 850 | EDIFACT ORDERS | bidirectional | P0 |
| `STD-PO-004` | PURCHASE_ORDER_INH | X12 850 | one-way | P1 |
| `STD-PO-005` | PURCHASE_ORDER_INH | EDIFACT ORDERS | one-way | P1 |
| `STD-PO-006` | X12 860 | PURCHASE_ORDER_INH | one-way (change overlay) | P1 |

#### Invoice Maps (5)

| Map ID | Source | Target | Direction | Priority |
|--------|--------|--------|-----------|----------|
| `STD-INV-001` | X12 810 | INVOICE_INH | one-way | P0 |
| `STD-INV-002` | EDIFACT INVOIC | INVOICE_INH | one-way | P0 |
| `STD-INV-003` | X12 810 | EDIFACT INVOIC | bidirectional | P0 |
| `STD-INV-004` | INVOICE_INH | X12 810 | one-way | P1 |
| `STD-INV-005` | INVOICE_INH | EDIFACT INVOIC | one-way | P1 |

#### Ship Notice / ASN Maps (5)

| Map ID | Source | Target | Direction | Priority |
|--------|--------|--------|-----------|----------|
| `STD-ASN-001` | X12 856 | SHIP_NOTICE_INH | one-way | P0 |
| `STD-ASN-002` | EDIFACT DESADV | SHIP_NOTICE_INH | one-way | P0 |
| `STD-ASN-003` | X12 856 | EDIFACT DESADV | bidirectional | P0 |
| `STD-ASN-004` | SHIP_NOTICE_INH | X12 856 | one-way | P1 |
| `STD-ASN-005` | SHIP_NOTICE_INH | EDIFACT DESADV | one-way | P1 |

#### Payment / Remittance Maps (5)

| Map ID | Source | Target | Direction | Priority |
|--------|--------|--------|-----------|----------|
| `STD-PAY-001` | X12 820 | PAYMENT_INH | one-way | P0 |
| `STD-PAY-002` | EDIFACT REMADV | PAYMENT_INH | one-way | P0 |
| `STD-PAY-003` | X12 820 | EDIFACT REMADV | bidirectional | P1 |
| `STD-PAY-004` | PAYMENT_INH | X12 820 | one-way | P1 |
| `STD-PAY-005` | PAYMENT_INH | EDIFACT REMADV | one-way | P1 |

#### Acknowledgment Maps (4)

| Map ID | Source | Target | Direction | Priority |
|--------|--------|--------|-----------|----------|
| `STD-ACK-001` | X12 997 | ACKNOWLEDGMENT_INH | one-way | P0 |
| `STD-ACK-002` | X12 999 | ACKNOWLEDGMENT_INH | one-way | P0 |
| `STD-ACK-003` | EDIFACT CONTRL | ACKNOWLEDGMENT_INH | one-way | P0 |
| `STD-ACK-004` | EDIFACT APERAK | ACKNOWLEDGMENT_INH | one-way | P1 |

#### PO Acknowledgment Maps (3)

| Map ID | Source | Target | Direction | Priority |
|--------|--------|--------|-----------|----------|
| `STD-POA-001` | X12 855 | PURCHASE_ORDER_INH | one-way (ack overlay) | P1 |
| `STD-POA-002` | EDIFACT ORDRSP | PURCHASE_ORDER_INH | one-way (ack overlay) | P1 |
| `STD-POA-003` | X12 855 | EDIFACT ORDRSP | bidirectional | P2 |

#### Receiving Advice Maps (2)

| Map ID | Source | Target | Direction | Priority |
|--------|--------|--------|-----------|----------|
| `STD-RCV-001` | X12 861 | SHIP_NOTICE_INH | one-way (receiving overlay) | P2 |
| `STD-RCV-002` | X12 861 | EDIFACT DESADV | one-way | P2 |

#### Planning / Forecast Maps (2)

| Map ID | Source | Target | Direction | Priority |
|--------|--------|--------|-----------|----------|
| `STD-PLN-001` | X12 830 | PURCHASE_ORDER_INH | one-way (forecast overlay) | P2 |
| `STD-PLN-002` | X12 840 | PURCHASE_ORDER_INH | one-way (RFQ overlay) | P2 |

#### Logistics Maps (2)

| Map ID | Source | Target | Direction | Priority |
|--------|--------|--------|-----------|----------|
| `STD-LOG-001` | EDIFACT IFTMIN | SHIP_NOTICE_INH | one-way | P2 |
| `STD-LOG-002` | EDIFACT CUSREP | ACKNOWLEDGMENT_INH | one-way | P2 |

#### Cross-Format Utility Maps (2)

| Map ID | Source | Target | Direction | Priority |
|--------|--------|--------|-----------|----------|
| `STD-XF-001` | X12 864 | EDIFACT (free text) | one-way | P2 |
| `STD-XF-002` | X12 843 | EDIFACT ORDRSP | one-way | P2 |

#### TRADACOMS Maps (4)

| Map ID | Source | Target | Direction | Priority |
|--------|--------|--------|-----------|----------|
| `STD-TRD-001` | TRADACOMS ORDHDR/ORDDET | PURCHASE_ORDER_INH | one-way | P1 |
| `STD-TRD-002` | TRADACOMS INVFIL | INVOICE_INH | one-way | P1 |
| `STD-TRD-003` | TRADACOMS DLCFIL | SHIP_NOTICE_INH | one-way | P1 |
| `STD-TRD-004` | TRADACOMS ORDHDR | EDIFACT ORDERS | one-way (migration) | P2 |

#### SWIFT / ISO 20022 Maps (8)

| Map ID | Source | Target | Direction | Priority |
|--------|--------|--------|-----------|----------|
| `STD-SWF-001` | SWIFT MT103 | PAYMENT_INH | one-way | P0 |
| `STD-SWF-002` | SWIFT MT940 | BANK_STATEMENT_INH | one-way | P0 |
| `STD-SWF-003` | SWIFT MT202 | PAYMENT_INH | one-way | P1 |
| `STD-SWF-004` | SWIFT MT535 | ACKNOWLEDGMENT_INH | one-way | P1 |
| `STD-SWF-005` | ISO20022 pacs.008 | PAYMENT_INH | one-way | P0 |
| `STD-SWF-006` | ISO20022 pain.001 | PAYMENT_INH | one-way | P0 |
| `STD-SWF-007` | ISO20022 camt.053 | BANK_STATEMENT_INH | one-way | P0 |
| `STD-SWF-008` | SWIFT MT103 | ISO20022 pacs.008 | one-way (migration) | P1 |

#### NACHA / ACH Maps (4)

| Map ID | Source | Target | Direction | Priority |
|--------|--------|--------|-----------|----------|
| `STD-ACH-001` | NACHA ACH_PPD | PAYMENT_INH | one-way | P0 |
| `STD-ACH-002` | NACHA ACH_CCD | PAYMENT_INH | one-way | P0 |
| `STD-ACH-003` | NACHA ACH_CTX | PAYMENT_INH | one-way | P1 |
| `STD-ACH-004` | PAYMENT_INH | NACHA ACH_CCD | one-way (outbound payment) | P1 |

#### HIPAA Healthcare Maps (8)

| Map ID | Source | Target | Direction | Priority |
|--------|--------|--------|-----------|----------|
| `STD-HIP-001` | X12 837P | HEALTHCARE_CLAIM_INH | one-way | P0 |
| `STD-HIP-002` | X12 837I | HEALTHCARE_CLAIM_INH | one-way | P0 |
| `STD-HIP-003` | X12 835 | PAYMENT_INH | one-way (ERA→remittance) | P0 |
| `STD-HIP-004` | X12 270 | ACKNOWLEDGMENT_INH | one-way (eligibility request) | P1 |
| `STD-HIP-005` | X12 271 | ACKNOWLEDGMENT_INH | one-way (eligibility response) | P1 |
| `STD-HIP-006` | X12 276 | ACKNOWLEDGMENT_INH | one-way (status inquiry) | P1 |
| `STD-HIP-007` | X12 277 | ACKNOWLEDGMENT_INH | one-way (status response) | P1 |
| `STD-HIP-008` | HEALTHCARE_CLAIM_INH | X12 837P | one-way (outbound claim) | P1 |

#### BAI2 Banking Maps (2)

| Map ID | Source | Target | Direction | Priority |
|--------|--------|--------|-----------|----------|
| `STD-BAI-001` | BAI2 Statement | BANK_STATEMENT_INH | one-way | P0 |
| `STD-BAI-002` | BAI2 Lockbox | PAYMENT_INH | one-way | P1 |

#### FIX Protocol Maps (3)

| Map ID | Source | Target | Direction | Priority |
|--------|--------|--------|-----------|----------|
| `STD-FIX-001` | FIX NewOrderSingle | TRADE_ORDER_INH | one-way | P1 |
| `STD-FIX-002` | FIX ExecutionReport | TRADE_ORDER_INH | one-way (fill) | P1 |
| `STD-FIX-003` | TRADE_ORDER_INH | FIX NewOrderSingle | one-way (outbound) | P2 |

#### NCPDP Pharmacy Maps (2)

| Map ID | Source | Target | Direction | Priority |
|--------|--------|--------|-----------|----------|
| `STD-RX-001` | NCPDP D.0 Claim | HEALTHCARE_CLAIM_INH | one-way | P2 |
| `STD-RX-002` | NCPDP SCRIPT NewRx | HEALTHCARE_CLAIM_INH | one-way | P2 |

**Total: 67 standard maps** covering 98%+ of B2B EDI, financial, healthcare, and retail traffic.

#### Coverage by Industry

| Industry | Standards | Maps | Coverage |
|----------|-----------|------|----------|
| **Retail / Manufacturing** | X12, EDIFACT, TRADACOMS | 40 | Supply chain, orders, invoices, shipping |
| **Banking / Payments** | SWIFT MT, ISO 20022, NACHA, BAI2 | 14 | Wire transfers, ACH, statements, reconciliation |
| **Healthcare** | HIPAA X12, NCPDP | 10 | Claims, eligibility, prescriptions, remittance |
| **Capital Markets** | FIX Protocol | 3 | Order entry, execution, market data |

### 3.4 Standard Map JSON Format

Every standard map is a JSON file. Example: `STD-PO-001.json` (X12 850 -> PURCHASE_ORDER_INH):

```json
{
  "mapId": "STD-PO-001",
  "name": "X12 850 Purchase Order to Internal PO",
  "description": "Standard map for converting X12 850 to PURCHASE_ORDER_INH format",
  "version": 1,
  "status": "CERTIFIED",
  "source": {
    "format": "X12",
    "transactionSet": "850",
    "version": "005010"
  },
  "target": {
    "format": "INHOUSE",
    "documentType": "PURCHASE_ORDER_INH",
    "version": "1.0"
  },
  "fieldMappings": [
    {
      "sourceField": "BEG*03",
      "targetField": "documentNumber",
      "transform": "DIRECT",
      "transformParam": null,
      "confidence": 100,
      "required": true,
      "description": "Purchase Order number"
    },
    {
      "sourceField": "BEG*05",
      "targetField": "documentDate",
      "transform": "DATE_REFORMAT",
      "transformParam": "yyyyMMdd->yyyy-MM-dd",
      "confidence": 100,
      "required": true,
      "description": "PO date, convert from X12 CCYYMMDD to ISO-8601"
    },
    {
      "sourceField": "BEG*01",
      "targetField": "header.purpose",
      "transform": "CODE_TABLE",
      "transformParam": "X12_PURPOSE_CODE",
      "confidence": 100,
      "required": false,
      "description": "Transaction Set Purpose Code: 00=Original, 05=Replace"
    },
    {
      "sourceField": "N1[BY]*02",
      "targetField": "buyer.name",
      "transform": "DIRECT",
      "transformParam": null,
      "confidence": 100,
      "required": true,
      "description": "Buyer name from N1 loop where N1*01=BY"
    },
    {
      "sourceField": "N1[BY]*04",
      "targetField": "buyer.id",
      "transform": "DIRECT",
      "transformParam": null,
      "confidence": 100,
      "required": true,
      "description": "Buyer identifier"
    },
    {
      "sourceField": "N1[BY]*03",
      "targetField": "buyer.qualifier",
      "transform": "CODE_TABLE",
      "transformParam": "X12_ID_QUALIFIER",
      "confidence": 100,
      "required": false,
      "description": "Buyer ID qualifier (01=DUNS, ZZ=Mutually Defined)"
    },
    {
      "sourceField": "N3[BY]*01",
      "targetField": "buyer.address.line1",
      "transform": "DIRECT",
      "transformParam": null,
      "confidence": 100,
      "required": false,
      "description": "Buyer street address"
    },
    {
      "sourceField": "N4[BY]*01",
      "targetField": "buyer.address.city",
      "transform": "DIRECT",
      "transformParam": null,
      "confidence": 100,
      "required": false,
      "description": "Buyer city"
    },
    {
      "sourceField": "N4[BY]*02",
      "targetField": "buyer.address.state",
      "transform": "DIRECT",
      "transformParam": null,
      "confidence": 100,
      "required": false,
      "description": "Buyer state/province"
    },
    {
      "sourceField": "N4[BY]*03",
      "targetField": "buyer.address.postalCode",
      "transform": "DIRECT",
      "transformParam": null,
      "confidence": 100,
      "required": false,
      "description": "Buyer postal code"
    },
    {
      "sourceField": "N4[BY]*04",
      "targetField": "buyer.address.country",
      "transform": "DIRECT",
      "transformParam": null,
      "confidence": 95,
      "required": false,
      "defaultValue": "US",
      "description": "Buyer country (defaults to US if absent)"
    },
    {
      "sourceField": "N1[SE]*02",
      "targetField": "seller.name",
      "transform": "DIRECT",
      "transformParam": null,
      "confidence": 100,
      "required": true,
      "description": "Seller name"
    },
    {
      "sourceField": "N1[SE]*04",
      "targetField": "seller.id",
      "transform": "DIRECT",
      "transformParam": null,
      "confidence": 100,
      "required": true,
      "description": "Seller identifier"
    },
    {
      "sourceField": "N1[ST]*02",
      "targetField": "shipTo.name",
      "transform": "DIRECT",
      "transformParam": null,
      "confidence": 100,
      "required": false,
      "description": "Ship-to party name"
    },
    {
      "sourceField": "CUR*02",
      "targetField": "currency",
      "transform": "DIRECT",
      "transformParam": null,
      "confidence": 100,
      "required": false,
      "defaultValue": "USD",
      "description": "Currency code (defaults to USD)"
    },
    {
      "sourceField": "PO1[*]*01",
      "targetField": "lineItems[*].lineNumber",
      "transform": "DIRECT",
      "transformParam": null,
      "confidence": 100,
      "required": true,
      "loop": "PO1",
      "description": "Line item sequence number"
    },
    {
      "sourceField": "PO1[*]*02",
      "targetField": "lineItems[*].quantity",
      "transform": "PARSE_NUMBER",
      "transformParam": null,
      "confidence": 100,
      "required": true,
      "loop": "PO1",
      "description": "Quantity ordered"
    },
    {
      "sourceField": "PO1[*]*03",
      "targetField": "lineItems[*].unitOfMeasure",
      "transform": "CODE_TABLE",
      "transformParam": "X12_UNIT_OF_MEASURE",
      "confidence": 100,
      "required": true,
      "loop": "PO1",
      "description": "Unit of measure (EA, CA, BX, etc.)"
    },
    {
      "sourceField": "PO1[*]*04",
      "targetField": "lineItems[*].unitPrice",
      "transform": "PARSE_NUMBER",
      "transformParam": null,
      "confidence": 100,
      "required": true,
      "loop": "PO1",
      "description": "Unit price"
    },
    {
      "sourceField": "PO1[*]*07",
      "targetField": "lineItems[*].itemId",
      "transform": "DIRECT",
      "transformParam": null,
      "confidence": 95,
      "required": false,
      "loop": "PO1",
      "description": "Product/item identification (VP=vendor part)"
    },
    {
      "sourceField": "PO1[*]*06",
      "targetField": "lineItems[*].codeQualifier",
      "transform": "DIRECT",
      "transformParam": null,
      "confidence": 95,
      "required": false,
      "loop": "PO1",
      "description": "Product ID qualifier (VP, UP, EN, SK)"
    },
    {
      "sourceField": "PID[*]*05",
      "targetField": "lineItems[*].description",
      "transform": "DIRECT",
      "transformParam": null,
      "confidence": 90,
      "required": false,
      "loop": "PO1",
      "description": "Product description from PID segment"
    },
    {
      "sourceField": "CTT*01",
      "targetField": "totals.lineItemCount",
      "transform": "PARSE_NUMBER",
      "transformParam": null,
      "confidence": 100,
      "required": false,
      "description": "Total line item count from CTT segment"
    },
    {
      "sourceField": "REF[*]*01",
      "targetField": "references[*].qualifier",
      "transform": "DIRECT",
      "transformParam": null,
      "confidence": 100,
      "required": false,
      "loop": "REF",
      "description": "Reference qualifier"
    },
    {
      "sourceField": "REF[*]*02",
      "targetField": "references[*].value",
      "transform": "DIRECT",
      "transformParam": null,
      "confidence": 100,
      "required": false,
      "loop": "REF",
      "description": "Reference value"
    },
    {
      "sourceField": "DTM[*]*02",
      "targetField": "dates[*].value",
      "transform": "DATE_REFORMAT",
      "transformParam": "yyyyMMdd->yyyy-MM-dd",
      "confidence": 95,
      "required": false,
      "loop": "DTM",
      "description": "Date/time value"
    },
    {
      "sourceField": "DTM[*]*01",
      "targetField": "dates[*].qualifier",
      "transform": "DIRECT",
      "transformParam": null,
      "confidence": 95,
      "required": false,
      "loop": "DTM",
      "description": "Date qualifier"
    }
  ],
  "codeTables": {
    "X12_PURPOSE_CODE": {
      "00": "Original",
      "01": "Cancellation",
      "05": "Replace",
      "06": "Confirmation",
      "07": "Duplicate"
    },
    "X12_ID_QUALIFIER": {
      "01": "DUNS",
      "08": "UCC/EAN",
      "09": "DUNS+4",
      "14": "EAN/UCC-13",
      "ZZ": "Mutually Defined"
    },
    "X12_UNIT_OF_MEASURE": {
      "EA": "Each",
      "CA": "Case",
      "BX": "Box",
      "PK": "Pack",
      "DZ": "Dozen",
      "LB": "Pound",
      "KG": "Kilogram",
      "FT": "Foot",
      "IN": "Inch",
      "GA": "Gallon",
      "LT": "Liter"
    }
  },
  "loopDefinitions": {
    "PO1": {
      "triggerSegment": "PO1",
      "childSegments": ["PID", "MEA", "DTM", "PKG", "TD1", "SAC"],
      "targetArrayField": "lineItems"
    },
    "N1": {
      "triggerSegment": "N1",
      "childSegments": ["N2", "N3", "N4", "PER", "REF"],
      "qualifierElement": "N1*01",
      "qualifierMapping": {
        "BY": "buyer",
        "SE": "seller",
        "ST": "shipTo",
        "BT": "billTo"
      }
    },
    "REF": {
      "triggerSegment": "REF",
      "targetArrayField": "references"
    },
    "DTM": {
      "triggerSegment": "DTM",
      "targetArrayField": "dates"
    }
  },
  "defaults": {
    "currency": "USD",
    "buyer.address.country": "US",
    "seller.address.country": "US"
  },
  "validation": {
    "requiredTargetFields": [
      "documentNumber", "documentDate", "buyer.name", "buyer.id",
      "seller.name", "seller.id", "lineItems"
    ],
    "minimumLineItems": 1
  },
  "metadata": {
    "author": "TranzFer Standard Library",
    "lastUpdated": "2026-04-10",
    "coverage": "Covers 95% of standard X12 850 fields per ASC X12 005010 spec",
    "testedWith": ["Walmart", "Amazon", "Target", "Home Depot", "Costco"]
  }
}
```

---

## Part 4: Map Architecture

### 4.1 Map Format Schema

Every map (standard, trained, or partner-custom) follows this unified JSON schema:

```json
{
  "mapId": "string (unique identifier)",
  "name": "string (human-readable name)",
  "description": "string",
  "version": "integer (auto-incremented per map ID)",
  "status": "DRAFT | TESTED | CERTIFIED | ACTIVE | DEPRECATED",
  "source": {
    "format": "X12 | EDIFACT | HL7 | SWIFT_MT | INHOUSE",
    "transactionSet": "850 | ORDERS | ADT_A01 | MT103 | PURCHASE_ORDER_INH",
    "version": "005010 | D01B | 2.5 | null"
  },
  "target": {
    "format": "same enum as source",
    "documentType": "same enum as source.transactionSet",
    "version": "string or null"
  },
  "fieldMappings": [
    {
      "sourceField": "segment path, e.g. BEG*03 or N1[BY]*02",
      "targetField": "dot-notation path, e.g. buyer.name or lineItems[*].quantity",
      "transform": "DIRECT | TRIM | UPPERCASE | LOWERCASE | ZERO_PAD | DATE_REFORMAT | PARSE_NUMBER | CODE_TABLE | CONCAT | SUBSTRING | CONDITIONAL | CUSTOM",
      "transformParam": "transform-specific parameter",
      "confidence": "integer 0-100",
      "required": "boolean",
      "defaultValue": "string (used when source field is absent)",
      "loop": "loop ID if this mapping is inside a repeating structure",
      "condition": "optional condition expression, e.g. 'N1*01 == BY'",
      "description": "human-readable explanation"
    }
  ],
  "codeTables": {
    "TABLE_NAME": { "sourceCode": "targetValue", "...": "..." }
  },
  "loopDefinitions": {
    "LOOP_ID": {
      "triggerSegment": "segment ID that starts the loop",
      "childSegments": ["segment IDs that belong to this loop"],
      "qualifierElement": "optional: element that determines loop variant",
      "qualifierMapping": { "qualifier value": "target object path" },
      "targetArrayField": "target field name for the array"
    }
  },
  "defaults": {
    "target.field.path": "default value"
  },
  "validation": {
    "requiredTargetFields": ["list of required fields in output"],
    "minimumLineItems": "integer or null"
  },
  "metadata": {
    "author": "string",
    "origin": "STANDARD | TRAINED | PARTNER_CUSTOM",
    "partnerId": "null for standard maps",
    "parentMapId": "null for originals, parent map ID for clones",
    "lastUpdated": "ISO-8601 date",
    "coverage": "string description",
    "testedWith": ["partner names or sample IDs"]
  }
}
```

### 4.2 Transform Types

| Transform | Parameter | Description | Example |
|-----------|-----------|-------------|---------|
| `DIRECT` | none | Copy value as-is | `BEG*03` -> `documentNumber` |
| `TRIM` | none | Remove leading/trailing whitespace | `"  ACME  "` -> `"ACME"` |
| `UPPERCASE` | none | Convert to uppercase | `"acme"` -> `"ACME"` |
| `LOWERCASE` | none | Convert to lowercase | `"ACME"` -> `"acme"` |
| `ZERO_PAD` | length | Left-pad with zeros | `"123"` with length=10 -> `"0000000123"` |
| `DATE_REFORMAT` | `srcFmt->tgtFmt` | Reformat date | `"20230101"` with `yyyyMMdd->yyyy-MM-dd` -> `"2023-01-01"` |
| `PARSE_NUMBER` | none | Parse string to number | `"12.50"` -> `12.50` |
| `CODE_TABLE` | table name | Lookup in code table | `"EA"` via `X12_UNIT_OF_MEASURE` -> `"Each"` |
| `CONCAT` | separator | Concatenate multiple source fields | `"line1|line2"` with `" "` -> `"line1 line2"` |
| `SUBSTRING` | `start,length` | Extract substring | `"20230101"` with `0,4` -> `"2023"` |
| `CONDITIONAL` | condition expression | Map only if condition is true | `"IF N1*01 == 'BY' THEN map"` |
| `CUSTOM` | Java/Groovy expression | Custom transformation | For edge cases only |
| `SPLIT` | delimiter, index | Split and take Nth element | `"ACME:001"` with `":",1` -> `"001"` |
| `DEFAULT` | value | Use this value if source is empty | `""` with default `"US"` -> `"US"` |
| `REGEX_EXTRACT` | regex with group | Extract via regex | `"PO-2023-001"` with `PO-(\d+)-(\d+)` group 2 -> `"001"` |

### 4.3 Storage Strategy

Roshan's concern: maps must not add load to boot time, DB queries, or Java memory.

#### Standard Maps: Classpath Resources in JAR

```
edi-converter/src/main/resources/maps/
  standard/
    STD-PO-001.json       # X12 850 -> PURCHASE_ORDER_INH
    STD-PO-002.json       # EDIFACT ORDERS -> PURCHASE_ORDER_INH
    STD-PO-003.json       # X12 850 <-> EDIFACT ORDERS
    STD-INV-001.json      # X12 810 -> INVOICE_INH
    ...                   # 36 total
  schemas/
    PURCHASE_ORDER_INH.schema.json
    INVOICE_INH.schema.json
    SHIP_NOTICE_INH.schema.json
    PAYMENT_INH.schema.json
    ACKNOWLEDGMENT_INH.schema.json
  code-tables/
    X12_PURPOSE_CODE.json
    X12_ID_QUALIFIER.json
    X12_UNIT_OF_MEASURE.json
    EDIFACT_MESSAGE_TYPE.json
    ...
```

**Loading behavior:** Standard maps are loaded LAZILY. On first conversion request for a given
source+target combination, the `StandardMapLibrary` service loads the matching JSON from the
classpath, parses it, and puts it in a `ConcurrentHashMap`. After first use, it stays in memory
forever (these are small JSON files, ~5-20 KB each, 36 total = ~500 KB max).

The `@PostConstruct` only builds an index of available map IDs (file listing) — it does NOT load
the JSON content. This means zero boot-time cost.

```java
@Service
public class StandardMapLibrary {
    // Index: loaded at startup (just file names, ~1ms)
    private final Map<String, String> mapIndex = new LinkedHashMap<>(); // mapId -> classpath path
    // Content cache: lazy-loaded on first use
    private final ConcurrentHashMap<String, StandardMap> loadedMaps = new ConcurrentHashMap<>();

    @PostConstruct
    void buildIndex() {
        // Scan classpath for maps/standard/*.json, extract mapId from filename
        // Does NOT load file content — just builds the lookup index
    }

    public Optional<StandardMap> getMap(String mapId) {
        return Optional.ofNullable(loadedMaps.computeIfAbsent(mapId, this::loadFromClasspath));
    }

    public Optional<StandardMap> findMap(String sourceFormat, String sourceType,
                                          String targetFormat, String targetType) {
        // Lookup by source+target combination
    }
}
```

#### Trained Maps: ai-engine PostgreSQL, Lazy-Loaded by edi-converter

Trained maps live in ai-engine's `edi_conversion_maps` table (existing `ConversionMap` entity).
The edi-converter fetches them via REST on first use and caches locally with TTL (existing
`TrainedMapConsumer` pattern).

**No changes to this pattern** — it already works correctly. The cache has 5-minute TTL,
ConcurrentHashMap storage, disk-based fallback for when ai-engine is down.

#### Partner Custom Maps: ai-engine PostgreSQL with partnerId Scope

Partner custom maps are stored in the same `edi_conversion_maps` table with a non-null `partnerId`
column. The `TrainedMapStore` already supports partner-scoped lookup:

```java
// Existing code in TrainedMapStore.getActiveMap():
// Try partner-specific first, then default
String partnerKey = buildMapKey(sourceFormat, sourceType, targetFormat, null, partnerId);
Optional<ConversionMap> partnerMap = getActiveMap(partnerKey);
if (partnerMap.isPresent()) return partnerMap;
// Fall back to default (no partner)
String defaultKey = buildMapKey(sourceFormat, sourceType, targetFormat, null, null);
return getActiveMap(defaultKey);
```

### 4.4 Map Resolution Chain (Override Order)

When a conversion request arrives with source type, target type, and partner ID, the map engine
resolves the map in this cascade order:

```
1. Partner Custom Map  (partnerId = "ACME", origin = PARTNER_CUSTOM)
   |
   | not found
   v
2. Trained Map         (partnerId = null, origin = TRAINED)
   |
   | not found
   v
3. Standard Map        (origin = STANDARD, from classpath)
   |
   | not found
   v
4. ERROR: "No map available for X12_850 -> PURCHASE_ORDER_INH"
```

This is implemented as a new `MapResolver` service that coordinates across:
- `StandardMapLibrary` (classpath)
- `TrainedMapConsumer` (ai-engine REST with cache)

```java
@Service
public class MapResolver {
    private final StandardMapLibrary standardLibrary;
    private final TrainedMapConsumer trainedMapConsumer;

    public ResolvedMap resolve(String sourceFormat, String sourceType,
                                String targetFormat, String targetType,
                                String partnerId) {
        // 1. Try partner-custom (trained map with partnerId)
        if (partnerId != null) {
            Optional<TrainedMap> partnerMap = trainedMapConsumer.getTrainedMap(
                sourceFormat, sourceType, targetFormat, partnerId);
            if (partnerMap.isPresent()) return wrap(partnerMap.get(), "PARTNER_CUSTOM");
        }

        // 2. Try trained (no partnerId)
        Optional<TrainedMap> trainedMap = trainedMapConsumer.getTrainedMap(
            sourceFormat, sourceType, targetFormat, null);
        if (trainedMap.isPresent()) return wrap(trainedMap.get(), "TRAINED");

        // 3. Try standard
        Optional<StandardMap> standardMap = standardLibrary.findMap(
            sourceFormat, sourceType, targetFormat, targetType);
        if (standardMap.isPresent()) return wrap(standardMap.get(), "STANDARD");

        // 4. Not found
        return ResolvedMap.notFound(sourceFormat, sourceType, targetFormat, targetType);
    }
}
```

### 4.5 Versioning

Each map (standard and trained) has:

| Field | Description |
|-------|-------------|
| `version` | Integer, auto-incremented per mapId |
| `status` | DRAFT -> TESTED -> CERTIFIED -> ACTIVE -> DEPRECATED |
| `active` | Boolean, only one version per mapId can be active |
| `createdAt` | Timestamp when this version was created |
| `testAccuracy` | Integer 0-100, measured accuracy on test samples |
| `usageCount` | How many times this version has been used for conversion |
| `lastUsedAt` | Last conversion timestamp |

Standard maps ship as version 1, status CERTIFIED. When a customer customizes a standard map, a
PARTNER_CUSTOM version 1 is created with `parentMapId` pointing to the standard.

### 4.6 Map Lifecycle State Machine

```
             train / create
                  |
                  v
              +-------+
              | DRAFT |  <--- AI-generated maps start here
              +-------+
                  |
              validate against test samples
                  |
                  v
             +--------+
             | TESTED |  <--- has testAccuracy score
             +--------+
                  |
              human review + approval (if confidence >= 80%)
                  |
                  v
           +-----------+
           | CERTIFIED |  <--- standard maps ship as CERTIFIED
           +-----------+
                  |
              activate (set active=true, deactivate previous)
                  |
                  v
            +--------+
            | ACTIVE |  <--- used for production conversions
            +--------+
                  |
              superseded by newer version
                  |
                  v
          +------------+
          | DEPRECATED |
          +------------+
```

---

## Part 5: AI-Powered Map Creation

### 5.1 Sample-Based Training (Existing — Needs Improvement)

The current pipeline in `EdiMapTrainingEngine` works but needs these enhancements:

#### 5.1.1 Confidence Threshold Enforcement

```java
// CURRENT: map is stored regardless of confidence
mapStore.storeTrainingResult(result, triggeredBy);

// NEW: enforce minimum confidence for auto-activation
if (result.getConfidence() >= 80) {
    result.setStatus(MapStatus.CERTIFIED);  // auto-certify high-confidence maps
} else if (result.getConfidence() >= 60) {
    result.setStatus(MapStatus.TESTED);     // needs human review
} else {
    result.setStatus(MapStatus.DRAFT);      // too low, needs more samples
}
```

#### 5.1.2 Field-Level Confidence Reporting

Each field mapping already has a confidence score. The training result should include:

```json
{
  "confidence": 87,
  "fieldBreakdown": {
    "highConfidence": 18,   "comment": "fields with confidence >= 90%",
    "mediumConfidence": 4,  "comment": "fields with confidence 60-89%",
    "lowConfidence": 2,     "comment": "fields with confidence < 60%",
    "unmapped": 3           "comment": "source fields with no target match"
  },
  "lowConfidenceFields": [
    { "sourceField": "REF*02", "targetField": "references[0].value", "confidence": 55, "reason": "Only matched in 2 of 5 samples" },
    { "sourceField": "PER*04", "targetField": "contacts[0].phone", "confidence": 45, "reason": "Format inconsistency across samples" }
  ]
}
```

#### 5.1.3 Minimum Sample Recommendations

| Confidence Target | Recommended Samples | Note |
|-------------------|--------------------:|------|
| 60% (DRAFT) | 3 | Minimum to run statistical correlation |
| 80% (TESTED) | 5-10 | Enough for train/test split |
| 90% (CERTIFIED) | 15-20 | Robust validation possible |
| 95%+ (production-grade) | 30+ | Covers edge cases and variations |

The UI should show: "You have 3 samples. Upload 2 more for TESTED quality, 12 more for CERTIFIED."

### 5.2 Schema-Based Generation (NEW)

For cases where no sample pairs exist, generate an initial map from schemas alone.

**Input:** Source schema (e.g., X12 850 Implementation Guide) + target schema (e.g., customer's
ERP field list or INHOUSE JSON schema).

**Algorithm:**

1. Parse both schemas into normalized field lists:
   - Source: `[{path: "BEG*03", name: "PurchaseOrderNumber", type: "AN", maxLength: 22}, ...]`
   - Target: `[{path: "documentNumber", name: "documentNumber", type: "string"}, ...]`

2. Apply matching strategies (reusing `FieldEmbeddingEngine`):
   - **Name similarity**: "PurchaseOrderNumber" <-> "documentNumber" (high match via synonym groups)
   - **Type compatibility**: AN (alphanumeric) <-> string (compatible), N (numeric) <-> number
   - **Position heuristics**: First field in header <-> first field in target header
   - **Standard knowledge**: Built-in knowledge that BEG*03 always means PO number

3. Generate field mappings with lower confidence (typically 60-75% for schema-only).

4. Mark as DRAFT status — requires sample validation before production use.

**API:**

```
POST /api/v1/edi/training/generate-from-schema
{
  "sourceFormat": "X12",
  "sourceType": "850",
  "targetSchema": { ... JSON Schema ... },
  "targetFormat": "CUSTOM",
  "targetType": "ACME_PURCHASE_ORDER"
}

Response:
{
  "mapId": "GEN-xxxx",
  "status": "DRAFT",
  "confidence": 68,
  "fieldMappings": [ ... ],
  "reviewRequired": true,
  "lowConfidenceFields": [ ... ],
  "suggestion": "Upload 5 sample pairs to improve accuracy to 85%+"
}
```

**Implementation location:** New method in `EdiMapTrainingEngine`:

```java
public TrainingResult generateFromSchema(String sourceFormat, String sourceType,
                                          Map<String, Object> targetSchema,
                                          String targetFormat, String targetType) {
    // 1. Load source field definitions from X12SegmentDefinitions/EdifactSegmentDefinitions
    // 2. Parse target schema into field list
    // 3. Run FieldEmbeddingEngine.findBestMatches()
    // 4. Apply type compatibility filtering
    // 5. Apply standard knowledge rules
    // 6. Return TrainingResult with DRAFT status
}
```

### 5.3 Correction Loop (Existing — Needs Maturity)

The `MappingCorrectionService` and `MappingCorrectionInterpreter` in ai-engine implement
natural-language corrections. A user says "Map BEG*03 to our PO number field" and the system
interprets and applies the change.

**Current flow:**

```
startSession(mapKey, sampleInput) -> sessionId
submitCorrection(sessionId, "Map BEG*03 to poNumber") -> preview of changes
submitCorrection(sessionId, "Change date format to ISO-8601") -> preview
approveSession(sessionId) -> new map version stored, cache invalidated
```

**Enhancements needed:**

1. **Visual diff after each correction.** Show before/after conversion output side by side, with
   changed fields highlighted. Currently the correction service computes the diff but the UI does
   not render it well.

2. **Undo support.** Each correction should be undoable within a session.

3. **Batch corrections.** Accept a CSV/table of field mappings to apply in one shot, instead of
   one natural-language instruction at a time.

4. **Correction templates.** Common corrections like "use ISO-8601 dates" or "zero-pad all IDs
   to 10 digits" should be one-click.

### 5.4 Incremental Learning (NEW)

Every successful production conversion is an implicit training signal. The system should learn
from production data to improve maps over time.

**Design:**

1. After each successful conversion (no errors, output validated), record:
   - Input hash (to avoid storing sensitive data)
   - Map ID + version used
   - Field coverage (applied/skipped/total)
   - Any self-healing repairs applied
   - Timestamp

2. Periodically (daily or on-demand), analyze conversion statistics:
   - Fields with high skip rate -> likely unmapped or incorrectly mapped
   - Fields that required self-healing -> map rule needs fixing
   - New segments appearing in production data that are not in the map

3. Generate improvement suggestions:
   ```json
   {
     "mapId": "STD-PO-001",
     "suggestions": [
       {
         "type": "NEW_FIELD",
         "sourceField": "TD5*05",
         "observation": "Seen in 85% of conversions, currently unmapped",
         "suggestedTarget": "shipping.carrier",
         "confidence": 72
       },
       {
         "type": "TRANSFORM_IMPROVEMENT",
         "sourceField": "BEG*05",
         "observation": "12% of inputs use MMDDYYYY format, current transform expects CCYYMMDD",
         "suggestedAction": "Add multi-format date parsing"
       }
     ]
   }
   ```

4. Store suggestions in ai-engine DB. Surface them in the UI for human review and one-click
   application.

**Implementation:**

- New entity: `ConversionMetric` in ai-engine (tracks per-conversion statistics)
- New service: `IncrementalLearningService` in ai-engine (analyzes metrics, generates suggestions)
- New endpoint: `GET /api/v1/edi/training/maps/{mapId}/suggestions`
- Scheduler: `@Scheduled(cron = "0 0 2 * * *")` — runs at 2 AM daily

### 5.5 AI-Assisted Map Generation via Claude API

The existing `AiMappingGenerator` already uses `ClaudeApiClient` for schema-based generation.
This should be enhanced:

1. **Standard map enrichment.** When a standard map has low coverage for a specific partner's
   documents, send the unmapped fields to Claude API with context about the document type, and
   get suggested mappings.

2. **Natural language map creation.** User describes what they need in English: "I need to convert
   Walmart's X12 850 to our SAP IDoc format. The PO number goes to BSEG-BELNR, the line items go
   to ITEM segments." Claude generates the field mappings.

3. **Conflict resolution.** When two training strategies disagree on a field mapping, send both
   options to Claude with sample data and get a reasoned recommendation.

**Guard rails:**
- Claude API is optional. The system works without it (pure Java strategies).
- All Claude-generated mappings start as DRAFT.
- Rate limiting: max 50 Claude API calls per training session.
- No sensitive production data sent to Claude — only field names, paths, and sample structures.

---

## Part 6: Partner Map Customization

### 6.1 Customization Workflow

```
Step 1: Browse Standard Maps
   User sees: "X12 850 -> PURCHASE_ORDER_INH (standard, 27 field mappings, 100% confidence)"
   
Step 2: Clone for Partner
   User clicks "Customize for ACME_CORP"
   System creates: partner-custom map based on STD-PO-001, partnerId=ACME_CORP
   
Step 3: Modify Field Mappings
   Visual editor shows source fields on left, target fields on right, lines connecting them
   User can:
   a) Drag a new connection (add mapping)
   b) Delete a connection (remove mapping)
   c) Click a connection to edit transform (change format, add code table lookup)
   d) Add a custom target field not in the standard schema
   e) Set conditional logic (only map if segment exists)
   
Step 4: Add Code Table Overrides
   ACME uses non-standard unit codes: "PC" for "Piece" instead of "EA" for "Each"
   User adds: X12_UNIT_OF_MEASURE override: {"PC": "Piece", "BG": "Bag"}
   
Step 5: Test with Sample Data
   User uploads an actual ACME 850
   System converts with the customized map
   User reviews output, makes corrections
   
Step 6: Activate
   User approves -> map status changes to ACTIVE
   All future ACME conversions use this map
```

### 6.2 Persistence Model

Partner custom maps are stored in the existing `edi_conversion_maps` table with:
- `partnerId` = "ACME_CORP"
- `mapKey` = "X12:850->INHOUSE:PURCHASE_ORDER_INH@ACME_CORP"
- `metadata.parentMapId` = "STD-PO-001" (the standard map it was cloned from)
- `metadata.origin` = "PARTNER_CUSTOM"

### 6.3 API Endpoints

```
POST   /api/v1/edi/maps/clone
       { "sourceMapId": "STD-PO-001", "partnerId": "ACME_CORP" }
       -> Creates partner-custom copy, returns new map

PUT    /api/v1/edi/maps/{mapId}/fields
       { "fieldMappings": [ ... updated list ... ] }
       -> Replaces field mappings, creates new version

POST   /api/v1/edi/maps/{mapId}/fields/add
       { "sourceField": "TD5*05", "targetField": "shipping.carrier", "transform": "DIRECT" }
       -> Adds a single field mapping

DELETE /api/v1/edi/maps/{mapId}/fields/{fieldIndex}
       -> Removes a specific field mapping

PUT    /api/v1/edi/maps/{mapId}/code-tables/{tableName}
       { "PC": "Piece", "BG": "Bag" }
       -> Override or extend a code table

POST   /api/v1/edi/maps/{mapId}/test
       { "sampleInput": "<X12 850 content>" }
       -> Run conversion with this map, return output + field coverage report

POST   /api/v1/edi/maps/{mapId}/activate
       -> Set status to ACTIVE, deactivate previous version

POST   /api/v1/edi/maps/{mapId}/rollback/{version}
       -> Rollback to a specific version
```

### 6.4 Inheritance and Diff

When a standard map is updated (new version shipped), partner custom maps based on it should:

1. Be notified that the parent map has a new version.
2. Show a diff: "Parent map STD-PO-001 v2 added 3 new field mappings. Your custom map is based
   on v1. Review and merge?"
3. Allow selective merge: accept new fields from parent, keep custom overrides.

This is similar to how git rebase works — the partner's customizations are their "commits" on top
of the standard map "base."

---

## Part 7: Comparison Suite Maturity

### 7.1 Current State

The `ComparisonSuiteService` handles:
- Directory scanning and file pairing by name
- Preview before running (shows matched pairs)
- Semantic diff via `SemanticDiffEngine`
- Session management with 2-hour TTL
- Report generation

### 7.2 Enhancements

#### 7.2.1 Field-Level Accuracy Scoring

Instead of just "changed/unchanged," compute per-field and aggregate accuracy:

```json
{
  "sessionId": "...",
  "overallAccuracy": 94.2,
  "fieldAccuracy": {
    "documentNumber": { "match": 100, "sampleCount": 50 },
    "documentDate": { "match": 98.0, "sampleCount": 50, "note": "1 date format mismatch" },
    "buyer.name": { "match": 100, "sampleCount": 50 },
    "lineItems[*].quantity": { "match": 96.0, "sampleCount": 250, "note": "10 rounding diffs" },
    "lineItems[*].unitPrice": { "match": 92.0, "sampleCount": 250, "note": "20 decimal precision diffs" }
  },
  "categories": {
    "EXACT_MATCH": 42,
    "SEMANTIC_MATCH": 5,
    "VALUE_DIFFERENCE": 2,
    "MISSING_IN_TARGET": 1,
    "EXTRA_IN_TARGET": 0
  }
}
```

#### 7.2.2 Semantic Comparison

Two values that represent the same business meaning but differ in format should be flagged as
SEMANTIC_MATCH, not DIFFERENCE:

| Left (Legacy) | Right (TranzFer) | Category | Reason |
|----------------|-------------------|----------|--------|
| `20230101` | `2023-01-01` | SEMANTIC_MATCH | Same date, different format |
| `100.00` | `100` | SEMANTIC_MATCH | Same number, different precision |
| `EA` | `Each` | SEMANTIC_MATCH | Same unit, code vs. description |
| `US` | `United States` | SEMANTIC_MATCH | Same country, code vs. name |
| `  ACME  ` | `ACME` | SEMANTIC_MATCH | Same value, different whitespace |

The `SemanticDiffEngine` already does segment-level comparison. It needs a new `FieldComparator`
that understands date formats, number formats, code tables, and whitespace normalization.

#### 7.2.3 Regression Test Persistence

Save test suites as named test collections in ai-engine DB:

```java
@Entity
@Table(name = "edi_regression_tests")
public class RegressionTestSuite {
    @Id private UUID id;
    private String name;           // "ACME PO Migration Tests"
    private String mapId;          // Map being tested
    private String partnerId;
    private int testCaseCount;
    private Instant lastRunAt;
    private int lastRunAccuracy;   // 0-100
    private String status;         // PASSING, FAILING, STALE
}

@Entity
@Table(name = "edi_regression_test_cases")
public class RegressionTestCase {
    @Id private UUID id;
    private UUID suiteId;
    private String inputContent;   // Source EDI
    private String expectedOutput; // Expected conversion result
    private String label;          // "ACME PO #12345"
}
```

Re-run a test suite after any map change:

```
POST /api/v1/edi/compare/regression/{suiteId}/run
-> Runs all test cases, compares actual vs. expected, returns accuracy report
```

#### 7.2.4 Batch Conversion + Comparison Workflow

One-click workflow for migration validation:

```
POST /api/v1/edi/compare/migration
{
  "legacyOutputDir": "/data/legacy-outputs",
  "sourceInputDir": "/data/source-files",
  "mapId": "STD-PO-001",
  "partnerId": "ACME_CORP"
}
```

The system:
1. Reads all source files from `sourceInputDir`
2. Converts each one using the specified map
3. Compares each TranzFer output against the corresponding legacy output
4. Produces aggregate accuracy report with per-file details

#### 7.2.5 Summary Report Format

```
====================================================================
MIGRATION VALIDATION REPORT
====================================================================
Map:          STD-PO-001 v1 (X12 850 -> PURCHASE_ORDER_INH)
Partner:      ACME_CORP
Files tested: 50
Date:         2026-04-10

OVERALL ACCURACY: 94.2%

FIELD-LEVEL SUMMARY:
  100% match:  documentNumber, buyer.name, buyer.id, seller.name, seller.id,
               currency, lineItems.lineNumber, lineItems.itemId
  95-99%:      documentDate (2 date format mismatches)
               lineItems.quantity (5 rounding differences)
  90-94%:      lineItems.unitPrice (8 decimal precision differences)
  <90%:        lineItems.description (15 truncation differences)

RECOMMENDATIONS:
  1. Add multi-format date parsing for documentDate (some inputs use MM/DD/YYYY)
  2. Increase decimal precision for unitPrice to 4 decimal places
  3. Increase lineItems.description max length from 80 to 200 characters

FILES WITH ISSUES:
  PO-12345.edi: 3 field mismatches (details below)
  PO-12389.edi: 1 missing segment (TD5 not in map)
  PO-12401.edi: 2 decimal precision issues

====================================================================
```

---

## Part 8: UI Redesign for EDI

### 8.1 Current State

`Edi.jsx` has 8 tabs: Convert, Explain, Self-Heal, Compliance, Diff, NL Create, AI Mapping,
Partners. The Convert tab has a target format dropdown (JSON, XML, CSV, YAML, FLAT, TIF) but no
source type or target document type selection.

`EdiTraining.jsx` has 3 tabs for the training pipeline.

### 8.2 New Tab Structure

Merge `Edi.jsx` and `EdiTraining.jsx` into a unified EDI page with 5 primary tabs:

#### Tab 1: Convert

The primary conversion interface. Must require document-type-aware conversion.

```
+---------------------------------------------------------------+
| EDI Converter                                                  |
+---------------------------------------------------------------+
| [Convert] [Maps] [Training] [Testing] [Partners]              |
+---------------------------------------------------------------+
|                                                                |
| Source Document:                                               |
| +-----------------------------------------------------------+ |
| | [Paste EDI content or upload file]                         | |
| |                                                             | |
| | ISA*00*          *00*          *ZZ*SENDER...               | |
| +-----------------------------------------------------------+ |
|                                                                |
| Auto-detected: X12 850 Purchase Order     [Load Sample]       |
|                                                                |
| Target Type:  [PURCHASE_ORDER_INH  v]                         |
| Partner:      [ACME_CORP           v]  (optional)             |
|                                                                |
| Map:  STD-PO-001 v1 (Standard) + ACME_CORP override v3       |
|       27 field mappings | 100% confidence | 1,234 uses        |
|                                                                |
| [Convert]  [Explain]  [Validate]  [Self-Heal]                 |
|                                                                |
| Result:                                                        |
| +-----------------------------------------------------------+ |
| | {                                                           | |
| |   "documentType": "PURCHASE_ORDER",                        | |
| |   "documentNumber": "PO123456",                            | |
| |   "documentDate": "2023-01-01",                            | |
| |   "buyer": { "name": "ACME CORP", "id": "BUYER001" },     | |
| |   ...                                                       | |
| | }                                                           | |
| +-----------------------------------------------------------+ |
|                                                                |
| Conversion Stats:                                              |
| Fields applied: 24/27 | Skipped: 3 (optional fields absent)   |
| Map: STD-PO-001 v1 (STANDARD) | Latency: 12ms                |
+---------------------------------------------------------------+
```

Key changes from current:
- **Target Type dropdown** replaces the generic format dropdown. Options are populated from the
  map library based on what maps exist for the detected source type.
- **Partner dropdown** (optional) to select partner-specific overrides.
- **Map info display** shows which map will be used, its confidence, and usage count.
- Explain, Validate, and Self-Heal are inline buttons (not separate tabs).

#### Tab 2: Maps

Browse, search, create, and edit maps.

```
+---------------------------------------------------------------+
| Maps                                                           |
+---------------------------------------------------------------+
| Search: [________________]  Filter: [All v] [Standard v]       |
+---------------------------------------------------------------+
| ID          | Source        | Target            | Status  | v  |
|-------------|---------------|-------------------|---------|----|
| STD-PO-001  | X12 850       | PURCHASE_ORDER_INH| ACTIVE  | 1  |
| STD-PO-002  | EDIFACT ORDERS| PURCHASE_ORDER_INH| ACTIVE  | 1  |
| STD-INV-001 | X12 810       | INVOICE_INH       | ACTIVE  | 1  |
| TRN-PO-ACME | X12 850       | SAP_IDOC          | ACTIVE  | 3  |
| TRN-INV-WAL | X12 810       | WALMART_INV       | DRAFT   | 1  |
+---------------------------------------------------------------+
|                                                                |
| [+ New Map]  [Import Map]  [+ Clone Standard Map for Partner]  |
|                                                                |
| -- Map Detail Panel (opens on click) --                        |
| STD-PO-001: X12 850 -> PURCHASE_ORDER_INH                     |
| Version 1 | CERTIFIED | 27 fields | 100% confidence           |
| Used 1,234 times | Last used 2 hours ago                       |
|                                                                |
| [Visual Editor]  [JSON View]  [Version History]  [Test]        |
+---------------------------------------------------------------+
```

The Visual Editor is a two-column layout:

```
+-----------------------------+-----------------------------+
| SOURCE (X12 850)            | TARGET (PURCHASE_ORDER_INH) |
|-----------------------------|------------------------------|
| BEG*03 PurchaseOrderNumber -|-> documentNumber             |
| BEG*05 PurchaseOrderDate  --|-> documentDate [DATE_REFORMAT]|
| N1[BY]*02 BuyerName       -|-> buyer.name                 |
| N1[BY]*04 BuyerID         -|-> buyer.id                   |
| N1[SE]*02 SellerName      -|-> seller.name                |
| PO1[*]*02 Quantity        --|-> lineItems[*].quantity [NUM]|
| PO1[*]*04 UnitPrice       --|-> lineItems[*].unitPrice [NUM]|
| PO1[*]*07 ProductCode     -|-> lineItems[*].itemId        |
| ...                         | ...                          |
+-----------------------------+-----------------------------+
| [+ Add Mapping]  [Save]  [Test with Sample]                |
+------------------------------------------------------------+
```

Lines between source and target fields are drawn as SVG paths. Color-coded by confidence:
- Green (90-100%): solid line
- Yellow (60-89%): dashed line
- Red (<60%): dotted line with warning icon

#### Tab 3: Training

The AI training pipeline (merged from `EdiTraining.jsx`).

```
+---------------------------------------------------------------+
| Training                                                       |
+---------------------------------------------------------------+
| Map Key: [X12:850->INHOUSE:PURCHASE_ORDER_INH@_default  v]    |
+---------------------------------------------------------------+
| Samples: 8 uploaded | Recommended: 5 more for CERTIFIED        |
|                                                                |
| [Upload Sample Pair]  [Bulk Upload]  [Generate from Schema]    |
|                                                                |
| Training Sessions:                                              |
| Session #3  |  8 samples  |  87% confidence  |  TESTED        |
| Session #2  |  5 samples  |  72% confidence  |  DEPRECATED    |
| Session #1  |  3 samples  |  55% confidence  |  DEPRECATED    |
|                                                                |
| Latest Map: v3 | 22 fields | 87% confidence                   |
| Low confidence fields:                                          |
|   - REF*02 -> references[0].value (55%)                        |
|   - PER*04 -> contacts[0].phone (45%)                          |
|                                                                |
| [Train New Version]  [Approve & Activate]  [Rollback to v2]    |
|                                                                |
| -- Correction Session --                                       |
| [Start Correction Session] for fine-tuning via natural language |
+---------------------------------------------------------------+
```

#### Tab 4: Testing

Comparison suite and regression testing.

```
+---------------------------------------------------------------+
| Testing                                                        |
+---------------------------------------------------------------+
| [Quick Compare]  [Migration Validation]  [Regression Suites]   |
+---------------------------------------------------------------+
|                                                                |
| Quick Compare:                                                  |
| Left:  [paste or upload]     Right: [paste or upload]          |
| [Compare]                                                      |
|                                                                |
| Migration Validation:                                           |
| Source Dir:  [/data/source-files  ]                            |
| Legacy Dir:  [/data/legacy-outputs]                            |
| Map:         [STD-PO-001 v]  Partner: [ACME_CORP v]           |
| [Run Validation]                                               |
|                                                                |
| Regression Suites:                                              |
| +------------------------------------------------------+       |
| | ACME PO Tests      | 50 cases | PASSING | 94.2%     |       |
| | Walmart INV Tests   | 120 cases | FAILING | 88.1%   |       |
| | Standard 850 Tests  | 30 cases | PASSING | 99.8%    |       |
| +------------------------------------------------------+       |
| [+ Create Suite]  [Run All]  [View Report]                     |
+---------------------------------------------------------------+
```

#### Tab 5: Partners

Per-partner map customization and management.

```
+---------------------------------------------------------------+
| Partners                                                       |
+---------------------------------------------------------------+
| Search: [________________]                                     |
+---------------------------------------------------------------+
| Partner      | Format    | Maps | Documents | Last Active      |
|-------------|-----------|------|-----------|------------------|
| ACME_CORP   | X12 005010| 3    | 1,234     | 2 hours ago      |
| WALMART     | X12 005010| 5    | 12,456    | 5 minutes ago    |
| BOSCH_DE    | EDIFACT D01B| 2  | 567       | 1 day ago        |
+---------------------------------------------------------------+
|                                                                |
| ACME_CORP Detail:                                              |
| Sender: ZZ/ACME001 | Receiver: ZZ/OURCO001 | X12 005010       |
|                                                                |
| Custom Maps:                                                    |
|   X12 850 -> PURCHASE_ORDER_INH  (based on STD-PO-001, +4 overrides)|
|   X12 810 -> INVOICE_INH         (based on STD-INV-001, +2 overrides)|
|   X12 856 -> SHIP_NOTICE_INH     (standard, no overrides)     |
|                                                                |
| [+ Customize Map]  [Upload Sample]  [View Conversion History]  |
| [Analyze New Sample]  [Export Partner Config]                   |
+---------------------------------------------------------------+
```

### 8.3 Deprecated UI Elements

Remove from the Convert tab:
- The generic target format dropdown (JSON, XML, CSV) — replaced by document type dropdown
- The "Canonical" button — canonical model becomes internal only
- The "NL Create" tab — keep as a feature within Convert (button, not tab)

### 8.4 UI Component Architecture

```
ui-service/src/pages/
  Edi.jsx                     # REWRITE: 5-tab layout
  EdiTraining.jsx             # DEPRECATE: merge into Edi.jsx Tab 3

ui-service/src/components/edi/
  EdiConvertTab.jsx           # NEW: Tab 1 — document-type-aware conversion
  EdiMapsTab.jsx              # NEW: Tab 2 — map browser + visual editor
  EdiTrainingTab.jsx          # NEW: Tab 3 — training pipeline
  EdiTestingTab.jsx           # NEW: Tab 4 — comparison + regression
  EdiPartnersTab.jsx          # NEW: Tab 5 — partner management
  VisualMapEditor.jsx         # NEW: two-column drag-and-drop map editor
  MapFieldList.jsx            # NEW: source/target field list component
  MapConnection.jsx           # NEW: SVG line between source and target fields
  FieldMappingDialog.jsx      # NEW: dialog for editing transform/condition
  ComparisonReport.jsx        # NEW: rendered comparison report
  RegressionSuitePanel.jsx    # NEW: regression test suite management
```

---

## Part 9: Flow Integration Enhancement

### 9.1 Current State

The `CONVERT_EDI` step in `FlowProcessingEngine` calls the edi-converter's `/convert/trained`
endpoint. Configuration is:

```java
// Config: {"targetFormat": "JSON|XML|CSV", "partnerId": "optional-partner-id"}
```

This has the same problem as the UI — it uses `targetFormat` (generic format) instead of
`targetType` (document type).

### 9.2 Enhanced CONVERT_EDI Step

The flow step configuration should change to:

```json
{
  "type": "CONVERT_EDI",
  "config": {
    "targetType": "PURCHASE_ORDER_INH",
    "targetFormat": "JSON",
    "partnerId": "ACME_CORP",
    "mapId": null,
    "failOnLowConfidence": true,
    "confidenceThreshold": 80,
    "selfHealOnError": true,
    "validateOutput": true
  }
}
```

### 9.3 Enhanced Conversion Flow

```java
private String callEdiConverter(Path input, Path workDir, Map<String, String> cfg, String trackId) {
    // 1. Read input file
    String content = Files.readString(input);

    // 2. Auto-detect source document type
    // POST /api/v1/convert/detect -> {format: "X12", documentType: "850"}

    // 3. Resolve map
    // The converter's MapResolver handles: partner-custom > trained > standard

    // 4. Convert using the resolved map
    // POST /api/v1/convert/map
    // {
    //   "content": "...",
    //   "targetType": "PURCHASE_ORDER_INH",
    //   "targetFormat": "JSON",
    //   "partnerId": "ACME_CORP"
    // }

    // 5. Validate output
    // If failOnLowConfidence=true and map confidence < threshold, fail the step
    // If validateOutput=true, check required fields are present

    // 6. Self-heal on error
    // If conversion fails and selfHealOnError=true, run /heal first then retry

    // 7. Record metrics
    // POST /api/v1/analytics/conversion-metric
    // { mapId, sourceType, targetType, partnerId, latencyMs, fieldCoverage, success }

    // 8. Write output file
}
```

### 9.4 New API Endpoint for Flow Integration

```
POST /api/v1/convert/map
{
  "content": "<raw EDI>",
  "sourceFormat": "X12",       // optional, auto-detected if absent
  "sourceType": "850",          // optional, auto-detected if absent
  "targetType": "PURCHASE_ORDER_INH",  // REQUIRED
  "targetFormat": "JSON",       // output serialization format
  "partnerId": "ACME_CORP",    // optional
  "mapId": null,                // optional, overrides auto-resolution
  "options": {
    "selfHeal": true,
    "validateOutput": true,
    "includeMetrics": true
  }
}

Response:
{
  "output": "{ ... converted document ... }",
  "sourceFormat": "X12",
  "sourceType": "850",
  "targetType": "PURCHASE_ORDER_INH",
  "mapUsed": {
    "mapId": "STD-PO-001",
    "version": 1,
    "origin": "STANDARD",
    "confidence": 100
  },
  "metrics": {
    "fieldsApplied": 24,
    "fieldsSkipped": 3,
    "totalMappings": 27,
    "latencyMs": 12,
    "selfHealApplied": false
  },
  "validation": {
    "valid": true,
    "requiredFieldsPresent": 7,
    "requiredFieldsMissing": 0
  }
}
```

### 9.5 Backward Compatibility

The existing `/convert/trained` and `/convert/convert` endpoints remain functional but are
deprecated in favor of `/convert/map`. The old endpoints will internally delegate to the new
`MapResolver` logic when possible and fall back to the existing behavior otherwise.

### 9.6 Conversion Metrics

Every conversion through `/convert/map` records:

```java
@Entity
@Table(name = "edi_conversion_metrics")
public class ConversionMetric {
    @Id private UUID id;
    private String mapId;
    private int mapVersion;
    private String sourceFormat;
    private String sourceType;
    private String targetType;
    private String partnerId;
    private int fieldsApplied;
    private int fieldsSkipped;
    private int totalMappings;
    private long latencyMs;
    private boolean selfHealApplied;
    private boolean validationPassed;
    private String trackId;          // links to file transfer tracking
    private Instant convertedAt;
}
```

This feeds the incremental learning system (Part 5.4) and provides dashboards:
- Conversion volume by map, partner, document type
- Average latency and field coverage
- Error rates and self-healing frequency
- Map usage trends (which maps are used most)

---

## Part 10: Implementation Phases

### Phase 1: Standard Map Library + Map-Aware Converter (4-5 weeks)

**Goal:** Ship 95% coverage out of the box. Convert using maps, not generic format transforms.

**Deliverables:**

1. **Standard map JSON files** (36 maps) in `edi-converter/src/main/resources/maps/standard/`
   - Start with P0 maps (18 maps for PO, INV, ASN, PAY, ACK)
   - Include INHOUSE JSON schemas in `maps/schemas/`
   - Include code tables in `maps/code-tables/`

2. **StandardMapLibrary service** in edi-converter
   - Lazy loading from classpath
   - Index of available maps
   - Lookup by source+target combination

3. **MapResolver service** in edi-converter
   - Cascade: partner-custom -> trained -> standard
   - Returns ResolvedMap with origin and confidence

4. **MapEngine service** in edi-converter
   - Replaces current generic conversion for map-aware paths
   - Applies field mappings with all 14 transform types
   - Handles loops (PO1, N1 qualified, REF, DTM)
   - Applies code table lookups
   - Applies default values for absent fields
   - Validates output against required fields

5. **New API endpoint:** `POST /api/v1/convert/map`
   - Accepts targetType + partnerId
   - Uses MapResolver + MapEngine
   - Returns structured response with metrics

6. **CONVERT_EDI flow step enhancement**
   - Accept targetType in config
   - Use `/convert/map` endpoint
   - Record conversion metrics

7. **UI: Convert tab update**
   - Replace format dropdown with document type dropdown
   - Add partner dropdown
   - Show map info (which map, confidence, field count)

**Definition of Done:**
- `mvn test` passes with new unit tests for StandardMapLibrary, MapResolver, MapEngine
- A sample X12 850 converts to PURCHASE_ORDER_INH using STD-PO-001 with 95%+ field coverage
- Flow CONVERT_EDI step works with targetType config
- Existing /convert/convert and /convert/trained endpoints still work (backward compat)

### Phase 2: Visual Map Editor + Partner Customization (3-4 weeks)

**Goal:** Partners can customize standard maps through the UI.

**Deliverables:**

1. **Visual map editor** (`VisualMapEditor.jsx`)
   - Two-column layout: source fields / target fields
   - SVG connection lines between mapped fields
   - Drag-and-drop to add/remove mappings
   - Click to edit transforms, conditions, defaults
   - Color-coded by confidence

2. **Map clone API** for partner customization
   - Clone standard map with partnerId scope
   - Store in ai-engine DB via existing ConversionMap entity

3. **Map CRUD API** in ai-engine
   - Add/remove/modify field mappings
   - Code table overrides
   - Version management

4. **Partner management tab** in UI
   - Browse partners
   - View partner-specific maps
   - Link to map editor

5. **Maps tab** in UI
   - Browse all maps (standard, trained, custom)
   - Search/filter by source/target/partner
   - Version history per map

**Definition of Done:**
- User can clone STD-PO-001 for ACME_CORP, add a custom field mapping, test it, activate it
- ACME_CORP conversions use the custom map, other partners use the standard map
- Maps tab shows all 36 standard maps + any trained/custom maps

### Phase 3: AI Improvements (3-4 weeks)

**Goal:** Make map creation faster and more accurate.

**Deliverables:**

1. **Schema-based map generation** in ai-engine
   - New endpoint: `POST /api/v1/edi/training/generate-from-schema`
   - Generates initial map from source+target schemas (no samples needed)
   - Returns DRAFT map with confidence scores

2. **Confidence threshold enforcement**
   - Auto-status assignment based on confidence (DRAFT/TESTED/CERTIFIED)
   - UI shows sample count recommendations

3. **Enhanced correction session**
   - Visual diff after each correction
   - Undo support
   - Batch corrections via CSV
   - Correction templates (common patterns)

4. **Incremental learning service**
   - ConversionMetric entity + daily analysis
   - Improvement suggestions per map
   - UI surface for reviewing and applying suggestions

5. **Claude API enhancements** (optional, behind feature flag)
   - Standard map enrichment
   - Natural language map creation
   - Conflict resolution

**Definition of Done:**
- User can generate a DRAFT map from schemas alone (no samples)
- Maps below 80% confidence are not auto-activated
- Correction session shows before/after visual diff
- Daily incremental learning job produces actionable suggestions

### Phase 4: Comparison Suite Maturity + Regression Testing (2-3 weeks)

**Goal:** Production-grade migration validation and ongoing regression testing.

**Deliverables:**

1. **Field-level accuracy scoring** in ComparisonSuiteService
   - Per-field match percentages
   - Aggregate accuracy across file batches

2. **Semantic comparison** in SemanticDiffEngine
   - Date format normalization
   - Number precision normalization
   - Code table bidirectional matching
   - Whitespace normalization

3. **Regression test persistence** in ai-engine
   - RegressionTestSuite + RegressionTestCase entities
   - CRUD APIs for test suites
   - Run suite + report generation

4. **Batch conversion + comparison workflow**
   - One-click migration validation
   - Aggregate summary report

5. **Testing tab** in UI
   - Quick compare (two documents)
   - Migration validation wizard
   - Regression suite management

**Definition of Done:**
- Migration validation of 50 files produces a report with per-field accuracy
- Semantic comparison correctly identifies date/number format differences as MATCH
- Regression suite of 30 test cases runs in under 10 seconds
- Test suite status (PASSING/FAILING) visible in UI

### Phase 5: Performance Optimization + Metrics (2 weeks)

**Goal:** Faster than legacy tools, with published benchmarks.

**Deliverables:**

1. **Performance benchmark suite**
   - Convert 1,000 X12 850s (varying complexity) and measure:
     - Throughput (documents/second)
     - Latency (p50, p95, p99)
     - Memory usage
   - Compare against published Sterling Integrator benchmarks

2. **Map engine optimizations**
   - Pre-compile field path lookups (avoid string splitting on every conversion)
   - Pre-compile code table lookups as `Map<String, String>` instead of JSON parsing
   - Pre-compile loop definitions as segment index ranges
   - Object pooling for intermediate data structures

3. **Conversion metrics dashboard**
   - Volume, latency, error rates by map/partner/document type
   - Trend charts over time
   - Alert thresholds for degradation

4. **Map preloading** (optional, off by default)
   - Configuration to eagerly load high-usage maps at startup
   - `edi.maps.preload=STD-PO-001,STD-INV-001` (opt-in list)

**Definition of Done:**
- Benchmark: >5,000 conversions/second for standard X12 850 on single instance
- Benchmark: p99 latency <50ms for standard maps
- Metrics dashboard shows real-time conversion statistics
- Published benchmark document comparing TranzFer vs. industry standards

### Timeline Summary

| Phase | Duration | Dependencies | Key Deliverable |
|-------|----------|-------------|-----------------|
| Phase 1 | 4-5 weeks | None | Standard maps + map-aware converter |
| Phase 2 | 3-4 weeks | Phase 1 | Visual map editor + partner customization |
| Phase 3 | 3-4 weeks | Phase 1 | AI schema generation + incremental learning |
| Phase 4 | 2-3 weeks | Phase 1 | Comparison suite + regression testing |
| Phase 5 | 2 weeks | Phase 1-4 | Performance benchmarks + metrics |
| **Total** | **14-18 weeks** | | **Production-grade EDI conversion platform** |

Phases 2, 3, and 4 can run in parallel after Phase 1 completes. Phase 5 is the capstone.

---

## Part 11: File Inventory

### 11.1 New Files to Create

#### edi-converter: Standard Map Library

```
edi-converter/src/main/resources/maps/
  standard/
    STD-PO-001.json           # X12 850 -> PURCHASE_ORDER_INH
    STD-PO-002.json           # EDIFACT ORDERS -> PURCHASE_ORDER_INH
    STD-PO-003.json           # X12 850 <-> EDIFACT ORDERS
    STD-PO-004.json           # PURCHASE_ORDER_INH -> X12 850
    STD-PO-005.json           # PURCHASE_ORDER_INH -> EDIFACT ORDERS
    STD-PO-006.json           # X12 860 -> PURCHASE_ORDER_INH
    STD-INV-001.json          # X12 810 -> INVOICE_INH
    STD-INV-002.json          # EDIFACT INVOIC -> INVOICE_INH
    STD-INV-003.json          # X12 810 <-> EDIFACT INVOIC
    STD-INV-004.json          # INVOICE_INH -> X12 810
    STD-INV-005.json          # INVOICE_INH -> EDIFACT INVOIC
    STD-ASN-001.json          # X12 856 -> SHIP_NOTICE_INH
    STD-ASN-002.json          # EDIFACT DESADV -> SHIP_NOTICE_INH
    STD-ASN-003.json          # X12 856 <-> EDIFACT DESADV
    STD-ASN-004.json          # SHIP_NOTICE_INH -> X12 856
    STD-ASN-005.json          # SHIP_NOTICE_INH -> EDIFACT DESADV
    STD-PAY-001.json          # X12 820 -> PAYMENT_INH
    STD-PAY-002.json          # EDIFACT REMADV -> PAYMENT_INH
    STD-PAY-003.json          # X12 820 <-> EDIFACT REMADV
    STD-PAY-004.json          # PAYMENT_INH -> X12 820
    STD-PAY-005.json          # PAYMENT_INH -> EDIFACT REMADV
    STD-ACK-001.json          # X12 997 -> ACKNOWLEDGMENT_INH
    STD-ACK-002.json          # X12 999 -> ACKNOWLEDGMENT_INH
    STD-ACK-003.json          # EDIFACT CONTRL -> ACKNOWLEDGMENT_INH
    STD-ACK-004.json          # EDIFACT APERAK -> ACKNOWLEDGMENT_INH
    STD-POA-001.json          # X12 855 -> PURCHASE_ORDER_INH
    STD-POA-002.json          # EDIFACT ORDRSP -> PURCHASE_ORDER_INH
    STD-POA-003.json          # X12 855 <-> EDIFACT ORDRSP
    STD-RCV-001.json          # X12 861 -> SHIP_NOTICE_INH
    STD-RCV-002.json          # X12 861 -> EDIFACT DESADV
    STD-PLN-001.json          # X12 830 -> PURCHASE_ORDER_INH
    STD-PLN-002.json          # X12 840 -> PURCHASE_ORDER_INH
    STD-LOG-001.json          # EDIFACT IFTMIN -> SHIP_NOTICE_INH
    STD-LOG-002.json          # EDIFACT CUSREP -> ACKNOWLEDGMENT_INH
    STD-XF-001.json           # X12 864 -> EDIFACT free text
    STD-XF-002.json           # X12 843 -> EDIFACT ORDRSP
  schemas/
    PURCHASE_ORDER_INH.schema.json
    INVOICE_INH.schema.json
    SHIP_NOTICE_INH.schema.json
    PAYMENT_INH.schema.json
    ACKNOWLEDGMENT_INH.schema.json
  code-tables/
    X12_PURPOSE_CODE.json
    X12_ID_QUALIFIER.json
    X12_UNIT_OF_MEASURE.json
    X12_PAYMENT_METHOD.json
    X12_SHIPMENT_STATUS.json
    EDIFACT_QUALIFIER.json
    EDIFACT_MESSAGE_FUNCTION.json
    EDIFACT_UNIT_OF_MEASURE.json
    COUNTRY_CODES.json
    CURRENCY_CODES.json
```

#### edi-converter: New Java Services (Phase 1)

```
edi-converter/src/main/java/com/filetransfer/edi/
  service/
    StandardMapLibrary.java       # Loads standard maps from classpath
    MapResolver.java              # Cascade: partner > trained > standard
    MapEngine.java                # Applies maps with transforms, loops, code tables
    MapSerializer.java            # Serializes mapped output to target format (JSON/XML/CSV)
  model/
    StandardMap.java              # POJO for deserialized standard map JSON
    ResolvedMap.java              # Result of map resolution (map + origin + confidence)
    MapFieldMapping.java          # Field mapping with transform + condition
    MapCodeTable.java             # Code table (source code -> target value)
    MapLoopDefinition.java        # Loop definition (trigger segment, child segments, target array)
    MapTransform.java             # Transform enum with apply() method
    ConversionRequest.java        # Input DTO for /convert/map endpoint
    ConversionResponse.java       # Output DTO for /convert/map endpoint
```

#### edi-converter: New Tests (Phase 1)

```
edi-converter/src/test/java/com/filetransfer/edi/
  service/
    StandardMapLibraryTest.java   # Tests map loading, index, lookup
    MapResolverTest.java          # Tests cascade resolution
    MapEngineTest.java            # Tests field mapping, transforms, loops, code tables
    MapSerializerTest.java        # Tests output serialization
  integration/
    X12_850_ConversionTest.java   # End-to-end: X12 850 -> PURCHASE_ORDER_INH
    EDIFACT_ORDERS_ConversionTest.java
    X12_810_ConversionTest.java
    CrossFormat_850_ORDERS_Test.java
    PartnerOverride_ConversionTest.java
```

#### ai-engine: New Services (Phase 3-4)

```
ai-engine/src/main/java/com/filetransfer/ai/
  entity/edi/
    ConversionMetric.java         # Per-conversion statistics
    RegressionTestSuite.java      # Named test collection
    RegressionTestCase.java       # Individual test case (input + expected output)
    MapSuggestion.java            # AI-generated improvement suggestion
  repository/edi/
    ConversionMetricRepository.java
    RegressionTestSuiteRepository.java
    RegressionTestCaseRepository.java
    MapSuggestionRepository.java
  service/edi/
    SchemaBasedMapGenerator.java  # Generate maps from schemas (no samples)
    IncrementalLearningService.java  # Analyze metrics, generate suggestions
    RegressionTestService.java    # Run test suites, compare results
    MapCloneService.java          # Clone maps for partner customization
  controller/
    MapManagementController.java  # CRUD for maps, clone, version, activate
    RegressionTestController.java # CRUD for test suites, run
    ConversionMetricController.java  # Query metrics, dashboards
```

#### ai-engine: New Flyway Migrations (Phase 3-4)

```
ai-engine/src/main/resources/db/migration/
  V47__add_conversion_metrics_table.sql
  V48__add_regression_test_tables.sql
  V49__add_map_suggestions_table.sql
  V50__add_map_status_column.sql       # Add 'status' to edi_conversion_maps
  V51__add_parent_map_id_column.sql    # Add 'parentMapId' for cloned maps
```

*Note: Migration numbers V47+ follow the Flyway convention for local migrations above the shared-platform JAR range (V46+).*

#### ui-service: New Components (Phase 1-4)

```
ui-service/src/pages/
  Edi.jsx                         # REWRITE with 5-tab structure

ui-service/src/components/edi/
  EdiConvertTab.jsx               # Tab 1: document-type-aware conversion
  EdiMapsTab.jsx                  # Tab 2: map browser
  EdiTrainingTab.jsx              # Tab 3: training pipeline (from EdiTraining.jsx)
  EdiTestingTab.jsx               # Tab 4: comparison + regression
  EdiPartnersTab.jsx              # Tab 5: partner management
  VisualMapEditor.jsx             # Two-column visual map editor
  MapFieldList.jsx                # Scrollable field list (source or target)
  MapConnectionSvg.jsx            # SVG layer for connection lines
  FieldMappingDialog.jsx          # Modal for editing a single field mapping
  CodeTableEditor.jsx             # Inline editor for code table overrides
  ComparisonReport.jsx            # Rendered comparison report
  RegressionSuitePanel.jsx        # Regression test suite list + runner
  MapVersionHistory.jsx           # Version list with rollback/diff
  ConversionMetricsDashboard.jsx  # Charts for conversion volume, latency, errors
```

### 11.2 Files to Modify

#### edi-converter

| File | Change |
|------|--------|
| `EdiConverterController.java` | Add `/convert/map` endpoint, deprecate `/convert/convert` for map-aware flows |
| `TrainedMapConsumer.java` | Add targetType to cache key (currently only targetFormat) |
| `UniversalConverter.java` | Delegate to MapEngine when map is available |
| `CanonicalMapper.java` | Make `toCanonical()` and `fromCanonical()` internal (package-private) |
| `ComparisonSuiteService.java` | Add field-level accuracy scoring, semantic comparison |
| `SemanticDiffEngine.java` | Add FieldComparator with date/number/code normalization |
| `PartnerProfileManager.java` | Persist to ai-engine DB instead of in-memory map |
| `application.yml` | Add `edi.maps.standard.enabled: true`, `edi.maps.preload: []` |

#### ai-engine

| File | Change |
|------|--------|
| `ConversionMap.java` | Add `status` (DRAFT/TESTED/CERTIFIED/ACTIVE/DEPRECATED), `parentMapId`, `origin` (STANDARD/TRAINED/PARTNER_CUSTOM) columns |
| `ConversionMapRepository.java` | Add queries: `findByStatusAndPartnerId`, `findByParentMapId` |
| `TrainedMapStore.java` | Add status enforcement, clone support, suggestion generation |
| `EdiMapTrainingEngine.java` | Add schema-based generation, confidence threshold, enhanced reporting |
| `EdiMapTrainingController.java` | Add `/generate-from-schema`, `/maps/{id}/suggestions` endpoints |
| `MappingCorrectionService.java` | Add undo support, batch corrections, visual diff |

#### shared-platform

| File | Change |
|------|--------|
| `FlowProcessingEngine.java` | Update `callEdiConverter()` and `refConvertEdi()` to use `targetType` config and call `/convert/map` |

#### ui-service

| File | Change |
|------|--------|
| `Edi.jsx` | Complete rewrite with 5-tab structure |
| `EdiTraining.jsx` | Deprecate (content merged into Edi.jsx Tab 3) |
| `client.js` (API client) | Add new endpoints for map CRUD, regression tests, metrics |
| `Flows.jsx` | Update CONVERT_EDI step config UI to show targetType + partnerId |

### 11.3 Configuration Changes

#### edi-converter application.yml

```yaml
edi:
  maps:
    standard:
      enabled: true                    # Load standard maps from classpath
      path: "classpath:maps/standard"  # Directory containing standard map JSON files
    preload: []                        # List of map IDs to preload at startup (empty = lazy only)
    cache:
      ttl: 300                         # TTL in seconds for trained map cache (default: 5 minutes)
      max-size: 500                    # Maximum number of maps in cache
  conversion:
    self-heal-on-error: true           # Auto-heal before retry on conversion failure
    validate-output: true              # Validate required fields in output
    confidence-threshold: 80           # Minimum map confidence for auto-use (below = warning)
    record-metrics: true               # Record conversion metrics to ai-engine
  code-tables:
    path: "classpath:maps/code-tables" # Directory containing code table JSON files
```

#### docker-compose.yml (CONVERT_EDI step example)

```yaml
# Example flow step configuration
# flows:
#   - type: CONVERT_EDI
#     config:
#       targetType: PURCHASE_ORDER_INH
#       targetFormat: JSON
#       partnerId: ACME_CORP
#       selfHealOnError: true
#       validateOutput: true
```

### 11.4 File Count Summary

| Category | New Files | Modified Files |
|----------|-----------|---------------|
| Standard map JSONs | 36 | 0 |
| Schema JSONs | 5 | 0 |
| Code table JSONs | 10 | 0 |
| edi-converter Java (services) | 8 | 7 |
| edi-converter Java (models) | 8 | 0 |
| edi-converter Java (tests) | 9 | 0 |
| ai-engine Java (entities) | 4 | 1 |
| ai-engine Java (repositories) | 4 | 1 |
| ai-engine Java (services) | 4 | 3 |
| ai-engine Java (controllers) | 3 | 1 |
| ai-engine Flyway migrations | 5 | 0 |
| ui-service components | 14 | 3 |
| shared-platform | 0 | 1 |
| Config files | 0 | 2 |
| **Total** | **110** | **19** |

---

## Appendix A: Code Table Format

Each code table is a standalone JSON file:

```json
{
  "tableId": "X12_UNIT_OF_MEASURE",
  "name": "X12 Unit of Measure Codes",
  "source": "ASC X12 Standard",
  "bidirectional": true,
  "entries": {
    "EA": "Each",
    "CA": "Case",
    "BX": "Box",
    "PK": "Pack",
    "DZ": "Dozen",
    "LB": "Pound",
    "KG": "Kilogram",
    "FT": "Foot",
    "IN": "Inch",
    "GA": "Gallon",
    "LT": "Liter",
    "OZ": "Ounce",
    "YD": "Yard",
    "M": "Meter",
    "CM": "Centimeter",
    "MM": "Millimeter",
    "SQ": "Square",
    "CF": "Cubic Foot",
    "CY": "Cubic Yard",
    "RL": "Roll",
    "CT": "Carton",
    "PL": "Pallet",
    "DR": "Drum",
    "TN": "Ton (Short)",
    "MT": "Metric Ton"
  }
}
```

## Appendix B: EDIFACT ORDERS -> PURCHASE_ORDER_INH Map Example

```json
{
  "mapId": "STD-PO-002",
  "name": "EDIFACT ORDERS to Internal Purchase Order",
  "version": 1,
  "status": "CERTIFIED",
  "source": {
    "format": "EDIFACT",
    "transactionSet": "ORDERS",
    "version": "D01B"
  },
  "target": {
    "format": "INHOUSE",
    "documentType": "PURCHASE_ORDER_INH",
    "version": "1.0"
  },
  "fieldMappings": [
    {
      "sourceField": "BGM+220+{C106.1004}",
      "targetField": "documentNumber",
      "transform": "DIRECT",
      "confidence": 100,
      "required": true,
      "description": "Document/message number from BGM segment"
    },
    {
      "sourceField": "DTM+137:{C507.2380}:102",
      "targetField": "documentDate",
      "transform": "DATE_REFORMAT",
      "transformParam": "yyyyMMdd->yyyy-MM-dd",
      "confidence": 100,
      "required": true,
      "description": "Document date from DTM+137 (format 102 = CCYYMMDD)"
    },
    {
      "sourceField": "NAD+BY+{C082.3039}",
      "targetField": "buyer.id",
      "transform": "DIRECT",
      "confidence": 100,
      "required": true,
      "description": "Buyer identifier from NAD+BY"
    },
    {
      "sourceField": "NAD+BY+++{C080.3036}",
      "targetField": "buyer.name",
      "transform": "DIRECT",
      "confidence": 100,
      "required": true,
      "description": "Buyer name from NAD+BY party name"
    },
    {
      "sourceField": "NAD+SU+{C082.3039}",
      "targetField": "seller.id",
      "transform": "DIRECT",
      "confidence": 100,
      "required": true,
      "description": "Supplier/seller identifier from NAD+SU"
    },
    {
      "sourceField": "NAD+SU+++{C080.3036}",
      "targetField": "seller.name",
      "transform": "DIRECT",
      "confidence": 100,
      "required": true,
      "description": "Supplier/seller name from NAD+SU"
    },
    {
      "sourceField": "NAD+DP+{C082.3039}",
      "targetField": "shipTo.id",
      "transform": "DIRECT",
      "confidence": 95,
      "required": false,
      "description": "Delivery party identifier from NAD+DP"
    },
    {
      "sourceField": "CUX+2:{6345}:4",
      "targetField": "currency",
      "transform": "DIRECT",
      "confidence": 100,
      "required": false,
      "defaultValue": "EUR",
      "description": "Currency from CUX segment (defaults to EUR for EDIFACT)"
    },
    {
      "sourceField": "LIN+{1082}",
      "targetField": "lineItems[*].lineNumber",
      "transform": "PARSE_NUMBER",
      "confidence": 100,
      "required": true,
      "loop": "LIN",
      "description": "Line item number from LIN segment"
    },
    {
      "sourceField": "LIN+++{C212.7140}:{C212.7143}",
      "targetField": "lineItems[*].itemId",
      "transform": "DIRECT",
      "confidence": 95,
      "required": false,
      "loop": "LIN",
      "description": "Item number from LIN segment (EAN, buyer's article, etc.)"
    },
    {
      "sourceField": "QTY+21:{C186.6060}",
      "targetField": "lineItems[*].quantity",
      "transform": "PARSE_NUMBER",
      "confidence": 100,
      "required": true,
      "loop": "LIN",
      "description": "Ordered quantity from QTY+21"
    },
    {
      "sourceField": "QTY+21::{6411}",
      "targetField": "lineItems[*].unitOfMeasure",
      "transform": "CODE_TABLE",
      "transformParam": "EDIFACT_UNIT_OF_MEASURE",
      "confidence": 95,
      "required": false,
      "loop": "LIN",
      "description": "Measurement unit from QTY segment"
    },
    {
      "sourceField": "PRI+AAA:{5118}",
      "targetField": "lineItems[*].unitPrice",
      "transform": "PARSE_NUMBER",
      "confidence": 100,
      "required": true,
      "loop": "LIN",
      "description": "Calculation net price from PRI+AAA"
    },
    {
      "sourceField": "IMD+F++:::{7008}",
      "targetField": "lineItems[*].description",
      "transform": "DIRECT",
      "confidence": 90,
      "required": false,
      "loop": "LIN",
      "description": "Item description from IMD segment"
    },
    {
      "sourceField": "MOA+86:{5004}",
      "targetField": "totals.totalAmount",
      "transform": "PARSE_NUMBER",
      "confidence": 100,
      "required": false,
      "description": "Total message amount from MOA+86"
    },
    {
      "sourceField": "RFF+ON:{C506.1154}",
      "targetField": "references[*].value",
      "transform": "DIRECT",
      "confidence": 100,
      "required": false,
      "loop": "RFF",
      "description": "Order reference number from RFF+ON"
    }
  ],
  "codeTables": {
    "EDIFACT_UNIT_OF_MEASURE": {
      "PCE": "Each",
      "KGM": "Kilogram",
      "LTR": "Liter",
      "MTR": "Meter",
      "GRM": "Gram",
      "TNE": "Metric Ton",
      "SET": "Set",
      "PR": "Pair",
      "BX": "Box",
      "CT": "Carton",
      "PK": "Package"
    }
  },
  "loopDefinitions": {
    "LIN": {
      "triggerSegment": "LIN",
      "childSegments": ["PIA", "IMD", "MEA", "QTY", "DTM", "MOA", "PRI", "RFF", "LOC", "TAX", "NAD", "ALC"],
      "targetArrayField": "lineItems"
    },
    "NAD": {
      "triggerSegment": "NAD",
      "childSegments": ["LOC", "FII", "RFF", "CTA", "COM"],
      "qualifierElement": "NAD+{3035}",
      "qualifierMapping": {
        "BY": "buyer",
        "SU": "seller",
        "DP": "shipTo",
        "IV": "billTo"
      }
    },
    "RFF": {
      "triggerSegment": "RFF",
      "targetArrayField": "references"
    }
  },
  "defaults": {
    "currency": "EUR"
  },
  "validation": {
    "requiredTargetFields": [
      "documentNumber", "documentDate", "buyer.name", "buyer.id",
      "seller.name", "seller.id", "lineItems"
    ]
  },
  "metadata": {
    "author": "TranzFer Standard Library",
    "lastUpdated": "2026-04-10",
    "coverage": "Covers standard EDIFACT ORDERS D01B fields per UN/EDIFACT specification"
  }
}
```

## Appendix C: INVOICE_INH Schema

```json
{
  "$schema": "https://tranzfer.io/schemas/inhouse/invoice-v1.json",
  "type": "object",
  "properties": {
    "documentType": { "const": "INVOICE" },
    "invoiceNumber": { "type": "string" },
    "invoiceDate": { "type": "string", "format": "date" },
    "dueDate": { "type": "string", "format": "date" },
    "purchaseOrderNumber": { "type": "string", "description": "Referenced PO number" },
    "seller": {
      "type": "object",
      "properties": {
        "id": { "type": "string" },
        "name": { "type": "string" },
        "qualifier": { "type": "string" },
        "taxId": { "type": "string" },
        "address": { "$ref": "#/$defs/address" }
      },
      "required": ["id", "name"]
    },
    "buyer": {
      "type": "object",
      "properties": {
        "id": { "type": "string" },
        "name": { "type": "string" },
        "qualifier": { "type": "string" },
        "address": { "$ref": "#/$defs/address" }
      },
      "required": ["id", "name"]
    },
    "shipTo": { "$ref": "#/$defs/party" },
    "remitTo": { "$ref": "#/$defs/party" },
    "currency": { "type": "string", "default": "USD" },
    "paymentTerms": {
      "type": "object",
      "properties": {
        "termsType": { "type": "string", "description": "Net30, Net60, COD, etc." },
        "discountPercent": { "type": "number" },
        "discountDays": { "type": "integer" },
        "netDays": { "type": "integer" }
      }
    },
    "lineItems": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "lineNumber": { "type": "integer" },
          "itemId": { "type": "string" },
          "description": { "type": "string" },
          "quantity": { "type": "number" },
          "unitOfMeasure": { "type": "string" },
          "unitPrice": { "type": "number" },
          "lineTotal": { "type": "number" },
          "purchaseOrderLineNumber": { "type": "integer" },
          "taxAmount": { "type": "number" },
          "taxRate": { "type": "number" }
        },
        "required": ["lineNumber", "quantity", "unitPrice"]
      }
    },
    "totals": {
      "type": "object",
      "properties": {
        "subtotalAmount": { "type": "number" },
        "taxAmount": { "type": "number" },
        "shippingAmount": { "type": "number" },
        "discountAmount": { "type": "number" },
        "totalAmount": { "type": "number" },
        "amountDue": { "type": "number" },
        "lineItemCount": { "type": "integer" }
      }
    },
    "references": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "qualifier": { "type": "string" },
          "value": { "type": "string" },
          "description": { "type": "string" }
        }
      }
    },
    "notes": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "type": { "type": "string" },
          "text": { "type": "string" }
        }
      }
    }
  },
  "required": ["documentType", "invoiceNumber", "invoiceDate", "seller", "buyer", "lineItems", "totals"],
  "$defs": {
    "address": {
      "type": "object",
      "properties": {
        "line1": { "type": "string" },
        "line2": { "type": "string" },
        "city": { "type": "string" },
        "state": { "type": "string" },
        "postalCode": { "type": "string" },
        "country": { "type": "string" }
      }
    },
    "party": {
      "type": "object",
      "properties": {
        "id": { "type": "string" },
        "name": { "type": "string" },
        "qualifier": { "type": "string" },
        "address": { "$ref": "#/$defs/address" }
      }
    }
  }
}
```

## Appendix D: SHIP_NOTICE_INH Schema

```json
{
  "$schema": "https://tranzfer.io/schemas/inhouse/ship-notice-v1.json",
  "type": "object",
  "properties": {
    "documentType": { "const": "SHIP_NOTICE" },
    "shipmentId": { "type": "string" },
    "shipDate": { "type": "string", "format": "date" },
    "expectedDeliveryDate": { "type": "string", "format": "date" },
    "purchaseOrderNumber": { "type": "string" },
    "billOfLadingNumber": { "type": "string" },
    "shipFrom": {
      "type": "object",
      "properties": {
        "id": { "type": "string" },
        "name": { "type": "string" },
        "address": { "$ref": "#/$defs/address" }
      },
      "required": ["name"]
    },
    "shipTo": {
      "type": "object",
      "properties": {
        "id": { "type": "string" },
        "name": { "type": "string" },
        "address": { "$ref": "#/$defs/address" }
      },
      "required": ["name"]
    },
    "carrier": {
      "type": "object",
      "properties": {
        "name": { "type": "string" },
        "scac": { "type": "string", "description": "Standard Carrier Alpha Code" },
        "trackingNumber": { "type": "string" },
        "serviceLevel": { "type": "string" }
      }
    },
    "shipmentWeight": {
      "type": "object",
      "properties": {
        "value": { "type": "number" },
        "unit": { "type": "string", "enum": ["LB", "KG"] }
      }
    },
    "packageCount": { "type": "integer" },
    "lineItems": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "lineNumber": { "type": "integer" },
          "itemId": { "type": "string" },
          "description": { "type": "string" },
          "quantityShipped": { "type": "number" },
          "unitOfMeasure": { "type": "string" },
          "purchaseOrderLineNumber": { "type": "integer" },
          "lotNumber": { "type": "string" },
          "serialNumbers": { "type": "array", "items": { "type": "string" } }
        },
        "required": ["lineNumber", "quantityShipped"]
      }
    },
    "references": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "qualifier": { "type": "string" },
          "value": { "type": "string" }
        }
      }
    }
  },
  "required": ["documentType", "shipmentId", "shipDate", "shipFrom", "shipTo", "lineItems"],
  "$defs": {
    "address": {
      "type": "object",
      "properties": {
        "line1": { "type": "string" },
        "line2": { "type": "string" },
        "city": { "type": "string" },
        "state": { "type": "string" },
        "postalCode": { "type": "string" },
        "country": { "type": "string" }
      }
    }
  }
}
```

## Appendix E: Performance Targets

| Metric | Target | How Measured |
|--------|--------|-------------|
| Standard map conversion throughput | >5,000 docs/sec | JMH benchmark, single instance, 8-core |
| Standard map conversion p50 latency | <5ms | JMH benchmark |
| Standard map conversion p99 latency | <50ms | JMH benchmark |
| Trained map conversion throughput | >2,000 docs/sec | Includes REST call to ai-engine cache |
| Map loading (lazy, first use) | <100ms | Time from first request to map available |
| Standard map index build (startup) | <50ms | @PostConstruct duration |
| Memory footprint (36 standard maps) | <2 MB | Heap usage after all maps loaded |
| Training (5 samples) | <2 sec | End-to-end training time |
| Training (50 samples) | <10 sec | End-to-end training time |
| Comparison (100 file pairs) | <30 sec | Batch comparison with report |

Competitor benchmarks for reference:
- Sterling Integrator: ~500-1,000 docs/sec (map-based, Java)
- Axway B2Bi: ~800-1,500 docs/sec (depends on map complexity)
- IBM DataPower: ~2,000-3,000 docs/sec (hardware-accelerated)

TranzFer target: 5,000+ docs/sec for standard maps, making it the fastest Java-based EDI
converter available. This is achievable because:
1. Maps are pre-loaded and pre-parsed (no XML/XSLT compilation like Sterling)
2. No database call for standard maps (classpath resources)
3. No XML DOM overhead (direct field-path addressing)
4. Java 21 virtual threads for concurrent conversions

---

*End of EDI Converter Maturity Plan*
