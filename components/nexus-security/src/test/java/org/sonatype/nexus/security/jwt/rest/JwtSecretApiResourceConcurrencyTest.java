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
package org.sonatype.nexus.security.jwt.rest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.ws.rs.core.Response;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.jwt.SecretStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static javax.ws.rs.core.Response.Status.OK;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Concurrency tests for {@link JwtSecretApiResourceV1} using Java 21 virtual threads.
 * 
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
public class JwtSecretApiResourceConcurrencyTest
    extends TestSupport
{
  private static final int CONCURRENT_REQUESTS = 1000;
  private static final int HIGH_CONCURRENCY_REQUESTS = 10000;
  private static final int WARMUP_REQUESTS = 100;
  
  @Mock
  private SecretStore secretStore;
  
  @Captor
  private ArgumentCaptor<String> secretCaptor;
  
  private JwtSecretApiResourceV1 underTest;
  
  private final ConcurrentHashMap<String, AtomicInteger> secretOccurrences = new ConcurrentHashMap<>();
  
  @BeforeEach
  public void setup() {
    underTest = new JwtSecretApiResourceV1(secretStore);
    
    // Configure the mock to track secret occurrences to verify thread safety
    doAnswer(invocation -> {
      String secret = invocation.getArgument(0);
      secretOccurrences.computeIfAbsent(secret, k -> new AtomicInteger(0)).incrementAndGet();
      return null;
    }).when(secretStore).setSecret(any(String.class));
  }
  
  /**
   * Tests concurrent JWT secret reset operations using virtual threads.
   * Verifies that all operations complete successfully and that the SecretStore
   * is called the expected number of times.
   */
  @Test
  @DisplayName("Test concurrent JWT secret reset with virtual threads")
  public void testConcurrentResetSecretWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Response>> futures = new ArrayList<>();
      
      // Submit concurrent reset requests
      for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
        futures.add(executor.submit(() -> underTest.resetSecret()));
      }
      
      // Wait for all operations to complete and verify responses
      for (Future<Response> future : futures) {
        Response response = future.get(10, TimeUnit.SECONDS);
        assertThat(response.getStatus(), is(OK.getStatusCode()));
      }
    }
    
    // Verify the SecretStore was called the expected number of times
    verify(secretStore, times(CONCURRENT_REQUESTS)).setSecret(any(String.class));
  }
  
  /**
   * Tests concurrent JWT secret reset operations using platform threads.
   * This provides a comparison point for the virtual thread implementation.
   */
  @Test
  @DisplayName("Test concurrent JWT secret reset with platform threads")
  public void testConcurrentResetSecretWithPlatformThreads() throws Exception {
    // Create a platform thread executor with a fixed thread pool
    try (ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {
      List<Future<Response>> futures = new ArrayList<>();
      
      // Submit concurrent reset requests
      for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
        futures.add(executor.submit(() -> underTest.resetSecret()));
      }
      
      // Wait for all operations to complete and verify responses
      for (Future<Response> future : futures) {
        Response response = future.get(10, TimeUnit.SECONDS);
        assertThat(response.getStatus(), is(OK.getStatusCode()));
      }
    }
    
    // Verify the SecretStore was called the expected number of times
    verify(secretStore, times(CONCURRENT_REQUESTS)).setSecret(any(String.class));
  }
  
  /**
   * Compares the performance of virtual threads vs platform threads for JWT secret reset operations.
   * This test demonstrates the efficiency gains possible with virtual threads for I/O-bound operations.
   */
  @Test
  @DisplayName("Compare performance between virtual and platform threads")
  public void comparePerformanceVirtualVsPlatformThreads() throws Exception {
    // Warm up to avoid JIT compilation affecting results
    runConcurrentRequests(Executors.newVirtualThreadPerTaskExecutor(), WARMUP_REQUESTS);
    runConcurrentRequests(Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()), WARMUP_REQUESTS);
    
    // Test with virtual threads
    Instant virtualStart = Instant.now();
    runConcurrentRequests(Executors.newVirtualThreadPerTaskExecutor(), CONCURRENT_REQUESTS);
    Duration virtualDuration = Duration.between(virtualStart, Instant.now());
    
    // Test with platform threads
    Instant platformStart = Instant.now();
    runConcurrentRequests(Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()), CONCURRENT_REQUESTS);
    Duration platformDuration = Duration.between(platformStart, Instant.now());
    
    // Log performance results
    log.info("Virtual threads execution time: {} ms", virtualDuration.toMillis());
    log.info("Platform threads execution time: {} ms", platformDuration.toMillis());
    
    // For high-concurrency I/O-bound operations, virtual threads should generally be more efficient
    // However, we don't assert this as a requirement since it depends on the environment
  }
  
  /**
   * Tests thread safety under high concurrency using virtual threads.
   * Verifies that each secret is only used once, confirming the absence of race conditions.
   */
  @Test
  @DisplayName("Test thread safety under high concurrency with virtual threads")
  public void testThreadSafetyUnderHighConcurrency() throws Exception {
    secretOccurrences.clear();
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Response>> futures = new ArrayList<>();
      
      // Submit concurrent reset requests that all start at the same time
      for (int i = 0; i < HIGH_CONCURRENCY_REQUESTS; i++) {
        futures.add(executor.submit(() -> {
          startLatch.await(); // Wait for the signal to start
          return underTest.resetSecret();
        }));
      }
      
      // Signal all threads to start simultaneously
      startLatch.countDown();
      
      // Wait for all operations to complete
      for (Future<Response> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
    }
    
    // Verify the SecretStore was called the expected number of times
    verify(secretStore, times(HIGH_CONCURRENCY_REQUESTS)).setSecret(secretCaptor.capture());
    
    // Verify that each secret was only used once (no duplicates)
    List<String> capturedSecrets = secretCaptor.getAllValues();
    assertThat(capturedSecrets.size(), is(HIGH_CONCURRENCY_REQUESTS));
    
    // Check that each secret was used exactly once
    for (AtomicInteger count : secretOccurrences.values()) {
      assertThat("Each secret should be used exactly once", count.get(), is(1));
    }
    
    // The number of unique secrets should match the number of requests
    assertThat(secretOccurrences.size(), is(HIGH_CONCURRENCY_REQUESTS));
  }
  
  /**
   * Tests that the SecretStore implementation correctly handles simultaneous reset requests
   * from both platform and virtual threads without race conditions.
   */
  @Test
  @DisplayName("Test mixed thread types concurrency")
  public void testMixedThreadTypesConcurrency() throws Exception {
    secretOccurrences.clear();
    int requestsPerType = CONCURRENT_REQUESTS / 2;
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create executors for both thread types
    try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
         ExecutorService platformExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {
      
      List<Future<Response>> futures = new ArrayList<>();
      
      // Submit requests using virtual threads
      for (int i = 0; i < requestsPerType; i++) {
        futures.add(virtualExecutor.submit(() -> {
          startLatch.await();
          return underTest.resetSecret();
        }));
      }
      
      // Submit requests using platform threads
      for (int i = 0; i < requestsPerType; i++) {
        futures.add(platformExecutor.submit(() -> {
          startLatch.await();
          return underTest.resetSecret();
        }));
      }
      
      // Signal all threads to start simultaneously
      startLatch.countDown();
      
      // Wait for all operations to complete
      for (Future<Response> future : futures) {
        Response response = future.get(10, TimeUnit.SECONDS);
        assertThat(response.getStatus(), is(OK.getStatusCode()));
      }
    }
    
    // Verify the SecretStore was called the expected number of times
    verify(secretStore, times(CONCURRENT_REQUESTS)).setSecret(secretCaptor.capture());
    
    // Verify that each secret was only used once (no duplicates)
    List<String> capturedSecrets = secretCaptor.getAllValues();
    assertThat(capturedSecrets.size(), is(CONCURRENT_REQUESTS));
    
    // Check that each secret was used exactly once
    for (AtomicInteger count : secretOccurrences.values()) {
      assertThat("Each secret should be used exactly once", count.get(), is(1));
    }
    
    // The number of unique secrets should match the number of requests
    assertThat(secretOccurrences.size(), is(CONCURRENT_REQUESTS));
  }
  
  /**
   * Helper method to run concurrent requests using the specified executor.
   */
  private void runConcurrentRequests(ExecutorService executor, int requestCount) throws Exception {
    try (ExecutorService executorService = executor) {
      List<Future<Response>> futures = new ArrayList<>();
      
      // Submit concurrent reset requests
      for (int i = 0; i < requestCount; i++) {
        futures.add(executorService.submit(() -> underTest.resetSecret()));
      }
      
      // Wait for all operations to complete
      for (Future<Response> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
    }
    
    // Verify the SecretStore was called at least once
    verify(secretStore, atLeastOnce()).setSecret(any(String.class));
  }
}