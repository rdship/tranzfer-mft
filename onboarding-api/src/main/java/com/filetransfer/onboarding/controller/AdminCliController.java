package com.filetransfer.onboarding.controller;

import com.filetransfer.shared.cluster.ClusterService;
import com.filetransfer.shared.entity.*;
import com.filetransfer.shared.entity.core.*;
import com.filetransfer.shared.entity.transfer.*;
import com.filetransfer.shared.entity.vfs.*;
import com.filetransfer.shared.entity.security.*;
import com.filetransfer.shared.entity.integration.*;
import com.filetransfer.shared.enums.ClusterCommunicationMode;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.*;
import com.filetransfer.shared.util.TrackIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Backend for the Admin CLI terminal in the UI.
 * Accepts plain-text commands and returns structured responses.
 */
@RestController
@RequestMapping("/api/cli")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Slf4j
public class AdminCliController {

    private final UserRepository userRepository;
    private final TransferAccountRepository accountRepository;
    private final FolderMappingRepository folderMappingRepository;
    private final FileTransferRecordRepository transferRecordRepository;
    private final ServiceRegistrationRepository serviceRegistrationRepository;
    private final AuditLogRepository auditLogRepository;
    private final FileFlowRepository flowRepository;
    private final FlowExecutionRepository flowExecutionRepository;
    private final TrackIdGenerator trackIdGenerator;
    private final ClusterService clusterService;
    private final ClusterNodeRepository clusterNodeRepository;

    private static final int MAX_COMMAND_LENGTH = 500;
    private static final int MAX_SEARCH_RESULTS = 100;

    @Transactional
    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> execute(@RequestBody Map<String, String> body) {
        String input = body.getOrDefault("command", "").trim();
        if (input.isEmpty()) {
            return ok("Type 'help' for available commands.");
        }
        // Cap command length to prevent abuse
        if (input.length() > MAX_COMMAND_LENGTH) {
            return ok("Error: command too long (max " + MAX_COMMAND_LENGTH + " characters)");
        }
        // Strip control characters to prevent log injection
        input = input.replaceAll("[\\r\\n\\t]", " ");

        String[] parts = input.split("\\s+", 10);
        String cmd = parts[0].toLowerCase();

        try {
            String result = switch (cmd) {
                case "help" -> helpText();
                case "status" -> statusCommand();
                case "accounts" -> accountsCommand(parts);
                case "users" -> usersCommand(parts);
                case "flows" -> flowsCommand(parts);
                case "track" -> trackCommand(parts);
                case "search" -> searchCommand(parts);
                case "services" -> servicesCommand();
                case "cluster" -> clusterCommand(parts);
                case "logs" -> logsCommand(parts);
                case "onboard" -> onboardCommand(parts);
                case "whoami" -> "Admin CLI — TranzFer MFT Platform";
                case "version" -> "TranzFer MFT v2.1.0";
                default -> "Unknown command: " + cmd + ". Type 'help' for available commands.";
            };
            return ok(result);
        } catch (Exception e) {
            return ok("Error: " + e.getMessage());
        }
    }

    private String helpText() {
        return """
            === TranzFer MFT Admin CLI ===

            COMMANDS:
              help                         Show this help
              status                       Platform overview
              version                      Show version

            ACCOUNTS:
              accounts list                List all transfer accounts
              accounts create <proto> <user> <pass>  Create account (SFTP/FTP)
              accounts disable <username>  Disable account
              accounts enable <username>   Enable account

            USERS:
              users list                   List system users
              users promote <email>        Promote user to ADMIN
              users demote <email>         Demote admin to USER

            FLOWS:
              flows list                   List file processing flows
              flows info <name>            Show flow details

            TRACKING:
              track <trackId>              Lookup transfer by track ID
              search file <pattern>        Search transfers by filename
              search account <username>    Search by account
              search recent [N]            Show N most recent transfers

            SERVICES:
              services                     List registered service instances

            CLUSTER:
              cluster status               Show this instance's cluster info
              cluster list                 List all known clusters
              cluster services [id]        Services in a cluster
              cluster mode                 Show current communication mode
              cluster mode within          Set to within-cluster only
              cluster mode cross           Set to cross-cluster (federated)

            LOGS:
              logs recent [N]              Show N most recent audit logs
              logs search <term>           Search audit logs

            ONBOARDING:
              onboard <email> <password>   Register new user + create SFTP account
            """;
    }

