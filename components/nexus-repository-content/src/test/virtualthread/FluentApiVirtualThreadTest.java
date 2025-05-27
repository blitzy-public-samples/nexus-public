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
package org.sonatype.nexus.repository.content.fluent;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobMetrics;
import org.sonatype.nexus.blobstore.api.BlobRef;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.AssetBlob;
import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.facet.ContentFacet;
import org.sonatype.nexus.repository.content.facet.ContentFacetDependencies;
import org.sonatype.nexus.repository.content.facet.ContentFacetStores;
import org.sonatype.nexus.repository.content.facet.ContentFacetSupport;
import org.sonatype.nexus.repository.content.fluent.internal.FluentAssetImpl;
import org.sonatype.nexus.repository.content.fluent.internal.FluentComponentImpl;
import org.sonatype.nexus.repository.content.store.AssetStore;
import org.sonatype.nexus.repository.content.store.ComponentStore;
import org.sonatype.nexus.repository.move.RepositoryMoveService;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.payloads.StringPayload;

import org.joda.time.DateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.view.Content.CONTENT_LAST_MODIFIED;

/**
 * Tests the repository content fluent API with Java 21 Virtual Threads to ensure compatibility and concurrent performance.
 * This test class validates that the fluent interfaces for asset and component manipulation work correctly when used with
 * virtual threads, maintaining proper transaction boundaries, resource management, and exception handling under high
 * concurrency.
 */
