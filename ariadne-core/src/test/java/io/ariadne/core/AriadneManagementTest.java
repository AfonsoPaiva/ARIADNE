package io.ariadne.core;

import java.lang.management.ManagementFactory;

import javax.management.Attribute;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AriadneManagementTest {

    private MBeanServer mBeanServer;
    private ObjectName objectName;

    @BeforeEach
    void setUp() throws Exception {
        AriadneConfig.resetDefaults();
        AriadneMetrics.reset();
        AriadneManagement.registerMBean();
        mBeanServer = ManagementFactory.getPlatformMBeanServer();
        objectName = new ObjectName(AriadneManagement.MBEAN_OBJECT_NAME);
    }

    @AfterEach
    void tearDown() {
        AriadneManagement.unregisterMBean();
        AriadneConfig.resetDefaults();
        AriadneMetrics.reset();
    }

    @Test
    void shouldRegisterMBeanOnPlatformServer() {
        assertThat(AriadneManagement.isRegistered()).isTrue();
        assertThat(mBeanServer.isRegistered(objectName)).isTrue();
    }

    @Test
    void shouldReadAndWriteAttributesViaJmx() throws Exception {
        // Read initial maxDepth
        int maxDepth = (int) mBeanServer.getAttribute(objectName, "MaxDepth");
        assertThat(maxDepth).isEqualTo(32);

        // Dynamically update maxDepth via JMX
        mBeanServer.setAttribute(objectName, new Attribute("MaxDepth", 64));
        assertThat(AriadneConfig.getMaxDepth()).isEqualTo(64);
        assertThat(mBeanServer.getAttribute(objectName, "MaxDepth")).isEqualTo(64);

        // Toggle failFast via JMX
        mBeanServer.setAttribute(objectName, new Attribute("FailFast", true));
        assertThat(AriadneConfig.isFailFast()).isTrue();

        // Toggle canaryProbesEnabled via JMX
        mBeanServer.setAttribute(objectName, new Attribute("CanaryProbesEnabled", false));
        assertThat(AriadneConfig.isCanaryProbesEnabled()).isFalse();

        // Toggle mdcPropagationEnabled via JMX
        mBeanServer.setAttribute(objectName, new Attribute("MdcPropagationEnabled", false));
        assertThat(AriadneConfig.isMdcPropagationEnabled()).isFalse();
        assertThat(mBeanServer.getAttribute(objectName, "MdcPropagationEnabled")).isEqualTo(false);
    }

    @Test
    void shouldReadTelemetryMetricsViaJmx() throws Exception {
        AriadneMetrics.recordHop();
        AriadneMetrics.recordHop();
        AriadneMetrics.recordReconstruction();

        long hops = (long) mBeanServer.getAttribute(objectName, "HopsSpawned");
        long reconstructions = (long) mBeanServer.getAttribute(objectName, "ReconstructionsTotal");
        String version = (String) mBeanServer.getAttribute(objectName, "Version");

        assertThat(hops).isEqualTo(2);
        assertThat(reconstructions).isEqualTo(1);
        assertThat(version).isEqualTo("0.1.0-beta.1");
    }

    @Test
    void shouldInvokeJmxOperations() throws Exception {
        AriadneMetrics.recordHop();
        assertThat(AriadneMetrics.getHopsSpawned()).isEqualTo(1);

        // Invoke resetMetrics()
        mBeanServer.invoke(objectName, "resetMetrics", new Object[0], new String[0]);
        assertThat(AriadneMetrics.getHopsSpawned()).isEqualTo(0);

        // Invoke dumpHealthReport()
        String report = (String) mBeanServer.invoke(objectName, "dumpHealthReport", new Object[0], new String[0]);
        assertThat(report).contains("=== Ariadne Health Report ===");
        assertThat(report).contains("Max Depth: 32");
    }

    @Test
    void shouldGracefullyUnregister() {
        AriadneManagement.unregisterMBean();
        assertThat(AriadneManagement.isRegistered()).isFalse();
        assertThat(mBeanServer.isRegistered(objectName)).isFalse();
    }
}
