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
package org.sonatype.nexus.internal.capability.storage.datastore;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.capability.CapabilityIdentity;
import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.common.entity.EntityUUID;
import org.sonatype.nexus.internal.capability.storage.CapabilityStorageItemData;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for {@link CapabilityStorageItemEventSupport} with Java 21 features.
 */
public class CapabilityStorageItemEventSupportTest
    extends TestSupport
{
  /**
   * Test that demonstrates the use of pattern matching in the constructor.
   */
  @Test
  public void testPatternMatchingConstructor() {
    // Create a test capability storage item
    CapabilityStorageItemData item = createTestItem();
    
    // Create the event using the constructor with pattern matching
    TestCapabilityEvent event = new TestCapabilityEvent(item);
    
    // Verify the capability ID was correctly extracted
    assertThat(event.getCapabilityId(), is(notNullValue()));
    assertThat(event.getCapabilityId().toString(), is(item.getId().getValue()));
  }
  
  /**
   * Test that demonstrates the use of Virtual Threads for asynchronous event processing.
   */
  @Test
  public void testVirtualThreadAsyncProcessing() throws Exception {
    // Create a test capability storage item
    CapabilityStorageItemData item = createTestItem();
    
    // Create the event
    TestCapabilityEvent event = new TestCapabilityEvent(item);
    
    // Create a latch to wait for the async processing to complete
    CountDownLatch latch = new CountDownLatch(1);
    
    // Process the event asynchronously using a Virtual Thread
    CompletableFuture<Void> future = event.processAsync(() -> {
      // Simulate some processing
      try {
        Thread.sleep(100);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      
      // Signal that processing is complete
      latch.countDown();
    });
    
    // Wait for the processing to complete
    boolean completed = latch.await(1, TimeUnit.SECONDS);
    
    // Verify that the processing completed successfully
    assertThat("Async processing should complete", completed, is(true));
    assertThat("Future should be completed", future.isDone(), is(true));
  }
  
  /**
   * Creates a test capability storage item for use in tests.
   */
  private CapabilityStorageItemData createTestItem() {
    EntityId id = new EntityUUID();
    String type = "test-capability";
    boolean enabled = true;
    String notes = "Test notes";
    Map<String, String> properties = new HashMap<>();
    properties.put("key1", "value1");
    properties.put("key2", "value2");
    
    // Use the factory method from CapabilityStorageItemData
    return CapabilityStorageItemData.of(id, 1, type, enabled, notes, properties);
  }
  
  /**
   * Test implementation of CapabilityStorageItemEventSupport for testing purposes.
   */
  private static class TestCapabilityEvent extends CapabilityStorageItemEventSupport {
    public TestCapabilityEvent(CapabilityStorageItemData item) {
      super(item);
    }
  }
}