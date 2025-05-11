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
package org.sonatype.nexus.content.maven.internal.store;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.common.entity.EntityUUID;
import org.sonatype.nexus.content.maven.store.GAV;
import org.sonatype.nexus.content.maven.store.Maven2AssetBlobDAO;
import org.sonatype.nexus.content.maven.store.Maven2AssetDAO;
import org.sonatype.nexus.content.maven.store.Maven2ComponentDAO;
import org.sonatype.nexus.content.maven.store.Maven2ComponentData;
import org.sonatype.nexus.content.maven.store.Maven2ContentRepositoryDAO;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.store.BlobRefTypeHandler;
import org.sonatype.nexus.repository.content.store.ContentRepositoryDAO;
import org.sonatype.nexus.repository.content.store.ContentRepositoryData;
import org.sonatype.nexus.repository.search.normalize.VersionNumberExpander;
import org.sonatype.nexus.testdb.DataSessionRule;

import com.google.common.collect.ImmutableMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.experimental.categories.Category;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;
import static org.sonatype.nexus.datastore.mybatis.CombUUID.combUUID;

/**
 * Benchmark and feature validation tests for {@link Maven2ComponentDAO} using Java 21 features.
 * <p>
 * This test suite validates:
 * <ul>
 *   <li>Virtual thread performance compared to platform threads for DAO operations</li>
 *   <li>Pattern matching with Maven2ComponentData</li>
 *   <li>Record patterns usage with GAV data</li>
 *   <li>String templates for logging and SQL-like operations</li>
 * </ul>
 * <p>
 * These tests ensure that the Maven2ComponentDAO works correctly with Java 21 features
 * and provides performance benefits when using virtual threads for concurrent operations.
 */
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
public class Maven2ComponentDAOVirtualThreadBenchmarkTest
    extends TestSupport
{
  private static final int COMPONENT_COUNT = 100;
  private static final int CONCURRENT_OPERATIONS = 1000;
  private static final int WARMUP_ITERATIONS = 5;
  private static final int BENCHMARK_ITERATIONS = 10;
  
  private ContentRepositoryData contentRepository;

  private int repositoryId;

  @RegisterExtension
  public DataSessionRule sessionRule = new DataSessionRule(DEFAULT_DATASTORE_NAME)
      .handle(new BlobRefTypeHandler())
      .access(Maven2ContentRepositoryDAO.class)
      .access(Maven2ComponentDAO.class)
      .access(Maven2AssetBlobDAO.class)
      .access(Maven2AssetDAO.class);

  @BeforeEach
  public void setupContent() {
    contentRepository = new ContentRepositoryData();
    contentRepository.setConfigRepositoryId(new EntityUUID(combUUID()));
    contentRepository.setAttributes(newAttributes("repository"));

    createContentRepository(contentRepository);

    repositoryId = contentRepository.contentRepositoryId();

    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      Maven2ComponentDAO dao = session.access(Maven2ComponentDAO.class);
      
      // Generate a larger dataset for benchmarking
      for (int i = 1; i <= COMPONENT_COUNT; i++) {
        if (i % 4 == 0) {
          // Create a release version
          generateComponent(i, "1." + (i / 4) + ".0", "1." + (i / 4) + ".0", dao);
        } else {
          // Create snapshots with different timestamps
          String baseVersion = "1." + (i / 4) + ".0-SNAPSHOT";
          String timestamp = String.format("%02d%02d%02d.%06d-%02d", 
              2023, (i % 12) + 1, (i % 28) + 1, i * 1000, i % 100);
          generateComponent(i, "1." + (i / 4) + ".0-" + timestamp, baseVersion, dao);
        }
      }
      
      session.getTransaction().commit();
    }
  }

  /**
   * Record to store benchmark results for comparison.
   * 
   * <p>This uses Java 21's record feature for immutable data classes.</p>
   */
  record BenchmarkResult(String name, long operationCount, Duration duration, long errorCount) {
    /**
     * Calculate operations per second.
     */
    public double operationsPerSecond() {
      return operationCount / (duration.toNanos() / 1_000_000_000.0);
    }
    
    /**
     * Format the result as a string using Java 21 string templates.
     */
    @Override
    public String toString() {
      return STR."""
          Benchmark: \{name}
          Operations: \{operationCount}
          Duration: \{duration.toMillis()} ms
          Errors: \{errorCount}
          Throughput: \{String.format("%.2f", operationsPerSecond())} ops/sec
          """;
    }
  }

  /**
   * Benchmark comparing platform threads vs virtual threads for concurrent component reads.
   * 
   * <p>This test demonstrates the performance benefits of Java 21 virtual threads
   * when performing many concurrent I/O-bound operations.</p>
   */
  @Test
  @DisplayName("Compare platform threads vs virtual threads for concurrent component reads")
  public void compareThreadModelsForComponentReads() throws Exception {
    // Run benchmarks
    BenchmarkResult platformThreadResult = benchmarkComponentReads(Thread.ofPlatform().factory());
    BenchmarkResult virtualThreadResult = benchmarkComponentReads(Thread.ofVirtual().factory());
    
    // Log results
    log.info("Platform Thread Result:\n{}\n", platformThreadResult);
    log.info("Virtual Thread Result:\n{}\n", virtualThreadResult);
    
    // Verify no errors occurred
    assertThat(platformThreadResult.errorCount(), is(0L));
    assertThat(virtualThreadResult.errorCount(), is(0L));
    
    // Virtual threads should handle more concurrent operations efficiently
    // Note: In some environments, the difference might not be significant for this simple test
    assertThat(virtualThreadResult.operationsPerSecond(), greaterThanOrEqualTo(platformThreadResult.operationsPerSecond() * 0.9));
  }

  /**
   * Benchmark comparing platform threads vs virtual threads for concurrent GAV queries.
   * 
   * <p>This test demonstrates the performance benefits of Java 21 virtual threads
   * when performing database queries with many concurrent operations.</p>
   */
  @Test
  @DisplayName("Compare platform threads vs virtual threads for concurrent GAV queries")
  public void compareThreadModelsForGavQueries() throws Exception {
    // Run benchmarks
    BenchmarkResult platformThreadResult = benchmarkGavQueries(Thread.ofPlatform().factory());
    BenchmarkResult virtualThreadResult = benchmarkGavQueries(Thread.ofVirtual().factory());
    
    // Log results
    log.info("Platform Thread Result:\n{}\n", platformThreadResult);
    log.info("Virtual Thread Result:\n{}\n", virtualThreadResult);
    
    // Verify no errors occurred
    assertThat(platformThreadResult.errorCount(), is(0L));
    assertThat(virtualThreadResult.errorCount(), is(0L));
    
    // Virtual threads should handle more concurrent operations efficiently
    assertThat(virtualThreadResult.operationsPerSecond(), greaterThanOrEqualTo(platformThreadResult.operationsPerSecond() * 0.9));
  }

  /**
   * Test using Java 21 pattern matching with instanceof for Maven2ComponentData.
   * 
   * <p>This test demonstrates how pattern matching simplifies code when working with
   * Maven2ComponentData objects.</p>
   */
  @Test
  @DisplayName("Test pattern matching with instanceof for Maven2ComponentData")
  public void testPatternMatchingWithInstanceOf() {
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      Maven2ComponentDAO dao = session.access(Maven2ComponentDAO.class);
      Optional<Component> componentOpt = dao.readComponent(1);
      
      assertTrue(componentOpt.isPresent(), "Component should be present");
      Component component = componentOpt.get();
      
      // Using Java 21 pattern matching with instanceof
      if (component instanceof Maven2ComponentData mavenComponent) {
        // With pattern matching, we can directly use the mavenComponent variable
        assertNotNull(mavenComponent.getBaseVersion());
        assertEquals(mavenComponent.getBaseVersion(), mavenComponent.version());
      } else {
        // This should not happen
        assertTrue(false, "Component should be an instance of Maven2ComponentData");
      }
    }
  }

  /**
   * Test using Java 21 pattern matching with switch expressions for Maven2ComponentData.
   * 
   * <p>This test demonstrates how pattern matching in switch expressions simplifies
   * code when working with different component types.</p>
   */
  @Test
  @DisplayName("Test pattern matching with switch expressions for Maven2ComponentData")
  public void testPatternMatchingWithSwitchExpressions() {
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      Maven2ComponentDAO dao = session.access(Maven2ComponentDAO.class);
      Optional<Component> componentOpt = dao.readComponent(1);
      
      assertTrue(componentOpt.isPresent(), "Component should be present");
      Component component = componentOpt.get();
      
      // Using Java 21 pattern matching with switch expressions
      String result = switch (component) {
        case Maven2ComponentData mavenComponent when mavenComponent.getBaseVersion().contains("-SNAPSHOT") ->
          "Snapshot: " + mavenComponent.getBaseVersion();
        case Maven2ComponentData mavenComponent ->
          "Release: " + mavenComponent.getBaseVersion();
        default ->
          "Unknown component type";
      };
      
      assertTrue(result.startsWith("Release: ") || result.startsWith("Snapshot: "),
          "Result should identify component as release or snapshot");
      log.info("Component classification: {}", result);
    }
  }

  /**
   * Test using Java 21 record patterns with GAV data.
   * 
   * <p>This test demonstrates how record patterns simplify code when working with
   * record types like our BenchmarkResult.</p>
   */
  @Test
  @DisplayName("Test record patterns with GAV data")
  public void testRecordPatterns() {
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      Maven2ComponentDAO dao = session.access(Maven2ComponentDAO.class);
      Set<GAV> gavs = dao.findGavsWithSnaphots(repositoryId, 2);
      
      assertFalse(gavs.isEmpty(), "Should find GAVs with snapshots");
      
      // Create a list of benchmark results for demonstration
      List<BenchmarkResult> results = new ArrayList<>();
      results.add(new BenchmarkResult("Test1", 1000, Duration.ofMillis(500), 0));
      results.add(new BenchmarkResult("Test2", 2000, Duration.ofMillis(600), 5));
      
      // Using Java 21 record patterns in enhanced for loop
      double totalOps = 0;
      for (BenchmarkResult(String name, long ops, Duration duration, long errors) : results) {
        // We can directly use the destructured fields
        totalOps += ops;
        log.info("Benchmark {} completed {} operations in {} ms with {} errors",
            name, ops, duration.toMillis(), errors);
      }
      
      assertEquals(3000, totalOps, "Total operations should be 3000");
      
      // Using record patterns with if statement
      if (!results.isEmpty() && results.get(0) instanceof BenchmarkResult(String name, long ops, var duration, var errors)) {
        assertEquals("Test1", name);
        assertEquals(1000, ops);
        assertEquals(0, errors);
      }
    }
  }

  /**
   * Test using Java 21 string templates for logging and SQL-like operations.
   * 
   * <p>This test demonstrates how string templates simplify string formatting
   * and make code more readable.</p>
   */
  @Test
  @DisplayName("Test string templates for logging and SQL-like operations")
  public void testStringTemplates() {
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      Maven2ComponentDAO dao = session.access(Maven2ComponentDAO.class);
      Set<String> baseVersions = dao.getBaseVersions(repositoryId, "group", "artifact");
      
      assertFalse(baseVersions.isEmpty(), "Should find base versions");
      
      // Using Java 21 string templates for logging
      String versionsStr = String.join(", ", baseVersions);
      String logMessage = STR."Found \{baseVersions.size()} base versions: \{versionsStr}";
      log.info(logMessage);
      
      // Using string templates for SQL-like operations
      String whereClause = baseVersions.stream()
          .map(v -> STR."'\{v}'")
          .collect(Collectors.joining(", "));
      
      String sqlQuery = STR."""
          SELECT * FROM components 
          WHERE repository_id = \{repositoryId}
          AND base_version IN (\{whereClause})
          ORDER BY version DESC
          """;
      
      log.info("Generated SQL query:\n{}", sqlQuery);
      
      // Verify the SQL query contains the correct repository ID
      assertTrue(sqlQuery.contains(STR."repository_id = \{repositoryId}"), 
          "SQL query should contain the repository ID");
    }
  }

  /**
   * Test concurrent component operations using virtual threads.
   * 
   * <p>This test validates that the Maven2ComponentDAO works correctly when accessed
   * concurrently from many virtual threads.</p>
   */
  @Test
  @DisplayName("Test concurrent component operations using virtual threads")
  public void testConcurrentComponentOperationsWithVirtualThreads() throws Exception {
    int concurrentThreads = 1000;
    CountDownLatch latch = new CountDownLatch(concurrentThreads);
    AtomicInteger errorCount = new AtomicInteger(0);
    ConcurrentHashMap<Integer, Boolean> results = new ConcurrentHashMap<>();
    
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to read components concurrently
      for (int i = 0; i < concurrentThreads; i++) {
        final int componentId = (i % COMPONENT_COUNT) + 1;
        executor.submit(() -> {
          try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
            Maven2ComponentDAO dao = session.access(Maven2ComponentDAO.class);
            Optional<Component> component = dao.readComponent(componentId);
            results.put(componentId, component.isPresent());
          } catch (Exception e) {
            log.error("Error in virtual thread operation", e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), 
          "All operations should complete within timeout");
      
      // Verify results
      assertThat("No errors should occur", errorCount.get(), is(0));
      assertThat("All components should be found", 
          results.values().stream().filter(v -> v).count(), 
          is((long) results.size()));
    }
  }

  /**
   * Benchmark component read operations using the specified thread factory.
   *
   * @param threadFactory the thread factory to use (platform or virtual)
   * @return benchmark results
   */
  private BenchmarkResult benchmarkComponentReads(ThreadFactory threadFactory) throws Exception {
    // Warm up
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runConcurrentComponentReads(threadFactory, CONCURRENT_OPERATIONS / 10);
    }
    
    // Benchmark
    long totalOperations = 0;
    long totalErrors = 0;
    long startTime = System.nanoTime();
    
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      BenchmarkIteration result = runConcurrentComponentReads(threadFactory, CONCURRENT_OPERATIONS);
      totalOperations += result.operations;
      totalErrors += result.errors;
    }
    
    long endTime = System.nanoTime();
    Duration duration = Duration.ofNanos(endTime - startTime);
    
    String name = threadFactory instanceof Thread.Builder.OfVirtual ? "Virtual Threads" : "Platform Threads";
    return new BenchmarkResult(name, totalOperations, duration, totalErrors);
  }

  /**
   * Benchmark GAV query operations using the specified thread factory.
   *
   * @param threadFactory the thread factory to use (platform or virtual)
   * @return benchmark results
   */
  private BenchmarkResult benchmarkGavQueries(ThreadFactory threadFactory) throws Exception {
    // Warm up
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runConcurrentGavQueries(threadFactory, CONCURRENT_OPERATIONS / 10);
    }
    
    // Benchmark
    long totalOperations = 0;
    long totalErrors = 0;
    long startTime = System.nanoTime();
    
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      BenchmarkIteration result = runConcurrentGavQueries(threadFactory, CONCURRENT_OPERATIONS);
      totalOperations += result.operations;
      totalErrors += result.errors;
    }
    
    long endTime = System.nanoTime();
    Duration duration = Duration.ofNanos(endTime - startTime);
    
    String name = threadFactory instanceof Thread.Builder.OfVirtual ? "Virtual Threads" : "Platform Threads";
    return new BenchmarkResult(name, totalOperations, duration, totalErrors);
  }

  /**
   * Simple record to hold benchmark iteration results.
   */
  record BenchmarkIteration(long operations, long errors) {}

  /**
   * Run concurrent component read operations using the specified thread factory.
   *
   * @param threadFactory the thread factory to use
   * @param operationCount the number of operations to perform
   * @return benchmark iteration results
   */
  private BenchmarkIteration runConcurrentComponentReads(
      ThreadFactory threadFactory, int operationCount) throws Exception {
    CountDownLatch latch = new CountDownLatch(operationCount);
    LongAdder errorCount = new LongAdder();
    
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory)) {
      // Submit tasks to read components concurrently
      for (int i = 0; i < operationCount; i++) {
        final int componentId = (i % COMPONENT_COUNT) + 1;
        executor.submit(() -> {
          try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
            Maven2ComponentDAO dao = session.access(Maven2ComponentDAO.class);
            dao.readComponent(componentId);
          } catch (Exception e) {
            errorCount.increment();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      latch.await(30, TimeUnit.SECONDS);
    }
    
    return new BenchmarkIteration(operationCount, errorCount.sum());
  }

  /**
   * Run concurrent GAV query operations using the specified thread factory.
   *
   * @param threadFactory the thread factory to use
   * @param operationCount the number of operations to perform
   * @return benchmark iteration results
   */
  private BenchmarkIteration runConcurrentGavQueries(
      ThreadFactory threadFactory, int operationCount) throws Exception {
    CountDownLatch latch = new CountDownLatch(operationCount);
    LongAdder errorCount = new LongAdder();
    
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory)) {
      // Submit tasks to query GAVs concurrently
      for (int i = 0; i < operationCount; i++) {
        final int minRetained = i % 5;
        executor.submit(() -> {
          try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
            Maven2ComponentDAO dao = session.access(Maven2ComponentDAO.class);
            dao.findGavsWithSnaphots(repositoryId, minRetained);
          } catch (Exception e) {
            errorCount.increment();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      latch.await(30, TimeUnit.SECONDS);
    }
    
    return new BenchmarkIteration(operationCount, errorCount.sum());
  }

  private void createContentRepository(final ContentRepositoryData contentRepository) {
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      ContentRepositoryDAO dao = session.access(Maven2ContentRepositoryDAO.class);
      dao.createContentRepository(contentRepository);
      session.getTransaction().commit();
    }
  }

  protected NestedAttributesMap newAttributes(final String key) {
    return new NestedAttributesMap("attributes", new HashMap<>(ImmutableMap.of(key, "test-value")));
  }

  private void generateComponent(final int componentId,
                                 final String version,
                                 final String baseVersion,
                                 final Maven2ComponentDAO dao)
  {
    Maven2ComponentData component = new Maven2ComponentData();
    component.setComponentId(componentId);
    component.setNamespace("group");
    component.setName("artifact");
    component.setVersion(version);
    component.setNormalizedVersion(VersionNumberExpander.expand(version));
    component.setBaseVersion(baseVersion);
    component.setAttributes(newAttributes("component"));
    component.setKind("aKind");
    component.setRepositoryId(repositoryId);
    dao.createComponent(component, false);
    dao.updateBaseVersion(component);
  }
}