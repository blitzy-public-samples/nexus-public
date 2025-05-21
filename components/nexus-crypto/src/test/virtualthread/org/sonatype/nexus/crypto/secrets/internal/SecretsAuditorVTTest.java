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
package org.sonatype.nexus.crypto.secrets.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.audit.AuditData;
import org.sonatype.nexus.audit.AuditRecorder;
import org.sonatype.nexus.crypto.secrets.ActiveKeyChangeEvent;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Spy;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Virtual Thread tests for {@link SecretsAuditor} that validate thread safety and event handling
 * under high concurrency with Java 21 Virtual Threads.
 */
public class SecretsAuditorVTTest
    extends TestSupport
{
  private static final int CONCURRENT_THREADS = 1000;
  private static final int TIMEOUT_SECONDS = 10;

  @Mock
  private AuditRecorder auditRecorder;

  @Spy
  private SecretsAuditor underTest = new SecretsAuditor();

  @Captor
  private ArgumentCaptor<AuditData> captor;

  @Before
  public void setup() {
    underTest.setAuditRecorder(() -> auditRecorder);
    when(auditRecorder.isEnabled()).thenReturn(true);
  }

  /**
   * Tests that the SecretsAuditor can handle multiple concurrent events from virtual threads
   * without missing any events or experiencing thread safety issues.
   */
  @Test
  public void testConcurrentEventsWithVirtualThreads() throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger counter = new AtomicInteger(0);
    
    // Create and start virtual threads to trigger events concurrently
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        final int threadNum = i;
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready before starting
            startLatch.await();
            
            // Generate a unique event for this thread
            String newKeyId = "key-" + threadNum;
            String previousKeyId = "prev-" + threadNum;
            String userId = "user-" + threadNum;
            
            // Trigger the event
            underTest.on(new ActiveKeyChangeEvent(newKeyId, previousKeyId, userId));
            
            // Count successful event processing
            counter.incrementAndGet();
          }
          catch (Exception e) {
            log.error("Error in virtual thread {}", threadNum, e);
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete or timeout
      boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat("All virtual threads should complete in time", completed, is(true));
    }
    
    // Verify that all events were processed
    assertThat("All events should be processed", counter.get(), equalTo(CONCURRENT_THREADS));
    
    // Verify that the audit recorder received the correct number of events
    verify(auditRecorder, times(CONCURRENT_THREADS)).record(any(AuditData.class));
  }

  /**
   * Tests that the SecretsAuditor correctly records all event data when processing
   * events from multiple virtual threads simultaneously.
   */
  @Test
  public void testEventDataIntegrityWithVirtualThreads() throws Exception {
    // Use a concurrent set to track all the key IDs that were processed
    Set<String> processedKeyIds = ConcurrentHashMap.newKeySet();
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
    
    // Capture all audit data records
    List<AuditData> capturedData = new ArrayList<>();
    when(auditRecorder.record(captor.capture())).thenAnswer(invocation -> {
      AuditData data = invocation.getArgument(0);
      capturedData.add(data);
      String keyId = (String) data.getAttributes().get("newKeyId");
      processedKeyIds.add(keyId);
      completionLatch.countDown();
      return null;
    });
    
    // Create and start virtual threads to trigger events concurrently
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        final int threadNum = i;
        executor.submit(() -> {
          // Generate a unique event for this thread
          String newKeyId = "key-" + threadNum;
          String previousKeyId = "prev-" + threadNum;
          String userId = "user-" + threadNum;
          
          // Trigger the event
          underTest.on(new ActiveKeyChangeEvent(newKeyId, previousKeyId, userId));
        });
      }
      
      // Wait for all events to be processed or timeout
      boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat("All events should be processed in time", completed, is(true));
    }
    
    // Verify that all events were recorded correctly
    assertThat("All audit records should be captured", capturedData, hasSize(CONCURRENT_THREADS));
    assertThat("All unique key IDs should be processed", processedKeyIds.size(), equalTo(CONCURRENT_THREADS));
    
    // Verify that each key ID was processed exactly once (no duplicates or missing events)
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      String expectedKeyId = "key-" + i;
      assertThat("Key ID should be processed: " + expectedKeyId, 
          processedKeyIds.contains(expectedKeyId), is(true));
    }
  }

  /**
   * Tests that the SecretsAuditor can handle a high volume of events from virtual threads
   * without experiencing performance degradation or resource leaks.
   */
  @Test
  public void testHighVolumeEventProcessingWithVirtualThreads() throws Exception {
    final int HIGH_VOLUME = 10000; // 10,000 concurrent events
    CountDownLatch completionLatch = new CountDownLatch(HIGH_VOLUME);
    AtomicInteger successCounter = new AtomicInteger(0);
    
    // Configure mock to count down latch when record is called
    when(auditRecorder.record(any(AuditData.class))).thenAnswer(invocation -> {
      successCounter.incrementAndGet();
      completionLatch.countDown();
      return null;
    });
    
    long startTime = System.currentTimeMillis();
    
    // Create and start virtual threads to trigger a high volume of events
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < HIGH_VOLUME; i++) {
        final int threadNum = i;
        executor.submit(() -> {
          String newKeyId = "high-volume-key-" + threadNum;
          String previousKeyId = "high-volume-prev-" + threadNum;
          String userId = "high-volume-user-" + threadNum;
          
          underTest.on(new ActiveKeyChangeEvent(newKeyId, previousKeyId, userId));
        });
      }
      
      // Wait for all events to be processed or timeout
      boolean completed = completionLatch.await(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS);
      assertThat("All high-volume events should be processed in time", completed, is(true));
    }
    
    long duration = System.currentTimeMillis() - startTime;
    log.info("Processed {} events in {} ms", HIGH_VOLUME, duration);
    
    // Verify that all events were processed successfully
    assertThat("All high-volume events should be processed successfully", 
        successCounter.get(), equalTo(HIGH_VOLUME));
    
    // Verify with the mock that the correct number of events were recorded
    verify(auditRecorder, times(HIGH_VOLUME)).record(any(AuditData.class));
  }
}