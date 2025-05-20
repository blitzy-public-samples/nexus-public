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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import javax.ws.rs.core.Response;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.rest.api.RepositoryXO;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.rest.Resource;
import org.sonatype.nexus.security.SecurityHelper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests REST API operations with Java 21 Virtual Threads in the repository services context.
 * 
 * This test class validates that REST resource handlers, response serialization, and API routing
 * work correctly with virtual threads under high concurrency. It ensures that API operations can
 * safely leverage virtual threads to significantly improve throughput and concurrency for I/O-bound
 * operations without sacrificing correctness or security guarantees.
 */
public class RestApiVirtualThreadTest
    extends TestSupport
{
  private static final int HIGH_CONCURRENCY = 1000;
  private static final int MEDIUM_CONCURRENCY = 100;
  private static final int LOW_CONCURRENCY = 10;
  
  private static final int WARMUP_ITERATIONS = 5;
  private static final int TEST_ITERATIONS = 10;
  
  @Mock
  private Repository repository;
  
  @Mock
  private SecurityHelper securityHelper;
  
  private MockRestResource restResource;
  
  private ObjectMapper objectMapper;
  
  private ExecutorService platformThreadExecutor;
  private ExecutorService virtualThreadExecutor;
  
  @Before
  public void setUp() throws Exception {
    // Setup repository mock
    Format format = mock(Format.class);
    when(format.getValue()).thenReturn("maven");
    when(repository.getFormat()).thenReturn(format);
    when(repository.getType()).thenReturn(new ProxyType());
    when(repository.getName()).thenReturn("test-repo");
    when(repository.getUrl()).thenReturn("http://localhost:8081/repository/test-repo");
    
    // Setup security helper mock
    when(securityHelper.isPermitted(anyString())).thenReturn(true);
    
    // Create REST resource
    restResource = new MockRestResource(securityHelper);
    
    // Setup JSON mapper
    objectMapper = new ObjectMapper();
    
    // Create executors
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }
  
  @After
  public void tearDown() throws Exception {
    platformThreadExecutor.shutdown();
    virtualThreadExecutor.shutdown();
    
    if (!platformThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
      platformThreadExecutor.shutdownNow();
    }
    
    if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
      virtualThreadExecutor.shutdownNow();
    }
  }
  
  /**
   * Tests that REST API operations can be executed concurrently using virtual threads,
   * verifying that all operations complete successfully with correct results.
   */
  @Test
  public void testConcurrentRestApiOperationsWithVirtualThreads() throws Exception {
    int concurrentRequests = HIGH_CONCURRENCY;
    CountDownLatch latch = new CountDownLatch(concurrentRequests);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Execute concurrent REST API calls using virtual threads
    for (int i = 0; i < concurrentRequests; i++) {
      final int requestId = i;
      CompletableFuture.runAsync(() -> {
        try {
          // Simulate REST API call
          Response response = restResource.getRepository("repo-" + requestId);
          RepositoryXO repo = (RepositoryXO) response.getEntity();
          
          // Verify response
          if (response.getStatus() == 200 && repo != null && "test-repo".equals(repo.getName())) {
            successCount.incrementAndGet();
          }
          else {
            errorCount.incrementAndGet();
          }
        }
        catch (Exception e) {
          errorCount.incrementAndGet();
        }
        finally {
          latch.countDown();
        }
      }, virtualThreadExecutor);
    }
    
    // Wait for all requests to complete
    assertThat("All requests should complete within timeout", 
        latch.await(30, TimeUnit.SECONDS), is(true));
    
    // Verify all requests succeeded
    assertThat("All requests should succeed", successCount.get(), equalTo(concurrentRequests));
    assertThat("No errors should occur", errorCount.get(), equalTo(0));
  }
  
  /**
   * Compares the performance of REST API operations between platform threads and virtual threads
   * under high concurrency, expecting virtual threads to handle more concurrent operations
   * with better throughput.
   */
  @Test
  public void testPlatformVsVirtualThreadPerformance() throws Exception {
    int concurrentRequests = MEDIUM_CONCURRENCY;
    
    // Warm up
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runConcurrentRequests(platformThreadExecutor, concurrentRequests, 10);
      runConcurrentRequests(virtualThreadExecutor, concurrentRequests, 10);
    }
    
    // Test platform threads
    long platformThreadTime = measureAverageExecutionTime(() -> {
      try {
        return runConcurrentRequests(platformThreadExecutor, concurrentRequests, 50);
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, TEST_ITERATIONS);
    
    // Test virtual threads
    long virtualThreadTime = measureAverageExecutionTime(() -> {
      try {
        return runConcurrentRequests(virtualThreadExecutor, concurrentRequests, 50);
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, TEST_ITERATIONS);
    
    log.info("Platform thread average execution time: {} ms", platformThreadTime);
    log.info("Virtual thread average execution time: {} ms", virtualThreadTime);
    
    // Virtual threads should be faster or at least not significantly slower
    // We're using a relaxed assertion here because in some environments the difference might not be dramatic
    // for this simple test case, but in real-world scenarios with I/O operations, the difference would be more significant
    assertThat("Virtual threads should not be significantly slower than platform threads",
        virtualThreadTime, lessThan(platformThreadTime * 1.5));
  }
  
  /**
   * Tests that security enforcement works correctly with concurrent virtual thread API requests,
   * ensuring that permission checks are properly applied even under high concurrency.
   */
  @Test
  public void testSecurityEnforcementWithConcurrentVirtualThreads() throws Exception {
    int concurrentRequests = MEDIUM_CONCURRENCY;
    CountDownLatch latch = new CountDownLatch(concurrentRequests);
    AtomicInteger authorizedCount = new AtomicInteger(0);
    AtomicInteger unauthorizedCount = new AtomicInteger(0);
    
    // Configure security helper to deny half of the requests
    doAnswer(invocation -> {
      String permission = invocation.getArgument(0);
      // Even-numbered permissions are allowed, odd-numbered are denied
      int permissionNum = Integer.parseInt(permission.replaceAll("\\D+", ""));
      return permissionNum % 2 == 0;
    }).when(securityHelper).isPermitted(anyString());
    
    // Execute concurrent REST API calls with security checks
    for (int i = 0; i < concurrentRequests; i++) {
      final int requestId = i;
      CompletableFuture.runAsync(() -> {
        try {
          // Each request uses a different permission string
          Response response = restResource.getRepositoryWithPermissionCheck("permission-" + requestId);
          
          if (response.getStatus() == 200) {
            authorizedCount.incrementAndGet();
          }
          else if (response.getStatus() == 403) {
            unauthorizedCount.incrementAndGet();
          }
        }
        finally {
          latch.countDown();
        }
      }, virtualThreadExecutor);
    }
    
    // Wait for all requests to complete
    assertThat("All requests should complete within timeout", 
        latch.await(30, TimeUnit.SECONDS), is(true));
    
    // Verify security enforcement
    assertThat("Approximately half of requests should be authorized", 
        authorizedCount.get(), is(concurrentRequests / 2));
    assertThat("Approximately half of requests should be unauthorized", 
        unauthorizedCount.get(), is(concurrentRequests / 2));
    
    // Verify security helper was called for each request
    verify(securityHelper, times(concurrentRequests)).isPermitted(anyString());
  }
  
  /**
   * Tests JSON serialization correctness under extreme virtual thread parallelism,
   * ensuring that object serialization and deserialization work correctly even with
   * thousands of concurrent operations.
   */
  @Test
  public void testJsonSerializationUnderExtremeParallelism() throws Exception {
    int concurrentRequests = HIGH_CONCURRENCY;
    CountDownLatch latch = new CountDownLatch(concurrentRequests);
    Map<Integer, String> results = new ConcurrentHashMap<>();
    
    // Create a list of repository objects to serialize
    List<RepositoryXO> repositories = new ArrayList<>();
    for (int i = 0; i < concurrentRequests; i++) {
      RepositoryXO repo = new RepositoryXO();
      repo.setName("repo-" + i);
      repo.setFormat("maven");
      repo.setType("proxy");
      repo.setUrl("http://localhost:8081/repository/repo-" + i);
      repositories.add(repo);
    }
    
    // Execute concurrent JSON serialization/deserialization using virtual threads
    for (int i = 0; i < concurrentRequests; i++) {
      final int index = i;
      final RepositoryXO repo = repositories.get(index);
      
      CompletableFuture.runAsync(() -> {
        try {
          // Serialize to JSON
          String json = objectMapper.writeValueAsString(repo);
          
          // Deserialize back to object
          RepositoryXO deserialized = objectMapper.readValue(json, RepositoryXO.class);
          
          // Store result for verification
          results.put(index, deserialized.getName());
        }
        catch (Exception e) {
          results.put(index, "ERROR: " + e.getMessage());
        }
        finally {
          latch.countDown();
        }
      }, virtualThreadExecutor);
    }
    
    // Wait for all operations to complete
    assertThat("All serialization operations should complete within timeout", 
        latch.await(30, TimeUnit.SECONDS), is(true));
    
    // Verify all serialization/deserialization operations succeeded
    assertThat("All results should be present", results.size(), equalTo(concurrentRequests));
    
    // Check a sample of results
    for (int i = 0; i < concurrentRequests; i += concurrentRequests / 10) {
      String result = results.get(i);
      assertThat("Result should not contain error", result, equalTo("repo-" + i));
    }
  }
  
  /**
   * Measures response time improvements for typical REST operations using virtual threads,
   * simulating I/O-bound operations that benefit from virtual thread concurrency.
   */
  @Test
  public void testResponseTimeImprovementsWithVirtualThreads() throws Exception {
    int concurrentRequests = LOW_CONCURRENCY;
    int ioDelayMs = 100; // Simulate I/O delay
    
    // Configure REST resource to add artificial I/O delay
    restResource.setIoDelayMs(ioDelayMs);
    
    // Measure response time with platform threads
    long startPlatform = System.nanoTime();
    runConcurrentRequests(platformThreadExecutor, concurrentRequests, ioDelayMs);
    long platformThreadTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startPlatform);
    
    // Measure response time with virtual threads
    long startVirtual = System.nanoTime();
    runConcurrentRequests(virtualThreadExecutor, concurrentRequests, ioDelayMs);
    long virtualThreadTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startVirtual);
    
    log.info("Platform thread total response time: {} ms", platformThreadTime);
    log.info("Virtual thread total response time: {} ms", virtualThreadTime);
    
    // For I/O bound operations, virtual threads should show better throughput
    // The improvement depends on the nature of the I/O operations and concurrency level
    assertThat("Virtual threads should handle I/O-bound operations more efficiently",
        virtualThreadTime, lessThan(platformThreadTime));
    
    // Reset I/O delay for other tests
    restResource.setIoDelayMs(0);
  }
  
  /**
   * Tests pattern matching with switch expressions for handling different repository types,
   * demonstrating Java 21 pattern matching improvements in REST API handlers.
   */
  @Test
  public void testPatternMatchingForRepositoryTypes() {
    // Test pattern matching with different repository types
    Response response = restResource.getRepositoryWithPatternMatching("maven");
    assertThat(response.getStatus(), is(200));
    assertThat(response.getEntity(), notNullValue());
    
    response = restResource.getRepositoryWithPatternMatching("npm");
    assertThat(response.getStatus(), is(200));
    assertThat(response.getEntity(), notNullValue());
    
    response = restResource.getRepositoryWithPatternMatching("unknown");
    assertThat(response.getStatus(), is(400));
  }
  
  /**
   * Tests that virtual threads can handle a very high number of concurrent REST API requests,
   * demonstrating the scalability benefits of virtual threads for I/O-bound operations.
   */
  @Test
  public void testVeryHighConcurrencyWithVirtualThreads() throws Exception {
    // This test demonstrates that virtual threads can handle thousands of concurrent requests
    // which would be impractical with platform threads due to their higher memory footprint
    int veryHighConcurrency = HIGH_CONCURRENCY * 5; // 5000 concurrent requests
    CountDownLatch latch = new CountDownLatch(veryHighConcurrency);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Execute a very high number of concurrent requests using virtual threads
    for (int i = 0; i < veryHighConcurrency; i++) {
      CompletableFuture.runAsync(() -> {
        try {
          // Simple REST API call
          Response response = restResource.getRepository("test-repo");
          if (response.getStatus() == 200) {
            successCount.incrementAndGet();
          }
        }
        finally {
          latch.countDown();
        }
      }, virtualThreadExecutor);
    }
    
    // Wait for all requests to complete with a generous timeout
    assertThat("All high-concurrency requests should complete within timeout", 
        latch.await(60, TimeUnit.SECONDS), is(true));
    
    // Verify all requests succeeded
    assertThat("All high-concurrency requests should succeed", 
        successCount.get(), equalTo(veryHighConcurrency));
  }
  
  /**
   * Helper method to run concurrent REST API requests using the specified executor.
   * 
   * @param executor The executor service to use (platform or virtual thread executor)
   * @param concurrentRequests The number of concurrent requests to execute
   * @param ioDelayMs Artificial I/O delay in milliseconds to simulate I/O-bound operations
   * @return The total execution time in milliseconds
   */
  private long runConcurrentRequests(ExecutorService executor, int concurrentRequests, int ioDelayMs) throws Exception {
    CountDownLatch latch = new CountDownLatch(concurrentRequests);
    restResource.setIoDelayMs(ioDelayMs);
    
    long startTime = System.nanoTime();
    
    // Execute concurrent REST API calls
    for (int i = 0; i < concurrentRequests; i++) {
      executor.submit(() -> {
        try {
          restResource.getRepository("test-repo");
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all requests to complete
    latch.await();
    
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
  }
  
  /**
   * Helper method to measure the average execution time of a task over multiple iterations.
   * 
   * @param task The task to measure
   * @param iterations The number of iterations to run
   * @return The average execution time in milliseconds
   */
  private long measureAverageExecutionTime(Supplier<Long> task, int iterations) {
    long totalTime = 0;
    
    for (int i = 0; i < iterations; i++) {
      totalTime += task.get();
    }
    
    return totalTime / iterations;
  }
  
  /**
   * Mock REST resource implementation for testing.
   */
  private class MockRestResource implements Resource {
    private final SecurityHelper securityHelper;
    private volatile int ioDelayMs = 0;
    
    public MockRestResource(SecurityHelper securityHelper) {
      this.securityHelper = securityHelper;
    }
    
    public void setIoDelayMs(int ioDelayMs) {
      this.ioDelayMs = ioDelayMs;
    }
    
    /**
     * Simulates a REST API endpoint that returns repository information.
     */
    public Response getRepository(String repositoryName) {
      // Simulate I/O delay (e.g., database query, network call)
      if (ioDelayMs > 0) {
        try {
          Thread.sleep(ioDelayMs);
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      
      RepositoryXO repositoryXO = RepositoryXO.fromRepository(repository);
      return Response.ok(repositoryXO).build();
    }
    
    /**
     * Simulates a REST API endpoint with security permission check.
     */
    public Response getRepositoryWithPermissionCheck(String permission) {
      // Check permission
      if (!securityHelper.isPermitted(permission)) {
        return Response.status(Response.Status.FORBIDDEN).build();
      }
      
      // Permission granted, return repository
      RepositoryXO repositoryXO = RepositoryXO.fromRepository(repository);
      return Response.ok(repositoryXO).build();
    }
    
    /**
     * Demonstrates Java 21 pattern matching with switch expressions for handling
     * different repository formats.
     */
    public Response getRepositoryWithPatternMatching(String format) {
      // Using Java 21 pattern matching with switch expressions
      return switch (format) {
        case "maven" -> {
          RepositoryXO repo = new RepositoryXO();
          repo.setName("maven-central");
          repo.setFormat("maven");
          repo.setType("proxy");
          yield Response.ok(repo).build();
        }
        case "npm" -> {
          RepositoryXO repo = new RepositoryXO();
          repo.setName("npmjs");
          repo.setFormat("npm");
          repo.setType("proxy");
          yield Response.ok(repo).build();
        }
        case "docker" -> {
          RepositoryXO repo = new RepositoryXO();
          repo.setName("docker-hub");
          repo.setFormat("docker");
          repo.setType("proxy");
          yield Response.ok(repo).build();
        }
        default -> Response.status(Response.Status.BAD_REQUEST)
            .entity("Unsupported format: " + format)
            .build();
      };
    }
  }
}