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
package org.sonatype.nexus.virtualthread;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.blobstore.BlobStoreDescriptor;
import org.sonatype.nexus.blobstore.BlobStoreDescriptorProvider;
import org.sonatype.nexus.blobstore.MockBlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreException;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;
import org.sonatype.nexus.blobstore.api.tasks.BlobStoreTaskService;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuota;
import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.coreui.BlobStoreComponent;
import org.sonatype.nexus.coreui.BlobStoreXO;
import org.sonatype.nexus.repository.blobstore.BlobStoreConfigurationStore;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.security.RepositoryPermissionChecker;
import org.sonatype.nexus.testcommon.virtualthread.ThreadPinningDetector;

import static java.lang.Math.pow;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link BlobStoreComponent} using Java 21 Virtual Threads.
 * 
 * This test class verifies that BlobStore operations (create, update, delete, list) function correctly
 * under high concurrency with Virtual Threads, ensuring no thread pinning issues occur during I/O operations.
 */
@ExtendWith(MockitoExtension.class)
@org.junit.experimental.categories.Category(VirtualThreadTestGroup.class)
public class BlobStoreComponentVirtualThreadTest
{
  private static final int CONCURRENT_OPERATIONS = 100;
  private static final int TIMEOUT_SECONDS = 30;

  @Mock
  private BlobStoreManager blobStoreManager;

  @Mock
  private BlobStoreConfigurationStore store;

  @Mock
  private BlobStoreDescriptorProvider blobStoreDescriptorProvider;

  private Map<String, BlobStoreQuota> quotaFactories = new HashMap<>();

  @Mock
  private ApplicationDirectories applicationDirectories;

  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private RepositoryPermissionChecker permissionChecker;

  @Mock
  private BlobStoreTaskService blobStoreTaskService;

  private BlobStoreComponent underTest;

  @BeforeEach
  public void setup() {
    underTest = new BlobStoreComponent(blobStoreManager, store, blobStoreDescriptorProvider, quotaFactories,
        applicationDirectories, repositoryManager, permissionChecker, blobStoreTaskService);
  }

