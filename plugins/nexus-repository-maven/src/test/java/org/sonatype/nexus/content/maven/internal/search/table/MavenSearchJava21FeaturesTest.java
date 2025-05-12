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
package org.sonatype.nexus.content.maven.internal.search.table;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;
import org.sonatype.nexus.repository.search.ComponentSearchResult;
import org.sonatype.nexus.repository.search.sql.SearchResult;
import org.sonatype.nexus.thread.Java21TestGroup;
import org.sonatype.nexus.thread.VirtualThreadTestGroup;

import org.junit.experimental.categories.Category;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.maven.internal.Attributes.P_BASE_VERSION;

/**
 * Test class that explicitly validates Java 21 language features as applied to Maven search logic.
 * <p>
 * This test suite ensures that the Maven search table logic is robust and compatible with new Java 21
 * constructs, and that any future regressions in Java 21 feature usage are detected.
 * </p>
 * <p>
 * The tests in this class validate the following Java 21 features:
 * <ul>
 *   <li>Virtual Threads - Lightweight threads managed by the JVM for high-concurrency operations</li>
 *   <li>Pattern Matching for instanceof - Simplified type checking and casting</li>
 *   <li>Record Patterns - Destructuring record types in pattern matching contexts</li>
 *   <li>String Templates - Simplified string interpolation with expressions</li>
 * </ul>
 * </p>
 * 
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
@Category({Java21TestGroup.class})
@DisplayName("Maven Search Java 21 Features Tests")
public class MavenSearchJava21FeaturesTest
    extends TestSupport
{
  @Mock
  private SearchResult searchResult;
  
  private MavenSqlSearchResultDecorator decorator;
  private MavenSearchComponentPathFilter pathFilter;
  private NestedAttributesMap attributes;

  @BeforeEach
  public void setup() {
    decorator = new MavenSqlSearchResultDecorator();
    pathFilter = new MavenSearchComponentPathFilter();
    attributes = new NestedAttributesMap();
    
    when(searchResult.attributes()).thenReturn(attributes);
  }

  /**
   * Tests the pattern matching for instanceof feature introduced in Java 21.
   * <p>
   * This test verifies that the MavenSqlSearchResultDecorator correctly uses pattern matching
   * for instanceof when processing format attributes.
   * </p>
   */
  @Test
  @DisplayName("Pattern Matching for instanceof in MavenSqlSearchResultDecorator")
  public void testPatternMatchingForInstanceOf() {
    // Setup test data
    ComponentSearchResult component = new ComponentSearchResult();
    component.setFormat(Maven2Format.NAME);
    
    // Create a map of Maven attributes with a base version
    Map<String, String> mavenAttributes = new HashMap<>();
    String baseVersion = "1.2.3";
    mavenAttributes.put(P_BASE_VERSION, baseVersion);
    
    // Add the Maven attributes to the nested attributes map
    attributes.set(Maven2Format.NAME, mavenAttributes);
    
    // Execute the method that uses pattern matching for instanceof
    decorator.updateComponent(component, searchResult);
    
    // Verify the result
    assertThat(component.getAnnotation(P_BASE_VERSION), is(baseVersion));
    
    // Implement our own pattern matching for instanceof to demonstrate the feature
    Object obj = mavenAttributes;
    String extractedVersion = null;
    
    // Using pattern matching for instanceof (Java 21 feature)
    if (obj instanceof Map<?, ?> map && map.containsKey(P_BASE_VERSION)) {
      extractedVersion = (String) map.get(P_BASE_VERSION);
    }
    
    assertThat(extractedVersion, is(baseVersion));
  }

  /**
   * Tests the record pattern matching feature introduced in Java 21.
   * <p>
   * This test demonstrates how record pattern matching can be used to simplify
   * the extraction of data from nested record structures in search results.
   * </p>
   */
  @Test
  @DisplayName("Record Pattern Matching for Maven Search Results")
  public void testRecordPatternMatching() {
    // Define records for Maven search data
    record MavenCoordinates(String groupId, String artifactId, String version) {}
    record MavenSearchEntry(String repositoryName, MavenCoordinates coordinates, String path) {}
    
    // Create test data using records
    MavenSearchEntry entry = new MavenSearchEntry(
        "maven-central",
        new MavenCoordinates("org.example", "test-artifact", "1.0.0"),
        "org/example/test-artifact/1.0.0/test-artifact-1.0.0.jar"
    );
    
    // Use record pattern matching to extract data (Java 21 feature)
    String result = switch(entry) {
      case MavenSearchEntry(var repo, MavenCoordinates(var group, var artifact, var version), var path) 
          when path.endsWith(".jar") -> 
          String.format("%s:%s:%s (JAR in %s)", group, artifact, version, repo);
      
      case MavenSearchEntry(var repo, MavenCoordinates(var group, var artifact, var version), var path) 
          when path.endsWith(".pom") -> 
          String.format("%s:%s:%s (POM in %s)", group, artifact, version, repo);
      
      default -> "Unknown format";
    };
    
    // Verify the result
    assertThat(result, is("org.example:test-artifact:1.0.0 (JAR in maven-central)"));
  }

  /**
   * Tests the string templates feature introduced in Java 21.
   * <p>
   * This test demonstrates how string templates can be used to format Maven search results
   * in a more readable and maintainable way.
   * </p>
   */
  @Test
  @DisplayName("String Templates for Maven Search Result Formatting")
  public void testStringTemplates() {
    // Setup test data
    String groupId = "org.example";
    String artifactId = "test-artifact";
    String version = "1.0.0";
    String repository = "maven-central";
    
    // Using Java 21 string templates (STR) for formatting
    String formatted = STR."""
        Maven Artifact:
        - GroupId: \{groupId}
        - ArtifactId: \{artifactId}
        - Version: \{version}
        - Repository: \{repository}
        - Coordinates: \{groupId}:\{artifactId}:\{version}
        """;
    
    // Verify the result
    assertThat(formatted, containsString("Maven Artifact:"));
    assertThat(formatted, containsString("GroupId: org.example"));
    assertThat(formatted, containsString("ArtifactId: test-artifact"));
    assertThat(formatted, containsString("Version: 1.0.0"));
    assertThat(formatted, containsString("Repository: maven-central"));
    assertThat(formatted, containsString("Coordinates: org.example:test-artifact:1.0.0"));
    
    // Test string template with expression processing
    record MavenArtifact(String groupId, String artifactId, String version) {}
    MavenArtifact artifact = new MavenArtifact(groupId, artifactId, version);
    
    String templateWithExpression = STR."""
        Maven coordinates: \{artifact.groupId()}:\{artifact.artifactId()}:\{artifact.version()}
        Repository: \{repository.toUpperCase()}
        """;
    
    assertThat(templateWithExpression, containsString("Maven coordinates: org.example:test-artifact:1.0.0"));
    assertThat(templateWithExpression, containsString("Repository: MAVEN-CENTRAL"));
  }

  /**
   * Tests the virtual threads feature introduced in Java 21 for concurrent search operations.
   * <p>
   * This test demonstrates how virtual threads can be used to perform many concurrent
   * search operations with minimal overhead compared to platform threads.
   * </p>
   */
  @Test
  @Category(VirtualThreadTestGroup.class)
  @DisplayName("Virtual Threads for Concurrent Maven Search Operations")
  public void testVirtualThreadsForConcurrentSearches() throws Exception {
    // Number of concurrent search operations to simulate
    int concurrentSearches = 1000;
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that uses virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      CountDownLatch latch = new CountDownLatch(concurrentSearches);
      AtomicInteger completedSearches = new AtomicInteger(0);
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      
      // Submit concurrent search tasks
      for (int i = 0; i < concurrentSearches; i++) {
        int searchIndex = i;
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            // Simulate a search operation
            simulateSearchOperation(searchIndex);
            completedSearches.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        }, executor);
        
        futures.add(future);
      }
      
      // Wait for all searches to complete
      boolean completed = latch.await(10, TimeUnit.SECONDS);
      
      // Verify results
      assertTrue(completed, "All search operations should complete within the timeout");
      assertEquals(concurrentSearches, completedSearches.get(), 
          "All search operations should complete successfully");
      
      // Verify that all futures completed without exceptions
      CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
      allFutures.join(); // This will throw an exception if any future failed
      
      // Verify thread characteristics
      Thread mainThread = Thread.currentThread();
      assertFalse(mainThread.isVirtual(), "Main test thread should be a platform thread");
      
      // Create and verify a virtual thread directly
      Thread virtualThread = Thread.ofVirtual().name("test-virtual-thread").start(() -> {
        // This code runs in a virtual thread
        Thread current = Thread.currentThread();
        assertTrue(current.isVirtual(), "Should be a virtual thread");
        assertEquals("test-virtual-thread", current.getName(), "Thread name should match");
      });
      
      // Wait for the virtual thread to complete
      virtualThread.join(1000);
      assertEquals(Thread.State.TERMINATED, virtualThread.getState(), "Virtual thread should terminate");
    }
  }
  
  /**
   * Tests the combination of pattern matching and virtual threads for Maven path filtering.
   * <p>
   * This test demonstrates how pattern matching can be combined with virtual threads
   * to efficiently filter Maven paths in parallel.
   * </p>
   */
  @Test
  @Category(VirtualThreadTestGroup.class)
  @DisplayName("Pattern Matching with Virtual Threads for Path Filtering")
  public void testPatternMatchingWithVirtualThreads() throws Exception {
    // Create a list of paths to filter
    List<String> paths = List.of(
        "org/example/artifact/1.0.0/artifact-1.0.0.jar",
        "org/example/artifact/1.0.0/artifact-1.0.0.pom",
        "org/example/artifact/1.0.0/artifact-1.0.0.jar.sha1",
        "org/example/artifact/1.0.0/artifact-1.0.0.pom.md5",
        "org/example/artifact/1.0.0/artifact-1.0.0.war",
        "org/example/artifact/1.0.0/artifact-1.0.0.zip"
    );
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that uses virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Process paths in parallel using virtual threads
      List<CompletableFuture<Boolean>> futures = paths.stream()
          .map(path -> CompletableFuture.supplyAsync(() -> {
            // Use the path filter to determine if the path should be filtered
            return pathFilter.shouldFilterPathExtension(path);
          }, executor))
          .toList();
      
      // Wait for all futures to complete
      CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
      allFutures.join();
      
      // Get the results
      List<Boolean> results = futures.stream()
          .map(CompletableFuture::join)
          .toList();
      
      // Verify the results
      assertEquals(6, results.size(), "Should have results for all paths");
      
      // Create a custom path filter using pattern matching
      Predicate<String> customPathFilter = path -> switch (path) {
        // Using pattern matching in switch expressions (Java 21 feature)
        case String p when p.endsWith(".jar") || p.endsWith(".pom") || 
                        p.endsWith(".war") || p.endsWith(".zip") -> false;
        case String p when p.endsWith(".sha1") || p.endsWith(".md5") -> true;
        default -> true;
      };
      
      // Verify specific results using pattern matching
      for (int i = 0; i < paths.size(); i++) {
        String path = paths.get(i);
        boolean shouldFilter = results.get(i);
        boolean customFilterResult = customPathFilter.test(path);
        
        // Verify our custom filter matches the actual implementation
        assertEquals(customFilterResult, shouldFilter, 
            "Custom filter should match actual implementation for: " + path);
        
        // Use pattern matching to check the results
        switch (path) {
          case String p when p.endsWith(".jar") || p.endsWith(".pom") || 
                          p.endsWith(".war") || p.endsWith(".zip") -> 
              assertFalse(shouldFilter, "Common Maven types should not be filtered: " + p);
              
          case String p when p.endsWith(".sha1") || p.endsWith(".md5") -> 
              assertTrue(shouldFilter, "Checksum files should be filtered: " + p);
              
          default -> 
              throw new IllegalArgumentException("Unexpected path: " + path);
        }
      }
    }
  }

  /**
   * Simulates a Maven search operation with the given index.
   * <p>
   * This method simulates the work done during a search operation, including
   * creating search results and processing them.
   * </p>
   * 
   * @param index the index of the search operation
   */
  private void simulateSearchOperation(int index) {
    // Create a component search result
    ComponentSearchResult component = new ComponentSearchResult();
    component.setId("component-" + index);
    component.setName("test-artifact-" + index);
    component.setGroup("org.example");
    component.setVersion("1.0." + index);
    component.setFormat(Maven2Format.NAME);
    
    // Simulate some processing time
    try {
      Thread.sleep(5); // Small delay to simulate work
    } 
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    
    // Verify the thread is a virtual thread
    Thread currentThread = Thread.currentThread();
    assertTrue(currentThread.isVirtual(), "Search operation should run on a virtual thread");
  }
}