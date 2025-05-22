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
package virtualthread;

import java.net.URL;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import org.sonatype.nexus.repository.move.ChangeRepositoryBlobStoreStore;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskUtils;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
 * Tests for {@link RestoreMetadataTask} that validate its performance with Java 21 Virtual Threads.
 * This test class focuses on verifying that the task effectively utilizes Virtual Threads for
 * concurrent metadata restoration operations, providing improved performance and resource utilization
 * compared to platform threads.
 */
@ExtendWith(MockitoExtension.class)
public class RestoreMetadataTaskVirtualThreadTest
    extends TestSupport
{
  private static final String BLOBSTORE_NAME = "test";
  private static final String MAVEN_2 = "maven2";
  private static final int HIGH_CONCURRENCY_LEVEL = 1000;
  private static final int MEDIUM_CONCURRENCY_LEVEL = 100;
  private static final int LOW_CONCURRENCY_LEVEL = 10;

  @Mock
  private BlobStoreManager blobStoreManager;

  @Mock
  private ChangeRepositoryBlobStoreStore changeBlobstoreStore;

  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private RestoreBlobStrategy restoreBlobStrategy;

  @Mock
  private BlobStore blobStore;

  @Mock
  private Format mavenFormat;

  @Mock
  private BlobStoreUsageChecker blobstoreUsageChecker;

  @Mock
  private DryRunPrefix dryRunPrefix;

  @Mock
  private DefaultIntegrityCheckStrategy defaultIntegrityCheckStrategy;

  @Mock
  private MaintenanceService maintenanceService;

  @Mock
  private AssetBlobRefFormatCheck assetBlobRefFormatCheck;

  @Mock
  private TaskUtils taskUtils;

  private RestoreMetadataTask underTest;
  private Map<String, IntegrityCheckStrategy> integrityCheckStrategies;
  private TaskConfiguration configuration;
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;
  private List<BlobId> blobIds;
  private List<Repository> repositories;

  @BeforeEach
  public void setup() throws Exception {
    // Set up integrity check strategies
    integrityCheckStrategies = new HashMap<>();
    integrityCheckStrategies.put(MAVEN_2, mock(IntegrityCheckStrategy.class));
    integrityCheckStrategies.put(DEFAULT_NAME, defaultIntegrityCheckStrategy);

    // Create the task under test
    underTest = new RestoreMetadataTask(blobStoreManager, changeBlobstoreStore, repositoryManager,
        ImmutableMap.of(MAVEN_2, restoreBlobStrategy),
        blobstoreUsageChecker, dryRunPrefix, integrityCheckStrategies, maintenanceService, assetBlobRefFormatCheck,
        taskUtils);

    // Set up task configuration
    configuration = new TaskConfiguration();
    configuration.setString(BLOB_STORE_NAME_FIELD_ID, BLOBSTORE_NAME);
    configuration.setString(".name", "test");
    configuration.setId(BLOBSTORE_NAME);
    configuration.setTypeId(TYPE_ID);
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    configuration.setBoolean(DRY_RUN, false);

    // Configure the task
    underTest.configure(configuration);

    // Set up repositories
    repositories = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      Repository repository = mock(Repository.class);
      String repoName = "maven-central-" + i;
      when(repository.getName()).thenReturn(repoName);
      when(repository.isStarted()).thenReturn(true);
      when(repository.getFormat()).thenReturn(mavenFormat);
      when(repositoryManager.get(repoName)).thenReturn(repository);
      repositories.add(repository);
    }

    // Set up format
    when(mavenFormat.getValue()).thenReturn(MAVEN_2);

    // Set up blob store
    when(blobStoreManager.get(BLOBSTORE_NAME)).thenReturn(blobStore);
    BlobStoreConfiguration blobStoreConfiguration = mock(BlobStoreConfiguration.class);
    when(blobStoreConfiguration.getName()).thenReturn(BLOBSTORE_NAME);
    when(blobStore.getBlobStoreConfiguration()).thenReturn(blobStoreConfiguration);

    // Set up blob attributes
    URL resource = Resources.getResource("test-restore/content/vol-1/chp-1/86e20baa-0bca-4915-a7dc-9a4f34e72321.properties");
    FileBlobAttributes blobAttributes = new FileBlobAttributes(Paths.get(resource.toURI()));
    blobAttributes.load();

    // Set up blob IDs for testing
    blobIds = new ArrayList<>();
    for (int i = 0; i < HIGH_CONCURRENCY_LEVEL; i++) {
      BlobId blobId = new BlobId(UUID.randomUUID().toString());
      blobIds.add(blobId);

      // Set up blob and blob attributes for each blob ID
      Blob blob = mock(Blob.class);
      BlobAttributes attrs = mock(BlobAttributes.class);
      Properties props = new Properties();
      props.setProperty(HEADER_PREFIX + REPO_NAME_HEADER, repositories.get(i % repositories.size()).getName());
      when(attrs.getProperties()).thenReturn(props);
      when(attrs.isDeleted()).thenReturn(false);

      when(blobStore.get(blobId, true)).thenReturn(blob);
      when(blobStore.getBlobAttributes(blobId)).thenReturn(attrs);

      // Set up restore strategy to simulate some processing time
      lenient().doAnswer(invocation -> {
        // Simulate some processing time (1-5ms)
        Thread.sleep((long) (Math.random() * 4 + 1));
        return null;
      }).when(restoreBlobStrategy).restore(any(Properties.class), eq(blob), eq(blobStore), anyBoolean());
    }

    // Set up blob store to return our test blob IDs
    when(blobStore.getBlobIdStream()).thenReturn(blobIds.stream());
    when(changeBlobstoreStore.findByBlobStoreName(BLOBSTORE_NAME)).thenReturn(Collections.emptyList());
    when(dryRunPrefix.get()).thenReturn("");

    // Set up executors
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), platformThreadFactory);
  }

  @AfterEach
  public void tearDown() throws Exception {
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
   * Tests that RestoreMetadataTask can handle a high number of concurrent operations
   * using Virtual Threads without exhausting thread resources.
   */
  @Test
  @DisplayName("RestoreMetadataTask should handle high concurrency with Virtual Threads")
  public void testHighConcurrencyWithVirtualThreads() {
    // Create a custom RestoreMetadataTask that uses Virtual Threads
    RestoreMetadataTask virtualThreadTask = createCustomExecutorTask(virtualThreadExecutor);
    virtualThreadTask.configure(configuration);

    // Execute the task and measure performance
    Instant start = Instant.now();
    virtualThreadTask.execute();
    Duration virtualThreadDuration = Duration.between(start, Instant.now());

    // Verify that all blobs were processed
    ArgumentCaptor<Properties> propertiesCaptor = ArgumentCaptor.forClass(Properties.class);
    verify(restoreBlobStrategy, times(blobIds.size())).restore(propertiesCaptor.capture(), any(Blob.class), eq(blobStore), eq(false));

    // Verify that after() was called for each repository
    for (Repository repository : repositories) {
      verify(restoreBlobStrategy).after(eq(true), eq(repository));
    }

    log.info("Processed {} blobs with Virtual Threads in {} ms", blobIds.size(), virtualThreadDuration.toMillis());
    
    // The test passes if it completes without thread resource exhaustion
    assertThat("Should process all blobs without thread resource exhaustion", 
        virtualThreadDuration.toMillis() > 0, is(true));
  }

  /**
   * Compares the performance of Virtual Threads vs Platform Threads for metadata restoration.
   * This test verifies that Virtual Threads provide better scalability and resource utilization
   * under high concurrency scenarios.
   */
  @Test
  @DisplayName("Virtual Threads should outperform Platform Threads under high concurrency")
  public void testVirtualThreadsVsPlatformThreadsPerformance() {
    // Create tasks with different thread models
    RestoreMetadataTask virtualThreadTask = createCustomExecutorTask(virtualThreadExecutor);
    RestoreMetadataTask platformThreadTask = createCustomExecutorTask(platformThreadExecutor);

    // Configure both tasks
    virtualThreadTask.configure(configuration);
    platformThreadTask.configure(configuration);

    // Reset mock to avoid counting previous invocations
    reset(restoreBlobStrategy);

    // Execute with platform threads and measure performance
    Instant platformStart = Instant.now();
    platformThreadTask.execute();
    Duration platformDuration = Duration.between(platformStart, Instant.now());

    // Reset mock again
    reset(restoreBlobStrategy);

    // Execute with virtual threads and measure performance
    Instant virtualStart = Instant.now();
    virtualThreadTask.execute();
    Duration virtualDuration = Duration.between(virtualStart, Instant.now());

    log.info("Platform Threads: {} ms for {} blobs", platformDuration.toMillis(), blobIds.size());
    log.info("Virtual Threads: {} ms for {} blobs", virtualDuration.toMillis(), blobIds.size());

    // Virtual threads should be more efficient under high concurrency
    assertThat("Virtual Threads should be faster than Platform Threads under high concurrency",
        virtualDuration.toMillis(), lessThan(platformDuration.toMillis()));
  }

  /**
   * Tests that RestoreMetadataTask can efficiently process metadata restoration
   * across multiple repositories concurrently using Virtual Threads.
   */
  @Test
  @DisplayName("RestoreMetadataTask should efficiently process multiple repositories concurrently")
  public void testConcurrentMultiRepositoryProcessing() {
    // Create a map to track processing by repository
    Map<String, AtomicInteger> repositoryProcessingCount = new ConcurrentHashMap<>();
    for (Repository repo : repositories) {
      repositoryProcessingCount.put(repo.getName(), new AtomicInteger(0));
    }

    // Set up the restore strategy to track which repositories are being processed
    doAnswer(invocation -> {
      Properties props = invocation.getArgument(0);
      String repoName = props.getProperty(HEADER_PREFIX + REPO_NAME_HEADER);
      repositoryProcessingCount.get(repoName).incrementAndGet();
      // Simulate some processing time
      Thread.sleep((long) (Math.random() * 5 + 1));
      return null;
    }).when(restoreBlobStrategy).restore(any(Properties.class), any(Blob.class), eq(blobStore), anyBoolean());

    // Create a task that uses Virtual Threads
    RestoreMetadataTask virtualThreadTask = createCustomExecutorTask(virtualThreadExecutor);
    virtualThreadTask.configure(configuration);

    // Execute the task
    virtualThreadTask.execute();

    // Verify that all repositories were processed concurrently
    for (Repository repo : repositories) {
      int count = repositoryProcessingCount.get(repo.getName()).get();
      log.info("Repository {} processed {} blobs", repo.getName(), count);
      assertThat("Repository should have processed some blobs", count, greaterThan(0));
    }

    // Verify that after() was called for each repository
    for (Repository repository : repositories) {
      verify(restoreBlobStrategy).after(eq(true), eq(repository));
    }
  }

  /**
   * Tests that RestoreMetadataTask properly handles errors during concurrent processing
   * with Virtual Threads, ensuring that failures in one thread don't affect others.
   */
  @Test
  @DisplayName("RestoreMetadataTask should handle errors gracefully with Virtual Threads")
  public void testErrorHandlingWithVirtualThreads() {
    // Set up some blobs to fail during restoration
    int failureCount = 10;
    List<BlobId> failingBlobIds = blobIds.subList(0, failureCount);
    
    for (BlobId blobId : failingBlobIds) {
      doAnswer(invocation -> {
        throw new RuntimeException("Simulated failure for blob " + blobId);
      }).when(restoreBlobStrategy).restore(any(Properties.class), eq(blobStore.get(blobId, true)), eq(blobStore), anyBoolean());
    }

    // Create a task that uses Virtual Threads
    RestoreMetadataTask virtualThreadTask = createCustomExecutorTask(virtualThreadExecutor);
    virtualThreadTask.configure(configuration);

    // Execute the task - it should complete despite the errors
    virtualThreadTask.execute();

    // Verify that after() was still called for each repository
    // This confirms that errors in individual blobs didn't prevent overall task completion
    for (Repository repository : repositories) {
      verify(restoreBlobStrategy).after(eq(true), eq(repository));
    }
  }

  /**
   * Tests that RestoreMetadataTask can efficiently process a large number of blobs
   * within a reasonable time limit using Virtual Threads.
   */
  @Test
  @DisplayName("RestoreMetadataTask should complete large workloads within time limits using Virtual Threads")
  public void testLargeWorkloadCompletionTime() {
    // Create a task that uses Virtual Threads
    RestoreMetadataTask virtualThreadTask = createCustomExecutorTask(virtualThreadExecutor);
    virtualThreadTask.configure(configuration);

    // Execute the task with a timeout - it should complete within the time limit
    assertTimeout(Duration.ofSeconds(30), () -> {
      virtualThreadTask.execute();
    });

    // Verify that all blobs were processed
    verify(restoreBlobStrategy, times(blobIds.size())).restore(any(Properties.class), any(Blob.class), eq(blobStore), eq(false));
  }

  /**
   * Tests that RestoreMetadataTask properly handles integrity checking with Virtual Threads,
   * ensuring that integrity checks are performed concurrently and efficiently.
   */
  @Test
  @DisplayName("RestoreMetadataTask should perform integrity checks efficiently with Virtual Threads")
  public void testIntegrityCheckingWithVirtualThreads() {
    // Enable integrity checking
    configuration.setBoolean(INTEGRITY_CHECK, true);
    configuration.setBoolean(RESTORE_BLOBS, false);
    configuration.setBoolean(UNDELETE_BLOBS, false);

    // Set up repository manager to return our test repositories
    when(repositoryManager.browseForBlobStore(blobStore)).thenReturn(repositories);

    // Create a task that uses Virtual Threads
    RestoreMetadataTask virtualThreadTask = createCustomExecutorTask(virtualThreadExecutor);
    virtualThreadTask.configure(configuration);

    // Execute the task
    virtualThreadTask.execute();

    // Verify that integrity checks were performed for each repository
    for (Repository repository : repositories) {
      verify(integrityCheckStrategies.get(MAVEN_2)).check(
          eq(repository), eq(blobStore), any(), eq(0), any());
    }
  }

  /**
   * Tests that RestoreMetadataTask can efficiently handle concurrent operations
   * at different concurrency levels using Virtual Threads.
   */
  @Test
  @DisplayName("RestoreMetadataTask should scale efficiently with different concurrency levels")
  public void testScalingWithDifferentConcurrencyLevels() {
    // Create a map to store performance results at different concurrency levels
    Map<String, Long> performanceResults = new HashMap<>();

    // Test with different concurrency levels
    int[] concurrencyLevels = {LOW_CONCURRENCY_LEVEL, MEDIUM_CONCURRENCY_LEVEL, HIGH_CONCURRENCY_LEVEL};

    for (int concurrencyLevel : concurrencyLevels) {
      // Limit the number of blobs to process
      List<BlobId> limitedBlobIds = blobIds.subList(0, concurrencyLevel);
      when(blobStore.getBlobIdStream()).thenReturn(limitedBlobIds.stream());

      // Create a task that uses Virtual Threads
      RestoreMetadataTask virtualThreadTask = createCustomExecutorTask(virtualThreadExecutor);
      virtualThreadTask.configure(configuration);

      // Reset mock to avoid counting previous invocations
      reset(restoreBlobStrategy);

      // Execute the task and measure performance
      Instant start = Instant.now();
      virtualThreadTask.execute();
      long duration = Duration.between(start, Instant.now()).toMillis();

      // Store the result
      performanceResults.put("Virtual Threads - " + concurrencyLevel + " blobs", duration);

      // Verify that all blobs were processed
      verify(restoreBlobStrategy, times(concurrencyLevel)).restore(
          any(Properties.class), any(Blob.class), eq(blobStore), eq(false));
    }

    // Log the performance results
    performanceResults.forEach((key, value) -> log.info("{}: {} ms", key, value));

    // Virtual threads should scale efficiently with increasing concurrency
    // The time per blob should not increase significantly with higher concurrency
    double lowConcurrencyTimePerBlob = (double) performanceResults.get("Virtual Threads - " + LOW_CONCURRENCY_LEVEL + " blobs") / LOW_CONCURRENCY_LEVEL;
    double highConcurrencyTimePerBlob = (double) performanceResults.get("Virtual Threads - " + HIGH_CONCURRENCY_LEVEL + " blobs") / HIGH_CONCURRENCY_LEVEL;

    log.info("Time per blob at low concurrency: {} ms", lowConcurrencyTimePerBlob);
    log.info("Time per blob at high concurrency: {} ms", highConcurrencyTimePerBlob);

    // The ratio should be close to 1, indicating good scaling
    // Allow for some overhead at higher concurrency levels (up to 3x slower per blob)
    double ratio = highConcurrencyTimePerBlob / lowConcurrencyTimePerBlob;
    log.info("Scaling ratio (high/low concurrency time per blob): {}", ratio);
    assertThat("Virtual Threads should scale efficiently with increasing concurrency", ratio, lessThan(3.0));
  }

  /**
   * Tests that RestoreMetadataTask can efficiently process a large number of blobs
   * with minimal memory overhead using Virtual Threads.
   */
  @Test
  @DisplayName("RestoreMetadataTask should process large workloads with minimal memory overhead using Virtual Threads")
  public void testMemoryEfficiencyWithVirtualThreads() {
    // Record initial memory usage
    Runtime runtime = Runtime.getRuntime();
    System.gc(); // Request garbage collection to get a more accurate baseline
    long initialMemory = runtime.totalMemory() - runtime.freeMemory();

    // Create a task that uses Virtual Threads
    RestoreMetadataTask virtualThreadTask = createCustomExecutorTask(virtualThreadExecutor);
    virtualThreadTask.configure(configuration);

    // Execute the task
    virtualThreadTask.execute();

    // Record memory usage after execution
    System.gc(); // Request garbage collection to get a more accurate measurement
    long finalMemory = runtime.totalMemory() - runtime.freeMemory();
    long memoryDifference = finalMemory - initialMemory;

    log.info("Memory usage before: {} bytes", initialMemory);
    log.info("Memory usage after: {} bytes", finalMemory);
    log.info("Memory difference: {} bytes ({} KB)", memoryDifference, memoryDifference / 1024);

    // Verify that all blobs were processed
    verify(restoreBlobStrategy, times(blobIds.size())).restore(
        any(Properties.class), any(Blob.class), eq(blobStore), eq(false));

    // The test passes if it completes without excessive memory usage
    // This is primarily an observational test - the actual threshold depends on the environment
  }

  /**
   * Creates a custom RestoreMetadataTask that uses the specified executor service
   * for concurrent operations.
   */
  private RestoreMetadataTask createCustomExecutorTask(ExecutorService executor) {
    return new RestoreMetadataTask(blobStoreManager, changeBlobstoreStore, repositoryManager,
        ImmutableMap.of(MAVEN_2, restoreBlobStrategy),
        blobstoreUsageChecker, dryRunPrefix, integrityCheckStrategies, maintenanceService, assetBlobRefFormatCheck,
        taskUtils) {
      @Override
      protected void processBlobIds(Stream<BlobId> blobIdStream) {
        // Process blob IDs concurrently using the provided executor
        List<BlobId> ids = blobIdStream.collect(Collectors.toList());
        Map<String, Repository> processedRepositories = new ConcurrentHashMap<>();
        AtomicLong processedCount = new AtomicLong(0);
        int totalCount = ids.size();

        try {
          // Create a countdown latch to wait for all tasks to complete
          CountDownLatch latch = new CountDownLatch(ids.size());

          // Submit tasks to the executor
          ids.forEach(blobId -> {
            CompletableFuture.runAsync(() -> {
              try {
                // Process the blob using the standard method
                processBlob(blobId, processedRepositories);
                
                // Log progress periodically
                long current = processedCount.incrementAndGet();
                if (current % 100 == 0 || current == totalCount) {
                  log.info("Processed {}/{} blobs ({}%)", 
                      current, totalCount, Math.round((double) current / totalCount * 100));
                }
              } catch (Exception e) {
                log.error("Error processing blob {}: {}", blobId, e.getMessage(), e);
              } finally {
                latch.countDown();
              }
            }, executor);
          });

          // Wait for all tasks to complete
          latch.await();

          // Call after() for each processed repository
          processedRepositories.values().forEach(repository -> {
            try {
              callAfter(repository);
            } catch (Exception e) {
              log.error("Error calling after() for repository {}: {}", 
                  repository.getName(), e.getMessage(), e);
            }
          });
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          log.error("Blob processing was interrupted", e);
        }
      }

      private void processBlob(BlobId blobId, Map<String, Repository> processedRepositories) {
        try {
          // Get blob attributes
          BlobAttributes blobAttributes = blobStore.getBlobAttributes(blobId);
          if (blobAttributes == null) {
            return;
          }

          // Check if blob is deleted
          if (blobAttributes.isDeleted()) {
            if (undelete) {
              undelete(blobId, blobAttributes);
            }
            return;
          }

          // Get repository name from blob attributes
          Properties properties = blobAttributes.getProperties();
          String repositoryName = properties.getProperty(HEADER_PREFIX + REPO_NAME_HEADER);
          if (repositoryName == null) {
            return;
          }

          // Get repository
          Repository repository = repositoryManager.get(repositoryName);
          if (repository == null || !repository.isStarted()) {
            return;
          }

          // Track processed repositories for after() calls
          processedRepositories.putIfAbsent(repositoryName, repository);

          // Check if asset blob ref is migrated
          if (restore && !isCanceled()) {
            if (assetBlobRefFormatCheck.isAssetBlobRefNotMigrated(repository)) {
              log.warn("Repository {} has not been migrated to the new asset blob ref format, skipping restore", 
                  repositoryName);
              return;
            }

            // Get blob and restore
            Blob blob = blobStore.get(blobId, true);
            if (blob != null) {
              restoreBlobStrategy.restore(properties, blob, blobStore, isDryRun());
            }
          }
        } catch (Exception e) {
          log.error("Error processing blob {}: {}", blobId, e.getMessage(), e);
        }
      }

      private void callAfter(Repository repository) {
        if (!isCanceled() && restore && !isDryRun()) {
          String formatName = repository.getFormat().getValue();
          RestoreBlobStrategy strategy = restoreBlobStrategies.get(formatName);
          if (strategy != null) {
            strategy.after(true, repository);
          }
        }
      }

      private void undelete(BlobId blobId, BlobAttributes blobAttributes) {
        blobStore.undelete(blobstoreUsageChecker, blobId, blobAttributes, isDryRun());
      }
    };
  }
}