    private String statusCommand() {
        long accounts = accountRepository.count();
        long activeAccounts = accountRepository.findAll().stream().filter(TransferAccount::isActive).count();
        long users = userRepository.count();
        long transfers = transferRecordRepository.count();
        long services = serviceRegistrationRepository.count();
        long flows = flowRepository.findByActiveTrueOrderByPriorityAsc().size();
        long executions = flowExecutionRepository.count();
        List<String> clusters = clusterService.listClusters();

        return String.format("""
            === Platform Status ===
            Users:             %d
            Accounts:          %d active / %d total
            Transfer Records:  %d
            Active Flows:      %d
            Flow Executions:   %d
            Services:          %d registered
            Cluster:           %s
            Comm. Mode:        %s
            Known Clusters:    %d (%s)
            """, users, activeAccounts, accounts, transfers, flows, executions, services,
                clusterService.getClusterId(), clusterService.getCommunicationMode(),
                clusters.size(), String.join(", ", clusters));
    }

    private String accountsCommand(String[] parts) {
        if (parts.length < 2 || parts[1].equals("list")) {
            List<TransferAccount> accounts = accountRepository.findAll();
            if (accounts.isEmpty()) return "No accounts found.";
            StringBuilder sb = new StringBuilder("ID | Username | Protocol | Active | Home\n");
            sb.append("-".repeat(70)).append("\n");
            for (TransferAccount a : accounts) {
                sb.append(String.format("%s | %-15s | %-6s | %-6s | %s\n",
                        a.getId().toString().substring(0, 8), a.getUsername(),
                        a.getProtocol(), a.isActive() ? "YES" : "NO", a.getHomeDir()));
            }
            return sb.toString();
        }
        if (parts[1].equals("create") && parts.length >= 5) {
            String proto = parts[2].toUpperCase();
            String username = parts[3];
            String password = parts[4];
            if (accountRepository.findAll().stream().anyMatch(a -> a.getUsername().equals(username))) {
                return "Account already exists: " + username;
            }
            User systemUser = userRepository.findAll().stream().findFirst()
                    .orElseThrow(() -> new RuntimeException("No system users exist"));
            TransferAccount account = TransferAccount.builder()
                    .user(systemUser).protocol(Protocol.valueOf(proto))
                    .username(username).passwordHash(new BCryptPasswordEncoder().encode(password))
                    .homeDir("/data/" + proto.toLowerCase() + "/" + username)
                    .build();
            accountRepository.save(account);
            return "Created " + proto + " account: " + username;
        }
        if ((parts[1].equals("enable") || parts[1].equals("disable")) && parts.length >= 3) {
            boolean enable = parts[1].equals("enable");
            String username = parts[2];
            TransferAccount account = accountRepository.findAll().stream()
                    .filter(a -> a.getUsername().equals(username)).findFirst()
                    .orElseThrow(() -> new RuntimeException("Account not found: " + username));
            account.setActive(enable);
            accountRepository.save(account);
            return "Account " + username + " " + (enable ? "enabled" : "disabled");
        }
        return "Usage: accounts list | accounts create <SFTP|FTP> <user> <pass> | accounts enable/disable <user>";
    }

    private String usersCommand(String[] parts) {
        if (parts.length < 2 || parts[1].equals("list")) {
            List<User> users = userRepository.findAll();
            StringBuilder sb = new StringBuilder("Email | Role | Created\n");
            sb.append("-".repeat(50)).append("\n");
            for (User u : users) {
                sb.append(String.format("%-30s | %-6s | %s\n", u.getEmail(), u.getRole(),
                        u.getCreatedAt() != null ? u.getCreatedAt().toString().substring(0, 10) : "—"));
            }
            return sb.toString();
        }
        if (parts[1].equals("promote") && parts.length >= 3) {
            User u = userRepository.findByEmail(parts[2])
                    .orElseThrow(() -> new RuntimeException("User not found: " + parts[2]));
            u.setRole(com.filetransfer.shared.enums.UserRole.ADMIN);
            userRepository.save(u);
            return "Promoted " + parts[2] + " to ADMIN";
        }
        if (parts[1].equals("demote") && parts.length >= 3) {
            User u = userRepository.findByEmail(parts[2])
                    .orElseThrow(() -> new RuntimeException("User not found: " + parts[2]));
            u.setRole(com.filetransfer.shared.enums.UserRole.USER);
            userRepository.save(u);
            return "Demoted " + parts[2] + " to USER";
        }
        return "Usage: users list | users promote <email> | users demote <email>";
    }

