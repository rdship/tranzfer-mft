package com.filetransfer.ftp.server;

import com.filetransfer.shared.entity.core.ServerInstance;
import com.filetransfer.shared.enums.Protocol;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R87: per-listener FTP passive-port range override.
 *
 * <p>Exercises the private {@code resolvePassivePorts(ServerInstance)} helper
 * reflectively to prove the ordering:
 * <ol>
 *   <li>ServerInstance.ftpPassivePortFrom + ftpPassivePortTo win when both set.</li>
 *   <li>Fall back to the service-wide ftp.passive-ports default otherwise.</li>
 * </ol>
 *
 * <p>Isolated unit test — does not Spring-boot the whole context.
 */
class FtpServerBuilderR87Test {

    @Test
    void perListenerRangeWinsOverDefault() throws Exception {
        FtpServerBuilder builder = buildBare();
        setField(builder, "defaultPassivePorts", "30000-30009");

        ServerInstance si = ServerInstance.builder()
                .instanceId("ftp-1").protocol(Protocol.FTP).name("ftp-1")
                .internalHost("ftp-service").internalPort(21)
                .ftpPassivePortFrom(21000).ftpPassivePortTo(21010)
                .build();

        String ports = invokeResolve(builder, si);
        assertThat(ports).isEqualTo("21000-21010");
    }

    @Test
    void fallsBackToDefaultWhenUnset() throws Exception {
        FtpServerBuilder builder = buildBare();
        setField(builder, "defaultPassivePorts", "40000-40009");

        ServerInstance si = ServerInstance.builder()
                .instanceId("ftp-2").protocol(Protocol.FTP).name("ftp-2")
                .internalHost("ftp-service").internalPort(21)
                .build();

        String ports = invokeResolve(builder, si);
        assertThat(ports).isEqualTo("40000-40009");
    }

    @Test
    void fallsBackWhenOnlyLowerBoundSet() throws Exception {
        FtpServerBuilder builder = buildBare();
        setField(builder, "defaultPassivePorts", "50000-50009");

        // Validator rejects this at the API layer; builder defensively falls
        // back so a stale/partial row can't leak a malformed range into MINA.
        ServerInstance si = ServerInstance.builder()
                .instanceId("ftp-3").protocol(Protocol.FTP).name("ftp-3")
                .internalHost("ftp-service").internalPort(21)
                .ftpPassivePortFrom(21000).ftpPassivePortTo(null)
                .build();

        String ports = invokeResolve(builder, si);
        assertThat(ports).isEqualTo("50000-50009");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static FtpServerBuilder buildBare() {
        // Builder has @RequiredArgsConstructor — but for resolvePassivePorts we
        // only need the instance; pass nulls via reflection via
        // Unsafe-style newInstance through the no-arg constructor trick. Since
        // @RequiredArgsConstructor synthesizes a single constructor requiring
        // 6 collaborators, we instantiate the class via Objenesis-free reflect.
        try {
            var ctor = FtpServerBuilder.class.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            Object[] args = new Object[ctor.getParameterCount()];
            return (FtpServerBuilder) ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        var f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static String invokeResolve(FtpServerBuilder b, ServerInstance si) throws Exception {
        Method m = FtpServerBuilder.class.getDeclaredMethod("resolvePassivePorts", ServerInstance.class);
        m.setAccessible(true);
        return (String) m.invoke(b, si);
    }
}
