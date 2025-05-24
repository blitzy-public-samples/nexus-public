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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.sonatype.goodies.testsupport.TestSupport;
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
import org.sonatype.nexus.testcommon.virtualthread.ThreadPinningDetector;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
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
 * Virtual Thread-specific test for the RestoreMetadataTask that verifies its blob restoration functionality
 * when executed with Java 21's Virtual Threads.
 */
@ExtendWith(MockitoExtension.class)
class RestoreMetadataTaskTest
    extends VirtualThreadTestSupport
{
  public static final String BLOBSTORE_NAME = "test";

  public static final String MAVEN_2 = "maven2";

  private static final int LARGE_BLOB_COUNT = 1000;
  
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

  @BeforeEach
  void setup() throws Exception {
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

  @Test
  void checkForConflictsThrowsExceptionIfConflictingTaskIsRunning() {
    underTest.configure(configuration);

    doThrow(new IllegalStateException("conflicting task"))
        .when(taskUtils).checkForConflictingTasks(anyString(), anyString(), any(List.class), any(Map.class));
    when(changeBlobstoreStore.findByBlobStoreName(anyString())).thenReturn(Collections.emptyList());

    IllegalStateException exception = assertThrows(IllegalStateException.class, underTest::checkForConflicts);

    assertEquals("conflicting task", exception.getMessage());
    verify(taskUtils, times(1)).checkForConflictingTasks(anyString(), anyString(), any(List.class), any(Map.class));
  }

  @Test
  void checkForConflictsThrowsExceptionIfMoveTaskIsUnfinished() {
    ChangeRepositoryBlobStoreConfiguration record = getRecord("test" , BLOBSTORE_NAME, "target-blobstore");

    underTest.configure(configuration);

    doNothing()
        .when(taskUtils).checkForConflictingTasks(anyString(), anyString(), any(List.class), any(Map.class));
    when(changeBlobstoreStore.findByBlobStoreName(anyString())).thenReturn(Collections.singletonList(record));

    IllegalStateException exception = assertThrows(IllegalStateException.class, underTest::checkForConflicts);

    assertEquals(String.format("found unfinished move task using blobstore '%s', task can't be executed", BLOBSTORE_NAME), exception.getMessage());
    verify(taskUtils, times(1)).checkForConflictingTasks(anyString(), anyString(), any(List.class), any(Map.class));
    verify(changeBlobstoreStore, times(1)).findByBlobStoreName(eq(BLOBSTORE_NAME));
  }

  @Test
  void checkForConflictsRunsIfNoConflictingTasks() {
    underTest.configure(configuration);

    doNothing().when(taskUtils).checkForConflictingTasks(anyString(), anyString(), any(List.class), any(Map.class));
    when(changeBlobstoreStore.findByBlobStoreName(anyString())).thenReturn(Collections.emptyList());

    underTest.checkForConflicts();

    verify(taskUtils, times(1)).checkForConflictingTasks(anyString(), anyString(), any(List.class), any(Map.class));
  }

  private ChangeRepositoryBlobStoreConfiguration getRecord(final String name , final String sourceBlobStoreName , final String targetBlobStoreName) {
    return new ChangeRepositoryBlobStoreConfiguration()
    {
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

  @Test
  void testRestoreMetadataWithVirtualThreads() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    underTest.configure(configuration);

    // Execute the task using a virtual thread
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture.runAsync(underTest::execute, executor).join();
    }

    ArgumentCaptor<Properties> propertiesArgumentCaptor = ArgumentCaptor.forClass(Properties.class);
    verify(restoreBlobStrategy).restore(propertiesArgumentCaptor.capture(), eq(blob), eq(blobStore), eq(false));
    verify(blobStore).undelete(blobstoreUsageChecker, blobId, blobAttributes, false);
    Properties properties = propertiesArgumentCaptor.getValue();

    assertThat(properties.getProperty("@BlobStore.blob-name"), is("org/codehaus/plexus/plexus/3.1/plexus-3.1.pom"));
  }

  @Test
  void testConcurrentRestoreWithVirtualThreads() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    underTest.configure(configuration);

    // Create multiple blob IDs for concurrent restoration
    List<BlobId> blobIds = new ArrayList<>();
    List<Blob> blobs = new ArrayList<>();
    List<BlobAttributes> attributes = new ArrayList<>();

    for (int i = 0; i < 100; i++) {
      BlobId id = new BlobId(UUID.randomUUID().toString());
      blobIds.add(id);
      
      Blob mockBlob = mock(Blob.class);
      blobs.add(mockBlob);
      
      BlobAttributes mockAttrs = mock(BlobAttributes.class);
      Properties props = new Properties();
      props.setProperty(HEADER_PREFIX + REPO_NAME_HEADER, "maven-central");
      when(mockAttrs.getProperties()).thenReturn(props);
      attributes.add(mockAttrs);
      
      when(blobStore.get(id, true)).thenReturn(mockBlob);
      when(blobStore.getBlobAttributes(id)).thenReturn(mockAttrs);
    }

    when(blobStore.getBlobIdStream()).thenReturn(blobIds.stream());

    // Use a CountDownLatch to track completion
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean completed = new AtomicBoolean(false);

    // Execute with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture.runAsync(() -> {
        try {
          underTest.execute();
          completed.set(true);
        } finally {
          latch.countDown();
        }
      }, executor);

      // Wait for completion with timeout
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Task execution timed out");
      assertTrue(completed.get(), "Task did not complete successfully");
    }

    // Verify all blobs were processed
    for (int i = 0; i < blobs.size(); i++) {
      verify(restoreBlobStrategy).restore(any(Properties.class), eq(blobs.get(i)), eq(blobStore), eq(false));
    }
    
    // Verify after() was called once for the repository
    verify(restoreBlobStrategy).after(true, repository);
  }

  @Test
  void testThreadPinningDetectionDuringRestore() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    underTest.configure(configuration);

    // Setup thread pinning detector
    ThreadPinningDetector pinningDetector = new ThreadPinningDetector();

    // Execute with virtual threads and monitor for pinning
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        pinningDetector.startMonitoring();
        try {
          underTest.execute();
        } finally {
          pinningDetector.stopMonitoring();
        }
      }, executor);

      future.join(); // Wait for completion
    }

    // Verify no thread pinning occurred during I/O operations
    assertFalse(pinningDetector.wasPinningDetected(), 
        "Thread pinning detected during blob restore operations: " + pinningDetector.getPinningEvents());
  }

  @Test
  void testCancellationWithVirtualThreads() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);

    // Create a task that can be cancelled
    RestoreMetadataTask cancellableTask =
        new RestoreMetadataTask(blobStoreManager, changeBlobstoreStore, repositoryManager,
            ImmutableMap.of(MAVEN_2, restoreBlobStrategy),
            blobstoreUsageChecker, dryRunPrefix, integrityCheckStrategies, maintenanceService, assetBlobRefFormatCheck,
            taskUtils)
        {
          private final AtomicBoolean cancelled = new AtomicBoolean(false);
          
          @Override
          public boolean isCanceled() {
            return cancelled.get();
          }
          
          // Simulate cancellation after processing a few blobs
          @Override
          protected void processBlob(BlobId blobId) {
            super.processBlob(blobId);
            cancelled.set(true); // Cancel after first blob
          }
        };
    
    cancellableTask.configure(configuration);

    // Create multiple blob IDs
    List<BlobId> blobIds = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      BlobId id = new BlobId(UUID.randomUUID().toString());
      blobIds.add(id);
      
      Blob mockBlob = mock(Blob.class);
      BlobAttributes mockAttrs = mock(BlobAttributes.class);
      Properties props = new Properties();
      props.setProperty(HEADER_PREFIX + REPO_NAME_HEADER, "maven-central");
      when(mockAttrs.getProperties()).thenReturn(props);
      
      when(blobStore.get(id, true)).thenReturn(mockBlob);
      when(blobStore.getBlobAttributes(id)).thenReturn(mockAttrs);
    }

    when(blobStore.getBlobIdStream()).thenReturn(blobIds.stream());

    // Execute with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture.runAsync(cancellableTask::execute, executor).join();
    }

    // Verify that after() was never called due to cancellation
    verify(restoreBlobStrategy, never()).after(true, repository);
  }

  @Test
  void testLargeBlobCountWithVirtualThreads() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    underTest.configure(configuration);

    // Create a large number of blob IDs
    List<BlobId> blobIds = new ArrayList<>();
    for (int i = 0; i < LARGE_BLOB_COUNT; i++) {
      BlobId id = new BlobId(UUID.randomUUID().toString());
      blobIds.add(id);
      
      Blob mockBlob = mock(Blob.class);
      BlobAttributes mockAttrs = mock(BlobAttributes.class);
      Properties props = new Properties();
      props.setProperty(HEADER_PREFIX + REPO_NAME_HEADER, "maven-central");
      when(mockAttrs.getProperties()).thenReturn(props);
      
      when(blobStore.get(id, true)).thenReturn(mockBlob);
      when(blobStore.getBlobAttributes(id)).thenReturn(mockAttrs);
    }

    when(blobStore.getBlobIdStream()).thenReturn(blobIds.stream());

    // Track processed blob count
    AtomicInteger processedCount = new AtomicInteger(0);
    
    // Create a task that tracks processed blobs
    RestoreMetadataTask countingTask =
        new RestoreMetadataTask(blobStoreManager, changeBlobstoreStore, repositoryManager,
            ImmutableMap.of(MAVEN_2, restoreBlobStrategy),
            blobstoreUsageChecker, dryRunPrefix, integrityCheckStrategies, maintenanceService, assetBlobRefFormatCheck,
            taskUtils)
        {
          @Override
          protected void processBlob(BlobId blobId) {
            super.processBlob(blobId);
            processedCount.incrementAndGet();
          }
        };
    
    countingTask.configure(configuration);

    // Measure execution time with virtual threads
    long startTime = System.currentTimeMillis();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture.runAsync(countingTask::execute, executor).join();
    }
    long virtualThreadTime = System.currentTimeMillis() - startTime;

    // Verify all blobs were processed
    assertEquals(LARGE_BLOB_COUNT, processedCount.get(), "Not all blobs were processed");
    
    // Reset for platform thread test
    processedCount.set(0);
    
    // Create a new task for platform thread test
    RestoreMetadataTask platformTask =
        new RestoreMetadataTask(blobStoreManager, changeBlobstoreStore, repositoryManager,
            ImmutableMap.of(MAVEN_2, restoreBlobStrategy),
            blobstoreUsageChecker, dryRunPrefix, integrityCheckStrategies, maintenanceService, assetBlobRefFormatCheck,
            taskUtils)
        {
          @Override
          protected void processBlob(BlobId blobId) {
            super.processBlob(blobId);
            processedCount.incrementAndGet();
          }
        };
    
    platformTask.configure(configuration);

    // Measure execution time with platform threads
    startTime = System.currentTimeMillis();
    platformTask.execute(); // Direct execution on platform thread
    long platformThreadTime = System.currentTimeMillis() - startTime;

    // Verify all blobs were processed again
    assertEquals(LARGE_BLOB_COUNT, processedCount.get(), "Not all blobs were processed with platform threads");
    
    // Log performance comparison
    System.out.println("Virtual Thread execution time: " + virtualThreadTime + "ms");
    System.out.println("Platform Thread execution time: " + platformThreadTime + "ms");
    
    // Virtual threads should generally be more efficient for I/O-bound operations
    // This assertion might need adjustment based on actual performance characteristics
    assertThat("Virtual threads should be more efficient for I/O-bound operations", 
        virtualThreadTime, lessThan(platformThreadTime));
  }

  @Test
  void testIntegrityCheckWithVirtualThreads() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, false);
    configuration.setBoolean(UNDELETE_BLOBS, false);
    configuration.setBoolean(INTEGRITY_CHECK, true);
    underTest.configure(configuration);

    when(repositoryManager.browseForBlobStore(any())).thenReturn(singletonList(repository));

    // Execute with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture.runAsync(underTest::execute, executor).join();
    }

    // Verify integrity check was performed
    verify(testIntegrityCheckStrategy).check(eq(repository), eq(blobStore), any(), anyInt(), any());
  }

  @Test
  void testConcurrentIntegrityCheckWithVirtualThreads() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, false);
    configuration.setBoolean(UNDELETE_BLOBS, false);
    configuration.setBoolean(INTEGRITY_CHECK, true);
    underTest.configure(configuration);

    // Create multiple repositories for concurrent integrity checks
    List<Repository> repositories = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      Repository repo = mock(Repository.class);
      when(repo.isStarted()).thenReturn(true);
      when(repo.getFormat()).thenReturn(mavenFormat);
      repositories.add(repo);
    }

    when(repositoryManager.browseForBlobStore(any())).thenReturn(repositories);

    // Execute with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture.runAsync(underTest::execute, executor).join();
    }

    // Verify integrity check was performed for each repository
    for (Repository repo : repositories) {
      verify(testIntegrityCheckStrategy).check(eq(repo), eq(blobStore), any(), anyInt(), any());
    }
  }

  @Test
  void testVirtualThreadPerformanceComparison() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    underTest.configure(configuration);

    // Create a large number of blob IDs
    List<BlobId> blobIds = IntStream.range(0, LARGE_BLOB_COUNT)
        .mapToObj(i -> new BlobId(UUID.randomUUID().toString()))
        .toList();

    // Setup mocks for all blobs
    for (BlobId id : blobIds) {
      Blob mockBlob = mock(Blob.class);
      BlobAttributes mockAttrs = mock(BlobAttributes.class);
      Properties props = new Properties();
      props.setProperty(HEADER_PREFIX + REPO_NAME_HEADER, "maven-central");
      when(mockAttrs.getProperties()).thenReturn(props);
      
      when(blobStore.get(id, true)).thenReturn(mockBlob);
      when(blobStore.getBlobAttributes(id)).thenReturn(mockAttrs);
    }

    when(blobStore.getBlobIdStream()).thenReturn(blobIds.stream());

    // Run with virtual threads and measure performance
    long startTimeVirtual = System.currentTimeMillis();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture.runAsync(underTest::execute, executor).join();
    }
    long virtualThreadDuration = System.currentTimeMillis() - startTimeVirtual;

    // Reset mocks for platform thread test
    reset(restoreBlobStrategy);
    when(restoreBlobStrategy.restore(any(), any(), any(), eq(false))).thenReturn(true);

    // Run with platform threads and measure performance
    long startTimePlatform = System.currentTimeMillis();
    underTest.execute(); // Direct execution on platform thread
    long platformThreadDuration = System.currentTimeMillis() - startTimePlatform;

    // Log performance results
    System.out.println("Performance comparison for processing " + LARGE_BLOB_COUNT + " blobs:");
    System.out.println("Virtual Threads: " + virtualThreadDuration + "ms");
    System.out.println("Platform Threads: " + platformThreadDuration + "ms");
    System.out.println("Improvement: " + 
        String.format("%.2f%%", (platformThreadDuration - virtualThreadDuration) * 100.0 / platformThreadDuration));

    // Virtual threads should be more efficient for I/O-bound operations
    assertThat("Virtual threads should be more efficient for I/O-bound operations", 
        virtualThreadDuration, lessThan(platformThreadDuration));
  }

  @Test
  void testVirtualThreadScalability() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    underTest.configure(configuration);

    // Create an extremely large number of blob IDs to test scalability
    final int SCALABILITY_TEST_SIZE = 10000;
    List<BlobId> blobIds = IntStream.range(0, SCALABILITY_TEST_SIZE)
        .mapToObj(i -> new BlobId(UUID.randomUUID().toString()))
        .toList();

    // Setup minimal mocks for all blobs to reduce memory overhead
    Blob mockBlob = mock(Blob.class);
    BlobAttributes mockAttrs = mock(BlobAttributes.class);
    Properties props = new Properties();
    props.setProperty(HEADER_PREFIX + REPO_NAME_HEADER, "maven-central");
    when(mockAttrs.getProperties()).thenReturn(props);
    
    for (BlobId id : blobIds) {
      when(blobStore.get(id, true)).thenReturn(mockBlob);
      when(blobStore.getBlobAttributes(id)).thenReturn(mockAttrs);
    }

    when(blobStore.getBlobIdStream()).thenReturn(blobIds.stream());

    // Track processed blob count
    AtomicInteger processedCount = new AtomicInteger(0);
    
    // Create a task that tracks processed blobs
    RestoreMetadataTask countingTask =
        new RestoreMetadataTask(blobStoreManager, changeBlobstoreStore, repositoryManager,
            ImmutableMap.of(MAVEN_2, restoreBlobStrategy),
            blobstoreUsageChecker, dryRunPrefix, integrityCheckStrategies, maintenanceService, assetBlobRefFormatCheck,
            taskUtils)
        {
          @Override
          protected void processBlob(BlobId blobId) {
            super.processBlob(blobId);
            processedCount.incrementAndGet();
          }
        };
    
    countingTask.configure(configuration);

    // Execute with virtual threads
    long startTime = System.currentTimeMillis();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture.runAsync(countingTask::execute, executor).join();
    }
    long duration = System.currentTimeMillis() - startTime;

    // Verify all blobs were processed
    assertEquals(SCALABILITY_TEST_SIZE, processedCount.get(), 
        "Not all blobs were processed in the scalability test");
    
    // Log performance metrics
    System.out.println("Virtual Thread Scalability Test Results:");
    System.out.println("Processed " + SCALABILITY_TEST_SIZE + " blobs in " + duration + "ms");
    System.out.println("Average processing time per blob: " + 
        String.format("%.2f", (double)duration / SCALABILITY_TEST_SIZE) + "ms");
    System.out.println("Processing rate: " + 
        String.format("%.2f", (double)SCALABILITY_TEST_SIZE * 1000 / duration) + " blobs/second");

    // Verify the task can handle a large number of blobs efficiently
    // This is a relative performance metric that may need adjustment based on the test environment
    assertThat("Should process blobs at a reasonable rate", 
        (double)SCALABILITY_TEST_SIZE * 1000 / duration, greaterThan(100.0));
  }
}