    private String flowsCommand(String[] parts) {
        if (parts.length < 2 || parts[1].equals("list")) {
            List<FileFlow> flows = flowRepository.findByActiveTrueOrderByPriorityAsc();
            if (flows.isEmpty()) return "No active flows. Create one via Admin UI → Flows or API.";
            StringBuilder sb = new StringBuilder("Name | Pattern | Steps | Priority\n");
            sb.append("-".repeat(60)).append("\n");
            for (FileFlow f : flows) {
                sb.append(String.format("%-20s | %-15s | %d steps | P%d\n",
                        f.getName(), f.getFilenamePattern() != null ? f.getFilenamePattern() : "*",
                        f.getSteps().size(), f.getPriority()));
            }
            return sb.toString();
        }
        if (parts[1].equals("info") && parts.length >= 3) {
            FileFlow flow = flowRepository.findByNameAndActiveTrue(parts[2])
                    .orElseThrow(() -> new RuntimeException("Flow not found: " + parts[2]));
            StringBuilder sb = new StringBuilder();
            sb.append("Flow: ").append(flow.getName()).append("\n");
            sb.append("Description: ").append(flow.getDescription()).append("\n");
            sb.append("Pattern: ").append(flow.getFilenamePattern()).append("\n");
            sb.append("Steps:\n");
            for (int i = 0; i < flow.getSteps().size(); i++) {
                FileFlow.FlowStep s = flow.getSteps().get(i);
                sb.append(String.format("  %d. %s %s\n", i + 1, s.getType(),
                        s.getConfig() != null ? s.getConfig() : ""));
            }
            return sb.toString();
        }
        return "Usage: flows list | flows info <name>";
    }

    private String trackCommand(String[] parts) {
        if (parts.length < 2) return "Usage: track <trackId>";
        return flowExecutionRepository.findByTrackId(parts[1])
                .map(e -> String.format("""
                    Track ID:    %s
                    Flow:        %s
                    File:        %s
                    Status:      %s
                    Step:        %d/%d
                    Started:     %s
                    Completed:   %s
                    Error:       %s
                    """, e.getTrackId(), e.getFlow().getName(), e.getOriginalFilename(),
                    e.getStatus(), e.getCurrentStep(),
                    e.getFlow().getSteps().size(),
                    e.getStartedAt(), e.getCompletedAt() != null ? e.getCompletedAt() : "—",
                    e.getErrorMessage() != null ? e.getErrorMessage() : "—"))
                .orElse("No execution found for track ID: " + parts[1]);
    }

    private String searchCommand(String[] parts) {
        if (parts.length < 3) return "Usage: search file <pattern> | search account <username> | search recent [N]";
        if (parts[1].equals("recent")) {
            int n = parts.length >= 3 ? Integer.parseInt(parts[2]) : 10;
            n = Math.max(1, Math.min(n, MAX_SEARCH_RESULTS));
            List<FileTransferRecord> records = transferRecordRepository.findAll(
                    PageRequest.of(0, n, Sort.by(Sort.Direction.DESC, "uploadedAt"))).getContent();
            if (records.isEmpty()) return "No transfer records found.";
            StringBuilder sb = new StringBuilder("File | Status | Uploaded\n");
            sb.append("-".repeat(60)).append("\n");
            for (FileTransferRecord r : records) {
                sb.append(String.format("%-25s | %-10s | %s\n",
                        r.getOriginalFilename(), r.getStatus(),
                        r.getUploadedAt() != null ? r.getUploadedAt().toString().substring(0, 19) : "—"));
            }
            return sb.toString();
        }
        if (parts[1].equals("file")) {
            String pattern = parts[2];
            List<FileTransferRecord> records = transferRecordRepository.findAll().stream()
                    .filter(r -> r.getOriginalFilename() != null && r.getOriginalFilename().contains(pattern))
                    .limit(20).collect(Collectors.toList());
            if (records.isEmpty()) return "No transfers found matching: " + pattern;
            StringBuilder sb = new StringBuilder();
            for (FileTransferRecord r : records) {
                sb.append(String.format("%s | %s | %s\n",
                        r.getOriginalFilename(), r.getStatus(), r.getUploadedAt()));
            }
            return sb.toString();
        }
        return "Usage: search file <pattern> | search recent [N]";
    }

