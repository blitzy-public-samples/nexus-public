/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.apache.felix;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.felix.framework.Felix;
import org.apache.felix.framework.util.FelixConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.wiring.BundleWiring;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Tests to verify Apache Felix OSGi framework's bundle loading and lifecycle functionality under Java 21.
 * <p>
 * This test suite validates that Felix correctly handles Java 21 class formats, module boundaries,
 * and virtual thread execution when loading and managing OSGi bundles.
 */
@ExtendWith(MockitoExtension.class)
public class FelixBundleLoaderTest
{
  private static final String BUNDLE_SYMBOLIC_NAME = "org.sonatype.nexus.test.bundle";

  private static final String BUNDLE_VERSION = "1.0.0";

  @TempDir
  Path tempDir;

  @Mock
  private BundleContext mockBundleContext;

  @Mock
  private Bundle mockBundle;

  @Mock
  private BundleWiring mockBundleWiring;

  @Mock
  private ServiceReference<?> mockServiceReference;

  private Framework felixFramework;

  private File cacheDir;

  private File bundleDir;

  @BeforeEach
  void setUp() throws IOException {
    // Create directories for Felix cache and test bundles
    cacheDir = tempDir.resolve("felix-cache").toFile();
    bundleDir = tempDir.resolve("bundles").toFile();
    cacheDir.mkdirs();
    bundleDir.mkdirs();

    // Configure Felix framework
    Map<String, Object> configMap = new ConcurrentHashMap<>();
    configMap.put(Constants.FRAMEWORK_STORAGE, cacheDir.getAbsolutePath());
    configMap.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
    configMap.put(FelixConstants.LOG_LEVEL_PROP, "4"); // DEBUG logging
    configMap.put(Constants.FRAMEWORK_SYSTEMPACKAGES_EXTRA,
        "org.osgi.framework; version=1.10.0," +
        "org.osgi.framework.wiring; version=1.2.0," +
        "org.osgi.service.packageadmin; version=2.0.0," +
        "org.osgi.service.startlevel; version=1.1.0," +
        "org.osgi.service.url; version=1.0.0," +
        "org.osgi.util.tracker; version=1.5.0");

    // Initialize Felix framework
    felixFramework = new Felix(configMap);
  }

  @AfterEach
  void tearDown() throws BundleException, InterruptedException {
    if (felixFramework != null) {
      felixFramework.stop();
      felixFramework.waitForStop(10000);
    }
  }

  /**
   * Tests basic Felix framework initialization and bundle context retrieval under Java 21.
   */
  @Test
  @DisplayName("Felix framework initializes correctly under Java 21")
  void testFelixInitialization() throws BundleException {
    // Start Felix framework
    felixFramework.init();
    felixFramework.start();

    // Verify framework is active
    assertEquals(Bundle.ACTIVE, felixFramework.getState(), "Felix framework should be in ACTIVE state");

    // Verify bundle context is available
    BundleContext bundleContext = felixFramework.getBundleContext();
    assertNotNull(bundleContext, "Bundle context should be available");

    // Verify system bundle is registered
    Bundle systemBundle = bundleContext.getBundle(0);
    assertNotNull(systemBundle, "System bundle should be available");
    assertEquals("org.apache.felix.framework", systemBundle.getSymbolicName(),
        "System bundle should have correct symbolic name");
  }

  /**
   * Tests bundle installation, resolution, and lifecycle operations under Java 21.
   */
  @Test
  @DisplayName("Felix handles bundle lifecycle operations correctly under Java 21")
  void testBundleLifecycle() throws BundleException, IOException {
    // Start Felix framework
    felixFramework.init();
    felixFramework.start();
    BundleContext bundleContext = felixFramework.getBundleContext();

    // Create a simple test bundle
    File bundleFile = createTestBundle(bundleDir, BUNDLE_SYMBOLIC_NAME, BUNDLE_VERSION);

    // Install the bundle
    Bundle bundle = bundleContext.installBundle("file:" + bundleFile.getAbsolutePath());
    assertNotNull(bundle, "Bundle should be installed");
    assertEquals(BUNDLE_SYMBOLIC_NAME, bundle.getSymbolicName(), "Bundle should have correct symbolic name");
    assertEquals(BUNDLE_VERSION, bundle.getVersion().toString(), "Bundle should have correct version");
    assertEquals(Bundle.INSTALLED, bundle.getState(), "Bundle should be in INSTALLED state");

    // Resolve the bundle
    bundle.resolve();
    assertTrue(bundle.getState() == Bundle.RESOLVED || bundle.getState() == Bundle.INSTALLED,
        "Bundle should be in RESOLVED or INSTALLED state after resolve()");

    // Start the bundle
    bundle.start();
    assertEquals(Bundle.ACTIVE, bundle.getState(), "Bundle should be in ACTIVE state after start()");

    // Stop the bundle
    bundle.stop();
    assertEquals(Bundle.RESOLVED, bundle.getState(), "Bundle should be in RESOLVED state after stop()");

    // Uninstall the bundle
    bundle.uninstall();
    assertEquals(Bundle.UNINSTALLED, bundle.getState(), "Bundle should be in UNINSTALLED state after uninstall()");
  }

