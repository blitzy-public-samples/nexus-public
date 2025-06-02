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

package org.sonatype.nexus.repository.internal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import jakarta.inject.Provider;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.file.FileBlobStore;
import org.sonatype.nexus.blobstore.group.BlobStoreGroup;
import org.sonatype.nexus.repository.internal.blobstore.BlobStoreStateHealthCheck;

import com.codahale.metrics.health.HealthCheck.Result;
import com.google.common.collect.Lists;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link BlobStoreStateHealthCheck} using Java 21 Virtual Threads.
 * 
 * This test suite verifies that health check operations work correctly under the new threading model,
 * particularly when checking multiple blob stores concurrently.
 */
public class BlobStoreStateHealthCheckVirtualThreadTest
    extends TestSupport
{
  @Mock
  private BlobStoreManager blobStoreManager;

  private final Provider<BlobStoreManager> blobStoreManagerProvider = () -> blobStoreManager;

  private final BlobStoreStateHealthCheck healthCheck = new BlobStoreStateHealthCheck(blobStoreManagerProvider);

  @Mock
  private BlobStore blobStore;

  @Mock
  private BlobStoreConfiguration blobStoreConfiguration;

  @Before
  public void setUp() throws Exception {
    when(blobStoreManager.browse()).thenReturn(Collections.singletonList(blobStore));
    when(blobStore.getBlobStoreConfiguration()).thenReturn(blobStoreConfiguration);
    when(blobStoreConfiguration.getName()).thenReturn("blob-store-name");
  }

  /**
   * Verifies basic health check functionality works with virtual threads.
   */
  @Test
  public void basicHealthCheckWithVirtualThreads() {
    when(blobStore.isStarted()).thenReturn(true);
    when(blobStore.isWritable()).thenReturn(true);
    when(blobStore.isStorageAvailable()).thenReturn(true);
    when(blobStoreConfiguration.getType()).thenReturn(FileBlobStore.TYPE);

    // Run health check in a virtual thread
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Result result = executor.submit(() -> healthCheck.check()).get(5, TimeUnit.SECONDS);
      assertTrue(result.isHealthy());
    } catch (Exception e) {
      throw new RuntimeException("Failed to execute health check in virtual thread", e);
    }
  }

  /**
   * Tests that unhealthy blob store state is correctly detected when using virtual threads.
   */
  @Test
  public void unhealthyStateDetectionWithVirtualThreads() {
    when(blobStore.isStarted()).thenReturn(true);
    when(blobStore.isWritable()).thenReturn(false); // Not writable
    when(blobStore.isStorageAvailable()).thenReturn(true);
    when(blobStoreConfiguration.getType()).thenReturn(FileBlobStore.TYPE);

    // Run health check in a virtual thread
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Result result = executor.submit(() -> healthCheck.check()).get(5, TimeUnit.SECONDS);
      assertFalse(result.isHealthy());
      assertThat(result.getMessage(), is("1/1 blob stores report issues<br>Blob store 'blob-store-name' reports as not writeable"));
    } catch (Exception e) {
      throw new RuntimeException("Failed to execute health check in virtual thread", e);
    }
  }

  /**
   * Tests concurrent health checks across multiple blob stores using virtual threads.
   */
  @Test
  public void concurrentHealthChecksWithVirtualThreads() throws Exception {
    // Create a large number of blob stores to test scalability
    int blobStoreCount = 50;
    List<BlobStore> blobStores = new ArrayList<>(blobStoreCount);
    
    for (int i = 0; i < blobStoreCount; i++) {
      BlobStore mockStore = mock(BlobStore.class);
      BlobStoreConfiguration mockConfig = mock(BlobStoreConfiguration.class);
      
      when(mockStore.getBlobStoreConfiguration()).thenReturn(mockConfig);
      when(mockConfig.getName()).thenReturn("blob-store-" + i);
      when(mockConfig.getType()).thenReturn(FileBlobStore.TYPE);
      
      // Make every 5th blob store unhealthy in different ways
      if (i % 15 == 0) {
        when(mockStore.isStarted()).thenReturn(false);
        when(mockStore.isWritable()).thenReturn(true);
        when(mockStore.isStorageAvailable()).thenReturn(true);
      } else if (i % 15 == 5) {
        when(mockStore.isStarted()).thenReturn(true);
        when(mockStore.isWritable()).thenReturn(false);
        when(mockStore.isStorageAvailable()).thenReturn(true);
      } else if (i % 15 == 10) {
        when(mockStore.isStarted()).thenReturn(true);
        when(mockStore.isWritable()).thenReturn(true);
        when(mockStore.isStorageAvailable()).thenReturn(false);
      } else {
        when(mockStore.isStarted()).thenReturn(true);
        when(mockStore.isWritable()).thenReturn(true);
        when(mockStore.isStorageAvailable()).thenReturn(true);
      }
      
      blobStores.add(mockStore);
    }
    
    when(blobStoreManager.browse()).thenReturn(blobStores);
    
    // Run health check in a virtual thread
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Result result = executor.submit(() -> healthCheck.check()).get(5, TimeUnit.SECONDS);
      
      // We expect 10 unhealthy blob stores (every 5th out of 50)
      assertFalse(result.isHealthy());
      assertTrue(result.getMessage().startsWith("10/50 blob stores report issues"));
    }
  }

  /**
   * Tests health check behavior with a large number of blob stores to verify scalability with virtual threads.
   */
  @Test
  public void largeScaleHealthCheckWithVirtualThreads() throws Exception {
    // Create a very large number of blob stores to test scalability
    int blobStoreCount = 200;
    List<BlobStore> blobStores = new ArrayList<>(blobStoreCount);
    
    for (int i = 0; i < blobStoreCount; i++) {
      BlobStore mockStore = mock(BlobStore.class);
      BlobStoreConfiguration mockConfig = mock(BlobStoreConfiguration.class);
      
      when(mockStore.getBlobStoreConfiguration()).thenReturn(mockConfig);
      when(mockConfig.getName()).thenReturn("blob-store-" + i);
      when(mockConfig.getType()).thenReturn(FileBlobStore.TYPE);
      
      // All stores are healthy for this test
      when(mockStore.isStarted()).thenReturn(true);
      when(mockStore.isWritable()).thenReturn(true);
      when(mockStore.isStorageAvailable()).thenReturn(true);
      
      blobStores.add(mockStore);
    }
    
    when(blobStoreManager.browse()).thenReturn(blobStores);
    
    // Run health check in a virtual thread
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Result result = executor.submit(() -> healthCheck.check()).get(5, TimeUnit.SECONDS);
      
      // All blob stores should be healthy
      assertTrue(result.isHealthy());
    }
  }

  /**
   * Tests health check with blob store groups using virtual threads.
   */
  @Test
  public void blobStoreGroupsWithVirtualThreads() throws Exception {
    BlobStore blobStore1 = mock(BlobStore.class);
    when(blobStore1.isStarted()).thenReturn(true);
    when(blobStore1.isWritable()).thenReturn(true);
    when(blobStore1.isStorageAvailable()).thenReturn(true);
    BlobStoreConfiguration blobStoreConfiguration1 = mock(BlobStoreConfiguration.class);
    when(blobStore1.getBlobStoreConfiguration()).thenReturn(blobStoreConfiguration1);
    when(blobStoreConfiguration1.getName()).thenReturn("blob-store-1");
    when(blobStoreConfiguration1.getType()).thenReturn(FileBlobStore.TYPE);

    BlobStore blobStore2 = mock(BlobStore.class);
    when(blobStore2.isStarted()).thenReturn(true);
    when(blobStore2.isWritable()).thenReturn(true);
    when(blobStore2.isStorageAvailable()).thenReturn(true);
    BlobStoreConfiguration blobStoreConfiguration2 = mock(BlobStoreConfiguration.class);
    when(blobStore2.getBlobStoreConfiguration()).thenReturn(blobStoreConfiguration2);
    when(blobStoreConfiguration2.getName()).thenReturn("blob-store-2");
    when(blobStoreConfiguration2.getType()).thenReturn(FileBlobStore.TYPE);

    BlobStoreGroup blobStore3 = mock(BlobStoreGroup.class);
    when(blobStore3.getMembers()).thenReturn(Lists.newArrayList(blobStore1, blobStore2));
    when(blobStore3.isStarted()).thenReturn(true);
    when(blobStore3.isWritable()).thenReturn(true);
    when(blobStore3.isStorageAvailable()).thenReturn(true);
    BlobStoreConfiguration blobStoreConfiguration3 = mock(BlobStoreConfiguration.class);
    when(blobStore3.getBlobStoreConfiguration()).thenReturn(blobStoreConfiguration3);
    when(blobStoreConfiguration3.getName()).thenReturn("group-blob-store");
    when(blobStoreConfiguration3.getType()).thenReturn(BlobStoreGroup.TYPE);

    when(blobStoreManager.browse()).thenReturn(Lists.newArrayList(blobStore1, blobStore2, blobStore3));

    // Run health check in a virtual thread
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Result result = executor.submit(() -> healthCheck.check()).get(5, TimeUnit.SECONDS);
      assertTrue(result.isHealthy());
    }
  }

  /**
   * Compares performance between virtual threads and platform threads for health checks.
   */
  @Test
  public void performanceComparisonTest() throws Exception {
    // Create a large number of blob stores for performance testing
    int blobStoreCount = 100;
    List<BlobStore> blobStores = new ArrayList<>(blobStoreCount);
    
    for (int i = 0; i < blobStoreCount; i++) {
      BlobStore mockStore = mock(BlobStore.class);
      BlobStoreConfiguration mockConfig = mock(BlobStoreConfiguration.class);
      
      when(mockStore.getBlobStoreConfiguration()).thenReturn(mockConfig);
      when(mockConfig.getName()).thenReturn("blob-store-" + i);
      when(mockConfig.getType()).thenReturn(FileBlobStore.TYPE);
      
      // All stores are healthy for this test
      when(mockStore.isStarted()).thenReturn(true);
      when(mockStore.isWritable()).thenReturn(true);
      when(mockStore.isStorageAvailable()).thenReturn(true);
      
      blobStores.add(mockStore);
    }
    
    when(blobStoreManager.browse()).thenReturn(blobStores);
    
    // Test with platform threads
    long platformThreadStart = System.nanoTime();
    try (ExecutorService executor = Executors.newFixedThreadPool(10)) {
      Result result = executor.submit(() -> healthCheck.check()).get(5, TimeUnit.SECONDS);
      assertTrue(result.isHealthy());
    }
    long platformThreadDuration = System.nanoTime() - platformThreadStart;
    
    // Test with virtual threads
    long virtualThreadStart = System.nanoTime();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Result result = executor.submit(() -> healthCheck.check()).get(5, TimeUnit.SECONDS);
      assertTrue(result.isHealthy());
    }
    long virtualThreadDuration = System.nanoTime() - virtualThreadStart;
    
    // Log the performance comparison
    log.info("Platform thread execution time: {} ms", TimeUnit.NANOSECONDS.toMillis(platformThreadDuration));
    log.info("Virtual thread execution time: {} ms", TimeUnit.NANOSECONDS.toMillis(virtualThreadDuration));
  }

  /**
   * Tests concurrent execution of multiple health checks using virtual threads.
   */
  @Test
  public void concurrentExecutionTest() throws Exception {
    // Create a moderate number of blob stores
    int blobStoreCount = 20;
    List<BlobStore> blobStores = new ArrayList<>(blobStoreCount);
    
    for (int i = 0; i < blobStoreCount; i++) {
      BlobStore mockStore = mock(BlobStore.class);
      BlobStoreConfiguration mockConfig = mock(BlobStoreConfiguration.class);
      
      when(mockStore.getBlobStoreConfiguration()).thenReturn(mockConfig);
      when(mockConfig.getName()).thenReturn("blob-store-" + i);
      when(mockConfig.getType()).thenReturn(FileBlobStore.TYPE);
      
      // All stores are healthy for this test
      when(mockStore.isStarted()).thenReturn(true);
      when(mockStore.isWritable()).thenReturn(true);
      when(mockStore.isStorageAvailable()).thenReturn(true);
      
      blobStores.add(mockStore);
    }
    
    when(blobStoreManager.browse()).thenReturn(blobStores);
    
    // Number of concurrent health checks to run
    int concurrentChecks = 1000;
    CountDownLatch latch = new CountDownLatch(concurrentChecks);
    
    // Use virtual threads for concurrent execution
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit many concurrent health checks
      for (int i = 0; i < concurrentChecks; i++) {
        executor.submit(() -> {
          try {
            Result result = healthCheck.check();
            assertTrue(result.isHealthy());
            return result;
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all health checks to complete
      boolean completed = latch.await(10, TimeUnit.SECONDS);
      assertTrue("Failed to complete all concurrent health checks in time", completed);
    }
  }
}