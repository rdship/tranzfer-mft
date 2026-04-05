package com.filetransfer.ai.service.intelligence;

import com.filetransfer.ai.entity.intelligence.IndicatorType;
import com.filetransfer.ai.entity.intelligence.ThreatIndicator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory knowledge graph representing relationships between threat entities.
 *
 * <p>Uses adjacency-list representation with concurrent data structures for
 * thread-safe reads and writes. No external graph database dependency (no Neo4j);
 * the entire graph lives in JVM heap.</p>
 *
 * <h3>Node Types</h3>
 * <ul>
 *   <li>{@code IP} — IP address (v4 or v6)</li>
 *   <li>{@code DOMAIN} — fully qualified domain name</li>
 *   <li>{@code HASH} — file hash (any algorithm)</li>
 *   <li>{@code URL} — full URL</li>
 *   <li>{@code ACTOR} — threat actor or APT group</li>
 *   <li>{@code CAMPAIGN} — named attack campaign</li>
 *   <li>{@code CVE} — Common Vulnerability and Exposure identifier</li>
 *   <li>{@code USER} — platform user account</li>
 *   <li>{@code HOST} — a server or endpoint</li>
 * </ul>
 *
 * <h3>Edge Types</h3>
 * <ul>
 *   <li>{@code COMMUNICATES_WITH} — network communication</li>
 *   <li>{@code RESOLVES_TO} — DNS resolution (domain to IP)</li>
 *   <li>{@code HOSTS} — IP hosts a domain or service</li>
 *   <li>{@code ATTRIBUTED_TO} — IOC attributed to an actor/group</li>
 *   <li>{@code EXPLOITS} — actor/campaign exploits a CVE</li>
 *   <li>{@code SIMILAR_TO} — behavioural or structural similarity</li>
 *   <li>{@code PART_OF_CAMPAIGN} — IOC is part of a named campaign</li>
 *   <li>{@code TARGETS} — actor/campaign targets an entity</li>
 *   <li>{@code USES_TOOL} — actor uses a specific tool or malware</li>
 * </ul>
 *
 * @see ThreatIntelligenceStore
 */
@Service
@Slf4j
public class ThreatKnowledgeGraph {

    // ── Record Definitions ─────────────────────────────────────────────

    /**
     * A node in the threat knowledge graph.
     *
     * @param id         unique identifier (typically the observable value or a UUID)
     * @param type       node classification (IP, DOMAIN, HASH, ACTOR, etc.)
     * @param properties additional key-value metadata
     */
    public record GraphNode(String id, String type, Map<String, Object> properties) {}

    /**
     * A directed, weighted edge in the threat knowledge graph.
     *
     * @param sourceId  source node ID
     * @param targetId  target node ID
     * @param type      edge classification (COMMUNICATES_WITH, RESOLVES_TO, etc.)
     * @param weight    relationship strength (0.0-1.0)
     * @param timestamp when this relationship was observed
     */
    public record GraphEdge(String sourceId, String targetId, String type, double weight, Instant timestamp) {}

    // ── Graph Storage ──────────────────────────────────────────────────

    /** All nodes keyed by ID. */
    private final ConcurrentHashMap<String, GraphNode> nodes = new ConcurrentHashMap<>();

    /** Forward adjacency: source ID to set of outgoing edges. */
    private final ConcurrentHashMap<String, Set<GraphEdge>> adjacency = new ConcurrentHashMap<>();

    /** Reverse adjacency: target ID to set of incoming edges — supports reverse traversal. */
    private final ConcurrentHashMap<String, Set<GraphEdge>> reverseAdjacency = new ConcurrentHashMap<>();

    // ── Mutation API ───────────────────────────────────────────────────

    /**
     * Adds a node to the graph. If a node with the same ID already exists,
     * its properties are merged (new properties overwrite existing ones).
     *
     * @param id    unique node identifier
     * @param type  node type (IP, DOMAIN, ACTOR, etc.)
     * @param props additional metadata (may be null or empty)
     */
    public void addNode(String id, String type, Map<String, Object> props) {
        if (id == null || type == null) {
            return;
        }
        String normalizedId = id.strip().toLowerCase();
        Map<String, Object> safeProps = props != null ? new HashMap<>(props) : new HashMap<>();

        nodes.merge(normalizedId,
                new GraphNode(normalizedId, type, safeProps),
                (existing, incoming) -> {
                    Map<String, Object> merged = new HashMap<>(existing.properties());
                    merged.putAll(incoming.properties());
                    return new GraphNode(normalizedId, type, merged);
                });
    }

