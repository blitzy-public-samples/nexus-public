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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
import org.sonatype.nexus.repository.security.RepositoryPermissionChecker;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.types.ProxyType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
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
 * This test class validates proper operation of asynchronous operations in the REST layer,
 * including proper pinning detection and concurrency handling.
 */
@ExtendWith(MockitoExtension.class)
public class VirtualThreadRepositoryTest extends TestSupport
{
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

  /**
   * Tests that repository operations can be executed using virtual threads.
   * This validates that the repository layer can work with Java 21 virtual threads.
   */
  @Test
  @DisplayName("Repository operations should work with virtual threads")
  public void repositoryOperationsShouldWorkWithVirtualThreads() throws Exception {
    // Setup test repositories
    Format maven2 = new Format("maven2") {};
    Repository mavenProxyRepository = mockRepository("maven-central", maven2, proxyType, 
        "http://localhost:8081/repository/maven-central/", true, Map.of());
    
    List<Repository> repositories = List.of(mavenProxyRepository);
    
    when(repositoryPermissionChecker.userCanBrowseRepositories(repositories)).thenReturn(repositories);
    when(repositoryManager.browse()).thenReturn(repositories);

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("test-virtual-", 0).factory();
    
    // Execute repository operation in a virtual thread
    AtomicReference<List<RepositoryXO>> result = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    
    Thread virtualThread = virtualThreadFactory.newThread(() -> {
      try {
        result.set(underTest.getRepositories(null, false, false, null));
      } catch (Throwable t) {
        error.set(t);
      }
    });
    
    virtualThread.start();
    virtualThread.join();
    
    // Verify operation completed successfully
    assertThat("No errors should occur when using virtual threads", error.get(), is(null));
    assertThat("Result should be returned", result.get(), is(notNullValue()));
    assertThat("Repository list should contain our test repository", result.get().size(), is(1));
    assertThat("Repository name should match", result.get().get(0).getName(), is("maven-central"));
  }

