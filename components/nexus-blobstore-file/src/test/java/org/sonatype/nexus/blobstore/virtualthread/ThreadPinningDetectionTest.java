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
package org.sonatype.nexus.blobstore.virtualthread;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.blobstore.MockBlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreException;
import org.sonatype.nexus.blobstore.file.FileBlobDeletionIndex;
import org.sonatype.nexus.blobstore.file.FileBlobStore;
import org.sonatype.nexus.blobstore.file.internal.FileOperations;
import org.sonatype.nexus.blobstore.file.internal.SimpleFileOperations;
import org.sonatype.nexus.blobstore.file.internal.datastore.metrics.DatastoreFileBlobStoreMetricsService;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaUsageChecker;
import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.common.log.DryRunPrefix;
import org.sonatype.nexus.common.node.NodeAccess;

import com.google.common.collect.ImmutableMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.api.BlobStore.BLOB_NAME_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.CREATED_BY_HEADER;

/**
 * Tests to detect and validate thread pinning issues when using Virtual Threads with FileBlobStore operations.
 * <p>
 * Thread pinning occurs when a virtual thread gets "stuck" to its carrier thread (platform thread),
 * preventing the carrier thread from executing other virtual threads. This happens primarily in two scenarios:
 * <ul>
 *   <li>When using synchronized blocks/methods</li>
 *   <li>When executing native methods/foreign functions</li>
 * </ul>
 * <p>
 * This test class monitors operations that can cause carrier thread pinning, analyzes stack traces for pinning events,
 * and ensures the implementation avoids problematic patterns.
 *
 * @since 3.60
 */
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
public class ThreadPinningDetectionTest
    extends TestSupport
{
  private static final int TEST_DATA_LENGTH = 1024;
  private static final int CONCURRENT_OPERATIONS = 100;
  private static final int TIMEOUT_SECONDS = 30;
  private static final String THREAD_PINNING_MARKER = "reason:MONITOR";

  private static final ImmutableMap<String, String> TEST_HEADERS = ImmutableMap.of(
      CREATED_BY_HEADER, "test",
      BLOB_NAME_HEADER, "test/pinning-test.bin");

  @Mock
  private NodeAccess nodeAccess;

  @Mock
  private ApplicationDirectories applicationDirectories;

  @Mock
  private DryRunPrefix dryRunPrefix;

  @Mock
  private BlobStoreQuotaUsageChecker quotaUsageChecker;

  @Mock
  private FileBlobDeletionIndex deletionIndex;

  @Mock
  private DatastoreFileBlobStoreMetricsService metricsService;

  private Path tempDir;
  private FileBlobStore underTest;
  private FileOperations fileOperations;
  private final Map<String, List<String>> pinnedThreadLogs = new ConcurrentHashMap<>();
  private final AtomicBoolean pinnedThreadDetected = new AtomicBoolean(false);

  /**
   * Set up the test environment with a FileBlobStore instance and configure thread pinning detection.
   */
  @Before
  public void setUp() throws Exception {
    // Configure system property to detect thread pinning
    System.setProperty("jdk.tracePinnedThreads", "full");

    // Redirect System.err to capture pinning logs
    redirectSystemErr();

    // Set up mocks
    when(nodeAccess.getId()).thenReturn("test-node");
    when(dryRunPrefix.get()).thenReturn("");
    tempDir = util.createTempDir().toPath();
    when(applicationDirectories.getWorkDirectory(anyString())).thenReturn(tempDir.toFile());

    // Create a real FileOperations instance for actual file operations
    fileOperations = new SimpleFileOperations();

    // Create and initialize the FileBlobStore
    BlobStoreConfiguration config = new MockBlobStoreConfiguration();
    config.attributes(FileBlobStore.CONFIG_KEY).set(FileBlobStore.PATH_KEY, tempDir.toString());

    underTest = new FileBlobStore(
        tempDir,
        null, // BlobIdLocationResolver will be set by init
        fileOperations,
        metricsService,
        config,
        applicationDirectories,
        nodeAccess,
        dryRunPrefix,
        null, // BlobStoreReconciliationLogger
        0L,
        quotaUsageChecker,
        deletionIndex);

    underTest.init(config);
    underTest.start();
  }

  /**
   * Clean up after tests, restore System.err, and remove the thread pinning detection property.
   */
  @After
  public void tearDown() throws Exception {
    // Restore System.err
    restoreSystemErr();

    // Remove the thread pinning detection property
    System.clearProperty("jdk.tracePinnedThreads");

    // Stop the FileBlobStore
    if (underTest != null) {
      underTest.stop();
    }
  }

  /**
   * Test that basic blob operations (create, get, delete) don't cause thread pinning when executed concurrently
   * with virtual threads.
   */
  @Test
  public void basicOperationsShouldNotCauseThreadPinning() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      int taskCount = CONCURRENT_OPERATIONS;
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      List<BlobId> createdBlobIds = new ArrayList<>();

      // Create blobs concurrently using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            // Create a blob
            Blob blob = createRandomBlob();
            synchronized (createdBlobIds) {
              createdBlobIds.add(blob.getId());
            }
          }
          catch (Exception e) {
            log.error("Error creating blob", e);
            errorCount.incrementAndGet();
          }
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      assertThat("All blob creation tasks should complete in time",
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
      assertThat("No errors should occur during blob creation", errorCount.get(), is(0));

      // Check for thread pinning
      assertThat("Thread pinning should not be detected during blob creation",
          pinnedThreadDetected.get(), is(false));

      // Now get and delete the blobs concurrently
      CountDownLatch getLatch = new CountDownLatch(createdBlobIds.size());
      errorCount.set(0);

      for (BlobId blobId : createdBlobIds) {
        executor.submit(() -> {
          try {
            // Get the blob
            Blob blob = underTest.get(blobId);
            assertThat("Blob should exist", blob != null, is(true));

            // Delete the blob
            boolean deleted = underTest.delete(blobId, "test cleanup");
            assertThat("Blob should be deleted", deleted, is(true));
          }
          catch (Exception e) {
            log.error("Error getting or deleting blob", e);
            errorCount.incrementAndGet();
          }
          finally {
            getLatch.countDown();
          }
        });
      }

      // Wait for all get/delete tasks to complete
      assertThat("All blob get/delete tasks should complete in time",
          getLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
      assertThat("No errors should occur during blob get/delete", errorCount.get(), is(0));

      // Check for thread pinning
      assertThat("Thread pinning should not be detected during blob get/delete",
          pinnedThreadDetected.get(), is(false));
    }
    finally {
      executor.shutdown();
      executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
  }

  /**
   * Test that file operations that involve I/O don't cause thread pinning when executed concurrently
   * with virtual threads.
   */
  @Test
  public void fileOperationsShouldNotCauseThreadPinning() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      int taskCount = CONCURRENT_OPERATIONS;
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger errorCount = new AtomicInteger(0);

      // Create temporary files concurrently using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a temporary file
            Path tempFile = tempDir.resolve("test-file-" + index + ".tmp");
            Files.write(tempFile, generateRandomBytes());

            // Read the file
            byte[] content = Files.readAllBytes(tempFile);
            assertThat("File content should not be empty", content.length, is(greaterThan(0)));

            // Delete the file
            Files.delete(tempFile);
          }
          catch (Exception e) {
            log.error("Error in file operation", e);
            errorCount.incrementAndGet();
          }
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      assertThat("All file operation tasks should complete in time",
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
      assertThat("No errors should occur during file operations", errorCount.get(), is(0));

      // Check for thread pinning
      assertThat("Thread pinning should not be detected during file operations",
          pinnedThreadDetected.get(), is(false));
    }
    finally {
      executor.shutdown();
      executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
  }

  /**
   * Test that demonstrates how synchronized blocks can cause thread pinning with virtual threads.
   * This test intentionally creates a situation where thread pinning will occur to show what to avoid.
   */
  @Test
  public void demonstrateSynchronizedBlockCausingThreadPinning() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      // Create an object to synchronize on
      final Object lock = new Object();
      final AtomicInteger completedTasks = new AtomicInteger(0);
      final int taskCount = 10; // Using fewer tasks for this demonstration

      // Submit tasks that will cause thread pinning
      for (int i = 0; i < taskCount; i++) {
        final int taskId = i;
        executor.submit(() -> {
          try {
            // This synchronized block will cause thread pinning when the I/O operation is performed
            synchronized (lock) {
              // Perform an I/O operation inside the synchronized block - this will cause pinning
              Path tempFile = tempDir.resolve("pinning-demo-" + taskId + ".tmp");
              Files.write(tempFile, generateRandomBytes());
              
              // Sleep to make pinning more likely to be detected
              Thread.sleep(100);
              
              // Read the file (another I/O operation)
              byte[] content = Files.readAllBytes(tempFile);
              assertThat("File content should not be empty", content.length, is(greaterThan(0)));
              
              // Delete the file
              Files.delete(tempFile);
              
              completedTasks.incrementAndGet();
            }
          }
          catch (Exception e) {
            log.error("Error in synchronized block task", e);
          }
        });
      }

      // Wait for tasks to complete
      executor.shutdown();
      executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify all tasks completed
      assertThat("All tasks should complete", completedTasks.get(), is(taskCount));
      
      // Check if thread pinning was detected
      if (!pinnedThreadDetected.get()) {
        log.warn("Expected thread pinning was not detected. This could be due to timing or JVM implementation details.");
      }
      
      // Print pinning logs for demonstration purposes
      if (!pinnedThreadLogs.isEmpty()) {
        log.info("Thread pinning detected in the following threads:");
        pinnedThreadLogs.forEach((threadName, logs) -> {
          log.info("Thread: {}", threadName);
          logs.forEach(log -> log.info("  {}", log));
        });
      }
    }
    finally {
      if (!executor.isShutdown()) {
        executor.shutdown();
      }
    }
  }

  /**
   * Test that demonstrates how to avoid thread pinning by using ReentrantLock instead of synchronized blocks.
   */
  @Test
  public void demonstrateReentrantLockAvoidingThreadPinning() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      // Create a ReentrantLock instead of using synchronized
      final ReentrantLock lock = new ReentrantLock();
      final AtomicInteger completedTasks = new AtomicInteger(0);
      final int taskCount = 10;

      // Submit tasks that will avoid thread pinning
      for (int i = 0; i < taskCount; i++) {
        final int taskId = i;
        executor.submit(() -> {
          try {
            // Use ReentrantLock instead of synchronized
            lock.lock();
            try {
              // Initial setup inside the lock
              Path tempFile = tempDir.resolve("no-pinning-demo-" + taskId + ".tmp");
              
              // Release the lock before performing I/O operations
              lock.unlock();
              
              // Perform I/O operations outside the lock
              Files.write(tempFile, generateRandomBytes());
              Thread.sleep(100); // Simulate longer I/O
              byte[] content = Files.readAllBytes(tempFile);
              
              // Acquire the lock again for the final part if needed
              lock.lock();
              try {
                // Process results inside the lock if necessary
                assertThat("File content should not be empty", content.length, is(greaterThan(0)));
              }
              finally {
                lock.unlock();
              }
              
              // Clean up outside the lock
              Files.delete(tempFile);
              
              completedTasks.incrementAndGet();
            }
            finally {
              // Ensure the lock is released if still held
              if (lock.isHeldByCurrentThread()) {
                lock.unlock();
              }
            }
          }
          catch (Exception e) {
            log.error("Error in ReentrantLock task", e);
          }
        });
      }

      // Wait for tasks to complete
      executor.shutdown();
      executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify all tasks completed
      assertThat("All tasks should complete", completedTasks.get(), is(taskCount));
      
      // Check that no thread pinning was detected
      assertThat("Thread pinning should not be detected when using ReentrantLock",
          pinnedThreadDetected.get(), is(false));
    }
    finally {
      if (!executor.isShutdown()) {
        executor.shutdown();
      }
    }
  }

  /**
   * Test that compares the performance of synchronized blocks vs ReentrantLock with virtual threads.
   */
  @Test
  public void comparePerformanceSynchronizedVsReentrantLock() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    final int taskCount = 100;
    final int iterationsPerTask = 10;
    
    // Test with synchronized blocks
    long synchronizedTime = measureExecutionTime(taskCount, iterationsPerTask, virtualThreadFactory, true);
    
    // Test with ReentrantLock
    long reentrantLockTime = measureExecutionTime(taskCount, iterationsPerTask, virtualThreadFactory, false);
    
    log.info("Execution time with synchronized blocks: {} ms", synchronizedTime);
    log.info("Execution time with ReentrantLock: {} ms", reentrantLockTime);
    
    // ReentrantLock should generally be faster with virtual threads due to avoiding pinning
    assertThat("ReentrantLock should be faster than synchronized with virtual threads",
        reentrantLockTime, is(lessThan(synchronizedTime)));
  }

  /**
   * Measure execution time for concurrent tasks using either synchronized blocks or ReentrantLock.
   *
   * @param taskCount number of concurrent tasks
   * @param iterationsPerTask number of iterations per task
   * @param threadFactory thread factory to use
   * @param useSynchronized true to use synchronized blocks, false to use ReentrantLock
   * @return execution time in milliseconds
   */
  private long measureExecutionTime(
      int taskCount,
      int iterationsPerTask,
      ThreadFactory threadFactory,
      boolean useSynchronized) throws Exception
  {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    final Object lock = new Object();
    final ReentrantLock reentrantLock = new ReentrantLock();
    final CountDownLatch latch = new CountDownLatch(taskCount);
    
    long startTime = System.currentTimeMillis();
    
    try {
      for (int i = 0; i < taskCount; i++) {
        final int taskId = i;
        executor.submit(() -> {
          try {
            for (int j = 0; j < iterationsPerTask; j++) {
              if (useSynchronized) {
                performOperationWithSynchronized(lock, taskId, j);
              }
              else {
                performOperationWithReentrantLock(reentrantLock, taskId, j);
              }
            }
          }
          catch (Exception e) {
            log.error("Error in performance test task", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
    finally {
      executor.shutdown();
      executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
    
    return System.currentTimeMillis() - startTime;
  }

  /**
   * Perform an operation using synchronized blocks.
   */
  private void performOperationWithSynchronized(Object lock, int taskId, int iteration) throws Exception {
    synchronized (lock) {
      // Prepare operation inside synchronized block
      Path tempFile = tempDir.resolve(String.format("sync-perf-%d-%d.tmp", taskId, iteration));
      
      // Perform I/O operation inside synchronized block - this will cause pinning
      Files.write(tempFile, generateRandomBytes(256)); // Smaller data for performance test
      
      // Read the file
      byte[] content = Files.readAllBytes(tempFile);
      
      // Delete the file
      Files.delete(tempFile);
    }
  }

  /**
   * Perform an operation using ReentrantLock, releasing the lock during I/O operations.
   */
  private void performOperationWithReentrantLock(ReentrantLock lock, int taskId, int iteration) throws Exception {
    lock.lock();
    Path tempFile = null;
    try {
      // Prepare operation inside lock
      tempFile = tempDir.resolve(String.format("lock-perf-%d-%d.tmp", taskId, iteration));
    }
    finally {
      lock.unlock();
    }
    
    // Perform I/O operations outside the lock
    Files.write(tempFile, generateRandomBytes(256)); // Smaller data for performance test
    byte[] content = Files.readAllBytes(tempFile);
    Files.delete(tempFile);
    
    // Acquire lock again if needed for final processing
    lock.lock();
    try {
      // Process results if needed
    }
    finally {
      lock.unlock();
    }
  }

  /**
   * Create a random blob in the blob store.
   */
  private Blob createRandomBlob() throws IOException {
    byte[] data = generateRandomBytes();
    return underTest.create(new ByteArrayInputStream(data), TEST_HEADERS);
  }

  /**
   * Generate random bytes for test data.
   */
  private byte[] generateRandomBytes() {
    return generateRandomBytes(TEST_DATA_LENGTH);
  }

  /**
   * Generate random bytes of specified length for test data.
   */
  private byte[] generateRandomBytes(int length) {
    byte[] data = new byte[length];
    new Random().nextBytes(data);
    return data;
  }

  /**
   * Redirect System.err to capture thread pinning logs.
   */
  private void redirectSystemErr() {
    // Create a custom PrintStream that captures pinning logs
    System.setErr(new java.io.PrintStream(System.err) {
      @Override
      public void println(String x) {
        super.println(x);
        if (x != null && x.contains(THREAD_PINNING_MARKER)) {
          // Extract thread name from the log
          String threadName = extractThreadName(x);
          if (threadName != null) {
            pinnedThreadLogs.computeIfAbsent(threadName, k -> new ArrayList<>()).add(x);
            pinnedThreadDetected.set(true);
          }
        }
      }
    });
  }

  /**
   * Extract thread name from a pinning log message.
   */
  private String extractThreadName(String logMessage) {
    // Example log format: VirtualThread[#20]/runnable@ForkJoinPool-1-worker-1
    if (logMessage != null && logMessage.startsWith("VirtualThread[")) {
      int endIndex = logMessage.indexOf(']');
      if (endIndex > 0) {
        return logMessage.substring(0, endIndex + 1);
      }
    }
    return null;
  }

  /**
   * Restore the original System.err.
   */
  private void restoreSystemErr() {
    // Reset System.err to its original state
    System.setErr(System.err);
  }
}