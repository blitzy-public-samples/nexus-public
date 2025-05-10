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
package org.sonatype.nexus.blobstore.rest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.ws.rs.WebApplicationException;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.ConnectionChecker;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConnectionException;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaResult;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaService;
import org.sonatype.nexus.repository.blobstore.BlobStoreConfigurationStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlobStoreResourceTest
    extends TestSupport
{
  @Mock
  BlobStoreManager manager;

  @Mock
  BlobStoreConfigurationStore store;

  @Mock
  BlobStoreQuotaService quotaService;

  @Mock
  ConnectionChecker connectionChecker;

  @Mock
  BlobStore noQuota;

  @Mock
  BlobStore passing;

  @Mock
  BlobStore failing;

  BlobStoreResource resource;

  @BeforeEach
  void setup() {
    // Using lenient() for mocks that might not be used in all tests
    lenient().when(quotaService.checkQuota(noQuota)).thenReturn(null);
    lenient().when(quotaService.checkQuota(passing)).thenReturn(new BlobStoreQuotaResult(false, "passing", "test"));
    lenient().when(quotaService.checkQuota(failing)).thenReturn(new BlobStoreQuotaResult(true, "failing", "test"));

    lenient().when(manager.get(eq("passing"))).thenReturn(passing);
    lenient().when(manager.get(eq("noQuota"))).thenReturn(noQuota);
    lenient().when(manager.get(eq("failing"))).thenReturn(failing);

    Map<String, ConnectionChecker> connectionCheckers = new HashMap<>();
    connectionCheckers.put("azure cloud storage", connectionChecker);

    resource = new BlobStoreResource(manager, store, quotaService, connectionCheckers);
  }

  @Test
  void quotaStatusPassingReturnsNonViolation() {
    BlobStoreQuotaResultXO resultXO = resource.quotaStatus("passing");
    assertFalse(resultXO.getIsViolation());
    assertEquals("passing", resultXO.getBlobStoreName());
  }

  @Test
  void quotaStatusFailingReturnsViolation() {
    BlobStoreQuotaResultXO resultXO = resource.quotaStatus("failing");
    assertTrue(resultXO.getIsViolation());
    assertEquals("failing", resultXO.getBlobStoreName());
  }

  @Test
  void quotaStatusWithNoQuotaReturnsNonViolation() {
    BlobStoreQuotaResultXO resultXO = resource.quotaStatus("noQuota");
    assertFalse(resultXO.getIsViolation());
    assertEquals("noQuota", resultXO.getBlobStoreName());
  }

  @Test
  void verifyConnectionSucceedsWithValidConnection() {
    when(connectionChecker.verifyConnection(any(String.class), any(Map.class))).thenReturn(true);
    resource.verifyConnection(getBlobStoreConnectionXO());
  }

  @Test
  void verifyConnectionThrowsWebApplicationExceptionOnRuntimeException() {
    when(connectionChecker.verifyConnection(any(String.class), any(Map.class)))
        .thenThrow(new RuntimeException("Fake unsuccessful connection Exception"));
    assertThrows(WebApplicationException.class, () -> resource.verifyConnection(getBlobStoreConnectionXO()));
  }

  @Test
  void verifyConnectionThrowsWebApplicationExceptionWithCorrectStatusOnBlobStoreConnectionException() {
    when(connectionChecker.verifyConnection(any(String.class), any(Map.class)))
        .thenThrow(new BlobStoreConnectionException("Fake BlobStoreConnectionException"));
    WebApplicationException e =
        assertThrows(WebApplicationException.class, () -> resource.verifyConnection(getBlobStoreConnectionXO()));
    assertEquals(400, e.getResponse().getStatus());
    assertEquals("Fake BlobStoreConnectionException", e.getResponse().getEntity());
  }
  
  @Test
  void concurrentQuotaStatusRequestsSucceedWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    List<BlobStoreQuotaResultXO> results = new ArrayList<>();
    
    try {
      // Submit multiple concurrent quota status requests using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final String blobStoreName = i % 3 == 0 ? "passing" : (i % 3 == 1 ? "failing" : "noQuota");
        
        executor.submit(() -> {
          try {
            BlobStoreQuotaResultXO result = resource.quotaStatus(blobStoreName);
            synchronized (results) {
              results.add(result);
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      assertTrue(latch.await(10, TimeUnit.SECONDS), "Timed out waiting for concurrent quota status requests");
      
      // Verify results
      assertEquals(0, errorCount.get(), "Some quota status requests failed");
      assertEquals(taskCount, results.size(), "Not all quota status requests returned results");
      
      // Verify the correct distribution of results
      long passingCount = results.stream().filter(r -> "passing".equals(r.getBlobStoreName())).count();
      long failingCount = results.stream().filter(r -> "failing".equals(r.getBlobStoreName())).count();
      long noQuotaCount = results.stream().filter(r -> "noQuota".equals(r.getBlobStoreName())).count();
      
      assertEquals(taskCount / 3, passingCount, "Incorrect number of 'passing' results");
      assertEquals(taskCount / 3, failingCount, "Incorrect number of 'failing' results");
      assertEquals(taskCount / 3, noQuotaCount, "Incorrect number of 'noQuota' results");
    } finally {
      executor.shutdown();
    }
  }
  
  @Test
  void concurrentConnectionVerificationsSucceedWithVirtualThreads() throws Exception {
    // Setup connection checker to return true
    when(connectionChecker.verifyConnection(any(String.class), any(Map.class))).thenReturn(true);
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 50;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicReference<Exception> lastException = new AtomicReference<>();
    
    try {
      // Submit multiple concurrent connection verification requests using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            resource.verifyConnection(getBlobStoreConnectionXO());
          } catch (Exception e) {
            errorCount.incrementAndGet();
            lastException.set(e);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      assertTrue(latch.await(10, TimeUnit.SECONDS), "Timed out waiting for concurrent connection verifications");
      
      // Verify results
      assertEquals(0, errorCount.get(), 
          "Some connection verifications failed: " + 
          (lastException.get() != null ? lastException.get().getMessage() : "unknown error"));
    } finally {
      executor.shutdown();
    }
  }

  private BlobStoreConnectionXO getBlobStoreConnectionXO() {
    Map<String, Object> connectionDetails = new HashMap<>();
    connectionDetails.put("accountName", "some account name");
    connectionDetails.put("accountKey", "some account key");
    connectionDetails.put("containerName", "some container name");
    Map<String, Map<String, Object>> attributes = new HashMap<>();
    attributes.put("azure cloud storage", connectionDetails);
    BlobStoreConnectionXO blobStoreConnectionXO = new BlobStoreConnectionXO();
    blobStoreConnectionXO.setName("blobstoreName");
    blobStoreConnectionXO.setType("azure cloud storage");
    blobStoreConnectionXO.setAttributes(attributes);
    return blobStoreConnectionXO;
  }
}