    /**
     * Adds a directed edge between two nodes. Both nodes are created automatically
     * (with type "UNKNOWN") if they do not already exist.
     *
     * @param sourceId source node ID
     * @param targetId target node ID
     * @param edgeType edge classification
     * @param weight   relationship strength (0.0-1.0)
     */
    public void addEdge(String sourceId, String targetId, String edgeType, double weight) {
        if (sourceId == null || targetId == null || edgeType == null) {
            return;
        }

        String src = sourceId.strip().toLowerCase();
        String tgt = targetId.strip().toLowerCase();

        // Auto-create nodes if missing
        nodes.computeIfAbsent(src, k -> new GraphNode(k, "UNKNOWN", new HashMap<>()));
        nodes.computeIfAbsent(tgt, k -> new GraphNode(k, "UNKNOWN", new HashMap<>()));

        GraphEdge edge = new GraphEdge(src, tgt, edgeType, weight, Instant.now());

        adjacency.computeIfAbsent(src, k -> ConcurrentHashMap.newKeySet()).add(edge);
        reverseAdjacency.computeIfAbsent(tgt, k -> ConcurrentHashMap.newKeySet()).add(edge);
    }

    /**
     * Removes a node and all its associated edges from the graph.
     *
     * @param nodeId the node to remove
     */
    public void removeNode(String nodeId) {
        if (nodeId == null) {
            return;
        }
        String normalized = nodeId.strip().toLowerCase();

        nodes.remove(normalized);

        // Remove outgoing edges
        Set<GraphEdge> outgoing = adjacency.remove(normalized);
        if (outgoing != null) {
            for (GraphEdge edge : outgoing) {
                Set<GraphEdge> reverse = reverseAdjacency.get(edge.targetId());
                if (reverse != null) {
                    reverse.remove(edge);
                }
            }
        }

        // Remove incoming edges
        Set<GraphEdge> incoming = reverseAdjacency.remove(normalized);
        if (incoming != null) {
            for (GraphEdge edge : incoming) {
                Set<GraphEdge> forward = adjacency.get(edge.sourceId());
                if (forward != null) {
                    forward.remove(edge);
                }
            }
        }
    }

    // ── Query API ──────────────────────────────────────────────────────

