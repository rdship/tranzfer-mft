# EDI Converter

> Universal EDI format detection, validation, translation, self-healing, and AI-powered mapping across 11 input formats.

**Port:** 8095 | **Database:** None | **Required:** Optional

---

## Overview

The EDI converter handles all electronic data interchange operations:

- **Format detection** — Auto-detect X12, EDIFACT, SWIFT, HL7, NACHA, BAI2, ISO20022, FIX, PEPPOL, TRADACOMS
- **Translation** — Convert to JSON, XML, CSV, YAML, flat-file, or TranzFer Internal Format (TIF)
- **Validation** — Smart validation with error detection
- **Self-healing** — Auto-detect and fix 25+ common EDI errors
- **Semantic diff** — Field-level business comparison (not character-level)
- **Compliance scoring** — 0-100 score with A-F grades
- **Streaming parser** — Memory-efficient O(1)-per-segment processing
- **AI mapping** — Auto-generate mappings from source/target samples
- **Natural language creation** — Describe in English → get valid EDI
- **Partner profiles** — Per-partner format rules and templates

---

## Quick Start

```bash
docker compose up -d edi-converter

# Detect format
curl -X POST http://localhost:8095/convert/detect \
  -H "Content-Type: application/json" \
  -d '{"content": "ISA*00*          *00*          *ZZ*SENDER..."}'

# Convert EDI to JSON
curl -X POST http://localhost:8095/convert \
  -H "Content-Type: application/json" \
  -d '{"content": "ISA*00*...", "inputFormat": "X12", "outputFormat": "JSON"}'
```

---

## API Endpoints

### Core Conversion

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/convert/detect` | Auto-detect EDI format |
| POST | `/convert/parse` | Parse EDI into segments |
| POST | `/convert` | Convert between formats |
| POST | `/convert/file` | Convert uploaded file (multipart) |

**Detect format:**
```bash
curl -X POST http://localhost:8095/convert/detect \
  -H "Content-Type: application/json" \
  -d '{"content": "ISA*00*          *00*          ..."}'
```

**Response:**
```json
{"format": "X12", "transactionType": "850", "description": "Purchase Order", "confidence": 0.98}
```

**Convert EDI → JSON:**
```bash
curl -X POST http://localhost:8095/convert \
  -H "Content-Type: application/json" \
  -d '{"content": "ISA*00*...", "inputFormat": "X12", "outputFormat": "JSON"}'
```

### Validation & Healing

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/validate` | Smart validation with error details |
| POST | `/heal` | Auto-detect and fix 25+ common errors |

**Validate:**
```bash
curl -X POST http://localhost:8095/validate \
  -H "Content-Type: application/json" \
  -d '{"content": "ISA*00*..."}'
```

**Self-heal:**
```bash
curl -X POST http://localhost:8095/heal \
  -H "Content-Type: application/json" \
  -d '{"content": "ISA*00*... (with errors)"}'
```

**Response:**
```json
{
  "healed": true,
  "fixesApplied": ["Added missing IEA trailer", "Fixed segment count in SE"],
  "content": "ISA*00*... (corrected)"
}
```

### Analysis

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/explain` | Human-readable explanation of EDI document |
| POST | `/diff` | Semantic field-level comparison of two documents |
| POST | `/compliance` | Compliance score (0-100, grade A-F) |

**Explain:**
```bash
curl -X POST http://localhost:8095/explain \
  -H "Content-Type: application/json" \
  -d '{"content": "ISA*00*..."}'
```

**Response:**
```json
{
  "summary": "This is an X12 850 Purchase Order from SENDER to RECEIVER...",
  "segments": [
    {"segment": "ISA", "explanation": "Interchange header: sender SENDER, receiver RECEIVER, date 2026-04-05"}
  ]
}
```

### Streaming (Memory-Efficient)

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/stream` | Stream-parse EDI content |
| POST | `/stream/file` | Stream-parse uploaded file |

O(1) memory per segment — suitable for very large EDI files.

### Templates

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/templates` | List available templates |
| POST | `/templates/{id}/generate` | Generate EDI from template |

### AI-Powered

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/mapping/generate` | Auto-generate mapping from source+target samples |
| POST | `/mapping/schema` | Generate mapping from schema description |
| POST | `/canonical` | Map to universal JSON canonical model |
| POST | `/create` | Describe in English → generate valid EDI |

**Natural language creation:**
```bash
curl -X POST http://localhost:8095/create \
  -H "Content-Type: application/json" \
  -d '{"description": "Create a purchase order for 100 widgets at $5 each from ACME Corp"}'
```

### Partner Profiles

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/partners` | List partner profiles |
| POST | `/partners` | Create partner profile |
| PUT | `/partners/{id}` | Update partner profile |
| DELETE | `/partners/{id}` | Delete partner profile |

### Trained Map Conversion

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/convert/trained` | Convert using trained/partner-specific map |
| POST | `/api/v1/convert/test-mappings` | Test custom field mappings against sample EDI |
| POST | `/api/v1/convert/trained/invalidate-cache` | Invalidate trained map cache |

**Test custom mappings:**
```bash
curl -X POST http://localhost:8095/api/v1/convert/test-mappings \
  -H "Content-Type: application/json" \
  -d '{
    "sourceContent": "ISA*00*...",
    "targetFormat": "JSON",
    "fieldMappings": [
      {"sourceField": "BEG*03", "targetField": "poNumber", "transform": "DIRECT"},
      {"sourceField": "NM1*03", "targetField": "buyerName", "transform": "TRIM"}
    ]
  }'
```

**Supported transforms:** `DIRECT`, `TRIM`, `UPPERCASE`, `LOWERCASE`, `ZERO_PAD`, `DATE_REFORMAT`

---

## Supported Formats

### Input Formats (11)

| Format | Detection | Description |
|--------|-----------|-------------|
| X12 | ISA/GS/ST headers | US healthcare, retail, finance |
| EDIFACT | UNB/UNH headers | International trade |
| TRADACOMS | STX header | UK retail |
| SWIFT_MT | :20:, :32A: fields | Banking messages |
| HL7 | MSH segment | Healthcare |
| NACHA | Record types 1/5/6/8/9 | ACH payments |
| BAI2 | 01/02/03/16/49/98/99 records | Bank account reporting |
| ISO20022 | XML namespace | Modern banking (XML) |
| FIX | 8=FIX tag | Financial trading |
| PEPPOL | XML namespace | EU e-invoicing |
| AUTO | — | Auto-detect from content |

### Output Formats (6)

| Format | Description |
|--------|-------------|
| JSON | Structured JSON with segments and elements |
| XML | Standard XML representation |
| CSV | Tabular (segment_id, element_1-5) |
| YAML | YAML structure |
| FLAT | Fixed-width (field name padded to 30 chars) |
| TIF | TranzFer Internal Format v1.0 |

### Supported X12 Transaction Types

| Type | Description |
|------|-------------|
| 837 | Healthcare claims |
| 835 | Healthcare payments |
| 850 | Purchase orders |
| 856 | Ship notices |
| 270/271 | Eligibility inquiry/response |
| 997 | Functional acknowledgment |
| 810 | Invoice |
| 820 | Payment order |
| 834 | Benefit enrollment |

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8095` | API port |
| `SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE` | `100MB` | Max upload size |

**No database required.** The EDI converter is a stateless transformation service.

---

## Dependencies

- None required. Fully standalone.
