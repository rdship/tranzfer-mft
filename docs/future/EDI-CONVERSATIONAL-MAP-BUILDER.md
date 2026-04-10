# EDI Conversational Map Builder — Design Document

## The Problem with Traditional Map Editors

Every MFT product (Sterling, Axway, Cleo) makes EDI mapping a **technical task**:
- Open a visual editor with source/target schemas
- Drag fields one by one
- Configure transforms manually
- Test, fix, repeat

This requires:
- EDI knowledge (what's a BEG segment? What's N1[BY]?)
- Technical skills (regex, date formats, code tables)
- Hours of work per map
- Expensive consultants ($200/hr mapping specialists)

## Our Approach: Two Paths, Zero Technical Knowledge Required

### Path 1: Sample-Based Auto-Build (90% of cases)

```
Partner uploads 2-5 sample file pairs:
  "Here's what we send (X12 850)" + "Here's what our ERP needs (JSON)"
     ↓
AI analyzes patterns, builds map automatically (30 seconds)
     ↓
Shows sample output: "Here's what your next file would look like"
     ↓
Partner: "Looks good!" → Map activated ✓
     OR
Partner: "The PO date is wrong, it should be MM/DD/YYYY not YYYY-MM-DD"
     ↓
AI adjusts, shows new output (5 seconds)
     ↓
Partner: "Perfect!" → Map activated ✓
```

**Total time: 2-5 minutes. Zero technical knowledge.**

### Path 2: Conversational Editor (highly custom maps)

For partners with unusual requirements, an AI chat interface overlaid on the map:

```
┌─────────────────────────────────────────────────────────┐
│  MAP: Acme Corp PO Map (X12 850 → Custom JSON)          │
│                                                          │
│  [Source Fields]          [Target Fields]                 │
│  BEG.03 ─────────────── orderNumber                     │
│  BEG.05 ─────────────── orderDate                       │
│  N1[BY].02 ──────────── buyer.name                      │
│  PO1.02 ─────────────── items[].qty                     │
│  PO1.04 ─────────────── items[].price                   │
│                                                          │
├──────────────────────────────────────────────────────────┤
│  💬 AI Assistant                                         │
│                                                          │
│  You: "Add the ship-to address. It's in the N1 segment  │
│        where the code is ST"                             │
│                                                          │
│  AI: I found the Ship-To party (N1[ST]). I've mapped:   │
│    • N1[ST].02 → shipTo.name                            │
│    • N3.01 → shipTo.address.line1                       │
│    • N4.01 → shipTo.address.city                        │
│    • N4.02 → shipTo.address.state                       │
│    • N4.03 → shipTo.address.zip                         │
│                                                          │
│    [Preview shows updated output with ship-to]           │
│    Does this look right?                                 │
│                                                          │
│  You: "Yes, but rename 'zip' to 'postalCode'"           │
│                                                          │
│  AI: Done ✓ Updated shipTo.address.zip → postalCode     │
│                                                          │
│  You: "Also, we need the total order amount. Add up all  │
│        the line item prices times quantities"             │
│                                                          │
│  AI: I've added a computed field:                        │
│    • SUM(PO1.02 × PO1.04) → orderTotal                  │
│    Transform: COMPUTE with formula                       │
│    [Preview shows orderTotal: 1,247.50]                  │
│                                                          │
│  ┌──────────────────────────────────────────────┐        │
│  │ Type your message...                    [Send]│        │
│  └──────────────────────────────────────────────┘        │
└──────────────────────────────────────────────────────────┘
```

## Technical Architecture

### Component 1: MapConversationEngine (AI Engine)

New service in ai-engine that powers the conversational interface.

**Endpoint:** `POST /api/v1/edi/maps/chat`

