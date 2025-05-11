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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.audit.AuditData;
import org.sonatype.nexus.audit.InitiatorProvider;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.common.node.NodeAccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.when;

/**
 * Tests to explicitly validate Java 21 runtime compatibility and Virtual Thread concurrency
 * for the Nexus Audit plugin.
 *
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
@Tag("java21")
public class AuditJava21CompatibilityTest
    extends TestSupport
{
  private static final String TEST_NODE_ID = "test-node";
  private static final String TEST_INITIATOR = "test-user/127.0.0.1";
  private static final String TEST_DOMAIN = "test-domain";
  private static final String TEST_TYPE = "test-type";
  private static final String TEST_CONTEXT = "test-context";

  @Mock
  private EventManager eventManager;

  @Mock
  private NodeAccess nodeAccess;

  @Mock
  private InitiatorProvider initiatorProvider;

  private AuditRecorderImpl underTest;

  @BeforeEach
  void setUp() {
    when(nodeAccess.getId()).thenReturn(TEST_NODE_ID);
    when(initiatorProvider.get()).thenReturn(TEST_INITIATOR);

    underTest = new AuditRecorderImpl(eventManager, nodeAccess, initiatorProvider);
    underTest.setEnabled(true);
  }

  /**
   * Validates that audit event recording works correctly when executed using Java 21 Virtual Threads.
   * This test creates multiple virtual threads that concurrently record audit events and verifies
   * that all operations complete successfully without errors.
   */
  @Test
  @DisplayName("Audit recording should work correctly with Java 21 Virtual Threads")
  void auditRecordingWorksWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicReference<Exception> firstException = new AtomicReference<>();
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create and record an audit event
            AuditData data = createTestAuditData(index);
            underTest.record(data);
          } catch (Exception e) {
            errorCount.incrementAndGet();
            firstException.compareAndSet(null, e);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("All tasks should complete within timeout", completed, is(true));
      assertThat("No errors should occur during concurrent audit recording", errorCount.get(), is(0));
      
      if (firstException.get() != null) {
        throw new AssertionError("Exception during virtual thread execution", firstException.get());
      }
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Validates that AuditDTO JSON serialization works correctly when executed using Java 21 Virtual Threads.
   * This test creates multiple virtual threads that concurrently serialize audit data to JSON and verifies
   * that all operations complete successfully without errors.
   */
  @Test
  @DisplayName("AuditDTO serialization should work correctly with Java 21 Virtual Threads")
  void auditDtoSerializationWorksWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicReference<Exception> firstException = new AtomicReference<>();
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create audit data and serialize it to JSON using AuditDTO
            AuditData data = createTestAuditData(index);
            AuditDTO dto = new AuditDTO(data);
            String json = dto.toString();
            
            // Verify the serialized JSON is not null
            assertThat("Serialized JSON should not be null", json, notNullValue());
          } catch (Exception e) {
            errorCount.incrementAndGet();
            firstException.compareAndSet(null, e);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("All tasks should complete within timeout", completed, is(true));
      assertThat("No errors should occur during concurrent DTO serialization", errorCount.get(), is(0));
      
      if (firstException.get() != null) {
        throw new AssertionError("Exception during virtual thread execution", firstException.get());
      }
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Helper method to create test audit data with unique attributes based on the index.
   */
  private AuditData createTestAuditData(int index) {
    AuditData data = new AuditData();
    data.setDomain(TEST_DOMAIN);
    data.setType(TEST_TYPE);
    data.setContext(TEST_CONTEXT + "-" + index);
    
    // Add some attributes with unique values
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("index", index);
    attributes.put("uuid", UUID.randomUUID().toString());
    attributes.put("timestamp", new Date().getTime());
    data.setAttributes(attributes);
    
    return data;
  }
}