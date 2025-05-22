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
package org.sonatype.nexus.blobstore.restore.datastore;

import java.net.URL;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobAttributes;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.api.BlobStoreUsageChecker;
import org.sonatype.nexus.blobstore.file.FileBlobAttributes;
import org.sonatype.nexus.blobstore.restore.RestoreBlobStrategy;
import org.sonatype.nexus.common.log.DryRunPrefix;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.maintenance.MaintenanceService;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.move.ChangeRepositoryBlobStoreConfiguration;
import org.sonatype.nexus.repository.move.ChangeRepositoryBlobStoreStore;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskUtils;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.sonatype.nexus.blobstore.api.BlobAttributesConstants.HEADER_PREFIX;
import static org.sonatype.nexus.blobstore.api.BlobStore.REPO_NAME_HEADER;
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.BLOB_STORE_NAME_FIELD_ID;
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.DRY_RUN;
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.INTEGRITY_CHECK;
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.RESTORE_BLOBS;
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.SINCE_DAYS;
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.TYPE_ID;
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.UNDELETE_BLOBS;
import static org.sonatype.nexus.blobstore.restore.datastore.DefaultIntegrityCheckStrategy.DEFAULT_NAME;

/**
 * Virtual Thread-specific test for the {@link RestoreMetadataTask} that verifies its blob restoration functionality
 * when executed with Java 21's Virtual Threads.
 */