    private String servicesCommand() {
        List<ServiceRegistration> services = serviceRegistrationRepository.findAll();
        if (services.isEmpty()) return "No services registered.";

        StringBuilder sb = new StringBuilder();
        sb.append("Communication mode: ").append(clusterService.getCommunicationMode())
          .append(" (cluster: ").append(clusterService.getClusterId()).append(")\n\n");
        sb.append("Type | Host | Cluster | Active\n");
        sb.append("-".repeat(60)).append("\n");
        // Deduplicate by serviceInstanceId
        Map<String, ServiceRegistration> unique = new LinkedHashMap<>();
        for (ServiceRegistration s : services) {
            unique.put(s.getServiceType() + "@" + s.getHost(), s);
        }
        for (ServiceRegistration s : unique.values()) {
            String marker = s.getClusterId().equals(clusterService.getClusterId()) ? "" : " *";
            sb.append(String.format("%-12s | %-20s | %-12s | %s%s\n",
                    s.getServiceType(), s.getHost(), s.getClusterId(),
                    s.isActive() ? "UP" : "DOWN", marker));
        }
        sb.append("\n* = different cluster");
        return sb.toString();
    }

    private String clusterCommand(String[] parts) {
        if (parts.length < 2) return clusterStatus();
        String sub = parts[1].toLowerCase();
        return switch (sub) {
            case "status" -> clusterStatus();
            case "list" -> clusterList();
            case "services" -> clusterServices(parts.length >= 3 ? parts[2] : clusterService.getClusterId());
            case "mode" -> {
                if (parts.length >= 3) {
                    yield clusterSetMode(parts[2]);
                }
                yield "Communication mode: " + clusterService.getCommunicationMode()
                        + "\nCluster: " + clusterService.getClusterId();
            }
            default -> "Usage: cluster status | list | services [id] | mode [within|cross]";
        };
    }

    private String clusterStatus() {
        String clusterId = clusterService.getClusterId();
        ClusterCommunicationMode mode = clusterService.getCommunicationMode();
        List<ServiceRegistration> localServices = clusterService.getServicesInCluster(clusterId);
        List<String> allClusters = clusterService.listClusters();

        return String.format("""
            === Cluster Status ===
            Cluster ID:         %s
            Instance ID:        %s
            Communication Mode: %s
            Services in cluster: %d
            Known clusters:     %d (%s)
            """, clusterId,
                clusterService.getServiceInstanceId().substring(0, 8) + "...",
                mode, localServices.size(), allClusters.size(),
                String.join(", ", allClusters));
    }

    private String clusterList() {
        List<ClusterNode> nodes = clusterNodeRepository.findByActiveTrue();
        Map<String, Long> counts = clusterService.getClusterServiceCounts();
        if (nodes.isEmpty()) return "No clusters registered.";

        StringBuilder sb = new StringBuilder("Cluster ID | Name | Mode | Region | Services\n");
        sb.append("-".repeat(70)).append("\n");
        for (ClusterNode node : nodes) {
            String marker = node.getClusterId().equals(clusterService.getClusterId()) ? " ◄" : "";
            sb.append(String.format("%-16s | %-18s | %-14s | %-10s | %d%s\n",
                    node.getClusterId(),
                    node.getDisplayName() != null ? node.getDisplayName() : "—",
                    node.getCommunicationMode(),
                    node.getRegion() != null ? node.getRegion() : "—",
                    counts.getOrDefault(node.getClusterId(), 0L),
                    marker));
        }
        return sb.toString();
    }

