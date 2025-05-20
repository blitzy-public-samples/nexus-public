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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.sonatype.goodies.testsupport.TestSupport;
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
import org.sonatype.nexus.testcommon.Java21TestGroup;
import org.sonatype.nexus.virtualthread.VirtualThreadTestSupport;

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
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.google.common.collect.Range.closed;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.search.query.RepositoryQueryBuilder.unrestricted;

@ExtendWith(MockitoExtension.class)
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
public class ElasticSearchIndexServiceImplIT
    extends TestSupport
{
  static final String BASEDIR = new File(System.getProperty("basedir", "")).getAbsolutePath();

  private static final int CALM_TIMEOUT = 3000;

  private static final int TEST_REPOSITORY_COUNT = 10;

  private static final int TEST_COMPONENT_COUNT = 3000;
  
  private static final int HIGH_CONCURRENCY_COUNT = 1000;

  private TestDataRule testData = new TestDataRule(Paths.get(BASEDIR, "src/test/it-resources").toFile());

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
  
  List<ExecutorService> executorServices = new ArrayList<>();

  @BeforeEach
  public void setup() {
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
  public void teardown() {
    repositories.forEach(searchIndexService::deleteIndex);
    executorServices.forEach(ExecutorService::shutdownNow);
    executorServices.clear();
  }

  @Test
  public void testBulkDelete() {
    seedComponentIndex();

    repositories.forEach(repo -> searchIndexService.bulkDelete(repo,
        componentsByRepository.get(repo).stream().map(c -> c.get("name")).toList()));

    await().atMost(1, MINUTES)
        .untilAsserted(() -> assertThat(Iterables.size(searchQueryService.browse(unrestricted(exampleQuery))), is(0)));
  }

  @Test
  public void testBulkDeleteByIdentifierOnly() {
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
  public void searchResultsArePaged() {
    seedComponentIndex();

    BoolQueryBuilder query = boolQuery().must(matchAllQuery());

    List<String> repos = repositories.stream().map(Repository::getName).collect(Collectors.toList());
    SearchResponse searchResponse = searchQueryService.search(unrestricted(query).inRepositories(repos), 0, 2);

    assertThat(searchResponse.getHits().hits().length, is(2));

    SearchResponse secondPage = searchQueryService.search(unrestricted(query).inRepositories(repos), 2, 4);

    assertThat(secondPage.getHits().hits().length, is(4));
    assertThat(searchResponse.getHits(), not(hasItems(secondPage.getHits().hits()[0], secondPage.getHits().hits()[1])));
  }
  
  @Test
  public void testConcurrentIndexingWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    executorServices.add(executor);
    
    // Prepare components for concurrent indexing
    int componentCount = 1000;
    List<Map<String, String>> testComponents = new ArrayList<>();
    for (int i = 0; i < componentCount; i++) {
      Map<String, String> component = new HashMap<>();
      component.put("format", "test-format");
      component.put("group", "concurrent-example");
      component.put("name", "concurrent-" + i);
      component.put("version", "1.0");
      testComponents.add(component);
    }
    
    // Distribute components across repositories
    Random random = new SecureRandom();
    Multimap<Repository, Map<String, String>> testComponentsByRepo =
        Multimaps.newListMultimap(new HashMap<>(), ArrayList::new);
    testComponents.forEach(
        component -> testComponentsByRepo.put(repositories.get(random.nextInt(TEST_REPOSITORY_COUNT)), component));
    
    // Concurrently index components using virtual threads
    CountDownLatch latch = new CountDownLatch(testComponentsByRepo.keySet().size());
    AtomicInteger errorCount = new AtomicInteger(0);
    
    testComponentsByRepo.keySet().forEach(repository -> {
      executor.submit(() -> {
        try {
          searchIndexService.bulkPut(
              repository,
              testComponentsByRepo.get(repository),
              component -> component.get("name"),
              component -> String.format("{ \"format\":\"%s\", \"group\":\"%s\", \"name\":\"%s\", \"version\":\"%s\" }",
                  component.get("format"), component.get("group"), component.get("name"), component.get("version")));
        } catch (Exception e) {
          log.error("Error during concurrent indexing", e);
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
    });
    
    // Wait for all indexing tasks to complete
    boolean completed = latch.await(1, MINUTES);
    assertThat("All indexing tasks should complete within timeout", completed, is(true));
    assertThat("No errors should occur during concurrent indexing", errorCount.get(), is(0));
    
    // Verify all components were indexed
    BoolQueryBuilder concurrentQuery = boolQuery().must(queryStringQuery("concurrent-example"));
    await().atMost(1, MINUTES)
        .untilAsserted(() -> assertThat(Iterables.size(searchQueryService.browse(unrestricted(concurrentQuery))),
            is(componentCount)));
  }
  
  @Test
  public void testVirtualThreadScalingForBulkOperations() throws Exception {
    // Create virtual and platform thread executors for comparison
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    
    ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    ExecutorService platformExecutor = Executors.newFixedThreadPool(100, platformThreadFactory);
    executorServices.add(virtualExecutor);
    executorServices.add(platformExecutor);
    
    // Prepare test data
    seedComponentIndex();
    
    // Measure platform thread performance for bulk delete
    long platformStartTime = System.nanoTime();
    CountDownLatch platformLatch = new CountDownLatch(repositories.size());
    AtomicInteger platformErrorCount = new AtomicInteger(0);
    
    repositories.forEach(repo -> {
      platformExecutor.submit(() -> {
        try {
          searchIndexService.bulkDelete(repo,
              componentsByRepository.get(repo).stream().map(c -> c.get("name")).toList());
        } catch (Exception e) {
          log.error("Error during platform thread bulk delete", e);
          platformErrorCount.incrementAndGet();
        } finally {
          platformLatch.countDown();
        }
      });
    });
    
    platformLatch.await(1, MINUTES);
    long platformDuration = System.nanoTime() - platformStartTime;
    
    // Recreate the index for virtual thread test
    seedComponentIndex();
    
    // Measure virtual thread performance for bulk delete
    long virtualStartTime = System.nanoTime();
    CountDownLatch virtualLatch = new CountDownLatch(repositories.size());
    AtomicInteger virtualErrorCount = new AtomicInteger(0);
    
    repositories.forEach(repo -> {
      virtualExecutor.submit(() -> {
        try {
          searchIndexService.bulkDelete(repo,
              componentsByRepository.get(repo).stream().map(c -> c.get("name")).toList());
        } catch (Exception e) {
          log.error("Error during virtual thread bulk delete", e);
          virtualErrorCount.incrementAndGet();
        } finally {
          virtualLatch.countDown();
        }
      });
    });
    
    virtualLatch.await(1, MINUTES);
    long virtualDuration = System.nanoTime() - virtualStartTime;
    
    // Verify no errors occurred
    assertEquals(0, platformErrorCount.get(), "No errors should occur during platform thread bulk delete");
    assertEquals(0, virtualErrorCount.get(), "No errors should occur during virtual thread bulk delete");
    
    // Log performance comparison
    log.info("Bulk delete performance comparison:");
    log.info("Platform threads: {} ms", platformDuration / 1_000_000);
    log.info("Virtual threads: {} ms", virtualDuration / 1_000_000);
    log.info("Improvement ratio: {}", (double) platformDuration / virtualDuration);
    
    // Virtual threads should perform better or at least not significantly worse
    assertThat("Virtual threads should not be significantly slower than platform threads",
        virtualDuration, lessThan(platformDuration * 1.2));
  }
  
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    executorServices.add(executor);
    
    // Prepare a large number of small components for high concurrency test
    List<Map<String, String>> highConcurrencyComponents = new ArrayList<>();
    for (int i = 0; i < HIGH_CONCURRENCY_COUNT; i++) {
      Map<String, String> component = new HashMap<>();
      component.put("format", "test-format");
      component.put("group", "high-concurrency");
      component.put("name", "hc-" + i);
      component.put("version", "1.0");
      highConcurrencyComponents.add(component);
    }
    
    // Use a single repository for simplicity
    Repository testRepo = repositories.get(0);
    
    // Submit many small indexing tasks using virtual threads
    CountDownLatch latch = new CountDownLatch(HIGH_CONCURRENCY_COUNT);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    for (Map<String, String> component : highConcurrencyComponents) {
      executor.submit(() -> {
        try {
          // Index a single component
          searchIndexService.bulkPut(
              testRepo,
              List.of(component),
              c -> c.get("name"),
              c -> String.format("{ \"format\":\"%s\", \"group\":\"%s\", \"name\":\"%s\", \"version\":\"%s\" }",
                  c.get("format"), c.get("group"), c.get("name"), c.get("version")));
        } catch (Exception e) {
          log.error("Error during high concurrency indexing", e);
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    boolean completed = latch.await(2, MINUTES);
    assertThat("All high concurrency tasks should complete within timeout", completed, is(true));
    assertThat("No errors should occur during high concurrency indexing", errorCount.get(), is(0));
    
    // Verify all components were indexed
    BoolQueryBuilder highConcurrencyQuery = boolQuery().must(queryStringQuery("high-concurrency"));
    await().atMost(1, MINUTES)
        .untilAsserted(() -> {
          int size = Iterables.size(searchQueryService.browse(unrestricted(highConcurrencyQuery)));
          assertThat("All high concurrency components should be indexed", size, is(HIGH_CONCURRENCY_COUNT));
        });
  }
  
  @Test
  public void testBulkOperationPerformanceComparison() throws Exception {
    // Create components for performance test
    List<Map<String, String>> perfTestComponents = new ArrayList<>();
    for (int i = 0; i < 5000; i++) {
      Map<String, String> component = new HashMap<>();
      component.put("format", "test-format");
      component.put("group", "performance-test");
      component.put("name", "perf-" + i);
      component.put("version", "1.0");
      perfTestComponents.add(component);
    }
    
    Repository testRepo = repositories.get(0);
    
    // Measure platform thread performance
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    ExecutorService platformExecutor = Executors.newFixedThreadPool(100, platformThreadFactory);
    executorServices.add(platformExecutor);
    
    long platformStartTime = System.nanoTime();
    
    // Split components into batches for platform threads
    int batchSize = 100;
    int batchCount = (perfTestComponents.size() + batchSize - 1) / batchSize;
    CountDownLatch platformLatch = new CountDownLatch(batchCount);
    
    for (int i = 0; i < batchCount; i++) {
      int fromIndex = i * batchSize;
      int toIndex = Math.min(fromIndex + batchSize, perfTestComponents.size());
      List<Map<String, String>> batch = perfTestComponents.subList(fromIndex, toIndex);
      
      platformExecutor.submit(() -> {
        try {
          searchIndexService.bulkPut(
              testRepo,
              batch,
              c -> c.get("name"),
              c -> String.format("{ \"format\":\"%s\", \"group\":\"%s\", \"name\":\"%s\", \"version\":\"%s\" }",
                  c.get("format"), c.get("group"), c.get("name"), c.get("version")));
        } finally {
          platformLatch.countDown();
        }
      });
    }
    
    platformLatch.await(2, MINUTES);
    long platformDuration = System.nanoTime() - platformStartTime;
    
    // Delete all components for next test
    searchIndexService.bulkDelete(testRepo, perfTestComponents.stream().map(c -> c.get("name")).toList());
    
    await().atMost(30, SECONDS)
        .untilAsserted(() -> {
          BoolQueryBuilder perfQuery = boolQuery().must(queryStringQuery("performance-test"));
          assertThat(Iterables.size(searchQueryService.browse(unrestricted(perfQuery))), is(0));
        });
    
    // Measure virtual thread performance
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    executorServices.add(virtualExecutor);
    
    long virtualStartTime = System.nanoTime();
    
    // Use smaller batches for virtual threads to maximize concurrency
    batchSize = 10;
    batchCount = (perfTestComponents.size() + batchSize - 1) / batchSize;
    CountDownLatch virtualLatch = new CountDownLatch(batchCount);
    
    for (int i = 0; i < batchCount; i++) {
      int fromIndex = i * batchSize;
      int toIndex = Math.min(fromIndex + batchSize, perfTestComponents.size());
      List<Map<String, String>> batch = perfTestComponents.subList(fromIndex, toIndex);
      
      virtualExecutor.submit(() -> {
        try {
          searchIndexService.bulkPut(
              testRepo,
              batch,
              c -> c.get("name"),
              c -> String.format("{ \"format\":\"%s\", \"group\":\"%s\", \"name\":\"%s\", \"version\":\"%s\" }",
                  c.get("format"), c.get("group"), c.get("name"), c.get("version")));
        } finally {
          virtualLatch.countDown();
        }
      });
    }
    
    virtualLatch.await(2, MINUTES);
    long virtualDuration = System.nanoTime() - virtualStartTime;
    
    // Verify all components were indexed
    BoolQueryBuilder perfQuery = boolQuery().must(queryStringQuery("performance-test"));
    await().atMost(30, SECONDS)
        .untilAsserted(() -> assertThat(Iterables.size(searchQueryService.browse(unrestricted(perfQuery))),
            is(perfTestComponents.size())));
    
    // Log performance comparison
    double platformMs = platformDuration / 1_000_000.0;
    double virtualMs = virtualDuration / 1_000_000.0;
    double improvementRatio = platformMs / virtualMs;
    
    log.info("Bulk operation performance comparison:");
    log.info("Platform threads: {} ms", platformMs);
    log.info("Virtual threads: {} ms", virtualMs);
    log.info("Improvement ratio: {}", improvementRatio);
    
    // Virtual threads should show performance improvement
    assertThat("Virtual threads should provide performance improvement for bulk operations",
        improvementRatio, greaterThan(1.0));
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
