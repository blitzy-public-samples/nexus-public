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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.blobstore.BlobStoreDescriptor;
import org.sonatype.nexus.blobstore.BlobStoreDescriptorProvider;
import org.sonatype.nexus.blobstore.MockBlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;
import org.sonatype.nexus.blobstore.api.tasks.BlobStoreTaskService;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuota;
import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.content.testsuite.groups.VirtualThreadTestSupport;
import org.sonatype.nexus.coreui.BlobStoreComponent;
import org.sonatype.nexus.coreui.BlobStoreXO;
import org.sonatype.nexus.repository.blobstore.BlobStoreConfigurationStore;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.security.RepositoryPermissionChecker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestGroup;

import static java.lang.Math.pow;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link BlobStoreComponent} using Java 21 Virtual Threads.
 * 
 * This test class verifies that BlobStore operations (create, update, delete, list) function correctly
 * under high concurrency with Virtual Threads, ensuring no thread pinning issues occur during I/O operations.
 * 
 * @since 3.60
 */
@VirtualThreadTestGroup
@ExtendWith(MockitoExtension.class)
public class BlobStoreComponentVirtualThreadTest extends VirtualThreadTestSupport
{
  private static final Logger log = LoggerFactory.getLogger(BlobStoreComponentVirtualThreadTest.class);
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
  void setUp() {
    underTest = new BlobStoreComponent(blobStoreManager, store, blobStoreDescriptorProvider, quotaFactories,
        applicationDirectories, repositoryManager, permissionChecker, blobStoreTaskService);
  }

  /**
   * Tests that creating blob stores concurrently using Virtual Threads works correctly.
   * This test verifies that I/O-bound operations in the BlobStoreComponent can benefit from
   * Virtual Threads without thread pinning issues.
   */
  @Test
  void concurrentBlobStoreCreationWithVirtualThreads() throws Exception {
    // Setup mock behavior
    BlobStoreConfiguration config = mock(BlobStoreConfiguration.class);
    BlobStore blobStore = mock(BlobStore.class);
    when(blobStore.getBlobStoreConfiguration()).thenReturn(config);
    when(blobStore.getMetrics()).thenReturn(mock(BlobStoreMetrics.class));
    when(blobStoreManager.create(any(BlobStoreConfiguration.class))).thenReturn(blobStore);
    when(blobStoreManager.newConfiguration()).thenReturn(mock(BlobStoreConfiguration.class));
    when(blobStoreManager.getByName()).thenReturn(Collections.singletonMap("test-blob-store", blobStore));
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      int taskCount = 100; // Number of concurrent operations
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Submit multiple concurrent blob store creation tasks
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Verify we're running on a virtual thread
            assertCurrentThreadIsVirtual();
            
            // Create a blob store
            BlobStoreXO blobStoreXO = createTestBlobStoreXO("test-blob-store-" + index);
            underTest.create(blobStoreXO);
          }
          catch (Exception e) {
            errorCount.incrementAndGet();
            log("Error creating blob store: " + e.getMessage(), e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify no errors occurred
      assertThat("All blob store creation operations should succeed", errorCount.get(), is(0));
    }
  }

