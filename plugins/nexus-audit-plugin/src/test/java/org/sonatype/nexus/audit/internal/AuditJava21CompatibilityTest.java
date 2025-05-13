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
package org.sonatype.nexus.audit.internal;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.audit.AuditData;
import org.sonatype.nexus.audit.AuditDataRecordedEvent;
import org.sonatype.nexus.audit.InitiatorProvider;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.common.node.NodeAccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests to explicitly validate Java 21 compatibility and Virtual Thread concurrency for the Nexus Audit plugin.
 * 
 * This test ensures that core audit operations (event recording, DTO serialization) execute correctly
 * on Java 21 and under virtual thread execution.
 */
@ExtendWith(MockitoExtension.class)
@Tag("java21")
public class AuditJava21CompatibilityTest
    extends TestSupport
{
  private static final String TEST_NODE_ID = "test-node";
  private static final String TEST_INITIATOR = "test-initiator";
  
  @Mock
  private EventManager eventManager;
  
  @Mock
  private NodeAccess nodeAccess;
  
  @Mock
  private InitiatorProvider initiatorProvider;
  
  @Captor
  private ArgumentCaptor<AuditDataRecordedEvent> eventCaptor;
  
  private AuditRecorderImpl underTest;
  
  @BeforeEach
  void setUp() {
    // Set up the mocks with predictable behavior
    when(nodeAccess.getId()).thenReturn(TEST_NODE_ID);
    when(initiatorProvider.get()).thenReturn(TEST_INITIATOR);
    
    // Create and enable the recorder
    underTest = new AuditRecorderImpl(eventManager, nodeAccess, initiatorProvider);
    underTest.setEnabled(true);
  }
  
  /**
   * Tests that audit recording works correctly when executed on a Virtual Thread.
   * 
   * This test validates that the audit subsystem can properly record events when running
   * on Java 21's Virtual Threads, ensuring compatibility with the new concurrency model.
   */
  @Test
  @DisplayName("Audit recording should work correctly on Virtual Threads")
  void auditRecordingWorksOnVirtualThreads() throws Exception {
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create a latch to wait for the virtual thread to complete
      CountDownLatch latch = new CountDownLatch(1);
      AtomicBoolean success = new AtomicBoolean(false);
      
      // Execute audit recording on a virtual thread
      executor.submit(() -> {
        try {
          // Create test audit data
          AuditData data = new AuditData();
          data.setDomain("test-domain");
          data.setType("test-type");
          data.setContext("test-context");
          
          // Add some attributes
          Map<String, Object> attributes = new HashMap<>();
          attributes.put("key1", "value1");
          attributes.put("key2", 123);
          data.setAttributes(attributes);
          
          // Record the audit event
          underTest.record(data);
          
          // Mark success
          success.set(true);
        } finally {
          latch.countDown();
        }
      });
      
      // Wait for the virtual thread to complete (with timeout)
      assertThat("Virtual thread execution timed out", 
          latch.await(5, TimeUnit.SECONDS), is(true));
      
      // Verify the operation was successful
      assertThat("Audit recording failed on virtual thread", 
          success.get(), is(true));
      
      // Verify the event was properly recorded and dispatched
      verify(eventManager).post(eventCaptor.capture());
      AuditDataRecordedEvent event = eventCaptor.getValue();
      assertThat(event, notNullValue());
      
      // Verify the event data
      AuditData capturedData = event.getData();
      assertThat(capturedData.getDomain(), is("test-domain"));
      assertThat(capturedData.getType(), is("test-type"));
      assertThat(capturedData.getContext(), is("test-context"));
      assertThat(capturedData.getNodeId(), is(TEST_NODE_ID));
      assertThat(capturedData.getInitiator(), is(TEST_INITIATOR));
      assertThat(capturedData.getAttributes().get("key1"), is("value1"));
      assertThat(capturedData.getAttributes().get("key2"), is(123));
      assertThat(capturedData.getTimestamp(), notNullValue());
    }
  }
  
  /**
   * Tests that AuditDTO serialization works correctly when executed on a Virtual Thread.
   * 
   * This test validates that the AuditDTO can properly serialize audit data to JSON
   * when running on Java 21's Virtual Threads.
   */
  @Test
  @DisplayName("AuditDTO serialization should work correctly on Virtual Threads")
  void auditDtoSerializationWorksOnVirtualThreads() throws Exception {
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create a latch to wait for the virtual thread to complete
      CountDownLatch latch = new CountDownLatch(1);
      AtomicBoolean success = new AtomicBoolean(false);
      
      // Execute AuditDTO serialization on a virtual thread
      executor.submit(() -> {
        try {
          // Create test audit data
          AuditData data = new AuditData();
          data.setTimestamp(new Date());
          data.setNodeId(TEST_NODE_ID);
          data.setInitiator(TEST_INITIATOR);
          data.setDomain("test-domain");
          data.setType("test-type");
          data.setContext("test-context");
          
          // Add some attributes
          Map<String, Object> attributes = new HashMap<>();
          attributes.put("key1", "value1");
          attributes.put("key2", 123);
          data.setAttributes(attributes);
          
          // Create AuditDTO and serialize to JSON
          AuditDTO dto = new AuditDTO(data);
          String json = dto.toString();
          
          // Verify JSON is not empty
          success.set(json != null && !json.isEmpty());
        } finally {
          latch.countDown();
        }
      });
      
      // Wait for the virtual thread to complete (with timeout)
      assertThat("Virtual thread execution timed out", 
          latch.await(5, TimeUnit.SECONDS), is(true));
      
      // Verify the operation was successful
      assertThat("AuditDTO serialization failed on virtual thread", 
          success.get(), is(true));
    }
  }
  
  /**
   * Tests concurrent audit recording using multiple Virtual Threads.
   * 
   * This test validates that the audit subsystem can handle concurrent audit recording
   * from multiple Virtual Threads without errors, ensuring thread safety and scalability
   * with Java 21's concurrency model.
   */
  @Test
  @DisplayName("Concurrent audit recording should work correctly with multiple Virtual Threads")
  void concurrentAuditRecordingWithVirtualThreads() throws Exception {
    final int threadCount = 100;
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create a latch to wait for all virtual threads to complete
      CountDownLatch latch = new CountDownLatch(threadCount);
      AtomicBoolean anyFailure = new AtomicBoolean(false);
      
      // Submit multiple concurrent audit recording tasks
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create test audit data with unique values
            AuditData data = new AuditData();
            data.setDomain("test-domain-" + index);
            data.setType("test-type-" + index);
            data.setContext("test-context-" + index);
            
            // Record the audit event
            underTest.record(data);
          } catch (Exception e) {
            log.error("Error in virtual thread {}", index, e);
            anyFailure.set(true);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all virtual threads to complete (with timeout)
      assertThat("Virtual thread execution timed out", 
          latch.await(10, TimeUnit.SECONDS), is(true));
      
      // Verify no failures occurred
      assertThat("One or more audit recording operations failed on virtual threads", 
          anyFailure.get(), is(false));
    }
  }
}