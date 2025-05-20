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
package org.sonatype.virtualthread;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.common.app.FreezeService;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Recipe;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.config.ConfigurationFacet;
import org.sonatype.nexus.repository.config.ConfigurationStore;
import org.sonatype.nexus.repository.manager.DefaultRepositoriesContributor;
import org.sonatype.nexus.repository.manager.RepositoryFactory;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.manager.internal.RepositoryAdminSecurityContributor;
import org.sonatype.nexus.repository.manager.internal.RepositoryManagerImpl;
import org.sonatype.nexus.repository.manager.internal.GroupMemberMappingCache;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.common.node.NodeAccess;
import org.sonatype.nexus.repository.security.HttpAuthenticationPasswordEncoder;

import com.google.common.collect.ImmutableMap;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;

import javax.inject.Provider;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests Repository Manager operations using Java 21 Virtual Threads, focusing on high-concurrency
 * repository creation, lookup, and browse operations.
 * 
 * This test class verifies that the Repository Manager maintains data consistency, thread safety,
 * and improved performance when thousands of virtual threads simultaneously access and modify
 * repository information.
 */
@Category(VirtualThreadTestGroup.class)
public class RepositoryManagerVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_OPERATIONS = 1000;
  private static final int REPOSITORY_COUNT = 100;
  private static final String RECIPE_NAME = "mockRecipe";
  
  @Mock
  private EventManager eventManager;

  @Mock
  private ConfigurationStore configurationStore;

  @Mock
  private FreezeService freezeService;

  @Mock
  private RepositoryFactory repositoryFactory;

  @Mock
  private Provider<ConfigurationFacet> configurationFacetProvider;

  @Mock
  private RepositoryAdminSecurityContributor securityContributor;

  @Mock
  private DefaultRepositoriesContributor defaultRepositoriesContributor;

  @Mock
  private Recipe recipe;

  @Mock
  private Type type;

  @Mock
  private Format format;

  @Mock
  private NodeAccess nodeAccess;

  @Mock
  private BlobStoreManager blobStoreManager;

  @Mock
  private GroupMemberMappingCache groupMemberMappingCache;

  @Mock
  private HttpAuthenticationPasswordEncoder httpAuthenticationPasswordEncoder;

  private RepositoryManager repositoryManager;
  private List<Repository> mockRepositories;
  private List<Configuration> mockConfigurations;

  @Before
  public void setup() throws Exception {
    setupRecipe();
    setupRepositories();
    initializeRepositoryManager();
  }

  private void setupRecipe() {
    when(recipe.getType()).thenReturn(type);
    when(recipe.getFormat()).thenReturn(format);
  }

  private void setupRepositories() {
    mockRepositories = new ArrayList<>();
    mockConfigurations = new ArrayList<>();

    for (int i = 0; i < REPOSITORY_COUNT; i++) {
      String repoName = "repository-" + i;
      Configuration config = mock(Configuration.class);
      Repository repository = mock(Repository.class);

      when(config.getRepositoryName()).thenReturn(repoName);
      when(config.getRecipeName()).thenReturn(RECIPE_NAME);
      when(repository.getName()).thenReturn(repoName);
      when(repository.getConfiguration()).thenReturn(config);

      mockRepositories.add(repository);
      mockConfigurations.add(config);
    }

    when(repositoryFactory.create(any(Type.class), any(Format.class)))
        .thenAnswer(invocation -> {
          if (mockRepositories.isEmpty()) {
            return mock(Repository.class);
          }
          return mockRepositories.remove(0);
        });

    when(configurationStore.list()).thenReturn(mockConfigurations);
    when(blobStoreManager.exists(any())).thenReturn(true);
  }

  private void initializeRepositoryManager() throws Exception {
    List<DefaultRepositoriesContributor> defaultRepositoriesContributorList = 
        singletonList(defaultRepositoriesContributor);
    when(defaultRepositoriesContributor.getRepositoryConfigurations()).thenReturn(emptyList());

    repositoryManager = new RepositoryManagerImpl(
        eventManager,
        configurationStore,
        repositoryFactory,
        configurationFacetProvider,
        ImmutableMap.of(RECIPE_NAME, recipe),
        securityContributor,
        defaultRepositoriesContributorList,
        freezeService,
        true,
        blobStoreManager,
        groupMemberMappingCache,
        emptyList(),
        httpAuthenticationPasswordEncoder);

    repositoryManager.doStart();
  }

  /**
   * Tests concurrent repository lookup operations using virtual threads.
   * This test creates thousands of virtual threads that simultaneously look up repositories
   * to verify thread safety and data consistency under high concurrency.
   */
  @Test
  public void testConcurrentRepositoryLookupWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Set up a latch to coordinate all threads
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_OPERATIONS);
      
      // Track errors that occur during concurrent execution
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Create a large number of concurrent lookup operations
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final int index = i % REPOSITORY_COUNT;
        final String repoName = "repository-" + index;
        
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Perform repository lookup
            Repository repository = repositoryManager.get(repoName);
            
            // Verify the repository was found and has the correct name
            if (repository == null || !repository.getName().equals(repoName)) {
              errorCount.incrementAndGet();
            }
          } 
          catch (Exception e) {
            log.error("Error in virtual thread", e);
            errorCount.incrementAndGet();
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all operations to complete
      boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
      
      // Verify all operations completed successfully
      assertThat("All operations should complete within the timeout", completed, is(true));
      assertThat("No errors should occur during concurrent repository lookups", 
          errorCount.get(), is(0));
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Compares the performance of repository browse operations between platform threads and virtual threads.
   * This test measures throughput and memory efficiency when browsing repositories under high concurrency.
   */
  @Test
  public void testRepositoryBrowsePerformanceComparison() throws Exception {
    // Create thread factories for both types
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Run benchmark with platform threads
    PerformanceResult platformResult = benchmarkRepositoryBrowse(platformThreadFactory, "Platform Threads");
    
    // Run benchmark with virtual threads
    PerformanceResult virtualResult = benchmarkRepositoryBrowse(virtualThreadFactory, "Virtual Threads");
    
    // Log performance comparison
    log.info("Performance comparison for repository browse operations:");
    log.info("Platform Threads - Operations/sec: {}, Avg time: {} ms", 
        platformResult.operationsPerSecond, platformResult.averageTimeMs);
    log.info("Virtual Threads - Operations/sec: {}, Avg time: {} ms", 
        virtualResult.operationsPerSecond, virtualResult.averageTimeMs);
    
    // Virtual threads should handle more operations per second
    assertThat("Virtual threads should provide better throughput",
        virtualResult.operationsPerSecond, greaterThan(platformResult.operationsPerSecond));
  }

  /**
   * Tests repository list operations with thousands of concurrent virtual threads.
   * This test verifies that the repository manager can handle extreme concurrency
   * when listing repositories, maintaining consistency and performance.
   */
  @Test
  public void testHighConcurrencyRepositoryListOperations() throws Exception {
    // Create a virtual thread executor
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Set up tracking for results
      Set<String> uniqueRepositorySets = ConcurrentHashMap.newKeySet();
      CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_OPERATIONS);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Launch many concurrent operations
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        executor.submit(() -> {
          try {
            // Browse repositories and collect names
            List<String> repoNames = repositoryManager.browse()
                .stream()
                .map(Repository::getName)
                .collect(Collectors.toList());
            
            // Add the set of repository names to our tracking set
            uniqueRepositorySets.add(repoNames.toString());
            
            // Verify we got the expected number of repositories
            if (repoNames.size() != REPOSITORY_COUNT) {
              errorCount.incrementAndGet();
            }
          } 
          catch (Exception e) {
            log.error("Error in repository list operation", e);
            errorCount.incrementAndGet();
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("All operations should complete within the timeout", completed, is(true));
      assertThat("No errors should occur during concurrent repository list operations", 
          errorCount.get(), is(0));
      assertThat("All threads should see the same repository list", 
          uniqueRepositorySets.size(), is(1));
    }
  }

  /**
   * Tests thread safety of repository operations under extreme virtual thread parallelism.
   * This test creates and looks up repositories concurrently to verify data consistency.
   */
  @Test
  public void testThreadSafetyUnderExtremeParallelism() throws Exception {
    // Create a map to track repositories by name
    Map<String, Repository> createdRepositories = new ConcurrentHashMap<>();
    
    // Create a virtual thread executor
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create a large number of repositories concurrently
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      
      for (int i = 0; i < 500; i++) {
        final String repoName = "concurrent-repo-" + i;
        
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            // Create a new repository configuration
            Configuration config = mock(Configuration.class);
            when(config.getRepositoryName()).thenReturn(repoName);
            when(config.getRecipeName()).thenReturn(RECIPE_NAME);
            when(config.copy()).thenReturn(config);
            
            // Create the repository
            Repository repository = repositoryManager.create(config);
            createdRepositories.put(repoName, repository);
          }
          catch (Exception e) {
            log.error("Error creating repository: {}", repoName, e);
          }
        }, executor);
        
        futures.add(future);
      }
      
      // Wait for all creation operations to complete
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
      
      // Now verify all repositories can be looked up concurrently
      List<CompletableFuture<Boolean>> lookupFutures = new ArrayList<>();
      AtomicInteger lookupErrorCount = new AtomicInteger(0);
      
      for (String repoName : createdRepositories.keySet()) {
        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
          try {
            Repository repository = repositoryManager.get(repoName);
            return repository != null && repository.getName().equals(repoName);
          }
          catch (Exception e) {
            log.error("Error looking up repository: {}", repoName, e);
            lookupErrorCount.incrementAndGet();
            return false;
          }
        }, executor);
        
        lookupFutures.add(future);
      }
      
      // Wait for all lookup operations to complete
      CompletableFuture<Void> allLookups = CompletableFuture.allOf(
          lookupFutures.toArray(new CompletableFuture[0]));
      allLookups.join();
      
      // Count successful lookups
      long successfulLookups = lookupFutures.stream()
          .map(CompletableFuture::join)
          .filter(Boolean::booleanValue)
          .count();
      
      // Verify results
      assertThat("No errors should occur during concurrent repository lookups", 
          lookupErrorCount.get(), is(0));
      assertThat("All repositories should be successfully looked up", 
          successfulLookups, is((long) createdRepositories.size()));
    }
  }

  /**
   * Tests memory efficiency by running operations with thousands of virtual threads simultaneously.
   * This test verifies that virtual threads use significantly less memory than platform threads
   * when performing the same operations at scale.
   */
  @Test
  public void testMemoryEfficiencyWithThousandsOfVirtualThreads() throws Exception {
    // Number of threads to create - this would be impractical with platform threads
    final int THREAD_COUNT = 10_000;
    
    // Create a virtual thread executor
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Record memory usage before creating threads
      long memoryBefore = getUsedMemory();
      
      // Create thousands of virtual threads that perform repository operations
      CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
      
      for (int i = 0; i < THREAD_COUNT; i++) {
        final int index = i % REPOSITORY_COUNT;
        final String repoName = "repository-" + index;
        
        executor.submit(() -> {
          try {
            // Perform a simple repository lookup
            repositoryManager.get(repoName);
            
            // Small delay to ensure threads coexist
            Thread.sleep(10);
          }
          catch (Exception e) {
            log.error("Error in virtual thread operation", e);
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Wait for half the threads to be created and record memory
      while (completionLatch.getCount() > THREAD_COUNT / 2) {
        Thread.sleep(100);
      }
      
      // Record memory usage with thousands of active virtual threads
      long memoryDuring = getUsedMemory();
      
      // Wait for all operations to complete
      completionLatch.await(30, TimeUnit.SECONDS);
      
      // Calculate memory overhead per thread
      long memoryOverhead = memoryDuring - memoryBefore;
      long overheadPerThread = memoryOverhead / (THREAD_COUNT / 2);
      
      log.info("Memory overhead for {} virtual threads: {} bytes", THREAD_COUNT / 2, memoryOverhead);
      log.info("Average memory overhead per virtual thread: {} bytes", overheadPerThread);
      
      // Virtual threads should have very low memory overhead per thread
      // Platform threads typically use 2MB+ each, virtual threads should use orders of magnitude less
      assertThat("Virtual threads should have low memory overhead", 
          overheadPerThread, lessThan(50_000L));
    }
  }

  /**
   * Helper method to benchmark repository browse operations using the specified thread factory.
   */
  private PerformanceResult benchmarkRepositoryBrowse(
      ThreadFactory threadFactory, 
      String threadType) throws Exception {
    
    final int OPERATIONS = 1000;
    final int WARMUP_OPERATIONS = 100;
    
    // Create executor with the specified thread factory
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    
    try {
      // Warm up
      runBrowseOperations(executor, WARMUP_OPERATIONS);
      
      // Measure performance
      long startTime = System.nanoTime();
      OperationStats stats = runBrowseOperations(executor, OPERATIONS);
      long endTime = System.nanoTime();
      
      // Calculate metrics
      double totalTimeSeconds = Duration.ofNanos(endTime - startTime).toMillis() / 1000.0;
      double operationsPerSecond = OPERATIONS / totalTimeSeconds;
      double averageTimeMs = stats.totalTimeMs.doubleValue() / OPERATIONS;
      
      return new PerformanceResult(operationsPerSecond, averageTimeMs);
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Runs the specified number of repository browse operations using the provided executor.
   */
  private OperationStats runBrowseOperations(ExecutorService executor, int operations) 
      throws Exception {
    
    CountDownLatch completionLatch = new CountDownLatch(operations);
    LongAdder totalTimeMs = new LongAdder();
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Launch operations
    for (int i = 0; i < operations; i++) {
      executor.submit(() -> {
        try {
          long startTime = System.nanoTime();
          
          // Browse all repositories
          List<Repository> repositories = IntStream.range(0, REPOSITORY_COUNT)
              .mapToObj(index -> "repository-" + index)
              .map(name -> repositoryManager.get(name))
              .collect(Collectors.toList());
          
          long endTime = System.nanoTime();
          totalTimeMs.add(Duration.ofNanos(endTime - startTime).toMillis());
          
          // Verify we got the expected number of repositories
          if (repositories.size() != REPOSITORY_COUNT) {
            errorCount.incrementAndGet();
          }
        } 
        catch (Exception e) {
          log.error("Error in browse operation", e);
          errorCount.incrementAndGet();
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
    assertThat("All operations should complete within the timeout", completed, is(true));
    assertThat("No errors should occur during browse operations", errorCount.get(), is(0));
    
    return new OperationStats(totalTimeMs);
  }

  /**
   * Gets the current used memory in bytes.
   */
  private long getUsedMemory() {
    Runtime runtime = Runtime.getRuntime();
    // Force garbage collection to get more accurate memory usage
    runtime.gc();
    return runtime.totalMemory() - runtime.freeMemory();
  }

  /**
   * Simple class to hold performance test results.
   */
  private static class PerformanceResult {
    final double operationsPerSecond;
    final double averageTimeMs;
    
    PerformanceResult(double operationsPerSecond, double averageTimeMs) {
      this.operationsPerSecond = operationsPerSecond;
      this.averageTimeMs = averageTimeMs;
    }
  }

  /**
   * Simple class to hold operation statistics.
   */
  private static class OperationStats {
    final LongAdder totalTimeMs;
    
    OperationStats(LongAdder totalTimeMs) {
      this.totalTimeMs = totalTimeMs;
    }
  }
}