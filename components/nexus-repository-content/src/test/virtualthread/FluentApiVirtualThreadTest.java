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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobMetrics;
import org.sonatype.nexus.blobstore.api.BlobRef;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.AssetBlob;
import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.facet.ContentFacet;
import org.sonatype.nexus.repository.content.facet.ContentFacetDependencies;
import org.sonatype.nexus.repository.content.facet.ContentFacetStores;
import org.sonatype.nexus.repository.content.facet.ContentFacetSupport;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.content.fluent.internal.FluentAssetImpl;
import org.sonatype.nexus.repository.content.fluent.internal.FluentComponentImpl;
import org.sonatype.nexus.repository.content.store.AssetStore;
import org.sonatype.nexus.repository.content.store.ComponentStore;
import org.sonatype.nexus.repository.content.store.FormatStoreManager;
import org.sonatype.nexus.repository.move.RepositoryMoveService;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.view.Content;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.view.Content.CONTENT_LAST_MODIFIED;

/**
 * Tests the repository content fluent API with Java 21 Virtual Threads to ensure compatibility
 * and concurrent performance. This test class validates that the fluent interfaces for asset and
 * component manipulation work correctly when used with virtual threads, maintaining proper
 * transaction boundaries, resource management, and exception handling under high concurrency.
 * 
 * <p>Virtual threads are lightweight threads managed by the JVM rather than the OS, making them
 * ideal for I/O-bound operations like those in the repository content fluent API. This test
 * specifically focuses on validating that the fluent API operations work correctly when executed
 * concurrently with virtual threads.</p>
 * 
 * <p>The tests cover:</p>
 * <ul>
 *   <li>Asset and component find/browse operations</li>
 *   <li>Update operations maintaining consistency</li>
 *   <li>Download operations with improved I/O concurrency</li>
 *   <li>Exception handling and resource cleanup</li>
 * </ul>
 * 
 * <p>Each test creates a large number of virtual threads (1000 by default) to validate the
 * scalability benefits of virtual threads for I/O-bound operations.</p>
 */
