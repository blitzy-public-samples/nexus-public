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
package org.sonatype.nexus.mime.internal;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.mime.MimeRulesSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DefaultMimeSupport} using Java 21 Virtual Threads.
 * 
 * This test validates that the MIME detection functionality works correctly
 * when executed with Virtual Threads, ensuring that I/O-bound operations
 * like detectMimeType() are efficient and don't cause thread pinning.
 */
@Tag("java21")
@Tag("virtualThread")
public class DefaultMimeSupportVirtualThreadTest
    extends TestSupport
{
  private DefaultMimeSupport underTest;
  
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;
  
  @TempDir
  Path tempDir;
  
  @BeforeEach
  void setUp() {
    underTest = new DefaultMimeSupport();
    
    // Create executors for testing
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
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
   * Tests basic MIME type detection with Virtual Threads.
   */
  @Test
  @DisplayName("Basic MIME type detection works with Virtual Threads")
  void basicMimeTypeDetectionWithVirtualThreads() throws Exception {
    // Create test files
    File gifFile = createTestFile("test.gif", new byte[] {0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00});
    File zipFile = createTestFile("test.zip", new byte[] {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00});
    
    // Test detection using virtual threads via CompletableFuture
    CompletableFuture<String> gifFuture = CompletableFuture.supplyAsync(() -> {
      try (InputStream is = new FileInputStream(gifFile)) {
        return underTest.detectMimeType(is, gifFile.getName());
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }, virtualThreadExecutor);
    
    CompletableFuture<String> zipFuture = CompletableFuture.supplyAsync(() -> {
      try (InputStream is = new FileInputStream(zipFile)) {
        return underTest.detectMimeType(is, zipFile.getName());
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }, virtualThreadExecutor);
    
    // Wait for both operations to complete
    CompletableFuture.allOf(gifFuture, zipFuture).join();
    
    // Verify results
    assertAll(
        () -> assertEquals("image/gif", gifFuture.get(), "GIF file should be detected correctly"),
        () -> assertEquals("application/zip", zipFuture.get(), "ZIP file should be detected correctly")
    );
  }
  
  /**
   * Tests concurrent MIME type detection with many Virtual Threads.
   */
  @Test
  @DisplayName("Concurrent MIME type detection with many Virtual Threads")
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void concurrentMimeTypeDetectionWithManyVirtualThreads() throws Exception {
    // Create test files of different types
    File gifFile = createTestFile("concurrent.gif", new byte[] {0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00});
    File zipFile = createTestFile("concurrent.zip", new byte[] {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00});
    File xmlFile = createTestFile("concurrent.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><root></root>".getBytes());
    
    // Files to test with
    File[] testFiles = {gifFile, zipFile, xmlFile};
    
    // Expected MIME types
    String[] expectedMimeTypes = {"image/gif", "application/zip", "application/xml"};
    
    // Run many concurrent operations
    int taskCount = 1000;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Submit tasks
    for (int i = 0; i < taskCount; i++) {
      final int index = i % testFiles.length;
      CompletableFuture.runAsync(() -> {
        try (InputStream is = new FileInputStream(testFiles[index])) {
          String mimeType = underTest.detectMimeType(is, testFiles[index].getName());
          if (!expectedMimeTypes[index].equals(mimeType)) {
            errorCount.incrementAndGet();
          }
        } catch (Exception e) {
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      }, virtualThreadExecutor);
    }
    
    // Wait for all tasks to complete
    assertTrue(latch.await(20, TimeUnit.SECONDS), "All tasks should complete within timeout");
    
    // Verify no errors occurred
    assertEquals(0, errorCount.get(), "All MIME type detections should succeed");
  }
  
  /**
   * Tests that guessing MIME types from paths works correctly with Virtual Threads.
   */
  @Test
  @DisplayName("Guessing MIME types from paths works with Virtual Threads")
  void guessMimeTypeFromPathWithVirtualThreads() throws Exception {
    // Create a list of paths to test
    List<String> paths = List.of(
        "/some/path/artifact.pom",
        "/some/path/artifact.jar",
        "/some/path/maven-metadata.xml",
        "/some/path/some.tar.gz",
        "/some/path/some.zip"
    );
    
    // Expected MIME types
    List<String> expectedMimeTypes = List.of(
        "application/xml",
        "application/java-archive",
        "application/xml",
        "application/x-gzip",
        "application/zip"
    );
    
    // Run concurrent guessing operations
    List<CompletableFuture<String>> futures = new ArrayList<>();
    
    for (int i = 0; i < paths.size(); i++) {
      final String path = paths.get(i);
      futures.add(CompletableFuture.supplyAsync(
          () -> underTest.guessMimeTypeFromPath(path),
          virtualThreadExecutor
      ));
    }
    
    // Wait for all operations to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    
    // Verify results
    for (int i = 0; i < paths.size(); i++) {
      assertEquals(expectedMimeTypes.get(i), futures.get(i).get(),
          "MIME type for " + paths.get(i) + " should be detected correctly");
    }
  }
  
  /**
   * Tests custom MIME rules source with Virtual Threads.
   */
  @Test
  @DisplayName("Custom MIME rules source works with Virtual Threads")
  void customMimeRulesSourceWithVirtualThreads() throws Exception {
    // Create a custom MimeRulesSource
    MimeRulesSource customSource = path -> {
      if (path.endsWith(".custom")) {
        return new org.sonatype.nexus.mime.MimeRule(true, "application/custom");
      }
      return null;
    };
    
    // Test with multiple threads
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    for (int i = 0; i < taskCount; i++) {
      CompletableFuture.runAsync(() -> {
        try {
          String mimeType = underTest.guessMimeTypeFromPath("/path/to/file.custom", customSource);
          if (!"application/custom".equals(mimeType)) {
            errorCount.incrementAndGet();
          }
        } catch (Exception e) {
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      }, virtualThreadExecutor);
    }
    
    // Wait for all tasks to complete
    assertTrue(latch.await(10, TimeUnit.SECONDS), "All tasks should complete within timeout");
    
    // Verify no errors occurred
    assertEquals(0, errorCount.get(), "All MIME type guesses should succeed");
  }
  
  /**
   * Compares performance between Virtual Threads and Platform Threads for MIME detection.
   */
  @Test
  @DisplayName("Performance comparison between Virtual Threads and Platform Threads")
  void performanceComparisonBetweenVirtualAndPlatformThreads() throws Exception {
    // Create a larger test file for more realistic I/O operations
    byte[] largeContent = new byte[1024 * 1024]; // 1MB
    // Fill with some pattern
    for (int i = 0; i < largeContent.length; i++) {
      largeContent[i] = (byte)(i % 256);
    }
    // Add ZIP header at the beginning
    largeContent[0] = 0x50;
    largeContent[1] = 0x4B;
    largeContent[2] = 0x03;
    largeContent[3] = 0x04;
    
    File largeFile = createTestFile("large.zip", largeContent);
    
    // Number of concurrent operations
    int concurrentTasks = 100;
    
    // Test with Virtual Threads
    long virtualThreadTime = measureExecutionTime(() -> {
      runConcurrentMimeDetection(largeFile, concurrentTasks, virtualThreadExecutor);
    });
    
    // Test with Platform Threads
    long platformThreadTime = measureExecutionTime(() -> {
      runConcurrentMimeDetection(largeFile, concurrentTasks, platformThreadExecutor);
    });
    
    // Log the results
    log.info("Virtual Thread execution time: {} ms", virtualThreadTime);
    log.info("Platform Thread execution time: {} ms", platformThreadTime);
    
    // Virtual Threads should generally be more efficient for I/O-bound operations
    // but we can't guarantee it in all environments, so we just log the results
    // and make a basic assertion that both completed successfully
    assertThat(virtualThreadTime, greaterThan(0L));
    assertThat(platformThreadTime, greaterThan(0L));
    
    // In most cases, Virtual Threads should be faster or at least comparable
    // This is a soft assertion that can be adjusted if needed
    assertThat("Virtual Threads should be efficient for I/O operations",
        virtualThreadTime <= platformThreadTime * 1.5, is(true));
  }
  
  /**
   * Tests that no thread pinning occurs during MIME detection.
   */
  @Test
  @DisplayName("No thread pinning occurs during MIME detection")
  void noThreadPinningDuringMimeDetection() throws Exception {
    // Create a test file
    File testFile = createTestFile("pinning-test.zip", new byte[] {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00});
    
    // Run many concurrent operations to increase chances of detecting pinning
    int taskCount = 500;
    CountDownLatch latch = new CountDownLatch(taskCount);
    
    // Track thread execution times to detect potential pinning
    List<Long> executionTimes = new ArrayList<>(taskCount);
    Object lock = new Object();
    
    for (int i = 0; i < taskCount; i++) {
      CompletableFuture.runAsync(() -> {
        long startTime = System.nanoTime();
        try (InputStream is = new FileInputStream(testFile)) {
          String mimeType = underTest.detectMimeType(is, testFile.getName());
          assertThat(mimeType, equalTo("application/zip"));
          
          long executionTime = System.nanoTime() - startTime;
          synchronized (lock) {
            executionTimes.add(executionTime);
          }
        } catch (IOException e) {
          throw new RuntimeException(e);
        } finally {
          latch.countDown();
        }
      }, virtualThreadExecutor);
    }
    
    // Wait for all tasks to complete
    assertTrue(latch.await(20, TimeUnit.SECONDS), "All tasks should complete within timeout");
    
    // Calculate statistics to detect potential pinning
    // If there's significant thread pinning, we'd see a few very long execution times
    long sum = 0;
    long max = 0;
    for (long time : executionTimes) {
      sum += time;
      max = Math.max(max, time);
    }
    long avg = sum / executionTimes.size();
    
    // Log statistics
    log.info("Average execution time: {} ns", avg);
    log.info("Maximum execution time: {} ns", max);
    
    // If max is significantly higher than average, it might indicate pinning
    // This is a heuristic and might need adjustment based on the environment
    assertThat("Maximum execution time should not be extremely high compared to average",
        max < avg * 20, is(true));
  }
  
  /**
   * Tests that MIME detection works correctly with large files using Virtual Threads.
   */
  @Test
  @DisplayName("MIME detection works with large files using Virtual Threads")
  void mimeDetectionWithLargeFilesUsingVirtualThreads() throws Exception {
    // Create a large test file (10MB)
    byte[] largeContent = new byte[10 * 1024 * 1024];
    // Add XML header at the beginning
    String xmlHeader = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><root>";
    System.arraycopy(xmlHeader.getBytes(), 0, largeContent, 0, xmlHeader.length());
    // Add closing tag at the end
    String xmlFooter = "</root>";
    System.arraycopy(xmlFooter.getBytes(), 0, largeContent, largeContent.length - xmlFooter.length(), xmlFooter.length());
    
    File largeXmlFile = createTestFile("large.xml", largeContent);
    
    // Detect MIME type using a virtual thread
    String mimeType = CompletableFuture.supplyAsync(() -> {
      try (InputStream is = new FileInputStream(largeXmlFile)) {
        return underTest.detectMimeType(is, largeXmlFile.getName());
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }, virtualThreadExecutor).get(30, TimeUnit.SECONDS);
    
    // Verify correct detection
    assertEquals("application/xml", mimeType, "Large XML file should be detected correctly");
  }
  
  /**
   * Tests that multiple MIME type detection operations can run in parallel without issues.
   */
  @Test
  @DisplayName("Multiple MIME type detection operations can run in parallel")
  void multipleMimeTypeDetectionOperationsInParallel() throws Exception {
    // Create different test files
    File gifFile = createTestFile("parallel.gif", new byte[] {0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00});
    File zipFile = createTestFile("parallel.zip", new byte[] {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00});
    File xmlFile = createTestFile("parallel.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><root></root>".getBytes());
    File pdfFile = createTestFile("parallel.pdf", new byte[] {0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E});
    
    // Run detection operations in parallel
    CompletableFuture<String> gifFuture = detectMimeTypeAsync(gifFile);
    CompletableFuture<String> zipFuture = detectMimeTypeAsync(zipFile);
    CompletableFuture<String> xmlFuture = detectMimeTypeAsync(xmlFile);
    CompletableFuture<String> pdfFuture = detectMimeTypeAsync(pdfFile);
    
    // Wait for all operations to complete
    CompletableFuture.allOf(gifFuture, zipFuture, xmlFuture, pdfFuture).join();
    
    // Verify results
    assertAll(
        () -> assertEquals("image/gif", gifFuture.get(), "GIF file should be detected correctly"),
        () -> assertEquals("application/zip", zipFuture.get(), "ZIP file should be detected correctly"),
        () -> assertEquals("application/xml", xmlFuture.get(), "XML file should be detected correctly"),
        () -> assertEquals("application/pdf", pdfFuture.get(), "PDF file should be detected correctly")
    );
  }
  
  /**
   * Helper method to create a test file with specified content.
   */
  private File createTestFile(String filename, byte[] content) throws IOException {
    Path filePath = tempDir.resolve(filename);
    Files.write(filePath, content);
    return filePath.toFile();
  }
  
  /**
   * Helper method to run concurrent MIME detection operations.
   */
  private void runConcurrentMimeDetection(File file, int taskCount, ExecutorService executor) throws Exception {
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    for (int i = 0; i < taskCount; i++) {
      CompletableFuture.runAsync(() -> {
        try (InputStream is = new FileInputStream(file)) {
          String mimeType = underTest.detectMimeType(is, file.getName());
          if (!"application/zip".equals(mimeType)) {
            errorCount.incrementAndGet();
          }
        } catch (Exception e) {
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      }, executor);
    }
    
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    assertTrue(completed, "All tasks should complete within timeout");
    assertEquals(0, errorCount.get(), "All MIME type detections should succeed");
  }
  
  /**
   * Helper method to measure execution time of a task.
   */
  private long measureExecutionTime(Runnable task) {
    long startTime = System.currentTimeMillis();
    task.run();
    return System.currentTimeMillis() - startTime;
  }
  
  /**
   * Helper method to detect MIME type asynchronously.
   */
  private CompletableFuture<String> detectMimeTypeAsync(File file) {
    return CompletableFuture.supplyAsync(() -> {
      try (InputStream is = new FileInputStream(file)) {
        return underTest.detectMimeType(is, file.getName());
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }, virtualThreadExecutor);
  }
}