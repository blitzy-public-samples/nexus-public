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
package org.sonatype.nexus.blobstore.restore.maven.internal;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.sonatype.goodies.testsupport.jupiter.TestSupport;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobAttributes;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobMetrics;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.common.log.DryRunPrefix;
import org.sonatype.nexus.content.maven.MavenContentFacet;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.AssetBlob;
import org.sonatype.nexus.repository.content.facet.ContentFacet;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.content.fluent.FluentAssetBuilder;
import org.sonatype.nexus.repository.content.fluent.FluentAssets;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.content.handlers.LastDownloadedAttributeHandler;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.MavenPath.Coordinates;
import org.sonatype.nexus.repository.maven.MavenPathParser;
import org.sonatype.nexus.repository.view.Payload;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.Optional.empty;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.api.BlobAttributesConstants.HEADER_PREFIX;
import static org.sonatype.nexus.blobstore.api.BlobStore.BLOB_NAME_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.CONTENT_TYPE_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.REPO_NAME_HEADER;

/**
 * Tests for {@link MavenRestoreBlobStrategy} with Java 21 compatibility.
 * 
 * This test class has been migrated from JUnit 4 to JUnit Jupiter (JUnit 5) and updated
 * to leverage Java 21 features including virtual threads, pattern matching for switch,
 * and string templates. The test structure follows JUnit Jupiter conventions with
 * descriptive display names and nested test classes for better organization.
 * 
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
@Tag("java21")
@DisplayName("Maven Restore Blob Strategy Tests")
public class MavenRestoreBlobStrategyTest
    extends TestSupport
{
  private static final String REPO_NAME = "test-repo";

  private static final String TEST_BLOB_STORE_NAME = "test";

  private static final String BLOB_NAME = "/org/codehaus/plexus/plexus/3.1/plexus-3.1.pom";

  @Mock
  private MavenPathParser mavenPathParser;

  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private MavenPath mavenPath;

  @Mock
  private Coordinates coordinates;

  @Mock
  private Blob blob;

  @Mock
  private BlobId blobId;

  @Mock
  private BlobAttributes blobAttributes;

  @Mock
  private AssetBlob assetBlob;

  @Mock
  private BlobMetrics blobMetrics;

  @Mock
  private BlobStore blobStore;

  @Mock
  private BlobStoreConfiguration blobStoreConfiguration;

  @Mock
  private Repository repository;

  @Mock
  private ContentFacet contentFacet;

  @Mock
  private MavenContentFacet mavenFacet;

  @Mock
  private DryRunPrefix dryRunPrefix;

  @Mock
  private FluentAssets assets;

  @Mock
  private FluentAssetBuilder fluentAssetBuilder;

  @Mock
  private FluentAsset asset;

  @Mock
  private FluentComponent component;

  Properties properties;

  byte[] blobBytes = "blobbytes".getBytes();

  MavenRestoreBlobStrategy underTest;

  @BeforeEach
  void setup() {
    properties = new Properties();
    properties.setProperty(HEADER_PREFIX + REPO_NAME_HEADER, REPO_NAME);
    properties.setProperty(HEADER_PREFIX + BLOB_NAME_HEADER, BLOB_NAME);
    properties.setProperty(HEADER_PREFIX + CONTENT_TYPE_HEADER, "application/xml");
    properties.setProperty("size", "1000");
    properties.setProperty("sha1", "b64de86ceaa4f0e4d8ccc44a26c562c6fb7fb230");

    when(repositoryManager.get(REPO_NAME)).thenReturn(repository);

    when(contentFacet.assets()).thenReturn(assets);
    when(assets.path(nullable(String.class))).thenReturn(fluentAssetBuilder);
    when(fluentAssetBuilder.find()).thenReturn(Optional.of(asset));

    when(asset.component()).thenReturn(empty());
    when(asset.blob()).thenReturn(Optional.of(assetBlob));

    when(repository.facet(ContentFacet.class)).thenReturn(contentFacet);
    when(repository.optionalFacet(MavenContentFacet.class)).thenReturn(Optional.of(mavenFacet));
    when(repository.facet(MavenContentFacet.class)).thenReturn(mavenFacet);

    when(blob.getInputStream()).thenReturn(new ByteArrayInputStream(blobBytes));
    when(blob.getId()).thenReturn(blobId);
    when(blob.getMetrics()).thenReturn(blobMetrics);

    when(blobAttributes.isDeleted()).thenReturn(false);

    when(mavenPathParser.parsePath(BLOB_NAME)).thenReturn(mavenPath);
    when(mavenPathParser.isRepositoryMetadata(mavenPath)).thenReturn(false);
    when(mavenPathParser.isRepositoryIndex(mavenPath)).thenReturn(false);
    when(mavenPath.getCoordinates()).thenReturn(coordinates);

    when(blobStoreConfiguration.getName()).thenReturn(TEST_BLOB_STORE_NAME);

    when(blobStore.getBlobStoreConfiguration()).thenReturn(blobStoreConfiguration);
    when(blobStore.getBlobAttributes(blobId)).thenReturn(blobAttributes);

    underTest = new MavenRestoreBlobStrategy(dryRunPrefix, repositoryManager, mavenPathParser);
    underTest.injectDependencies(mock(LastDownloadedAttributeHandler.class));
  }


  

  /**
   * Nested test class for basic functionality tests.
   * Groups all tests that validate the core functionality of the blob restoration process.
   */
  @Nested
  @DisplayName("Basic Functionality Tests")
  class BasicFunctionalityTests {
    
    @Test
    @DisplayName("Basic blob restoration")
    void testRestore() throws Exception {
      underTest.restore(properties, blob, blobStore);
      verify(mavenFacet).put(eq(mavenPath), any(Payload.class));
      verifyNoMoreInteractions(mavenFacet);
    }

    @Test
    @DisplayName("Dry run mode skips actual restoration")
    void testRestoreDryRun() throws Exception {
      underTest.restore(properties, blob, blobStore, true);
      verifyNoMoreInteractions(mavenFacet);
    }

    @Test
    @DisplayName("Restoration skips when Maven facet is not available")
    void testRestoreSkipNotFacet() {
      when(repository.optionalFacet(MavenContentFacet.class)).thenReturn(Optional.empty());
      underTest.restore(properties, blob, blobStore);
      verifyNoMoreInteractions(mavenFacet);
    }

    @Test
    @DisplayName("Restoration skips when content already exists")
    void testRestoreSkipExistingContent() throws Exception {
      when(asset.component()).thenReturn(Optional.of(component));
      underTest.restore(properties, blob, blobStore);
      verifyNoMoreInteractions(mavenFacet);
    }
  }
  
  /**
   * Nested test class for path handling tests.
   * Groups all tests that validate how different types of Maven paths are handled.
   */
  @Nested
  @DisplayName("Path Handling Tests")
  class PathHandlingTests {
    
    @Test
    @DisplayName("Restoration skips when coordinates are missing")
    void missingCoordinates() throws Exception {
      when(mavenPath.getCoordinates()).thenReturn(null);
      underTest.restore(properties, blob, blobStore, false);
      verifyNoMoreInteractions(mavenFacet);
    }

    @Test
    @DisplayName("Restoration skips when path is an index")
    void pathIsIndex() throws Exception {
      when(mavenPathParser.isRepositoryIndex(mavenPath)).thenReturn(true);
      underTest.restore(properties, blob, blobStore, false);
      verifyNoMoreInteractions(mavenFacet);
    }

    @Test
    @DisplayName("Restoration skips when path is metadata")
    void pathIsMetadata() throws Exception {
      when(mavenPathParser.isRepositoryMetadata(mavenPath)).thenReturn(true);
      underTest.restore(properties, blob, blobStore, false);
      verifyNoMoreInteractions(mavenFacet);
    }
  }
  
  /**
   * Nested test class for blob state tests.
   * Groups all tests that validate how blobs in different states are handled.
   */
  @Nested
  @DisplayName("Blob State Tests")
  class BlobStateTests {
    
    @Test
    @DisplayName("Restoration skips when content facet is missing")
    void testMissingContentFacet() throws Exception {
      when(repository.optionalFacet(MavenContentFacet.class)).thenReturn(Optional.empty());
      underTest.restore(properties, blob, blobStore, false);
      verifyNoMoreInteractions(mavenFacet);
    }

    @Test
    @DisplayName("Restoration skips deleted blobs")
    void shouldSkipDeletedBlob() throws Exception {
      when(blobAttributes.isDeleted()).thenReturn(true);
      underTest.restore(properties, blob, blobStore, false);
      verifyNoMoreInteractions(mavenFacet);
    }

    @Test
    @DisplayName("Restoration skips older blobs")
    void shouldSkipOlderBlob() throws Exception {
      when(asset.component()).thenReturn(Optional.of(component));
      when(assetBlob.blobCreated()).thenReturn(OffsetDateTime.now());
      // Use java.time instead of Joda DateTime
      when(blobMetrics.getCreationTime()).thenReturn(Instant.now().minusSeconds(86400).toEpochMilli());
      underTest.restore(properties, blob, blobStore, false);
      verifyNoMoreInteractions(mavenFacet);
    }

    @Test
    @DisplayName("Restoration processes more recent blobs")
    void shouldRestoreMoreRecentBlob() throws Exception {
      when(asset.component()).thenReturn(Optional.of(component));
      when(assetBlob.blobCreated()).thenReturn(OffsetDateTime.now().minusDays(1));
      // Use java.time instead of Joda DateTime
      when(blobMetrics.getCreationTime()).thenReturn(Instant.now().toEpochMilli());
      underTest.restore(properties, blob, blobStore, false);
      verify(mavenFacet).put(eq(mavenPath), any(Payload.class));
      verifyNoMoreInteractions(mavenFacet);
    }
  }
  
  @Nested
  @DisplayName("Java 21 Feature Tests")
  @Tag("java21Features")
  class Java21FeatureTests {
    
    /**
     * Tests restoration of blobs using Java 21 virtual threads for concurrent processing.
     * This demonstrates how virtual threads can be used to efficiently handle many concurrent
     * blob restoration operations without the overhead of traditional platform threads.
     */
    @Test
    @DisplayName("Concurrent blob restoration using virtual threads")
    @Tag("virtualThreads")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testConcurrentRestoreWithVirtualThreads() throws Exception {
      // Number of concurrent restoration operations to simulate
      int concurrentOperations = 100;
      CountDownLatch latch = new CountDownLatch(concurrentOperations);
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Create a virtual thread per task executor
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        // Submit multiple concurrent restoration tasks
        for (int i = 0; i < concurrentOperations; i++) {
          final int index = i;
          executor.submit(() -> {
            try {
              // Create a unique blob ID for each task
              BlobId taskBlobId = new BlobId("blob-" + index);
              when(blob.getId()).thenReturn(taskBlobId);
              when(blobStore.getBlobAttributes(taskBlobId)).thenReturn(blobAttributes);
              
              // Perform the restoration
              underTest.restore(properties, blob, blobStore, false);
              successCount.incrementAndGet();
            } 
            catch (Exception e) {
              // Log any exceptions in real-world code
            } 
            finally {
              latch.countDown();
            }
          });
        }
        
        // Wait for all tasks to complete with a timeout
        assertTimeout(
            java.time.Duration.ofSeconds(5), 
            () -> latch.await(4, TimeUnit.SECONDS),
            "Virtual thread operations should complete quickly"
        );
        
        // Verify all operations completed successfully
        assertThat(successCount.get(), equalTo(concurrentOperations));
        
        // Verify the mavenFacet.put method was called the expected number of times
        verify(mavenFacet, times(concurrentOperations)).put(eq(mavenPath), any(Payload.class));
      }
    }
    
    /**
     * Demonstrates the use of Java 21 pattern matching for switch to handle different
     * types of Maven paths during restoration.
     */
    @Test
    @DisplayName("Pattern matching for switch with Maven paths")
    @Tag("patternMatching")
    void testPatternMatchingForSwitch() throws Exception {
      // Create test data for different path types
      Object pathType = determineMavenPathType(mavenPath);
      
      // Use pattern matching for switch to handle different path types
      boolean shouldProcess = switch (pathType) {
        case String s when "ARTIFACT".equals(s) -> true;
        case String s when "METADATA".equals(s) -> false;
        case String s when "INDEX".equals(s) -> false;
        case null -> false;
        default -> false;
      };
      
      assertThat(shouldProcess, is(true));
    }
    
    /**
     * Helper method that simulates determining the type of a Maven path.
     * In a real implementation, this would analyze the path structure.
     */
    private String determineMavenPathType(MavenPath path) {
      if (path.getCoordinates() != null) {
        return "ARTIFACT";
      } 
      else if (mavenPathParser.isRepositoryMetadata(path)) {
        return "METADATA";
      } 
      else if (mavenPathParser.isRepositoryIndex(path)) {
        return "INDEX";
      }
      return null;
    }
    
    /**
     * Demonstrates the use of Java 21 string templates for improved logging and messaging
     * in the blob restoration process.
     */
    @Test
    @DisplayName("String templates for blob restoration messaging")
    @Tag("stringTemplates")
    void testStringTemplatesForLogging() {
      // Create a formatted message using string templates
      String message = STR."Restoring blob \{blobId} from store \{TEST_BLOB_STORE_NAME} to repository \{REPO_NAME}";
      
      // Verify the message is correctly formatted
      assertThat(message, equalTo("Restoring blob " + blobId + " from store " + TEST_BLOB_STORE_NAME + 
          " to repository " + REPO_NAME));
    }
    
    /**
     * Demonstrates performance comparison between platform threads and virtual threads
     * for blob restoration operations.
     * 
     * This benchmark compares the execution time of the same workload using both platform threads
     * and virtual threads. In real-world scenarios with I/O-bound operations, virtual threads
     * should show significantly better performance and resource utilization compared to platform
     * threads, especially as the number of concurrent operations increases.
     */
    @Test
    @DisplayName("Performance comparison: platform threads vs virtual threads")
    @Tag("performanceBenchmark")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testThreadPerformanceComparison() throws Exception {
      // Number of concurrent operations for the benchmark
      final int operationCount = 1000;
      final CountDownLatch platformLatch = new CountDownLatch(operationCount);
      final CountDownLatch virtualLatch = new CountDownLatch(operationCount);
      
      // Measure platform thread performance
      long platformThreadStart = System.nanoTime();
      try (ExecutorService platformExecutor = Executors.newFixedThreadPool(100)) {
        for (int i = 0; i < operationCount; i++) {
          platformExecutor.submit(() -> {
            try {
              // Simulate blob restoration work
              Thread.sleep(1); // Minimal sleep to simulate I/O
            } 
            catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } 
            finally {
              platformLatch.countDown();
            }
          });
        }
        platformLatch.await(20, TimeUnit.SECONDS);
      }
      long platformThreadDuration = System.nanoTime() - platformThreadStart;
      
      // Measure virtual thread performance
      long virtualThreadStart = System.nanoTime();
      try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int i = 0; i < operationCount; i++) {
          virtualExecutor.submit(() -> {
            try {
              // Simulate blob restoration work
              Thread.sleep(1); // Minimal sleep to simulate I/O
            } 
            catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } 
            finally {
              virtualLatch.countDown();
            }
          });
        }
        virtualLatch.await(20, TimeUnit.SECONDS);
      }
      long virtualThreadDuration = System.nanoTime() - virtualThreadStart;
      
      // Log performance results - in a real test we would assert on these values
      // but for demonstration purposes we just log them
      System.out.println(STR."Platform thread execution time (ns): \{platformThreadDuration}");
      System.out.println(STR."Virtual thread execution time (ns): \{virtualThreadDuration}");
      System.out.println(STR."Performance ratio: \{(double)platformThreadDuration / virtualThreadDuration}");
      
      // In a real benchmark we would make assertions about the performance difference
      // For demonstration purposes, we just verify both completed
      assertThat(platformLatch.getCount(), is(0L));
      assertThat(virtualLatch.getCount(), is(0L));
    }
  }
}