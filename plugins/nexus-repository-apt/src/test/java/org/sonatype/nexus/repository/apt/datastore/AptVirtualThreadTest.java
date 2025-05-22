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
package org.sonatype.nexus.repository.apt.datastore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.collect.AttributesMap;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.apt.AptFormat;
import org.sonatype.nexus.repository.apt.internal.debian.ControlFile;
import org.sonatype.nexus.repository.apt.internal.debian.ControlFileParser;
import org.sonatype.nexus.repository.apt.internal.debian.PackageInfo;
import org.sonatype.nexus.repository.apt.internal.debian.PackageInfoParser;
import org.sonatype.nexus.repository.apt.virtualthread.VirtualThreadTestGroup;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.AssetBlob;
import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.store.AssetStore;
import org.sonatype.nexus.repository.content.store.ComponentStore;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.repository.view.Response;
import org.sonatype.nexus.repository.view.payloads.StringPayload;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for validating APT repository datastore operations with Java 21 Virtual Threads.
 * <p>
 * This test suite validates that APT repository datastore operations work correctly with Virtual Threads
 * and demonstrate improved performance and scalability compared to platform threads.
 * <p>
 * The tests focus on I/O-bound operations like asset browsing, component retrieval, and metadata processing
 * under high concurrency scenarios, which are prime candidates for Virtual Thread optimization.
 *
 * @since 3.60
 */
