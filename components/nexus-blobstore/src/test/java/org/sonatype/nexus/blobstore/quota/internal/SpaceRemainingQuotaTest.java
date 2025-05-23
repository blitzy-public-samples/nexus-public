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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.rest.ValidationErrorsException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
  void setup() {
    when(blobStore.getMetrics()).thenReturn(metrics);
    when(blobStore.getBlobStoreConfiguration()).thenReturn(config);
    when(config.getName()).thenReturn("test");
    when(config.attributes(ROOT_KEY)).thenReturn(attributesMap);
    when(attributesMap.get(eq(LIMIT_KEY), eq(Number.class))).thenReturn(10L);

    quota = new SpaceRemainingQuota();
  }

  @Test
  void sufficientSpaceRemaining() {
    when(metrics.isUnlimited()).thenReturn(false);
    when(metrics.getAvailableSpace()).thenReturn(20L);

    assertFalse(quota.check(blobStore).isViolation(), STR."Space check should not be violated with \{metrics.getAvailableSpace()} available space");
  }

  @Test
  void insufficientSpaceRemaining() {
    when(metrics.isUnlimited()).thenReturn(false);
    when(metrics.getAvailableSpace()).thenReturn(5L);

    assertTrue(quota.check(blobStore).isViolation(), STR."Space check should be violated with only \{metrics.getAvailableSpace()} available space");
  }

  @Test
  void unlimitedSpaceRemaining() {
    when(metrics.isUnlimited()).thenReturn(true);
    when(metrics.getAvailableSpace()).thenReturn(5L);

    assertFalse(quota.check(blobStore).isViolation(), STR."Space check should not be violated when space is unlimited");
  }

  @Test
  void greaterThanZeroLimitIsValid() {
    when(attributesMap.get(eq(LIMIT_KEY), eq(Number.class))).thenReturn(10L);
    quota.validateConfig(config);
  }

  @Test
  void zeroLimitIsInvalid() {
    when(attributesMap.get(eq(LIMIT_KEY), eq(Number.class))).thenReturn(0);
    assertThrows(ValidationErrorsException.class, () -> quota.validateConfig(config), 
        STR."Should throw ValidationErrorsException when limit is \{attributesMap.get(eq(LIMIT_KEY), eq(Number.class))}");
  }

  @Test
  void noLimitIsInvalid() {
    when(attributesMap.get(eq(LIMIT_KEY), eq(Number.class))).thenReturn(null);
    assertThrows(IllegalArgumentException.class, () -> quota.validateConfig(config),
        STR."Should throw IllegalArgumentException when limit is null");
  }
  
  @Test
  @VirtualThreadTestGroup
  void concurrentQuotaChecks() throws Exception {
    // Setup for concurrent testing
    when(metrics.isUnlimited()).thenReturn(false);
    when(metrics.getAvailableSpace()).thenReturn(5L); // Insufficient space
    
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger violationCount = new AtomicInteger(0);
    
    // Create virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent quota check tasks
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            if (quota.check(blobStore).isViolation()) {
              violationCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      assertTrue(latch.await(10, TimeUnit.SECONDS), STR."Timed out waiting for \{taskCount} virtual threads to complete");
      
      // All checks should report violation
      assertTrue(violationCount.get() == taskCount, 
          STR."Expected \{taskCount} violations but got \{violationCount.get()}");
    }
  }
  
  @Test
  @VirtualThreadTestGroup
  void quotaEnforcingUnderHighConcurrency() throws Exception {
    // Setup for high concurrency testing with varying available space
    AtomicInteger availableSpace = new AtomicInteger(15); // Start with sufficient space
    when(metrics.isUnlimited()).thenReturn(false);
    when(metrics.getAvailableSpace()).thenAnswer(invocation -> (long) availableSpace.get());
    
    int taskCount = 1000;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger violationCount = new AtomicInteger(0);
    
    // Create virtual thread executor for high concurrency
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit many concurrent quota check tasks
      for (int i = 0; i < taskCount; i++) {
        final int iteration = i;
        executor.submit(() -> {
          try {
            // Every 100 iterations, decrease available space
            if (iteration % 100 == 0 && iteration > 0) {
              availableSpace.updateAndGet(current -> Math.max(0, current - 2));
            }
            
            // Check quota and count violations
            if (quota.check(blobStore).isViolation()) {
              violationCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      assertTrue(latch.await(10, TimeUnit.SECONDS), STR."Timed out waiting for \{taskCount} virtual threads to complete");
      
      // Verify that we have some violations (exact count will depend on execution order)
      assertTrue(violationCount.get() > 0, 
          STR."Expected some quota violations but got \{violationCount.get()}");
      
      // Log the final state for diagnostics
      logger.info(STR."Final available space: \{availableSpace.get()}, Violation count: \{violationCount.get()}");
    }
  }
}
