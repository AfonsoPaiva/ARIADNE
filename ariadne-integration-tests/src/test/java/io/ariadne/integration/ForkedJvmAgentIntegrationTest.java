package io.ariadne.integration;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration test executing a real forked JVM process with
 * {@code -javaagent:/path/to/ariadne-agent.jar} attached at startup.
 * <p>
 * This strictly verifies production behavior without any test mocking, dynamic ByteBuddyAgent
 * attachment, or manual BootstrapClassLoader manipulation.
 */
class ForkedJvmAgentIntegrationTest {

    @Test
    void shouldPropagateCausalityInForkedJvmWithRealJavaAgent() throws Exception {
        File agentJar = resolveAgentJar();
        assertThat(agentJar)
                .as("Ariadne Agent JAR must exist at %s", agentJar.getAbsolutePath())
                .exists()
                .canRead();

        String javaBin = ProcessHandle.current().info().command().orElse(
                Paths.get(System.getProperty("java.home"), "bin", "java").toString()
        );
        String classpath = System.getProperty("java.class.path");

        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-javaagent:" + agentJar.getAbsolutePath(),
                "-cp", classpath,
                "io.ariadne.integration.ForkedAgentSampleApp"
        );
        pb.redirectErrorStream(true);

        Process process = pb.start();

        List<String> outputLines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                outputLines.add(line);
            }
        }

        int exitCode = process.waitFor();
        String fullOutput = String.join("\n", outputLines);

        assertThat(exitCode)
                .as("Forked JVM process must terminate cleanly (exit code 0). Process output was:\n%s", fullOutput)
                .isEqualTo(0);

        assertThat(fullOutput)
                .as("Forked process must catch simulated error")
                .contains("ROOT_CAUSE=java.lang.IllegalStateException: Simulated crash in forked JVM async task: hop-1-data");

        assertThat(fullOutput)
                .as("Ariadne Agent must enrich exception with AsyncCausalityException across asynchronous boundaries")
                .contains("CAUSAL_EXCEPTION_FOUND=Asynchronous execution path (2 hops)")
                .contains("traceId=forked-trace-999")
                .contains("tenant=acme-corp");

        assertThat(fullOutput)
                .as("Synthesized stack trace must contain caller class ForkedAgentSampleApp")
                .contains("ForkedAgentSampleApp");

        assertThat(fullOutput)
                .as("Virtual thread execution must propagate causal context automatically without manual wrapping")
                .contains("DIRECT_VTHREAD_CAUSAL_EXCEPTION=Asynchronous execution path (1 hop)")
                .contains("VTHREAD_CAUSAL_FRAME=");
    }

    private static File resolveAgentJar() {
        // Check candidate locations relative to current working directory or submodule
        String[] candidatePaths = new String[] {
                "ariadne-agent/target/ariadne-agent-0.1.0-alpha.2.jar",
                "../ariadne-agent/target/ariadne-agent-0.1.0-alpha.2.jar",
                "target/ariadne-agent-0.1.0-alpha.2.jar"
        };

        for (String candidate : candidatePaths) {
            File f = new File(candidate);
            if (isValidAgentJar(f)) {
                return f.getAbsoluteFile();
            }
        }

        // Search directory if exact name varies
        String[] searchDirs = new String[] { "ariadne-agent/target", "../ariadne-agent/target" };
        for (String dirPath : searchDirs) {
            File dir = new File(dirPath);
            if (dir.isDirectory()) {
                File[] jars = dir.listFiles((d, name) -> name.startsWith("ariadne-agent-") && name.endsWith(".jar") && !name.startsWith("original-"));
                if (jars != null) {
                    for (File jar : jars) {
                        if (isValidAgentJar(jar)) {
                            return jar.getAbsoluteFile();
                        }
                    }
                }
            }
        }

        throw new IllegalStateException("Could not locate ariadne-agent shaded JAR with Premain-Class in target directories");
    }

    private static boolean isValidAgentJar(File file) {
        if (!file.isFile() || !file.canRead()) {
            return false;
        }
        try (JarFile jar = new JarFile(file)) {
            var manifest = jar.getManifest();
            if (manifest != null) {
                String premain = manifest.getMainAttributes().getValue("Premain-Class");
                return "io.ariadne.agent.AriadneAgent".equals(premain);
            }
        } catch (Exception ignored) {}
        return false;
    }
}