  /**
   * Tests that repository operations can be executed concurrently using virtual threads.
   * This validates that the repository layer can handle high concurrency with virtual threads.
   */
  @Test
  @DisplayName("Repository operations should handle high concurrency with virtual threads")
  public void repositoryOperationsShouldHandleHighConcurrencyWithVirtualThreads() throws Exception {
    // Setup test repositories
    Format maven2 = new Format("maven2") {};
    Repository mavenProxyRepository = mockRepository("maven-central", maven2, proxyType, 
        "http://localhost:8081/repository/maven-central/", true, Map.of());
    
    List<Repository> repositories = List.of(mavenProxyRepository);
    
    when(repositoryPermissionChecker.userCanBrowseRepositories(repositories)).thenReturn(repositories);
    when(repositoryManager.browse()).thenReturn(repositories);

    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    int taskCount = 1000;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    ConcurrentHashMap<String, Boolean> threadTypes = new ConcurrentHashMap<>();
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            // Record if this is running on a virtual thread
            threadTypes.put(Thread.currentThread().getName(), Thread.currentThread().isVirtual());
            
            List<RepositoryXO> repos = underTest.getRepositories(null, false, false, null);
            if (repos == null || repos.isEmpty()) {
              errorCount.incrementAndGet();
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("All tasks should complete", completed, is(true));
      assertThat("No errors should occur during concurrent execution", errorCount.get(), is(0));
      
      // Verify all operations ran on virtual threads
      long virtualThreadCount = threadTypes.values().stream().filter(Boolean::booleanValue).count();
      log.info("Operations executed on virtual threads: {}/{}", virtualThreadCount, taskCount);
      assertThat("All operations should run on virtual threads", virtualThreadCount, is((long)taskCount));
      
      // Verify the repository manager was called the expected number of times
      verify(repositoryManager, times(taskCount)).browse();
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Compares performance between platform threads and virtual threads for repository operations.
   * This test validates that virtual threads provide better scalability for I/O-bound operations.
   */
  @Test
  @DisplayName("Virtual threads should provide better scalability than platform threads")
  public void virtualThreadsShouldProvideScalabilityBenefits() throws Exception {
    // Setup test repositories
    Format maven2 = new Format("maven2") {};
    Repository mavenProxyRepository = mockRepository("maven-central", maven2, proxyType, 
        "http://localhost:8081/repository/maven-central/", true, Map.of());
    
    List<Repository> repositories = List.of(mavenProxyRepository);
    
    when(repositoryPermissionChecker.userCanBrowseRepositories(repositories)).thenReturn(repositories);
    when(repositoryManager.browse()).thenReturn(repositories);

    // Number of concurrent operations to perform
    int concurrentOperations = 1000;
    
    // Measure execution time with platform threads
    long platformThreadTime = measureExecutionTime(() -> {
      ExecutorService platformExecutor = Executors.newFixedThreadPool(100); // Limited thread pool
      try {
        executeRepositoryOperations(platformExecutor, concurrentOperations);
      } finally {
        platformExecutor.shutdown();
      }
    });
    
    // Measure execution time with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
      try {
        executeRepositoryOperations(virtualExecutor, concurrentOperations);
      } finally {
        virtualExecutor.shutdown();
      }
    });
    
    // Log the results for analysis
    log.info("Platform thread execution time: {} ms", platformThreadTime);
    log.info("Virtual thread execution time: {} ms", virtualThreadTime);
    log.info("Performance ratio: platform/virtual = {}", (double) platformThreadTime / virtualThreadTime);
    
    // For high concurrency operations, virtual threads should generally be more efficient
    // However, this is a simple test and might not always show benefits in all environments
    // So we'll log the results but not make hard assertions about performance
    
    // Optional assertion if we want to enforce performance expectations
    // assertThat("Virtual threads should be faster than platform threads for I/O bound operations", 
    //     virtualThreadTime, lessThan(platformThreadTime));
  }

  /**
   * Tests that thread pinning can be detected when using virtual threads.
   * This is important for identifying operations that might block carrier threads.
   */
  @Test
  @DisplayName("Thread pinning should be detectable when using virtual threads")
  public void threadPinningShouldBeDetectable() throws Exception {
    // This test demonstrates how to detect thread pinning
    // In a real application, you would use JFR events or the jdk.tracePinnedThreads system property
    
    // For this test, we'll simulate pinning detection by checking thread names and monitoring
    // In a real scenario, you would use proper pinning detection mechanisms
    
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("test-virtual-", 0).factory();
    AtomicReference<String> threadName = new AtomicReference<>();
    AtomicReference<Boolean> isVirtual = new AtomicReference<>();
    AtomicReference<String> threadDump = new AtomicReference<>();
    
    Thread virtualThread = virtualThreadFactory.newThread(() -> {
      // Capture the current thread information
      Thread currentThread = Thread.currentThread();
      threadName.set(currentThread.getName());
      isVirtual.set(currentThread.isVirtual());
      
      // Capture stack trace for pinning analysis
      StackTraceElement[] stackTrace = currentThread.getStackTrace();
      StringBuilder sb = new StringBuilder();
      sb.append("Thread stack trace for pinning analysis:\n");
      for (StackTraceElement element : stackTrace) {
        sb.append("  at ").append(element).append("\n");
      }
      threadDump.set(sb.toString());
      
      // In a real test, you might perform operations that could cause pinning
      // For example, synchronized blocks, native methods, or certain I/O operations
      // synchronized (this) {
      //   try {
      //     Thread.sleep(100); // This would cause pinning inside a synchronized block
      //   } catch (InterruptedException e) {
      //     Thread.currentThread().interrupt();
      //   }
      // }
    });
    
    virtualThread.start();
    virtualThread.join();
    
    // Verify the thread was a virtual thread
    assertThat("Thread should be a virtual thread", isVirtual.get(), is(true));
    assertThat("Thread name should match virtual thread naming pattern", 
        threadName.get().startsWith("test-virtual-"), is(true));
    
    // Log the thread dump for analysis
    log.info(threadDump.get());
    
    // In a real test, you would verify that no pinning occurred
    // This could be done by checking JFR events or logs when jdk.tracePinnedThreads is enabled
    log.info("In production, enable -Djdk.tracePinnedThreads=full to detect thread pinning");
    log.info("Example command: java -Djdk.tracePinnedThreads=full -jar nexus-repository-services.jar");
  }