  /**
   * Test that creating blob stores concurrently with Virtual Threads works correctly.
   */
  @Test
  public void testConcurrentBlobStoreCreationWithVirtualThreads() throws Exception {
    // Setup thread factory for virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Setup mocks
    BlobStore blobStore = mock(BlobStore.class);
    BlobStoreMetrics metrics = mock(BlobStoreMetrics.class);
    when(blobStore.getMetrics()).thenReturn(metrics);
    when(blobStoreManager.create(any(BlobStoreConfiguration.class))).thenReturn(blobStore);
    when(blobStoreManager.newConfiguration()).thenReturn(mock(BlobStoreConfiguration.class));
    when(blobStoreManager.getByName()).thenReturn(Collections.singletonMap("blob", blobStore));
    
    // Setup for concurrent operations
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create blob stores concurrently using virtual threads
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final int index = i;
      executor.submit(() -> {
        try {
          // Create a unique blob store for each thread
          Map<String, Map<String, Object>> attributes = new HashMap<>();
          Map<String, Object> fileAttributes = new HashMap<>();
          fileAttributes.put("path", "path/to/blobs/blob" + index);
          attributes.put("file", fileAttributes);
          
          BlobStoreXO blobStoreXO = new BlobStoreXO()
              .withName("blob" + index)
              .withType("File")
              .withIsQuotaEnabled(true)
              .withQuotaType("spaceUsedQuota")
              .withQuotaLimit(10L)
              .withAttributes(attributes);
          
          // Configure mock for this specific blob store
          MockBlobStoreConfiguration config = new MockBlobStoreConfiguration()
              .withName("blob" + index)
              .withType("File")
              .withAttributes(Map.of(
                  "file", Map.of("path", "path/to/blobs/blob" + index),
                  "blobStoreQuotaConfig", Map.of("quotaType", "spaceUsedQuota", "quotaLimit", 10L * MILLION)));
          when(blobStore.getBlobStoreConfiguration()).thenReturn(config);
          
          // Create the blob store
          BlobStoreXO result = underTest.create(blobStoreXO);
          
          // Verify the result
          assertThat(result, notNullValue());
          assertThat(result.getName(), is("blob" + index));
          assertThat(result.getType(), is("File"));
        } 
        catch (Exception e) {
          errorCount.incrementAndGet();
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Shutdown the executor
    executor.shutdown();
    
    // Verify results
    assertThat("All operations should complete within timeout", completed, is(true));
    assertThat("No operations should fail", errorCount.get(), is(0));
    
    // Verify the blobStoreManager.create was called the expected number of times
    verify(blobStoreManager, times(CONCURRENT_OPERATIONS)).create(any(BlobStoreConfiguration.class));
  }

  /**
   * Test that updating blob stores concurrently with Virtual Threads works correctly.
   */
  @Test
  public void testConcurrentBlobStoreUpdateWithVirtualThreads() throws Exception {
    // Setup thread factory for virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Setup mocks
    BlobStore blobStore = mock(BlobStore.class);
    BlobStoreMetrics metrics = mock(BlobStoreMetrics.class);
    when(blobStore.getMetrics()).thenReturn(metrics);
    when(blobStoreManager.update(any(BlobStoreConfiguration.class))).thenReturn(blobStore);
    when(blobStoreManager.newConfiguration()).thenReturn(mock(BlobStoreConfiguration.class));
    when(blobStoreManager.getByName()).thenReturn(Collections.singletonMap("blob", blobStore));
    
    // Setup for concurrent operations
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Update blob stores concurrently using virtual threads
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final int index = i;
      executor.submit(() -> {
        try {
          // Create a unique blob store for each thread
          Map<String, Map<String, Object>> attributes = new HashMap<>();
          Map<String, Object> fileAttributes = new HashMap<>();
          fileAttributes.put("path", "path/to/blobs/blob" + index);
          attributes.put("file", fileAttributes);
          
          BlobStoreXO blobStoreXO = new BlobStoreXO()
              .withName("blob" + index)
              .withType("File")
              .withIsQuotaEnabled(true)
              .withQuotaType("spaceUsedQuota")
              .withQuotaLimit(20L) // Updated quota limit
              .withAttributes(attributes);
          
          // Configure mock for this specific blob store
          MockBlobStoreConfiguration config = new MockBlobStoreConfiguration()
              .withName("blob" + index)
              .withType("File")
              .withAttributes(Map.of(
                  "file", Map.of("path", "path/to/blobs/blob" + index),
                  "blobStoreQuotaConfig", Map.of("quotaType", "spaceUsedQuota", "quotaLimit", 20L * MILLION)));
          when(blobStore.getBlobStoreConfiguration()).thenReturn(config);
          when(blobStoreManager.get("blob" + index)).thenReturn(blobStore);
          
          // Update the blob store
          BlobStoreXO result = underTest.update(blobStoreXO);
          
          // Verify the result
          assertThat(result, notNullValue());
          assertThat(result.getName(), is("blob" + index));
          assertThat(result.getQuotaLimit(), is(20L));
        } 
        catch (Exception e) {
          errorCount.incrementAndGet();
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Shutdown the executor
    executor.shutdown();
    
    // Verify results
    assertThat("All operations should complete within timeout", completed, is(true));
    assertThat("No operations should fail", errorCount.get(), is(0));
    
    // Verify the blobStoreManager.update was called the expected number of times
    verify(blobStoreManager, times(CONCURRENT_OPERATIONS)).update(any(BlobStoreConfiguration.class));
  }

  /**
   * Test that removing blob stores concurrently with Virtual Threads works correctly.
   */
  @Test
  public void testConcurrentBlobStoreRemovalWithVirtualThreads() throws Exception {
    // Setup thread factory for virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Setup for concurrent operations
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Configure mocks for blob store removal
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      String blobStoreName = "blob" + i;
      when(repositoryManager.isBlobstoreUsed(blobStoreName)).thenReturn(false);
      when(blobStoreTaskService.countTasksInUseForBlobStore(blobStoreName)).thenReturn(0);
    }
    
    // Remove blob stores concurrently using virtual threads
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final int index = i;
      executor.submit(() -> {
        try {
          // Remove the blob store
          underTest.remove("blob" + index);
        } 
        catch (Exception e) {
          errorCount.incrementAndGet();
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Shutdown the executor
    executor.shutdown();
    
    // Verify results
    assertThat("All operations should complete within timeout", completed, is(true));
    assertThat("No operations should fail", errorCount.get(), is(0));
    
    // Verify the blobStoreManager.delete was called the expected number of times
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      verify(blobStoreManager).delete("blob" + i);
    }
  }

  /**
   * Test that reading blob stores concurrently with Virtual Threads works correctly.
   */
  @Test
  public void testConcurrentBlobStoreReadWithVirtualThreads() throws Exception {
    // Setup thread factory for virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Setup mocks
    BlobStore blobStore = mock(BlobStore.class);
    BlobStoreMetrics metrics = mock(BlobStoreMetrics.class);
    when(blobStore.getMetrics()).thenReturn(metrics);
    
    // Create a list of blob store configurations
    List<BlobStoreConfiguration> configList = new java.util.ArrayList<>();
    for (int i = 0; i < 10; i++) {
      MockBlobStoreConfiguration config = new MockBlobStoreConfiguration()
          .withName("blob" + i)
          .withType("File")
          .withAttributes(Map.of(
              "file", Map.of("path", "path/to/blobs/blob" + i),
              "blobStoreQuotaConfig", Map.of("quotaType", "spaceUsedQuota", "quotaLimit", 10L * MILLION)));
      configList.add(config);
    }
    
    // Setup store to return the list of configurations
    when(store.list()).thenReturn(configList);
    
    // Setup blobStoreManager to return the blob store for each configuration
    Map<String, BlobStore> blobStoreMap = new HashMap<>();
    for (int i = 0; i < 10; i++) {
      blobStoreMap.put("blob" + i, blobStore);
    }
    when(blobStoreManager.getByName()).thenReturn(blobStoreMap);
    
    // Setup for concurrent operations
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Read blob stores concurrently using virtual threads
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      executor.submit(() -> {
        try {
          // Read all blob stores
          List<BlobStoreXO> result = underTest.read();
          
          // Verify the result
          assertThat(result, notNullValue());
          assertEquals(10, result.size());
        } 
        catch (Exception e) {
          errorCount.incrementAndGet();
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Shutdown the executor
    executor.shutdown();
    
    // Verify results
    assertThat("All operations should complete within timeout", completed, is(true));
    assertThat("No operations should fail", errorCount.get(), is(0));
    
    // Verify the store.list was called the expected number of times
    verify(store, times(CONCURRENT_OPERATIONS)).list();
  }

  /**
   * Test that blob store operations fail correctly when repositories are using the blob store.
   */
  @Test
  public void testConcurrentBlobStoreRemovalFailsWhenRepositoryUsesIt() throws Exception {
    // Setup thread factory for virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Setup for concurrent operations
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger expectedErrorCount = new AtomicInteger(0);
    
    // Configure mocks for blob store removal
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      // Every other blob store is in use by a repository
      String blobStoreName = "blob" + i;
      boolean isUsed = i % 2 == 0;
      when(repositoryManager.isBlobstoreUsed(blobStoreName)).thenReturn(isUsed);
      if (isUsed) {
        expectedErrorCount.incrementAndGet();
      }
    }
    
    // Remove blob stores concurrently using virtual threads
    AtomicInteger actualErrorCount = new AtomicInteger(0);
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final int index = i;
      executor.submit(() -> {
        try {
          // Try to remove the blob store
          underTest.remove("blob" + index);
        } 
        catch (BlobStoreException e) {
          // This is expected for blob stores in use
          actualErrorCount.incrementAndGet();
        } 
        catch (Exception e) {
          // Unexpected error
          System.err.println("Unexpected error: " + e);
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Shutdown the executor
    executor.shutdown();
    
    // Verify results
    assertThat("All operations should complete within timeout", completed, is(true));
    assertThat("Expected number of operations should fail", actualErrorCount.get(), is(expectedErrorCount.get()));
    
    // Verify the blobStoreManager.delete was called only for blob stores not in use
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      if (i % 2 == 0) {
        // Blob store is in use, delete should not be called
        verify(blobStoreManager, never()).delete("blob" + i);
      } else {
        // Blob store is not in use, delete should be called
        verify(blobStoreManager).delete("blob" + i);
      }
    }
  }

  /**
   * Test that no thread pinning occurs during blob store operations.
   * This test uses the ThreadPinningDetector to check for thread pinning issues.
   */
  @Test
  public void testNoPinningDuringBlobStoreOperations() throws Exception {
    // Setup thread factory for virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Setup mocks
    BlobStore blobStore = mock(BlobStore.class);
    BlobStoreMetrics metrics = mock(BlobStoreMetrics.class);
    when(blobStore.getMetrics()).thenReturn(metrics);
    when(blobStoreManager.create(any(BlobStoreConfiguration.class))).thenReturn(blobStore);
    when(blobStoreManager.newConfiguration()).thenReturn(mock(BlobStoreConfiguration.class));
    when(blobStoreManager.getByName()).thenReturn(Collections.singletonMap("blob", blobStore));
    
    // Setup for concurrent operations
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    
    // Create a ThreadPinningDetector to detect any thread pinning issues
    ThreadPinningDetector pinningDetector = new ThreadPinningDetector();
    
    // Create blob stores concurrently using virtual threads
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final int index = i;
      executor.submit(() -> {
        try {
          // Start monitoring for thread pinning
          pinningDetector.startMonitoring();
          
          // Create a unique blob store for each thread
          Map<String, Map<String, Object>> attributes = new HashMap<>();
          Map<String, Object> fileAttributes = new HashMap<>();
          fileAttributes.put("path", "path/to/blobs/blob" + index);
          attributes.put("file", fileAttributes);
          
          BlobStoreXO blobStoreXO = new BlobStoreXO()
              .withName("blob" + index)
              .withType("File")
              .withIsQuotaEnabled(true)
              .withQuotaType("spaceUsedQuota")
              .withQuotaLimit(10L)
              .withAttributes(attributes);
          
          // Configure mock for this specific blob store
          MockBlobStoreConfiguration config = new MockBlobStoreConfiguration()
              .withName("blob" + index)
              .withType("File")
              .withAttributes(Map.of(
                  "file", Map.of("path", "path/to/blobs/blob" + index),
                  "blobStoreQuotaConfig", Map.of("quotaType", "spaceUsedQuota", "quotaLimit", 10L * MILLION)));
          when(blobStore.getBlobStoreConfiguration()).thenReturn(config);
          
          // Create the blob store
          underTest.create(blobStoreXO);
        } 
        catch (Exception e) {
          // Ignore exceptions for this test
        } 
        finally {
          // Stop monitoring and check for pinning
          pinningDetector.stopMonitoring();
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Shutdown the executor
    executor.shutdown();
    
    // Verify no thread pinning occurred
    assertThat("No thread pinning should occur during blob store operations", 
        pinningDetector.getPinningEvents().isEmpty(), is(true));
  }

  private static final long MILLION = 1_000_000;
}