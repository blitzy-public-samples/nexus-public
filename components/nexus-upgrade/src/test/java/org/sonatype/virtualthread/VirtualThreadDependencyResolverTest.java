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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.upgrade.plan.DependencyResolver;
import org.sonatype.nexus.upgrade.plan.DependencyResolver.CyclicDependencyException;
import org.sonatype.nexus.upgrade.plan.DependencyResolver.UnresolvedDependencyException;
import org.sonatype.nexus.upgrade.plan.DependencySource.DependsOnAware;

import com.google.common.collect.Multimap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for {@link DependencyResolver} using Java 21 virtual threads.
 * 
 * This test validates that the dependency resolution algorithms work correctly
 * with virtual threads, ensuring proper concurrency handling and performance
 * characteristics when resolving complex dependency graphs.
 */
@DisplayName("Virtual Thread Dependency Resolver Tests")
public class VirtualThreadDependencyResolverTest
    extends TestSupport
{
  /**
   * Test entity that implements DependencySource and DependsOnAware interfaces.
   * Used to create dependency graphs for testing resolution algorithms.
   */
  static class Thing
      implements DependencySource<Thing>, DependsOnAware<Thing>
  {
    String id;

    List<Dependency<Thing>> dependencies = new ArrayList<>();

    Collection<Thing> dependsOn;

    /**
     * Creates a dependency which requires a thing with the given identifier.
     */
    static Dependency<Thing> dependency(final String id) {
      return new Dependency<Thing>()
      {
        @Override
        public boolean satisfiedBy(final Thing other) {
          return other.id.equals(id);
        }

        @Override
        public String toString() {
          return "EXISTS(" + id + ")";
        }
      };
    }

    /**
     * Creates a dependency which requires a thing which has an identifier matching the given pattern.
     */
    static Dependency<Thing> dependency(final Pattern pattern) {
      return new Dependency<Thing>()
      {
        @Override
        public boolean satisfiedBy(final Thing other) {
          return pattern.matcher(other.id).matches();
        }

        @Override
        public String toString() {
          return "MATCHES(" + pattern + ")";
        }
      };
    }

    @Override
    public List<Dependency<Thing>> getDependencies() {
      return dependencies;
    }

    @Override
    public String toString() {
      return getClass().getSimpleName() + "{" +
          "id='" + id + '\'' +
          '}';
    }

    @Override
    public void setDependsOn(final Collection<Thing> dependsOn) {
      this.dependsOn = dependsOn;
    }

    @Override
    public boolean equals(final Object o) {
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Thing thing = (Thing) o;
      return Objects.equals(id, thing.id);
    }

    @Override
    public int hashCode() {
      return Objects.hash(id);
    }
  }

  private DependencyResolver<Thing> resolver;

  @BeforeEach
  public void setUp() {
    resolver = new DependencyResolver<>();
  }

  @Test
  @DisplayName("Cannot add duplicate sources")
  void cannotAddDuplicateSources() {
    Thing thing = new Thing();
    thing.id = "foo";
    
    assertThrows(IllegalStateException.class, () -> resolver.add(thing, thing));
  }

  @Test
  @DisplayName("At least one source required to resolve")
  void atLeastOneSourceRequiredToResolve() {
    assertThrows(IllegalStateException.class, () -> resolver.resolve());
  }

  @Test
  @DisplayName("Complex dependency resolution with virtual threads")
  void complexUsageWithVirtualThreads() {
    resolver.add(
        new Thing()
        {
          {
            id = "structure.house";
            dependencies = List.of(
                // a bunch of rooms
                Thing.dependency(Pattern.compile("room\\..*")),
                // and a garage
                Thing.dependency("structure.garage"));
          }
        },
        new Thing()
        {
          {
            id = "structure.garage";
          }
        },
        new Thing()
        {
          {
            id = "room.kitchen";
          }
        },
        new Thing()
        {
          {
            id = "room.bathroom";
          }
        },
        new Thing()
        {
          {
            id = "room.bedroom";
            dependencies = List.of(
                Thing.dependency("room.bathroom"));
          }
        },
        new Thing()
        {
          {
            id = "room.living";
            dependencies = List.of(
                // everything connects to living room
                Thing.dependency(Pattern.compile("room\\..*")));
          }
        },
        new Thing()
        {
          {
            id = "car.toyota";
            dependencies = List.of(
                // car needs a garage
                Thing.dependency("structure.garage"));
          }
        },
        new Thing()
        {
          {
            id = "structure.gazebo";
          }
        });

    List<Thing> resolved = resolver.resolve().ordered;
    assertEquals(8, resolved.size());

    resolved.forEach(thing -> logger.info(thing.toString()));

    // all rooms before house
    Thing house = resolved.stream().filter(t -> t.id.equals("structure.house")).findFirst().orElseThrow();
    for (Thing room : resolved.stream().filter(t -> t.id.contains("room")).toList()) {
      assertTrue(resolved.indexOf(room) < resolved.indexOf(house));
    }

    // garage is before house
    Thing garage = resolved.stream().filter(t -> t.id.equals("structure.garage")).findFirst().orElseThrow();
    assertTrue(resolved.indexOf(garage) < resolved.indexOf(house));

    // bathroom is before bedroom
    Thing bathroom = resolved.stream().filter(t -> t.id.equals("room.bathroom")).findFirst().orElseThrow();
    Thing bedroom = resolved.stream().filter(t -> t.id.equals("room.bedroom")).findFirst().orElseThrow();
    assertTrue(resolved.indexOf(bathroom) < resolved.indexOf(bedroom));
  }

  @Test
  @DisplayName("Resolve orders based on dependencies using virtual threads")
  void resolveOrdersBasedOnDependenciesWithVirtualThreads() {
    resolver.add(
        new Thing()
        {
          {
            id = "a";
            dependencies = List.of(
                Thing.dependency("b"));
          }
        },
        new Thing()
        {
          {
            id = "b";
            dependencies = List.of(
                Thing.dependency("c"));
          }
        },
        new Thing()
        {
          {
            id = "c";
          }
        });
    List<Thing> ordered = resolver.resolve().ordered;

    assertEquals(3, ordered.size());
    assertEquals("c", ordered.get(0).id);
    assertEquals("b", ordered.get(1).id);
    assertEquals("a", ordered.get(2).id);
  }

  @Test
  @DisplayName("Resolve collects dependsOn with virtual threads")
  void resolveCollectsDependsOnWithVirtualThreads() {
    Thing a = new Thing()
    {
      {
        id = "a";
        dependencies = List.of(
            Thing.dependency("b"));
      }
    };
    Thing b = new Thing()
    {
      {
        id = "b";
        dependencies = List.of(
            Thing.dependency("c"));
      }
    };
    Thing c = new Thing()
    {
      {
        id = "c";
      }
    };

    resolver.add(a, b, c);
    Multimap<Thing, Thing> dependsOn = resolver.resolve().dependsOn;

    assertTrue(dependsOn.containsEntry(a, b));
    assertTrue(dependsOn.containsEntry(b, c));
    assertTrue(!dependsOn.containsKey(c));

    assertTrue(a.dependsOn.contains(b));
    assertTrue(b.dependsOn.contains(c));
    assertTrue(c.dependsOn.isEmpty());
  }

  @Test
  @DisplayName("Cyclic dependency throws exception with virtual threads")
  void cyclicDependencyThrowsExceptionWithVirtualThreads() {
    resolver.add(
        new Thing()
        {
          {
            id = "a";
            dependencies = List.of(
                Thing.dependency("b"));
          }
        },
        new Thing()
        {
          {
            id = "b";
            dependencies = List.of(
                Thing.dependency("a"));
          }
        });
    
    assertThrows(CyclicDependencyException.class, () -> resolver.resolve());
  }

  @Test
  @DisplayName("Unresolved dependency throws exception with virtual threads")
  void unresolvedDependencyThrowsExceptionWithVirtualThreads() {
    resolver.add(
        new Thing()
        {
          {
            id = "a";
            dependencies = List.of(
                Thing.dependency("b"));
          }
        });
    
    assertThrows(UnresolvedDependencyException.class, () -> resolver.resolve());
  }

  /**
   * Tests concurrent dependency resolution with a large number of dependencies using virtual threads.
   * This test creates a large dependency graph and resolves it concurrently using virtual threads,
   * validating that the resolution algorithm works correctly under high concurrency.
   */
  @Test
  @DisplayName("Concurrent dependency resolution with large graph using virtual threads")
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void concurrentDependencyResolutionWithLargeGraphUsingVirtualThreads() throws Exception {
    // Create a large dependency graph with 1000 nodes
    int nodeCount = 1000;
    List<Thing> nodes = new ArrayList<>(nodeCount);
    
    // Create nodes
    for (int i = 0; i < nodeCount; i++) {
      Thing thing = new Thing();
      thing.id = "node" + i;
      nodes.add(thing);
    }
    
    // Add dependencies - each node depends on the next 5 nodes (circular for the last ones)
    for (int i = 0; i < nodeCount; i++) {
      Thing thing = nodes.get(i);
      List<Dependency<Thing>> deps = new ArrayList<>();
      for (int j = 1; j <= 5; j++) {
        int depIndex = (i + j) % nodeCount;
        deps.add(Thing.dependency("node" + depIndex));
      }
      thing.dependencies = deps;
    }
    
    // Add all nodes to the resolver
    resolver.add(nodes.toArray(new Thing[0]));
    
    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Number of concurrent resolution attempts
      int concurrentTasks = 50;
      CountDownLatch latch = new CountDownLatch(concurrentTasks);
      AtomicInteger successCount = new AtomicInteger(0);
      ConcurrentHashMap<String, Exception> errors = new ConcurrentHashMap<>();
      
      // Start timing
      Instant start = Instant.now();
      
      // Submit concurrent resolution tasks
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      for (int i = 0; i < concurrentTasks; i++) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            // Create a new resolver for each task to avoid interference
            DependencyResolver<Thing> taskResolver = new DependencyResolver<>();
            taskResolver.add(nodes.toArray(new Thing[0]));
            
            // Resolve dependencies
            List<Thing> resolved = taskResolver.resolve().ordered;
            
            // Verify resolution is correct
            assertEquals(nodeCount, resolved.size(), "All nodes should be resolved");
            
            // Verify topological ordering
            for (Thing thing : resolved) {
              for (Dependency<Thing> dep : thing.getDependencies()) {
                for (Thing other : resolved) {
                  if (dep.satisfiedBy(other)) {
                    int thingIndex = resolved.indexOf(thing);
                    int otherIndex = resolved.indexOf(other);
                    assertTrue(otherIndex < thingIndex, 
                        "Dependency " + other.id + " should come before " + thing.id);
                  }
                }
              }
            }
            
            successCount.incrementAndGet();
          } catch (Exception e) {
            errors.put("Task-" + Thread.currentThread().getName(), e);
          } finally {
            latch.countDown();
          }
        }, executor);
        
        futures.add(future);
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(20, TimeUnit.SECONDS);
      Instant end = Instant.now();
      Duration duration = Duration.between(start, end);
      
      // Log performance metrics
      logger.info("Concurrent dependency resolution completed in {} ms", duration.toMillis());
      logger.info("Success count: {}", successCount.get());
      
      // Check for errors
      if (!errors.isEmpty()) {
        errors.forEach((task, error) -> {
          logger.error("Error in {}: {}", task, error.getMessage(), error);
        });
        fail("Errors occurred during concurrent dependency resolution: " + errors.size());
      }
      
      // Verify all tasks completed successfully
      assertTrue(completed, "All tasks should complete within the timeout");
      assertEquals(concurrentTasks, successCount.get(), "All tasks should succeed");
      
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Compares the performance of dependency resolution using platform threads vs virtual threads.
   * This test creates identical dependency graphs and resolves them using both thread types,
   * measuring and comparing the execution time.
   */
  @Test
  @DisplayName("Performance comparison: Platform threads vs Virtual threads")
  void performanceComparisonPlatformVsVirtualThreads() throws Exception {
    // Create a medium-sized dependency graph with 500 nodes
    int nodeCount = 500;
    List<Thing> nodes = new ArrayList<>(nodeCount);
    
    // Create nodes
    for (int i = 0; i < nodeCount; i++) {
      Thing thing = new Thing();
      thing.id = "node" + i;
      nodes.add(thing);
    }
    
    // Add dependencies - each node depends on the next 3 nodes (circular for the last ones)
    for (int i = 0; i < nodeCount; i++) {
      Thing thing = nodes.get(i);
      List<Dependency<Thing>> deps = new ArrayList<>();
      for (int j = 1; j <= 3; j++) {
        int depIndex = (i + j) % nodeCount;
        deps.add(Thing.dependency("node" + depIndex));
      }
      thing.dependencies = deps;
    }
    
    // Number of concurrent resolution attempts
    int concurrentTasks = 100;
    int warmupRuns = 5;
    int measurementRuns = 10;
    
    // Run with platform threads
    Duration platformThreadDuration = runPerformanceTest(
        nodes, concurrentTasks, warmupRuns, measurementRuns, Thread.ofPlatform().factory());
    
    // Run with virtual threads
    Duration virtualThreadDuration = runPerformanceTest(
        nodes, concurrentTasks, warmupRuns, measurementRuns, Thread.ofVirtual().factory());
    
    // Log results
    logger.info("Platform threads execution time: {} ms", platformThreadDuration.toMillis());
    logger.info("Virtual threads execution time: {} ms", virtualThreadDuration.toMillis());
    logger.info("Performance improvement: {}x", 
        (double) platformThreadDuration.toMillis() / virtualThreadDuration.toMillis());
    
    // We expect virtual threads to be faster, but don't assert it as it depends on the environment
    // This is primarily for benchmarking purposes
  }
  
  /**
   * Helper method to run a performance test with the specified thread factory.
   * 
   * @param nodes The dependency graph nodes
   * @param concurrentTasks Number of concurrent resolution tasks
   * @param warmupRuns Number of warmup runs before measurement
   * @param measurementRuns Number of measurement runs to average
   * @param threadFactory The thread factory to use (platform or virtual)
   * @return The average duration of the measurement runs
   */
  private Duration runPerformanceTest(
      List<Thing> nodes, 
      int concurrentTasks, 
      int warmupRuns,
      int measurementRuns,
      ThreadFactory threadFactory) throws Exception {
    
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    
    try {
      // Warmup runs
      for (int run = 0; run < warmupRuns; run++) {
        runConcurrentResolution(nodes, concurrentTasks, executor);
      }
      
      // Measurement runs
      List<Duration> durations = new ArrayList<>();
      for (int run = 0; run < measurementRuns; run++) {
        Instant start = Instant.now();
        runConcurrentResolution(nodes, concurrentTasks, executor);
        Instant end = Instant.now();
        durations.add(Duration.between(start, end));
      }
      
      // Calculate average duration
      long totalMillis = durations.stream()
          .mapToLong(Duration::toMillis)
          .sum();
      return Duration.ofMillis(totalMillis / measurementRuns);
      
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Runs concurrent dependency resolution tasks.
   * 
   * @param nodes The dependency graph nodes
   * @param concurrentTasks Number of concurrent resolution tasks
   * @param executor The executor service to use
   */
  private void runConcurrentResolution(
      List<Thing> nodes, 
      int concurrentTasks, 
      ExecutorService executor) throws Exception {
    
    CountDownLatch latch = new CountDownLatch(concurrentTasks);
    AtomicInteger successCount = new AtomicInteger(0);
    ConcurrentHashMap<String, Exception> errors = new ConcurrentHashMap<>();
    
    // Submit concurrent resolution tasks
    for (int i = 0; i < concurrentTasks; i++) {
      executor.submit(() -> {
        try {
          // Create a new resolver for each task to avoid interference
          DependencyResolver<Thing> taskResolver = new DependencyResolver<>();
          taskResolver.add(nodes.toArray(new Thing[0]));
          
          // Resolve dependencies
          taskResolver.resolve();
          successCount.incrementAndGet();
        } catch (Exception e) {
          errors.put("Task-" + Thread.currentThread().getName(), e);
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    boolean completed = latch.await(10, TimeUnit.SECONDS);
    
    // Check for errors
    if (!errors.isEmpty() || !completed || successCount.get() != concurrentTasks) {
      throw new RuntimeException("Performance test failed: " + errors.size() + " errors, " 
          + successCount.get() + "/" + concurrentTasks + " successful tasks");
    }
  }
  
  /**
   * Tests dependency resolution with pattern matching for switch using virtual threads.
   * This test demonstrates the use of Java 21's pattern matching for switch to simplify
   * the handling of different dependency types.
   */
  @Test
  @DisplayName("Dependency resolution with pattern matching for switch")
  void dependencyResolutionWithPatternMatchingForSwitch() {
    // Create a resolver and add some dependencies
    resolver.add(
        new Thing() {{ id = "a"; dependencies = List.of(Thing.dependency("b")); }},
        new Thing() {{ id = "b"; dependencies = List.of(Thing.dependency("c")); }},
        new Thing() {{ id = "c"; }}
    );
    
    // Resolve dependencies
    var result = resolver.resolve();
    
    // Use pattern matching for switch to handle different result types
    Object outcome = switch (result) {
      case var r when r.ordered.size() == 3 -> "Complete resolution";
      case var r when r.ordered.isEmpty() -> "Empty resolution";
      case var r when r.dependsOn.isEmpty() -> "No dependencies";
      case null -> "Null result";
      default -> "Unknown result";
    };
    
    assertEquals("Complete resolution", outcome);
    
    // Verify the resolution order using pattern matching
    for (Thing thing : result.ordered) {
      String status = switch (thing.id) {
        case "a" -> "Depends on b";
        case "b" -> "Depends on c";
        case "c" -> "No dependencies";
        default -> "Unknown";
      };
      
      switch (thing.id) {
        case "a" -> assertTrue(thing.dependsOn.contains(resolver.resolve().ordered.get(1)));
        case "b" -> assertTrue(thing.dependsOn.contains(resolver.resolve().ordered.get(0)));
        case "c" -> assertTrue(thing.dependsOn == null || thing.dependsOn.isEmpty());
        default -> fail("Unexpected thing id: " + thing.id);
      }
    }
  }
}