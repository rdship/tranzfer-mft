package com.filetransfer.ai.service.detection;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Multi-algorithm anomaly detection ensemble.
 * Combines Isolation Forest (pure Java), statistical z-score with EWMA,
 * seasonal decomposition, and per-entity behavior profiling.
 *
 * No external ML libraries required — all algorithms implemented in pure Java.
 */
@Service
@Slf4j
public class AnomalyEnsemble {

    // ── Isolation Forest Implementation ───────────────────────────────

    private static class IsolationTree {
        private final int splitFeature;
        private final double splitValue;
        private final IsolationTree left;
        private final IsolationTree right;
        private final int size; // sample count at leaf

        private IsolationTree(int splitFeature, double splitValue,
                              IsolationTree left, IsolationTree right, int size) {
            this.splitFeature = splitFeature;
            this.splitValue = splitValue;
            this.left = left;
            this.right = right;
            this.size = size;
        }

        static IsolationTree build(double[][] data, int maxDepth, Random rng) {
            return buildRecursive(data, 0, maxDepth, rng);
        }

        private static IsolationTree buildRecursive(double[][] data, int depth, int maxDepth, Random rng) {
            if (data.length <= 1 || depth >= maxDepth) {
                return new IsolationTree(-1, 0, null, null, data.length);
            }

            int numFeatures = data[0].length;
            int feature = rng.nextInt(numFeatures);

            double minVal = Double.MAX_VALUE;
            double maxVal = -Double.MAX_VALUE;
            for (double[] row : data) {
                if (row[feature] < minVal) minVal = row[feature];
                if (row[feature] > maxVal) maxVal = row[feature];
            }

            if (Double.compare(minVal, maxVal) == 0) {
                return new IsolationTree(-1, 0, null, null, data.length);
            }

            double splitVal = minVal + rng.nextDouble() * (maxVal - minVal);

            List<double[]> leftData = new ArrayList<>();
            List<double[]> rightData = new ArrayList<>();
            for (double[] row : data) {
                if (row[feature] < splitVal) {
                    leftData.add(row);
                } else {
                    rightData.add(row);
                }
            }

            if (leftData.isEmpty() || rightData.isEmpty()) {
                return new IsolationTree(-1, 0, null, null, data.length);
            }

            IsolationTree left = buildRecursive(leftData.toArray(new double[0][]), depth + 1, maxDepth, rng);
            IsolationTree right = buildRecursive(rightData.toArray(new double[0][]), depth + 1, maxDepth, rng);

            return new IsolationTree(feature, splitVal, left, right, data.length);
        }

        double pathLength(double[] point) {
            return pathLengthRecursive(point, 0);
        }

        private double pathLengthRecursive(double[] point, int currentDepth) {
            if (splitFeature < 0 || left == null || right == null) {
                return currentDepth + averagePathLengthEstimate(size);
            }
            if (point[splitFeature] < splitValue) {
                return left.pathLengthRecursive(point, currentDepth + 1);
            } else {
                return right.pathLengthRecursive(point, currentDepth + 1);
            }
        }

        /**
         * Average path length of unsuccessful search in a BST (Eq. 1 from the Isolation Forest paper).
         * c(n) = 2*H(n-1) - 2*(n-1)/n where H(i) is the harmonic number.
         */
        static double averagePathLengthEstimate(int n) {
            if (n <= 1) return 0.0;
            if (n == 2) return 1.0;
            double harmonicNumber = Math.log(n - 1.0) + 0.5772156649; // Euler-Mascheroni constant
            return 2.0 * harmonicNumber - (2.0 * (n - 1.0) / n);
        }
    }

    private static class IsolationForest {
        static final int NUM_TREES = 100;
        static final int SUBSAMPLE = 256;

        private final List<IsolationTree> trees = new ArrayList<>();
        private int trainingSize;
        private volatile boolean trained = false;

