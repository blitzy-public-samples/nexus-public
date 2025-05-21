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
package org.sonatype.nexus.crypto.secrets;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link EncryptedSecret} using Java 21 Virtual Threads to validate thread safety
 * of PHC string serialization and deserialization operations.
 */
public class VirtualThreadEncryptedSecretTest
{
  private static final int THREAD_COUNT = 1000;
  private static final int OPERATIONS_PER_THREAD = 100;
  private static final int TIMEOUT_SECONDS = 30;

  /**
   * Tests concurrent parsing and formatting of PHC strings using virtual threads.
   * This ensures that the EncryptedSecret class is thread-safe when used with
   * high concurrency loads typical in a production environment utilizing virtual threads.
   */
  @Test
  public void testConcurrentPhcOperationsWithVirtualThreads() throws Exception {
    // Create a set of test secrets with different configurations
    List<EncryptedSecret> testSecrets = createTestSecrets();
    
    // Track errors that occur during concurrent operations
    Map<Integer, Throwable> errors = new ConcurrentHashMap<>();
    
    // Track completion of operations
    AtomicInteger completedOperations = new AtomicInteger(0);
    
    // Latch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    
    // Create and start virtual threads using the virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < THREAD_COUNT; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try {
            for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
              // Select a test secret based on the operation index
              EncryptedSecret secret = testSecrets.get(j % testSecrets.size());
              
              // Convert to PHC string
              String phcString = secret.toPhcString();
              assertNotNull(phcString, "PHC string should not be null");
              
              // Parse back to EncryptedSecret
              EncryptedSecret parsedSecret = EncryptedSecret.parse(phcString);
              assertNotNull(parsedSecret, "Parsed secret should not be null");
              
              // Verify the parsed secret matches the original
              assertThat("Parsed secret should match original", parsedSecret, equalTo(secret));
              
              // Increment completed operations counter
              completedOperations.incrementAndGet();
            }
          }
          catch (Throwable t) {
            // Record any errors that occur
            errors.put(threadId, t);
          }
          finally {
            // Signal completion
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete or timeout
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify all threads completed successfully
      assertTrue(completed, "All virtual threads should complete within the timeout period");
      assertThat("No errors should occur during concurrent operations", errors.isEmpty(), is(true));
      
      // Verify all operations were completed
      int expectedOperations = THREAD_COUNT * OPERATIONS_PER_THREAD;
      assertThat("All operations should complete successfully", 
          completedOperations.get(), equalTo(expectedOperations));
    }
  }
  
  /**
   * Tests concurrent creation and parsing of PHC strings with different thread counts
   * to validate scalability with virtual threads.
   */
  @Test
  public void testScalablePhcOperationsWithVirtualThreads() throws Exception {
    // Create a complex test secret with many parameters
    EncryptedSecret complexSecret = new EncryptedSecret(
        "complex-algorithm",
        "2.0",
        "complex-salt-value-with-special-chars-!@#$%^&*()",
        "encrypted-data-with-special-chars-!@#$%^&*()",
        ImmutableMap.of(
            "param1", "value1",
            "param2", "value2",
            "param3", "value3",
            "param4", "value4",
            "param5", "value5"
        )
    );
    
    // Track errors that occur during concurrent operations
    Map<Integer, Throwable> errors = new ConcurrentHashMap<>();
    
    // Create and start virtual threads using the virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create a larger number of virtual threads to test scalability
      int scalableThreadCount = THREAD_COUNT * 5;
      CountDownLatch latch = new CountDownLatch(scalableThreadCount);
      
      for (int i = 0; i < scalableThreadCount; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try {
            // Convert to PHC string
            String phcString = complexSecret.toPhcString();
            assertNotNull(phcString, "PHC string should not be null");
            
            // Parse back to EncryptedSecret
            EncryptedSecret parsedSecret = EncryptedSecret.parse(phcString);
            assertNotNull(parsedSecret, "Parsed secret should not be null");
            
            // Verify the parsed secret matches the original
            assertThat("Parsed secret should match original", parsedSecret, equalTo(complexSecret));
          }
          catch (Throwable t) {
            // Record any errors that occur
            errors.put(threadId, t);
          }
          finally {
            // Signal completion
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete or timeout
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify all threads completed successfully
      assertTrue(completed, "All virtual threads should complete within the timeout period");
      assertThat("No errors should occur during concurrent operations", errors.isEmpty(), is(true));
    }
  }
  
  /**
   * Creates a list of test secrets with different configurations for testing.
   */
  private List<EncryptedSecret> createTestSecrets() {
    List<EncryptedSecret> secrets = new ArrayList<>();
    
    // Add a secret with version
    secrets.add(new EncryptedSecret(
        "test-algorithm1", 
        "1.0", 
        "test-salt1", 
        "encrypted-value1", 
        ImmutableMap.of("key1", "val1", "key2", "val2")
    ));
    
    // Add a secret without version
    secrets.add(new EncryptedSecret(
        "test-algorithm2", 
        null, 
        "test-salt2", 
        "encrypted-value2", 
        ImmutableMap.of("key3", "val3", "key4", "val4")
    ));
    
    // Add a secret with empty parameters
    secrets.add(new EncryptedSecret(
        "test-algorithm3", 
        "2.0", 
        "test-salt3", 
        "encrypted-value3", 
        ImmutableMap.of()
    ));
    
    // Add a secret with special characters
    secrets.add(new EncryptedSecret(
        "test-algorithm4", 
        "3.0", 
        "test-salt-with-special-chars-!@#$%^&*()", 
        "encrypted-value-with-special-chars-!@#$%^&*()", 
        ImmutableMap.of("special", "chars!@#$%^&*()")
    ));
    
    return secrets;
  }
}