    private String clusterServices(String clusterId) {
        List<ServiceRegistration> services = clusterService.getServicesInCluster(clusterId);
        if (services.isEmpty()) return "No active services in cluster: " + clusterId;

        StringBuilder sb = new StringBuilder("Services in cluster '" + clusterId + "':\n");
        sb.append("Type | Host | Port | Instance\n");
        sb.append("-".repeat(60)).append("\n");
        for (ServiceRegistration s : services) {
            sb.append(String.format("%-12s | %-20s | %5d | %s\n",
                    s.getServiceType(), s.getHost(), s.getControlPort(),
                    s.getServiceInstanceId().substring(0, 8) + "..."));
        }
        return sb.toString();
    }

    private String clusterSetMode(String modeArg) {
        ClusterCommunicationMode mode;
        try {
            mode = switch (modeArg.toLowerCase()) {
                case "within", "within_cluster", "local" -> ClusterCommunicationMode.WITHIN_CLUSTER;
                case "cross", "cross_cluster", "federated" -> ClusterCommunicationMode.CROSS_CLUSTER;
                default -> ClusterCommunicationMode.valueOf(modeArg.toUpperCase());
            };
        } catch (IllegalArgumentException e) {
            return "Invalid mode: " + modeArg + ". Use: within | cross";
        }

        clusterService.setCommunicationMode(mode);

        // Persist to cluster_nodes table
        clusterNodeRepository.findByClusterId(clusterService.getClusterId())
                .ifPresent(node -> {
                    node.setCommunicationMode(mode);
                    clusterNodeRepository.save(node);
                });

        return "Communication mode set to: " + mode
                + "\nCluster: " + clusterService.getClusterId()
                + "\n\nNote: This affects routing on this instance immediately."
                + "\nOther instances will pick up the change on next heartbeat cycle.";
    }

    private String logsCommand(String[] parts) {
        if (parts.length >= 2 && parts[1].equals("recent")) {
            int n = parts.length >= 3 ? Integer.parseInt(parts[2]) : 20;
            n = Math.max(1, Math.min(n, MAX_SEARCH_RESULTS));
            List<AuditLog> logs = auditLogRepository.findAll(
                    PageRequest.of(0, n, Sort.by(Sort.Direction.DESC, "timestamp"))).getContent();
            if (logs.isEmpty()) return "No audit logs found.";
            StringBuilder sb = new StringBuilder();
            for (AuditLog l : logs) {
                sb.append(String.format("[%s] %s — %s\n",
                        l.getTimestamp() != null ? l.getTimestamp().toString().substring(11, 19) : "?",
                        l.getAccount() != null ? l.getAccount().getUsername() : "system",
                        l.getAction()));
            }
            return sb.toString();
        }
        return "Usage: logs recent [N]";
    }

    private String onboardCommand(String[] parts) {
        if (parts.length < 3) return "Usage: onboard <email> <password>";
        String email = parts[1];
        String password = parts[2];
        // Validate email format to prevent injection via crafted input
        if (!email.matches("^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$")) {
            return "Invalid email format: " + email.replaceAll("[^a-zA-Z0-9@._\\-]", "");
        }
        if (password.length() < 8) return "Password must be at least 8 characters";
        if (userRepository.findByEmail(email).isPresent()) return "User already exists: " + email;

        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        User user = User.builder()
                .email(email).passwordHash(encoder.encode(password))
                .role(com.filetransfer.shared.enums.UserRole.USER)
                .build();
        userRepository.save(user);

        String username = email.split("@")[0].replaceAll("[^a-zA-Z0-9]", "_");
        TransferAccount account = TransferAccount.builder()
                .user(user).protocol(Protocol.SFTP)
                .username(username).passwordHash(encoder.encode(password))
                .homeDir("/data/sftp/" + username)
                .build();
        accountRepository.save(account);

        return "Created user: " + email + "\nCreated SFTP account: " + username + "\nHome: /data/sftp/" + username;
    }

    private ResponseEntity<Map<String, Object>> ok(String output) {
        return ResponseEntity.ok(Map.of("output", output, "timestamp", Instant.now().toString()));
    }
}