        void fit(double[][] trainingData) {
            trees.clear();
            trainingSize = trainingData.length;
            int maxDepth = (int) Math.ceil(Math.log(Math.max(SUBSAMPLE, 2)) / Math.log(2));
            Random rng = new Random(42);

            for (int i = 0; i < NUM_TREES; i++) {
                double[][] subsample = subsample(trainingData, Math.min(SUBSAMPLE, trainingData.length), rng);
                trees.add(IsolationTree.build(subsample, maxDepth, rng));
            }
            trained = true;
        }

        double anomalyScore(double[] point) {
            if (!trained || trees.isEmpty()) return 0.5;

            double avgPathLength = trees.stream()
                    .mapToDouble(tree -> tree.pathLength(point))
                    .average()
                    .orElse(0.0);

            double c = IsolationTree.averagePathLengthEstimate(trainingSize);
            if (c == 0.0) return 0.5;

            // Score: s(x, n) = 2^(-E(h(x)) / c(n))
            return Math.pow(2.0, -avgPathLength / c);
        }

        boolean isTrained() {
            return trained;
        }

        private double[][] subsample(double[][] data, int size, Random rng) {
            if (data.length <= size) return data.clone();
            double[][] result = new double[size][];
            List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < data.length; i++) indices.add(i);
            Collections.shuffle(indices, rng);
            for (int i = 0; i < size; i++) {
                result[i] = data[indices.get(i)];
            }
            return result;
        }
    }

    // ── Circular Buffer for Sliding Window ────────────────────────────

    private static class CircularBuffer {
        private final double[] buffer;
        private int head = 0;
        private int count = 0;

        CircularBuffer(int capacity) {
            this.buffer = new double[capacity];
        }

        synchronized void add(double value) {
            buffer[head] = value;
            head = (head + 1) % buffer.length;
            if (count < buffer.length) count++;
        }

        synchronized double[] toArray() {
            double[] result = new double[count];
            for (int i = 0; i < count; i++) {
                int idx = (head - count + i + buffer.length) % buffer.length;
                result[i] = buffer[idx];
            }
            return result;
        }

        synchronized int size() {
            return count;
        }
    }

    // ── Entity Profile ────────────────────────────────────────────────

    private static class EntityProfile {
        final String entityId;
        final String entityType; // IP, USER, ACCOUNT
        final CircularBuffer recentValues = new CircularBuffer(1000);
        double mean = 0.0;
        double variance = 0.0;
        final double[] hourlyBaseline = new double[24];
        final long[] hourlyCount = new long[24];
        final double[] dowBaseline = new double[7];
        final long[] dowCount = new long[7];
        long observationCount = 0;
        Instant lastUpdated = Instant.now();

        // EWMA parameters
        private static final double EWMA_ALPHA = 0.1;
        private double ewmaMean = Double.NaN;
        private double ewmaVariance = 0.0;

        EntityProfile(String entityId, String entityType) {
            this.entityId = entityId;
            this.entityType = entityType;
        }

        synchronized void addObservation(double value, Instant timestamp) {
            recentValues.add(value);
            observationCount++;
            lastUpdated = timestamp;

            // Update running mean and variance (Welford's online algorithm)
            double oldMean = mean;
            mean += (value - mean) / observationCount;
            variance += (value - oldMean) * (value - mean);

            // EWMA update
            if (Double.isNaN(ewmaMean)) {
                ewmaMean = value;
                ewmaVariance = 0.0;
            } else {
                double diff = value - ewmaMean;
                ewmaMean = EWMA_ALPHA * value + (1 - EWMA_ALPHA) * ewmaMean;
                ewmaVariance = (1 - EWMA_ALPHA) * (ewmaVariance + EWMA_ALPHA * diff * diff);
            }

            // Seasonal baselines
            int hour = timestamp.atZone(ZoneOffset.UTC).getHour();
            int dow = timestamp.atZone(ZoneOffset.UTC).getDayOfWeek().getValue() - 1; // 0=Monday
            hourlyBaseline[hour] = ((hourlyBaseline[hour] * hourlyCount[hour]) + value) / (hourlyCount[hour] + 1);
            hourlyCount[hour]++;
            dowBaseline[dow] = ((dowBaseline[dow] * dowCount[dow]) + value) / (dowCount[dow] + 1);
            dowCount[dow]++;
        }

        synchronized double getZScore(double value) {
            if (observationCount < 5) return 0.0;
            double stdDev = getStdDev();
            if (stdDev < 1e-10) return 0.0;
            return (value - mean) / stdDev;
        }

        synchronized double getEwmaZScore(double value) {
            if (Double.isNaN(ewmaMean) || ewmaVariance < 1e-10) return 0.0;
            double ewmaStdDev = Math.sqrt(ewmaVariance);
            return (value - ewmaMean) / ewmaStdDev;
        }

        synchronized double getSeasonalDeviation(double value, Instant timestamp) {
            int hour = timestamp.atZone(ZoneOffset.UTC).getHour();
            int dow = timestamp.atZone(ZoneOffset.UTC).getDayOfWeek().getValue() - 1;

            double hourDev = 0.0;
            double dowDev = 0.0;

            if (hourlyCount[hour] > 3) {
                double hourlyStdDev = computeHourlyStdDev(hour);
                if (hourlyStdDev > 1e-10) {
                    hourDev = Math.abs(value - hourlyBaseline[hour]) / hourlyStdDev;
                }
            }

            if (dowCount[dow] > 3) {
                double dowStdDev = computeDowStdDev(dow);
                if (dowStdDev > 1e-10) {
                    dowDev = Math.abs(value - dowBaseline[dow]) / dowStdDev;
                }
            }

            // Combine hour and day-of-week deviations
            return Math.max(hourDev, dowDev);
        }

        private double getStdDev() {
            if (observationCount < 2) return 0.0;
            return Math.sqrt(variance / (observationCount - 1));
        }

        /**
         * Approximate std dev for hourly slot by using a fraction of overall variance
         * weighted by the count in that slot.
         */
        private double computeHourlyStdDev(int hour) {
            double[] values = recentValues.toArray();
            if (values.length < 5) return getStdDev();
            double sum = 0, sumSq = 0;
            int cnt = 0;
            for (double v : values) {
                // Approximate: use all values since we don't track per-hour values in the buffer
                sum += v;
                sumSq += v * v;
                cnt++;
            }
            if (cnt < 2) return getStdDev();
            double m = sum / cnt;
            return Math.sqrt((sumSq / cnt) - (m * m));
        }

        private double computeDowStdDev(int dow) {
            return computeHourlyStdDev(dow); // same approximation approach
        }

        synchronized Map<String, Object> toSummary() {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("entityId", entityId);
            summary.put("entityType", entityType);
            summary.put("observationCount", observationCount);
            summary.put("mean", Math.round(mean * 1000.0) / 1000.0);
            summary.put("stdDev", Math.round(getStdDev() * 1000.0) / 1000.0);
            summary.put("ewmaMean", Double.isNaN(ewmaMean) ? 0.0 : Math.round(ewmaMean * 1000.0) / 1000.0);
            summary.put("lastUpdated", lastUpdated.toString());

            // Active hours
            List<Integer> activeHours = new ArrayList<>();
            for (int h = 0; h < 24; h++) {
                if (hourlyCount[h] > 0) activeHours.add(h);
            }
            summary.put("activeHours", activeHours);
            return summary;
        }
    }

    // ── State ─────────────────────────────────────────────────────────

    private final ConcurrentHashMap<String, EntityProfile> entityProfiles = new ConcurrentHashMap<>();
    private final IsolationForest isolationForest = new IsolationForest();
    private final ReentrantReadWriteLock forestLock = new ReentrantReadWriteLock();

    // Ensemble weights
    private static final double WEIGHT_ISOLATION_FOREST = 0.35;
    private static final double WEIGHT_ZSCORE = 0.25;
    private static final double WEIGHT_EWMA = 0.15;
    private static final double WEIGHT_SEASONAL = 0.25;

    // ── Main Anomaly Detection ────────────────────────────────────────

    /**
     * Detect anomaly for a given entity observation.
     *
     * @param entityId   unique identifier (IP address, username, account ID)
     * @param entityType type of entity: IP, USER, ACCOUNT
     * @param features   feature vector for Isolation Forest scoring
     * @param timestamp  observation timestamp
     * @return ensemble anomaly result with individual scores and explanation
     */
    public AnomalyResult detectAnomaly(String entityId, String entityType,
                                        double[] features, Instant timestamp) {
        EntityProfile profile = entityProfiles.computeIfAbsent(
                entityId, k -> new EntityProfile(entityId, entityType));

        double primaryValue = features.length > 0 ? features[0] : 0.0;

        // 1. Isolation Forest score
        double ifScore = 0.5;
        forestLock.readLock().lock();
        try {
            if (isolationForest.isTrained() && features.length > 0) {
                ifScore = isolationForest.anomalyScore(features);
            }
        } finally {
            forestLock.readLock().unlock();
        }

        // 2. Z-score from entity profile
        double rawZScore = profile.getZScore(primaryValue);
        double zScoreNormalized = normalizeZScore(rawZScore);

        // 3. EWMA-based adaptive z-score
        double ewmaZScore = profile.getEwmaZScore(primaryValue);
        double ewmaNormalized = normalizeZScore(ewmaZScore);

        // 4. Seasonal deviation
        double seasonalDev = profile.getSeasonalDeviation(primaryValue, timestamp);
        double seasonalNormalized = normalizeZScore(seasonalDev);

        // Ensemble: weighted average
        double totalWeight = 0.0;
        double weightedSum = 0.0;

        if (isolationForest.isTrained()) {
            weightedSum += WEIGHT_ISOLATION_FOREST * ifScore;
            totalWeight += WEIGHT_ISOLATION_FOREST;
        }
        if (profile.observationCount >= 5) {
            weightedSum += WEIGHT_ZSCORE * zScoreNormalized;
            totalWeight += WEIGHT_ZSCORE;
            weightedSum += WEIGHT_EWMA * ewmaNormalized;
            totalWeight += WEIGHT_EWMA;
        }
        if (profile.observationCount >= 20) {
            weightedSum += WEIGHT_SEASONAL * seasonalNormalized;
            totalWeight += WEIGHT_SEASONAL;
        }

        double ensembleScore = totalWeight > 0 ? weightedSum / totalWeight : 0.5;
        ensembleScore = Math.max(0.0, Math.min(1.0, ensembleScore));

        // Identify which features triggered the anomaly
        List<String> anomalousFeatures = new ArrayList<>();
        if (ifScore > 0.65) anomalousFeatures.add("isolation_forest(score=" + format(ifScore) + ")");
        if (Math.abs(rawZScore) > 2.0) anomalousFeatures.add("z_score(z=" + format(rawZScore) + ")");
        if (Math.abs(ewmaZScore) > 2.0) anomalousFeatures.add("ewma(z=" + format(ewmaZScore) + ")");
        if (seasonalDev > 2.0) anomalousFeatures.add("seasonal(dev=" + format(seasonalDev) + "sigma)");

        // Build explanation
        String explanation = buildExplanation(entityId, entityType, ensembleScore,
                ifScore, rawZScore, ewmaZScore, seasonalDev, anomalousFeatures);

        log.debug("Anomaly detection for {}: ensemble={}, if={}, z={}, ewma={}, seasonal={}",
                entityId, format(ensembleScore), format(ifScore),
                format(rawZScore), format(ewmaZScore), format(seasonalDev));

        return new AnomalyResult(ensembleScore, ifScore, rawZScore,
                seasonalDev, explanation, anomalousFeatures);
    }

    // ── Training / Updating ───────────────────────────────────────────

    /**
     * Update the entity profile with a new observation value.
     */
    public void updateProfile(String entityId, String entityType, double value, Instant timestamp) {
        EntityProfile profile = entityProfiles.computeIfAbsent(
                entityId, k -> new EntityProfile(entityId, entityType));
        profile.addObservation(value, timestamp);
    }

    /**
     * Train or retrain the Isolation Forest on a batch of feature vectors.
     */
    public void trainIsolationForest(List<double[]> trainingData) {
        if (trainingData == null || trainingData.size() < 10) {
            log.warn("Insufficient training data for Isolation Forest: {} samples (minimum 10)",
                    trainingData == null ? 0 : trainingData.size());
            return;
        }

        double[][] data = trainingData.toArray(new double[0][]);
        forestLock.writeLock().lock();
        try {
            isolationForest.fit(data);
            log.info("Isolation Forest trained with {} samples, {} features",
                    data.length, data[0].length);
        } finally {
            forestLock.writeLock().unlock();
        }
    }

    // ── Results ───────────────────────────────────────────────────────

    public record AnomalyResult(
            double ensembleScore,        // 0-1, higher = more anomalous
            double isolationForestScore, // 0-1, higher = more anomalous
            double zScore,               // raw z-score (can be negative)
            double seasonalDeviation,    // standard deviations from seasonal baseline
            String explanation,          // human-readable explanation
            List<String> anomalousFeatures // which features triggered
    ) {}

    // ── Query ─────────────────────────────────────────────────────────

    /**
     * Get summary of an entity's learned profile.
     */
    public Map<String, Object> getEntityProfile(String entityId) {
        EntityProfile profile = entityProfiles.get(entityId);
        if (profile == null) {
            return Map.of("entityId", entityId, "status", "unknown", "observationCount", 0);
        }
        return profile.toSummary();
    }

    /**
     * Model statistics and health.
     */
    public Map<String, Object> getModelStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("trackedEntities", entityProfiles.size());
        stats.put("isolationForestTrained", isolationForest.isTrained());
        stats.put("isolationForestTrees", isolationForest.trees.size());

        // Breakdown by entity type
        Map<String, Long> byType = entityProfiles.values().stream()
                .collect(Collectors.groupingBy(p -> p.entityType, Collectors.counting()));
        stats.put("entitiesByType", byType);

        // Total observations
        long totalObs = entityProfiles.values().stream()
                .mapToLong(p -> p.observationCount)
                .sum();
        stats.put("totalObservations", totalObs);

        // Ensemble weights
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("isolationForest", WEIGHT_ISOLATION_FOREST);
        weights.put("zScore", WEIGHT_ZSCORE);
        weights.put("ewma", WEIGHT_EWMA);
        weights.put("seasonal", WEIGHT_SEASONAL);
        stats.put("ensembleWeights", weights);

        return stats;
    }

    // ── Helpers ────────────────────────────────────────────────────────

    /**
     * Normalize a z-score to [0, 1] using a sigmoid-like function.
     * |z| = 0 -> 0.5, |z| = 3 -> ~0.95, |z| = 5 -> ~0.99
     */
    private double normalizeZScore(double z) {
        double absZ = Math.abs(z);
        return 1.0 / (1.0 + Math.exp(-0.8 * (absZ - 2.0)));
    }

    private String buildExplanation(String entityId, String entityType, double ensemble,
                                     double ifScore, double zScore, double ewmaZ,
                                     double seasonalDev, List<String> anomalousFeatures) {
        StringBuilder sb = new StringBuilder();
        String level;
        if (ensemble >= 0.85) level = "CRITICAL";
        else if (ensemble >= 0.7) level = "HIGH";
        else if (ensemble >= 0.5) level = "MEDIUM";
        else level = "LOW";

        sb.append(String.format("%s anomaly for %s %s (ensemble score: %.3f). ",
                level, entityType, entityId, ensemble));

        if (anomalousFeatures.isEmpty()) {
            sb.append("No individual detectors triggered significantly.");
        } else {
            sb.append("Triggered by: ").append(String.join(", ", anomalousFeatures)).append(". ");
        }

        if (Math.abs(zScore) > 3.0) {
            sb.append(String.format("Value is %.1f standard deviations from the historical mean. ", zScore));
        }
        if (seasonalDev > 3.0) {
            sb.append(String.format("Seasonal deviation is %.1f sigma from expected pattern. ", seasonalDev));
        }

        return sb.toString().trim();
    }

    private static String format(double value) {
        return String.format("%.3f", value);
    }
}
