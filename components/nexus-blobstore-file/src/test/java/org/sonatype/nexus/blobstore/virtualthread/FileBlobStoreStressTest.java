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
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.BlobStoreReconciliationLogger;
import org.sonatype.nexus.blobstore.DefaultBlobIdLocationResolver;
import org.sonatype.nexus.blobstore.MetricsInputStream;
import org.sonatype.nexus.blobstore.MockBlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobMetrics;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreException;
import org.sonatype.nexus.blobstore.file.FileBlobDeletionIndex;
import org.sonatype.nexus.blobstore.file.FileBlobStore;
import org.sonatype.nexus.blobstore.file.internal.FileOperations;
import org.sonatype.nexus.blobstore.file.internal.SimpleFileOperations;
import org.sonatype.nexus.blobstore.file.internal.datastore.metrics.DatastoreFileBlobStoreMetricsService;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaService;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaUsageChecker;
import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.common.log.DryRunPrefix;
import org.sonatype.nexus.common.node.NodeAccess;
import org.sonatype.nexus.scheduling.internal.PeriodicJobServiceImpl;

import com.google.common.base.Objects;
import com.google.common.io.ByteStreams;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.io.ByteStreams.nullOutputStream;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.api.BlobStore.BLOB_NAME_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.CREATED_BY_HEADER;

/**
 * High-load stress test for {@link FileBlobStore} using Java 21 Virtual Threads.
 * <p>
 * This test validates FileBlobStore behavior under extreme concurrency conditions
 * by creating thousands of virtual threads to perform parallel operations - creating,
 * retrieving, and deleting blobs simultaneously. It verifies that the system maintains
 * correctness and stability under load while efficiently utilizing resources.
 * <p>
 * The test specifically verifies that FileBlobStore can handle the dramatically increased
 * concurrency enabled by Java 21 Virtual Threads while preventing resource exhaustion
 * and maintaining data integrity.
 */