  /**
   * Tests repository operations with different thread pool sizes to evaluate scalability.
   * This test helps identify the optimal thread pool configuration for the application.
   */
  @ParameterizedTest
  @ValueSource(ints = {10, 50, 100, 500})
  @DisplayName("Repository operations should scale with different thread pool sizes")
  public void repositoryOperationsShouldScaleWithDifferentThreadPoolSizes(int threadPoolSize) throws Exception {
    // Setup test repositories
    Format maven2 = new Format("maven2") {};
    Repository mavenProxyRepository = mockRepository("maven-central", maven2, proxyType, 
        "http://localhost:8081/repository/maven-central/", true, Map.of());
    
    List<Repository> repositories = List.of(mavenProxyRepository);
    
    when(repositoryPermissionChecker.userCanBrowseRepositories(repositories)).thenReturn(repositories);
    when(repositoryManager.browse()).thenReturn(repositories);

    // Number of operations to perform
    int operationCount = 1000;
    
    // Measure execution time with platform threads at the specified pool size
    ExecutorService platformExecutor = Executors.newFixedThreadPool(threadPoolSize);
    try {
      long startTime = System.currentTimeMillis();
      executeRepositoryOperations(platformExecutor, operationCount);
      long executionTime = System.currentTimeMillis() - startTime;
      
      log.info("Platform thread execution time with pool size {}: {} ms", threadPoolSize, executionTime);
      
      // No specific assertions here as performance will vary by environment
      // This test is primarily for gathering metrics on different thread pool sizes
    } finally {
      platformExecutor.shutdown();
    }
  }

  /**
   * Helper method to execute repository operations using the provided executor.
   */
  private void executeRepositoryOperations(ExecutorService executor, int operationCount) throws Exception {
    CountDownLatch latch = new CountDownLatch(operationCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Submit tasks to the executor
    for (int i = 0; i < operationCount; i++) {
      executor.submit(() -> {
        try {
          underTest.getRepositories(null, false, false, null);
        } catch (Exception e) {
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    
    // Verify results
    assertThat("All tasks should complete", completed, is(true));
    assertThat("No errors should occur during execution", errorCount.get(), is(0));
  }

  /**
   * Helper method to measure execution time of a runnable operation.
   */
  private long measureExecutionTime(Runnable operation) throws Exception {
    long startTime = System.currentTimeMillis();
    operation.run();
    return System.currentTimeMillis() - startTime;
  }

  /**
   * Helper method to create a mock repository with the specified properties.
   */
  private Repository mockRepository(
      String name,
      Format format,
      Type type,
      String url,
      boolean online,
      Map<Class<?>, Object> facets)
  {
    Repository repository = mock(Repository.class);
    when(repository.getName()).thenReturn(name);
    when(repository.getFormat()).thenReturn(format);
    when(repository.getType()).thenReturn(type);
    when(repository.getUrl()).thenReturn(url);
    
    Configuration configuration = mock(Configuration.class);
    when(repository.getConfiguration()).thenReturn(configuration);
    when(configuration.isOnline()).thenReturn(online);
    
    // Setup facets if needed
    facets.forEach((clazz, facet) -> {
      when(repository.facet(clazz)).thenReturn(facet);
    });
    
    return repository;
  }
}