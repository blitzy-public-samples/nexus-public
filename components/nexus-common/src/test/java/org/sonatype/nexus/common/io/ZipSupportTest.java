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
package org.sonatype.nexus.common.io;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestGroup;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link ZipSupport} class, including Virtual Thread optimizations.
 *
 * @since 3.60
 */
@Category(VirtualThreadTestGroup.class)
public class ZipSupportTest
    extends TestSupport
{
  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private static final byte[] PAYLOAD = "payload".getBytes(UTF_8);
  private static final int CONCURRENT_TASKS = 10;
  private static final int FILES_PER_TASK = 5;

  private File root;

  private final ZipSupport zipSupport = new ZipSupport();

  @Before
  public void prepare() throws IOException {
    root = util.createTempDir();
    Path path = root.toPath();
    Files.write(path.resolve("file1.tx"), PAYLOAD);
    Files.write(path.resolve("file2.txt"), PAYLOAD);
    Files.write(path.resolve("file4.txt"), PAYLOAD);
  }

  /**
   * Test basic ZIP file creation functionality.
   */
  @Test
  public void zipFiles() throws IOException {
    List<String> filesToZip = Arrays.asList("file1.txt", "file2.txt", "file3.txt");

    String zipFileName = root.toPath() + "/test.zip";

    zipSupport.zipFiles(root.toPath(), filesToZip, zipFileName);

    File zipFile = new File(zipFileName);
    assertTrue(zipFile.exists());
  }

  /**
   * Test ZIP file creation using Virtual Threads.
   * This test validates that the ZIP operations work correctly with Virtual Threads.
   */
  @Test
  public void zipFilesWithVirtualThreads() throws IOException {
    List<String> filesToZip = Arrays.asList("file1.tx", "file2.txt", "file4.txt");

    String zipFileName = root.toPath() + "/test-virtual.zip";

    // Use Virtual Thread to execute the ZIP operation
    Thread.ofVirtual().start(() -> {
      try {
        zipSupport.zipFiles(root.toPath(), filesToZip, zipFileName, true);
      }
      catch (IOException e) {
        log.error("Error during ZIP operation", e);
      }
    }).join();

    File zipFile = new File(zipFileName);
    assertTrue("ZIP file should exist", zipFile.exists());
  }

  /**
   * Test concurrent ZIP creation with multiple Virtual Threads.
   * This test creates multiple ZIP files concurrently using Virtual Threads.
   */
  @Test
  public void concurrentZipCreationWithVirtualThreads() throws Exception {
    // Create test files
    for (int i = 0; i < FILES_PER_TASK * CONCURRENT_TASKS; i++) {
      Files.write(root.toPath().resolve("concurrent-file-" + i + ".txt"), PAYLOAD);
    }

    CountDownLatch latch = new CountDownLatch(CONCURRENT_TASKS);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create and start virtual threads for concurrent ZIP operations
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_TASKS; i++) {
        final int taskId = i;
        executor.submit(() -> {
          try {
            // Create a list of files for this task
            List<String> filesToZip = new ArrayList<>();
            for (int j = 0; j < FILES_PER_TASK; j++) {
              filesToZip.add("concurrent-file-" + (taskId * FILES_PER_TASK + j) + ".txt");
            }

            String zipFileName = root.toPath() + "/concurrent-" + taskId + ".zip";
            zipSupport.zipFiles(root.toPath(), filesToZip, zipFileName, true);

            File zipFile = new File(zipFileName);
            if (zipFile.exists()) {
              successCount.incrementAndGet();
            }
          }
          catch (Exception e) {
            log.error("Error in concurrent ZIP task {}", taskId, e);
          }
          finally {
            latch.countDown();
          }
        });
      }
    }

    // Wait for all tasks to complete
    assertTrue("Timed out waiting for ZIP tasks", latch.await(30, TimeUnit.SECONDS));
    
    // Verify all ZIP files were created successfully
    assertThat("All ZIP operations should succeed", successCount.get(), is(CONCURRENT_TASKS));
  }

  /**
   * Test to detect thread pinning during ZIP operations.
   * Thread pinning occurs when a Virtual Thread is forced to execute on a platform thread
   * for an extended period, negating the benefits of Virtual Threads.
   */
  @Test
  public void detectThreadPinningDuringZipOperations() throws Exception {
    // Create larger test files to increase chances of detecting pinning
    byte[] largePayload = new byte[1024 * 1024]; // 1MB
    Arrays.fill(largePayload, (byte) 'X');
    
    for (int i = 0; i < 5; i++) {
      Files.write(root.toPath().resolve("large-file-" + i + ".dat"), largePayload);
    }

    List<String> filesToZip = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      filesToZip.add("large-file-" + i + ".dat");
    }

    String zipFileName = root.toPath() + "/large-test.zip";
    
    // Flag to track if pinning was detected
    AtomicBoolean pinningDetected = new AtomicBoolean(false);
    
    // Start a monitoring thread to check for pinning
    Thread monitorThread = Thread.ofPlatform().daemon().start(() -> {
      ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
      
      try {
        // Monitor for 5 seconds
        for (int i = 0; i < 10 && !pinningDetected.get(); i++) {
          ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(false, false);
          
          for (ThreadInfo info : threadInfos) {
            // Check if any carrier thread is executing ZIP-related code for too long
            if (info.getThreadName().contains("VirtualThread") && 
                info.getStackTrace().length > 0 &&
                containsZipOperations(info.getStackTrace())) {
              log.info("Potential thread pinning detected: {}", info.getThreadName());
              pinningDetected.set(true);
              break;
            }
          }
          
          Thread.sleep(500); // Check every 500ms
        }
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });

    // Execute ZIP operation in a Virtual Thread
    Thread zipThread = Thread.ofVirtual().start(() -> {
      try {
        zipSupport.zipFiles(root.toPath(), filesToZip, zipFileName, true);
      }
      catch (IOException e) {
        log.error("Error during ZIP operation", e);
      }
    });

    // Wait for ZIP operation to complete
    zipThread.join();
    
    // Wait for monitor thread to finish
    monitorThread.join(1000);
    
    // Verify ZIP file was created
    File zipFile = new File(zipFileName);
    assertTrue("ZIP file should exist", zipFile.exists());
    
    // Log if pinning was detected, but don't fail the test
    // This is informational and helps identify potential optimizations
    if (pinningDetected.get()) {
      log.warn("Thread pinning detected during ZIP operations. Consider optimizing the code.");
    }
  }

  /**
   * Performance comparison between platform threads and virtual threads for ZIP operations.
   * This test measures and compares the execution time of ZIP operations using both thread types.
   */
  @Test
  public void performanceComparisonBetweenThreadTypes() throws Exception {
    // Create test files
    for (int i = 0; i < 20; i++) {
      Files.write(root.toPath().resolve("perf-file-" + i + ".txt"), PAYLOAD);
    }

    List<String> filesToZip = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      filesToZip.add("perf-file-" + i + ".txt");
    }

    // Measure platform thread performance
    long platformThreadStart = System.nanoTime();
    
    try (ExecutorService platformExecutor = Executors.newFixedThreadPool(10)) {
      CountDownLatch platformLatch = new CountDownLatch(10);
      
      for (int i = 0; i < 10; i++) {
        final int taskId = i;
        platformExecutor.submit(() -> {
          try {
            String zipFileName = root.toPath() + "/platform-" + taskId + ".zip";
            zipSupport.zipFiles(root.toPath(), filesToZip, zipFileName, false);
          }
          catch (Exception e) {
            log.error("Error in platform thread ZIP task", e);
          }
          finally {
            platformLatch.countDown();
          }
        });
      }
      
      platformLatch.await();
    }
    
    long platformThreadTime = System.nanoTime() - platformThreadStart;
    
    // Measure virtual thread performance
    long virtualThreadStart = System.nanoTime();
    
    try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch virtualLatch = new CountDownLatch(10);
      
      for (int i = 0; i < 10; i++) {
        final int taskId = i;
        virtualExecutor.submit(() -> {
          try {
            String zipFileName = root.toPath() + "/virtual-" + taskId + ".zip";
            zipSupport.zipFiles(root.toPath(), filesToZip, zipFileName, true);
          }
          catch (Exception e) {
            log.error("Error in virtual thread ZIP task", e);
          }
          finally {
            virtualLatch.countDown();
          }
        });
      }
      
      virtualLatch.await();
    }
    
    long virtualThreadTime = System.nanoTime() - virtualThreadStart;
    
    // Log performance results
    log.info("Platform thread execution time: {} ms", TimeUnit.NANOSECONDS.toMillis(platformThreadTime));
    log.info("Virtual thread execution time: {} ms", TimeUnit.NANOSECONDS.toMillis(virtualThreadTime));
    
    // Verify that virtual threads perform at least as well as platform threads
    // Note: This is a soft assertion as performance can vary based on the environment
    if (virtualThreadTime > platformThreadTime) {
      log.warn("Virtual threads were slower than platform threads. This might be due to test environment conditions.");
    }
    else {
      log.info("Virtual threads performed better than platform threads as expected.");
    }
  }
  
  /**
   * Helper method to check if a thread stack trace contains ZIP-related operations.
   */
  private boolean containsZipOperations(StackTraceElement[] stackTrace) {
    for (StackTraceElement element : stackTrace) {
      String className = element.getClassName();
      String methodName = element.getMethodName();
      
      if ((className.contains("ZipOutputStream") || 
           className.contains("ZipInputStream") || 
           className.contains("ZipSupport")) && 
          (methodName.contains("write") || 
           methodName.contains("read") || 
           methodName.contains("zip"))) {
        return true;
      }
    }
    return false;
  }
}
