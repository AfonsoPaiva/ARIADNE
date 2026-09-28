package io.ariadne.agent;

import io.ariadne.core.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Injects Ariadne runtime classes into the JVM's Bootstrap ClassLoader search path,
 * enabling bootstrap-loaded classes (such as CompletableFuture and ThreadPoolExecutor)
 * to link against Ariadne core data structures without ClassNotFoundException.
 */
public final class BootstrapInjector {

    private static volatile boolean INJECTED = false;

    private BootstrapInjector() {}

    public static synchronized void inject(Instrumentation inst) {
        if (INJECTED) {
            return;
        }

        // Check if already on bootstrap path (e.g. when run with -javaagent jar)
        try {
            Class.forName("io.ariadne.core.Link", false, null);
            INJECTED = true;
            return;
        } catch (ClassNotFoundException ignored) {
            // Needs dynamic bootstrap injection (e.g. during test runs or unpacked agent execution)
        }

        try {
            File tempJar = Files.createTempFile("ariadne-bootstrap-", ".jar").toFile();
            tempJar.deleteOnExit();

            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");

            Class<?>[] classesToInject = new Class<?>[] {
                    Link.class,
                    CallSiteMetadata.class,
                    SiteRegistry.class,
                    ContextCarrier.class,
                    ThreadLocalContextCarrier.class,
                    AriadneContext.class,
                    AsyncCausalityException.class,
                    AriadneReconstructor.class,
                    AriadneRunnable.class,
                    AriadneCallable.class,
                    AriadneSupplier.class,
                    AriadneConsumer.class,
                    AriadneFunction.class,
                    AriadneBiConsumer.class,
                    AriadneBiFunction.class,
                    CanaryProbeResult.class,
                    UnsupportedFrameworkVersionException.class,
                    CompletableFutureAdvice.class,
                    ExecutorAdvice.class
            };

            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(tempJar), manifest)) {
                for (Class<?> clazz : classesToInject) {
                    addClassAndInners(jos, clazz);
                }
            }

            inst.appendToBootstrapClassLoaderSearch(new JarFile(tempJar));
            INJECTED = true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject Ariadne classes into Bootstrap ClassLoader", e);
        }
    }

    private static void addClassAndInners(JarOutputStream jos, Class<?> clazz) throws IOException {
        addClass(jos, clazz);
        for (Class<?> inner : clazz.getDeclaredClasses()) {
            addClassAndInners(jos, inner);
        }
    }

    private static void addClass(JarOutputStream jos, Class<?> clazz) throws IOException {
        String resourceName = clazz.getName().replace('.', '/') + ".class";
        ClassLoader cl = clazz.getClassLoader();
        try (InputStream is = (cl != null) ? cl.getResourceAsStream(resourceName) : ClassLoader.getSystemResourceAsStream(resourceName)) {
            if (is != null) {
                jos.putNextEntry(new JarEntry(resourceName));
                is.transferTo(jos);
                jos.closeEntry();
            }
        }
    }
}
