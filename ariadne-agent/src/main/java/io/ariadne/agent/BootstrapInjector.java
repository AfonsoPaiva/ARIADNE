package io.ariadne.agent;

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

import io.ariadne.core.AriadneBiConsumer;
import io.ariadne.core.AriadneBiFunction;
import io.ariadne.core.AriadneCallable;
import io.ariadne.core.AriadneConfig;
import io.ariadne.core.AriadneConsumer;
import io.ariadne.core.AriadneContext;
import io.ariadne.core.AriadneFunction;
import io.ariadne.core.AriadneMXBean;
import io.ariadne.core.AriadneManagement;
import io.ariadne.core.AriadneMetrics;
import io.ariadne.core.AriadneReconstructor;
import io.ariadne.core.AriadneRunnable;
import io.ariadne.core.AriadneSupplier;
import io.ariadne.core.AsyncCausalityException;
import io.ariadne.core.CallSiteMetadata;
import io.ariadne.core.CanaryProbeResult;
import io.ariadne.core.ContextCarrier;
import io.ariadne.core.Link;
import io.ariadne.core.SiteRegistry;
import io.ariadne.core.ThreadLocalContextCarrier;
import io.ariadne.core.UnsupportedFrameworkVersionException;

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
                    AriadneConfig.class,
                    AriadneMetrics.class,
                    AriadneMXBean.class,
                    AriadneManagement.class,
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
        ClassLoader cl = clazz.getClassLoader();
        for (int i = 1; i <= 20; i++) {
            String anonName = clazz.getName() + "$" + i;
            try {
                Class<?> anonClass = (cl != null) ? cl.loadClass(anonName) : Class.forName(anonName);
                addClassAndInners(jos, anonClass);
            } catch (ClassNotFoundException ignored) {
                break;
            }
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
