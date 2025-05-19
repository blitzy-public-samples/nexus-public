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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuota;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaResult;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaSupport;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.rest.ValidationErrorsException;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestGroup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.IsNull.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlobStoreQuotaServiceImplTest
    extends TestSupport
{
  BlobStoreQuotaServiceImpl service;

  @Mock
  BlobStoreQuota passingQuota;

  @Mock
  BlobStoreQuota violatedQuota;

  @Mock
  BlobStore blobStore;

  @Mock
  BlobStoreConfiguration config;

  @Mock
  NestedAttributesMap attributes;

  @BeforeEach
  void setup() {
    when(blobStore.getBlobStoreConfiguration()).thenReturn(config);
    when(config.attributes(eq(BlobStoreQuotaSupport.ROOT_KEY))).thenReturn(attributes);

    when(violatedQuota.check(any())).thenReturn(new BlobStoreQuotaResult(true, "test", "test"));
    when(passingQuota.check(any())).thenReturn(new BlobStoreQuotaResult(false, "test", "test"));

    Map<String, BlobStoreQuota> quotaProviders = new HashMap<>();
    quotaProviders.put("violated", violatedQuota);
    quotaProviders.put("passing", passingQuota);

    service = new BlobStoreQuotaServiceImpl(quotaProviders);
  }

  @Test
  void noQuotaIsAccepted() {
    when(attributes.get(BlobStoreQuotaSupport.TYPE_KEY, String.class)).thenReturn(null);
    assertThat(service.checkQuota(blobStore), nullValue());
  }

  @Test
  void passingQuota() {
    when(attributes.get(BlobStoreQuotaSupport.TYPE_KEY, String.class)).thenReturn("passing");
    BlobStoreQuotaResult result = service.checkQuota(blobStore);
    assertThat(result, notNullValue());
    
    // Using Record Pattern to extract fields from BlobStoreQuotaResult
    if (result instanceof BlobStoreQuotaResult(boolean isViolation, String message, String reason)) {
      assertFalse(isViolation, STR."Quota should not be violated but got: \{message} (\{reason})");
    }
  }

  @Test
  void failingQuota() {
    when(attributes.get(BlobStoreQuotaSupport.TYPE_KEY, String.class)).thenReturn("violated");
    BlobStoreQuotaResult result = service.checkQuota(blobStore);
    assertThat(result, notNullValue());
    
    // Using Record Pattern to extract fields from BlobStoreQuotaResult
    if (result instanceof BlobStoreQuotaResult(boolean isViolation, String message, String reason)) {
      assertTrue(isViolation, STR."Quota should be violated but got: \{message} (\{reason})");
    }
  }

  @Test
  void missingQuota() {
    when(attributes.get(BlobStoreQuotaSupport.TYPE_KEY, String.class)).thenReturn("non-existent");
    assertThat(service.checkQuota(blobStore), nullValue());
  }

  @Test
  void nullQuotaTypeIsValid() {
    when(attributes.get(BlobStoreQuotaSupport.TYPE_KEY, String.class)).thenReturn(null);
    service.validateSoftQuotaConfig(config);
  }

  @Test
  void emptyStringQuotaTypeFails() {
    when(attributes.get(BlobStoreQuotaSupport.TYPE_KEY, String.class)).thenReturn("");
    assertThrows(ValidationErrorsException.class, () -> service.validateSoftQuotaConfig(config),
        STR."Expected ValidationErrorsException for empty quota type");
  }

  @Test
  void unknownQuotaTypeFails() {
    when(attributes.get(BlobStoreQuotaSupport.TYPE_KEY, String.class)).thenReturn("TOTALLY_FAKE");
    assertThrows(ValidationErrorsException.class, () -> service.validateSoftQuotaConfig(config),
        STR."Expected ValidationErrorsException for unknown quota type");
  }

  @Test
  void knownQuotaTypePasses() {
    when(attributes.get(BlobStoreQuotaSupport.TYPE_KEY, String.class)).thenReturn("passing");
    service.validateSoftQuotaConfig(config);
  }
  
  @Test
  @VirtualThreadTestGroup
  void concurrentQuotaChecking() throws Exception {
    when(attributes.get(BlobStoreQuotaSupport.TYPE_KEY, String.class)).thenReturn("passing");
    
    int threadCount = 1000;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            BlobStoreQuotaResult result = service.checkQuota(blobStore);
            if (result == null || result.isViolation()) {
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
      assertTrue(completed, STR."Timed out waiting for \{threadCount} virtual threads to complete");
      assertTrue(errorCount.get() == 0, 
          STR."Expected 0 errors but got \{errorCount.get()} errors during concurrent quota checking");
    }
  }
}