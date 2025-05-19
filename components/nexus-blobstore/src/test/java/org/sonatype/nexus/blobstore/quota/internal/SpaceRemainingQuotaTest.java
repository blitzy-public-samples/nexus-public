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
package org.sonatype.nexus.blobstore.quota.internal;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaResult;
import org.sonatype.nexus.blobstore.virtualthread.VirtualThreadTestGroup;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.rest.ValidationErrorsException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.lang.StringTemplate.STR;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.quota.BlobStoreQuotaSupport.LIMIT_KEY;
import static org.sonatype.nexus.blobstore.quota.BlobStoreQuotaSupport.ROOT_KEY;

@ExtendWith(MockitoExtension.class)
public class SpaceRemainingQuotaTest
    extends TestSupport
{
  SpaceRemainingQuota quota;

  @Mock
  BlobStore blobStore;

  @Mock
  BlobStoreMetrics metrics;

  @Mock
  BlobStoreConfiguration config;

  @Mock
  NestedAttributesMap attributesMap;

  @BeforeEach
  public void setup() {
    when(blobStore.getMetrics()).thenReturn(metrics);
    when(blobStore.getBlobStoreConfiguration()).thenReturn(config);
    when(config.getName()).thenReturn("test");
    when(config.attributes(ROOT_KEY)).thenReturn(attributesMap);
    when(attributesMap.get(eq(LIMIT_KEY), eq(Number.class))).thenReturn(10L);

    quota = new SpaceRemainingQuota();
  }

  @Test
  public void sufficientSpaceRemaining() {
    when(metrics.isUnlimited()).thenReturn(false);
    when(metrics.getAvailableSpace()).thenReturn(20L);

    assertFalse(quota.check(blobStore).isViolation(), "Should not report violation when sufficient space remains");
  }

  @Test
  public void insufficientSpaceRemaining() {
    when(metrics.isUnlimited()).thenReturn(false);
    when(metrics.getAvailableSpace()).thenReturn(5L);

    assertTrue(quota.check(blobStore).isViolation(), "Should report violation when insufficient space remains");
  }

  @Test
  public void unlimitedSpaceRemaining() {
    when(metrics.isUnlimited()).thenReturn(true);
    when(metrics.getAvailableSpace()).thenReturn(5L);

    assertFalse(quota.check(blobStore).isViolation(), "Should not report violation when space is unlimited");
  }

  @Test
  public void greaterThanZeroLimitIsValid() {
    when(attributesMap.get(eq(LIMIT_KEY), eq(Number.class))).thenReturn(10L);
    quota.validateConfig(config);
  }

  @Test
  public void zeroLimitIsInvalid() {
    when(attributesMap.get(eq(LIMIT_KEY), eq(Number.class))).thenReturn(0);
    
    ValidationErrorsException exception = assertThrows(ValidationErrorsException.class, 
        () -> quota.validateConfig(config),
        STR."Should throw ValidationErrorsException for zero limit");
  }

  @Test
  public void noLimitIsInvalid() {
    when(attributesMap.get(eq(LIMIT_KEY), eq(Number.class))).thenReturn(null);
    
    assertThrows(IllegalArgumentException.class, 
        () -> quota.validateConfig(config),
        STR."Should throw IllegalArgumentException when limit is null");
  }
  
  @VirtualThreadTestGroup
  public void concurrentQuotaChecks() throws Exception {
    // Setup for concurrent testing
    int threadCount = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger violationCount = new AtomicInteger(0);
    
    // Configure metrics to alternate between sufficient and insufficient space
    when(metrics.isUnlimited()).thenReturn(false);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Launch multiple virtual threads to check quota concurrently
      for (int i = 0; i < threadCount; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            
            // Even threads get sufficient space, odd threads get insufficient space
            long availableSpace = (threadId % 2 == 0) ? 20L : 5L;
            when(metrics.getAvailableSpace()).thenReturn(availableSpace);
            
            BlobStoreQuotaResult result = quota.check(blobStore);
            if (result.isViolation()) {
              violationCount.incrementAndGet();
            }
            
            return null;
          }
          catch (Exception e) {
            log.error(STR."Error in virtual thread \{threadId}", e);
            return null;
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      assertTrue(completionLatch.await(10, TimeUnit.SECONDS), 
          STR."All \{threadCount} virtual threads should complete within timeout");
      
      // Approximately half of the threads should report violations
      int expectedViolations = threadCount / 2;
      assertTrue(Math.abs(violationCount.get() - expectedViolations) <= 5, 
          STR."Expected approximately \{expectedViolations} violations, got \{violationCount.get()}");
    }
  }
  
  @VirtualThreadTestGroup
  public void quotaEnforcementUnderHighConcurrency() throws Exception {
    int threadCount = 1000;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    
    // Configure for dynamic space checking
    when(metrics.isUnlimited()).thenReturn(false);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Launch many virtual threads to simulate high concurrency
      for (int i = 0; i < threadCount; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try {
            startLatch.await();
            
            // Simulate decreasing available space as more threads run
            // This tests pattern matching with instanceof in the quota validation logic
            long availableSpace = 15L - (threadId % 10);
            when(metrics.getAvailableSpace()).thenReturn(availableSpace);
            
            BlobStoreQuotaResult result = quota.check(blobStore);
            
            // Use pattern matching to handle the result
            if (result instanceof BlobStoreQuotaResult quotaResult && quotaResult.isViolation()) {
              failureCount.incrementAndGet();
            } else {
              successCount.incrementAndGet();
            }
            
            return null;
          }
          catch (Exception e) {
            log.error(STR."Error in high concurrency test thread \{threadId}", e);
            return null;
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      assertTrue(completionLatch.await(10, TimeUnit.SECONDS), 
          STR."All \{threadCount} virtual threads in high concurrency test should complete within timeout");
      
      // Verify results
      int totalResults = successCount.get() + failureCount.get();
      assertTrue(totalResults == threadCount, 
          STR."Expected \{threadCount} total results, got \{totalResults}");
      
      log.info(STR."High concurrency test completed with \{successCount.get()} successes and \{failureCount.get()} failures");
    }
  }
}