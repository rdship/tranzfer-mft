package com.filetransfer.edi.service;

import com.filetransfer.edi.model.EdiDocument;
import com.filetransfer.edi.model.EdiDocument.Segment;
import com.filetransfer.edi.parser.UniversalEdiParser;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Semantic Diff Engine — field-level comparison between two EDI documents.
 *
 * Unlike character-level diff, this understands EDI structure:
 *   - "Segment PO1*02 changed from 'EA' to 'CS'" not "line 47 char 12 changed"
 *   - Groups changes by segment type
 *   - Identifies added, removed, and modified segments
 *   - Compares business meaning, not raw bytes
 *
 * Use cases:
 *   - Partner changed their spec → see exactly what's different
 *   - Regression testing → compare before/after conversion
 *   - Audit trail → what changed between document versions
 */
@Service @RequiredArgsConstructor @Slf4j
public class SemanticDiffEngine {

    private final UniversalEdiParser parser;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DiffResult {
        private String leftFormat;
        private String rightFormat;
        private int totalChanges;
        private int segmentsAdded;
        private int segmentsRemoved;
        private int segmentsModified;
        private int segmentsUnchanged;
        private List<DiffEntry> changes;
        private Map<String, Object> summary;
        private String verdict;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DiffEntry {
        private ChangeType type;
        private int position;       // 1-based segment index
        private String segmentId;
        private String leftValue;   // null for ADDED
        private String rightValue;  // null for REMOVED
        private List<ElementDiff> elementDiffs; // null for ADDED/REMOVED
        private String description; // Human-readable

        public enum ChangeType { ADDED, REMOVED, MODIFIED, MOVED }
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ElementDiff {
        private int elementIndex;
        private String fieldName;
        private String leftValue;
        private String rightValue;
        private String description;
    }

    /**
     * Compare two EDI documents (raw content).
     */
    public DiffResult diff(String leftContent, String rightContent) {
        EdiDocument left = parser.parse(leftContent);
        EdiDocument right = parser.parse(rightContent);
        return diffDocuments(left, right);
    }

    /**
     * Compare two parsed EdiDocuments.
     */
    public DiffResult diffDocuments(EdiDocument left, EdiDocument right) {
        List<Segment> leftSegs = left.getSegments() != null ? left.getSegments() : List.of();
        List<Segment> rightSegs = right.getSegments() != null ? right.getSegments() : List.of();

        List<DiffEntry> changes = new ArrayList<>();
        int added = 0, removed = 0, modified = 0, unchanged = 0;

        // Build maps of segments by ID for structural comparison
        Map<String, List<IndexedSegment>> leftMap = indexSegments(leftSegs);
        Map<String, List<IndexedSegment>> rightMap = indexSegments(rightSegs);

        // Use LCS-based diff for ordered comparison
        int[][] lcs = computeLCS(leftSegs, rightSegs);
        List<DiffAction> actions = backtrackLCS(lcs, leftSegs, rightSegs);

        for (DiffAction action : actions) {
            switch (action.type) {
                case EQUAL -> {
                    // Check if elements differ even though segment ID matches
                    Segment l = leftSegs.get(action.leftIndex);
                    Segment r = rightSegs.get(action.rightIndex);
                    List<ElementDiff> elemDiffs = compareElements(l, r);
                    if (elemDiffs.isEmpty()) {
                        unchanged++;
                    } else {
                        modified++;
                        changes.add(DiffEntry.builder()
                                .type(DiffEntry.ChangeType.MODIFIED)
                                .position(action.rightIndex + 1)
                                .segmentId(l.getId())
                                .leftValue(formatSegment(l))
                                .rightValue(formatSegment(r))
                                .elementDiffs(elemDiffs)
                                .description(describeModification(l.getId(), elemDiffs))
                                .build());
                    }
                }
                case INSERT -> {
                    added++;
                    Segment r = rightSegs.get(action.rightIndex);
                    changes.add(DiffEntry.builder()
                            .type(DiffEntry.ChangeType.ADDED)
                            .position(action.rightIndex + 1)
                            .segmentId(r.getId())
                            .rightValue(formatSegment(r))
                            .description("Added segment " + r.getId() + " at position " + (action.rightIndex + 1))
                            .build());
                }
                case DELETE -> {
                    removed++;
                    Segment l = leftSegs.get(action.leftIndex);
                    changes.add(DiffEntry.builder()
                            .type(DiffEntry.ChangeType.REMOVED)
                            .position(action.leftIndex + 1)
                            .segmentId(l.getId())
                            .leftValue(formatSegment(l))
                            .description("Removed segment " + l.getId() + " from position " + (action.leftIndex + 1))
                            .build());
                }
            }
        }

        // Build summary
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("leftSegments", leftSegs.size());
        summary.put("rightSegments", rightSegs.size());
        summary.put("leftFormat", left.getSourceFormat());
        summary.put("rightFormat", right.getSourceFormat());

        // Header comparison
        if (!Objects.equals(left.getSenderId(), right.getSenderId()))
            summary.put("senderChanged", left.getSenderId() + " → " + right.getSenderId());
        if (!Objects.equals(left.getReceiverId(), right.getReceiverId()))
            summary.put("receiverChanged", left.getReceiverId() + " → " + right.getReceiverId());
        if (!Objects.equals(left.getDocumentType(), right.getDocumentType()))
            summary.put("documentTypeChanged", left.getDocumentType() + " → " + right.getDocumentType());

        int total = added + removed + modified;
        String verdict;
        if (total == 0) verdict = "Documents are identical";
        else if (total <= 3) verdict = "Minor differences (" + total + " changes)";
        else if (total <= 10) verdict = "Moderate differences (" + total + " changes)";
        else verdict = "Significant differences (" + total + " changes)";

        return DiffResult.builder()
                .leftFormat(left.getSourceFormat()).rightFormat(right.getSourceFormat())
                .totalChanges(total).segmentsAdded(added).segmentsRemoved(removed)
                .segmentsModified(modified).segmentsUnchanged(unchanged)
                .changes(changes).summary(summary).verdict(verdict).build();
    }

    // === Element-level comparison ===
    private List<ElementDiff> compareElements(Segment left, Segment right) {
        List<ElementDiff> diffs = new ArrayList<>();
        List<String> le = left.getElements() != null ? left.getElements() : List.of();
        List<String> re = right.getElements() != null ? right.getElements() : List.of();
        int max = Math.max(le.size(), re.size());

        for (int i = 0; i < max; i++) {
            String lv = i < le.size() ? le.get(i) : "(empty)";
            String rv = i < re.size() ? re.get(i) : "(empty)";
            if (!Objects.equals(lv, rv)) {
                diffs.add(ElementDiff.builder()
                        .elementIndex(i + 1)
                        .fieldName(left.getId() + String.format("*%02d", i + 1))
                        .leftValue(lv).rightValue(rv)
                        .description(left.getId() + " element " + (i + 1) + ": '" + lv + "' → '" + rv + "'")
                        .build());
            }
        }
        return diffs;
    }

    // === LCS-based segment diff ===

    private record DiffAction(ActionType type, int leftIndex, int rightIndex) {}
    private enum ActionType { EQUAL, INSERT, DELETE }

    private record IndexedSegment(Segment segment, int index) {}

    private Map<String, List<IndexedSegment>> indexSegments(List<Segment> segs) {
        Map<String, List<IndexedSegment>> map = new LinkedHashMap<>();
        for (int i = 0; i < segs.size(); i++) {
            map.computeIfAbsent(segs.get(i).getId(), k -> new ArrayList<>())
                    .add(new IndexedSegment(segs.get(i), i));
        }
        return map;
    }

    private int[][] computeLCS(List<Segment> left, List<Segment> right) {
        int m = left.size(), n = right.size();
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (left.get(i - 1).getId().equals(right.get(j - 1).getId())) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }
        return dp;
    }

    private List<DiffAction> backtrackLCS(int[][] dp, List<Segment> left, List<Segment> right) {
        List<DiffAction> actions = new ArrayList<>();
        int i = left.size(), j = right.size();

        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && left.get(i - 1).getId().equals(right.get(j - 1).getId())) {
                actions.add(0, new DiffAction(ActionType.EQUAL, i - 1, j - 1));
                i--; j--;
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                actions.add(0, new DiffAction(ActionType.INSERT, -1, j - 1));
                j--;
            } else {
                actions.add(0, new DiffAction(ActionType.DELETE, i - 1, -1));
                i--;
            }
        }
        return actions;
    }

    private String formatSegment(Segment seg) {
        StringBuilder sb = new StringBuilder(seg.getId());
        if (seg.getElements() != null) {
            for (String e : seg.getElements()) sb.append("*").append(e);
        }
        return sb.toString();
    }

    private String describeModification(String segId, List<ElementDiff> diffs) {
        if (diffs.size() == 1) return diffs.get(0).getDescription();
        return segId + ": " + diffs.size() + " elements changed";
    }
}