```json
Request:
{
  "mapId": "uuid-of-map-being-edited",
  "message": "Add the ship-to address from the ST segment",
  "context": {
    "currentMappings": [...],     // current state of the map
    "sourceSchema": "X12_850",
    "targetSchema": "CUSTOM_JSON",
    "sampleInput": "ISA*00*...",  // optional: partner's sample file
    "sampleOutput": {...}         // optional: expected output
  }
}

Response:
{
  "reply": "I found the Ship-To party (N1[ST]). I've mapped 5 fields...",
  "actions": [
    {"type": "ADD_MAPPING", "sourcePath": "N1[ST].02", "targetPath": "shipTo.name", "transform": "TRIM"},
    {"type": "ADD_MAPPING", "sourcePath": "N3.01", "targetPath": "shipTo.address.line1", "transform": "TRIM"},
    ...
  ],
  "preview": { "shipTo": { "name": "Acme Warehouse", "address": {...} } },
  "confidence": 0.95,
  "suggestedFollowUp": "Would you also like me to map the Bill-To address?"
}
```

**How it works internally:**
1. Parse the natural language message to understand intent
2. Look up the source schema to find matching segments/fields
3. Generate mapping actions
4. Apply actions to a copy of the map
5. Run sample conversion to generate preview
6. Return reply + actions + preview

**Intent categories:**
- "Add X" → find source field, suggest target, create mapping
- "Remove X" → identify and delete mapping
- "Change X to Y" → modify transform or target path
- "The date format should be..." → update transform config
- "Rename X" → change target path
- "Combine X and Y" → create CONCAT transform
- "Calculate X" → create COMPUTE transform
- "If X then Y" → create CONDITIONAL mapping
- "Show me what field Z maps to" → query existing mappings
- "What fields aren't mapped?" → list unmapped source/target fields

### Component 2: SampleMapBuilder (AI Engine)

New service that builds maps from sample file pairs.

**Endpoint:** `POST /api/v1/edi/maps/build-from-samples`

```json
Request:
{
  "partnerId": "uuid",
  "samples": [
    {
      "input": "ISA*00*...",           // raw source file content
      "inputFormat": "X12_850",         // auto-detected if not provided
      "output": { "orderNum": "PO123" },// expected output (JSON, XML, or flat)
      "outputFormat": "JSON"
    },
    // ... 2-5 more pairs
  ],
  "name": "Acme Corp Purchase Order Map"
}

Response:
{
  "mapId": "generated-uuid",
  "name": "Acme Corp Purchase Order Map",
  "confidence": 0.92,
  "fieldMappings": [...],
  "preview": { ... },          // conversion result for last sample
  "unmappedSourceFields": [...],
  "unmappedTargetFields": [...],
  "lowConfidenceFields": [
    {"sourcePath": "REF.02", "targetPath": "referenceId", "confidence": 0.6, "reason": "Only matched in 2 of 5 samples"}
  ],
  "status": "PENDING_APPROVAL"
}
```

**How it works:**
1. Parse all input samples
2. Parse all output samples (detect format)
3. For each output field, find which input field's value matches across samples
4. Use existing 5 training strategies (exact value, positional, value-type, contextual, semantic)
5. Generate ConversionMapDefinition
6. Run conversion on all samples, compute accuracy
7. Flag low-confidence fields for review
8. Return map + preview for approval

### Component 3: Approval/Feedback Loop

**Endpoint:** `POST /api/v1/edi/maps/{mapId}/feedback`

```json
Request:
{
  "approved": false,
  "comments": "The date format is wrong, should be MM/DD/YYYY. Also missing the carrier name.",
  "corrections": [
    {"field": "orderDate", "expected": "04/10/2026", "got": "2026-04-10"},
    {"field": "carrier", "expected": "UPS Ground", "source": "somewhere in the TD5 segment"}
  ]
}

Response:
{
  "reply": "I've fixed 2 issues:\n1. orderDate: changed format to MM/DD/YYYY\n2. carrier: mapped TD5.05 → carrier (found 'UPS Ground' in all samples)",
  "updatedMap": {...},
  "newPreview": {...},
  "remainingIssues": 0
}
```

**The feedback loop is key:**
- Partner provides natural language comments
- AI parses comments into specific corrections
- AI applies corrections to the map
- Shows new preview
- Repeat until approved
- Each iteration should be <10 seconds
- After 2-3 iterations, map should be perfect

### Component 4: UI — MapAssistant Panel

Embedded in the existing MapEditor component, this is a chat panel that lets partners interact with the map in natural language.

