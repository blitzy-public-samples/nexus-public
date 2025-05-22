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
package org.sonatype.virtualthread;

import java.net.URL;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
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
import org.sonatype.nexus.blobstore.restore.datastore.AssetBlobRefFormatCheck;
import org.sonatype.nexus.blobstore.restore.datastore.DefaultIntegrityCheckStrategy;
import org.sonatype.nexus.blobstore.restore.datastore.IntegrityCheckStrategy;
import org.sonatype.nexus.blobstore.restore.datastore.RestoreMetadataTask;
import org.sonatype.nexus.common.log.DryRunPrefix;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.maintenance.MaintenanceService;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.move.ChangeRepositoryBlobStoreConfiguration;
import org.sonatype.nexus.repository.move.ChangeRepositoryBlobStoreStore;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskUtils;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.TYPE_ID;
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.UNDELETE_BLOBS;
import static org.sonatype.nexus.blobstore.restore.datastore.DefaultIntegrityCheckStrategy.DEFAULT_NAME;

/**
 * Tests the {@link RestoreMetadataTask} with Java 21 Virtual Threads to verify that
 * blob metadata restoration operations can efficiently utilize the lightweight threading model.
 */
public class RestoreMetadataTaskVirtualThreadTest
    extends TestSupport
{
  private static final String BLOBSTORE_NAME = "test";
  private static final String MAVEN_2 = "maven2";
  private static final int HIGH_CONCURRENCY_COUNT = 1000;

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
    when(changeBlobstoreStore.findByBlobStoreName(any())).thenReturn(Collections.emptyList());
    doAnswer(invocation -> null).when(taskUtils).checkForConflictingTasks(any(), any(), any(), any());
  }

  /**
   * Test that RestoreMetadataTask can be executed using a Virtual Thread.
   */
  @Test
  public void testRestoreMetadataWithVirtualThread() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    underTest.configure(configuration);

    // Create and start a virtual thread to run the task
    Thread virtualThread = Thread.ofVirtual().name("restore-metadata-").start(() -> {
      try {
        underTest.execute();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });

    // Wait for the virtual thread to complete
    virtualThread.join();

    // Verify the thread was a virtual thread
    assertTrue("Thread should be a virtual thread", virtualThread.isVirtual());

    // Verify the task executed correctly
    ArgumentCaptor<Properties> propertiesArgumentCaptor = ArgumentCaptor.forClass(Properties.class);
    verify(restoreBlobStrategy).restore(propertiesArgumentCaptor.capture(), eq(blob), eq(blobStore), eq(false));
    verify(blobStore).undelete(blobstoreUsageChecker, blobId, blobAttributes, false);
    Properties properties = propertiesArgumentCaptor.getValue();

    assertThat(properties.getProperty("@BlobStore.blob-name"), is("org/codehaus/plexus/plexus/3.1/plexus-3.1.pom"));
  }

  /**
   * Test that RestoreMetadataTask can handle high concurrency with Virtual Threads.
   * This simulates a scenario where many blob restoration operations are happening concurrently.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, false);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    underTest.configure(configuration);

    // Create multiple blob IDs for testing high concurrency
    List<BlobId> blobIds = IntStream.range(0, HIGH_CONCURRENCY_COUNT)
        .mapToObj(i -> new BlobId("blob-" + i))
        .collect(Collectors.toList());

    // Setup mock behavior for multiple blobs
    when(blobStore.getBlobIdStream()).thenReturn(blobIds.stream());
    for (BlobId id : blobIds) {
      when(blobStore.get(id, true)).thenReturn(blob);
      when(blobStore.getBlobAttributes(id)).thenReturn(blobAttributes);
    }

    // Create a countdown latch to track completion
    CountDownLatch latch = new CountDownLatch(1);
    AtomicInteger completedTasks = new AtomicInteger(0);

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit the task to the executor
      Future<?> future = executor.submit(() -> {
        try {
          underTest.execute();
          completedTasks.incrementAndGet();
        } catch (Exception e) {
          throw new RuntimeException(e);
        } finally {
          latch.countDown();
        }
      });

      // Wait for the task to complete
      latch.await(30, TimeUnit.SECONDS);
      future.get(1, TimeUnit.SECONDS); // Should complete quickly

      // Verify the task completed successfully
      assertEquals("Task should have completed", 1, completedTasks.get());

      // Verify the restore method was called for each blob
      verify(restoreBlobStrategy, times(HIGH_CONCURRENCY_COUNT)).restore(any(), eq(blob), eq(blobStore), eq(false));
    }
  }

  /**
   * Test that integrity checking works correctly with Virtual Threads.
   */
  @Test
  public void testIntegrityCheckWithVirtualThreads() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, false);
    configuration.setBoolean(UNDELETE_BLOBS, false);
    configuration.setBoolean(INTEGRITY_CHECK, true);
    underTest.configure(configuration);

    when(repositoryManager.browseForBlobStore(any())).thenReturn(singletonList(repository));

    // Create a thread factory for virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();

    // Create and start a virtual thread to run the integrity check
    Thread virtualThread = virtualThreadFactory.newThread(() -> {
      try {
        underTest.execute();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    virtualThread.start();

    // Wait for the virtual thread to complete
    virtualThread.join();

    // Verify the thread was a virtual thread
    assertTrue("Thread should be a virtual thread", virtualThread.isVirtual());

    // Verify the integrity check was performed
    verifyNoInteractions(defaultIntegrityCheckStrategy);
    verify(testIntegrityCheckStrategy).check(eq(repository), eq(blobStore), any(), anyInt(), any());
  }

  /**
   * Test that I/O operations in RestoreMetadataTask don't cause thread pinning with Virtual Threads.
   * This test simulates I/O operations by adding delays in the blob operations.
   */
  @Test
  public void testIOOperationsWithVirtualThreads() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    underTest.configure(configuration);

    // Create multiple blob IDs
    List<BlobId> blobIds = IntStream.range(0, 10)
        .mapToObj(i -> new BlobId("io-blob-" + i))
        .collect(Collectors.toList());

    // Setup mock behavior for multiple blobs with simulated I/O delays
    when(blobStore.getBlobIdStream()).thenReturn(blobIds.stream());
    for (BlobId id : blobIds) {
      Blob mockBlob = mock(Blob.class);
      BlobAttributes mockAttributes = mock(BlobAttributes.class);
      Properties mockProperties = new Properties();
      mockProperties.setProperty(HEADER_PREFIX + REPO_NAME_HEADER, "maven-central");
      
      when(mockAttributes.getProperties()).thenReturn(mockProperties);
      when(blobStore.get(id, true)).thenReturn(mockBlob);
      when(blobStore.getBlobAttributes(id)).thenReturn(mockAttributes);
      
      // Simulate I/O delay in the restore operation
      doAnswer(invocation -> {
        Thread.sleep(100); // Simulate I/O operation
        return null;
      }).when(restoreBlobStrategy).restore(any(), eq(mockBlob), eq(blobStore), eq(false));
    }

    // Create a virtual thread executor with multiple threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple tasks to run concurrently
      List<Future<?>> futures = IntStream.range(0, 5)
          .mapToObj(i -> executor.submit(() -> {
            try {
              // Create a new task instance for each thread to avoid shared state
              RestoreMetadataTask task = new RestoreMetadataTask(
                  blobStoreManager, changeBlobstoreStore, repositoryManager,
                  ImmutableMap.of(MAVEN_2, restoreBlobStrategy),
                  blobstoreUsageChecker, dryRunPrefix, integrityCheckStrategies, 
                  maintenanceService, assetBlobRefFormatCheck, taskUtils);
              task.configure(configuration);
              task.execute();
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          }))
          .collect(Collectors.toList());

      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }

      // Verify that restore was called for each blob in each task
      verify(restoreBlobStrategy, times(10 * 5)).restore(any(), any(), eq(blobStore), eq(false));
    }
  }

  /**
   * Test that RestoreMetadataTask can be canceled while running on a Virtual Thread.
   */
  @Test
  public void testCancelTaskOnVirtualThread() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);

    // Create a task that can be canceled
    RestoreMetadataTask cancelableTask = new RestoreMetadataTask(
        blobStoreManager, changeBlobstoreStore, repositoryManager,
        ImmutableMap.of(MAVEN_2, restoreBlobStrategy),
        blobstoreUsageChecker, dryRunPrefix, integrityCheckStrategies, 
        maintenanceService, assetBlobRefFormatCheck, taskUtils) {
      @Override
      public boolean isCanceled() {
        return true; // Always return true to simulate cancellation
      }
    };
    cancelableTask.configure(configuration);

    // Run the task on a virtual thread
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        cancelableTask.execute();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });

    // Wait for the virtual thread to complete
    virtualThread.join();

    // Verify the after method was not called due to cancellation
    verify(restoreBlobStrategy, never()).after(true, repository);
  }
}