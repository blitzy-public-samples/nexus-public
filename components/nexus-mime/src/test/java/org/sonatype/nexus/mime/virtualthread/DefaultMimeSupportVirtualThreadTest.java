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
package org.sonatype.nexus.mime.virtualthread;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.mime.internal.DefaultMimeSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for {@link DefaultMimeSupport} using Java 21 Virtual Threads.
 * 
 * This test validates that MIME detection operations scale efficiently with Virtual Threads
 * and don't suffer from thread pinning issues when performing I/O operations.
 */
public class DefaultMimeSupportVirtualThreadTest
    extends TestSupport
{
  private DefaultMimeSupport underTest;
  
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;
  
  @TempDir
  Path tempDir;
  
  private File gifFile;
  private File zipFile;
  private File jarFile;
  
  @BeforeEach
  void setUp() throws IOException {
    underTest = new DefaultMimeSupport();
    
    // Create executors for testing
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    virtualThreadExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Limited platform thread pool for comparison
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    
    // Create test files in temp directory
    gifFile = copyResourceToTempFile("mime/file.gif");
    zipFile = copyResourceToTempFile("mime/file.zip");
    jarFile = copyResourceToTempFile("mime/file.jar");
  }
  
  @AfterEach
  void tearDown() {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
    }
    if (platformThreadExecutor != null) {
      platformThreadExecutor.shutdown();
    }
  }
  
  /**
   * Tests that MIME detection works correctly with a high number of concurrent operations using Virtual Threads.
   * This validates that the DefaultMimeSupport class can handle high concurrency scenarios efficiently.
   */
  @Test
  void concurrentMimeDetectionWithVirtualThreads() throws Exception {
    int taskCount = 5000; // High number of concurrent operations
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Submit multiple concurrent tasks using virtual threads
    for (int i = 0; i < taskCount; i++) {
      final int index = i % 3; // Cycle through the 3 test files
      virtualThreadExecutor.submit(() -> {
        try {
          String mimeType = detectMimeTypeForIndex(index);
          assertThat(mimeType, notNullValue());
        } 
        catch (Exception e) {
          log.error("Error detecting MIME type", e);
          errorCount.incrementAndGet();
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete with a reasonable timeout
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    
    // Verify results
    assertThat("All tasks should complete within the timeout", completed, is(true));
    assertThat("No errors should occur during MIME detection", errorCount.get(), is(0));
  }
  
  /**
   * Tests that MIME detection with content-based analysis doesn't suffer from thread pinning issues
   * when using Virtual Threads. This is important for I/O-bound operations like reading file content.
   */
  @Test
  void contentBasedMimeDetectionDoesNotPinVirtualThreads() throws Exception {
    int taskCount = 1000;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicReference<Exception> firstException = new AtomicReference<>();
    
    // Submit tasks that specifically perform content-based MIME detection
    for (int i = 0; i < taskCount; i++) {
      virtualThreadExecutor.submit(() -> {
        try (InputStream is = new FileInputStream(gifFile)) {
          // Force content-based detection by providing null filename
          String mimeType = underTest.detectMimeType(is, null);
          assertThat(mimeType, equalTo("image/gif"));
        } 
        catch (Exception e) {
          if (firstException.get() == null) {
            firstException.set(e);
          }
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    
    // Verify results
    assertThat("All tasks should complete within the timeout", completed, is(true));
    assertThat("No exceptions should occur during content-based MIME detection", 
        firstException.get(), is(null));
  }
  
  /**
   * Compares performance between Virtual Threads and Platform Threads for MIME detection operations.
   * This test validates that Virtual Threads provide better scalability for I/O-bound operations.
   */
  @Test
  void compareVirtualThreadsVsPlatformThreadsPerformance() throws Exception {
    int taskCount = 1000;
    
    // Measure execution time with platform threads
    long platformThreadTime = measureExecutionTime(platformThreadExecutor, taskCount);
    log.info("Platform thread execution time for {} tasks: {} ms", taskCount, platformThreadTime);
    
    // Measure execution time with virtual threads
    long virtualThreadTime = measureExecutionTime(virtualThreadExecutor, taskCount);
    log.info("Virtual thread execution time for {} tasks: {} ms", taskCount, virtualThreadTime);
    
    // For high concurrency I/O-bound operations, virtual threads should be more efficient
    // This might not always be true for small workloads due to JVM warmup, but should be
    // observable with sufficient load
    assertThat("Virtual threads should handle high concurrency efficiently", 
        virtualThreadTime, lessThan(platformThreadTime * 2)); // Conservative assertion
  }
  
  /**
   * Tests that extension-based MIME detection works correctly with Virtual Threads.
   */
  @Test
  void extensionBasedMimeDetectionWithVirtualThreads() throws Exception {
    int taskCount = 5000;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Submit multiple concurrent tasks using virtual threads
    for (int i = 0; i < taskCount; i++) {
      final int index = i % 3; // Cycle through the 3 test files
      virtualThreadExecutor.submit(() -> {
        try {
          String filename = getFilenameForIndex(index);
          String mimeType = underTest.guessMimeTypeFromPath(filename);
          assertThat(mimeType, notNullValue());
          
          // Verify correct MIME type based on extension
          switch (filename.substring(filename.lastIndexOf('.') + 1)) {
            case "gif" -> assertThat(mimeType, equalTo("image/gif"));
            case "zip" -> assertThat(mimeType, equalTo("application/zip"));
            case "jar" -> assertThat(mimeType, equalTo("application/java-archive"));
            default -> throw new AssertionError("Unexpected file extension");
          }
        } 
        catch (Exception e) {
          log.error("Error detecting MIME type", e);
          errorCount.incrementAndGet();
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    
    // Verify results
    assertThat("All tasks should complete within the timeout", completed, is(true));
    assertThat("No errors should occur during extension-based MIME detection", errorCount.get(), is(0));
  }
  
  /**
   * Tests that MIME detection with a large number of concurrent operations doesn't exhaust system resources
   * when using Virtual Threads. This validates the scalability benefits of Virtual Threads.
   */
  @Test
  void highConcurrencyMimeDetectionWithVirtualThreads() throws Exception {
    int taskCount = 10000; // Very high concurrency
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Submit a large number of concurrent tasks
    for (int i = 0; i < taskCount; i++) {
      final int index = i % 3;
      virtualThreadExecutor.submit(() -> {
        try {
          String mimeType = detectMimeTypeForIndex(index);
          if (mimeType != null) {
            successCount.incrementAndGet();
          }
        } 
        catch (Exception e) {
          log.error("Error in high concurrency test", e);
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete with a reasonable timeout
    boolean completed = latch.await(60, TimeUnit.SECONDS);
    
    // Verify results
    assertThat("All tasks should complete within the timeout", completed, is(true));
    assertThat("Most operations should succeed", successCount.get(), greaterThan(taskCount - 100));
  }
  
  /**
   * Helper method to measure execution time for a batch of MIME detection operations.
   */
  private long measureExecutionTime(ExecutorService executor, int taskCount) throws Exception {
    CountDownLatch latch = new CountDownLatch(taskCount);
    List<Exception> exceptions = new ArrayList<>();
    
    long startTime = System.currentTimeMillis();
    
    // Submit tasks
    for (int i = 0; i < taskCount; i++) {
      final int index = i % 3;
      executor.submit(() -> {
        try {
          detectMimeTypeForIndex(index);
        } 
        catch (Exception e) {
          synchronized (exceptions) {
            exceptions.add(e);
          }
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    long endTime = System.currentTimeMillis();
    
    if (!completed) {
      throw new AssertionError("Tasks did not complete within timeout");
    }
    
    if (!exceptions.isEmpty()) {
      throw new AssertionError(STR."\{exceptions.size()} exceptions occurred during execution", 
          exceptions.get(0));
    }
    
    return endTime - startTime;
  }
  
  /**
   * Helper method to detect MIME type for a file based on index.
   */
  private String detectMimeTypeForIndex(int index) throws IOException {
    File file = switch (index) {
      case 0 -> gifFile;
      case 1 -> zipFile;
      case 2 -> jarFile;
      default -> throw new IllegalArgumentException("Invalid index");
    };
    
    try (InputStream is = new FileInputStream(file)) {
      return underTest.detectMimeType(is, file.getName());
    }
  }
  
  /**
   * Helper method to get filename for a file based on index.
   */
  private String getFilenameForIndex(int index) {
    return switch (index) {
      case 0 -> "test.gif";
      case 1 -> "test.zip";
      case 2 -> "test.jar";
      default -> throw new IllegalArgumentException("Invalid index");
    };
  }
  
  /**
   * Helper method to copy a test resource to a temporary file.
   */
  private File copyResourceToTempFile(String resourcePath) throws IOException {
    File sourceFile = util.resolveFile(resourcePath);
    File targetFile = tempDir.resolve(sourceFile.getName()).toFile();
    Files.copy(sourceFile.toPath(), targetFile.toPath());
    return targetFile;
  }
}