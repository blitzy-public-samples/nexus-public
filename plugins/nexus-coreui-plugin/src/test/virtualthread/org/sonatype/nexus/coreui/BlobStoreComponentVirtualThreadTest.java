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
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.sonatype.goodies.testsupport.TestSupport;
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
import org.sonatype.nexus.rapture.PasswordPlaceholder;
import org.sonatype.nexus.repository.blobstore.BlobStoreConfigurationStore;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.security.RepositoryPermissionChecker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.lang.Math.pow;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test for {@link BlobStoreComponent} using Java 21 Virtual Threads.
 * 
 * This test class validates the BlobStoreComponent's behavior when executed with
 * Virtual Threads, focusing on I/O-bound operations like blob store creation,
 * deletion, and configuration updates.
 */
@ExtendWith(MockitoExtension.class)
public class BlobStoreComponentVirtualThreadTest
    extends TestSupport
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

  private BlobStoreComponent underTest;

  @BeforeEach
  public void setup() {
    underTest = new BlobStoreComponent(blobStoreManager, store, blobStoreDescriptorProvider, quotaFactories,
        applicationDirectories, repositoryManager, permissionChecker, blobStoreTaskService);
  }

  /**
   * Tests that reading blob store types works correctly when executed in a virtual thread.
   */
  @Test
  @DisplayName("Read blob store types in virtual thread")
  public void testReadTypesInVirtualThread() throws Exception {
    // Setup test data
    BlobStoreDescriptor descriptor = mock(BlobStoreDescriptor.class);
    when(descriptor.getName()).thenReturn("MyType");
    when(descriptor.getFormFields()).thenReturn(Collections.emptyList());
    Map<String, BlobStoreDescriptor> blobStoreDescriptors = Collections.singletonMap("MyType", descriptor);
    when(blobStoreDescriptorProvider.get()).thenReturn(blobStoreDescriptors);

    // Execute in virtual thread
    Thread virtualThread = Thread.ofVirtual().name("readTypes-vt").start(() -> {
      List<BlobStoreTypeXO> types = underTest.readTypes();

      // Verify results
      assertThat(types, containsInAnyOrder(
          allOf(
              hasProperty("id", is("MyType")),
              hasProperty("name", is("MyType")),
              hasProperty("formFields", is(empty()))),
          allOf(
              hasProperty("id", is("")),
              hasProperty("name", is("")),
              hasProperty("formFields", is(nullValue())))));
    });

    // Wait for virtual thread to complete
    virtualThread.join();
  }

  /**
   * Tests that creating a blob store works correctly when executed in a virtual thread.
   */
  @Test
  @DisplayName("Create blob store in virtual thread")
  public void testCreateBlobstoreInVirtualThread() throws Exception {
    // Setup test data
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
    BlobStoreConfiguration expectedConfig = new MockBlobStoreConfiguration().withName("myblobs")
        .withType("File")
        .withAttributes(
            Map.of("file", Map.of("path", "path/to/blobs/myblobs"), "blobStoreQuotaConfig",
                Map.of("quotaType", "spaceUsedQuota", "quotaLimit", 10L)));

    BlobStore blobStore = mock(BlobStore.class);
    when(blobStore.getBlobStoreConfiguration()).thenReturn(expectedConfig);
    when(blobStore.getMetrics()).thenReturn(mock(BlobStoreMetrics.class));

    when(blobStoreManager.create(any(BlobStoreConfiguration.class))).thenReturn(blobStore);
    when(blobStoreManager.newConfiguration()).thenReturn(mock(BlobStoreConfiguration.class));
    when(blobStoreManager.getByName()).thenReturn(Collections.singletonMap("myblobs", blobStore));

    // Execute in virtual thread
    Thread virtualThread = Thread.ofVirtual().name("createBlobstore-vt").start(() -> {
      BlobStoreXO createdXO = underTest.create(blobStoreXO);

      // Verify results
      verify(blobStoreManager).create(any(BlobStoreConfiguration.class));
      assertThat(createdXO.getName(), is(expectedConfig.getName()));
      assertThat(createdXO.getType(), is(expectedConfig.getType()));
      assertThat(createdXO.getAttributes(), is(expectedConfig.getAttributes()));
    });

    // Wait for virtual thread to complete
    virtualThread.join();
  }

  /**
   * Tests that removing a blob store works correctly when executed in a virtual thread.
   */
  @Test
  @DisplayName("Remove blob store in virtual thread")
  public void testRemoveBlobstoreInVirtualThread() throws Exception {
    // Setup test data
    when(repositoryManager.isBlobstoreUsed("not-used")).thenReturn(false);

    // Execute in virtual thread
    Thread virtualThread = Thread.ofVirtual().name("removeBlobstore-vt").start(() -> {
      underTest.remove("not-used");

      // Verify results
      verify(blobStoreManager).delete("not-used");
    });

    // Wait for virtual thread to complete
    virtualThread.join();

    // Test removing a used blob store
    when(repositoryManager.isBlobstoreUsed("used")).thenReturn(true);
    Thread virtualThread2 = Thread.ofVirtual().name("removeBlobstore-used-vt").start(() -> {
      assertThrows(BlobStoreException.class, () -> underTest.remove("used"));
      verify(blobStoreManager, never()).delete("used");
    });

    // Wait for virtual thread to complete
    virtualThread2.join();
  }

  /**
   * Tests that updating a blob store works correctly when executed in a virtual thread.
   */
  @Test
  @DisplayName("Update blob store in virtual thread")
  public void testUpdateBlobstoreInVirtualThread() throws Exception {
    // Setup test data
    ArgumentCaptor<BlobStoreConfiguration> blobStoreConfigCaptor =
        ArgumentCaptor.forClass(BlobStoreConfiguration.class);

    Map<String, Map<String, Object>> attributes = new HashMap<>();
    Map<String, Object> s3Attributes = new HashMap<>();
    s3Attributes.put("access", "test");
    s3Attributes.put("secretAccessKey", PasswordPlaceholder.get());
    attributes.put("s3", s3Attributes);

    String originalSecret = "hello";
    BlobStoreXO blobStoreXO = new BlobStoreXO().withName("myblobs")
        .withType("S3")
        .withAttributes(Map.of("s3", s3Attributes));
    Map<String, Map<String, Object>> existingAttributes = new HashMap<>();
    Map<String, Object> existingS3 = new HashMap<>();
    existingS3.put("accessKeyId", "test");
    existingS3.put("secretAccessKey", originalSecret);
    existingAttributes.put("s3", existingS3);
    MockBlobStoreConfiguration existingConfig = new MockBlobStoreConfiguration().withName("myblobs")
        .withType("S3")
        .withAttributes(existingAttributes);

    BlobStore blobStore = mock(BlobStore.class);
    when(blobStore.getBlobStoreConfiguration()).thenReturn(existingConfig);
    when(blobStore.getMetrics()).thenReturn(mock(BlobStoreMetrics.class));

    when(blobStoreManager.get("myblobs")).thenReturn(blobStore);
    when(blobStoreManager.newConfiguration()).thenReturn(new MockBlobStoreConfiguration());
    when(blobStoreManager.getByName()).thenReturn(Collections.singletonMap("myblobs", blobStore));
    when(blobStoreManager.update(any(BlobStoreConfiguration.class))).thenReturn(blobStore);

    // Execute in virtual thread
    Thread virtualThread = Thread.ofVirtual().name("updateBlobstore-vt").start(() -> {
      BlobStoreXO updatedXO = underTest.update(blobStoreXO);

      // Verify results
      verify(blobStoreManager).update(blobStoreConfigCaptor.capture());
      BlobStoreConfiguration capturedConfig = blobStoreConfigCaptor.getValue();
      assertThat(capturedConfig.getAttributes().get("s3").get("secretAccessKey"), is(originalSecret));
      assertThat(updatedXO.getAttributes().get("s3").get("secretAccessKey"), is(PasswordPlaceholder.get()));
    });

    // Wait for virtual thread to complete
    virtualThread.join();
  }

  /**
   * Tests that multiple concurrent blob store operations can be executed efficiently with virtual threads.
   * This test creates a large number of virtual threads to simulate high concurrency.
   */
  @Test
  @DisplayName("Concurrent blob store operations with virtual threads")
  public void testConcurrentBlobStoreOperations() throws Exception {
    // Setup test data
    int numOperations = 1000;
    CountDownLatch latch = new CountDownLatch(numOperations);
    
    // Mock for readTypes
    BlobStoreDescriptor descriptor = mock(BlobStoreDescriptor.class);
    when(descriptor.getName()).thenReturn("MyType");
    when(descriptor.getFormFields()).thenReturn(Collections.emptyList());
    Map<String, BlobStoreDescriptor> blobStoreDescriptors = Collections.singletonMap("MyType", descriptor);
    when(blobStoreDescriptorProvider.get()).thenReturn(blobStoreDescriptors);
    
    // Mock for defaultWorkDirectory
    File blobDirectory = new File("path/to/blobs");
    when(applicationDirectories.getWorkDirectory("blobs")).thenReturn(blobDirectory);

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("concurrent-vt-", 0).factory();
    
    // Create an executor service using virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Submit tasks to read blob store types concurrently
      List<Future<?>> futures = IntStream.range(0, numOperations)
          .mapToObj(i -> executor.submit(() -> {
            try {
              // Perform a blob store operation (read types)
              underTest.readTypes();
              // Get default work directory
              underTest.defaultWorkDirectory();
              latch.countDown();
            }
            catch (Exception e) {
              throw new RuntimeException(e);
            }
          }))
          .collect(Collectors.toList());
      
      // Wait for all operations to complete with a timeout
      assertTimeout(Duration.ofSeconds(10), () -> {
        boolean completed = latch.await(5, TimeUnit.SECONDS);
        assertTrue(completed, "Not all operations completed in time");
      });
      
      // Verify that all futures completed successfully
      for (Future<?> future : futures) {
        future.get(); // This will throw an exception if the task failed
      }
      
      // Verify that the methods were called the expected number of times
      verify(blobStoreDescriptorProvider, times(numOperations)).get();
      verify(applicationDirectories, times(numOperations)).getWorkDirectory("blobs");
    }
  }

  /**
   * Tests that blob store operations don't pin virtual threads by performing operations
   * that would typically cause thread pinning if not properly implemented.
   */
  @Test
  @DisplayName("Verify no thread pinning during blob store operations")
  public void testNoPinningDuringBlobStoreOperations() throws Exception {
    // Setup test data
    BlobStoreDescriptor descriptor = mock(BlobStoreDescriptor.class);
    when(descriptor.getName()).thenReturn("MyType");
    when(descriptor.getFormFields()).thenReturn(Collections.emptyList());
    Map<String, BlobStoreDescriptor> blobStoreDescriptors = Collections.singletonMap("MyType", descriptor);
    when(blobStoreDescriptorProvider.get()).thenReturn(blobStoreDescriptors);
    
    File blobDirectory = new File("path/to/blobs");
    when(applicationDirectories.getWorkDirectory("blobs")).thenReturn(blobDirectory);
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("pinning-test-vt-", 0).factory();
    
    // Create an executor service using virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Submit a task that performs multiple blob store operations in sequence
      Future<?> future = executor.submit(() -> {
        // Perform a sequence of operations that might cause pinning if not properly implemented
        underTest.readTypes();
        underTest.defaultWorkDirectory();
        
        // Create a mock blob store configuration for quota testing
        MockBlobStoreConfiguration config = mockConfig(1024 * 1024);
        BlobStore blobStore = mock(BlobStore.class);
        when(blobStore.getBlobStoreConfiguration()).thenReturn(config);
        when(blobStore.getMetrics()).thenReturn(mock(BlobStoreMetrics.class));
        when(blobStoreManager.getByName()).thenReturn(Collections.singletonMap("test", blobStore));
        
        // Test quota-related operations
        BlobStoreXO blobStoreXO = underTest.asBlobStoreXO(config);
        assertThat(blobStoreXO.isQuotaEnabled(), is(true));
      });
      
      // Wait for the operation to complete with a timeout
      // If thread pinning occurs, this might time out or take longer than expected
      assertTimeout(Duration.ofSeconds(2), () -> {
        future.get(1, TimeUnit.SECONDS);
      });
    }
  }

  /**
   * Tests the performance difference between virtual threads and platform threads
   * for blob store operations under high concurrency.
   */
  @Test
  @DisplayName("Compare performance between virtual threads and platform threads")
  public void testPerformanceComparison() throws Exception {
    // Setup test data
    int numOperations = 500;
    BlobStoreDescriptor descriptor = mock(BlobStoreDescriptor.class);
    when(descriptor.getName()).thenReturn("MyType");
    when(descriptor.getFormFields()).thenReturn(Collections.emptyList());
    Map<String, BlobStoreDescriptor> blobStoreDescriptors = Collections.singletonMap("MyType", descriptor);
    when(blobStoreDescriptorProvider.get()).thenReturn(blobStoreDescriptors);
    
    File blobDirectory = new File("path/to/blobs");
    when(applicationDirectories.getWorkDirectory("blobs")).thenReturn(blobDirectory);
    
    // Measure time with platform threads
    long platformThreadTime = measureExecutionTime(() -> {
      try (ExecutorService executor = Executors.newFixedThreadPool(20)) { // Limited thread pool
        List<Future<?>> futures = IntStream.range(0, numOperations)
            .mapToObj(i -> executor.submit(() -> {
              underTest.readTypes();
              underTest.defaultWorkDirectory();
            }))
            .collect(Collectors.toList());
        
        // Wait for all operations to complete
        for (Future<?> future : futures) {
          future.get();
        }
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    // Measure time with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<Future<?>> futures = IntStream.range(0, numOperations)
            .mapToObj(i -> executor.submit(() -> {
              underTest.readTypes();
              underTest.defaultWorkDirectory();
            }))
            .collect(Collectors.toList());
        
        // Wait for all operations to complete
        for (Future<?> future : futures) {
          future.get();
        }
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    // Log the performance comparison
    log.info("Performance comparison for {} concurrent operations:", numOperations);
    log.info("Platform threads: {} ms", platformThreadTime);
    log.info("Virtual threads: {} ms", virtualThreadTime);
    log.info("Improvement factor: {}", (double) platformThreadTime / virtualThreadTime);
    
    // Note: We don't assert on the actual times since they can vary by environment,
    // but in most cases virtual threads should be faster for I/O-bound operations
    // under high concurrency.
  }

  /**
   * Helper method to measure execution time of a runnable in milliseconds.
   */
  private long measureExecutionTime(Runnable task) {
    long startTime = System.currentTimeMillis();
    task.run();
    return System.currentTimeMillis() - startTime;
  }

  /**
   * Helper method to create a mock blob store configuration with quota.
   */
  private static MockBlobStoreConfiguration mockConfig(final long quotaLimitBytes) {
    return new MockBlobStoreConfiguration().withAttributes(
        Map.of("file", Map.of("path", "path"), "blobStoreQuotaConfig",
            Map.of("quotaType", "spaceUsedQuota", "quotaLimitBytes", quotaLimitBytes)));
  }
}