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
package org.sonatype.nexus.formfields;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import org.junit.experimental.categories.Category;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests {@link AbstractFormField} behavior under high concurrency using Java 21 Virtual Threads.
 * Validates that form field properties remain thread-safe when multiple virtual threads
 * simultaneously access and modify field attributes.
 */
@Category(VirtualThreadTestGroup.class)
public class AbstractFormFieldVirtualThreadTest
{
  private static final String ID = "testId";

  private static final String TYPE = "testField";
  
  private static final int THREAD_COUNT = 1000;

  private AbstractFormField<String> formField;

  @BeforeEach
  public void setUp() {
    formField = new AbstractFormField<String>(ID)
    {
      @Override
      public String getType() {
        return TYPE;
      }
    };
  }

  /**
   * Verifies that the form field ID and type are correctly accessible from multiple virtual threads.
   */
  @Test
  public void testConcurrentIdAndTypeAccess() throws Exception {
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < THREAD_COUNT; i++) {
        executor.submit(() -> {
          try {
            // Verify ID and type are correctly accessible
            String id = formField.getId();
            String type = formField.getType();
            
            if (!ID.equals(id) || !TYPE.equals(type)) {
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
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify no errors occurred
      assertThat("No errors should occur during concurrent access", errorCount.get(), is(0));
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Verifies that form field properties can be safely modified from multiple virtual threads.
   */
  @Test
  public void testConcurrentPropertyModification() throws Exception {
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < THREAD_COUNT; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Set different properties based on thread index
            if (index % 4 == 0) {
              formField.setRequired(true);
              formField.setHelpText("Help text " + index);
            } else if (index % 4 == 1) {
              formField.setDisabled(true);
              formField.setLabel("Label " + index);
            } else if (index % 4 == 2) {
              formField.setReadOnly(true);
              formField.setRegexValidation("[a-z]+");
            } else {
              formField.setInitialValue("Value " + index);
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify no errors occurred
      assertThat("No errors should occur during concurrent property modification", errorCount.get(), is(0));
      
      // Verify that properties were modified
      assertThat(formField.isRequired() || formField.isDisabled() || formField.isReadOnly(), is(true));
      assertThat(formField.getInitialValue(), notNullValue());
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Verifies that the attribute map can be safely modified from multiple virtual threads.
   * This specifically tests the thread safety of the getAttributes() and withAttribute() methods.
   */
  @Test
  public void testConcurrentAttributeMapModification() throws Exception {
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < THREAD_COUNT; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Add an attribute with a unique key
            String key = "attr" + index;
            String value = "value" + index;
            formField.withAttribute(key, value);
            
            // Verify the attribute was added correctly
            Map<String, Object> attributes = formField.getAttributes();
            if (!value.equals(attributes.get(key))) {
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
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify no errors occurred
      assertThat("No errors should occur during concurrent attribute map modification", errorCount.get(), is(0));
      
      // Verify that attributes were added correctly (check a few random ones)
      Map<String, Object> attributes = formField.getAttributes();
      assertThat(attributes.size(), is(THREAD_COUNT));
      assertThat(attributes, hasEntry("attr0", "value0"));
      assertThat(attributes, hasEntry("attr499", "value499"));
      assertThat(attributes, hasEntry("attr999", "value999"));
    } finally {
      executor.shutdown();
    }
  }
}