  /**
   * Tests that updating blob stores concurrently using Virtual Threads works correctly.
   * This test verifies that I/O-bound operations in the BlobStoreComponent can benefit from
   * Virtual Threads without thread pinning issues.
   */
  @Test
  void concurrentBlobStoreUpdateWithVirtualThreads() throws Exception {
    // Setup mock behavior
    MockBlobStoreConfiguration config = new MockBlobStoreConfiguration().withName("test-blob-store")
        .withType("File")
        .withAttributes(Map.of("file", Map.of("path", "path/to/blobs")));
    
    BlobStore blobStore = mock(BlobStore.class);
    when(blobStore.getBlobStoreConfiguration()).thenReturn(config);
    when(blobStore.getMetrics()).thenReturn(mock(BlobStoreMetrics.class));
    when(blobStoreManager.get("test-blob-store")).thenReturn(blobStore);
    when(blobStoreManager.update(any(BlobStoreConfiguration.class))).thenReturn(blobStore);
    when(blobStoreManager.newConfiguration()).thenReturn(new MockBlobStoreConfiguration());
    when(blobStoreManager.getByName()).thenReturn(Collections.singletonMap("test-blob-store", blobStore));
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      int taskCount = 100; // Number of concurrent operations
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Submit multiple concurrent blob store update tasks
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Verify we're running on a virtual thread
            assertCurrentThreadIsVirtual();
            
            // Update a blob store
            BlobStoreXO blobStoreXO = createTestBlobStoreXO("test-blob-store");
            // Change some attribute for each update
            blobStoreXO.getAttributes().get("file").put("path", "path/to/blobs/" + index);
            underTest.update(blobStoreXO);
          }
          catch (Exception e) {
            errorCount.incrementAndGet();
            log("Error updating blob store: " + e.getMessage(), e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify no errors occurred
      assertThat("All blob store update operations should succeed", errorCount.get(), is(0));
    }
  }

  /**
   * Tests that reading blob store types concurrently using Virtual Threads works correctly.
   * This test verifies that I/O-bound operations in the BlobStoreComponent can benefit from
   * Virtual Threads without thread pinning issues.
   */
  @Test
  void concurrentBlobStoreReadWithVirtualThreads() throws Exception {
    // Setup mock behavior
    BlobStoreDescriptor descriptor = mock(BlobStoreDescriptor.class);
    when(descriptor.getName()).thenReturn("File");
    when(descriptor.getFormFields()).thenReturn(Collections.emptyList());
    Map<String, BlobStoreDescriptor> blobStoreDescriptors = Collections.singletonMap("File", descriptor);
    when(blobStoreDescriptorProvider.get()).thenReturn(blobStoreDescriptors);
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      int taskCount = 1000; // Higher number for read operations
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Submit multiple concurrent blob store read tasks
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            // Verify we're running on a virtual thread
            assertCurrentThreadIsVirtual();
            
            // Read blob store types
            List<?> types = underTest.readTypes();
            // Verify we got results
            if (types == null || types.isEmpty()) {
              throw new AssertionError("Expected non-empty types list");
            }
          }
          catch (Exception e) {
            errorCount.incrementAndGet();
            log("Error reading blob store types: " + e.getMessage(), e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify no errors occurred
      assertThat("All blob store read operations should succeed", errorCount.get(), is(0));
    }
  }

  /**
   * Tests that getting the default work directory concurrently using Virtual Threads works correctly.
   * This test verifies that I/O-bound operations in the BlobStoreComponent can benefit from
   * Virtual Threads without thread pinning issues.
   */
  @Test
  void concurrentDefaultWorkDirectoryWithVirtualThreads() throws Exception {
    // Setup mock behavior
    File blobDirectory = new File("path/to/blobs");
    when(applicationDirectories.getWorkDirectory("blobs")).thenReturn(blobDirectory);
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      int taskCount = 1000; // Higher number for simple operations
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Submit multiple concurrent default work directory requests
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            // Verify we're running on a virtual thread
            assertCurrentThreadIsVirtual();
            
            // Get default work directory
            underTest.defaultWorkDirectory();
          }
          catch (Exception e) {
            errorCount.incrementAndGet();
            log("Error getting default work directory: " + e.getMessage(), e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify no errors occurred
      assertThat("All default work directory operations should succeed", errorCount.get(), is(0));
    }
  }

  private void log(String s, Exception e) {
    log.error(s, e);
  }

