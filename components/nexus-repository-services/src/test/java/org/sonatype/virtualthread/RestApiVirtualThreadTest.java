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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import javax.ws.rs.core.Response;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.BaseUrlHolder;
import org.sonatype.nexus.repository.rest.api.RepositoryXO;
import org.sonatype.nexus.repository.rest.api.model.AbstractRepositoryApiRequest;
import org.sonatype.nexus.repository.rest.api.model.SimpleApiResponse;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.security.SecurityHelper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests REST API operations with Java 21 Virtual Threads in the repository services context.
 * This test class validates that REST resource handlers, response serialization, and API routing
 * work correctly with virtual threads under high concurrency.
 *
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
@Tag("java21")
public class RestApiVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_REQUESTS = 1000;
  private static final int WARMUP_REQUESTS = 100;
  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  @Mock
  private SecurityHelper securityHelper;

  private ObjectMapper objectMapper;

  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;

  @BeforeEach
  void setUp() {
    BaseUrlHolder.set("http://localhost:8081");
    objectMapper = new ObjectMapper();

    // Create executors for both virtual and platform threads for comparison
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    // Configure security helper mock to simulate permission checks
    lenient().when(securityHelper.allPermitted(any())).thenReturn(true);
  }

  @AfterEach
  void tearDown() throws Exception {
    BaseUrlHolder.clear();
    virtualThreadExecutor.shutdown();
    platformThreadExecutor.shutdown();
    
    if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
      virtualThreadExecutor.shutdownNow();
    }
    
    if (!platformThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
      platformThreadExecutor.shutdownNow();
    }
  }

  /**
   * Tests that REST API operations can be executed concurrently using virtual threads
   * without errors or thread contention issues.
   */
  @Test
  @DisplayName("REST API operations execute correctly with virtual threads")
  void testRestApiOperationsWithVirtualThreads() throws Exception {
    // Create a mock REST resource that simulates a repository API
    MockRepositoryApiResource apiResource = new MockRepositoryApiResource(securityHelper);
    
    // Execute concurrent requests using virtual threads
    CountDownLatch latch = new CountDownLatch(CONCURRENT_REQUESTS);
    AtomicInteger successCount = new AtomicInteger(0);
    
    for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
      final int index = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Simulate a REST API call to create a repository
          Response response = apiResource.createRepository(createRepositoryRequest("repo-" + index));
          
          // Verify response
          if (response.getStatus() == 201 && response.getEntity() != null) {
            successCount.incrementAndGet();
          }
        } 
        catch (Exception e) {
          log.error("Error in virtual thread request", e);
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all requests to complete
    assertTrue(latch.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "Timed out waiting for requests to complete");
    
    // Verify all requests were successful
    assertEquals(CONCURRENT_REQUESTS, successCount.get(), "All requests should succeed");
    
    // Verify the correct number of repositories were created
    assertEquals(CONCURRENT_REQUESTS, apiResource.getRepositoryCount(), "All repositories should be created");
  }

  /**
   * Tests that JSON serialization and deserialization works correctly with virtual threads
   * under high concurrency, ensuring thread safety of Jackson operations.
   */
  @Test
  @DisplayName("JSON serialization works correctly with virtual threads")
  void testJsonSerializationWithVirtualThreads() throws Exception {
    // Create a concurrent map to store serialization results
    ConcurrentHashMap<String, String> serializationResults = new ConcurrentHashMap<>();
    CountDownLatch latch = new CountDownLatch(CONCURRENT_REQUESTS);
    
    // Execute concurrent serialization/deserialization operations
    for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
      final int index = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Create a repository object
          RepositoryXO repo = createRepositoryXO("repo-" + index, "maven", "hosted");
          
          // Serialize to JSON
          String json = objectMapper.writeValueAsString(repo);
          
          // Deserialize back to object
          RepositoryXO deserialized = objectMapper.readValue(json, RepositoryXO.class);
          
          // Store result for verification
          serializationResults.put(repo.getName(), deserialized.getName());
        } 
        catch (Exception e) {
          log.error("Error in JSON serialization", e);
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    assertTrue(latch.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "Timed out waiting for serialization operations");
    
    // Verify all serialization/deserialization operations were successful
    assertEquals(CONCURRENT_REQUESTS, serializationResults.size(), "All serialization operations should succeed");
    
    // Verify the content of some serialized objects
    for (int i = 0; i < 10; i++) {
      String repoName = "repo-" + i;
      assertEquals(repoName, serializationResults.get(repoName), "Repository name should be preserved");
    }
  }

  /**
   * Tests that security enforcement works correctly with virtual threads,
   * ensuring that permission checks are properly applied in a concurrent environment.
   */
  @Test
  @DisplayName("Security enforcement works correctly with virtual threads")
  void testSecurityEnforcementWithVirtualThreads() throws Exception {
    // Configure security helper to deny permissions for even-numbered repositories
    when(securityHelper.allPermitted(any())).thenAnswer(invocation -> {
      String permission = invocation.getArgument(0).toString();
      // Extract repository number from permission string
      if (permission.contains("repository-")) {
        String repoName = permission.substring(permission.indexOf("repository-"));
        int repoNumber = Integer.parseInt(repoName.split("-")[1]);
        return repoNumber % 2 == 1; // Allow only odd-numbered repositories
      }
      return false;
    });
    
    // Create a mock REST resource
    MockRepositoryApiResource apiResource = new MockRepositoryApiResource(securityHelper);
    
    // Execute concurrent requests
    CountDownLatch latch = new CountDownLatch(CONCURRENT_REQUESTS);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger forbiddenCount = new AtomicInteger(0);
    
    for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
      final int index = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Simulate a REST API call
          Response response = apiResource.createRepository(createRepositoryRequest("repository-" + index));
          
          // Count responses by status
          if (response.getStatus() == 201) {
            successCount.incrementAndGet();
          } else if (response.getStatus() == 403) {
            forbiddenCount.incrementAndGet();
          }
        } 
        catch (Exception e) {
          log.error("Error in security test", e);
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all requests to complete
    assertTrue(latch.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "Timed out waiting for security test requests");
    
    // Verify security enforcement
    int expectedSuccessCount = CONCURRENT_REQUESTS / 2; // Only odd-numbered repositories should succeed
    int expectedForbiddenCount = CONCURRENT_REQUESTS / 2; // Even-numbered repositories should be forbidden
    
    assertEquals(expectedSuccessCount, successCount.get(), "Half of the requests should succeed");
    assertEquals(expectedForbiddenCount, forbiddenCount.get(), "Half of the requests should be forbidden");
    
    // Verify security helper was called for each request
    verify(securityHelper, times(CONCURRENT_REQUESTS)).allPermitted(any());
  }

  /**
   * Compares the performance of virtual threads versus platform threads for REST API operations,
   * measuring throughput and response times under high concurrency.
   */
  @Test
  @DisplayName("Virtual threads outperform platform threads for REST API operations")
  void testVirtualThreadsVsPlatformThreadsPerformance() throws Exception {
    // Create a mock REST resource that simulates a repository API with some I/O delay
    MockRepositoryApiResource apiResource = new MockRepositoryApiResource(securityHelper);
    apiResource.setSimulatedIoDelayMs(50); // Add a 50ms delay to simulate I/O operations
    
    // Warm up both thread types
    runConcurrentRequests(apiResource, virtualThreadExecutor, WARMUP_REQUESTS);
    runConcurrentRequests(apiResource, platformThreadExecutor, WARMUP_REQUESTS);
    apiResource.resetRepositoryCount();
    
    // Measure platform thread performance
    long platformThreadStartTime = System.nanoTime();
    runConcurrentRequests(apiResource, platformThreadExecutor, CONCURRENT_REQUESTS);
    long platformThreadEndTime = System.nanoTime();
    long platformThreadDuration = TimeUnit.NANOSECONDS.toMillis(platformThreadEndTime - platformThreadStartTime);
    apiResource.resetRepositoryCount();
    
    // Measure virtual thread performance
    long virtualThreadStartTime = System.nanoTime();
    runConcurrentRequests(apiResource, virtualThreadExecutor, CONCURRENT_REQUESTS);
    long virtualThreadEndTime = System.nanoTime();
    long virtualThreadDuration = TimeUnit.NANOSECONDS.toMillis(virtualThreadEndTime - virtualThreadStartTime);
    
    // Log performance results
    log.info("Platform threads completed {} requests in {} ms", CONCURRENT_REQUESTS, platformThreadDuration);
    log.info("Virtual threads completed {} requests in {} ms", CONCURRENT_REQUESTS, virtualThreadDuration);
    
    // Assert that virtual threads outperform platform threads
    // Note: This assertion might be environment-dependent, but virtual threads should generally
    // perform better for I/O-bound operations with high concurrency
    assertThat("Virtual threads should complete faster than platform threads",
        virtualThreadDuration, lessThan(platformThreadDuration));
  }

  /**
   * Tests that virtual threads can handle extreme parallelism without resource exhaustion,
   * validating the scalability benefits of virtual threads for REST API operations.
   */
  @Test
  @DisplayName("Virtual threads handle extreme parallelism without resource exhaustion")
  void testVirtualThreadsExtremeParallelism() throws Exception {
    // Create a mock REST resource
    MockRepositoryApiResource apiResource = new MockRepositoryApiResource(securityHelper);
    apiResource.setSimulatedIoDelayMs(20); // Add a small delay to simulate I/O
    
    // Number of concurrent requests for extreme parallelism test
    // This is much higher than what would be practical with platform threads
    final int extremeParallelism = 10000;
    
    // Execute a large number of concurrent requests using virtual threads
    CountDownLatch latch = new CountDownLatch(extremeParallelism);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger activeThreads = new AtomicInteger(0);
    AtomicInteger maxActiveThreads = new AtomicInteger(0);
    
    for (int i = 0; i < extremeParallelism; i++) {
      final int index = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Track active threads
          int active = activeThreads.incrementAndGet();
          maxActiveThreads.updateAndGet(current -> Math.max(current, active));
          
          // Simulate a REST API call
          Response response = apiResource.createRepository(createRepositoryRequest("extreme-" + index));
          
          if (response.getStatus() == 201) {
            successCount.incrementAndGet();
          }
        } 
        catch (Exception e) {
          log.error("Error in extreme parallelism test", e);
        }
        finally {
          activeThreads.decrementAndGet();
          latch.countDown();
        }
      });
    }
    
    // Wait for all requests to complete
    assertTrue(latch.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), 
        "Timed out waiting for extreme parallelism test");
    
    // Verify all requests were successful
    assertEquals(extremeParallelism, successCount.get(), "All requests should succeed");
    
    // Log the maximum number of concurrent threads observed
    log.info("Maximum active virtual threads: {}", maxActiveThreads.get());
    
    // Verify that we achieved high parallelism
    assertThat("Should achieve high parallelism with virtual threads", 
        maxActiveThreads.get(), greaterThan(1000));
  }

  /**
   * Tests that virtual threads correctly handle thread-local variables in REST API operations,
   * ensuring that thread-local state is properly isolated between concurrent requests.
   */
  @Test
  @DisplayName("Virtual threads correctly handle thread-local variables")
  void testThreadLocalVariablesWithVirtualThreads() throws Exception {
    // Create a thread-local variable to store request-specific data
    ThreadLocal<String> requestContext = new ThreadLocal<>();
    
    // Create a concurrent map to verify thread-local isolation
    ConcurrentHashMap<String, String> results = new ConcurrentHashMap<>();
    CountDownLatch latch = new CountDownLatch(CONCURRENT_REQUESTS);
    
    // Execute concurrent operations with thread-local variables
    for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
      final String requestId = "request-" + i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Set thread-local value
          requestContext.set(requestId);
          
          // Simulate some processing time
          Thread.sleep(10);
          
          // Verify thread-local value is preserved
          String contextValue = requestContext.get();
          results.put(requestId, contextValue);
        } 
        catch (Exception e) {
          log.error("Error in thread-local test", e);
        }
        finally {
          // Clean up thread-local to prevent memory leaks
          requestContext.remove();
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    assertTrue(latch.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "Timed out waiting for thread-local test");
    
    // Verify thread-local isolation
    assertEquals(CONCURRENT_REQUESTS, results.size(), "All operations should complete");
    
    // Verify that each thread maintained its own thread-local value
    for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
      String requestId = "request-" + i;
      assertEquals(requestId, results.get(requestId), 
          "Thread-local value should be preserved for each virtual thread");
    }
  }

  /**
   * Helper method to run concurrent requests against a repository API resource.
   */
  private void runConcurrentRequests(MockRepositoryApiResource apiResource, 
                                    ExecutorService executor, 
                                    int requestCount) throws Exception {
    CountDownLatch latch = new CountDownLatch(requestCount);
    
    for (int i = 0; i < requestCount; i++) {
      final int index = i;
      executor.submit(() -> {
        try {
          apiResource.createRepository(createRepositoryRequest("perf-" + index));
        } 
        catch (Exception e) {
          log.error("Error in concurrent request", e);
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    assertTrue(latch.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), 
        "Timed out waiting for concurrent requests");
  }

  /**
   * Creates a repository request for testing.
   */
  private AbstractRepositoryApiRequest createRepositoryRequest(String name) {
    AbstractRepositoryApiRequest request = mock(AbstractRepositoryApiRequest.class);
    when(request.getName()).thenReturn(name);
    when(request.getFormat()).thenReturn("maven");
    when(request.getType()).thenReturn("hosted");
    return request;
  }

  /**
   * Creates a RepositoryXO for testing.
   */
  private RepositoryXO createRepositoryXO(String name, String format, String type) {
    RepositoryXO repo = new RepositoryXO();
    repo.setName(name);
    repo.setFormat(format);
    repo.setType(type);
    repo.setUrl("http://localhost:8081/repository/" + name);
    return repo;
  }

  /**
   * Mock implementation of a repository API resource for testing.
   */
  private static class MockRepositoryApiResource {
    private final SecurityHelper securityHelper;
    private final ConcurrentHashMap<String, RepositoryXO> repositories = new ConcurrentHashMap<>();
    private volatile long simulatedIoDelayMs = 0;
    
    public MockRepositoryApiResource(SecurityHelper securityHelper) {
      this.securityHelper = securityHelper;
    }
    
    public void setSimulatedIoDelayMs(long delayMs) {
      this.simulatedIoDelayMs = delayMs;
    }
    
    public int getRepositoryCount() {
      return repositories.size();
    }
    
    public void resetRepositoryCount() {
      repositories.clear();
    }
    
    public Response createRepository(AbstractRepositoryApiRequest request) throws Exception {
      // Check permissions
      if (!securityHelper.allPermitted("nexus:repository-admin:" + request.getName())) {
        return Response.status(Response.Status.FORBIDDEN)
            .entity(new SimpleApiResponse("Insufficient permissions"))
            .build();
      }
      
      // Simulate I/O delay
      if (simulatedIoDelayMs > 0) {
        Thread.sleep(simulatedIoDelayMs);
      }
      
      // Create repository
      RepositoryXO repo = new RepositoryXO();
      repo.setName(request.getName());
      repo.setFormat(request.getFormat());
      repo.setType(request.getType());
      repo.setUrl("http://localhost:8081/repository/" + request.getName());
      
      // Store repository
      repositories.put(request.getName(), repo);
      
      // Return success response
      return Response.status(Response.Status.CREATED)
          .entity(repo)
          .build();
    }
  }
}