public class FileBlobStoreStressTest
    extends TestSupport
{
  public static final int VIRTUAL_THREAD_COUNT = 5000;
  public static final int PLATFORM_THREAD_COUNT = 500;
  public static final int BLOB_MAX_SIZE_BYTES = 100_000;
  public static final int TEST_DURATION_SECONDS = 30;
  public static final int QUOTA_CHECK_INTERVAL = 1;

  public static final com.google.common.collect.ImmutableMap<String, String> TEST_HEADERS = 
      com.google.common.collect.ImmutableMap.of(
          CREATED_BY_HEADER, "test",
          BLOB_NAME_HEADER, "test/randomData.bin");

  private FileBlobStore underTest;

  @Mock
  private DatastoreFileBlobStoreMetricsService metricsStore;

  private BlobStoreQuotaUsageChecker blobStoreQuotaUsageChecker;

  @Mock
  private FileBlobDeletionIndex fileBlobDeletionIndex;

  @Mock
  NodeAccess nodeAccess;

  @Mock
  DryRunPrefix dryRunPrefix;

  @Mock
  BlobStoreQuotaService quotaService;

  @Mock
  FileOperations fileOperations;

  @Mock
  private BlobStoreReconciliationLogger reconciliationLogger;

  private final Random random = new Random();
  private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();

  @Before
  public void setUp() throws Exception {
    Path root = util.createTempDir().toPath();
    Path content = root.resolve("content");

    when(nodeAccess.getId()).thenReturn(UUID.randomUUID().toString());
    when(dryRunPrefix.get()).thenReturn("");

    ApplicationDirectories applicationDirectories = mock(ApplicationDirectories.class);
    when(applicationDirectories.getWorkDirectory(anyString())).thenReturn(root.toFile());

    final BlobStoreConfiguration config = new MockBlobStoreConfiguration();
    config.attributes(FileBlobStore.CONFIG_KEY).set(FileBlobStore.PATH_KEY, root.toString());

    blobStoreQuotaUsageChecker = new BlobStoreQuotaUsageChecker(
        new PeriodicJobServiceImpl(), QUOTA_CHECK_INTERVAL, quotaService);

    this.underTest = new FileBlobStore(content, new DefaultBlobIdLocationResolver(true), new SimpleFileOperations(),
        metricsStore, config, applicationDirectories, nodeAccess, dryRunPrefix, reconciliationLogger, 0L,
        blobStoreQuotaUsageChecker, fileBlobDeletionIndex);
    underTest.start();
  }

  @After
  public void tearDown() throws Exception {
    if (underTest != null) {
      underTest.stop();
    }
  }

  /**
   * Tests FileBlobStore under extreme load using thousands of virtual threads.
   * This test creates, reads, and deletes blobs concurrently to verify stability
   * and correctness under high concurrency conditions.
   */
  @Test
  public void testVirtualThreadStress() throws Exception {
    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      runStressTest(executor, VIRTUAL_THREAD_COUNT, "Virtual Thread");
    } finally {
      executor.shutdown();
      assertTrue("Executor did not terminate in time", 
          executor.awaitTermination(60, TimeUnit.SECONDS));
    }
  }

  /**
   * Tests FileBlobStore under load using platform threads for comparison.
   * This test uses a smaller number of threads as platform threads are more resource-intensive.
   */
  @Test
  public void testPlatformThreadStress() throws Exception {
    // Create a platform thread executor with a fixed thread pool
    ExecutorService executor = Executors.newFixedThreadPool(PLATFORM_THREAD_COUNT);

    try {
      runStressTest(executor, PLATFORM_THREAD_COUNT, "Platform Thread");
    } finally {
      executor.shutdown();
      assertTrue("Executor did not terminate in time", 
          executor.awaitTermination(60, TimeUnit.SECONDS));
    }
  }

  /**
   * Runs a stress test on the FileBlobStore using the provided executor service.
   *
   * @param executor The executor service to use for concurrent operations
   * @param threadCount The number of threads to use
   * @param testName A descriptive name for the test for logging purposes
   */
  private void runStressTest(ExecutorService executor, int threadCount, String testName) throws Exception {
    log.info("Starting {} stress test with {} threads", testName, threadCount);

    // Shared state for tracking operations
    final Queue<BlobId> blobIdsInTheStore = new ConcurrentLinkedDeque<>();
    final Set<BlobId> deletedIds = ConcurrentHashMap.newKeySet();
    final AtomicInteger createCount = new AtomicInteger(0);
    final AtomicInteger readCount = new AtomicInteger(0);
    final AtomicInteger deleteCount = new AtomicInteger(0);
    final AtomicInteger errorCount = new AtomicInteger(0);
    final AtomicBoolean running = new AtomicBoolean(true);
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(threadCount);

    // Memory tracking
    final long initialMemory = memoryMXBean.getHeapMemoryUsage().getUsed();
    final AtomicLong peakMemory = new AtomicLong(initialMemory);

    // Create tasks for different operations
    List<Runnable> tasks = new ArrayList<>();

    // Creator tasks - create new blobs
    for (int i = 0; i < threadCount / 5; i++) {
      tasks.add(() -> {
        try {
          startLatch.await();
          while (running.get()) {
            try {
              final byte[] data = new byte[random.nextInt(BLOB_MAX_SIZE_BYTES) + 1];
              random.nextBytes(data);
              final Blob blob = underTest.create(new ByteArrayInputStream(data), TEST_HEADERS);
              blobIdsInTheStore.add(blob.getId());
              createCount.incrementAndGet();

              // Track memory usage
              long currentMemory = memoryMXBean.getHeapMemoryUsage().getUsed();
              peakMemory.updateAndGet(peak -> Math.max(peak, currentMemory));
            } catch (Exception e) {
              log.error("Error creating blob", e);
              errorCount.incrementAndGet();
            }
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          completionLatch.countDown();
        }
      });
    }

    // Reader tasks - read existing blobs
    for (int i = 0; i < threadCount / 2; i++) {
      tasks.add(() -> {
        try {
          startLatch.await();
          while (running.get()) {
            final BlobId blobId = blobIdsInTheStore.peek();
            if (blobId == null) {
              Thread.yield();
              continue;
            }

            try {
              final Blob blob = underTest.get(blobId);
              if (blob == null) {
                // Blob might have been deleted by another thread
                continue;
              }

              try (InputStream inputStream = blob.getInputStream()) {
                readContentAndValidateMetrics(blobId, inputStream, blob.getMetrics());
                readCount.incrementAndGet();
              } catch (BlobStoreException e) {
                // This is normal if another thread deletes the blob after we obtain a reference
                if (deletedIds.contains(e.getBlobId())) {
                  log.debug("Attempted to read a blob that was concurrently deleted: {}", e.getBlobId());
                } else {
                  log.error("Error reading blob {}", blobId, e);
                  errorCount.incrementAndGet();
                }
              }
            } catch (Exception e) {
              log.error("Error getting blob {}", blobId, e);
              errorCount.incrementAndGet();
            }
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          completionLatch.countDown();
        }
      });
    }

    // Deleter tasks - delete existing blobs
    for (int i = 0; i < threadCount / 10; i++) {
      tasks.add(() -> {
        try {
          startLatch.await();
          while (running.get()) {
            final BlobId blobId = blobIdsInTheStore.poll();
            if (blobId == null) {
              Thread.yield();
              continue;
            }

            try {
              // Mark as deleted before actual deletion to handle concurrent reads
              deletedIds.add(blobId);
              underTest.delete(blobId, "Stress test deletion");
              deleteCount.incrementAndGet();
            } catch (Exception e) {
              log.error("Error deleting blob {}", blobId, e);
              errorCount.incrementAndGet();
            }
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          completionLatch.countDown();
        }
      });
    }

    // Shuffler tasks - move blob IDs around in the queue to create more randomness
    for (int i = 0; i < threadCount / 20; i++) {
      tasks.add(() -> {
        try {
          startLatch.await();
          while (running.get()) {
            final BlobId blobId = blobIdsInTheStore.poll();
            if (blobId != null) {
              blobIdsInTheStore.add(blobId);
            }
            Thread.yield(); // Allow other threads to run
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          completionLatch.countDown();
        }
      });
    }

    // Compactor tasks - run compaction periodically
    tasks.add(() -> {
      try {
        startLatch.await();
        while (running.get()) {
          try {
            underTest.compact(null);
          } catch (Exception e) {
            log.error("Error during compaction", e);
            errorCount.incrementAndGet();
          }
          // Sleep between compactions
          Thread.sleep(5000);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        completionLatch.countDown();
      }
    });

    // Fill remaining tasks with readers for maximum load
    int remainingTasks = threadCount - tasks.size();
    for (int i = 0; i < remainingTasks; i++) {
      tasks.add(() -> {
        try {
          startLatch.await();
          while (running.get()) {
            final BlobId blobId = blobIdsInTheStore.peek();
            if (blobId == null) {
              Thread.yield();
              continue;
            }

            try {
              final Blob blob = underTest.get(blobId);
              if (blob == null) {
                continue;
              }

              try (InputStream inputStream = blob.getInputStream()) {
                ByteStreams.copy(inputStream, nullOutputStream());
                readCount.incrementAndGet();
              } catch (BlobStoreException e) {
                if (deletedIds.contains(e.getBlobId())) {
                  log.debug("Attempted to read a blob that was concurrently deleted: {}", e.getBlobId());
                } else {
                  log.error("Error reading blob {}", blobId, e);
                  errorCount.incrementAndGet();
                }
              }
            } catch (Exception e) {
              log.error("Error getting blob {}", blobId, e);
              errorCount.incrementAndGet();
            }
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          completionLatch.countDown();
        }
      });
    }

    // Submit all tasks to the executor
    for (Runnable task : tasks) {
      executor.submit(task);
    }

    // Create some initial blobs to work with
    for (int i = 0; i < 100; i++) {
      byte[] data = new byte[1024];
      random.nextBytes(data);
      Blob blob = underTest.create(new ByteArrayInputStream(data), TEST_HEADERS);
      blobIdsInTheStore.add(blob.getId());
    }

    // Start the test
    startLatch.countDown();
    log.info("{} stress test started", testName);

    // Run for the specified duration
    Thread.sleep(Duration.ofSeconds(TEST_DURATION_SECONDS));
    running.set(false);

    // Wait for all tasks to complete
    log.info("Waiting for all tasks to complete...");
    completionLatch.await(30, TimeUnit.SECONDS);

    // Calculate memory usage
    long finalMemory = memoryMXBean.getHeapMemoryUsage().getUsed();
    long memoryIncrease = finalMemory - initialMemory;
    long peakIncrease = peakMemory.get() - initialMemory;

    // Log results
    log.info("{} stress test completed with {} threads", testName, threadCount);
    log.info("Operations performed: {} creates, {} reads, {} deletes", 
        createCount.get(), readCount.get(), deleteCount.get());
    log.info("Error count: {}", errorCount.get());
    log.info("Memory usage: initial={}MB, final={}MB, increase={}MB, peak={}MB", 
        initialMemory / (1024 * 1024), 
        finalMemory / (1024 * 1024), 
        memoryIncrease / (1024 * 1024),
        peakMemory.get() / (1024 * 1024));

    // Verify test results
    assertThat("Error count should be minimal", errorCount.get(), is(0));
    
    // For virtual threads, verify memory efficiency
    if (testName.equals("Virtual Thread")) {
      // Memory increase per thread should be very small with virtual threads
      double memoryPerThread = (double) peakIncrease / threadCount;
      log.info("Memory per thread: {}KB", String.format("%.2f", memoryPerThread / 1024));
      
      // Virtual threads should use significantly less memory per thread than platform threads
      // This is a rough estimate - actual values will depend on the environment
      assertThat("Memory per thread should be efficient with virtual threads", 
          memoryPerThread, lessThan(50.0 * 1024)); // Less than 50KB per thread
    }
    
    // Verify operations were performed
    assertTrue("Should have created blobs", createCount.get() > 0);
    assertTrue("Should have read blobs", readCount.get() > 0);
    assertTrue("Should have deleted blobs", deleteCount.get() > 0);
  }

  /**
   * Read all the content from a blob, and compare it with the metrics on file in the blob store.
   *
   * @throws RuntimeException if there is any deviation
   */
  private void readContentAndValidateMetrics(
      final BlobId blobId,
      final InputStream inputStream,
      final BlobMetrics metadataMetrics) throws NoSuchAlgorithmException, IOException
  {
    final MetricsInputStream measured = new MetricsInputStream(inputStream);
    ByteStreams.copy(measured, nullOutputStream());

    checkEqual("stream length", metadataMetrics.getContentSize(), measured.getSize(), blobId);
    checkEqual("SHA1 hash", metadataMetrics.getSha1Hash(), measured.getMessageDigest(), blobId);
  }

  private void checkEqual(
      final String propertyName,
      final Object expected,
      final Object measured,
      final BlobId blobId)
  {
    if (!Objects.equal(measured, expected)) {
      throw new RuntimeException(
          "Blob " + blobId + "'s measured " + propertyName + " differed from its metadata. Expected " + expected +
              " but was " + measured + ".");
    }
  }
}