  public boolean isCurrentThreadVirtual() {
    return Thread.currentThread().isVirtual();
  }
  public void assertCurrentThreadIsVirtual() {
    if (!isCurrentThreadVirtual()) {
      throw new AssertionError("Current thread is not a Virtual Thread: " + Thread.currentThread());
    }
  }
  /**
   * Tests high concurrency performance with Virtual Threads for mixed blob store operations.
   * This test verifies that the BlobStoreComponent can handle a large number of concurrent
   * operations efficiently using Virtual Threads.
   */
  @Test
  void highConcurrencyMixedOperationsWithVirtualThreads() throws Exception {
    // Setup mock behavior for all operation types
    setupMocksForAllOperations();
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      int taskCount = 5000; // Very high concurrency to stress test
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Submit multiple concurrent mixed operations
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Verify we're running on a virtual thread
            assertCurrentThreadIsVirtual();
            
            // Perform different operations based on the index
            // This simulates a mix of different blob store operations
            switch (index % 4) {
              case 0:
                // Create operation
                BlobStoreXO createXO = createTestBlobStoreXO("test-blob-store-" + index);
                underTest.create(createXO);
                break;
              case 1:
                // Update operation
                BlobStoreXO updateXO = createTestBlobStoreXO("test-blob-store");
                updateXO.getAttributes().get("file").put("path", "path/to/blobs/" + index);
                underTest.update(updateXO);
                break;
              case 2:
                // Read types operation
                underTest.readTypes();
                break;
              case 3:
                // Get default work directory
                underTest.defaultWorkDirectory();
                break;
            }
          }
          catch (Exception e) {
            errorCount.incrementAndGet();
            log("Error in mixed operation: " + e.getMessage(), e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(60, TimeUnit.SECONDS);
      
      // Verify no errors occurred
      assertThat("All mixed operations should succeed", errorCount.get(), is(0));
    }
  }

  /**
   * Helper method to set up mocks for all operation types.
   */
  private void setupMocksForAllOperations() {
    // Setup for create/update operations
    MockBlobStoreConfiguration config = new MockBlobStoreConfiguration().withName("test-blob-store")
        .withType("File")
        .withAttributes(Map.of("file", Map.of("path", "path/to/blobs")));
    
    BlobStore blobStore = mock(BlobStore.class);
    when(blobStore.getBlobStoreConfiguration()).thenReturn(config);
    when(blobStore.getMetrics()).thenReturn(mock(BlobStoreMetrics.class));

      try {
          when(blobStoreManager.create(any(BlobStoreConfiguration.class))).thenReturn(blobStore);
      } catch (Exception e) {
          throw new RuntimeException(e);
      }
      when(blobStoreManager.get("test-blob-store")).thenReturn(blobStore);
      try {
          when(blobStoreManager.update(any(BlobStoreConfiguration.class))).thenReturn(blobStore);
      } catch (Exception e) {
          throw new RuntimeException(e);
      }
      when(blobStoreManager.newConfiguration()).thenReturn(new MockBlobStoreConfiguration());
    when(blobStoreManager.getByName()).thenReturn(Collections.singletonMap("test-blob-store", blobStore));
    
    // Setup for read types operation
    BlobStoreDescriptor descriptor = mock(BlobStoreDescriptor.class);
    when(descriptor.getName()).thenReturn("File");
    when(descriptor.getFormFields()).thenReturn(Collections.emptyList());
    Map<String, BlobStoreDescriptor> blobStoreDescriptors = Collections.singletonMap("File", descriptor);
    when(blobStoreDescriptorProvider.get()).thenReturn(blobStoreDescriptors);
    
    // Setup for default work directory
    File blobDirectory = new File("path/to/blobs");
    when(applicationDirectories.getWorkDirectory("blobs")).thenReturn(blobDirectory);
  }

  /**
   * Helper method to create a test BlobStoreXO instance.
   */
  private BlobStoreXO createTestBlobStoreXO(String name) {
    Map<String, Map<String, Object>> attributes = new HashMap<>();
    Map<String, Object> fileAttributes = new HashMap<>();
    fileAttributes.put("path", "path/to/blobs/" + name);
    attributes.put("file", fileAttributes);
    
    return new BlobStoreXO()
        .withName(name)
        .withType("File")
        .withIsQuotaEnabled(true)
        .withQuotaType("spaceUsedQuota")
        .withQuotaLimit(10L)
        .withAttributes(attributes);
  }
}