**Two modes:**

**Mode A: "Build Map" (new map from samples)**
1. Partner uploads sample files (drag-and-drop)
2. System auto-builds map
3. Shows preview output
4. Partner approves or gives feedback
5. Iterate until done

**Mode B: "Edit Map" (adjust existing map)**
1. Chat panel alongside visual editor
2. Partner types what they want changed
3. AI makes changes, shows diff
4. Visual editor updates in real-time
5. Both the chat and visual editor are synchronized

## UI Design

### Sample Upload Flow (Path 1)

```
┌─────────────────────────────────────────────────────────┐
│  🔮 Build Map from Samples                              │
│                                                          │
│  Step 1: Upload your files                               │
│                                                          │
│  ┌──────────────────┐  ┌──────────────────┐             │
│  │  📄 Source Files  │  │  📄 Target Files  │             │
│  │                   │  │                   │             │
│  │  Drop your EDI    │  │  Drop what your   │             │
│  │  files here       │  │  system expects   │             │
│  │  (X12, EDIFACT,   │  │  (JSON, XML, CSV, │             │
│  │   SWIFT, etc.)    │  │   flat file, etc.) │             │
│  │                   │  │                   │             │
│  │  sample1.edi ✓    │  │  sample1.json ✓   │             │
│  │  sample2.edi ✓    │  │  sample2.json ✓   │             │
│  │  sample3.edi ✓    │  │  sample3.json ✓   │             │
│  └──────────────────┘  └──────────────────┘             │
│                                                          │
│  [Build Map →]                                           │
│                                                          │
│  ─────────────────────────────────────────────────────── │
│                                                          │
│  Step 2: Review & Approve                                │
│                                                          │
│  ✅ 27 of 30 fields mapped (90% confidence)              │
│  ⚠️ 3 fields need review:                                │
│    • referenceId (60% confidence)                        │
│    • carrier (not found in source)                       │
│    • specialInstructions (ambiguous match)               │
│                                                          │
│  Preview (sample1):                                      │
│  ┌─────────────────────────────────────────────┐        │
│  │ { "orderNumber": "PO-2024-001",              │        │
│  │   "orderDate": "2024-03-15",                 │        │
│  │   "buyer": { "name": "Acme Corp" },          │        │
│  │   "items": [                                 │        │
│  │     { "sku": "WIDGET-A", "qty": 100 }        │        │
│  │   ] }                                        │        │
│  └─────────────────────────────────────────────┘        │
│                                                          │
│  💬 Feedback (optional):                                 │
│  ┌──────────────────────────────────────────────┐        │
│  │ The date should be MM/DD/YYYY and carrier    │        │
│  │ is in the TD5 segment, 5th field...    [Send]│        │
│  └──────────────────────────────────────────────┘        │
│                                                          │
│  [Approve & Activate ✓]   [Need Changes ↺]              │
└──────────────────────────────────────────────────────────┘
```

### Chat Editor (Path 2)

The chat panel sits to the right of the visual map editor. Every message from the AI includes:
1. What it understood
2. What actions it took
3. A live preview diff
4. A suggested follow-up question

The visual editor updates in real-time as the AI makes changes — the partner sees fields connecting/disconnecting as they chat.

## Implementation Plan

### Backend (ai-engine)
1. MapConversationEngine.java — NLP intent parsing + action generation
2. SampleMapBuilder.java — builds maps from file pairs
3. MapFeedbackProcessor.java — processes natural language feedback
4. MapChatController.java — REST endpoints for chat + samples + feedback

### Frontend (ui-service)
1. MapAssistant.jsx — chat panel component
2. SampleUploader.jsx — drag-drop file pair uploader
3. MapPreview.jsx — live preview with diff highlighting
4. Update Edi.jsx — new "Build Map" flow + chat in editor

### Key Design Principles
1. **Never ask for technical details** — AI figures out formats, segments, field paths
2. **Always show previews** — partner sees output, not mapping config
3. **Learn from every interaction** — corrections become training data
4. **Sub-10-second responses** — no waiting, feels instant
5. **Works without LLM API key** — fallback to pattern matching (existing strategies)