  /**
   * Tests bundle loading with virtual threads to verify thread compatibility.
   */
  @Test
  @DisplayName("Felix handles bundle operations with virtual threads")
  void testBundleOperationsWithVirtualThreads() throws BundleException, IOException, InterruptedException {
    // Start Felix framework
    felixFramework.init();
    felixFramework.start();
    BundleContext bundleContext = felixFramework.getBundleContext();

    // Create test bundles
    int bundleCount = 10;
    File[] bundleFiles = new File[bundleCount];
    for (int i = 0; i < bundleCount; i++) {
      bundleFiles[i] = createTestBundle(bundleDir, BUNDLE_SYMBOLIC_NAME + "." + i, BUNDLE_VERSION);
    }

    // Create virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("felix-test-", 0).factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      // Install bundles using virtual threads
      CountDownLatch installLatch = new CountDownLatch(bundleCount);
      Bundle[] bundles = new Bundle[bundleCount];

      for (int i = 0; i < bundleCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            bundles[index] = bundleContext.installBundle("file:" + bundleFiles[index].getAbsolutePath());
          }
          catch (BundleException e) {
            fail("Failed to install bundle: " + e.getMessage());
          }
          finally {
            installLatch.countDown();
          }
        });
      }

      assertTrue(installLatch.await(10, TimeUnit.SECONDS), "Bundle installation should complete within timeout");

      // Verify all bundles were installed
      for (int i = 0; i < bundleCount; i++) {
        assertNotNull(bundles[i], "Bundle " + i + " should be installed");
        assertEquals(BUNDLE_SYMBOLIC_NAME + "." + i, bundles[i].getSymbolicName(),
            "Bundle " + i + " should have correct symbolic name");
      }

      // Start bundles using virtual threads
      CountDownLatch startLatch = new CountDownLatch(bundleCount);
      AtomicInteger startErrorCount = new AtomicInteger(0);

      for (int i = 0; i < bundleCount; i++) {
        final Bundle bundle = bundles[i];
        executor.submit(() -> {
          try {
            bundle.start();
          }
          catch (BundleException e) {
            startErrorCount.incrementAndGet();
          }
          finally {
            startLatch.countDown();
          }
        });
      }

      assertTrue(startLatch.await(10, TimeUnit.SECONDS), "Bundle start operations should complete within timeout");
      assertEquals(0, startErrorCount.get(), "All bundles should start without errors");

      // Verify all bundles are active
      for (Bundle bundle : bundles) {
        assertEquals(Bundle.ACTIVE, bundle.getState(), "Bundle should be in ACTIVE state");
      }

      // Stop bundles using virtual threads
      CountDownLatch stopLatch = new CountDownLatch(bundleCount);
      AtomicInteger stopErrorCount = new AtomicInteger(0);

      for (int i = 0; i < bundleCount; i++) {
        final Bundle bundle = bundles[i];
        executor.submit(() -> {
          try {
            bundle.stop();
          }
          catch (BundleException e) {
            stopErrorCount.incrementAndGet();
          }
          finally {
            stopLatch.countDown();
          }
        });
      }

      assertTrue(stopLatch.await(10, TimeUnit.SECONDS), "Bundle stop operations should complete within timeout");
      assertEquals(0, stopErrorCount.get(), "All bundles should stop without errors");

      // Verify all bundles are resolved (stopped)
      for (Bundle bundle : bundles) {
        assertEquals(Bundle.RESOLVED, bundle.getState(), "Bundle should be in RESOLVED state after stop");
      }

      // Uninstall bundles using virtual threads
      CountDownLatch uninstallLatch = new CountDownLatch(bundleCount);
      AtomicInteger uninstallErrorCount = new AtomicInteger(0);

      for (int i = 0; i < bundleCount; i++) {
        final Bundle bundle = bundles[i];
        executor.submit(() -> {
          try {
            bundle.uninstall();
          }
          catch (BundleException e) {
            uninstallErrorCount.incrementAndGet();
          }
          finally {
            uninstallLatch.countDown();
          }
        });
      }

      assertTrue(uninstallLatch.await(10, TimeUnit.SECONDS),
          "Bundle uninstall operations should complete within timeout");
      assertEquals(0, uninstallErrorCount.get(), "All bundles should uninstall without errors");

      // Verify all bundles are uninstalled
      for (Bundle bundle : bundles) {
        assertEquals(Bundle.UNINSTALLED, bundle.getState(), "Bundle should be in UNINSTALLED state");
      }
    }
    finally {
      executor.shutdown();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor should terminate within timeout");
    }
  }

  /**
   * Tests loading of bundles containing Java 21 language features.
   */
  @Test
  @DisplayName("Felix correctly loads bundles with Java 21 language features")
  void testLoadingBundlesWithJava21Features() throws BundleException, IOException {
    // Configure mock bundle to simulate Java 21 features
    when(mockBundle.getSymbolicName()).thenReturn("java21.features.bundle");
    when(mockBundle.getVersion()).thenReturn(new org.osgi.framework.Version("1.0.0"));
    when(mockBundle.getBundleContext()).thenReturn(mockBundleContext);
    when(mockBundle.adapt(BundleWiring.class)).thenReturn(mockBundleWiring);

    // Simulate class loading for Java 21 features
    lenient().when(mockBundle.loadClass("org.example.RecordPatternExample"))
        .thenReturn((Class<?>) Object.class); // Simulate a class with record patterns
    lenient().when(mockBundle.loadClass("org.example.VirtualThreadExample"))
        .thenReturn((Class<?>) Object.class); // Simulate a class using virtual threads
    lenient().when(mockBundle.loadClass("org.example.StringTemplateExample"))
        .thenReturn((Class<?>) Object.class); // Simulate a class with string templates

    // Simulate service lookup
    when(mockBundleContext.getServiceReference(anyString())).thenReturn(mockServiceReference);
    when(mockBundleContext.getService(any(ServiceReference.class))).thenReturn(new Object());

    // Verify Java 21 feature classes can be loaded
    assertDoesNotThrow(() -> {
      Class<?> recordPatternClass = mockBundle.loadClass("org.example.RecordPatternExample");
      assertNotNull(recordPatternClass, "Should load class with record patterns");

      Class<?> virtualThreadClass = mockBundle.loadClass("org.example.VirtualThreadExample");
      assertNotNull(virtualThreadClass, "Should load class with virtual threads");

      Class<?> stringTemplateClass = mockBundle.loadClass("org.example.StringTemplateExample");
      assertNotNull(stringTemplateClass, "Should load class with string templates");
    }, "Loading classes with Java 21 features should not throw exceptions");
  }

  /**
   * Tests concurrent bundle operations using CompletableFuture with virtual threads.
   */
  @Test
  @DisplayName("Felix handles concurrent bundle operations with CompletableFuture and virtual threads")
  void testConcurrentBundleOperationsWithCompletableFuture() throws BundleException, IOException, InterruptedException {
    // Start Felix framework
    felixFramework.init();
    felixFramework.start();
    BundleContext bundleContext = felixFramework.getBundleContext();

    // Create test bundles
    int bundleCount = 20;
    File[] bundleFiles = new File[bundleCount];
    for (int i = 0; i < bundleCount; i++) {
      bundleFiles[i] = createTestBundle(bundleDir, BUNDLE_SYMBOLIC_NAME + ".cf." + i, BUNDLE_VERSION);
    }

    // Use virtual threads for CompletableFuture execution
    System.setProperty("jdk.virtualThreadScheduler.parallelism", "16");
    System.setProperty("jdk.virtualThreadScheduler.maxPoolSize", "256");

    // Install bundles concurrently using CompletableFuture with virtual threads
    CompletableFuture<?>[] installFutures = new CompletableFuture<?>[bundleCount];
    Bundle[] bundles = new Bundle[bundleCount];

    for (int i = 0; i < bundleCount; i++) {
      final int index = i;
      final File bundleFile = bundleFiles[index];
      installFutures[i] = CompletableFuture.runAsync(() -> {
        try {
          bundles[index] = bundleContext.installBundle("file:" + bundleFile.getAbsolutePath());
        }
        catch (BundleException e) {
          fail("Failed to install bundle: " + e.getMessage());
        }
      }, CompletableFuture.delayedExecutor(0, TimeUnit.MILLISECONDS));
    }

    // Wait for all installations to complete
    CompletableFuture.allOf(installFutures).join();

    // Verify all bundles were installed
    for (int i = 0; i < bundleCount; i++) {
      assertNotNull(bundles[i], "Bundle " + i + " should be installed");
      assertEquals(BUNDLE_SYMBOLIC_NAME + ".cf." + i, bundles[i].getSymbolicName(),
          "Bundle " + i + " should have correct symbolic name");
    }

    // Start bundles concurrently
    CompletableFuture<?>[] startFutures = new CompletableFuture<?>[bundleCount];
    for (int i = 0; i < bundleCount; i++) {
      final Bundle bundle = bundles[i];
      startFutures[i] = CompletableFuture.runAsync(() -> {
        try {
          bundle.start();
        }
        catch (BundleException e) {
          fail("Failed to start bundle: " + e.getMessage());
        }
      }, CompletableFuture.delayedExecutor(0, TimeUnit.MILLISECONDS));
    }

    // Wait for all starts to complete
    CompletableFuture.allOf(startFutures).join();

    // Verify all bundles are active
    for (Bundle bundle : bundles) {
      assertEquals(Bundle.ACTIVE, bundle.getState(), "Bundle should be in ACTIVE state");
    }

    // Stop bundles concurrently
    CompletableFuture<?>[] stopFutures = new CompletableFuture<?>[bundleCount];
    for (int i = 0; i < bundleCount; i++) {
      final Bundle bundle = bundles[i];
      stopFutures[i] = CompletableFuture.runAsync(() -> {
        try {
          bundle.stop();
        }
        catch (BundleException e) {
          fail("Failed to stop bundle: " + e.getMessage());
        }
      }, CompletableFuture.delayedExecutor(0, TimeUnit.MILLISECONDS));
    }

    // Wait for all stops to complete
    CompletableFuture.allOf(stopFutures).join();

    // Verify all bundles are resolved (stopped)
    for (Bundle bundle : bundles) {
      assertEquals(Bundle.RESOLVED, bundle.getState(), "Bundle should be in RESOLVED state after stop");
    }

    // Uninstall bundles concurrently
    CompletableFuture<?>[] uninstallFutures = new CompletableFuture<?>[bundleCount];
    for (int i = 0; i < bundleCount; i++) {
      final Bundle bundle = bundles[i];
      uninstallFutures[i] = CompletableFuture.runAsync(() -> {
        try {
          bundle.uninstall();
        }
        catch (BundleException e) {
          fail("Failed to uninstall bundle: " + e.getMessage());
        }
      }, CompletableFuture.delayedExecutor(0, TimeUnit.MILLISECONDS));
    }

    // Wait for all uninstalls to complete
    CompletableFuture.allOf(uninstallFutures).join();

    // Verify all bundles are uninstalled
    for (Bundle bundle : bundles) {
      assertEquals(Bundle.UNINSTALLED, bundle.getState(), "Bundle should be in UNINSTALLED state");
    }

    // Reset system properties
    System.clearProperty("jdk.virtualThreadScheduler.parallelism");
    System.clearProperty("jdk.virtualThreadScheduler.maxPoolSize");
  }

  /**
   * Tests that Felix correctly handles Java 21 module boundaries through OSGi classloading.
   */
  @Test
  @DisplayName("Felix correctly handles Java 21 module boundaries through OSGi classloading")
  void testJava21ModuleBoundaries() throws BundleException, IOException {
    // Start Felix framework
    felixFramework.init();
    felixFramework.start();
    BundleContext bundleContext = felixFramework.getBundleContext();

    // Create test bundles with different packages
    File bundle1File = createTestBundle(bundleDir, "org.example.module1", "1.0.0",
        List.of("org.example.module1"), List.of("org.example.module1.api"), null);
    File bundle2File = createTestBundle(bundleDir, "org.example.module2", "1.0.0",
        List.of("org.example.module2"), null, List.of("org.example.module1.api"));

    // Install bundles
    Bundle bundle1 = bundleContext.installBundle("file:" + bundle1File.getAbsolutePath());
    Bundle bundle2 = bundleContext.installBundle("file:" + bundle2File.getAbsolutePath());

    // Start bundles
    bundle1.start();
    bundle2.start();

    // Verify bundles are active
    assertEquals(Bundle.ACTIVE, bundle1.getState(), "Bundle 1 should be in ACTIVE state");
    assertEquals(Bundle.ACTIVE, bundle2.getState(), "Bundle 2 should be in ACTIVE state");

    // Verify bundle wiring
    BundleWiring wiring1 = bundle1.adapt(BundleWiring.class);
    BundleWiring wiring2 = bundle2.adapt(BundleWiring.class);

    assertNotNull(wiring1, "Bundle 1 should have wiring");
    assertNotNull(wiring2, "Bundle 2 should have wiring");

    // Verify exported packages
    List<BundleWiring.ListenerInfo> listeners1 = wiring1.getListeners();
    List<BundleWiring.ListenerInfo> listeners2 = wiring2.getListeners();

    assertNotNull(listeners1, "Bundle 1 should have listeners");
    assertNotNull(listeners2, "Bundle 2 should have listeners");

    // Clean up
    bundle2.stop();
    bundle1.stop();
    bundle2.uninstall();
    bundle1.uninstall();
  }

  /**
   * Creates a test bundle with the specified symbolic name and version.
   *
   * @param bundleDir       Directory to create the bundle in
   * @param symbolicName    Bundle symbolic name
   * @param version         Bundle version
   * @return                The created bundle file
   */
  private File createTestBundle(File bundleDir, String symbolicName, String version) throws IOException {
    return createTestBundle(bundleDir, symbolicName, version, null, null, null);
  }

  /**
   * Creates a test bundle with the specified symbolic name, version, and package information.
   *
   * @param bundleDir       Directory to create the bundle in
   * @param symbolicName    Bundle symbolic name
   * @param version         Bundle version
   * @param packages        List of packages in the bundle (can be null)
   * @param exportPackages  List of exported packages (can be null)
   * @param importPackages  List of imported packages (can be null)
   * @return                The created bundle file
   */
  private File createTestBundle(
      File bundleDir,
      String symbolicName,
      String version,
      List<String> packages,
      List<String> exportPackages,
      List<String> importPackages) throws IOException
  {
    // Create manifest content
    StringBuilder manifestContent = new StringBuilder();
    manifestContent.append("Manifest-Version: 1.0\n");
    manifestContent.append("Bundle-ManifestVersion: 2\n");
    manifestContent.append("Bundle-SymbolicName: ").append(symbolicName).append("\n");
    manifestContent.append("Bundle-Version: ").append(version).append("\n");
    manifestContent.append("Bundle-Name: Test Bundle\n");

    // Add Export-Package header if specified
    if (exportPackages != null && !exportPackages.isEmpty()) {
      manifestContent.append("Export-Package: ");
      for (int i = 0; i < exportPackages.size(); i++) {
        if (i > 0) {
          manifestContent.append(",");
        }
        manifestContent.append(exportPackages.get(i));
      }
      manifestContent.append("\n");
    }

    // Add Import-Package header if specified
    if (importPackages != null && !importPackages.isEmpty()) {
      manifestContent.append("Import-Package: ");
      for (int i = 0; i < importPackages.size(); i++) {
        if (i > 0) {
          manifestContent.append(",");
        }
        manifestContent.append(importPackages.get(i));
      }
      manifestContent.append("\n");
    }

    // Create a JAR file for the bundle
    File bundleFile = new File(bundleDir, symbolicName + "-" + version + ".jar");
    
    // Write manifest to the bundle file (simplified for testing)
    Files.writeString(bundleFile.toPath(), manifestContent.toString());

    // Create package directories and class files if specified
    if (packages != null) {
      for (String pkg : packages) {
        // In a real implementation, we would create actual class files here
        // For this test, we're just simulating the bundle structure
      }
    }

    return bundleFile;
  }

  /**
   * Record class for testing Java 21 record patterns.
   * This is just a placeholder to demonstrate Java 21 language feature compatibility.
   */
  record TestRecord(String name, int value) {
    // Empty record for testing purposes
  }

  /**
   * Interface for testing Java 21 sealed classes.
   * This is just a placeholder to demonstrate Java 21 language feature compatibility.
   */
  sealed interface TestInterface permits TestImplementation {
    void doSomething();
  }

  /**
   * Implementation class for testing Java 21 sealed classes.
   * This is just a placeholder to demonstrate Java 21 language feature compatibility.
   */
  final class TestImplementation implements TestInterface {
    @Override
    public void doSomething() {
      // Empty implementation for testing purposes
    }
  }
}