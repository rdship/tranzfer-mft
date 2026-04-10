package com.filetransfer.shared.routing;

import com.filetransfer.shared.entity.FlowEvent;
import com.filetransfer.shared.flow.*;
import com.filetransfer.shared.repository.FlowEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration-style tests for the COMPLETE execution pipeline:
 * FlowEventJournal recording, FlowActor replay, FlowFunctionRegistry catalog,
 * and FunctionImportExportService import.
 *
 * Pure JUnit 5 + Mockito, no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class FlowExecutionEndToEndTest {

    @Mock
    private FlowEventRepository eventRepo;

    @Captor
    private ArgumentCaptor<FlowEvent> eventCaptor;

    private FlowEventJournal journal;

    private static final String TRACK_ID = "TRK-ABC12345";
    private static final UUID EXECUTION_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @BeforeEach
    void setUp() {
        journal = new FlowEventJournal(eventRepo);
    }

    // ---- 1. Complete 2-step flow journal ----

    @Test
    void executeFlow_compressAndEncrypt_shouldRecordAllJournalEvents() {
        // Simulate: started -> step0 COMPRESS_GZIP started -> step0 completed ->
        //           step1 ENCRYPT_PGP started -> step1 completed -> execution completed
        journal.recordExecutionStarted(TRACK_ID, EXECUTION_ID, "sha256:input-key", 2);
        journal.recordStepStarted(TRACK_ID, EXECUTION_ID, 0, "COMPRESS_GZIP", "sha256:input-key", 1);
        journal.recordStepCompleted(TRACK_ID, EXECUTION_ID, 0, "COMPRESS_GZIP", "sha256:compressed", 2048L, 150L);
        journal.recordStepStarted(TRACK_ID, EXECUTION_ID, 1, "ENCRYPT_PGP", "sha256:compressed", 1);
        journal.recordStepCompleted(TRACK_ID, EXECUTION_ID, 1, "ENCRYPT_PGP", "sha256:encrypted", 2200L, 320L);
        journal.recordExecutionCompleted(TRACK_ID, EXECUTION_ID, 470L, 2);

        verify(eventRepo, times(6)).save(eventCaptor.capture());
        List<FlowEvent> events = eventCaptor.getAllValues();

        assertEquals("EXECUTION_STARTED", events.get(0).getEventType());
        assertEquals("STEP_STARTED", events.get(1).getEventType());
        assertEquals("COMPRESS_GZIP", events.get(1).getStepType());
        assertEquals(0, events.get(1).getStepIndex());
        assertEquals("STEP_COMPLETED", events.get(2).getEventType());
        assertEquals("sha256:compressed", events.get(2).getStorageKey());
        assertEquals("STEP_STARTED", events.get(3).getEventType());
        assertEquals("ENCRYPT_PGP", events.get(3).getStepType());
        assertEquals(1, events.get(3).getStepIndex());
        assertEquals("STEP_COMPLETED", events.get(4).getEventType());
        assertEquals("sha256:encrypted", events.get(4).getStorageKey());
        assertEquals("EXECUTION_COMPLETED", events.get(5).getEventType());
        assertEquals("COMPLETED", events.get(5).getStatus());
    }

    // ---- 2. Step failure recording ----

    @Test
    void executeFlow_stepFails_shouldRecordFailureEvent() {
        // Simulate: started -> step0 ok -> step1 FAILS -> execution failed
        journal.recordExecutionStarted(TRACK_ID, EXECUTION_ID, "sha256:input", 2);
        journal.recordStepStarted(TRACK_ID, EXECUTION_ID, 0, "COMPRESS_GZIP", "sha256:input", 1);
        journal.recordStepCompleted(TRACK_ID, EXECUTION_ID, 0, "COMPRESS_GZIP", "sha256:compressed", 1024L, 100L);
        journal.recordStepStarted(TRACK_ID, EXECUTION_ID, 1, "ENCRYPT_PGP", "sha256:compressed", 1);
        journal.recordStepFailed(TRACK_ID, EXECUTION_ID, 1, "ENCRYPT_PGP", "PGP key not found", 1);
        journal.recordExecutionFailed(TRACK_ID, EXECUTION_ID, "Step 1 ENCRYPT_PGP failed: PGP key not found");

        verify(eventRepo, times(6)).save(eventCaptor.capture());
        List<FlowEvent> events = eventCaptor.getAllValues();

        assertEquals("STEP_FAILED", events.get(4).getEventType());
        assertEquals("ENCRYPT_PGP", events.get(4).getStepType());
        assertEquals(1, events.get(4).getStepIndex());
        assertEquals("FAILED", events.get(4).getStatus());
        assertTrue(events.get(4).getErrorMessage().contains("PGP key not found"));

        assertEquals("EXECUTION_FAILED", events.get(5).getEventType());
        assertEquals("FAILED", events.get(5).getStatus());
    }

    // ---- 3. Approve gate: pause and resume ----

    @Test
    void executeFlow_approveGate_shouldPauseAndResume() {
        // Simulate: started -> step0 ok -> APPROVE gate pauses ->
        //           approval received -> resumed -> step2 ok -> completed
        journal.recordExecutionStarted(TRACK_ID, EXECUTION_ID, "sha256:input", 3);
        journal.recordStepStarted(TRACK_ID, EXECUTION_ID, 0, "COMPRESS_GZIP", "sha256:input", 1);
        journal.recordStepCompleted(TRACK_ID, EXECUTION_ID, 0, "COMPRESS_GZIP", "sha256:compressed", 2048L, 100L);
        journal.recordExecutionPaused(TRACK_ID, EXECUTION_ID, 1, "APPROVE gate requires manual approval");
        journal.recordApprovalReceived(TRACK_ID, EXECUTION_ID, 1, "admin@company.com", "APPROVED");
        journal.recordExecutionResumed(TRACK_ID, EXECUTION_ID, 2, "admin@company.com");
        journal.recordStepStarted(TRACK_ID, EXECUTION_ID, 2, "FILE_DELIVERY", "sha256:compressed", 1);
        journal.recordStepCompleted(TRACK_ID, EXECUTION_ID, 2, "FILE_DELIVERY", "sha256:delivered", 2048L, 500L);
        journal.recordExecutionCompleted(TRACK_ID, EXECUTION_ID, 1100L, 3);

        verify(eventRepo, times(9)).save(eventCaptor.capture());
        List<FlowEvent> events = eventCaptor.getAllValues();

        assertEquals("EXECUTION_PAUSED", events.get(3).getEventType());
        assertEquals("PAUSED", events.get(3).getStatus());
        assertEquals("APPROVAL_RECEIVED", events.get(4).getEventType());
        assertEquals("admin@company.com", events.get(4).getActor());
        assertEquals("APPROVED", events.get(4).getStatus());
        assertEquals("EXECUTION_RESUMED", events.get(5).getEventType());
        assertEquals("EXECUTION_COMPLETED", events.get(8).getEventType());
    }

    // ---- 4. IOMode classification for all built-ins ----

    @Test
    void flowFunction_ioModeClassification_shouldBeCorrectForAllBuiltins() {
        FlowFunctionRegistrar registrar = new FlowFunctionRegistrar();
        FlowFunctionRegistry registry = registrar.flowFunctionRegistry();

        // STREAMING functions
        for (String type : List.of("COMPRESS_GZIP", "DECOMPRESS_GZIP", "COMPRESS_ZIP", "DECOMPRESS_ZIP")) {
            FlowFunction fn = registry.get(type).orElseThrow(
                    () -> new AssertionError("Missing function: " + type));
            assertEquals(IOMode.STREAMING, fn.ioMode(), type + " should be STREAMING");
        }

        // MATERIALIZING functions
        for (String type : List.of("ENCRYPT_PGP", "DECRYPT_PGP", "ENCRYPT_AES", "DECRYPT_AES",
                "SCREEN", "EXECUTE_SCRIPT", "FILE_DELIVERY", "CONVERT_EDI")) {
            FlowFunction fn = registry.get(type).orElseThrow(
                    () -> new AssertionError("Missing function: " + type));
            assertEquals(IOMode.MATERIALIZING, fn.ioMode(), type + " should be MATERIALIZING");
        }

        // METADATA_ONLY functions
        for (String type : List.of("RENAME", "ROUTE", "APPROVE", "MAILBOX")) {
            FlowFunction fn = registry.get(type).orElseThrow(
                    () -> new AssertionError("Missing function: " + type));
            assertEquals(IOMode.METADATA_ONLY, fn.ioMode(), type + " should be METADATA_ONLY");
        }
    }

    // ---- 5. Function catalog completeness ----

    @Test
    void flowFunction_catalog_shouldReturnAll16BuiltIns() {
        FlowFunctionRegistrar registrar = new FlowFunctionRegistrar();
        FlowFunctionRegistry registry = registrar.flowFunctionRegistry();

        assertEquals(17, registry.size(), "Should have exactly 17 built-in functions (16 stubs + CHECKSUM_VERIFY)");

        List<String> expectedTypes = List.of(
                "COMPRESS_GZIP", "DECOMPRESS_GZIP", "COMPRESS_ZIP", "DECOMPRESS_ZIP",
                "ENCRYPT_PGP", "DECRYPT_PGP", "ENCRYPT_AES", "DECRYPT_AES",
                "RENAME", "SCREEN", "EXECUTE_SCRIPT", "MAILBOX",
                "FILE_DELIVERY", "CONVERT_EDI", "ROUTE", "APPROVE");

        Map<String, FlowFunction> all = registry.getAll();
        for (String type : expectedTypes) {
            assertTrue(all.containsKey(type), "Missing built-in function: " + type);
        }
    }

    // ---- 6. gRPC function import ----

    @Test
    void functionImport_grpc_shouldRegisterNewFunction() {
        FlowFunctionRegistry registry = new FlowFunctionRegistry();
        NoOpWasmRuntime wasmRuntime = new NoOpWasmRuntime();
        FunctionImportExportService importService = new FunctionImportExportService(registry, wasmRuntime);

        FunctionDescriptor descriptor = new FunctionDescriptor(
                "custom-transform", "1.0.0", "TRANSFORM", "TENANT", "partner-dev", true,
                "Custom gRPC transform function");
        FunctionPackage pkg = FunctionPackage.grpc(descriptor, "http://partner-fn:50051/transform", null);

        importService.importFunction(pkg);

        assertTrue(registry.get("CUSTOM_TRANSFORM").isPresent(), "gRPC function should be registered");
        FlowFunction fn = registry.get("CUSTOM_TRANSFORM").get();
        assertEquals("CUSTOM_TRANSFORM", fn.type());
        assertEquals(IOMode.MATERIALIZING, fn.ioMode());
        assertTrue(fn instanceof GrpcFlowFunction);
    }

    // ---- 7. WASM function import ----

    @Test
    void functionImport_wasm_shouldRegisterNewFunction() {
        FlowFunctionRegistry registry = new FlowFunctionRegistry();
        NoOpWasmRuntime wasmRuntime = new NoOpWasmRuntime();
        FunctionImportExportService importService = new FunctionImportExportService(registry, wasmRuntime);

        int initialSize = registry.size();

        FunctionDescriptor descriptor = new FunctionDescriptor(
                "wasm-filter", "2.0.0", "TRANSFORM", "PARTNER", "partner-dev", true,
                "WASM data filter");
        byte[] fakeWasmModule = new byte[]{0x00, 0x61, 0x73, 0x6D}; // fake WASM magic bytes
        FunctionPackage pkg = FunctionPackage.wasm(descriptor, fakeWasmModule, null);

        importService.importFunction(pkg);

        assertEquals(initialSize + 1, registry.size(), "Registry size should increase by 1");
        assertTrue(registry.get("WASM_FILTER").isPresent(), "WASM function should be registered");
        FlowFunction fn = registry.get("WASM_FILTER").get();
        assertEquals(IOMode.STREAMING, fn.ioMode(), "WASM functions use STREAMING mode");
        assertTrue(fn instanceof WasmFlowFunction);
    }

    // ---- 8. Actor replay ----

    @Test
    void actorReplay_afterMultiStepFlow_shouldReconstructCorrectState() {
        // Build a realistic 10-event sequence covering a 3-step flow with one retry
        List<FlowEvent> events = new ArrayList<>();
        UUID execId = EXECUTION_ID;

        // Event 0: Execution started
        events.add(FlowEvent.builder()
                .trackId(TRACK_ID).executionId(execId)
                .eventType("EXECUTION_STARTED")
                .storageKey("sha256:original-input")
                .createdAt(Instant.now()).build());

        // Event 1: Step 0 started
        events.add(FlowEvent.builder()
                .trackId(TRACK_ID).executionId(execId)
                .eventType("STEP_STARTED")
                .stepIndex(0).stepType("COMPRESS_GZIP")
                .storageKey("sha256:original-input").attemptNumber(1)
                .createdAt(Instant.now()).build());

        // Event 2: Step 0 completed
        events.add(FlowEvent.builder()
                .trackId(TRACK_ID).executionId(execId)
                .eventType("STEP_COMPLETED")
                .stepIndex(0).stepType("COMPRESS_GZIP")
                .storageKey("sha256:compressed-output")
                .createdAt(Instant.now()).build());

        // Event 3: Step 1 started (attempt 1)
        events.add(FlowEvent.builder()
                .trackId(TRACK_ID).executionId(execId)
                .eventType("STEP_STARTED")
                .stepIndex(1).stepType("ENCRYPT_PGP")
                .storageKey("sha256:compressed-output").attemptNumber(1)
                .createdAt(Instant.now()).build());

        // Event 4: Step 1 failed (transient error)
        events.add(FlowEvent.builder()
                .trackId(TRACK_ID).executionId(execId)
                .eventType("STEP_FAILED")
                .stepIndex(1).stepType("ENCRYPT_PGP")
                .errorMessage("Connection timeout").attemptNumber(1)
                .status("FAILED")
                .createdAt(Instant.now()).build());

        // Event 5: Step 1 retrying (attempt 2)
        events.add(FlowEvent.builder()
                .trackId(TRACK_ID).executionId(execId)
                .eventType("STEP_RETRYING")
                .stepIndex(1).stepType("ENCRYPT_PGP")
                .attemptNumber(2)
                .createdAt(Instant.now()).build());

        // Event 6: Step 1 started (attempt 2)
        events.add(FlowEvent.builder()
                .trackId(TRACK_ID).executionId(execId)
                .eventType("STEP_STARTED")
                .stepIndex(1).stepType("ENCRYPT_PGP")
                .storageKey("sha256:compressed-output").attemptNumber(2)
                .createdAt(Instant.now()).build());

        // Event 7: Step 1 completed (attempt 2 succeeds)
        events.add(FlowEvent.builder()
                .trackId(TRACK_ID).executionId(execId)
                .eventType("STEP_COMPLETED")
                .stepIndex(1).stepType("ENCRYPT_PGP")
                .storageKey("sha256:encrypted-output")
                .createdAt(Instant.now()).build());

        // Event 8: Step 2 started
        events.add(FlowEvent.builder()
                .trackId(TRACK_ID).executionId(execId)
                .eventType("STEP_STARTED")
                .stepIndex(2).stepType("FILE_DELIVERY")
                .storageKey("sha256:encrypted-output").attemptNumber(1)
                .createdAt(Instant.now()).build());

        // Event 9: Step 2 completed
        events.add(FlowEvent.builder()
                .trackId(TRACK_ID).executionId(execId)
                .eventType("STEP_COMPLETED")
                .stepIndex(2).stepType("FILE_DELIVERY")
                .storageKey("sha256:final-delivered")
                .createdAt(Instant.now()).build());

        // Event 10: Execution completed
        events.add(FlowEvent.builder()
                .trackId(TRACK_ID).executionId(execId)
                .eventType("EXECUTION_COMPLETED")
                .status("COMPLETED")
                .createdAt(Instant.now()).build());

        // Replay
        FlowActor actor = new FlowActor(TRACK_ID);
        actor.replayFromJournal(events);

        assertEquals(3, actor.getCurrentStep(), "Should be at step 3 (past last step index 2)");
        assertEquals("COMPLETED", actor.getStatus(), "Should be COMPLETED");
        assertEquals("sha256:final-delivered", actor.getCurrentStorageKey(),
                "Storage key should be output of last step");
        assertTrue(actor.isTerminal(), "Actor should be in terminal state");
        assertFalse(actor.isResumable(), "Completed actor should not be resumable");
    }

    // ---- reflection helper (for @Value fields if needed) ----

    @SuppressWarnings("unused")
    private static void setField(Object obj, String name, Object value) throws Exception {
        var field = obj.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(obj, value);
    }
}
