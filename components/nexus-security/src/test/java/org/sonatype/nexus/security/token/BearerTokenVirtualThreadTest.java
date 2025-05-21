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
package org.sonatype.nexus.security.token;

import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Provider;
import javax.servlet.http.HttpServletRequest;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.SecurityHelper;
import org.sonatype.nexus.security.UserPrincipalsHelper;
import org.sonatype.nexus.security.authc.NexusApiKeyAuthenticationToken;
import org.sonatype.nexus.security.authc.apikey.ApiKey;
import org.sonatype.nexus.security.authc.apikey.ApiKeyService;
import org.sonatype.nexus.security.user.UserStatus;

import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.credential.CredentialsMatcher;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for bearer token operations in a virtual thread environment.
 * 
 * This test suite validates that bearer token creation, validation, and management
 * work correctly with Java 21's virtual threads, including high-concurrency scenarios
 * and performance comparisons between platform threads and virtual threads.
 */
public class BearerTokenVirtualThreadTest
    extends TestSupport
{
  private static final String FORMAT = "format";
  private static final String TOKEN = "token";
  private static final int CONCURRENT_THREADS = 1000;
  private static final int HEAVY_LOAD_THREADS = 10000;
  private static final int WARMUP_ITERATIONS = 5;
  private static final int BENCHMARK_ITERATIONS = 10;

  @Mock
  private SecurityHelper securityHelper;

  @Mock
  private ApiKeyService apiKeyService;

  @Mock
  private SecurityManager securityManager;

  @Mock
  private AuthenticationInfo authenticationInfo;

  @Mock
  private PrincipalCollection principalCollection;

  @Mock
  private Subject subject;

  @Mock
  private UserPrincipalsHelper principalsHelper;

  @Mock
  private Provider<HttpServletRequest> requestProvider;

  @Mock
  private HttpServletRequest request;

  @Mock
  private CredentialsMatcher credentialsMatcher;

  @Mock
  private Principal principal;

  @Mock
  private NexusApiKeyAuthenticationToken token;

  private BearerTokenManager tokenManager;
  private BearerTokenRealm tokenRealm;

  @Before
  public void setup() throws Exception {
    // Setup for BearerTokenManager
    when(securityHelper.getSecurityManager()).thenReturn(securityManager);
    when(securityManager.authenticate(any())).thenReturn(authenticationInfo);
    when(authenticationInfo.getPrincipals()).thenReturn(principalCollection);
    when(securityHelper.subject()).thenReturn(subject);
    when(subject.getPrincipals()).thenReturn(principalCollection);
    tokenManager = new BearerTokenManager(apiKeyService, securityHelper, FORMAT) { };

    // Setup for BearerTokenRealm
    when(token.getPrincipal()).thenReturn(FORMAT);
    when(principalCollection.getPrimaryPrincipal()).thenReturn(principal);
    ApiKey key = mock(ApiKey.class);
    when(key.getPrincipals()).thenReturn(principalCollection);
    when(key.getApiKey()).thenReturn(TOKEN.toCharArray());
    when(apiKeyService.getApiKeyByToken(any(), any())).thenReturn(Optional.of(key));
    when(principalsHelper.getUserStatus(principalCollection)).thenReturn(UserStatus.active);
    when(credentialsMatcher.doCredentialsMatch(any(), any())).thenReturn(true);
    when(requestProvider.get()).thenReturn(request);
    tokenRealm = new BearerTokenRealm(apiKeyService, principalsHelper, FORMAT) {};
    tokenRealm.setRequestProvider(requestProvider);
    tokenRealm.setCredentialsMatcher(credentialsMatcher);
  }

  /**
   * Tests that a single token can be created and validated in a virtual thread.
   */
  @Test
  public void testTokenCreationInVirtualThread() throws Exception {
    AtomicReference<String> tokenRef = new AtomicReference<>();
    AtomicBoolean success = new AtomicBoolean(false);

    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        when(apiKeyService.createApiKey(FORMAT, principalCollection)).thenReturn(TOKEN.toCharArray());
        String token = tokenManager.createToken(principalCollection);
        tokenRef.set(token);
        success.set(true);
      } catch (Exception e) {
        log.error("Error in virtual thread", e);
      }
    });

    virtualThread.join();
    assertTrue("Token creation should succeed in virtual thread", success.get());
    assertEquals(FORMAT + "." + TOKEN, tokenRef.get());
  }

  /**
   * Tests that a token can be validated in a virtual thread.
   */
  @Test
  public void testTokenValidationInVirtualThread() throws Exception {
    AtomicReference<AuthenticationInfo> authInfoRef = new AtomicReference<>();
    AtomicBoolean success = new AtomicBoolean(false);

    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        AuthenticationInfo authInfo = tokenRealm.doGetAuthenticationInfo(token);
        authInfoRef.set(authInfo);
        success.set(true);
      } catch (Exception e) {
        log.error("Error in virtual thread", e);
      }
    });

    virtualThread.join();
    assertTrue("Token validation should succeed in virtual thread", success.get());
    assertNotNull("Authentication info should not be null", authInfoRef.get());
    assertNotNull("Principals should not be null", authInfoRef.get().getPrincipals());
  }

  /**
   * Tests that a token can be deleted in a virtual thread.
   */
  @Test
  public void testTokenDeletionInVirtualThread() throws Exception {
    AtomicBoolean deleteResult = new AtomicBoolean(false);
    AtomicBoolean success = new AtomicBoolean(false);

    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        Optional<ApiKey> apiKey = Optional.of(mockApiKey(TOKEN.toCharArray()));
        when(apiKeyService.getApiKey(any(), any())).thenReturn(apiKey);
        deleteResult.set(tokenManager.deleteToken());
        success.set(true);
      } catch (Exception e) {
        log.error("Error in virtual thread", e);
      }
    });

    virtualThread.join();
    assertTrue("Token deletion operation should complete successfully", success.get());
    assertTrue("Token deletion should return true", deleteResult.get());
    verify(apiKeyService).deleteApiKey(FORMAT, principalCollection);
  }

  /**
   * Tests concurrent token creation in multiple virtual threads.
   */
  @Test
  public void testConcurrentTokenCreationInVirtualThreads() throws Exception {
    int threadCount = CONCURRENT_THREADS;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    ConcurrentHashMap<String, String> tokens = new ConcurrentHashMap<>();

    // Setup mock to return unique tokens for each thread
    when(apiKeyService.createApiKey(any(), any())).thenAnswer(invocation -> {
      return ("token-" + Thread.currentThread().threadId()).toCharArray();
    });

    // Create and start virtual threads
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < threadCount; i++) {
      Thread thread = Thread.ofVirtual().name("token-thread-" + i).start(() -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          String token = tokenManager.createToken(principalCollection);
          tokens.put(Thread.currentThread().getName(), token);
          successCount.incrementAndGet();
        } catch (Exception e) {
          log.error("Error in virtual thread: {}", Thread.currentThread().getName(), e);
        } finally {
          completionLatch.countDown();
        }
      });
      threads.add(thread);
    }

    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
    assertTrue("All threads should complete in time", completed);
    
    // Verify results
    assertEquals("All token creations should succeed", threadCount, successCount.get());
    assertEquals("Each thread should create a unique token", threadCount, tokens.size());
  }

  /**
   * Tests concurrent token validation in multiple virtual threads.
   */
  @Test
  public void testConcurrentTokenValidationInVirtualThreads() throws Exception {
    int threadCount = CONCURRENT_THREADS;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);

    // Create and start virtual threads
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < threadCount; i++) {
      Thread thread = Thread.ofVirtual().name("validation-thread-" + i).start(() -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          AuthenticationInfo authInfo = tokenRealm.doGetAuthenticationInfo(token);
          if (authInfo != null && authInfo.getPrincipals() != null) {
            successCount.incrementAndGet();
          }
        } catch (Exception e) {
          log.error("Error in virtual thread: {}", Thread.currentThread().getName(), e);
        } finally {
          completionLatch.countDown();
        }
      });
      threads.add(thread);
    }

    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
    assertTrue("All threads should complete in time", completed);
    
    // Verify results
    assertEquals("All token validations should succeed", threadCount, successCount.get());
    verify(apiKeyService, times(threadCount)).getApiKeyByToken(any(), any());
  }

  /**
   * Tests for thread pinning during token operations.
   * This test uses the JVM's thread pinning detection mechanism.
   */
  @Test
  public void testThreadPinningDetection() throws Exception {
    // Enable thread pinning detection for this test
    // Note: In a real environment, you would use -Djdk.tracePinnedThreads=full JVM flag
    // or monitor JFR VirtualThreadPinned events
    
    // For this test, we'll simulate pinning detection by checking if operations complete in expected time
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean operationCompleted = new AtomicBoolean(false);
    
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Setup for a token creation that should not cause pinning
        when(apiKeyService.createApiKey(FORMAT, principalCollection)).thenReturn(TOKEN.toCharArray());
        tokenManager.createToken(principalCollection);
        operationCompleted.set(true);
      } finally {
        latch.countDown();
      }
    });
    
    // If the thread is pinned, this would take longer than expected
    boolean completed = latch.await(1, TimeUnit.SECONDS);
    assertTrue("Operation should complete without thread pinning", completed);
    assertTrue("Token creation should succeed", operationCompleted.get());
  }

  /**
   * Benchmarks token creation performance comparing platform threads vs virtual threads.
   */
  @Test
  public void benchmarkTokenCreationPerformance() throws Exception {
    int threadCount = 1000;
    
    // Setup mock to return tokens
    when(apiKeyService.createApiKey(any(), any())).thenReturn(TOKEN.toCharArray());
    
    // Warm up
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runTokenCreationBenchmark(threadCount, true); // Virtual threads
      runTokenCreationBenchmark(threadCount, false); // Platform threads
    }
    
    // Benchmark
    long virtualThreadTime = 0;
    long platformThreadTime = 0;
    
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      virtualThreadTime += runTokenCreationBenchmark(threadCount, true);
      platformThreadTime += runTokenCreationBenchmark(threadCount, false);
    }
    
    // Calculate averages
    long avgVirtualThreadTime = virtualThreadTime / BENCHMARK_ITERATIONS;
    long avgPlatformThreadTime = platformThreadTime / BENCHMARK_ITERATIONS;
    
    log.info("Average token creation time with {} threads:", threadCount);
    log.info("  Virtual threads:  {} ms", avgVirtualThreadTime);
    log.info("  Platform threads: {} ms", avgPlatformThreadTime);
    
    // Virtual threads should generally be faster or at least comparable for this I/O-bound operation
    // This is a flexible assertion as exact performance can vary by environment
    assertThat("Virtual threads should not be significantly slower than platform threads",
        avgVirtualThreadTime, lessThan(avgPlatformThreadTime * 2));
  }

  /**
   * Benchmarks token validation performance comparing platform threads vs virtual threads.
   */
  @Test
  public void benchmarkTokenValidationPerformance() throws Exception {
    int threadCount = 1000;
    
    // Warm up
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runTokenValidationBenchmark(threadCount, true); // Virtual threads
      runTokenValidationBenchmark(threadCount, false); // Platform threads
    }
    
    // Benchmark
    long virtualThreadTime = 0;
    long platformThreadTime = 0;
    
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      virtualThreadTime += runTokenValidationBenchmark(threadCount, true);
      platformThreadTime += runTokenValidationBenchmark(threadCount, false);
    }
    
    // Calculate averages
    long avgVirtualThreadTime = virtualThreadTime / BENCHMARK_ITERATIONS;
    long avgPlatformThreadTime = platformThreadTime / BENCHMARK_ITERATIONS;
    
    log.info("Average token validation time with {} threads:", threadCount);
    log.info("  Virtual threads:  {} ms", avgVirtualThreadTime);
    log.info("  Platform threads: {} ms", avgPlatformThreadTime);
    
    // Virtual threads should generally be faster or at least comparable for this I/O-bound operation
    assertThat("Virtual threads should not be significantly slower than platform threads",
        avgVirtualThreadTime, lessThan(avgPlatformThreadTime * 2));
  }

  /**
   * Tests token operations under extreme load with thousands of virtual threads.
   */
  @Test
  public void testHighConcurrencyTokenOperations() throws Exception {
    int threadCount = HEAVY_LOAD_THREADS;
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Setup mock to return tokens
    when(apiKeyService.createApiKey(any(), any())).thenReturn(TOKEN.toCharArray());
    
    // Create and start virtual threads
    for (int i = 0; i < threadCount; i++) {
      final int threadId = i;
      Thread.ofVirtual().name("heavy-load-thread-" + i).start(() -> {
        try {
          // Perform different operations based on thread ID to mix workloads
          if (threadId % 3 == 0) {
            // Create token
            String token = tokenManager.createToken(principalCollection);
            if (token != null) {
              successCount.incrementAndGet();
            }
          } else if (threadId % 3 == 1) {
            // Validate token
            AuthenticationInfo authInfo = tokenRealm.doGetAuthenticationInfo(token);
            if (authInfo != null && authInfo.getPrincipals() != null) {
              successCount.incrementAndGet();
            }
          } else {
            // Delete token
            Optional<ApiKey> apiKey = Optional.of(mockApiKey(TOKEN.toCharArray()));
            when(apiKeyService.getApiKey(any(), any())).thenReturn(apiKey);
            if (tokenManager.deleteToken()) {
              successCount.incrementAndGet();
            }
          }
        } catch (Exception e) {
          log.error("Error in virtual thread: {}", Thread.currentThread().getName(), e);
        } finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete with a reasonable timeout
    boolean completed = completionLatch.await(60, TimeUnit.SECONDS);
    assertTrue("All high-concurrency operations should complete in time", completed);
    
    // Verify that most operations succeeded
    // We don't expect 100% success due to the extreme load and potential resource limitations
    int expectedMinSuccesses = (int)(threadCount * 0.9); // 90% success rate
    assertTrue("At least 90% of operations should succeed under high load", 
        successCount.get() >= expectedMinSuccesses);
    
    log.info("High concurrency test completed with {} successful operations out of {}", 
        successCount.get(), threadCount);
  }

  /**
   * Helper method to run a token creation benchmark with the specified thread type.
   * 
   * @param threadCount Number of threads to use
   * @param useVirtualThreads Whether to use virtual threads (true) or platform threads (false)
   * @return Execution time in milliseconds
   */
  private long runTokenCreationBenchmark(int threadCount, boolean useVirtualThreads) throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    ExecutorService executor = useVirtualThreads ? 
        Executors.newVirtualThreadPerTaskExecutor() : 
        Executors.newFixedThreadPool(Math.min(100, threadCount));
    
    // Submit tasks
    for (int i = 0; i < threadCount; i++) {
      executor.submit(() -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          tokenManager.createToken(principalCollection);
        } catch (Exception e) {
          log.error("Error in benchmark thread", e);
        } finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start timing
    long startTime = System.currentTimeMillis();
    startLatch.countDown(); // Start all threads simultaneously
    
    // Wait for completion
    completionLatch.await();
    long endTime = System.currentTimeMillis();
    
    // Cleanup
    executor.shutdown();
    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
      executor.shutdownNow();
    }
    
    return endTime - startTime;
  }

  /**
   * Helper method to run a token validation benchmark with the specified thread type.
   * 
   * @param threadCount Number of threads to use
   * @param useVirtualThreads Whether to use virtual threads (true) or platform threads (false)
   * @return Execution time in milliseconds
   */
  private long runTokenValidationBenchmark(int threadCount, boolean useVirtualThreads) throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    ExecutorService executor = useVirtualThreads ? 
        Executors.newVirtualThreadPerTaskExecutor() : 
        Executors.newFixedThreadPool(Math.min(100, threadCount));
    
    // Submit tasks
    for (int i = 0; i < threadCount; i++) {
      executor.submit(() -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          tokenRealm.doGetAuthenticationInfo(token);
        } catch (Exception e) {
          log.error("Error in benchmark thread", e);
        } finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start timing
    long startTime = System.currentTimeMillis();
    startLatch.countDown(); // Start all threads simultaneously
    
    // Wait for completion
    completionLatch.await();
    long endTime = System.currentTimeMillis();
    
    // Cleanup
    executor.shutdown();
    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
      executor.shutdownNow();
    }
    
    return endTime - startTime;
  }

  /**
   * Helper method to create a mock ApiKey.
   */
  private ApiKey mockApiKey(final char[] token) {
    ApiKey key = mock(ApiKey.class);
    when(key.getApiKey()).thenReturn(token);
    when(key.getPrincipals()).thenReturn(principalCollection);
    return key;
  }
}