    /**
     * Returns a specific node by ID.
     *
     * @param nodeId the node ID
     * @return the node, or empty if not found
     */
    public Optional<GraphNode> getNode(String nodeId) {
        if (nodeId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(nodes.get(nodeId.strip().toLowerCase()));
    }

    /**
     * Returns all direct neighbours of a node (both directions).
     *
     * @param nodeId the source node ID
     * @return set of neighbouring nodes (empty if node not found)
     */
    public Set<GraphNode> getNeighbors(String nodeId) {
        if (nodeId == null) {
            return Collections.emptySet();
        }
        String normalized = nodeId.strip().toLowerCase();
        Set<GraphNode> result = new LinkedHashSet<>();

        Set<GraphEdge> outgoing = adjacency.getOrDefault(normalized, Collections.emptySet());
        for (GraphEdge edge : outgoing) {
            GraphNode target = nodes.get(edge.targetId());
            if (target != null) {
                result.add(target);
            }
        }

        Set<GraphEdge> incoming = reverseAdjacency.getOrDefault(normalized, Collections.emptySet());
        for (GraphEdge edge : incoming) {
            GraphNode source = nodes.get(edge.sourceId());
            if (source != null) {
                result.add(source);
            }
        }

        return Collections.unmodifiableSet(result);
    }

    /**
     * Returns direct neighbours connected by a specific edge type.
     *
     * @param nodeId   the source node ID
     * @param edgeType the edge type filter
     * @return set of matching neighbouring nodes
     */
    public Set<GraphNode> getNeighbors(String nodeId, String edgeType) {
        if (nodeId == null || edgeType == null) {
            return Collections.emptySet();
        }
        String normalized = nodeId.strip().toLowerCase();
        Set<GraphNode> result = new LinkedHashSet<>();

        Set<GraphEdge> outgoing = adjacency.getOrDefault(normalized, Collections.emptySet());
        for (GraphEdge edge : outgoing) {
            if (edgeType.equals(edge.type())) {
                GraphNode target = nodes.get(edge.targetId());
                if (target != null) {
                    result.add(target);
                }
            }
        }

        Set<GraphEdge> incoming = reverseAdjacency.getOrDefault(normalized, Collections.emptySet());
        for (GraphEdge edge : incoming) {
            if (edgeType.equals(edge.type())) {
                GraphNode source = nodes.get(edge.sourceId());
                if (source != null) {
                    result.add(source);
                }
            }
        }

        return Collections.unmodifiableSet(result);
    }

    /**
     * Finds the shortest path between two nodes using breadth-first search.
     *
     * @param fromId   start node ID
     * @param toId     end node ID
     * @param maxDepth maximum hops to search
     * @return ordered list of nodes on the shortest path, or empty if no path exists
     */
    public List<GraphNode> findPath(String fromId, String toId, int maxDepth) {
        if (fromId == null || toId == null) {
            return Collections.emptyList();
        }

        String from = fromId.strip().toLowerCase();
        String to = toId.strip().toLowerCase();

        if (from.equals(to)) {
            GraphNode node = nodes.get(from);
            return node != null ? List.of(node) : Collections.emptyList();
        }

        if (!nodes.containsKey(from) || !nodes.containsKey(to)) {
            return Collections.emptyList();
        }

        // BFS with parent tracking
        Map<String, String> parentMap = new HashMap<>();
        Queue<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();

        queue.add(from);
        visited.add(from);
        parentMap.put(from, null);

        int depth = 0;
        int nodesAtCurrentDepth = 1;
        int nodesAtNextDepth = 0;

        while (!queue.isEmpty() && depth < maxDepth) {
            String current = queue.poll();
            nodesAtCurrentDepth--;

            if (current.equals(to)) {
                return reconstructPath(parentMap, to);
            }

            // Explore neighbours
            Set<GraphEdge> outgoing = adjacency.getOrDefault(current, Collections.emptySet());
            for (GraphEdge edge : outgoing) {
                if (!visited.contains(edge.targetId())) {
                    visited.add(edge.targetId());
                    parentMap.put(edge.targetId(), current);
                    queue.add(edge.targetId());
                    nodesAtNextDepth++;
                }
            }

            Set<GraphEdge> incoming = reverseAdjacency.getOrDefault(current, Collections.emptySet());
            for (GraphEdge edge : incoming) {
                if (!visited.contains(edge.sourceId())) {
                    visited.add(edge.sourceId());
                    parentMap.put(edge.sourceId(), current);
                    queue.add(edge.sourceId());
                    nodesAtNextDepth++;
                }
            }

            if (nodesAtCurrentDepth == 0) {
                depth++;
                nodesAtCurrentDepth = nodesAtNextDepth;
                nodesAtNextDepth = 0;
            }
        }

        // Check if target is in the queue (found at maxDepth boundary)
        if (parentMap.containsKey(to)) {
            return reconstructPath(parentMap, to);
        }

        return Collections.emptyList();
    }

    /**
     * Finds related threats within a 2-hop neighbourhood of a given IOC value.
     *
     * @param iocValue   the IOC value to start from (IP, domain, hash, etc.)
     * @param maxResults maximum number of related nodes to return
     * @return related threat nodes sorted by connection weight (strongest first)
     */
    public List<GraphNode> findRelatedThreats(String iocValue, int maxResults) {
        if (iocValue == null) {
            return Collections.emptyList();
        }

        String normalized = iocValue.strip().toLowerCase();
        if (!nodes.containsKey(normalized)) {
            return Collections.emptyList();
        }

        // Collect 2-hop neighbourhood with weight accumulation
        Map<String, Double> nodeWeights = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        visited.add(normalized);

        // 1-hop
        Set<GraphEdge> hop1Edges = adjacency.getOrDefault(normalized, Collections.emptySet());
        Set<GraphEdge> hop1Reverse = reverseAdjacency.getOrDefault(normalized, Collections.emptySet());

        for (GraphEdge edge : hop1Edges) {
            visited.add(edge.targetId());
            nodeWeights.merge(edge.targetId(), edge.weight(), Double::sum);
        }
        for (GraphEdge edge : hop1Reverse) {
            visited.add(edge.sourceId());
            nodeWeights.merge(edge.sourceId(), edge.weight(), Double::sum);
        }

        // 2-hop
        Set<String> firstHopNodes = new HashSet<>(nodeWeights.keySet());
        for (String hop1Node : firstHopNodes) {
            Set<GraphEdge> hop2Edges = adjacency.getOrDefault(hop1Node, Collections.emptySet());
            for (GraphEdge edge : hop2Edges) {
                if (!visited.contains(edge.targetId())) {
                    // 2-hop weight is discounted
                    nodeWeights.merge(edge.targetId(), edge.weight() * 0.5, Double::sum);
                }
            }
            Set<GraphEdge> hop2Reverse = reverseAdjacency.getOrDefault(hop1Node, Collections.emptySet());
            for (GraphEdge edge : hop2Reverse) {
                if (!visited.contains(edge.sourceId())) {
                    nodeWeights.merge(edge.sourceId(), edge.weight() * 0.5, Double::sum);
                }
            }
        }

        return nodeWeights.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(Math.max(1, maxResults))
                .map(e -> nodes.get(e.getKey()))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Finds connected components (clusters) in the graph using union-find.
     *
     * @return list of clusters, each being a set of node IDs, sorted by size descending
     */
    public List<Set<String>> findClusters() {
        // Union-Find
        Map<String, String> parent = new HashMap<>();
        Map<String, Integer> rank = new HashMap<>();

        for (String nodeId : nodes.keySet()) {
            parent.put(nodeId, nodeId);
            rank.put(nodeId, 0);
        }

        for (Map.Entry<String, Set<GraphEdge>> entry : adjacency.entrySet()) {
            for (GraphEdge edge : entry.getValue()) {
                union(parent, rank, edge.sourceId(), edge.targetId());
            }
        }

        // Group nodes by root
        Map<String, Set<String>> clusters = new HashMap<>();
        for (String nodeId : nodes.keySet()) {
            String root = find(parent, nodeId);
            clusters.computeIfAbsent(root, k -> new LinkedHashSet<>()).add(nodeId);
        }

        return clusters.values().stream()
                .sorted(Comparator.comparingInt(Set<String>::size).reversed())
                .toList();
    }

    /**
     * Computes an approximation of PageRank to find the most connected and
     * important nodes in the graph.
     *
     * @param iterations number of PageRank iterations (typically 10-20)
     * @return map of node ID to PageRank score, sorted by score descending
     */
    public Map<String, Double> computePageRank(int iterations) {
        int nodeCount = nodes.size();
        if (nodeCount == 0) {
            return Collections.emptyMap();
        }

        double dampingFactor = 0.85;
        double initialRank = 1.0 / nodeCount;

        Map<String, Double> ranks = new HashMap<>();
        for (String nodeId : nodes.keySet()) {
            ranks.put(nodeId, initialRank);
        }

        for (int i = 0; i < Math.max(1, Math.min(iterations, 100)); i++) {
            Map<String, Double> newRanks = new HashMap<>();

            for (String nodeId : nodes.keySet()) {
                double incomingRank = 0.0;

                Set<GraphEdge> incoming = reverseAdjacency.getOrDefault(nodeId, Collections.emptySet());
                for (GraphEdge edge : incoming) {
                    int outDegree = adjacency.getOrDefault(edge.sourceId(), Collections.emptySet()).size();
                    if (outDegree > 0) {
                        incomingRank += ranks.getOrDefault(edge.sourceId(), 0.0) / outDegree;
                    }
                }

                double newRank = ((1.0 - dampingFactor) / nodeCount) + (dampingFactor * incomingRank);
                newRanks.put(nodeId, newRank);
            }

            ranks = newRanks;
        }

        // Sort by rank descending
        return ranks.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new));
    }

