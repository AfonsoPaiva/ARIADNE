package io.ariadne.integration;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

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
                "-Dariadne.exception.context.enabled=true",
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
        String version = "0.1.0-alpha.2";
        String userHome = System.getProperty("user.home", "");
        String localM2Jar = userHome + "/.m2/repository/io/ariadne/ariadne-agent/" + version + "/ariadne-agent-" + version + ".jar";

        // Check candidate locations relative to current working directory or submodule
        String[] candidatePaths = new String[] {
                "ariadne-agent/target/ariadne-agent-" + version + ".jar",
                "../ariadne-agent/target/ariadne-agent-" + version + ".jar",
                "target/ariadne-agent-" + version + ".jar",
                localM2Jar
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

        // Fallback: when running 'mvn test' without 'package', assemble a temporary agent jar
        try {
            File fallback = createFallbackAgentJar();
            if (isValidAgentJar(fallback)) {
                return fallback;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to assemble fallback agent JAR", e);
        }

        throw new IllegalStateException("Could not locate ariadne-agent shaded JAR with Premain-Class in target directories or local repository");
    }

    private static File createFallbackAgentJar() throws IOException {
        File tempJar = Files.createTempFile("ariadne-agent-fallback-", ".jar").toFile();
        tempJar.deleteOnExit();

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(new Attributes.Name("Premain-Class"), "io.ariadne.agent.AriadneAgent");
        manifest.getMainAttributes().put(new Attributes.Name("Agent-Class"), "io.ariadne.agent.AriadneAgent");
        manifest.getMainAttributes().put(new Attributes.Name("Can-Redefine-Classes"), "true");
        manifest.getMainAttributes().put(new Attributes.Name("Can-Retransform-Classes"), "true");

        String[] classDirCandidates = new String[] {
                "ariadne-agent/target/classes",
                "../ariadne-agent/target/classes",
                "ariadne-core/target/classes",
                "../ariadne-core/target/classes",
                "ariadne-adapter-mdc/target/classes",
                "../ariadne-adapter-mdc/target/classes"
        };

        Set<String> addedEntries = new HashSet<>();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(tempJar), manifest)) {
            for (String dirPath : classDirCandidates) {
                File dir = new File(dirPath);
                if (dir.isDirectory()) {
                    addDirectoryToJar(jos, dir, dir, addedEntries);
                }
            }
        }
        return tempJar;
    }

    private static void addDirectoryToJar(JarOutputStream jos, File root, File current, Set<String> addedEntries) throws IOException {
        File[] files = current.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                addDirectoryToJar(jos, root, file, addedEntries);
            } else if (file.isFile()) {
                String relativePath = root.toPath().relativize(file.toPath()).toString().replace('\\', '/');
                if (addedEntries.add(relativePath)) {
                    JarEntry entry = new JarEntry(relativePath);
                    jos.putNextEntry(entry);
                    Files.copy(file.toPath(), jos);
                    jos.closeEntry();
                }
            }
        }
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
