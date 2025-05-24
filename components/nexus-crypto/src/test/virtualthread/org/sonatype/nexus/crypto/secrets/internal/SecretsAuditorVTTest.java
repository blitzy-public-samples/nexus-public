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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.audit.AuditData;
import org.sonatype.nexus.audit.AuditRecorder;
import org.sonatype.nexus.common.event.EventHelper;
import org.sonatype.nexus.crypto.secrets.ActiveKeyChangeEvent;

import com.google.common.collect.ImmutableMap;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Spy;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Virtual Thread tests for {@link SecretsAuditor} that validate thread safety and event handling
 * under high concurrency with Java 21 Virtual Threads.
 */
@Category(VirtualThreadTestGroup.class)
public class SecretsAuditorVTTest
    extends TestSupport
{
  private static final int THREAD_COUNT = 5000;
  private static final int TIMEOUT_SECONDS = 30;

  @Mock
  private AuditRecorder auditRecorder;

  @Spy
  private SecretsAuditor underTest = new SecretsAuditor();

  @Captor
  private ArgumentCaptor<AuditData> captor;

  @Before
  public void setup() {
    underTest.setAuditRecorder(() -> auditRecorder);
  }

  /**
   * Tests that the SecretsAuditor can handle thousands of concurrent events from virtual threads
   * without thread safety issues or race conditions.
   */
  @Test
  public void testConcurrentEventHandlingWithVirtualThreads() throws Exception {
    when(auditRecorder.isEnabled()).thenReturn(true);

    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Use a CountDownLatch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    
    // Track any errors that occur during execution
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit tasks to the executor
      for (int i = 0; i < THREAD_COUNT; i++) {
        final String keyId = "key-" + UUID.randomUUID();
        final String previousKeyId = "prev-" + UUID.randomUUID();
        final String userId = "user-" + i;
        
        executor.submit(() -> {
          try {
            // Trigger the event handler
            underTest.on(new ActiveKeyChangeEvent(keyId, previousKeyId, userId));
          } catch (Exception e) {
            log.error("Error in virtual thread", e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete or timeout
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify all threads completed successfully
      assertThat("All virtual threads should complete within the timeout", completed, is(true));
      assertThat("No errors should occur during concurrent execution", errorCount.get(), is(0));
      
      // Verify that the audit recorder was called the expected number of times
      verify(auditRecorder, times(THREAD_COUNT)).record(any(AuditData.class));
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that the SecretsAuditor correctly handles audit data when processing events from
   * multiple virtual threads simultaneously.
   */
  @Test
  public void testAuditDataIntegrityWithVirtualThreads() throws Exception {
    when(auditRecorder.isEnabled()).thenReturn(true);

    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Use a smaller number of threads for this test to make verification manageable
    int testThreadCount = 100;
    CountDownLatch latch = new CountDownLatch(testThreadCount);
    
    // Create lists to store the expected key IDs
    List<String> expectedKeyIds = new ArrayList<>(testThreadCount);
    List<String> expectedPrevKeyIds = new ArrayList<>(testThreadCount);
    List<String> expectedUserIds = new ArrayList<>(testThreadCount);
    
    try {
      // Submit tasks to the executor
      for (int i = 0; i < testThreadCount; i++) {
        final String keyId = "key-" + i;
        final String previousKeyId = "prev-" + i;
        final String userId = "user-" + i;
        
        expectedKeyIds.add(keyId);
        expectedPrevKeyIds.add(previousKeyId);
        expectedUserIds.add(userId);
        
        executor.submit(() -> {
          try {
            // Trigger the event handler
            underTest.on(new ActiveKeyChangeEvent(keyId, previousKeyId, userId));
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Capture all audit data records
      verify(auditRecorder, times(testThreadCount)).record(captor.capture());
      
      // Verify that all expected audit data was recorded correctly
      List<AuditData> capturedData = captor.getAllValues();
      assertThat(capturedData.size(), is(testThreadCount));
      
      // Check that each audit record has the correct structure
      for (AuditData data : capturedData) {
        assertThat(data, notNullValue());
        assertThat(data.getContext(), is("system"));
        assertThat(data.getDomain(), is(SecretsAuditor.DOMAIN));
        assertThat(data.getType(), is("changed"));
        
        // Verify the attributes contain a key ID, previous key ID, and user ID
        assertThat(data.getAttributes().containsKey("newKeyId"), is(true));
        assertThat(data.getAttributes().containsKey("previousKeyId"), is(true));
        assertThat(data.getAttributes().containsKey("userId"), is(true));
        
        // Verify the key IDs and user ID are in our expected lists
        String newKeyId = (String) data.getAttributes().get("newKeyId");
        String prevKeyId = (String) data.getAttributes().get("previousKeyId");
        String userId = (String) data.getAttributes().get("userId");
        
        assertThat(expectedKeyIds.contains(newKeyId), is(true));
        assertThat(expectedPrevKeyIds.contains(prevKeyId), is(true));
        assertThat(expectedUserIds.contains(userId), is(true));
      }
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Verifies that replicating events from other nodes are not recorded when processed
   * by virtual threads.
   */
  @Test
  public void testReplicatingEventsIgnoredWithVirtualThreads() throws Exception {
    when(auditRecorder.isEnabled()).thenReturn(true);

    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    int testThreadCount = 100;
    CountDownLatch latch = new CountDownLatch(testThreadCount);
    
    try {
      // Submit tasks to the executor
      for (int i = 0; i < testThreadCount; i++) {
        final String keyId = "key-" + i;
        final String previousKeyId = "prev-" + i;
        final String userId = "user-" + i;
        
        executor.submit(() -> {
          try {
            // Simulate a replicating event
            EventHelper.asReplicating(() -> {
              underTest.on(new ActiveKeyChangeEvent(keyId, previousKeyId, userId));
              return null;
            });
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify that no audit records were created for replicating events
      verify(auditRecorder, never()).record(any());
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Verifies that when auditing is disabled, no events are recorded even with
   * high concurrency from virtual threads.
   */
  @Test
  public void testAuditingDisabledWithVirtualThreads() throws Exception {
    // Configure auditing to be disabled
    when(auditRecorder.isEnabled()).thenReturn(false);

    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    int testThreadCount = 100;
    CountDownLatch latch = new CountDownLatch(testThreadCount);
    
    try {
      // Submit tasks to the executor
      for (int i = 0; i < testThreadCount; i++) {
        final String keyId = "key-" + i;
        final String previousKeyId = "prev-" + i;
        final String userId = "user-" + i;
        
        executor.submit(() -> {
          try {
            // Trigger the event handler
            underTest.on(new ActiveKeyChangeEvent(keyId, previousKeyId, userId));
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify that no audit records were created when auditing is disabled
      verify(auditRecorder, never()).record(any());
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that context propagation works correctly across virtual thread boundaries.
   * This ensures that thread-local variables used by the auditing system are properly maintained.
   */
  @Test
  public void testContextPropagationAcrossVirtualThreads() throws Exception {
    when(auditRecorder.isEnabled()).thenReturn(true);

    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Use a CountDownLatch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(1);
    
    try {
      // Set up a replicating context in the parent thread
      EventHelper.asReplicating(() -> {
        // Submit a task to a virtual thread from within the replicating context
        executor.submit(() -> {
          try {
            // The replicating context should be propagated to the virtual thread
            // So this event should not be recorded
            underTest.on(new ActiveKeyChangeEvent("foo", "bar", "baz"));
          } finally {
            latch.countDown();
          }
        });
        return null;
      });
      
      // Wait for the thread to complete
      latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify that no audit records were created due to the propagated replicating context
      verify(auditRecorder, never()).record(any());
    } finally {
      executor.shutdown();
    }
  }
}