    /**
     * Extracts the subgraph associated with a specific attack campaign.
     *
     * <p>Traverses all nodes connected to the campaign node via
     * {@code PART_OF_CAMPAIGN}, {@code ATTRIBUTED_TO}, and {@code USES_TOOL} edges,
     * then includes inter-node relationships within that set.</p>
     *
     * @param campaignId the campaign node ID
     * @return a map containing {@code "nodes"} (list of GraphNode) and
     *         {@code "edges"} (list of GraphEdge), or empty if campaign not found
     */
    public Map<String, Object> getCampaignGraph(String campaignId) {
        if (campaignId == null) {
            return Collections.emptyMap();
        }

        String normalized = campaignId.strip().toLowerCase();
        GraphNode campaignNode = nodes.get(normalized);
        if (campaignNode == null) {
            return Collections.emptyMap();
        }

        Set<String> campaignNodeIds = new LinkedHashSet<>();
        campaignNodeIds.add(normalized);

        // Collect all nodes directly connected to the campaign
        Set<String> campaignEdgeTypes = Set.of("PART_OF_CAMPAIGN", "ATTRIBUTED_TO", "USES_TOOL", "TARGETS");

        Set<GraphEdge> outgoing = adjacency.getOrDefault(normalized, Collections.emptySet());
        for (GraphEdge edge : outgoing) {
            campaignNodeIds.add(edge.targetId());
        }

        Set<GraphEdge> incoming = reverseAdjacency.getOrDefault(normalized, Collections.emptySet());
        for (GraphEdge edge : incoming) {
            if (campaignEdgeTypes.contains(edge.type())) {
                campaignNodeIds.add(edge.sourceId());
            }
        }

        // Collect all edges between campaign members
        List<GraphEdge> campaignEdges = new ArrayList<>();
        for (String nodeId : campaignNodeIds) {
            Set<GraphEdge> nodeEdges = adjacency.getOrDefault(nodeId, Collections.emptySet());
            for (GraphEdge edge : nodeEdges) {
                if (campaignNodeIds.contains(edge.targetId())) {
                    campaignEdges.add(edge);
                }
            }
        }

        List<GraphNode> campaignNodes = campaignNodeIds.stream()
                .map(nodes::get)
                .filter(Objects::nonNull)
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("campaignId", normalized);
        result.put("nodes", campaignNodes);
        result.put("edges", campaignEdges);
        result.put("nodeCount", campaignNodes.size());
        result.put("edgeCount", campaignEdges.size());
        return result;
    }