public class FluentApiVirtualThreadTest
    extends TestSupport
{
  /**
   * Number of concurrent operations to run in each test.
   * This is set high to validate the scalability benefits of virtual threads.
   */
  private static final int CONCURRENT_OPERATIONS = 1000;
  
  /**
   * Timeout for waiting for all concurrent operations to complete.
   */
  private static final int TIMEOUT_SECONDS = 10;

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
  private Repository repository;

  private FluentAssetImpl fluentAsset;
  private FluentComponentImpl fluentComponent;

  /**
   * Set up the test environment with mocks for the fluent API components.
   * This includes setting up the content facet, blob store, and other dependencies
   * needed for testing the fluent API with virtual threads.
   */
  @Before
  public void setUp() {
    BlobStoreManager mockBlobstoreManager = mock(BlobStoreManager.class);
    FormatStoreManager mockFormatStoreManager = mock(FormatStoreManager.class);

    when(mockBlobstoreManager.get(anyString())).thenReturn(blobStore);

    contentFacetStores = new ContentFacetStores(mockBlobstoreManager, "test", mockFormatStoreManager, "test");

    when(contentFacet.stores()).thenReturn(contentFacetStores);
    when(contentFacet.dependencies()).thenReturn(dependencies);
    when(contentFacet.repository()).thenReturn(repository);
    when(dependencies.getMoveService()).thenReturn(Optional.of(moveService));
    when(asset.blob()).thenReturn(Optional.of(assetBlob));
    when(assetBlob.blobRef()).thenReturn(new BlobRef("default", "test"));
    when(assetBlob.contentType()).thenReturn("text/plain");
    when(repository.getType()).thenReturn(new HostedType());

    // Setup for asset operations
    fluentAsset = new FluentAssetImpl(contentFacet, asset);
    when(asset.attributes()).thenReturn(new NestedAttributesMap());

    // Setup for component operations
    fluentComponent = new FluentComponentImpl(contentFacet, component);
    when(component.attributes()).thenReturn(new NestedAttributesMap());

    // Setup blob for download tests
    Blob mockBlob = mock(Blob.class);
    BlobMetrics mockMetrics = mock(BlobMetrics.class);
    when(blobStore.get(any())).thenReturn(mockBlob);
    when(mockBlob.getMetrics()).thenReturn(mockMetrics);
  }

  /**
   * Tests concurrent asset find operations using virtual threads.
   * This validates that the fluent API can handle many concurrent find operations
   * without issues when executed on virtual threads.
   */
  @Test
  public void testConcurrentAssetFindWithVirtualThreads() throws Exception {
    // Setup mock for find operations
    when(contentFacet.assets()).thenReturn(assetStore);
    when(assetStore.findByPath(anyString())).thenReturn(Optional.of(asset));

    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicBoolean hasErrors = new AtomicBoolean(false);
    List<Exception> exceptions = new ArrayList<>();

    // Use virtual threads executor for concurrent operations
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        String path = "/path/to/asset-" + i;
        executor.submit(() -> {
          try {
            Optional<FluentAsset> foundAsset = contentFacet.assets().path(path).find();
            assertTrue("Asset should be found", foundAsset.isPresent());
          }
          catch (Exception e) {
            synchronized (exceptions) {
              exceptions.add(e);
            }
            hasErrors.set(true);
          }
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all operations to complete
      assertTrue("Timed out waiting for concurrent operations",
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    if (hasErrors.get()) {
      fail("Errors occurred during concurrent asset find operations: " + exceptions.get(0));
    }

    // Verify the find operation was called the expected number of times
    verify(assetStore, times(CONCURRENT_OPERATIONS)).findByPath(anyString());
  }

  /**
   * Tests concurrent component find operations using virtual threads.
   * This validates that the fluent API can handle many concurrent component find operations
   * without issues when executed on virtual threads.
   */
  @Test
  public void testConcurrentComponentFindWithVirtualThreads() throws Exception {
    // Setup mock for component find operations
    when(contentFacet.components()).thenReturn(componentStore);
    when(componentStore.findByNameAndNamespace(anyString(), anyString(), anyString()))
        .thenReturn(Optional.of(component));

    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicBoolean hasErrors = new AtomicBoolean(false);
    List<Exception> exceptions = new ArrayList<>();

    // Use virtual threads executor for concurrent operations
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        String name = "component-" + i;
        String namespace = "namespace-" + (i % 10); // Use a limited set of namespaces
        String version = "1.0." + i;

        executor.submit(() -> {
          try {
            Optional<FluentComponent> foundComponent = contentFacet.components()
                .name(name)
                .namespace(namespace)
                .version(version)
                .find();
            assertTrue("Component should be found", foundComponent.isPresent());
          }
          catch (Exception e) {
            synchronized (exceptions) {
              exceptions.add(e);
            }
            hasErrors.set(true);
          }
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all operations to complete
      assertTrue("Timed out waiting for concurrent operations",
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    if (hasErrors.get()) {
      fail("Errors occurred during concurrent component find operations: " + exceptions.get(0));
    }

    // Verify the find operation was called the expected number of times
    verify(componentStore, times(CONCURRENT_OPERATIONS)).findByNameAndNamespace(anyString(), anyString(), anyString());
  }

  /**
   * Tests concurrent asset download operations using virtual threads.
   * This validates that the fluent API can handle many concurrent download operations
   * without issues when executed on virtual threads, which is particularly important
   * for I/O-bound operations like downloads.
   */
  @Test
  public void testConcurrentAssetDownloadWithVirtualThreads() throws Exception {
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicBoolean hasErrors = new AtomicBoolean(false);
    List<Exception> exceptions = new ArrayList<>();

    // Use virtual threads executor for concurrent operations
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        executor.submit(() -> {
          try (Content content = fluentAsset.download()) {
            assertNotNull("Downloaded content should not be null", content);
            assertEquals("text/plain", content.getContentType());
          }
          catch (Exception e) {
            synchronized (exceptions) {
              exceptions.add(e);
            }
            hasErrors.set(true);
          }
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all operations to complete
      assertTrue("Timed out waiting for concurrent operations",
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    if (hasErrors.get()) {
      fail("Errors occurred during concurrent download operations: " + exceptions.get(0));
    }

    // Verify the blob was retrieved the expected number of times
    verify(blobStore, times(CONCURRENT_OPERATIONS)).get(any());
  }

  /**
   * Tests concurrent asset attribute updates using virtual threads.
   * This validates that the fluent API can handle many concurrent attribute update operations
   * without issues when executed on virtual threads, maintaining data consistency.
   */
  @Test
  public void testConcurrentAssetAttributeUpdatesWithVirtualThreads() throws Exception {
    // Setup a real attributes map to test concurrent modifications
    NestedAttributesMap attributesMap = new NestedAttributesMap();
    when(asset.attributes()).thenReturn(attributesMap);

    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    ConcurrentHashMap<String, String> expectedAttributes = new ConcurrentHashMap<>();
    AtomicBoolean hasErrors = new AtomicBoolean(false);
    List<Exception> exceptions = new ArrayList<>();

    // Use virtual threads executor for concurrent operations
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        String key = "attr-" + i;
        String value = UUID.randomUUID().toString();
        expectedAttributes.put(key, value);

        executor.submit(() -> {
          try {
            fluentAsset.attributes("test").set(key, value);
          }
          catch (Exception e) {
            synchronized (exceptions) {
              exceptions.add(e);
            }
            hasErrors.set(true);
          }
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all operations to complete
      assertTrue("Timed out waiting for concurrent operations",
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    if (hasErrors.get()) {
      fail("Errors occurred during concurrent attribute updates: " + exceptions.get(0));
    }

    // Verify all attributes were set correctly
    NestedAttributesMap testSection = attributesMap.child("test");
    expectedAttributes.forEach((key, value) -> {
      assertEquals("Attribute value should match expected", value, testSection.get(key));
    });
  }

  /**
   * Tests concurrent component attribute updates using virtual threads.
   * This validates that the fluent API can handle many concurrent component attribute update operations
   * without issues when executed on virtual threads, maintaining data consistency.
   */
  @Test
  public void testConcurrentComponentAttributeUpdatesWithVirtualThreads() throws Exception {
    // Setup a real attributes map to test concurrent modifications
    NestedAttributesMap attributesMap = new NestedAttributesMap();
    when(component.attributes()).thenReturn(attributesMap);

    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    ConcurrentHashMap<String, String> expectedAttributes = new ConcurrentHashMap<>();
    AtomicBoolean hasErrors = new AtomicBoolean(false);
    List<Exception> exceptions = new ArrayList<>();

    // Use virtual threads executor for concurrent operations
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        String key = "comp-attr-" + i;
        String value = UUID.randomUUID().toString();
        expectedAttributes.put(key, value);

        executor.submit(() -> {
          try {
            fluentComponent.attributes("test").set(key, value);
          }
          catch (Exception e) {
            synchronized (exceptions) {
              exceptions.add(e);
            }
            hasErrors.set(true);
          }
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all operations to complete
      assertTrue("Timed out waiting for concurrent operations",
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    if (hasErrors.get()) {
      fail("Errors occurred during concurrent component attribute updates: " + exceptions.get(0));
    }

    // Verify all attributes were set correctly
    NestedAttributesMap testSection = attributesMap.child("test");
    expectedAttributes.forEach((key, value) -> {
      assertEquals("Component attribute value should match expected", value, testSection.get(key));
    });
  }

  /**
   * Tests exception handling during concurrent operations using virtual threads.
   * This validates that the fluent API properly handles exceptions when operations fail,
   * ensuring resources are cleaned up correctly even under high concurrency with virtual threads.
   */
  @Test
  public void testExceptionHandlingWithVirtualThreads() throws Exception {
    // Setup to throw an exception during download
    when(blobStore.get(any())).thenThrow(new IOException("Simulated blob retrieval failure"));

    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger exceptionCount = new AtomicInteger(0);

    // Use virtual threads executor for concurrent operations
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        executor.submit(() -> {
          try (Content content = fluentAsset.download()) {
            fail("Should have thrown an exception");
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
      assertTrue("Timed out waiting for concurrent operations",
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    // Verify all operations resulted in exceptions as expected
    assertEquals("All operations should have thrown exceptions", 
        CONCURRENT_OPERATIONS, exceptionCount.get());
    
    // Verify the blob retrieval was attempted the expected number of times
    verify(blobStore, times(CONCURRENT_OPERATIONS)).get(any());
  }
}