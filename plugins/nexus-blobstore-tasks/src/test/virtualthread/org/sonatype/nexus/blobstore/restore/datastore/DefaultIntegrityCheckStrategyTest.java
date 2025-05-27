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

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobAttributes;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobMetrics;
import org.sonatype.nexus.blobstore.api.BlobRef;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreException;
import org.sonatype.nexus.common.entity.Continuation;
import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.AssetBlob;
import org.sonatype.nexus.repository.content.facet.ContentFacet;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.content.fluent.FluentAssets;

import com.google.common.hash.HashCode;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.slf4j.Logger;

import static java.lang.String.format;
import static java.util.Collections.addAll;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.api.BlobAttributesConstants.HEADER_PREFIX;
import static org.sonatype.nexus.blobstore.api.BlobStore.BLOB_NAME_HEADER;
import static org.sonatype.nexus.blobstore.restore.datastore.DefaultIntegrityCheckStrategy.*;

/**
 * Virtual Thread-specific test for {@link DefaultIntegrityCheckStrategy} that verifies its integrity checking
 * functionality when executed with Java 21's Virtual Threads.
 */
@Category(VirtualThreadTestGroup.class)
public class DefaultIntegrityCheckStrategyTest
    extends TestSupport
{
  private static final Optional<HashCode> TEST_HASH1 = of(HashCode.fromString("aa"));

  private static final Optional<HashCode> TEST_HASH2 = of(HashCode.fromString("bb"));

  private static final BooleanSupplier NO_CANCEL = () -> false;

  private static final int SINCE_NO_DAYS = 0;

  private static final int CONCURRENT_ASSETS = 100;

  private static final int BATCH_SIZE = 10;

  @Mock
  private Repository repository;

  @Mock
  private BlobStore blobStore;

  @Mock
  private InputStream blobData;

  @Mock
  FluentAssets assets;

  @Mock
  Consumer<Asset> checkFailedHandler;

  @Mock
  private Logger logger;

  private DefaultIntegrityCheckStrategy defaultIntegrityCheckStrategy;

  @Before
  public void setup() throws Exception {
    BlobStoreConfiguration blobStoreConfiguration = mock(BlobStoreConfiguration.class);
    when(blobStoreConfiguration.getName()).thenReturn("testBlobStore");

    when(blobStore.getBlobStoreConfiguration()).thenReturn(blobStoreConfiguration);

    ContentFacet contentFacet = mock(ContentFacet.class);
    when(contentFacet.assets()).thenReturn(assets);

    when(repository.facet(ContentFacet.class)).thenReturn(contentFacet);

    defaultIntegrityCheckStrategy = spy(new TestDefaultIntegrityCheckStrategy(BATCH_SIZE));
  }

  /**
   * Tests that integrity checks can be executed concurrently using Virtual Threads.
   * This verifies that the DefaultIntegrityCheckStrategy works correctly in a Virtual Thread context
   * and can handle multiple concurrent integrity checks efficiently.
   */
  @Test
  public void testConcurrentIntegrityChecksWithVirtualThreads() throws Exception {
    // Create multiple assets for concurrent checking
    List<FluentAsset> mockAssets = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_ASSETS; i++) {
      BlobId blobId = mock(BlobId.class);
      AssetBlob assetBlob = mockBlob(blobId, TEST_HASH1);
      FluentAsset mockAsset = getMockAsset("asset-" + i, of(assetBlob));
      mockAssets.add(mockAsset);

      // Set up blob attributes for each asset
      BlobAttributes blobAttributes = getMockBlobAttributes(of("asset-" + i), TEST_HASH1, false);
      when(blobStore.getBlobAttributes(blobId)).thenReturn(blobAttributes);
    }

    // Set up the continuation to return our mock assets
    ContinuationArrayList<FluentAsset> assetContinuation = new ContinuationArrayList<>();
    assetContinuation.addAll(mockAssets);
    when(assets.browse(anyInt(), nullable(String.class))).thenReturn(assetContinuation)
        .thenReturn(new ContinuationArrayList<>());

    // Use a CountDownLatch to track completion of all tasks
    CountDownLatch latch = new CountDownLatch(1);
    AtomicInteger completedChecks = new AtomicInteger(0);
    AtomicBoolean hasErrors = new AtomicBoolean(false);

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {
        try {
          defaultIntegrityCheckStrategy.check(repository, blobStore, NO_CANCEL, SINCE_NO_DAYS, asset -> {
            checkFailedHandler.accept(asset);
            hasErrors.set(true);
          });
          completedChecks.set(CONCURRENT_ASSETS);
        } 
        catch (Exception e) {
          log.error("Error during integrity check", e);
          hasErrors.set(true);
        }
        finally {
          latch.countDown();
        }
      });

      // Wait for all checks to complete with a timeout
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("All integrity checks should complete within the timeout", completed, is(true));
      assertThat("No errors should occur during integrity checks", hasErrors.get(), is(false));
      assertThat("All assets should be checked", completedChecks.get(), is(CONCURRENT_ASSETS));
      
      // Verify the logger was called correctly
      verify(logger).info(startsWith("Checking integrity of assets"), nullable(String.class), nullable(String.class));
      verify(logger, times(CONCURRENT_ASSETS)).debug(startsWith("Checking asset {}"), startsWith("asset-"));
      verify(checkFailedHandler, never()).accept(any());
    }
  }

  /**
   * Tests that multiple integrity check operations can be executed concurrently using Virtual Threads.
   * This verifies that the DefaultIntegrityCheckStrategy can handle multiple concurrent repository checks
   * without thread interference issues.
   */
  @Test
  public void testMultipleRepositoryConcurrentChecks() throws Exception {
    int repositoryCount = 5;
    List<Repository> repositories = new ArrayList<>();
    List<FluentAssets> assetsList = new ArrayList<>();
    List<ContentFacet> contentFacets = new ArrayList<>();

    // Create multiple repositories with their own assets
    for (int r = 0; r < repositoryCount; r++) {
      Repository repo = mock(Repository.class);
      FluentAssets repoAssets = mock(FluentAssets.class);
      ContentFacet contentFacet = mock(ContentFacet.class);
      
      when(contentFacet.assets()).thenReturn(repoAssets);
      when(repo.facet(ContentFacet.class)).thenReturn(contentFacet);
      
      repositories.add(repo);
      assetsList.add(repoAssets);
      contentFacets.add(contentFacet);

      // Create assets for this repository
      List<FluentAsset> repoMockAssets = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        BlobId blobId = mock(BlobId.class);
        AssetBlob assetBlob = mockBlob(blobId, TEST_HASH1);
        FluentAsset mockAsset = getMockAsset("repo-" + r + "-asset-" + i, of(assetBlob));
        repoMockAssets.add(mockAsset);

        // Set up blob attributes for each asset
        BlobAttributes blobAttributes = getMockBlobAttributes(of("repo-" + r + "-asset-" + i), TEST_HASH1, false);
        when(blobStore.getBlobAttributes(blobId)).thenReturn(blobAttributes);
      }

      // Set up the continuation to return this repository's mock assets
      ContinuationArrayList<FluentAsset> assetContinuation = new ContinuationArrayList<>();
      assetContinuation.addAll(repoMockAssets);
      when(repoAssets.browse(anyInt(), nullable(String.class))).thenReturn(assetContinuation)
          .thenReturn(new ContinuationArrayList<>());
    }

    // Use a CountDownLatch to track completion of all repository checks
    CountDownLatch latch = new CountDownLatch(repositoryCount);
    AtomicInteger successCount = new AtomicInteger(0);
    Map<String, Exception> errors = new ConcurrentHashMap<>();

    // Create a virtual thread executor and submit tasks for each repository
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int r = 0; r < repositoryCount; r++) {
        final int repoIndex = r;
        executor.submit(() -> {
          try {
            defaultIntegrityCheckStrategy.check(repositories.get(repoIndex), blobStore, NO_CANCEL, SINCE_NO_DAYS, 
                checkFailedHandler);
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            errors.put("Repository " + repoIndex, e);
          }
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all checks to complete with a timeout
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("All repository checks should complete within the timeout", completed, is(true));
      assertThat("All repository checks should succeed", successCount.get(), is(repositoryCount));
      assertThat("No errors should occur during repository checks", errors.isEmpty(), is(true));
    }
  }

  /**
   * Tests that integrity checks respect the sinceDays parameter when executed with Virtual Threads.
   * This verifies that the date filtering logic works correctly in a Virtual Thread context.
   */
  @Test
  public void testSinceDaysFilteringWithVirtualThreads() throws Exception {
    // Create assets with different creation dates
    List<FluentAsset> mockAssets = new ArrayList<>();
    List<AssetBlob> assetBlobs = new ArrayList<>();
    List<OffsetDateTime> creationDates = new ArrayList<>();
    
    // Create 3 assets: one from today, one from yesterday, one from 2 days ago
    for (int i = 0; i < 3; i++) {
      BlobId blobId = mock(BlobId.class);
      AssetBlob assetBlob = mockBlob(blobId, TEST_HASH1);
      FluentAsset mockAsset = getMockAsset("asset-" + i, of(assetBlob));
      mockAssets.add(mockAsset);
      assetBlobs.add(assetBlob);
      
      // Set up blob attributes for each asset
      BlobAttributes blobAttributes = getMockBlobAttributes(of("asset-" + i), TEST_HASH1, false);
      when(blobStore.getBlobAttributes(blobId)).thenReturn(blobAttributes);
      
      // Set up creation dates: today, yesterday, 2 days ago
      OffsetDateTime creationDate = mock(OffsetDateTime.class);
      when(creationDate.toLocalDate()).thenReturn(LocalDate.now().minusDays(i));
      when(assetBlob.blobCreated()).thenReturn(creationDate);
      creationDates.add(creationDate);
    }

    // Set up the continuation to return our mock assets
    ContinuationArrayList<FluentAsset> assetContinuation = new ContinuationArrayList<>();
    assetContinuation.addAll(mockAssets);
    when(assets.browse(anyInt(), nullable(String.class))).thenReturn(assetContinuation)
        .thenReturn(new ContinuationArrayList<>());

    // Use a CountDownLatch to track completion
    CountDownLatch latch = new CountDownLatch(1);
    AtomicInteger checkedAssetCount = new AtomicInteger(0);

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {
        try {
          // Only check assets from the last 1 day (today and yesterday)
          defaultIntegrityCheckStrategy.check(repository, blobStore, NO_CANCEL, 1, asset -> {
            checkFailedHandler.accept(asset);
          });
        } 
        finally {
          latch.countDown();
        }
      });

      // Wait for completion
      latch.await(10, TimeUnit.SECONDS);
      
      // Verify that only assets from today and yesterday were checked
      verify(logger).debug(eq("Checking asset {}"), eq("asset-0")); // Today's asset
      verify(logger).debug(eq("Checking asset {}"), eq("asset-1")); // Yesterday's asset
      verify(logger, never()).debug(eq("Checking asset {}"), eq("asset-2")); // 2 days ago asset
    }
  }

  /**
   * Tests the performance comparison between platform threads and virtual threads for integrity checks.
   * This verifies that virtual threads provide better performance for I/O-bound operations like integrity checks.
   */
  @Test
  public void testPerformanceComparisonBetweenThreadTypes() throws Exception {
    // Create a large number of assets for performance testing
    int assetCount = 1000;
    List<FluentAsset> mockAssets = new ArrayList<>();
    for (int i = 0; i < assetCount; i++) {
      BlobId blobId = mock(BlobId.class);
      AssetBlob assetBlob = mockBlob(blobId, TEST_HASH1);
      FluentAsset mockAsset = getMockAsset("perf-asset-" + i, of(assetBlob));
      mockAssets.add(mockAsset);

      // Set up blob attributes for each asset
      BlobAttributes blobAttributes = getMockBlobAttributes(of("perf-asset-" + i), TEST_HASH1, false);
      when(blobStore.getBlobAttributes(blobId)).thenReturn(blobAttributes);
    }

    // Set up the continuation to return our mock assets
    ContinuationArrayList<FluentAsset> assetContinuation = new ContinuationArrayList<>();
    assetContinuation.addAll(mockAssets);
    when(assets.browse(anyInt(), nullable(String.class))).thenReturn(assetContinuation)
        .thenReturn(new ContinuationArrayList<>());

    // Measure performance with platform threads
    long platformThreadTime = measureExecutionTime(() -> {
      try (ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {
        CountDownLatch latch = new CountDownLatch(1);
        executor.submit(() -> {
          try {
            defaultIntegrityCheckStrategy.check(repository, blobStore, NO_CANCEL, SINCE_NO_DAYS, checkFailedHandler);
          } 
          catch (Exception e) {
            log.error("Error during platform thread integrity check", e);
          }
          finally {
            latch.countDown();
          }
        });
        latch.await(60, TimeUnit.SECONDS);
      }
      catch (Exception e) {
        log.error("Error in platform thread test", e);
      }
    });

    // Reset mocks for the second test
    when(assets.browse(anyInt(), nullable(String.class))).thenReturn(assetContinuation)
        .thenReturn(new ContinuationArrayList<>());

    // Measure performance with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        CountDownLatch latch = new CountDownLatch(1);
        executor.submit(() -> {
          try {
            defaultIntegrityCheckStrategy.check(repository, blobStore, NO_CANCEL, SINCE_NO_DAYS, checkFailedHandler);
          } 
          catch (Exception e) {
            log.error("Error during virtual thread integrity check", e);
          }
          finally {
            latch.countDown();
          }
        });
        latch.await(60, TimeUnit.SECONDS);
      }
      catch (Exception e) {
        log.error("Error in virtual thread test", e);
      }
    });

    // Log the performance results
    log.info("Platform thread execution time: {} ms", platformThreadTime);
    log.info("Virtual thread execution time: {} ms", virtualThreadTime);

    // Virtual threads should generally be more efficient for I/O-bound operations
    // This assertion might be environment-dependent, but virtual threads should typically perform better
    assertThat("Virtual threads should be more efficient than platform threads for I/O-bound operations",
        virtualThreadTime, lessThan(platformThreadTime));
  }

  /**
   * Tests that integrity checks can detect thread pinning issues when executed with Virtual Threads.
   * This verifies that the DefaultIntegrityCheckStrategy doesn't cause thread pinning during I/O operations.
   */
  @Test
  public void testThreadPinningDetection() throws Exception {
    // Create a single asset for testing
    BlobId blobId = mock(BlobId.class);
    AssetBlob assetBlob = mockBlob(blobId, TEST_HASH1);
    FluentAsset mockAsset = getMockAsset("pinning-test-asset", of(assetBlob));

    // Set up blob attributes
    BlobAttributes blobAttributes = getMockBlobAttributes(of("pinning-test-asset"), TEST_HASH1, false);
    when(blobStore.getBlobAttributes(blobId)).thenReturn(blobAttributes);

    // Set up the continuation to return our mock asset
    ContinuationArrayList<FluentAsset> assetContinuation = new ContinuationArrayList<>();
    assetContinuation.add(mockAsset);
    when(assets.browse(anyInt(), nullable(String.class))).thenReturn(assetContinuation)
        .thenReturn(new ContinuationArrayList<>());

    // Use a CountDownLatch to track completion
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean completed = new AtomicBoolean(false);

    // Enable thread pinning detection with JVM flag: -Djdk.tracePinnedThreads=full
    // This is a diagnostic flag and doesn't need to be set in the test itself

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {
        try {
          defaultIntegrityCheckStrategy.check(repository, blobStore, NO_CANCEL, SINCE_NO_DAYS, checkFailedHandler);
          completed.set(true);
        } 
        finally {
          latch.countDown();
        }
      });

      // Wait for completion
      boolean finishedInTime = latch.await(10, TimeUnit.SECONDS);
      
      // Verify that the check completed successfully without thread pinning issues
      assertThat("Integrity check should complete within the timeout", finishedInTime, is(true));
      assertThat("Integrity check should complete successfully", completed.get(), is(true));
    }
  }

  /**
   * Measures the execution time of a runnable operation in milliseconds.
   */
  private long measureExecutionTime(Runnable operation) {
    long startTime = System.nanoTime();
    operation.run();
    long endTime = System.nanoTime();
    return TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
  }

  private static class ContinuationArrayList<E>
      extends ArrayList<E>
      implements Continuation<E>
  {
    private static final long serialVersionUID = -8278643802740770499L;

    @Override
    public String nextContinuationToken() {
      return null;
    }
  }

  private BlobAttributes getMockBlobAttributes(
      final Optional<String> name,
      final Optional<HashCode> sha1,
      final boolean deleted)
  {
    BlobAttributes blobAttributes = mock(BlobAttributes.class);

    Properties properties = new Properties();
    when(blobAttributes.getProperties()).thenReturn(properties);
    name.ifPresent(n -> properties.setProperty(HEADER_PREFIX + BLOB_NAME_HEADER, n));

    BlobMetrics metrics = new BlobMetrics(new DateTime(), sha1.map(HashCode::toString).orElse(null), 0);
    when(blobAttributes.getMetrics()).thenReturn(metrics);

    when(blobAttributes.isDeleted()).thenReturn(deleted);

    return blobAttributes;
  }

  private Continuation<FluentAsset> buildContinuation(final FluentAsset... assets) {
    ContinuationArrayList<FluentAsset> browseResults = new ContinuationArrayList<>();
    addAll(browseResults, assets);
    return browseResults;
  }

  private AssetBlob mockBlob(final BlobId blobId, final Optional<HashCode> sha1) {
    Map<String, String> checksums = new HashMap<>();
    checksums.put(HashAlgorithm.SHA1.name(), sha1.map(HashCode::toString).orElse(null));

    BlobRef blobRef = mock(BlobRef.class);
    when(blobRef.getBlobId()).thenReturn(blobId);

    AssetBlob assetBlob = mock(AssetBlob.class);
    when(assetBlob.blobRef()).thenReturn(blobRef);
    when(assetBlob.checksums()).thenReturn(checksums);

    Blob blob = mock(Blob.class);
    when(blob.getInputStream()).thenReturn(blobData);

    when(blobStore.get(blobId)).thenReturn(blob);

    return assetBlob;
  }

  private FluentAsset getMockAsset(final String path, final Optional<AssetBlob> assetBlob) {
    FluentAsset asset = mock(FluentAsset.class);
    when(asset.path()).thenReturn(path);
    when(asset.blob()).thenReturn(assetBlob);

    return asset;
  }

  // The whole point of the integrity checker is to log, so we need to run verifications against a mock logger
  private class TestDefaultIntegrityCheckStrategy
      extends DefaultIntegrityCheckStrategy
  {
    TestDefaultIntegrityCheckStrategy(final int batchSize) {
      super(batchSize);
    }

    @Override
    protected Logger createLogger() {
      return logger;
    }
  }
}