package com.filetransfer.ai.service.edi;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Pure-Java field embedding engine for EDI field matching.
 *
 * Computes vector similarity between field names/paths using:
 * 1. Character n-gram TF-IDF vectors (captures subword patterns)
 * 2. Token-based overlap (splits on camelCase, dots, underscores)
 * 3. Semantic synonym expansion (EDI domain knowledge)
 *
 * No external ML libraries — runs in O(n*m) for n source × m target fields.
 */
@Service
@Slf4j
public class FieldEmbeddingEngine {

    private static final int NGRAM_MIN = 2;
    private static final int NGRAM_MAX = 4;

    /** EDI domain synonyms — field names that mean the same thing across formats */
    private static final Map<String, Set<String>> SYNONYM_GROUPS = Map.ofEntries(
            Map.entry("sender", Set.of("sender", "from", "originator", "source", "submitter", "payer",
                    "isa06", "senderId", "senderid", "interchange_sender")),
            Map.entry("receiver", Set.of("receiver", "to", "destination", "recipient", "target",
                    "isa08", "receiverid", "interchange_receiver")),
            Map.entry("date", Set.of("date", "datetime", "timestamp", "created", "issued", "sent",
                    "documentdate", "transactiondate", "effectivedate", "dtp")),
            Map.entry("amount", Set.of("amount", "total", "sum", "value", "price", "cost", "charge",
                    "monetarytotal", "grandtotal", "nettotal", "tds")),
            Map.entry("quantity", Set.of("quantity", "qty", "count", "units", "volume", "numberof")),
            Map.entry("identifier", Set.of("id", "identifier", "number", "num", "no", "code", "ref",
                    "reference", "controlnumber", "trackingnumber")),
            Map.entry("name", Set.of("name", "fullname", "companyname", "tradingpartnername",
                    "organizationname", "entityname", "nm1")),
            Map.entry("address", Set.of("address", "addr", "street", "line1", "addressline",
                    "streetaddress", "n3", "n4")),
            Map.entry("city", Set.of("city", "town", "locality", "cityname")),
            Map.entry("state", Set.of("state", "province", "region", "statecode")),
            Map.entry("zip", Set.of("zip", "zipcode", "postalcode", "postal", "postcode")),
            Map.entry("country", Set.of("country", "countrycode", "nation")),
            Map.entry("description", Set.of("description", "desc", "detail", "text", "narrative",
                    "note", "comment", "remarks")),
            Map.entry("product", Set.of("product", "item", "sku", "productcode", "upc", "ean",
                    "itemnumber", "partnumber", "po1", "lin")),
            Map.entry("invoice", Set.of("invoice", "invoicenumber", "invoiceno", "invoiceid", "big")),
            Map.entry("purchaseorder", Set.of("purchaseorder", "po", "ponumber", "ordernumber",
                    "orderid", "beg")),
            Map.entry("claim", Set.of("claim", "claimid", "claimnumber", "clm")),
            Map.entry("patient", Set.of("patient", "patientname", "subscriber", "member", "insured")),
            Map.entry("provider", Set.of("provider", "providername", "doctor", "physician", "npi")),
            Map.entry("diagnosis", Set.of("diagnosis", "diagnosiscode", "icd", "icd10", "hi")),
            Map.entry("currency", Set.of("currency", "currencycode", "ccy", "cur"))
    );

    /** Reverse lookup: normalized token → synonym group key */
    private final Map<String, String> tokenToGroup;