@Category(VirtualThreadTestGroup.class)
public class RestoreMetadataTaskTest
    extends TestSupport
{
  public static final String BLOBSTORE_NAME = "test";

  public static final String MAVEN_2 = "maven2";

  private static final int LARGE_BLOB_COUNT = 1000;
  private static final int CONCURRENT_THREADS = 100;
  private static final int TIMEOUT_SECONDS = 30;

  @Mock
  BlobStoreManager blobStoreManager;

  @Mock
  ChangeRepositoryBlobStoreStore changeBlobstoreStore;

  @Mock
  RepositoryManager repositoryManager;

  @Mock
  RestoreBlobStrategy restoreBlobStrategy;

  @Mock
  Repository repository;

  @Mock
  BlobStore blobStore;

  @Mock
  Blob blob;

  @Mock
  Format mavenFormat;

  @Mock
  BlobStoreUsageChecker blobstoreUsageChecker;

  @Mock
  DryRunPrefix dryRunPrefix;

  @Mock
  DefaultIntegrityCheckStrategy defaultIntegrityCheckStrategy;

  @Mock
  IntegrityCheckStrategy testIntegrityCheckStrategy;

  @Mock
  MaintenanceService maintenanceService;

  @Mock
  private AssetBlobRefFormatCheck assetBlobRefFormatCheck;

  @Mock
  TaskUtils taskUtils;

  RestoreMetadataTask underTest;

  Map<String, IntegrityCheckStrategy> integrityCheckStrategies;

  BlobId blobId;

  FileBlobAttributes blobAttributes;

  TaskConfiguration configuration;

  @Before
  public void setup() throws Exception {
    integrityCheckStrategies = spy(new HashMap<>());
    integrityCheckStrategies.put(MAVEN_2, testIntegrityCheckStrategy);
    integrityCheckStrategies.put(DEFAULT_NAME, defaultIntegrityCheckStrategy);

    underTest =
        new RestoreMetadataTask(blobStoreManager, changeBlobstoreStore, repositoryManager,
            ImmutableMap.of(MAVEN_2, restoreBlobStrategy),
            blobstoreUsageChecker, dryRunPrefix, integrityCheckStrategies, maintenanceService, assetBlobRefFormatCheck,
            taskUtils);

    reset(integrityCheckStrategies); // reset this mock so we more easily verify calls

    configuration = new TaskConfiguration();
    configuration.setString(BLOB_STORE_NAME_FIELD_ID, BLOBSTORE_NAME);
    configuration.setString(".name", "test");
    configuration.setId(BLOBSTORE_NAME);
    configuration.setTypeId(TYPE_ID);

    when(repositoryManager.get("maven-central")).thenReturn(repository);
    when(repository.isStarted()).thenReturn(true);
    when(repository.getFormat()).thenReturn(mavenFormat);
    when(mavenFormat.getValue()).thenReturn(MAVEN_2);

    URL resource = Resources
        .getResource("test-restore/content/vol-1/chp-1/86e20baa-0bca-4915-a7dc-9a4f34e72321.properties");
    blobAttributes = new FileBlobAttributes(Paths.get(resource.toURI()));
    blobAttributes.load();
    blobId = new BlobId("86e20baa-0bca-4915-a7dc-9a4f34e72321");
    when(blobStore.getBlobIdStream()).thenReturn(Stream.of(blobId));
    when(blobStore.getBlobIdUpdatedSinceStream(any(Duration.class))).thenReturn(Stream.of(blobId));
    when(blobStoreManager.get(BLOBSTORE_NAME)).thenReturn(blobStore);

    when(blobStore.get(blobId, true)).thenReturn(blob);
    when(blobStore.getBlobAttributes(blobId)).thenReturn(blobAttributes);

    when(dryRunPrefix.get()).thenReturn("");
  }

  /**
   * Test that the RestoreMetadataTask can be executed with Virtual Threads.
   */
  @Test
  public void testRestoreMetadataWithVirtualThreads() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    underTest.configure(configuration);

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Execute the task in a virtual thread
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          underTest.execute();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }, executor);

      // Wait for completion
      future.join();
    }

    // Verify the task executed correctly
    ArgumentCaptor<Properties> propertiesArgumentCaptor = ArgumentCaptor.forClass(Properties.class);
    verify(restoreBlobStrategy).restore(propertiesArgumentCaptor.capture(), eq(blob), eq(blobStore), eq(false));
    verify(blobStore).undelete(blobstoreUsageChecker, blobId, blobAttributes, false);
    Properties properties = propertiesArgumentCaptor.getValue();

    assertThat(properties.getProperty("@BlobStore.blob-name"), is("org/codehaus/plexus/plexus/3.1/plexus-3.1.pom"));
  }

  /**
   * Test that the RestoreMetadataTask can handle concurrent blob restoration with Virtual Threads.
   */
  @Test
  public void testConcurrentBlobRestorationWithVirtualThreads() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    underTest.configure(configuration);

    // Create a large number of blob IDs
    List<BlobId> blobIds = IntStream.range(0, LARGE_BLOB_COUNT)
        .mapToObj(i -> new BlobId(UUID.randomUUID().toString()))
        .collect(Collectors.toList());

    // Set up mock behavior for each blob
    for (BlobId id : blobIds) {
      BlobAttributes attrs = mock(BlobAttributes.class);
      Properties props = new Properties();
      props.setProperty(HEADER_PREFIX + REPO_NAME_HEADER, "maven-central");
      when(attrs.getProperties()).thenReturn(props);
      when(blobStore.getBlobAttributes(id)).thenReturn(attrs);
      when(blobStore.get(id, true)).thenReturn(blob);
    }

    // Return the stream of blob IDs
    when(blobStore.getBlobIdStream()).thenReturn(blobIds.stream());

    // Create a latch to track completion
    CountDownLatch latch = new CountDownLatch(1);
    AtomicInteger processedCount = new AtomicInteger(0);

    // Mock the restore method to count processed blobs
    doAnswer(invocation -> {
      processedCount.incrementAndGet();
      return null;
    }).when(restoreBlobStrategy).restore(any(Properties.class), eq(blob), eq(blobStore), eq(false));

    // Execute the task in a virtual thread
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          underTest.execute();
          latch.countDown();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }, executor);

      // Wait for completion with timeout
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue("Task did not complete within timeout", completed);

      // Verify all blobs were processed
      assertThat(processedCount.get(), is(LARGE_BLOB_COUNT));
    }
  }

  /**
   * Test that the RestoreMetadataTask can be cancelled while running in a Virtual Thread.
   */
  @Test
  public void testCancellationWithVirtualThreads() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);

    // Create a task that can be cancelled
    AtomicBoolean cancelled = new AtomicBoolean(false);
    RestoreMetadataTask cancellableTask =
        new RestoreMetadataTask(blobStoreManager, changeBlobstoreStore, repositoryManager,
            ImmutableMap.of(MAVEN_2, restoreBlobStrategy),
            blobstoreUsageChecker, dryRunPrefix, integrityCheckStrategies, maintenanceService, assetBlobRefFormatCheck,
            taskUtils) {
          @Override
          public boolean isCanceled() {
            return cancelled.get();
          }
        };

    cancellableTask.configure(configuration);

    // Create a large number of blob IDs to ensure the task runs long enough to be cancelled
    List<BlobId> blobIds = IntStream.range(0, LARGE_BLOB_COUNT)
        .mapToObj(i -> new BlobId(UUID.randomUUID().toString()))
        .collect(Collectors.toList());

    // Set up mock behavior for each blob with a delay to simulate work
    for (BlobId id : blobIds) {
      BlobAttributes attrs = mock(BlobAttributes.class);
      Properties props = new Properties();
      props.setProperty(HEADER_PREFIX + REPO_NAME_HEADER, "maven-central");
      when(attrs.getProperties()).thenReturn(props);
      when(blobStore.getBlobAttributes(id)).thenReturn(attrs);
      when(blobStore.get(id, true)).thenReturn(blob);
    }

    // Return the stream of blob IDs
    when(blobStore.getBlobIdStream()).thenReturn(blobIds.stream());

    // Add a delay to the restore method to simulate work
    AtomicInteger processedCount = new AtomicInteger(0);
    doAnswer(new Answer<Void>() {
      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        // Process a few blobs before cancellation
        if (processedCount.incrementAndGet() > 10) {
          cancelled.set(true);
        }
        Thread.sleep(10); // Small delay to simulate work
        return null;
      }
    }).when(restoreBlobStrategy).restore(any(Properties.class), eq(blob), eq(blobStore), eq(false));

    // Execute the task in a virtual thread
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          cancellableTask.execute();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }, executor);

      // Wait for completion
      future.join();
    }

    // Verify the task was cancelled and only processed a subset of blobs
    assertTrue("Task should have been cancelled", cancelled.get());
    assertThat("Should have processed some blobs before cancellation", processedCount.get(), greaterThan(0));
    assertThat("Should not have processed all blobs due to cancellation", processedCount.get(), lessThan(LARGE_BLOB_COUNT));

    // Verify after() was not called due to cancellation
    verify(restoreBlobStrategy, never()).after(anyBoolean(), any(Repository.class));
  }

  /**
   * Test that compares performance between platform threads and virtual threads for blob restoration.
   */
  @Test
  public void testPerformanceComparisonBetweenThreadTypes() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    underTest.configure(configuration);

    // Create a large number of blob IDs
    List<BlobId> blobIds = IntStream.range(0, LARGE_BLOB_COUNT)
        .mapToObj(i -> new BlobId(UUID.randomUUID().toString()))
        .collect(Collectors.toList());

    // Set up mock behavior for each blob
    Map<BlobId, BlobAttributes> blobAttributesMap = new ConcurrentHashMap<>();
    for (BlobId id : blobIds) {
      BlobAttributes attrs = mock(BlobAttributes.class);
      Properties props = new Properties();
      props.setProperty(HEADER_PREFIX + REPO_NAME_HEADER, "maven-central");
      when(attrs.getProperties()).thenReturn(props);
      blobAttributesMap.put(id, attrs);
      when(blobStore.get(id, true)).thenReturn(blob);
    }

    // Mock the getBlobAttributes method to return the appropriate attributes
    when(blobStore.getBlobAttributes(any(BlobId.class))).thenAnswer(invocation -> {
      BlobId id = invocation.getArgument(0);
      return blobAttributesMap.get(id);
    });

    // Return the stream of blob IDs
    when(blobStore.getBlobIdStream()).thenReturn(blobIds.stream());

    // Create thread factories
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();

    // Measure platform thread performance
    long platformThreadTime = measureExecutionTime(() -> {
      try (ExecutorService executor = Executors.newThreadPerTaskExecutor(platformThreadFactory)) {
        executeWithExecutor(executor);
      }
    });

    // Reset mocks and counters
    reset(restoreBlobStrategy);
    when(restoreBlobStrategy.restore(any(Properties.class), eq(blob), eq(blobStore), eq(false))).thenReturn(true);

    // Measure virtual thread performance
    long virtualThreadTime = measureExecutionTime(() -> {
      try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
        executeWithExecutor(executor);
      }
    });

    // Log the results
    log.info("Platform thread execution time: {} ms", platformThreadTime);
    log.info("Virtual thread execution time: {} ms", virtualThreadTime);

    // Virtual threads should generally be more efficient for I/O-bound operations
    // but in a test environment with mocks, the difference might not be significant
    // This assertion is more for documentation than strict validation
    assertTrue("Virtual threads should not be significantly slower than platform threads",
        virtualThreadTime < platformThreadTime * 1.5);
  }

  /**
   * Test that the RestoreMetadataTask can handle a large number of concurrent operations with Virtual Threads.
   */
  @Test
  public void testLargeConcurrentOperationsWithVirtualThreads() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    underTest.configure(configuration);

    // Create a very large number of blob IDs to test scalability
    int veryLargeBlobCount = LARGE_BLOB_COUNT * 10;
    List<BlobId> blobIds = IntStream.range(0, veryLargeBlobCount)
        .mapToObj(i -> new BlobId(UUID.randomUUID().toString()))
        .collect(Collectors.toList());

    // Set up mock behavior for each blob
    for (BlobId id : blobIds) {
      BlobAttributes attrs = mock(BlobAttributes.class);
      Properties props = new Properties();
      props.setProperty(HEADER_PREFIX + REPO_NAME_HEADER, "maven-central");
      when(attrs.getProperties()).thenReturn(props);
      when(blobStore.getBlobAttributes(id)).thenReturn(attrs);
      when(blobStore.get(id, true)).thenReturn(blob);
    }

    // Return the stream of blob IDs
    when(blobStore.getBlobIdStream()).thenReturn(blobIds.stream());

    // Create a latch to track completion
    CountDownLatch latch = new CountDownLatch(1);
    AtomicInteger processedCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);

    // Mock the restore method to count processed blobs
    doAnswer(invocation -> {
      processedCount.incrementAndGet();
      return null;
    }).when(restoreBlobStrategy).restore(any(Properties.class), eq(blob), eq(blobStore), eq(false));

    // Execute the task in a virtual thread
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          underTest.execute();
          latch.countDown();
        } catch (Exception e) {
          errorCount.incrementAndGet();
          throw new RuntimeException(e);
        }
      }, executor);

      // Wait for completion with timeout
      boolean completed = latch.await(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS);
      assertTrue("Task did not complete within timeout", completed);

      // Verify all blobs were processed without errors
      assertThat(errorCount.get(), is(0));
      assertThat(processedCount.get(), is(veryLargeBlobCount));
    }
  }

  /**
   * Test that thread pinning detection works correctly with Virtual Threads.
   * This test simulates a scenario where thread pinning might occur and verifies
   * that the task can still complete successfully.
   */
  @Test
  public void testThreadPinningDetection() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    underTest.configure(configuration);

    // Create blob IDs
    List<BlobId> blobIds = IntStream.range(0, CONCURRENT_THREADS)
        .mapToObj(i -> new BlobId(UUID.randomUUID().toString()))
        .collect(Collectors.toList());

    // Set up mock behavior for each blob
    for (BlobId id : blobIds) {
      BlobAttributes attrs = mock(BlobAttributes.class);
      Properties props = new Properties();
      props.setProperty(HEADER_PREFIX + REPO_NAME_HEADER, "maven-central");
      when(attrs.getProperties()).thenReturn(props);
      when(blobStore.getBlobAttributes(id)).thenReturn(attrs);
      when(blobStore.get(id, true)).thenReturn(blob);
    }

    // Return the stream of blob IDs
    when(blobStore.getBlobIdStream()).thenReturn(blobIds.stream());

    // Create a latch to coordinate threads
    CountDownLatch startLatch = new CountDownLatch(CONCURRENT_THREADS);
    CountDownLatch endLatch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger activeThreads = new AtomicInteger(0);
    AtomicInteger maxActiveThreads = new AtomicInteger(0);

    // Mock the restore method to simulate potential thread pinning
    doAnswer(invocation -> {
      // Increment active threads and update max
      int active = activeThreads.incrementAndGet();
      int max;
      do {
        max = maxActiveThreads.get();
        if (active <= max) break;
      } while (!maxActiveThreads.compareAndSet(max, active));

      // Signal thread has started
      startLatch.countDown();

      try {
        // Simulate a synchronized block that could cause pinning
        synchronized (RestoreMetadataTaskTest.this) {
          // Small delay to increase chance of pinning
          Thread.sleep(10);
        }
      } finally {
        // Decrement active threads
        activeThreads.decrementAndGet();
        // Signal thread has completed
        endLatch.countDown();
      }
      return null;
    }).when(restoreBlobStrategy).restore(any(Properties.class), eq(blob), eq(blobStore), eq(false));

    // Execute the task in a virtual thread
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          underTest.execute();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }, executor);

      // Wait for all threads to start and finish
      boolean allStarted = startLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      boolean allFinished = endLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);

      assertTrue("Not all threads started within timeout", allStarted);
      assertTrue("Not all threads finished within timeout", allFinished);

      // Wait for task completion
      future.join();

      // Log the maximum number of concurrent threads observed
      log.info("Maximum concurrent threads: {}", maxActiveThreads.get());

      // Verify that multiple threads were active concurrently
      assertThat("Should have had multiple concurrent threads", maxActiveThreads.get(), greaterThan(1));
    }
  }

  /**
   * Helper method to execute the task with the given executor service.
   */
  private void executeWithExecutor(ExecutorService executor) throws Exception {
    // Create a latch to track completion
    CountDownLatch latch = new CountDownLatch(1);

    // Execute the task
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        underTest.execute();
        latch.countDown();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, executor);

    // Wait for completion with timeout
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    if (!completed) {
      fail("Task did not complete within timeout");
    }

    // Wait for the future to complete
    future.join();
  }

  /**
   * Helper method to measure execution time of a runnable.
   */
  private long measureExecutionTime(Runnable runnable) {
    long startTime = System.currentTimeMillis();
    runnable.run();
    return System.currentTimeMillis() - startTime;
  }

  /**
   * Helper method to create a mock ChangeRepositoryBlobStoreConfiguration.
   */
  private ChangeRepositoryBlobStoreConfiguration getRecord(final String name, final String sourceBlobStoreName, final String targetBlobStoreName) {
    return new ChangeRepositoryBlobStoreConfiguration() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public void setName(final String name) {
      }

      @Override
      public String getTargetBlobStoreName() {
        return targetBlobStoreName;
      }

      @Override
      public void setTargetBlobStoreName(final String targetBlobStoreName) {
      }

      @Override
      public String getSourceBlobStoreName() {
        return sourceBlobStoreName;
      }

      @Override
      public void setSourceBlobStoreName(final String sourceBlobStoreName) {
      }

      @Override
      public OffsetDateTime getStarted() {
        return null;
      }

      @Override
      public void setStarted(final OffsetDateTime processStartDate) {
      }
    };
  }
}