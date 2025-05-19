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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.lang.StringTemplate.STR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link BlobStoreResource} using Java 21 Virtual Threads to validate
 * concurrent performance and behavior under load.
 */
@ExtendWith(MockitoExtension.class)
public class BlobStoreResourceVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_THREADS = 50;
  private static final int TIMEOUT_SECONDS = 5;

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
  public void setup() {
    when(quotaService.checkQuota(noQuota)).thenReturn(null);
    when(quotaService.checkQuota(passing)).thenReturn(new BlobStoreQuotaResult(false, "passing", "test"));
    when(quotaService.checkQuota(failing)).thenReturn(new BlobStoreQuotaResult(true, "failing", "test"));

    when(manager.get(eq("passing"))).thenReturn(passing);
    when(manager.get(eq("noQuota"))).thenReturn(noQuota);
    when(manager.get(eq("failing"))).thenReturn(failing);

    Map<String, ConnectionChecker> connectionCheckers = new HashMap<>();
    connectionCheckers.put("azure cloud storage", connectionChecker);

    resource = new BlobStoreResource(manager, store, quotaService, connectionCheckers);
  }

  /**
   * Tests the quotaStatus endpoint with multiple concurrent virtual threads.
   * Verifies that the endpoint correctly handles concurrent requests and returns
   * the expected results for different blob store types.
   */
  @Test
  @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
  public void testConcurrentQuotaStatusWithVirtualThreads() throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS * 3); // 3 types of blob stores
    
    AtomicInteger passingCount = new AtomicInteger(0);
    AtomicInteger failingCount = new AtomicInteger(0);
    AtomicInteger noQuotaCount = new AtomicInteger(0);
    AtomicReference<Exception> threadException = new AtomicReference<>();

    // Create virtual threads for "passing" blob store
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      Thread.startVirtualThread(() -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          BlobStoreQuotaResultXO result = resource.quotaStatus("passing");
          assertFalse(result.getIsViolation());
          assertEquals("passing", result.getBlobStoreName());
          passingCount.incrementAndGet();
        }
        catch (Exception e) {
          threadException.set(e);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }

    // Create virtual threads for "failing" blob store
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      Thread.startVirtualThread(() -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          BlobStoreQuotaResultXO result = resource.quotaStatus("failing");
          assertTrue(result.getIsViolation());
          assertEquals("failing", result.getBlobStoreName());
          failingCount.incrementAndGet();
        }
        catch (Exception e) {
          threadException.set(e);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }

    // Create virtual threads for "noQuota" blob store
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      Thread.startVirtualThread(() -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          BlobStoreQuotaResultXO result = resource.quotaStatus("noQuota");
          assertFalse(result.getIsViolation());
          assertEquals("noQuota", result.getBlobStoreName());
          noQuotaCount.incrementAndGet();
        }
        catch (Exception e) {
          threadException.set(e);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }

    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(completed, STR."Timed out waiting for threads to complete. Completed: \{completionLatch.getCount()} of \{CONCURRENT_THREADS * 3}");
    
    // Check if any thread had an exception
    if (threadException.get() != null) {
      throw threadException.get();
    }
    
    // Verify all threads completed successfully
    assertEquals(CONCURRENT_THREADS, passingCount.get(), "Not all 'passing' threads completed successfully");
    assertEquals(CONCURRENT_THREADS, failingCount.get(), "Not all 'failing' threads completed successfully");
    assertEquals(CONCURRENT_THREADS, noQuotaCount.get(), "Not all 'noQuota' threads completed successfully");
  }

  /**
   * Tests the verifyConnection endpoint with multiple concurrent virtual threads.
   * Verifies that the endpoint correctly handles concurrent connection verification requests.
   */
  @Test
  @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
  public void testConcurrentVerifyConnectionWithVirtualThreads() throws Exception {
    when(connectionChecker.verifyConnection(any(String.class), any(Map.class))).thenReturn(true);
    
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicReference<Exception> threadException = new AtomicReference<>();

    // Create virtual threads for connection verification
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      Thread.startVirtualThread(() -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          resource.verifyConnection(getBlobStoreConnectionXO());
          successCount.incrementAndGet();
        }
        catch (Exception e) {
          threadException.set(e);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }

    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(completed, STR."Timed out waiting for threads to complete. Completed: \{completionLatch.getCount()} of \{CONCURRENT_THREADS}");
    
    // Check if any thread had an exception
    if (threadException.get() != null) {
      throw threadException.get();
    }
    
    // Verify all threads completed successfully
    assertEquals(CONCURRENT_THREADS, successCount.get(), "Not all connection verification threads completed successfully");
  }

  /**
   * Tests the verifyConnection endpoint with multiple concurrent virtual threads when connections fail.
   * Uses pattern matching to handle different exception types.
   */
  @Test
  @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
  public void testConcurrentVerifyConnectionFailureWithVirtualThreads() throws Exception {
    when(connectionChecker.verifyConnection(any(String.class), any(Map.class)))
        .thenThrow(new BlobStoreConnectionException("Fake BlobStoreConnectionException"));
    
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger exceptionCount = new AtomicInteger(0);
    AtomicReference<Exception> unexpectedException = new AtomicReference<>();

    // Create virtual threads for connection verification
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      Thread.startVirtualThread(() -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          resource.verifyConnection(getBlobStoreConnectionXO());
        }
        catch (Exception e) {
          // Using pattern matching for exception handling
          switch (e) {
            case WebApplicationException webEx -> {
              assertEquals(400, webEx.getResponse().getStatus());
              assertEquals("Fake BlobStoreConnectionException", webEx.getResponse().getEntity());
              exceptionCount.incrementAndGet();
            }
            case Exception otherEx -> unexpectedException.set(otherEx);
          }
        }
        finally {
          completionLatch.countDown();
        }
      });
    }

    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(completed, STR."Timed out waiting for threads to complete. Completed: \{completionLatch.getCount()} of \{CONCURRENT_THREADS}");
    
    // Check if any thread had an unexpected exception
    if (unexpectedException.get() != null) {
      throw unexpectedException.get();
    }
    
    // Verify all threads received the expected exception
    assertEquals(CONCURRENT_THREADS, exceptionCount.get(), 
        STR."Expected \{CONCURRENT_THREADS} WebApplicationExceptions but got \{exceptionCount.get()}");
  }

  /**
   * Compares performance between platform threads and virtual threads for connection verification.
   */
  @Test
  @Timeout(value = TIMEOUT_SECONDS * 2, unit = TimeUnit.SECONDS)
  public void compareThreadPerformance() throws Exception {
    when(connectionChecker.verifyConnection(any(String.class), any(Map.class))).thenReturn(true);
    
    // Test with platform threads
    long platformThreadTime = measureExecutionTime(() -> {
      runWithPlatformThreads(CONCURRENT_THREADS, () -> {
        resource.verifyConnection(getBlobStoreConnectionXO());
        return null;
      });
    });
    
    // Test with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      runWithVirtualThreads(CONCURRENT_THREADS, () -> {
        resource.verifyConnection(getBlobStoreConnectionXO());
        return null;
      });
    });
    
    log.info(STR."Performance comparison: Platform threads: \{platformThreadTime}ms, Virtual threads: \{virtualThreadTime}ms");
    
    // We don't assert on specific times as they can vary by environment,
    // but we log the results for analysis
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

  private <T> void runWithPlatformThreads(int threadCount, java.util.concurrent.Callable<T> task) throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicReference<Exception> threadException = new AtomicReference<>();

    // Create platform threads
    for (int i = 0; i < threadCount; i++) {
      Thread thread = new Thread(() -> {
        try {
          startLatch.await();
          task.call();
        }
        catch (Exception e) {
          threadException.set(e);
        }
        finally {
          completionLatch.countDown();
        }
      });
      thread.start();
    }

    startLatch.countDown();
    completionLatch.await();
    
    if (threadException.get() != null) {
      throw threadException.get();
    }
  }

  private <T> void runWithVirtualThreads(int threadCount, java.util.concurrent.Callable<T> task) throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicReference<Exception> threadException = new AtomicReference<>();

    // Create virtual threads
    for (int i = 0; i < threadCount; i++) {
      Thread.startVirtualThread(() -> {
        try {
          startLatch.await();
          task.call();
        }
        catch (Exception e) {
          threadException.set(e);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }

    startLatch.countDown();
    completionLatch.await();
    
    if (threadException.get() != null) {
      throw threadException.get();
    }
  }

  private long measureExecutionTime(Runnable task) throws Exception {
    long startTime = System.currentTimeMillis();
    task.run();
    return System.currentTimeMillis() - startTime;
  }
}