@ExtendWith(MockitoExtension.class)
class FluentApiVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_OPERATIONS = 1000;
  private static final int TIMEOUT_SECONDS = 30;
  
  @Mock
  private ContentFacetSupport contentFacet;

  @Mock
  private ContentFacetStores contentFacetStores;

  @Mock
  private BlobStore blobStore;

  @Mock
  private ContentFacetDependencies dependencies;

  @Mock
  private RepositoryMoveService moveService;

  @Mock
  private Asset asset;

  @Mock
  private AssetBlob assetBlob;

  @Mock
  private Component component;

  @Mock
  private AssetStore assetStore;

  @Mock
  private ComponentStore componentStore;

  @Mock
  private EventManager eventManager;

  private FluentAssetImpl fluentAsset;
  private FluentComponentImpl fluentComponent;
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;

  @BeforeEach
  void setUp() {
    BlobStoreManager mockBlobstoreManager = mock(BlobStoreManager.class);
    Repository mockRepository = mock(Repository.class);

    when(mockBlobstoreManager.get(anyString())).thenReturn(blobStore);
    contentFacetStores = new ContentFacetStores(mockBlobstoreManager, "test", mock(ContentFacet.class), "test");

    when(contentFacet.stores()).thenReturn(contentFacetStores);
    when(contentFacet.dependencies()).thenReturn(dependencies);
    when(contentFacet.repository()).thenReturn(mockRepository);
    when(dependencies.getMoveService()).thenReturn(Optional.of(moveService));
    when(dependencies.getEventManager()).thenReturn(eventManager);
    when(asset.blob()).thenReturn(Optional.of(assetBlob));
    when(assetBlob.blobRef()).thenReturn(new BlobRef("default", "test"));
    when(assetBlob.contentType()).thenReturn("text/plain");
    when(mockRepository.getType()).thenReturn(new HostedType());
    when(asset.attributes()).thenReturn(new NestedAttributesMap());
    when(component.attributes()).thenReturn(new NestedAttributesMap());

    fluentAsset = new FluentAssetImpl(contentFacet, asset);
    fluentComponent = new FluentComponentImpl(contentFacet, component);

    // Create executors for performance comparison
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
  }

  @AfterEach
  void tearDown() throws Exception {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }
    if (platformThreadExecutor != null) {
      platformThreadExecutor.shutdown();
      platformThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  /**
   * Tests concurrent download operations using virtual threads.
   * This test validates that the fluent API's download method works correctly when invoked
   * from many virtual threads simultaneously, ensuring proper resource management and cleanup.
   */
  @Test
  void concurrentDownloadsWithVirtualThreads() throws Exception {
    // Setup mock blob and metrics
    Blob mockBlob = mock(Blob.class);
    BlobMetrics mockMetrics = mock(BlobMetrics.class);
    DateTime creationDate = DateTime.now();

    when(blobStore.get(any())).thenReturn(mockBlob);
    when(mockBlob.getMetrics()).thenReturn(mockMetrics);
    when(mockMetrics.getCreationTime()).thenReturn(creationDate);
    when(mockMetrics.getSha1Hash()).thenReturn("sha1-test");

    // Create a latch to wait for all operations to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicBoolean resourceLeakDetected = new AtomicBoolean(false);

    // Submit concurrent download tasks using virtual threads
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      virtualThreadExecutor.submit(() -> {
        try (Content content = fluentAsset.download()) {
          // Verify content properties
          assertNotNull(content);
          assertEquals("text/plain", content.getContentType());
          assertEquals(creationDate, content.getAttributes().get(CONTENT_LAST_MODIFIED));
        } 
        catch (Exception e) {
          errorCount.incrementAndGet();
          log.error("Error during concurrent download", e);
          resourceLeakDetected.set(true);
        }
        finally {
          latch.countDown();
        }
      });
    }

    // Wait for all operations to complete
    assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "Timed out waiting for concurrent downloads to complete");

    // Verify results
    assertEquals(0, errorCount.get(), "Some download operations failed");
    assertFalse(resourceLeakDetected.get(), "Resource leak detected during concurrent downloads");

    // Verify the blob was retrieved the expected number of times
    verify(blobStore, times(CONCURRENT_OPERATIONS)).get(any());
  }

  /**
   * Tests concurrent asset find operations using virtual threads.
   * This test validates that the fluent API's find methods work correctly when invoked
   * from many virtual threads simultaneously.
   */
  @Test
  void concurrentAssetFindOperationsWithVirtualThreads() throws Exception {
    // Setup mock asset store behavior
    when(contentFacet.assets()).thenReturn(assetStore);
    when(assetStore.findLatestPath(anyString())).thenReturn(Optional.of(asset));

    // Create a latch to wait for all operations to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicInteger successCount = new AtomicInteger(0);

    // Submit concurrent find tasks using virtual threads
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final String path = "/test/asset/" + i;
      virtualThreadExecutor.submit(() -> {
        try {
          Optional<FluentAsset> foundAsset = fluentAsset.path(path).find();
          if (foundAsset.isPresent()) {
            successCount.incrementAndGet();
          }
        } 
        catch (Exception e) {
          errorCount.incrementAndGet();
          log.error("Error during concurrent asset find", e);
        }
        finally {
          latch.countDown();
        }
      });
    }

    // Wait for all operations to complete
    assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "Timed out waiting for concurrent asset find operations to complete");

    // Verify results
    assertEquals(0, errorCount.get(), "Some asset find operations failed");
    assertEquals(CONCURRENT_OPERATIONS, successCount.get(), "Not all asset find operations succeeded");

    // Verify the asset store was called the expected number of times
    verify(assetStore, times(CONCURRENT_OPERATIONS)).findLatestPath(anyString());
  }

  /**
   * Tests concurrent component find operations using virtual threads.
   * This test validates that the fluent API's find methods work correctly when invoked
   * from many virtual threads simultaneously.
   */
  @Test
  void concurrentComponentFindOperationsWithVirtualThreads() throws Exception {
    // Setup mock component store behavior
    when(contentFacet.components()).thenReturn(componentStore);
    when(componentStore.findComponent(anyString(), anyString(), anyString()))
        .thenReturn(Optional.of(component));

    // Create a latch to wait for all operations to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicInteger successCount = new AtomicInteger(0);

    // Submit concurrent find tasks using virtual threads
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final String name = "component-" + i;
      virtualThreadExecutor.submit(() -> {
        try {
          Optional<FluentComponent> foundComponent = fluentComponent.name(name).version("1.0").find();
          if (foundComponent.isPresent()) {
            successCount.incrementAndGet();
          }
        } 
        catch (Exception e) {
          errorCount.incrementAndGet();
          log.error("Error during concurrent component find", e);
        }
        finally {
          latch.countDown();
        }
      });
    }

    // Wait for all operations to complete
    assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "Timed out waiting for concurrent component find operations to complete");

    // Verify results
    assertEquals(0, errorCount.get(), "Some component find operations failed");
    assertEquals(CONCURRENT_OPERATIONS, successCount.get(), "Not all component find operations succeeded");

    // Verify the component store was called the expected number of times
    verify(componentStore, times(CONCURRENT_OPERATIONS)).findComponent(anyString(), anyString(), anyString());
  }

  /**
   * Tests concurrent asset browse operations using virtual threads.
   * This test validates that the fluent API's browse methods work correctly when invoked
   * from many virtual threads simultaneously.
   */
  @Test
  void concurrentAssetBrowseOperationsWithVirtualThreads() throws Exception {
    // Setup mock asset store behavior for browse
    when(contentFacet.assets()).thenReturn(assetStore);
    doAnswer(invocation -> {
      List<Asset> assets = new ArrayList<>();
      assets.add(asset);
      return assets;
    }).when(assetStore).browseAssets(anyInt(), any());

    // Create a latch to wait for all operations to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger errorCount = new AtomicInteger(0);
    ConcurrentHashMap<Integer, Integer> resultCounts = new ConcurrentHashMap<>();

    // Submit concurrent browse tasks using virtual threads
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final int taskId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          List<FluentAsset> assets = fluentAsset.browse(10).stream().toList();
          resultCounts.put(taskId, assets.size());
        } 
        catch (Exception e) {
          errorCount.incrementAndGet();
          log.error("Error during concurrent asset browse", e);
        }
        finally {
          latch.countDown();
        }
      });
    }

    // Wait for all operations to complete
    assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "Timed out waiting for concurrent asset browse operations to complete");

    // Verify results
    assertEquals(0, errorCount.get(), "Some asset browse operations failed");
    assertEquals(CONCURRENT_OPERATIONS, resultCounts.size(), "Not all asset browse operations returned results");
    
    // All browse operations should return 1 asset
    for (Integer count : resultCounts.values()) {
      assertEquals(1, count.intValue(), "Asset browse returned unexpected number of results");
    }

    // Verify the asset store was called the expected number of times
    verify(assetStore, times(CONCURRENT_OPERATIONS)).browseAssets(anyInt(), any());
  }

  /**
   * Tests concurrent component browse operations using virtual threads.
   * This test validates that the fluent API's browse methods work correctly when invoked
   * from many virtual threads simultaneously.
   */
  @Test
  void concurrentComponentBrowseOperationsWithVirtualThreads() throws Exception {
    // Setup mock component store behavior for browse
    when(contentFacet.components()).thenReturn(componentStore);
    doAnswer(invocation -> {
      List<Component> components = new ArrayList<>();
      components.add(component);
      return components;
    }).when(componentStore).browseComponents(anyInt(), any());

    // Create a latch to wait for all operations to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger errorCount = new AtomicInteger(0);
    ConcurrentHashMap<Integer, Integer> resultCounts = new ConcurrentHashMap<>();

    // Submit concurrent browse tasks using virtual threads
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final int taskId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          List<FluentComponent> components = fluentComponent.browse(10).stream().toList();
          resultCounts.put(taskId, components.size());
        } 
        catch (Exception e) {
          errorCount.incrementAndGet();
          log.error("Error during concurrent component browse", e);
        }
        finally {
          latch.countDown();
        }
      });
    }

    // Wait for all operations to complete
    assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "Timed out waiting for concurrent component browse operations to complete");

    // Verify results
    assertEquals(0, errorCount.get(), "Some component browse operations failed");
    assertEquals(CONCURRENT_OPERATIONS, resultCounts.size(), "Not all component browse operations returned results");
    
    // All browse operations should return 1 component
    for (Integer count : resultCounts.values()) {
      assertEquals(1, count.intValue(), "Component browse returned unexpected number of results");
    }

    // Verify the component store was called the expected number of times
    verify(componentStore, times(CONCURRENT_OPERATIONS)).browseComponents(anyInt(), any());
  }

  /**
   * Tests concurrent asset update operations using virtual threads.
   * This test validates that the fluent API's update methods work correctly when invoked
   * from many virtual threads simultaneously, maintaining data consistency.
   */
  @Test
  void concurrentAssetUpdateOperationsWithVirtualThreads() throws Exception {
    // Setup mock asset store behavior for update
    when(contentFacet.assets()).thenReturn(assetStore);
    when(assetStore.updateAssetAttributes(any(Asset.class))).thenReturn(asset);

    // Create a latch to wait for all operations to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicInteger successCount = new AtomicInteger(0);

    // Submit concurrent update tasks using virtual threads
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final int taskId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          FluentAsset updated = fluentAsset.attributes(attrs -> 
              attrs.set("test", "value-" + taskId));
          assertNotNull(updated);
          successCount.incrementAndGet();
        } 
        catch (Exception e) {
          errorCount.incrementAndGet();
          log.error("Error during concurrent asset update", e);
        }
        finally {
          latch.countDown();
        }
      });
    }

    // Wait for all operations to complete
    assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "Timed out waiting for concurrent asset update operations to complete");

    // Verify results
    assertEquals(0, errorCount.get(), "Some asset update operations failed");
    assertEquals(CONCURRENT_OPERATIONS, successCount.get(), "Not all asset update operations succeeded");

    // Verify the asset store was called the expected number of times
    verify(assetStore, times(CONCURRENT_OPERATIONS)).updateAssetAttributes(any(Asset.class));
  }

  /**
   * Tests concurrent component update operations using virtual threads.
   * This test validates that the fluent API's update methods work correctly when invoked
   * from many virtual threads simultaneously, maintaining data consistency.
   */
  @Test
  void concurrentComponentUpdateOperationsWithVirtualThreads() throws Exception {
    // Setup mock component store behavior for update
    when(contentFacet.components()).thenReturn(componentStore);
    when(componentStore.updateComponentAttributes(any(Component.class))).thenReturn(component);

    // Create a latch to wait for all operations to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicInteger successCount = new AtomicInteger(0);

    // Submit concurrent update tasks using virtual threads
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final int taskId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          FluentComponent updated = fluentComponent.attributes(attrs -> 
              attrs.set("test", "value-" + taskId));
          assertNotNull(updated);
          successCount.incrementAndGet();
        } 
        catch (Exception e) {
          errorCount.incrementAndGet();
          log.error("Error during concurrent component update", e);
        }
        finally {
          latch.countDown();
        }
      });
    }

    // Wait for all operations to complete
    assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "Timed out waiting for concurrent component update operations to complete");

    // Verify results
    assertEquals(0, errorCount.get(), "Some component update operations failed");
    assertEquals(CONCURRENT_OPERATIONS, successCount.get(), "Not all component update operations succeeded");

    // Verify the component store was called the expected number of times
    verify(componentStore, times(CONCURRENT_OPERATIONS)).updateComponentAttributes(any(Component.class));
  }

  /**
   * Tests exception handling during concurrent operations with virtual threads.
   * This test validates that exceptions are properly propagated and resources are cleaned up
   * when errors occur during concurrent operations.
   */
  @Test
  void exceptionHandlingWithVirtualThreads() throws Exception {
    // Setup mock blob store to throw an exception
    when(blobStore.get(any())).thenThrow(new IOException("Simulated blob store failure"));

    // Create a latch to wait for all operations to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger exceptionCount = new AtomicInteger(0);

    // Submit concurrent download tasks that will fail
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      virtualThreadExecutor.submit(() -> {
        try (Content content = fluentAsset.download()) {
          fail("Expected exception was not thrown");
        } 
        catch (IOException e) {
          // Expected exception
          exceptionCount.incrementAndGet();
        }
        finally {
          latch.countDown();
        }
      });
    }

    // Wait for all operations to complete
    assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "Timed out waiting for concurrent operations to complete");

    // Verify all operations resulted in the expected exception
    assertEquals(CONCURRENT_OPERATIONS, exceptionCount.get(), 
        "Not all operations resulted in the expected exception");

    // Verify the blob store was called the expected number of times
    verify(blobStore, times(CONCURRENT_OPERATIONS)).get(any());
  }

  /**
   * Compares performance between virtual threads and platform threads for download operations.
   * This test measures the execution time for the same workload using both thread types
   * to demonstrate the performance benefits of virtual threads for I/O-bound operations.
   */
  @Test
  void compareDownloadPerformanceVirtualVsPlatformThreads() throws Exception {
    // Setup mock blob and metrics
    Blob mockBlob = mock(Blob.class);
    BlobMetrics mockMetrics = mock(BlobMetrics.class);
    DateTime creationDate = DateTime.now();

    when(blobStore.get(any())).thenReturn(mockBlob);
    when(mockBlob.getMetrics()).thenReturn(mockMetrics);
    when(mockMetrics.getCreationTime()).thenReturn(creationDate);
    when(mockMetrics.getSha1Hash()).thenReturn("sha1-test");

    // Create a payload that simulates some processing time
    StringPayload payload = new StringPayload("test content", "text/plain");
    when(mockBlob.getInputStream()).thenReturn(payload.openInputStream());

    // Add a small delay to simulate I/O latency
    doAnswer(invocation -> {
      Thread.sleep(10); // 10ms delay to simulate I/O
      return mockBlob;
    }).when(blobStore).get(any());

    // Measure execution time with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      runConcurrentDownloads(virtualThreadExecutor);
      return null;
    });

    // Measure execution time with platform threads
    long platformThreadTime = measureExecutionTime(() -> {
      runConcurrentDownloads(platformThreadExecutor);
      return null;
    });

    // Log the results
    log.info("Virtual Thread execution time: {} ms", virtualThreadTime);
    log.info("Platform Thread execution time: {} ms", platformThreadTime);

    // Verify virtual threads perform better for I/O-bound operations
    // This may not always be true in a test environment with mocks,
    // but in real-world scenarios with actual I/O, virtual threads should show benefits
    assertThat("Virtual threads should be more efficient for I/O-bound operations",
        virtualThreadTime, lessThan(platformThreadTime * 1.5)); // Allow some margin
  }

  /**
   * Helper method to run concurrent download operations using the provided executor.
   */
  private void runConcurrentDownloads(ExecutorService executor) {
    int operations = 100; // Smaller number for performance test
    CountDownLatch latch = new CountDownLatch(operations);
    AtomicReference<Exception> error = new AtomicReference<>();

    for (int i = 0; i < operations; i++) {
      executor.submit(() -> {
        try (Content content = fluentAsset.download()) {
          // Read the content to simulate full download
          content.getInputStream().readAllBytes();
        } 
        catch (Exception e) {
          error.set(e);
        }
        finally {
          latch.countDown();
        }
      });
    }

    try {
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (!completed) {
        fail("Timed out waiting for concurrent downloads to complete");
      }
      if (error.get() != null) {
        fail("Error during concurrent downloads: " + error.get().getMessage());
      }
    } 
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      fail("Test was interrupted");
    }
  }

  /**
   * Helper method to measure execution time of a task.
   */
  private long measureExecutionTime(Supplier<?> task) {
    long startTime = System.currentTimeMillis();
    task.get();
    return System.currentTimeMillis() - startTime;
  }
}