    public FieldEmbeddingEngine() {
        tokenToGroup = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : SYNONYM_GROUPS.entrySet()) {
            for (String synonym : entry.getValue()) {
                tokenToGroup.put(synonym.toLowerCase(), entry.getKey());
            }
        }
    }

    /**
     * Compute similarity between two field names/paths.
     * Returns 0.0 (no match) to 1.0 (identical meaning).
     */
    public double similarity(String field1, String field2) {
        if (field1 == null || field2 == null) return 0.0;

        String norm1 = normalize(field1);
        String norm2 = normalize(field2);

        if (norm1.equals(norm2)) return 1.0;

        // Weighted combination of three strategies
        double ngramSim = ngramCosineSimilarity(norm1, norm2);
        double tokenSim = tokenOverlapSimilarity(field1, field2);
        double semanticSim = semanticSimilarity(field1, field2);

        // Take the maximum — if any strategy is confident, trust it
        double maxSim = Math.max(ngramSim, Math.max(tokenSim, semanticSim));

        // Weighted average favoring the best match but not ignoring others
        return 0.6 * maxSim + 0.25 * secondHighest(ngramSim, tokenSim, semanticSim)
                + 0.15 * lowest(ngramSim, tokenSim, semanticSim);
    }

    /**
     * Find the best matching target field for a source field.
     * Returns the match with highest similarity, or empty if below threshold.
     */
    public Optional<FieldMatch> findBestMatch(String sourceField, Collection<String> targetFields, double threshold) {
        FieldMatch best = null;
        for (String target : targetFields) {
            double sim = similarity(sourceField, target);
            if (sim >= threshold && (best == null || sim > best.similarity)) {
                best = new FieldMatch(sourceField, target, sim, explainMatch(sourceField, target));
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Compute all-pairs similarity matrix between source and target fields.
     * Used during training to find optimal field alignment.
     */
    public List<FieldMatch> computeSimilarityMatrix(Collection<String> sourceFields,
                                                     Collection<String> targetFields,
                                                     double threshold) {
        List<FieldMatch> matches = new ArrayList<>();
        for (String source : sourceFields) {
            for (String target : targetFields) {
                double sim = similarity(source, target);
                if (sim >= threshold) {
                    matches.add(new FieldMatch(source, target, sim, explainMatch(source, target)));
                }
            }
        }
        // Sort by similarity descending
        matches.sort((a, b) -> Double.compare(b.similarity, a.similarity));
        return matches;
    }

    // === N-gram TF-IDF Cosine Similarity ===

    private double ngramCosineSimilarity(String s1, String s2) {
        Map<String, Integer> v1 = ngramVector(s1);
        Map<String, Integer> v2 = ngramVector(s2);
        return cosineSimilarity(v1, v2);
    }

    private Map<String, Integer> ngramVector(String s) {
        Map<String, Integer> vector = new HashMap<>();
        String padded = "$" + s + "$";
        for (int n = NGRAM_MIN; n <= NGRAM_MAX; n++) {
            for (int i = 0; i <= padded.length() - n; i++) {
                String ngram = padded.substring(i, i + n);
                vector.merge(ngram, 1, Integer::sum);
            }
        }
        return vector;
    }

    private double cosineSimilarity(Map<String, Integer> v1, Map<String, Integer> v2) {
        Set<String> allKeys = new HashSet<>(v1.keySet());
        allKeys.addAll(v2.keySet());

        double dotProduct = 0, norm1 = 0, norm2 = 0;
        for (String key : allKeys) {
            int a = v1.getOrDefault(key, 0);
            int b = v2.getOrDefault(key, 0);
            dotProduct += a * b;
            norm1 += (long) a * a;
            norm2 += (long) b * b;
        }

        if (norm1 == 0 || norm2 == 0) return 0.0;
        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    // === Token Overlap Similarity ===

    private double tokenOverlapSimilarity(String field1, String field2) {
        Set<String> tokens1 = tokenize(field1);
        Set<String> tokens2 = tokenize(field2);

        if (tokens1.isEmpty() || tokens2.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(tokens1);
        intersection.retainAll(tokens2);

        Set<String> union = new HashSet<>(tokens1);
        union.addAll(tokens2);

        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    // === Semantic Synonym Similarity ===

    private double semanticSimilarity(String field1, String field2) {
        Set<String> tokens1 = tokenize(field1);
        Set<String> tokens2 = tokenize(field2);

        // Expand tokens to their synonym group keys
        Set<String> groups1 = tokens1.stream()
                .map(t -> tokenToGroup.getOrDefault(t, t))
                .collect(Collectors.toSet());
        Set<String> groups2 = tokens2.stream()
                .map(t -> tokenToGroup.getOrDefault(t, t))
                .collect(Collectors.toSet());

        if (groups1.isEmpty() || groups2.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(groups1);
        intersection.retainAll(groups2);

        Set<String> union = new HashSet<>(groups1);
        union.addAll(groups2);

        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    // === Helpers ===

    private String normalize(String field) {
        if (field == null) return "";
        return field.toLowerCase()
                .replaceAll("[^a-z0-9]", "")
                .trim();
    }

    /** Split a field name into meaningful tokens (handles camelCase, dots, underscores, EDI paths) */
    Set<String> tokenize(String field) {
        if (field == null || field.isBlank()) return Set.of();

        // Remove EDI-specific prefixes like "biz.", segment positions like "*03"
        String cleaned = field.replaceAll("^(biz\\.|\\$\\.)", "")
                .replaceAll("\\*\\d+$", "");

        // Split on common separators: dots, underscores, hyphens, slashes
        String[] parts = cleaned.split("[._\\-/]");

        Set<String> tokens = new HashSet<>();
        for (String part : parts) {
            // Split camelCase: "purchaseOrderNumber" → ["purchase", "order", "number"]
            String[] camelParts = part.split("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
            for (String cp : camelParts) {
                String lower = cp.toLowerCase().trim();
                if (!lower.isEmpty() && lower.length() > 1) {
                    tokens.add(lower);
                }
            }
        }
        return tokens;
    }

    private String explainMatch(String source, String target) {
        double ngramSim = ngramCosineSimilarity(normalize(source), normalize(target));
        double tokenSim = tokenOverlapSimilarity(source, target);
        double semanticSim = semanticSimilarity(source, target);

        String best;
        if (semanticSim >= tokenSim && semanticSim >= ngramSim) {
            best = "semantic synonym match";
        } else if (tokenSim >= ngramSim) {
            best = "token overlap match";
        } else {
            best = "character n-gram similarity";
        }
        return String.format("%s (ngram=%.0f%%, token=%.0f%%, semantic=%.0f%%)",
                best, ngramSim * 100, tokenSim * 100, semanticSim * 100);
    }

    private double secondHighest(double a, double b, double c) {
        double max = Math.max(a, Math.max(b, c));
        double min = Math.min(a, Math.min(b, c));
        return a + b + c - max - min;
    }

    private double lowest(double a, double b, double c) {
        return Math.min(a, Math.min(b, c));
    }

    /** Result of a field matching operation */
    public record FieldMatch(String sourceField, String targetField, double similarity, String reasoning) {}
}