    // ── Graph Statistics ───────────────────────────────────────────────

    /**
     * Returns comprehensive statistics about the knowledge graph.
     *
     * @return map of statistic names to values
     */
    public Map<String, Object> getGraphStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        // Node statistics
        stats.put("totalNodes", nodes.size());
        Map<String, Long> nodesByType = nodes.values().stream()
                .collect(Collectors.groupingBy(GraphNode::type, Collectors.counting()));
        stats.put("nodesByType", nodesByType);

        // Edge statistics
        long totalEdges = adjacency.values().stream().mapToLong(Set::size).sum();
        stats.put("totalEdges", totalEdges);
        Map<String, Long> edgesByType = adjacency.values().stream()
                .flatMap(Set::stream)
                .collect(Collectors.groupingBy(GraphEdge::type, Collectors.counting()));
        stats.put("edgesByType", edgesByType);

        // Degree statistics
        if (!nodes.isEmpty()) {
            Map<String, Integer> degrees = new HashMap<>();
            for (String nodeId : nodes.keySet()) {
                int outDegree = adjacency.getOrDefault(nodeId, Collections.emptySet()).size();
                int inDegree = reverseAdjacency.getOrDefault(nodeId, Collections.emptySet()).size();
                degrees.put(nodeId, outDegree + inDegree);
            }

            double avgDegree = degrees.values().stream()
                    .mapToInt(Integer::intValue)
                    .average()
                    .orElse(0.0);
            int maxDegree = degrees.values().stream()
                    .mapToInt(Integer::intValue)
                    .max()
                    .orElse(0);
            String maxDegreeNode = degrees.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("none");

            stats.put("averageDegree", Math.round(avgDegree * 100.0) / 100.0);
            stats.put("maxDegree", maxDegree);
            stats.put("maxDegreeNode", maxDegreeNode);
        }

