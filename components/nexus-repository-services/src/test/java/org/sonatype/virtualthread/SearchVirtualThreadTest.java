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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.manager.internal.RepositoryImpl;
import org.sonatype.nexus.repository.search.index.ElasticSearchIndexServiceImpl;
import org.sonatype.nexus.repository.search.index.IndexSettingsContributor;
import org.sonatype.nexus.repository.search.index.SearchIndexFacet;
import org.sonatype.nexus.repository.search.query.ElasticSearchQueryServiceImpl;
import org.sonatype.nexus.repository.search.query.SearchSubjectHelper;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.security.SecurityHelper;

import org.elasticsearch.action.ListenableActionFuture;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsRequestBuilder;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsResponse;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.AdminClient;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.IndicesAdminClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.common.hash.HashAlgorithm.SHA1;
import static org.sonatype.nexus.repository.search.index.SearchConstants.TYPE;

/**
 * Tests search operations using Java 21 Virtual Threads in the repository services context.
 * This test class focuses on validating that search indexing, querying, and Elasticsearch
 * interactions work correctly and efficiently with virtual threads.
 */
public class SearchVirtualThreadTest
    extends TestSupport
{
  private static final int COMPONENT_COUNT = 1000;
  private static final int CONCURRENT_QUERIES = 100;
  private static final int WARMUP_ITERATIONS = 3;
  private static final int BENCHMARK_ITERATIONS = 5;
  
  @Mock
  private Client client;
  
  @Mock
  private AdminClient adminClient;
  
  @Mock
  private IndicesAdminClient indicesAdminClient;
  
  @Mock
  private IndicesExistsRequestBuilder indicesExistsRequestBuilder;
  
  @Mock
  private ListenableActionFuture<IndicesExistsResponse> actionFuture;
  
  @Mock
  private IndicesExistsResponse indicesExistsResponse;
  
  @Mock
  private RepositoryManager repositoryManager;
  
  @Mock
  private SecurityHelper securityHelper;
  
  @Mock
  private SearchSubjectHelper searchSubjectHelper;
  
  @Mock
  private List<IndexSettingsContributor> indexSettingsContributors;
  
  @Mock
  private EventManager eventManager;
  
  @Mock
  private BulkProcessor bulkProcessor;
  
  @Mock
  private SearchRequestBuilder searchRequestBuilder;
  
  @Mock
  private SearchResponse searchResponse;
  
  @Mock
  private SearchHit searchHit;
  
  private ElasticSearchIndexServiceImpl searchIndexService;
  private ElasticSearchQueryServiceImpl searchQueryService;
  private Repository repository;
  private String indexName;
  
  @Before
  public void setup() throws Exception {
    // Setup Elasticsearch client mocks
    when(client.admin()).thenReturn(adminClient);
    when(adminClient.indices()).thenReturn(indicesAdminClient);
    when(client.settings()).thenReturn(Settings.EMPTY);
    when(client.prepareSearch(anyString())).thenReturn(searchRequestBuilder);
    when(searchRequestBuilder.setQuery(any())).thenReturn(searchRequestBuilder);
    when(searchRequestBuilder.execute()).thenReturn(actionFuture);
    when(actionFuture.actionGet()).thenReturn(searchResponse);
    when(searchResponse.getHits()).thenReturn(new SearchHit[] { searchHit });
    
    // Setup index existence check
    when(indicesAdminClient.prepareExists(anyString())).thenReturn(indicesExistsRequestBuilder);
    when(indicesExistsRequestBuilder.execute()).thenReturn(actionFuture);
    when(actionFuture.actionGet()).thenReturn(indicesExistsResponse);
    when(indicesExistsResponse.isExists()).thenReturn(true);
    
    // Create search services
    searchIndexService = new ElasticSearchIndexServiceImpl(
        () -> client,
        name -> SHA1.function().hashUnencodedChars(name).toString(),
        indexSettingsContributors,
        eventManager,
        1000, 0, 0, 3000, 1);
    
    searchQueryService = new ElasticSearchQueryServiceImpl(
        () -> client,
        repositoryManager,
        securityHelper,
        searchSubjectHelper,
        name -> SHA1.function().hashUnencodedChars(name).toString(),
        false);
    
    // Setup bulk processor
    Map<Integer, Map.Entry<BulkProcessor, ExecutorService>> bulkProcessorToExecutors = new HashMap<>();
    bulkProcessorToExecutors.put(0, Map.entry(bulkProcessor, Executors.newSingleThreadExecutor()));
    searchIndexService.setBulkProcessorToExecutors(bulkProcessorToExecutors);
    
    // Create test repository
    repository = createRepository("test-repo");
    indexName = SHA1.function().hashUnencodedChars(repository.getName()).toString();
    searchIndexService.createIndex(repository);
    
    // Setup index request mocking
    setupIndexRequestMocking();
  }
  
  @After
  public void tearDown() {
    // Shutdown any executors that might have been created
    searchIndexService.getBulkProcessorToExecutors().values().forEach(entry -> {
      if (entry.getValue() != null && !entry.getValue().isShutdown()) {
        entry.getValue().shutdown();
      }
    });
  }
  
  /**
   * Tests bulk indexing performance using platform threads vs virtual threads.
   * This test compares the performance of indexing a large number of components
   * using traditional platform threads versus Java 21 virtual threads.
   */
  @Test
  public void testBulkIndexingPerformanceComparison() throws Exception {
    // Generate test components
    List<Map<String, Object>> components = generateTestComponents(COMPONENT_COUNT);
    
    // Warm up to avoid JIT compilation effects
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runBulkIndexWithPlatformThreads(components, 10);
      runBulkIndexWithVirtualThreads(components, 10);
    }
    
    // Benchmark platform threads
    long platformThreadTime = 0;
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      platformThreadTime += runBulkIndexWithPlatformThreads(components, COMPONENT_COUNT);
    }
    long avgPlatformThreadTime = platformThreadTime / BENCHMARK_ITERATIONS;
    
    // Benchmark virtual threads
    long virtualThreadTime = 0;
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      virtualThreadTime += runBulkIndexWithVirtualThreads(components, COMPONENT_COUNT);
    }
    long avgVirtualThreadTime = virtualThreadTime / BENCHMARK_ITERATIONS;
    
    log.info("Average bulk indexing time with platform threads: {} ms", avgPlatformThreadTime);
    log.info("Average bulk indexing time with virtual threads: {} ms", avgVirtualThreadTime);
    
    // Virtual threads should be more efficient for I/O-bound operations
    assertThat("Virtual threads should be faster than platform threads for I/O-bound operations",
        avgVirtualThreadTime, lessThan(avgPlatformThreadTime));
  }
  
  /**
   * Tests concurrent search query execution using virtual threads.
   * This test verifies that a large number of concurrent search queries
   * can be efficiently executed using virtual threads.
   */
  @Test
  public void testConcurrentSearchQueriesWithVirtualThreads() throws Exception {
    // Setup search response mocking
    when(searchResponse.getHits()).thenReturn(new SearchHit[] { searchHit });
    when(searchHit.getSourceAsMap()).thenReturn(Collections.singletonMap("id", "test-id"));
    
    // Create a countdown latch to wait for all queries to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_QUERIES);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Execute concurrent search queries using virtual threads
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_QUERIES; i++) {
        final int queryId = i;
        executor.submit(() -> {
          try {
            // Simulate a search query
            searchQueryService.search(QueryBuilders.matchAllQuery(), repository.getName(), 0, 10);
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            log.error("Error executing search query {}: {}", queryId, e.getMessage(), e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all queries to complete (with timeout)
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertTrue("All concurrent search queries should complete within the timeout", completed);
      assertEquals("All search queries should succeed", CONCURRENT_QUERIES, successCount.get());
      
      // Verify that the search was executed the expected number of times
      verify(client, times(CONCURRENT_QUERIES)).prepareSearch(eq(indexName));
    }
  }
  
  /**
   * Tests memory utilization when using virtual threads for search operations.
   * This test measures memory usage before and after executing a large number
   * of concurrent search operations using virtual threads.
   */
  @Test
  public void testMemoryUtilizationWithVirtualThreads() throws Exception {
    // Measure initial memory usage
    System.gc(); // Request garbage collection to get more accurate measurements
    long initialMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Generate a large number of concurrent search tasks using virtual threads
    int concurrentTasks = 10000; // 10K concurrent virtual threads
    CountDownLatch latch = new CountDownLatch(concurrentTasks);
    
    // Execute search tasks using virtual threads
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < concurrentTasks; i++) {
        executor.submit(() -> {
          try {
            // Simulate a lightweight search operation
            searchQueryService.search(QueryBuilders.matchAllQuery(), repository.getName(), 0, 10);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
    }
    
    // Measure memory after virtual thread execution
    System.gc(); // Request garbage collection
    long afterVirtualThreadsMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Now perform the same operations with platform threads for comparison
    // We'll use a smaller number to avoid OutOfMemoryError
    int platformThreadTasks = 1000; // 1K platform threads
    CountDownLatch platformLatch = new CountDownLatch(platformThreadTasks);
    
    // Create a fixed thread pool with platform threads
    try (ExecutorService platformExecutor = Executors.newFixedThreadPool(100)) {
      for (int i = 0; i < platformThreadTasks; i++) {
        platformExecutor.submit(() -> {
          try {
            // Simulate the same search operation
            searchQueryService.search(QueryBuilders.matchAllQuery(), repository.getName(), 0, 10);
          }
          finally {
            platformLatch.countDown();
          }
        });
      }
      
      // Wait for all platform thread tasks to complete
      platformLatch.await(30, TimeUnit.SECONDS);
      platformExecutor.shutdown();
      platformExecutor.awaitTermination(10, TimeUnit.SECONDS);
    }
    
    // Measure memory after platform thread execution
    System.gc();
    long afterPlatformThreadsMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Calculate memory usage per thread for both approaches
    long virtualThreadMemoryPerThread = (afterVirtualThreadsMemory - initialMemory) / concurrentTasks;
    long platformThreadMemoryPerThread = (afterPlatformThreadsMemory - afterVirtualThreadsMemory) / platformThreadTasks;
    
    log.info("Memory usage per virtual thread: {} bytes", virtualThreadMemoryPerThread);
    log.info("Memory usage per platform thread: {} bytes", platformThreadMemoryPerThread);
    
    // Virtual threads should use significantly less memory per thread
    assertThat("Virtual threads should use less memory per thread than platform threads",
        virtualThreadMemoryPerThread, lessThan(platformThreadMemoryPerThread));
  }
  
  /**
   * Tests the scalability of virtual threads for handling thousands of concurrent search operations.
   * This test verifies that virtual threads can efficiently handle a very large number of
   * concurrent search operations without exhausting system resources.
   */
  @Test
  public void testVirtualThreadScalabilityForSearchOperations() throws Exception {
    // Define a large number of concurrent search operations
    int concurrentOperations = 5000; // 5K concurrent operations
    
    // Create a map to track completion times
    ConcurrentHashMap<Integer, Long> completionTimes = new ConcurrentHashMap<>();
    CountDownLatch latch = new CountDownLatch(concurrentOperations);
    
    // Record start time
    long startTime = System.nanoTime();
    
    // Execute search operations using virtual threads
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < concurrentOperations; i++) {
        final int operationId = i;
        executor.submit(() -> {
          try {
            // Simulate a search operation with varying complexity
            searchQueryService.search(QueryBuilders.matchAllQuery(), repository.getName(), 0, operationId % 20 + 1);
            // Record completion time
            completionTimes.put(operationId, System.nanoTime());
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      boolean completed = latch.await(60, TimeUnit.SECONDS);
      assertTrue("All search operations should complete within the timeout", completed);
    }
    
    // Calculate total duration
    long endTime = System.nanoTime();
    Duration totalDuration = Duration.ofNanos(endTime - startTime);
    
    // Calculate throughput (operations per second)
    double throughput = concurrentOperations / (totalDuration.toMillis() / 1000.0);
    
    // Calculate percentiles for operation completion times
    List<Long> sortedCompletionTimes = completionTimes.values().stream()
        .map(time -> time - startTime)
        .sorted()
        .collect(Collectors.toList());
    
    long p50 = sortedCompletionTimes.get(sortedCompletionTimes.size() / 2);
    long p95 = sortedCompletionTimes.get((int) (sortedCompletionTimes.size() * 0.95));
    long p99 = sortedCompletionTimes.get((int) (sortedCompletionTimes.size() * 0.99));
    
    log.info("Virtual Thread Search Operations Throughput: {} ops/sec", String.format("%.2f", throughput));
    log.info("P50 latency: {} ms", Duration.ofNanos(p50).toMillis());
    log.info("P95 latency: {} ms", Duration.ofNanos(p95).toMillis());
    log.info("P99 latency: {} ms", Duration.ofNanos(p99).toMillis());
    
    // Verify that the throughput is reasonable
    // The actual threshold depends on the test environment, but we expect it to be high
    assertThat("Search operation throughput should be high with virtual threads",
        throughput, greaterThanOrEqualTo(1000.0));
    
    // Verify that the search was executed the expected number of times
    verify(client, times(concurrentOperations)).prepareSearch(eq(indexName));
  }
  
  /**
   * Tests the performance of bulk indexing operations using platform threads.
   */
  private long runBulkIndexWithPlatformThreads(List<Map<String, Object>> components, int batchSize) throws Exception {
    long startTime = System.currentTimeMillis();
    
    // Create a fixed thread pool with platform threads
    try (ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {
      List<Future<Void>> futures = new ArrayList<>();
      
      // Process components in batches
      for (int i = 0; i < components.size(); i += batchSize) {
        int endIndex = Math.min(i + batchSize, components.size());
        List<Map<String, Object>> batch = components.subList(i, endIndex);
        
        // Submit batch for processing
        futures.add(CompletableFuture.runAsync(() -> {
          try {
            searchIndexService.bulkPut(repository, batch, 
                component -> (String) component.get("id"),
                component -> String.format("{\"id\":\"%s\",\"name\":\"%s\"}", 
                    component.get("id"), component.get("name")));
          }
          catch (Exception e) {
            throw new RuntimeException(e);
          }
        }, executor).thenApply(v -> null));
      }
      
      // Wait for all futures to complete
      for (Future<Void> future : futures) {
        future.get();
      }
    }
    
    return System.currentTimeMillis() - startTime;
  }
  
  /**
   * Tests the performance of bulk indexing operations using virtual threads.
   */
  private long runBulkIndexWithVirtualThreads(List<Map<String, Object>> components, int batchSize) throws Exception {
    long startTime = System.currentTimeMillis();
    
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Void>> futures = new ArrayList<>();
      
      // Process components in batches
      for (int i = 0; i < components.size(); i += batchSize) {
        int endIndex = Math.min(i + batchSize, components.size());
        List<Map<String, Object>> batch = components.subList(i, endIndex);
        
        // Submit batch for processing using virtual threads
        futures.add(CompletableFuture.runAsync(() -> {
          try {
            searchIndexService.bulkPut(repository, batch, 
                component -> (String) component.get("id"),
                component -> String.format("{\"id\":\"%s\",\"name\":\"%s\"}", 
                    component.get("id"), component.get("name")));
          }
          catch (Exception e) {
            throw new RuntimeException(e);
          }
        }, executor).thenApply(v -> null));
      }
      
      // Wait for all futures to complete
      for (Future<Void> future : futures) {
        future.get();
      }
    }
    
    return System.currentTimeMillis() - startTime;
  }
  
  /**
   * Generates a list of test components with random IDs and names.
   */
  private List<Map<String, Object>> generateTestComponents(int count) {
    return IntStream.range(0, count)
        .mapToObj(i -> {
          Map<String, Object> component = new HashMap<>();
          component.put("id", UUID.randomUUID().toString());
          component.put("name", "Component-" + i);
          return component;
        })
        .collect(Collectors.toList());
  }
  
  /**
   * Sets up mocking for index requests to simulate Elasticsearch behavior.
   */
  private void setupIndexRequestMocking() {
    // Mock index request builder
    IndexRequestBuilder indexRequestBuilder = mock(IndexRequestBuilder.class);
    when(client.prepareIndex(eq(indexName), eq(TYPE), anyString())).thenReturn(indexRequestBuilder);
    when(indexRequestBuilder.setSource(anyString())).thenReturn(indexRequestBuilder);
    
    // Mock index request
    org.elasticsearch.action.index.IndexRequest indexRequest = mock(org.elasticsearch.action.index.IndexRequest.class);
    when(indexRequestBuilder.request()).thenReturn(indexRequest);
  }
  
  /**
   * Creates a test repository with the given name.
   */
  private Repository createRepository(String name) throws Exception {
    TestRepository repository = new TestRepository(eventManager, new HostedType(), new TestFormat("test"));
    repository.setName(name);
    
    Configuration configuration = mock(Configuration.class);
    when(configuration.isOnline()).thenReturn(true);
    repository.setConfiguration(configuration);
    
    SearchIndexFacet searchFacet = mock(SearchIndexFacet.class);
    repository.attach(searchFacet);
    
    return repository;
  }
  
  /**
   * Test implementation of Format for testing purposes.
   */
  class TestFormat extends Format {
    TestFormat(final String value) {
      super(value);
    }
  }
  
  /**
   * Test implementation of Repository for testing purposes.
   */
  class TestRepository extends RepositoryImpl {
    private String name;
    private Configuration configuration;
    
    public TestRepository(EventManager eventManager, Type type, Format format) {
      super(eventManager, type, format);
    }
    
    public void setName(String name) {
      this.name = name;
    }
    
    public void setConfiguration(Configuration configuration) {
      this.configuration = configuration;
    }
    
    @Override
    public String getName() {
      return name;
    }
    
    @Override
    public Configuration getConfiguration() {
      return configuration;
    }
  }
}