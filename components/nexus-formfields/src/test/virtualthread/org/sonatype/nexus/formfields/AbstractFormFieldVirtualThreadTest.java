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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link AbstractFormField} behavior under high concurrency using Java 21 Virtual Threads.
 * 
 * This test ensures that form field properties remain thread-safe when multiple virtual threads
 * simultaneously access and modify field attributes.
 */
@Tag("virtualthread")
public class AbstractFormFieldVirtualThreadTest
{
  private static final String ID = "testId";

  private static final String TYPE = "testField";
  
  private static final int THREAD_COUNT = 1000;
  
  private static final int TIMEOUT_SECONDS = 10;

  private AbstractFormField<String> formField;

  @BeforeEach
  void setUp() {
    formField = new AbstractFormField<String>(ID)
    {
      @Override
      public String getType() {
        return TYPE;
      }
    };
  }

  @Test
  void basicPropertiesAreCorrect() {
    assertThat(formField.getId(), equalTo(ID));
    assertThat(formField.getType(), equalTo(TYPE));
    assertFalse(formField.isRequired());
    assertFalse(formField.isDisabled());
    assertFalse(formField.isReadOnly());
  }

  @Test
  void concurrentAttributeAccess() throws Exception {
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
      
      // Launch multiple virtual threads to concurrently access the attributes map
      for (int i = 0; i < THREAD_COUNT; i++) {
        final int threadNum = i;
        executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            
            // Each thread adds a unique attribute
            String key = "key-" + threadNum;
            String value = "value-" + threadNum;
            formField.withAttribute(key, value);
            
            // Verify the attribute was added correctly
            Map<String, Object> attributes = formField.getAttributes();
            assertThat(attributes, hasEntry(key, value));
            
            return null;
          } finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      assertTrue(completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for virtual threads to complete");
      
      // Verify all attributes were added correctly
      Map<String, Object> attributes = formField.getAttributes();
      assertThat(attributes.size(), is(THREAD_COUNT));
      
      for (int i = 0; i < THREAD_COUNT; i++) {
        String key = "key-" + i;
        String expectedValue = "value-" + i;
        assertThat(attributes, hasEntry(key, expectedValue));
      }
    }
  }

  @Test
  void concurrentPropertyModification() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
      
      // Track the last value set for each property
      ConcurrentHashMap<String, Object> expectedValues = new ConcurrentHashMap<>();
      
      // Launch multiple virtual threads to concurrently modify properties
      for (int i = 0; i < THREAD_COUNT; i++) {
        final int threadNum = i;
        executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            
            // Each thread modifies various properties
            String helpText = "Help-" + threadNum;
            formField.setHelpText(helpText);
            expectedValues.put("helpText", helpText);
            
            String label = "Label-" + threadNum;
            formField.setLabel(label);
            expectedValues.put("label", label);
            
            String regex = "Regex-" + threadNum;
            formField.setRegexValidation(regex);
            expectedValues.put("regex", regex);
            
            // Toggle boolean properties
            boolean required = (threadNum % 2 == 0);
            formField.setRequired(required);
            expectedValues.put("required", required);
            
            boolean disabled = (threadNum % 3 == 0);
            formField.setDisabled(disabled);
            expectedValues.put("disabled", disabled);
            
            boolean readOnly = (threadNum % 5 == 0);
            formField.setReadOnly(readOnly);
            expectedValues.put("readOnly", readOnly);
            
            return null;
          } finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      assertTrue(completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for virtual threads to complete");
      
      // Verify the final property values match the last values set
      assertThat(formField.getHelpText(), equalTo(expectedValues.get("helpText")));
      assertThat(formField.getLabel(), equalTo(expectedValues.get("label")));
      assertThat(formField.getRegexValidation(), equalTo(expectedValues.get("regex")));
      assertThat(formField.isRequired(), equalTo(expectedValues.get("required")));
      assertThat(formField.isDisabled(), equalTo(expectedValues.get("disabled")));
      assertThat(formField.isReadOnly(), equalTo(expectedValues.get("readOnly")));
    }
  }

  @Test
  void attributeMapLazyInitialization() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
      
      // Launch multiple virtual threads to concurrently access the attributes map
      for (int i = 0; i < THREAD_COUNT; i++) {
        executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            
            // Each thread gets the attributes map, which should trigger lazy initialization if needed
            Map<String, Object> attributes = formField.getAttributes();
            assertThat(attributes, notNullValue());
            
            // Add a unique attribute to verify the map is working
            String uniqueKey = UUID.randomUUID().toString();
            attributes.put(uniqueKey, "value");
            assertThat(attributes, hasKey(uniqueKey));
            
            return null;
          } finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      assertTrue(completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for virtual threads to complete");
      
      // Verify the attributes map was initialized and contains entries
      Map<String, Object> attributes = formField.getAttributes();
      assertThat(attributes, notNullValue());
      assertThat(attributes.size(), is(THREAD_COUNT));
    }
  }
}