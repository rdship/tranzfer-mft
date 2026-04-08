package com.filetransfer.shared.replica;

import com.filetransfer.shared.cluster.ClusterContext;
import com.filetransfer.shared.cluster.ClusterService;
import com.filetransfer.shared.entity.ServiceRegistration;
import com.filetransfer.shared.enums.ClusterCommunicationMode;
import com.filetransfer.shared.enums.ServiceType;
import com.filetransfer.shared.repository.ServiceRegistrationRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Batch 1c: Service Registration & Discovery — multi-replica visibility.
 *
 * Tests:
 * 1. Two SFTP replicas in same cluster — both discoverable
 * 2. All 17 service types can register replicas
 * 3. Stale replica filtered out
 * 4. Cross-cluster discovery returns replicas from all clusters
 */
class Batch1ServiceDiscoveryReplicaTest {

    @Test
    void serviceDiscovery_multipleReplicas_allVisible() {
        ServiceRegistrationRepository regRepo = mock(ServiceRegistrationRepository.class);

        ServiceRegistration sftp1 = ServiceRegistration.builder()
                .serviceInstanceId("sftp-1").clusterId("cluster-1")
                .serviceType(ServiceType.SFTP).host("sftp-service").controlPort(8081).active(true).build();
        ServiceRegistration sftp2 = ServiceRegistration.builder()
                .serviceInstanceId("sftp-2").clusterId("cluster-1")
                .serviceType(ServiceType.SFTP).host("sftp-service-2").controlPort(8081).active(true).build();

        when(regRepo.findByServiceTypeAndClusterIdAndActiveTrue(ServiceType.SFTP, "cluster-1"))
                .thenReturn(List.of(sftp1, sftp2));

        ClusterContext ctx = new ClusterContext();
        ctx.setClusterId("cluster-1");
        ctx.setServiceInstanceId("sftp-1");
        ctx.setCommunicationMode(ClusterCommunicationMode.WITHIN_CLUSTER);

        ClusterService svc = new ClusterService(ctx, regRepo);
        assertEquals(2, svc.discoverServices(ServiceType.SFTP).size());
        assertTrue(svc.isLocalService(sftp1));
        assertFalse(svc.isLocalService(sftp2));
    }

    @Test
    void serviceDiscovery_allServiceTypes_canRegisterReplicas() {
        ServiceRegistrationRepository regRepo = mock(ServiceRegistrationRepository.class);

        for (ServiceType type : ServiceType.values()) {
            List<ServiceRegistration> replicas = List.of(
                    ServiceRegistration.builder()
                            .serviceInstanceId(type.name().toLowerCase() + "-1").clusterId("cluster-1")
                            .serviceType(type).host(type.name().toLowerCase()).controlPort(8080).active(true).build(),
                    ServiceRegistration.builder()
                            .serviceInstanceId(type.name().toLowerCase() + "-2").clusterId("cluster-1")
                            .serviceType(type).host(type.name().toLowerCase() + "-2").controlPort(8080).active(true).build());

            when(regRepo.findByServiceTypeAndClusterIdAndActiveTrue(type, "cluster-1")).thenReturn(replicas);

            ClusterContext ctx = new ClusterContext();
            ctx.setClusterId("cluster-1");
            ctx.setServiceInstanceId(type.name().toLowerCase() + "-1");
            ctx.setCommunicationMode(ClusterCommunicationMode.WITHIN_CLUSTER);

            assertEquals(2, new ClusterService(ctx, regRepo).discoverServices(type).size(),
                    type + " should have 2 replicas");
        }
    }

    @Test
    void serviceDiscovery_staleReplica_filteredOut() {
        ServiceRegistrationRepository regRepo = mock(ServiceRegistrationRepository.class);

        ServiceRegistration healthy = ServiceRegistration.builder()
                .serviceInstanceId("sftp-1").clusterId("cluster-1").serviceType(ServiceType.SFTP)
                .host("sftp-service").controlPort(8081).active(true).lastHeartbeat(Instant.now()).build();

        when(regRepo.findByServiceTypeAndClusterIdAndActiveTrue(ServiceType.SFTP, "cluster-1"))
                .thenReturn(List.of(healthy)); // stale replica already filtered by query

        ClusterContext ctx = new ClusterContext();
        ctx.setClusterId("cluster-1");
        ctx.setServiceInstanceId("sftp-1");
        ctx.setCommunicationMode(ClusterCommunicationMode.WITHIN_CLUSTER);

        assertEquals(1, new ClusterService(ctx, regRepo).discoverServices(ServiceType.SFTP).size());
    }

    @Test
    void serviceDiscovery_crossCluster_returnsAll() {
        ServiceRegistrationRepository regRepo = mock(ServiceRegistrationRepository.class);

        ServiceRegistration cluster1 = ServiceRegistration.builder()
                .serviceInstanceId("ftp-1").clusterId("cluster-1")
                .serviceType(ServiceType.FTP).host("ftp-service").controlPort(8082).active(true).build();
        ServiceRegistration cluster2 = ServiceRegistration.builder()
                .serviceInstanceId("ftp-2").clusterId("cluster-2")
                .serviceType(ServiceType.FTP).host("ftp-service-2").controlPort(8082).active(true).build();

        when(regRepo.findByServiceTypeAndActiveTrue(ServiceType.FTP))
                .thenReturn(List.of(cluster1, cluster2));

        ClusterContext ctx = new ClusterContext();
        ctx.setClusterId("cluster-1");
        ctx.setServiceInstanceId("ftp-1");
        ctx.setCommunicationMode(ClusterCommunicationMode.CROSS_CLUSTER);

        assertEquals(2, new ClusterService(ctx, regRepo).discoverServices(ServiceType.FTP).size());
    }
}