        // Cluster statistics
        List<Set<String>> clusters = findClusters();
        stats.put("clusterCount", clusters.size());
        if (!clusters.isEmpty()) {
            stats.put("largestClusterSize", clusters.get(0).size());
        }

        return stats;
    }

    // ── Rebuild from Threat Intel Store ─────────────────────────────────

    /**
     * Rebuilds the knowledge graph from the current contents of the threat
     * intelligence store.
     *
     * <p>This method clears the existing graph and creates nodes for every
     * indicator in the store, plus relationship edges inferred from shared
     * metadata (common sources, MITRE techniques, campaigns, subnets, etc.).</p>
     *
     * @param store the threat intelligence store to read indicators from
     */
    public void rebuildFromStore(ThreatIntelligenceStore store) {
        log.info("Rebuilding knowledge graph from threat intelligence store...");

        long startTime = System.currentTimeMillis();

        // Clear existing graph
        nodes.clear();
        adjacency.clear();
        reverseAdjacency.clear();

        Collection<ThreatIndicator> allIndicators = store.getAllIndicators();

        // Pass 1: Create nodes for every indicator
        for (ThreatIndicator indicator : allIndicators) {
            String nodeType = mapIndicatorTypeToNodeType(indicator.getType());
            Map<String, Object> props = new HashMap<>();
            props.put("threatLevel", indicator.getThreatLevel() != null ? indicator.getThreatLevel().name() : "UNKNOWN");
            props.put("confidence", indicator.getConfidence());
            props.put("sightings", indicator.getSightings());
            props.put("firstSeen", indicator.getFirstSeen() != null ? indicator.getFirstSeen().toString() : null);
            props.put("lastSeen", indicator.getLastSeen() != null ? indicator.getLastSeen().toString() : null);

            addNode(indicator.getValue(), nodeType, props);
        }

        // Pass 2: Infer relationships
        Map<String, List<ThreatIndicator>> bySource = new HashMap<>();
        Map<String, List<ThreatIndicator>> byTechnique = new HashMap<>();
        Map<String, List<ThreatIndicator>> bySubnet = new HashMap<>();

        for (ThreatIndicator indicator : allIndicators) {
            // Group by source for co-occurrence edges
            for (String source : indicator.getSourcesList()) {
                bySource.computeIfAbsent(source, k -> new ArrayList<>()).add(indicator);
            }

            // Group by MITRE technique for SIMILAR_TO edges
            for (String technique : indicator.getMitreTechniquesList()) {
                byTechnique.computeIfAbsent(technique, k -> new ArrayList<>()).add(indicator);
            }

            // Group IPs by /24 subnet
            if (indicator.getType() == IndicatorType.IP) {
                String prefix = extractSubnetPrefix(indicator.getValue());
                if (prefix != null) {
                    bySubnet.computeIfAbsent(prefix, k -> new ArrayList<>()).add(indicator);
                }
            }

            // Parse contextJson for campaign associations
            if (indicator.getContextJson() != null && indicator.getContextJson().contains("campaign")) {
                String campaignName = extractCampaignName(indicator.getContextJson());
                if (campaignName != null) {
                    addNode(campaignName, "CAMPAIGN", Map.of());
                    addEdge(indicator.getValue(), campaignName, "PART_OF_CAMPAIGN", 0.8);
                }
            }
        }

        // Create SIMILAR_TO edges for indicators sharing the same source (limit fan-out)
        for (List<ThreatIndicator> group : bySource.values()) {
            createPairwiseEdges(group, "SIMILAR_TO", 0.3, 50);
        }

        // Create SIMILAR_TO edges for indicators sharing the same MITRE technique
        for (List<ThreatIndicator> group : byTechnique.values()) {
            createPairwiseEdges(group, "SIMILAR_TO", 0.4, 30);
        }

        // Create COMMUNICATES_WITH edges for IPs in the same /24 subnet
        for (List<ThreatIndicator> group : bySubnet.values()) {
            if (group.size() > 1 && group.size() <= 20) {
                createPairwiseEdges(group, "COMMUNICATES_WITH", 0.5, 20);
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Knowledge graph rebuilt in {}ms: {} nodes, {} edges",
                elapsed, nodes.size(),
                adjacency.values().stream().mapToLong(Set::size).sum());
    }

    // ── Private Helpers ────────────────────────────────────────────────

    /**
     * Reconstructs a path from the BFS parent map.
     */
    private List<GraphNode> reconstructPath(Map<String, String> parentMap, String target) {
        List<GraphNode> path = new ArrayList<>();
        String current = target;

        while (current != null) {
            GraphNode node = nodes.get(current);
            if (node != null) {
                path.add(node);
            }
            current = parentMap.get(current);
        }

        Collections.reverse(path);
        return Collections.unmodifiableList(path);
    }

    /**
     * Union-Find: find operation with path compression.
     */
    private String find(Map<String, String> parent, String x) {
        if (!parent.get(x).equals(x)) {
            parent.put(x, find(parent, parent.get(x)));
        }
        return parent.get(x);
    }

    /**
     * Union-Find: union by rank.
     */
    private void union(Map<String, String> parent, Map<String, Integer> rank, String x, String y) {
        String rootX = find(parent, x);
        String rootY = find(parent, y);

        if (rootX.equals(rootY)) {
            return;
        }

        int rankX = rank.getOrDefault(rootX, 0);
        int rankY = rank.getOrDefault(rootY, 0);

        if (rankX < rankY) {
            parent.put(rootX, rootY);
        } else if (rankX > rankY) {
            parent.put(rootY, rootX);
        } else {
            parent.put(rootY, rootX);
            rank.put(rootX, rankX + 1);
        }
    }

    /**
     * Maps an {@link IndicatorType} to a graph node type string.
     */
    private String mapIndicatorTypeToNodeType(IndicatorType type) {
        return switch (type) {
            case IP, CIDR -> "IP";
            case DOMAIN -> "DOMAIN";
            case HASH_MD5, HASH_SHA1, HASH_SHA256 -> "HASH";
            case URL -> "URL";
            case CVE -> "CVE";
            case EMAIL -> "ACTOR";
            default -> "UNKNOWN";
        };
    }

    /**
     * Creates pairwise edges between indicators in a group, limited to avoid
     * quadratic blow-up for large groups.
     */
    private void createPairwiseEdges(List<ThreatIndicator> group, String edgeType,
                                     double weight, int maxPairs) {
        int pairs = 0;
        for (int i = 0; i < group.size() && pairs < maxPairs; i++) {
            for (int j = i + 1; j < group.size() && pairs < maxPairs; j++) {
                addEdge(group.get(i).getValue(), group.get(j).getValue(), edgeType, weight);
                pairs++;
            }
        }
    }

    /**
     * Extracts the /24 subnet prefix from an IPv4 address.
     */
    private String extractSubnetPrefix(String ip) {
        if (ip == null || !ip.contains(".")) {
            return null;
        }
        String[] octets = ip.split("\\.");
        if (octets.length != 4) {
            return null;
        }
        return octets[0] + "." + octets[1] + "." + octets[2];
    }

    /**
     * Naive extraction of campaign name from contextJson.
     * Looks for a "campaign" key and extracts its value.
     */
    private String extractCampaignName(String contextJson) {
        if (contextJson == null) {
            return null;
        }
        // Simple pattern: "campaign":"SomeName" or "campaign": "SomeName"
        int idx = contextJson.indexOf("\"campaign\"");
        if (idx < 0) {
            return null;
        }
        int colonIdx = contextJson.indexOf(':', idx + 10);
        if (colonIdx < 0) {
            return null;
        }
        int quoteStart = contextJson.indexOf('"', colonIdx + 1);
        if (quoteStart < 0) {
            return null;
        }
        int quoteEnd = contextJson.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) {
            return null;
        }
        String campaign = contextJson.substring(quoteStart + 1, quoteEnd).strip();
        return campaign.isEmpty() ? null : campaign.toLowerCase();
    }
}
