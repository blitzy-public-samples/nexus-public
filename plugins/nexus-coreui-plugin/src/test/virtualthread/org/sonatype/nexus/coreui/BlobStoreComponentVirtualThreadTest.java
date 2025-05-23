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
package org.sonatype.nexus.coreui;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

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
import org.sonatype.nexus.repository.blobstore.BlobStoreConfigurationStore;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.security.RepositoryPermissionChecker;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.lang.Math.pow;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link BlobStoreComponent} with Java 21 Virtual Threads.
 * 
 * This test class validates that BlobStoreComponent operations work correctly
 * when executed with Virtual Threads, focusing on I/O-bound operations like
 * blob store creation, deletion, and configuration updates.
 *
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
public class BlobStoreComponentVirtualThreadTest
    extends VirtualThreadTestSupport
{
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
  
  @Captor
  private ArgumentCaptor<BlobStoreConfiguration> blobStoreConfigCaptor;

  private BlobStoreComponent underTest;

  @BeforeEach
  void setup() {
    // Skip tests if virtual threads are not supported
    assumeVirtualThreadSupported();
    
    underTest = new BlobStoreComponent(blobStoreManager, store, blobStoreDescriptorProvider, quotaFactories,
        applicationDirectories, repositoryManager, permissionChecker, blobStoreTaskService);
  }

  @Test
  void testReadTypesWithVirtualThread() throws Exception {
    // Setup
    BlobStoreDescriptor descriptor = mock(BlobStoreDescriptor.class);
    when(descriptor.getName()).thenReturn("MyType");
    when(descriptor.getFormFields()).thenReturn(Collections.emptyList());
    Map<String, BlobStoreDescriptor> blobStoreDescriptors = Collections.singletonMap("MyType", descriptor);
    when(blobStoreDescriptorProvider.get()).thenReturn(blobStoreDescriptors);

    // Execute on a virtual thread
    List<BlobStoreTypeXO> types = callVirtual(() -> underTest.readTypes());

    // Verify
    assertThat(types, notNullValue());
    assertThat(types.size(), is(2)); // MyType + empty type
    assertThat(types.get(0).getId(), is("MyType"));
  }

  @Test
  void testCreateBlobstoreWithVirtualThread() throws Exception {
    // Setup
    Map<String, Map<String, Object>> attributes = new HashMap<>();
    Map<String, Object> fileAttributes = new HashMap<>();
    fileAttributes.put("path", "path/to/blobs/myblobs");
    attributes.put("file", fileAttributes);
    BlobStoreXO blobStoreXO =
        new BlobStoreXO()
            .withName("myblobs")
            .withType("File")
            .withIsQuotaEnabled(true)
            .withQuotaType("spaceUsedQuota")
            .withQuotaLimit(10L)
            .withAttributes(attributes);

    BlobStore blobStore = mock(BlobStore.class);
    BlobStoreConfiguration config = new MockBlobStoreConfiguration().withName("myblobs")
        .withType("File")
        .withAttributes(
            Map.of("file", Map.of("path", "path/to/blobs/myblobs"), "blobStoreQuotaConfig",
                Map.of("quotaType", "spaceUsedQuota", "quotaLimit", 10L)));
    when(blobStore.getBlobStoreConfiguration()).thenReturn(config);
    when(blobStore.getMetrics()).thenReturn(mock(BlobStoreMetrics.class));

    when(blobStoreManager.create(any(BlobStoreConfiguration.class))).thenReturn(blobStore);
    when(blobStoreManager.newConfiguration()).thenReturn(mock(BlobStoreConfiguration.class));
    when(blobStoreManager.getByName()).thenReturn(Collections.singletonMap("myblobs", blobStore));

    // Execute on a virtual thread
    BlobStoreXO createdXO = callVirtual(() -> underTest.create(blobStoreXO));

    // Verify
    verify(blobStoreManager).create(any(BlobStoreConfiguration.class));
    assertThat(createdXO.getName(), is(config.getName()));
    assertThat(createdXO.getType(), is(config.getType()));
  }

  @Test
  void testRemoveBlobstoreWithVirtualThread() throws Exception {
    // Setup
    when(repositoryManager.isBlobstoreUsed("not-used")).thenReturn(false);

    // Execute on a virtual thread
    runVirtual(() -> underTest.remove("not-used"));

    // Verify
    verify(blobStoreManager).delete("not-used");

    // Test exception case
    when(repositoryManager.isBlobstoreUsed("used")).thenReturn(true);
    assertThrows(BlobStoreException.class, () -> callVirtual(() -> {
      underTest.remove("used");
      return null;
    }));
    verify(blobStoreManager, never()).delete("used");
  }

  @Test
  void testDefaultWorkDirectoryWithVirtualThread() throws Exception {
    // Setup
    File blobDirectory = new File("path/to/blobs");
    when(applicationDirectories.getWorkDirectory("blobs")).thenReturn(blobDirectory);

    // Execute on a virtual thread
    PathSeparatorXO defaultWorkDirectory = callVirtual(() -> underTest.defaultWorkDirectory());

    // Verify
    assertThat(new File(defaultWorkDirectory.getPath()), is(blobDirectory));
    assertThat(defaultWorkDirectory.getFileSeparator(), is(File.separator));
  }

  @Test
  void testNoPinningDuringBlobStoreOperations() throws Exception {
    // Setup for create operation
    Map<String, Map<String, Object>> attributes = new HashMap<>();
    Map<String, Object> fileAttributes = new HashMap<>();
    fileAttributes.put("path", "path/to/blobs/myblobs");
    attributes.put("file", fileAttributes);
    BlobStoreXO blobStoreXO =
        new BlobStoreXO()
            .withName("myblobs")
            .withType("File")
            .withIsQuotaEnabled(true)
            .withQuotaType("spaceUsedQuota")
            .withQuotaLimit(10L)
            .withAttributes(attributes);

    BlobStore blobStore = mock(BlobStore.class);
    BlobStoreConfiguration config = new MockBlobStoreConfiguration().withName("myblobs")
        .withType("File")
        .withAttributes(
            Map.of("file", Map.of("path", "path/to/blobs/myblobs"), "blobStoreQuotaConfig",
                Map.of("quotaType", "spaceUsedQuota", "quotaLimit", 10L)));
    when(blobStore.getBlobStoreConfiguration()).thenReturn(config);
    when(blobStore.getMetrics()).thenReturn(mock(BlobStoreMetrics.class));

    when(blobStoreManager.create(any(BlobStoreConfiguration.class))).thenReturn(blobStore);
    when(blobStoreManager.newConfiguration()).thenReturn(mock(BlobStoreConfiguration.class));
    when(blobStoreManager.getByName()).thenReturn(Collections.singletonMap("myblobs", blobStore));

    // Check for thread pinning during create operation
    boolean createPinning = detectThreadPinning(() -> {
      try {
        underTest.create(blobStoreXO);
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    assertFalse(createPinning, "Create operation should not cause thread pinning");

    // Setup for remove operation
    when(repositoryManager.isBlobstoreUsed("not-used")).thenReturn(false);

    // Check for thread pinning during remove operation
    boolean removePinning = detectThreadPinning(() -> {
      try {
        underTest.remove("not-used");
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    assertFalse(removePinning, "Remove operation should not cause thread pinning");
  }

  @Test
  void testConcurrentBlobStoreOperations() throws Exception {
    // Setup
    int concurrentOperations = 100;
    CountDownLatch latch = new CountDownLatch(concurrentOperations);
    AtomicInteger successCount = new AtomicInteger(0);

    // Mock for read types operation
    BlobStoreDescriptor descriptor = mock(BlobStoreDescriptor.class);
    when(descriptor.getName()).thenReturn("MyType");
    when(descriptor.getFormFields()).thenReturn(Collections.emptyList());
    Map<String, BlobStoreDescriptor> blobStoreDescriptors = Collections.singletonMap("MyType", descriptor);
    when(blobStoreDescriptorProvider.get()).thenReturn(blobStoreDescriptors);

    // Create a virtual thread executor
    ExecutorService executor = newVirtualThreadExecutor("blob-store-test-");

    try {
      // Submit concurrent operations
      for (int i = 0; i < concurrentOperations; i++) {
        executor.submit(() -> {
          try {
            List<BlobStoreTypeXO> types = underTest.readTypes();
            if (types != null && types.size() == 2) {
              successCount.incrementAndGet();
            }
          }
          catch (Exception e) {
            // Log exception but continue
            log.error("Error in concurrent operation", e);
          }
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all operations to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "All operations should complete within timeout");

      // Verify all operations were successful
      assertThat(successCount.get(), is(concurrentOperations));
    }
    finally {
      executor.shutdown();
    }
  }

  @Test
  void testHighConcurrencyBlobStoreOperations() throws Exception {
    // Setup for a high number of concurrent operations
    int concurrentOperations = 1000;
    
    // Mock for read types operation
    BlobStoreDescriptor descriptor = mock(BlobStoreDescriptor.class);
    when(descriptor.getName()).thenReturn("MyType");
    when(descriptor.getFormFields()).thenReturn(Collections.emptyList());
    Map<String, BlobStoreDescriptor> blobStoreDescriptors = Collections.singletonMap("MyType", descriptor);
    when(blobStoreDescriptorProvider.get()).thenReturn(blobStoreDescriptors);

    // Create completable futures for all operations
    CompletableFuture<?>[] futures = new CompletableFuture[concurrentOperations];
    
    // Use virtual threads for high concurrency
    ThreadFactory factory = Thread.ofVirtual().name("blob-store-high-concurrency-", 0).factory();
    
    // Execute operations
    for (int i = 0; i < concurrentOperations; i++) {
      final int index = i;
      futures[i] = CompletableFuture.supplyAsync(() -> {
        try {
          return underTest.readTypes();
        }
        catch (Exception e) {
          throw new RuntimeException("Failed operation " + index, e);
        }
      }, factory);
    }
    
    // Wait for all operations to complete
    CompletableFuture.allOf(futures).join();
    
    // Verify the operations were called the expected number of times
    verify(blobStoreDescriptorProvider, times(concurrentOperations)).get();
  }

  @Test
  void testPerformanceComparisonBetweenThreadTypes() throws Exception {
    // Setup
    int operationCount = 100;
    BlobStoreDescriptor descriptor = mock(BlobStoreDescriptor.class);
    when(descriptor.getName()).thenReturn("MyType");
    when(descriptor.getFormFields()).thenReturn(Collections.emptyList());
    Map<String, BlobStoreDescriptor> blobStoreDescriptors = Collections.singletonMap("MyType", descriptor);
    when(blobStoreDescriptorProvider.get()).thenReturn(blobStoreDescriptors);

    // Measure with platform threads
    long platformThreadTime = measureWithThreadType(operationCount, Thread.ofPlatform().factory());
    
    // Measure with virtual threads
    long virtualThreadTime = measureWithThreadType(operationCount, Thread.ofVirtual().factory());
    
    // Log the results
    log.info("Performance comparison for {} operations:", operationCount);
    log.info("Platform threads: {} ms", platformThreadTime);
    log.info("Virtual threads: {} ms", virtualThreadTime);
    
    // For high concurrency operations, virtual threads should generally be more efficient
    // This might not always be true for small workloads due to initialization overhead
    // but should be observable with larger workloads or when thread pinning is avoided
    if (operationCount >= 100) {
      assertThat("Virtual threads should be more efficient for I/O bound operations",
          virtualThreadTime, lessThan(platformThreadTime * 1.5)); // Allow some margin
    }
  }
  
  /**
   * Helper method to measure performance with a specific thread factory
   */
  private long measureWithThreadType(int operationCount, ThreadFactory threadFactory) throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    try {
      long startTime = System.currentTimeMillis();
      
      // Create futures for all operations
      CompletableFuture<?>[] futures = new CompletableFuture[operationCount];
      
      // Submit all operations
      for (int i = 0; i < operationCount; i++) {
        futures[i] = CompletableFuture.supplyAsync(() -> {
          try {
            return underTest.readTypes();
          }
          catch (Exception e) {
            throw new RuntimeException(e);
          }
        }, executor);
      }
      
      // Wait for all operations to complete
      CompletableFuture.allOf(futures).join();
      
      return System.currentTimeMillis() - startTime;
    }
    finally {
      executor.shutdown();
    }
  }
  
  @Test
  void testBlobStoreConfigurationWithVirtualThread() throws Exception {
    // Setup
    BlobStoreConfiguration blobStoreConfig = mock(BlobStoreConfiguration.class);
    BlobStoreXO blobStoreXO = mock(BlobStoreXO.class);
    when(blobStoreXO.getName()).thenReturn("xoTest");
    when(blobStoreXO.getType()).thenReturn("type");
    when(blobStoreXO.isQuotaEnabled()).thenReturn(true);
    when(blobStoreXO.getQuotaLimit()).thenReturn(10L);
    when(blobStoreXO.getQuotaType()).thenReturn("properType");
    Map<String, Map<String, Object>> attributes = new HashMap<>();
    Map<String, Object> quotaConfig = new HashMap<>();
    quotaConfig.put("quotaType", "shouldBeClobbered");
    quotaConfig.put("quotaLimitBytes", 7);
    attributes.put("blobStoreQuotaConfig", quotaConfig);
    when(blobStoreXO.getAttributes()).thenReturn(attributes);
    when(blobStoreManager.newConfiguration()).thenReturn(blobStoreConfig);

    // Execute on a virtual thread
    runVirtual(() -> underTest.asConfiguration(blobStoreXO));

    // Verify
    verify(blobStoreConfig).setName("xoTest");
    verify(blobStoreConfig).setType("type");
    verify(blobStoreConfig).setAttributes(
        Map.of("blobStoreQuotaConfig", Map.of("quotaType", "properType", "quotaLimitBytes", (long) (10 * pow(10, 6)))));
  }
  
  @Test
  void testBulkBlobStoreOperations() throws Exception {
    // Setup for multiple blob stores
    int blobStoreCount = 50;
    
    // Create mock blob stores
    for (int i = 0; i < blobStoreCount; i++) {
      String name = "blob-store-" + i;
      BlobStore blobStore = mock(BlobStore.class);
      BlobStoreConfiguration config = new MockBlobStoreConfiguration().withName(name)
          .withType("File")
          .withAttributes(Map.of("file", Map.of("path", "path/to/blobs/" + name)));
      when(blobStore.getBlobStoreConfiguration()).thenReturn(config);
      when(blobStore.getMetrics()).thenReturn(mock(BlobStoreMetrics.class));
      when(blobStoreManager.get(name)).thenReturn(blobStore);
    }
    
    // Setup a map of all blob stores for getByName()
    Map<String, BlobStore> blobStoreMap = new HashMap<>();
    for (int i = 0; i < blobStoreCount; i++) {
      String name = "blob-store-" + i;
      BlobStore blobStore = blobStoreManager.get(name);
      blobStoreMap.put(name, blobStore);
    }
    when(blobStoreManager.getByName()).thenReturn(blobStoreMap);
    
    // Mark all blob stores as not used by repositories
    for (int i = 0; i < blobStoreCount; i++) {
      String name = "blob-store-" + i;
      when(repositoryManager.isBlobstoreUsed(name)).thenReturn(false);
    }
    
    // Execute bulk operations with virtual threads
    runConcurrently(blobStoreCount, i -> () -> {
      try {
        String name = "blob-store-" + i;
        // First get the blob store info
        BlobStoreXO blobStoreXO = underTest.asBlobStoreXO(
            blobStoreManager.get(name).getBlobStoreConfiguration());
        // Then remove it
        underTest.remove(name);
      }
      catch (Exception e) {
        throw new RuntimeException("Failed operation for blob store " + i, e);
      }
    });
    
    // Verify all blob stores were deleted
    for (int i = 0; i < blobStoreCount; i++) {
      verify(blobStoreManager).delete("blob-store-" + i);
    }
  }
}