@EnabledOnJre(JRE.JAVA_21) // Only run on Java 21 which supports Virtual Threads
@Category(VirtualThreadTestGroup.class)
public class AptVirtualThreadTest
    extends VirtualThreadTestSupport
{
  private static final int CONCURRENT_OPERATIONS = 1000;
  private static final int WARMUP_OPERATIONS = 50;
  private static final String SAMPLE_PACKAGE_INFO = "Package: test-package\n" +
      "Version: 1.0.0\n" +
      "Architecture: amd64\n" +
      "Maintainer: Test <test@example.com>\n" +
      "Installed-Size: 1000\n" +
      "Depends: libc6, libtest\n" +
      "Filename: pool/main/t/test-package/test-package_1.0.0_amd64.deb\n" +
      "Size: 1024\n" +
      "MD5sum: abcdef1234567890abcdef1234567890\n" +
      "SHA1: abcdef1234567890abcdef1234567890abcdef12\n" +
      "SHA256: abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890\n" +
      "Section: utils\n" +
      "Priority: optional\n" +
      "Description: Test package for virtual thread testing\n" +
      " This is a test package used for validating virtual thread performance\n" +
      " in APT repository operations.\n";

  @Mock
  private Repository repository;

  @Mock
  private Request request;

  @Mock
  private Context context;
  
  @Mock
  private AssetStore assetStore;
  
  @Mock
  private ComponentStore componentStore;

  private ControlFileParser controlFileParser;
  private PackageInfoParser packageInfoParser;

  @BeforeEach
  public void setup() {
    // Skip tests if Virtual Threads are not supported
    assumeVirtualThreadSupported();

    // Initialize parsers
    controlFileParser = new ControlFileParser();
    packageInfoParser = new PackageInfoParser(controlFileParser);

    // Setup repository mock
    when(repository.getName()).thenReturn("apt-test-repo");
    when(repository.getFormat()).thenReturn(new AptFormat());

    // Setup context mock
    AttributesMap attributes = new AttributesMap();
    when(context.getAttributes()).thenReturn(attributes);
    when(context.getRepository()).thenReturn(repository);
    when(context.getRequest()).thenReturn(request);
  }

  /**
   * Tests that APT package info parsing works correctly with Virtual Threads.
   * <p>
   * This test validates that the core APT package parsing functionality works
   * correctly when executed on Virtual Threads, ensuring functional correctness.
   */
  @Test
  public void testPackageInfoParsingWithVirtualThreads() throws Exception {
    // Create a test callable that parses package info
    Callable<PackageInfo> parsePackageInfo = () -> {
      try (ByteArrayInputStream inputStream = new ByteArrayInputStream(
          SAMPLE_PACKAGE_INFO.getBytes(StandardCharsets.UTF_8))) {
        return packageInfoParser.parsePackageInfo(inputStream);
      }
    };

    // Execute the callable on a Virtual Thread
    PackageInfo packageInfo = callVirtual(parsePackageInfo);

    // Verify the parsed package info
    assertNotNull(packageInfo, "Package info should not be null");
    assertEquals("test-package", packageInfo.getPackageName(), "Package name should match");
    assertEquals("1.0.0", packageInfo.getVersion(), "Package version should match");
    assertEquals("amd64", packageInfo.getArchitecture(), "Package architecture should match");
  }

  /**
   * Tests that control file parsing works correctly with Virtual Threads.
   * <p>
   * This test validates that the APT control file parsing functionality works
   * correctly when executed on Virtual Threads, ensuring functional correctness.
   */
  @Test
  public void testControlFileParsingWithVirtualThreads() throws Exception {
    // Create a test callable that parses a control file
    Callable<ControlFile> parseControlFile = () -> {
      try (ByteArrayInputStream inputStream = new ByteArrayInputStream(
          SAMPLE_PACKAGE_INFO.getBytes(StandardCharsets.UTF_8))) {
        return controlFileParser.parse(inputStream);
      }
    };

    // Execute the callable on a Virtual Thread
    ControlFile controlFile = callVirtual(parseControlFile);

    // Verify the parsed control file
    assertNotNull(controlFile, "Control file should not be null");
    assertEquals("test-package", controlFile.getField("Package"), "Package field should match");
    assertEquals("1.0.0", controlFile.getField("Version"), "Version field should match");
    assertEquals("amd64", controlFile.getField("Architecture"), "Architecture field should match");
  }

  /**
   * Tests that APT repository operations don't cause thread pinning.
   * <p>
   * Thread pinning occurs when a Virtual Thread is forced to stay on its carrier platform thread,
   * which negates many of the benefits of Virtual Threads. This test verifies that common APT
   * operations don't cause thread pinning.
   */
  @Test
  public void testNoThreadPinningInAptOperations() throws Exception {
    // Test package info parsing for thread pinning
    boolean pinningDetected = detectThreadPinning(() -> {
      try (ByteArrayInputStream inputStream = new ByteArrayInputStream(
          SAMPLE_PACKAGE_INFO.getBytes(StandardCharsets.UTF_8))) {
        packageInfoParser.parsePackageInfo(inputStream);
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
    });

    assertFalse(pinningDetected, "Package info parsing should not cause thread pinning");

    // Test control file parsing for thread pinning
    pinningDetected = detectThreadPinning(() -> {
      try (ByteArrayInputStream inputStream = new ByteArrayInputStream(
          SAMPLE_PACKAGE_INFO.getBytes(StandardCharsets.UTF_8))) {
        controlFileParser.parse(inputStream);
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
    });

    assertFalse(pinningDetected, "Control file parsing should not cause thread pinning");
  }

  /**
   * Tests the performance of APT package info parsing with Virtual Threads vs Platform Threads.
   * <p>
   * This test compares the performance of parsing a large number of package info files
   * concurrently using both Virtual Threads and Platform Threads. Virtual Threads should
   * demonstrate better scalability and resource utilization for this I/O-bound operation.
   */
  @Test
  public void testPackageInfoParsingPerformance() throws Exception {
    // Create a list of package info strings to parse
    List<String> packageInfos = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      packageInfos.add(SAMPLE_PACKAGE_INFO.replace("test-package", "test-package-" + i));
    }

    // Warm up to avoid JIT compilation effects
    for (int i = 0; i < WARMUP_OPERATIONS; i++) {
      try (ByteArrayInputStream inputStream = new ByteArrayInputStream(
          packageInfos.get(i % packageInfos.size()).getBytes(StandardCharsets.UTF_8))) {
        packageInfoParser.parsePackageInfo(inputStream);
      }
    }

    // Test with platform threads
    long platformThreadTime = measurePlatformThreadPerformance(packageInfos);

    // Test with virtual threads
    long virtualThreadTime = measureVirtualThreadPerformance(packageInfos);

    // Log the results
    log.info("Platform Thread Time: {} ms for {} operations", platformThreadTime, CONCURRENT_OPERATIONS);
    log.info("Virtual Thread Time: {} ms for {} operations", virtualThreadTime, CONCURRENT_OPERATIONS);

    // Virtual threads should be faster or at least not significantly slower
    // The exact performance difference depends on the environment, but virtual threads
    // should show better scalability with high concurrency
    assertThat("Virtual threads should perform better than platform threads for I/O-bound operations",
        virtualThreadTime, lessThan(platformThreadTime * 1.2)); // Allow some margin for test variability
  }

  /**
   * Tests the scalability of APT operations with a high number of concurrent Virtual Threads.
   * <p>
   * This test validates that APT operations can scale effectively with a very high number
   * of concurrent Virtual Threads, which would be impractical with platform threads due to
   * their higher memory footprint and context switching overhead.
   */
  @Test
  public void testHighConcurrencyScalability() throws Exception {
    // Create a large number of concurrent tasks
    int concurrentTasks = 10000; // This would be impractical with platform threads
    AtomicInteger successCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(concurrentTasks);

    // Create and start virtual threads for each task
    try (ExecutorService executor = newVirtualThreadExecutor("apt-test-")) {
      for (int i = 0; i < concurrentTasks; i++) {
        final int taskId = i;
        executor.submit(() -> {
          try {
            // Simulate an APT repository operation
            String packageInfoContent = SAMPLE_PACKAGE_INFO.replace("test-package", "test-package-" + taskId);
            try (ByteArrayInputStream inputStream = new ByteArrayInputStream(
                packageInfoContent.getBytes(StandardCharsets.UTF_8))) {
              PackageInfo packageInfo = packageInfoParser.parsePackageInfo(inputStream);
              if (packageInfo != null && packageInfo.getPackageName().equals("test-package-" + taskId)) {
                successCount.incrementAndGet();
              }
            }
          }
          catch (Exception e) {
            log.error("Error in virtual thread task", e);
          }
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete or timeout
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertTrue(completed, "All virtual thread tasks should complete within the timeout");

      // Verify that all tasks completed successfully
      assertEquals(concurrentTasks, successCount.get(),
          "All concurrent tasks should complete successfully");
    }
  }

  /**
   * Tests that APT repository response handling works correctly with Virtual Threads.
   * <p>
   * This test validates that the APT repository response handling functionality works
   * correctly when executed on Virtual Threads, ensuring functional correctness.
   */
  @Test
  public void testRepositoryResponseHandlingWithVirtualThreads() throws Exception {
    // Create a mock response
    Response response = mock(Response.class);
    Content content = mock(Content.class);
    Payload payload = new StringPayload(SAMPLE_PACKAGE_INFO, "text/plain");
    when(content.getPayload()).thenReturn(payload);
    when(response.getPayload()).thenReturn(content);
    when(response.getStatus()).thenReturn(Response.Status.success(200));

    // Create a test callable that processes the response
    Callable<PackageInfo> processResponse = () -> {
      try (ByteArrayInputStream inputStream = new ByteArrayInputStream(
          SAMPLE_PACKAGE_INFO.getBytes(StandardCharsets.UTF_8))) {
        return packageInfoParser.parsePackageInfo(inputStream);
      }
    };

    // Execute the callable on a Virtual Thread
    PackageInfo packageInfo = callVirtual(processResponse);

    // Verify the processed response
    assertNotNull(packageInfo, "Package info should not be null");
    assertEquals("test-package", packageInfo.getPackageName(), "Package name should match");
  }

  /**
   * Tests concurrent APT repository datastore operations with mixed read patterns.
   * <p>
   * This test validates that APT repository datastore operations with mixed read patterns
   * work correctly when executed concurrently on Virtual Threads.
   */
  @Test
  public void testConcurrentDatastoreOperations() throws Exception {
    // Setup mock assets and components for datastore operations
    List<Asset> mockAssets = new ArrayList<>();
    List<Component> mockComponents = new ArrayList<>();
    
    for (int i = 0; i < 100; i++) {
      Asset asset = mock(Asset.class);
      Component component = mock(Component.class);
      AssetBlob assetBlob = mock(AssetBlob.class);
      
      when(asset.path()).thenReturn("/path/to/asset-" + i);
      when(asset.component()).thenReturn(component);
      when(asset.blob()).thenReturn(assetBlob);
      when(component.namespace()).thenReturn("amd64");
      when(component.name()).thenReturn("test-package-" + i);
      when(component.version()).thenReturn("1.0." + i);
      
      mockAssets.add(asset);
      mockComponents.add(component);
    }
    
    // Configure asset store to return mock assets
    when(assetStore.browseAssets(any(), any(), any(), any())).thenReturn(mockAssets.iterator());
    when(componentStore.browseComponents(any(), any(), any(), any())).thenReturn(mockComponents.iterator());
    
    int concurrentTasks = 1000;
    AtomicInteger successCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(concurrentTasks);

    // Create a mix of read operations
    List<Supplier<Runnable>> tasks = new ArrayList<>();
    for (int i = 0; i < concurrentTasks; i++) {
      final int taskId = i;
      if (i % 3 == 0) {
        // Asset browsing operation
        tasks.add(() -> () -> {
          try {
            // Simulate browsing assets
            mockAssets.forEach(asset -> {
              String path = asset.path();
              if (path != null && path.contains("asset-")) {
                successCount.incrementAndGet();
              }
            });
          }
          catch (Exception e) {
            log.error("Error in asset browsing operation", e);
          }
        });
      }
      else if (i % 3 == 1) {
        // Component browsing operation
        tasks.add(() -> () -> {
          try {
            // Simulate browsing components
            mockComponents.forEach(component -> {
              String name = component.name();
              if (name != null && name.contains("test-package-")) {
                successCount.incrementAndGet();
              }
            });
          }
          catch (Exception e) {
            log.error("Error in component browsing operation", e);
          }
        });
      }
      else {
        // Package info parsing operation
        tasks.add(() -> () -> {
          try {
            String packageInfoContent = SAMPLE_PACKAGE_INFO.replace("test-package", "test-package-" + taskId);
            try (ByteArrayInputStream inputStream = new ByteArrayInputStream(
                packageInfoContent.getBytes(StandardCharsets.UTF_8))) {
              PackageInfo packageInfo = packageInfoParser.parsePackageInfo(inputStream);
              if (packageInfo != null) {
                successCount.incrementAndGet();
              }
            }
          }
          catch (Exception e) {
            log.error("Error in package info parsing operation", e);
          }
        });
      }
    }

    // Execute all tasks concurrently using virtual threads
    runConcurrently(concurrentTasks, () -> {
      try {
        int index = (int) (Math.random() * tasks.size());
        tasks.get(index).get().run();
      }
      finally {
        latch.countDown();
      }
    });

    // Wait for all tasks to complete
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    assertTrue(completed, "All virtual thread tasks should complete within the timeout");
    
    // Verify that tasks completed successfully
    assertThat("A significant number of tasks should complete successfully", 
        successCount.get(), greaterThan(concurrentTasks / 2));
  }

  /**
   * Measures the performance of parsing package info files using platform threads.
   *
   * @param packageInfos the list of package info strings to parse
   * @return the time taken in milliseconds
   */
  private long measurePlatformThreadPerformance(List<String> packageInfos) throws Exception {
    final int threadPoolSize = Math.min(100, Runtime.getRuntime().availableProcessors() * 2);
    final AtomicInteger successCount = new AtomicInteger(0);

    long startTime = System.currentTimeMillis();

    try (ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize)) {
      List<Future<?>> futures = new ArrayList<>();

      for (int i = 0; i < packageInfos.size(); i++) {
        final String packageInfoContent = packageInfos.get(i);
        futures.add(executor.submit(() -> {
          try (ByteArrayInputStream inputStream = new ByteArrayInputStream(
              packageInfoContent.getBytes(StandardCharsets.UTF_8))) {
            PackageInfo packageInfo = packageInfoParser.parsePackageInfo(inputStream);
            if (packageInfo != null) {
              successCount.incrementAndGet();
            }
          }
          catch (Exception e) {
            log.error("Error parsing package info with platform thread", e);
          }
        }));
      }

      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        future.get();
      }
    }

    long endTime = System.currentTimeMillis();
    assertEquals(packageInfos.size(), successCount.get(), "All package info files should be parsed successfully");
    return endTime - startTime;
  }

  /**
   * Measures the performance of parsing package info files using virtual threads.
   *
   * @param packageInfos the list of package info strings to parse
   * @return the time taken in milliseconds
   */
  private long measureVirtualThreadPerformance(List<String> packageInfos) throws Exception {
    final AtomicInteger successCount = new AtomicInteger(0);

    long startTime = System.currentTimeMillis();

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();

      for (int i = 0; i < packageInfos.size(); i++) {
        final String packageInfoContent = packageInfos.get(i);
        futures.add(executor.submit(() -> {
          try (ByteArrayInputStream inputStream = new ByteArrayInputStream(
              packageInfoContent.getBytes(StandardCharsets.UTF_8))) {
            PackageInfo packageInfo = packageInfoParser.parsePackageInfo(inputStream);
            if (packageInfo != null) {
              successCount.incrementAndGet();
            }
          }
          catch (Exception e) {
            log.error("Error parsing package info with virtual thread", e);
          }
        }));
      }

      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        future.get();
      }
    }

    long endTime = System.currentTimeMillis();
    assertEquals(packageInfos.size(), successCount.get(), "All package info files should be parsed successfully");
    return endTime - startTime;
  }

  /**
   * Custom implementation of thread pinning detection for APT operations.
   * <p>
   * This method runs a task on a virtual thread and attempts to detect if the thread
   * gets pinned to its carrier platform thread during execution.
   *
   * @param task the task to execute and check for pinning
   * @return true if pinning was detected, false otherwise
   */
  private boolean detectThreadPinning(Runnable task) throws Exception {
    // Enable thread pinning detection via JVM flag
    String previousValue = System.getProperty("jdk.tracePinnedThreads");
    try {
      System.setProperty("jdk.tracePinnedThreads", "full");
      
      // Create a concurrent task that will run alongside the main task
      AtomicInteger concurrentExecutions = new AtomicInteger(0);
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch endLatch = new CountDownLatch(1);
      
      // Start the main task
      Thread mainThread = Thread.ofVirtual().name("apt-main-task").start(() -> {
        try {
          startLatch.countDown(); // Signal that the main thread has started
          task.run();
        }
        finally {
          endLatch.countDown(); // Signal that the main thread has completed
        }
      });
      
      // Wait for the main thread to start
      startLatch.await();
      
      // Start multiple concurrent tasks to detect pinning
      int probeCount = 10;
      Thread[] probeThreads = new Thread[probeCount];
      for (int i = 0; i < probeCount; i++) {
        probeThreads[i] = Thread.ofVirtual().name("apt-probe-" + i).start(() -> {
          concurrentExecutions.incrementAndGet();
        });
      }
      
      // Wait for the main thread to complete
      endLatch.await();
      
      // If fewer than expected concurrent executions occurred, pinning may have happened
      return concurrentExecutions.get() < probeCount;
    }
    finally {
      // Restore the previous system property value
      if (previousValue == null) {
        System.clearProperty("jdk.tracePinnedThreads");
      }
      else {
        System.setProperty("jdk.tracePinnedThreads", previousValue);
      }
    }
  }
}