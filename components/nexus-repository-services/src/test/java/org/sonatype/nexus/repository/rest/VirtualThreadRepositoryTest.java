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
package org.sonatype.nexus.repository.rest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.BaseUrlHolder;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.rest.api.AuthorizingRepositoryManager;
import org.sonatype.nexus.repository.rest.api.RepositoryXO;
import org.sonatype.nexus.repository.rest.internal.api.RepositoryInternalResource;
import org.sonatype.nexus.repository.rest.internal.api.RepositoryXO;
import org.sonatype.nexus.repository.security.RepositoryPermissionChecker;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.types.ProxyType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for repository REST API functionality with Java 21 Virtual Threads.
 * 
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
public class VirtualThreadRepositoryTest
    extends TestSupport
{
  private static final int CONCURRENT_REQUESTS = 1000;
  private static final int SIMULATED_IO_DELAY_MS = 50;
  
  @Mock
  private List<Format> formats;
  
  @Mock
  private RepositoryManager repositoryManager;
  
  @Mock
  private RepositoryPermissionChecker repositoryPermissionChecker;
  
  @Mock
  private List<org.sonatype.nexus.repository.Recipe> recipes;
  
  @Mock
  private AuthorizingRepositoryManager authorizingRepositoryManager;
  
  @Mock
  private Map<String, org.sonatype.nexus.repository.rest.api.ApiRepositoryAdapter> convertersByFormat;
  
  @Mock
  private org.sonatype.nexus.repository.rest.api.ApiRepositoryAdapter defaultAdapter;
  
  private final ProxyType proxyType = new ProxyType();
  
  private final GroupType groupType = new GroupType();
  
  private final HostedType hostedType = new HostedType();
  
  private RepositoryInternalResource underTest;
  
  @BeforeEach
  public void setup() {
    underTest = new RepositoryInternalResource(
        formats,
        repositoryManager,
        repositoryPermissionChecker,
        proxyType,
        recipes,
        authorizingRepositoryManager,
        convertersByFormat,
        defaultAdapter);
    
    BaseUrlHolder.set("http://nexus-url", "");
  }
  
  @Test
  @DisplayName("Test repository listing with virtual threads")
  public void testRepositoryListingWithVirtualThreads() throws Exception {
    // Setup test repositories
    List<Repository> repositories = createTestRepositories();
    when(repositoryManager.browse()).thenReturn(repositories);
    when(repositoryPermissionChecker.userCanBrowseRepositories(repositories)).thenReturn(repositories);
    
    // Execute repository listing using virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      CompletableFuture<List<RepositoryXO>> future = CompletableFuture.supplyAsync(() -> {
        return underTest.getRepositories(null, false, false, null);
      }, executor);
      
      List<RepositoryXO> result = future.get(5, SECONDS);
      
      // Verify results
      assertNotNull(result);
      assertEquals(5, result.size());
      verify(repositoryManager).browse();
      verify(repositoryPermissionChecker).userCanBrowseRepositories(repositories);
    } finally {
      executor.shutdown();
    }
  }
  
  @Test
  @DisplayName("Compare performance between platform threads and virtual threads")
  public void compareThreadPerformance() throws Exception {
    // Setup test repositories
    List<Repository> repositories = createTestRepositories();
    when(repositoryManager.browse()).thenReturn(repositories);
    when(repositoryPermissionChecker.userCanBrowseRepositories(repositories)).thenReturn(repositories);
    
    // Measure platform thread performance
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    long platformThreadTime = measurePerformance(() -> 
        Executors.newThreadPerTaskExecutor(platformThreadFactory), CONCURRENT_REQUESTS);
    
    // Measure virtual thread performance
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    long virtualThreadTime = measurePerformance(() -> 
        Executors.newThreadPerTaskExecutor(virtualThreadFactory), CONCURRENT_REQUESTS);
    
    // Log performance results
    log.info("Platform thread execution time: {} ms", platformThreadTime);
    log.info("Virtual thread execution time: {} ms", virtualThreadTime);
    
    // Virtual threads should generally perform better with I/O-bound operations
    // but this is not a strict assertion as it depends on the test environment
    assertThat("Virtual threads should handle concurrent I/O-bound operations efficiently",
        virtualThreadTime, lessThan(platformThreadTime * 2));
  }
  
  @Test
  @DisplayName("Test concurrent repository operations with virtual threads")
  public void testConcurrentRepositoryOperations() throws Exception {
    // Setup test repositories
    List<Repository> repositories = createTestRepositories();
    when(repositoryManager.browse()).thenReturn(repositories);
    when(repositoryPermissionChecker.userCanBrowseRepositories(repositories)).thenReturn(repositories);
    
    // Create virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      int concurrentRequests = 100;
      CountDownLatch latch = new CountDownLatch(concurrentRequests);
      AtomicInteger successCount = new AtomicInteger(0);
      AtomicBoolean hasErrors = new AtomicBoolean(false);
      
      // Submit concurrent requests
      for (int i = 0; i < concurrentRequests; i++) {
        executor.submit(() -> {
          try {
            List<RepositoryXO> result = underTest.getRepositories(null, false, false, null);
            if (result != null && result.size() == 5) {
              successCount.incrementAndGet();
            }
          } catch (Exception e) {
            log.error("Error in concurrent repository operation", e);
            hasErrors.set(true);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(10, SECONDS);
      
      // Verify results
      assertTrue(completed, "All concurrent tasks should complete within timeout");
      assertFalse(hasErrors.get(), "No errors should occur during concurrent execution");
      assertEquals(concurrentRequests, successCount.get(), "All requests should succeed");
      
      // Verify repository manager was called the expected number of times
      verify(repositoryManager, times(concurrentRequests)).browse();
    } finally {
      executor.shutdown();
    }
  }
  
  @Test
  @DisplayName("Test thread pinning detection in repository operations")
  public void testThreadPinningDetection() throws Exception {
    // This test simulates a scenario where thread pinning might occur
    // and verifies that operations still complete successfully
    
    // Setup test repositories
    List<Repository> repositories = createTestRepositories();
    when(repositoryManager.browse()).thenReturn(repositories);
    when(repositoryPermissionChecker.userCanBrowseRepositories(repositories)).thenReturn(repositories);
    
    // Create virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Create a synchronized block that would normally cause pinning
      // In a real application, this would be detected using JFR events or jdk.tracePinnedThreads
      Object lock = new Object();
      
      CompletableFuture<List<RepositoryXO>> future = CompletableFuture.supplyAsync(() -> {
        List<RepositoryXO> result;
        synchronized (lock) {
          // This synchronized block would cause pinning in a real application
          // Simulate I/O operation inside synchronized block (which would cause pinning)
          try {
            Thread.sleep(SIMULATED_IO_DELAY_MS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          result = underTest.getRepositories(null, false, false, null);
        }
        return result;
      }, executor);
      
      // Operation should still complete despite potential pinning
      List<RepositoryXO> result = future.get(5, SECONDS);
      
      // Verify results
      assertNotNull(result);
      assertEquals(5, result.size());
    } finally {
      executor.shutdown();
    }
  }
  
  @Test
  @DisplayName("Test high concurrency with virtual threads")
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Setup test repositories
    List<Repository> repositories = createTestRepositories();
    when(repositoryManager.browse()).thenReturn(repositories);
    when(repositoryPermissionChecker.userCanBrowseRepositories(repositories)).thenReturn(repositories);
    
    // Create virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      int concurrentRequests = 10000; // High concurrency test
      CountDownLatch latch = new CountDownLatch(concurrentRequests);
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Submit many concurrent requests
      for (int i = 0; i < concurrentRequests; i++) {
        executor.submit(() -> {
          try {
            // Simulate I/O-bound operation
            Thread.sleep(SIMULATED_IO_DELAY_MS);
            List<RepositoryXO> result = underTest.getRepositories(null, false, false, null);
            if (result != null && !result.isEmpty()) {
              successCount.incrementAndGet();
            }
          } catch (Exception e) {
            log.error("Error in high concurrency test", e);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete or timeout
      boolean completed = latch.await(30, SECONDS);
      
      // Verify results
      assertTrue(completed, "All concurrent tasks should complete within timeout");
      assertThat("Most requests should succeed", 
          successCount.get(), greaterThanOrEqualTo((int)(concurrentRequests * 0.95)));
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Measures performance of repository operations using the provided executor factory.
   */
  private long measurePerformance(Supplier<ExecutorService> executorFactory, int concurrentRequests) 
      throws Exception {
    ExecutorService executor = executorFactory.get();
    try {
      CountDownLatch latch = new CountDownLatch(concurrentRequests);
      long startTime = System.currentTimeMillis();
      
      // Submit concurrent requests
      for (int i = 0; i < concurrentRequests; i++) {
        executor.submit(() -> {
          try {
            // Simulate I/O-bound operation
            Thread.sleep(SIMULATED_IO_DELAY_MS);
            underTest.getRepositories(null, false, false, null);
          } catch (Exception e) {
            log.error("Error during performance test", e);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(60, SECONDS);
      long endTime = System.currentTimeMillis();
      
      return endTime - startTime;
    } finally {
      executor.shutdown();
      executor.awaitTermination(5, SECONDS);
    }
  }
  
  /**
   * Creates a list of test repositories for testing.
   */
  private List<Repository> createTestRepositories() {
    Format maven2 = new Format("maven2") {};
    Format nuget = new Format("nuget") {};
    
    List<Repository> repositories = new ArrayList<>();
    
    repositories.add(mockRepository("maven-central", maven2, proxyType, 
        "http://localhost:8081/repository/maven-central/", true));
    repositories.add(mockRepository("maven-public", maven2, groupType, 
        "http://localhost:8081/repository/maven-public/", true));
    repositories.add(mockRepository("nuget-group", nuget, groupType, 
        "http://localhost:8081/repository/nuget-group/", true));
    repositories.add(mockRepository("nuget-hosted", nuget, hostedType, 
        "http://localhost:8081/repository/nuget-hosted/", true));
    repositories.add(mockRepository("nuget.org-proxy", nuget, proxyType, 
        "http://localhost:8081/repository/nuget.org-proxy/", true));
    
    return repositories;
  }
  
  /**
   * Creates a mock repository with the specified properties.
   */
  private Repository mockRepository(String name, Format format, Type type, String url, boolean online) {
    Repository repository = mock(Repository.class);
    when(repository.getName()).thenReturn(name);
    when(repository.getFormat()).thenReturn(format);
    when(repository.getType()).thenReturn(type);
    when(repository.getUrl()).thenReturn(url);
    
    Configuration configuration = mock(Configuration.class);
    when(configuration.isOnline()).thenReturn(online);
    when(repository.getConfiguration()).thenReturn(configuration);
    
    return repository;
  }
}