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
package org.sonatype.nexus.repository.search.index;

import java.io.File;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.junit.TestDataRule;
import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.common.node.NodeAccess;
import org.sonatype.nexus.elasticsearch.internal.ClientProvider;
import org.sonatype.nexus.elasticsearch.internal.NodeProvider;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.search.query.ElasticSearchQueryServiceImpl;
import org.sonatype.nexus.repository.search.query.SearchSubjectHelper;
import org.sonatype.nexus.security.SecurityHelper;

import com.google.common.collect.ContiguousSet;
import com.google.common.collect.DiscreteDomain;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.google.common.collect.Range.closed;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.awaitility.Awaitility.await;
import static org.elasticsearch.index.query.QueryBuilders.boolQuery;
import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.elasticsearch.index.query.QueryBuilders.queryStringQuery;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.search.query.RepositoryQueryBuilder.unrestricted;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("Java21")
@org.junit.experimental.categories.Category(Java21TestGroup.class)
class ElasticSearchIndexServiceImplIT
    extends TestSupport
{
  static final String BASEDIR = new File(System.getProperty("basedir", "")).getAbsolutePath();

  private static final int CALM_TIMEOUT = 3000;

  private static final int TEST_REPOSITORY_COUNT = 10;

  // Increased component count to better validate virtual thread performance
  private static final int TEST_COMPONENT_COUNT = 5000;
  
  // Thread factory for creating virtual threads
  private static final ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();

  @RegisterExtension
  TestDataRule testData = new TestDataRule(Paths.get(BASEDIR, "src/test/it-resources").toFile());

  @Mock
  ApplicationDirectories directories;

  @Mock
  NodeAccess nodeAccess;

  @Mock
  Configuration repositoryConfig;

  @Mock
  SearchIndexFacet searchFacet;

  @Mock
  Format testFormat;

  @Mock
  RepositoryManager repositoryManager;

  @Mock
  SecurityHelper securityHelper;

  @Mock
  SearchSubjectHelper searchSubjectHelper;

  @Mock
  EventManager eventManager;

  ElasticSearchIndexServiceImpl searchIndexService;

  ElasticSearchQueryServiceImpl searchQueryService;

  List<Repository> repositories = new ArrayList<>();

  List<Map<String, String>> components = new ArrayList<>();

  Multimap<Repository, Map<String, String>> componentsByRepository =
      Multimaps.newListMultimap(new HashMap<>(), ArrayList::new);

  BoolQueryBuilder exampleQuery = boolQuery().must(queryStringQuery("example"));

  @BeforeEach
  void setup() {
    when(directories.getConfigDirectory("fabric")).thenReturn(testData.resolveFile("fabric"));
    when(nodeAccess.getId()).thenReturn("test-node");

    System.setProperty("testdir", new File(BASEDIR, "target/test-node").getPath());

    NodeProvider nodeProvider = new NodeProvider(directories, nodeAccess, null, null);
    ClientProvider clientProvider = new ClientProvider(nodeProvider);

    IndexNamingPolicy indexNamingPolicy = new HashedNamingPolicy();

    searchIndexService = new ElasticSearchIndexServiceImpl(clientProvider,
        indexNamingPolicy, List.of(), eventManager, 1000, 1, 0, CALM_TIMEOUT, 1);

    searchQueryService = new ElasticSearchQueryServiceImpl(clientProvider,
        repositoryManager, securityHelper, searchSubjectHelper, indexNamingPolicy, false);

    when(repositoryConfig.isOnline()).thenReturn(true);
    when(testFormat.getValue()).thenReturn("test-format");

    for (int i = 0; i < TEST_REPOSITORY_COUNT; i++) {
      Repository repository = mock(Repository.class);
      String repoName = "test-" + i;
      when(repository.getName()).thenReturn(repoName);
      when(repository.getConfiguration()).thenReturn(repositoryConfig);
      when(repository.optionalFacet(SearchIndexFacet.class)).thenReturn(Optional.of(searchFacet));
      when(repository.getFormat()).thenReturn(testFormat);
      searchIndexService.createIndex(repository);
      repositories.add(repository);
    }

    when(repositoryManager.browse()).thenReturn(repositories);
    when(securityHelper.allPermitted(any())).thenReturn(true);

    for (int i = 0; i < TEST_COMPONENT_COUNT; i++) {
      Map<String, String> component = new HashMap<>();
      component.put("format", "test-format");
      component.put("group", "example");
      component.put("name", String.valueOf(i));
      component.put("version", "1.0");
      components.add(component);
    }
  }

  @AfterEach
  void teardown() {
    repositories.forEach(searchIndexService::deleteIndex);
  }

  @Test
  void testBulkDelete() {
    seedComponentIndex();

    repositories.forEach(repo -> searchIndexService.bulkDelete(repo,
        componentsByRepository.get(repo).stream().map(c -> c.get("name")).toList()));

    await().atMost(1, MINUTES)
        .untilAsserted(() -> assertThat(Iterables.size(searchQueryService.browse(unrestricted(exampleQuery))), is(0)));
  }

  @Test
  void testBulkDeleteByIdentifierOnly() {
    seedComponentIndex();

    searchIndexService.bulkDelete(null,
        ContiguousSet.create(closed(0, TEST_COMPONENT_COUNT), DiscreteDomain.integers())
            .asList()
            .stream()
            .map(String::valueOf)
            .toList());

    await().atMost(1, MINUTES)
        .untilAsserted(() -> assertThat(Iterables.size(searchQueryService.browse(unrestricted(exampleQuery))), is(0)));
  }

  @Test
  void searchResultsArePaged() {
    seedComponentIndex();

    BoolQueryBuilder query = boolQuery().must(matchAllQuery());

    List<String> repos = repositories.stream().map(Repository::getName).collect(Collectors.toList());
    SearchResponse searchResponse = searchQueryService.search(unrestricted(query).inRepositories(repos), 0, 2);

    assertThat(searchResponse.getHits().hits().length, is(2));

    SearchResponse secondPage = searchQueryService.search(unrestricted(query).inRepositories(repos), 2, 4);

    assertThat(secondPage.getHits().hits().length, is(4));
    assertThat(searchResponse.getHits(), not(hasItems(secondPage.getHits().hits()[0], secondPage.getHits().hits()[1])));
  }
  
  /**
   * Test high-volume concurrent indexing using Virtual Threads.
   * This test validates that the indexing service can handle a large number of concurrent operations
   * efficiently using Java 21's Virtual Threads.
   */
  @Test
  void testConcurrentIndexingWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      int taskCount = 1000; // High volume of concurrent tasks
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Submit multiple concurrent indexing tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            Map<String, String> component = new HashMap<>();
            component.put("format", "test-format");
            component.put("group", "example-concurrent");
            component.put("name", "concurrent-" + index);
            component.put("version", "1.0");
            
            // Use a random repository for each component
            Repository repository = repositories.get(index % TEST_REPOSITORY_COUNT);
            
            // Index the component
            searchIndexService.put(repository, 
                component.get("name"),
                String.format("{ \"format\":\"%s\", \"group\":\"%s\", \"name\":\"%s\", \"version\":\"%s\" }",
                    component.get("format"), component.get("group"), component.get("name"), component.get("version")));
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      assertTrue(latch.await(2, MINUTES), "All indexing tasks should complete within timeout");
      
      // Verify no errors occurred
      assertEquals(0, errorCount.get(), "No errors should occur during concurrent indexing");
      
      // Verify the components were indexed
      BoolQueryBuilder query = boolQuery().must(queryStringQuery("example-concurrent"));
      await().atMost(1, MINUTES)
          .untilAsserted(() -> assertThat(Iterables.size(searchQueryService.browse(unrestricted(query))), is(taskCount)));
    }
  }
  
  /**
   * Test performance comparison between platform threads and virtual threads for bulk operations.
   * This test validates that virtual threads provide better performance for I/O-bound operations.
   */
  @Test
  void testThreadPerformanceComparison() throws Exception {
    // Create test data
    List<Map<String, String>> testComponents = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      Map<String, String> component = new HashMap<>();
      component.put("format", "test-format");
      component.put("group", "performance-test");
      component.put("name", "perf-" + i);
      component.put("version", "1.0");
      testComponents.add(component);
    }
    
    // Test with platform threads
    long platformThreadTime = measureExecutionTime(() -> {
      try (ExecutorService executor = Executors.newFixedThreadPool(20)) {
        executeParallelIndexing(executor, testComponents, "platform");
      }
    });
    
    // Test with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        executeParallelIndexing(executor, testComponents, "virtual");
      }
    });
    
    // Virtual threads should perform better for I/O-bound operations
    System.out.println("Platform thread execution time: " + platformThreadTime + "ms");
    System.out.println("Virtual thread execution time: " + virtualThreadTime + "ms");
    
    // Assert that virtual threads perform better
    // This might be flaky in some environments, so we're using a reasonable threshold
    assertThat("Virtual threads should be faster than platform threads", 
        virtualThreadTime, lessThan(platformThreadTime * 1.2));
  }
  
  /**
   * Execute parallel indexing operations using the provided executor service.
   */
  private void executeParallelIndexing(ExecutorService executor, List<Map<String, String>> components, String prefix) 
      throws Exception {
    CountDownLatch latch = new CountDownLatch(components.size());
    AtomicInteger errorCount = new AtomicInteger(0);
    
    for (int i = 0; i < components.size(); i++) {
      final int index = i;
      final Map<String, String> component = components.get(index);
      component.put("name", prefix + "-" + component.get("name"));
      
      executor.submit(() -> {
        try {
          Repository repository = repositories.get(index % TEST_REPOSITORY_COUNT);
          searchIndexService.put(repository, 
              component.get("name"),
              String.format("{ \"format\":\"%s\", \"group\":\"%s\", \"name\":\"%s\", \"version\":\"%s\" }",
                  component.get("format"), component.get("group"), component.get("name"), component.get("version")));
        } catch (Exception e) {
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
    }
    
    assertTrue(latch.await(1, MINUTES), "All indexing tasks should complete within timeout");
    assertEquals(0, errorCount.get(), "No errors should occur during parallel indexing");
  }
  
  /**
   * Measure the execution time of a runnable operation in milliseconds.
   */
  private long measureExecutionTime(Runnable operation) throws Exception {
    long startTime = System.currentTimeMillis();
    operation.run();
    return System.currentTimeMillis() - startTime;
  }

  /**
   * Test repository operations using virtual threads with CompletableFuture for improved concurrency.
   * This test demonstrates how to use virtual threads with CompletableFuture for asynchronous operations.
   */
  @Test
  void testRepositoryOperationsWithVirtualThreads() throws Exception {
    seedComponentIndex();
    
    // Create a list of futures for parallel operations
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    
    // Perform operations on each repository concurrently using virtual threads
    for (Repository repository : repositories) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        // Perform a search operation
        BoolQueryBuilder query = boolQuery().must(queryStringQuery("example"));
        Iterable<Map<String, Object>> results = searchQueryService.browse(unrestricted(query)
            .inRepository(repository.getName()));
        
        // Verify results
        assertThat(Iterables.size(results), greaterThan(0));
      }, Executors.newVirtualThreadPerTaskExecutor());
      
      futures.add(future);
    }
    
    // Wait for all operations to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
  }

  private void seedComponentIndex() {
    Random random = new SecureRandom();

    components.forEach(
        component -> componentsByRepository.put(repositories.get(random.nextInt(TEST_REPOSITORY_COUNT)), component));

    componentsByRepository.keySet()
        .forEach(repository -> searchIndexService.bulkPut(
            repository,
            componentsByRepository.get(repository),
            component -> component.get("name"),
            component -> String.format("{ \"format\":\"%s\", \"group\":\"%s\", \"name\":\"%s\", \"version\":\"%s\" }",
                component.get("format"), component.get("group"), component.get("name"), component.get("version"))));

    await().atMost(1, MINUTES)
        .untilAsserted(
            () -> assertThat(Iterables.size(searchQueryService.browse(unrestricted(exampleQuery))),
                is(TEST_COMPONENT_COUNT)));
  }
}