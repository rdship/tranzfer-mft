# EDI Conversion Sample Documents

Runnable sample payloads for exercising the `edi-converter` service (port 8095, available in the full-stack demo) through the admin UI's `/edi` page or directly via REST.

## Samples

| File | Format | Document Type | What it represents |
|---|---|---|---|
| [x12-850-purchase-order.edi](x12-850-purchase-order.edi) | X12 005010 | 850 (Purchase Order) | ACME Corp orders 100 widgets and 50 gadgets from Global Supply |
| [x12-810-invoice.edi](x12-810-invoice.edi) | X12 005010 | 810 (Invoice) | Global Supply's invoice back to ACME for that order ($2,500 total) |
| [edifact-orders.edi](edifact-orders.edi) | EDIFACT D.96A | ORDERS | Same purchase order in the international EDIFACT standard |
| [hl7-adt-admission.hl7](hl7-adt-admission.hl7) | HL7 v2.5 | ADT^A01 | Patient admission record for a pneumonia case at UCSF |

## How to use them — the click-path

1. Boot the **full-stack** demo (`./scripts/demo-all.sh --full`). The edi-converter service runs on port 8095.
2. Log in at http://localhost:3000.
3. Navigate to **EDI Translation** (`/edi` in the sidebar).
4. Open the **Convert** tab.
5. Open any of the sample files in a text editor, copy the contents, and paste into the "EDI Content" textarea.
6. Click **Detect** — the source type dropdown should auto-populate (e.g. `X12_850` for the purchase order).
7. Pick a target type from the **Target Type** dropdown — good things to try:
   - X12 850 → **JSON** (structured data)
   - X12 850 → **XML**
   - X12 850 → **EDIFACT_ORDERS** (converts to the international equivalent)
   - EDIFACT ORDERS → **X12_850** (reverse direction)
   - HL7 ADT → **JSON** (parses the HL7 segments into a structured object)
8. Click **Convert** (or **Convert with Map** if you picked a map-based target). The result renders in the right panel.

## How to use them — from the terminal

Run all four samples through the live edi-converter in one shot:

```bash
./scripts/demo-edi.sh
```

That script hits `https://localhost:9095/api/v1/convert/detect` and `/convert/convert` for each sample, prints the detected format + the JSON output, and is purely read-only.

## Other tabs worth a click on `/edi`

- **Explain** — paste any sample, get a human-readable English explanation of what the document says
- **Validate** — paste any sample, get validation issues + suggested fixes
- **Heal** — edit the sample to break something (delete a segment, change a date format), paste it in, watch the auto-heal engine fix 25+ common EDI errors
- **Diff** — paste two different samples side-by-side to see semantic field-level differences
- **Compliance** — scores the sample against the EDI standard's compliance rules (0-100)

## Troubleshooting

**"Format unknown" from Detect**
The edi-converter service expects pristine payloads. If you added stray whitespace or removed a segment terminator (`~` in X12, `'` in EDIFACT, newline in HL7) the parser will reject it. Re-copy from the sample file.

**"Service unavailable"**
edi-converter is dropped in tier-2. Run the full-stack demo instead:
```bash
./scripts/demo-all.sh --full
```

**HL7 parsing error**
HL7 uses carriage-return (`\r`) as the segment separator. Most editors will preserve this when you copy from the `.hl7` file, but if the conversion fails, try the terminal path (`./scripts/demo-edi.sh`) which handles line endings correctly.
