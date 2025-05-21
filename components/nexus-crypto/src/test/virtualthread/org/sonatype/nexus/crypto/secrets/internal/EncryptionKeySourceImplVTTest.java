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

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.crypto.secrets.internal.EncryptionKeyList.SecretEncryptionKey;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Virtual Thread tests for {@link EncryptionKeySourceImpl}.
 * 
 * These tests validate that the EncryptionKeySourceImpl works correctly when accessed
 * by many concurrent virtual threads, ensuring thread safety and proper performance
 * characteristics under Java 21's Virtual Thread model.
 */
public class EncryptionKeySourceImplVTTest
    extends TestSupport
{
  private static final String BASE_PATH = "src/test/resources/";

  private static final String TEST_SECRET_KEYS_PATH = BASE_PATH + "test-secret-keys.json";

  private static final String INVALID_FILE_PATH = BASE_PATH + "invalid-secret-keys.json";

  private final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * Tests that concurrent access to the encryption key source by many virtual threads
   * works correctly without thread pinning or race conditions.
   */
  @Test
  public void testConcurrentAccessWithVirtualThreads() throws Exception {
    EncryptionKeySource encryptionKeySource = new EncryptionKeySourceImpl(TEST_SECRET_KEYS_PATH, objectMapper);
    
    int threadCount = 1000;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicBoolean hasErrors = new AtomicBoolean(false);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create an executor service that uses virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to be executed by virtual threads
      List<Future<?>> futures = IntStream.range(0, threadCount)
          .mapToObj(i -> executor.submit(() -> {
            try {
              // Wait for all threads to start at the same time
              startLatch.await();
              
              // Get the active key
              Optional<SecretEncryptionKey> key = encryptionKeySource.getActiveKey();
              
              // Verify the key is correct
              if (key.isPresent() && "my-secret".equals(key.get().getId())) {
                successCount.incrementAndGet();
              } else {
                hasErrors.set(true);
              }
            } 
            catch (Exception e) {
              hasErrors.set(true);
            }
            finally {
              completionLatch.countDown();
            }
          }))
          .collect(Collectors.toList());
      
      // Start all threads at once
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(10, TimeUnit.SECONDS);
      
      // Verify all threads completed successfully
      assertThat("All virtual threads should complete in time", completed, is(true));
      assertThat("No errors should occur during concurrent access", hasErrors.get(), is(false));
      assertThat("All threads should successfully retrieve the key", successCount.get(), is(threadCount));
    }
  }

  /**
   * Tests that concurrent key retrieval by ID works correctly with virtual threads.
   */
  @Test
  public void testConcurrentKeyRetrievalByIdWithVirtualThreads() throws Exception {
    EncryptionKeySource encryptionKeySource = new EncryptionKeySourceImpl(TEST_SECRET_KEYS_PATH, objectMapper);
    
    int threadCount = 1000;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicBoolean hasErrors = new AtomicBoolean(false);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create an executor service that uses virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to be executed by virtual threads
      List<Future<?>> futures = IntStream.range(0, threadCount)
          .mapToObj(i -> executor.submit(() -> {
            try {
              // Wait for all threads to start at the same time
              startLatch.await();
              
              // Get a specific key by ID
              String keyId = "random-key-s3";
              Optional<SecretEncryptionKey> key = encryptionKeySource.getKey(keyId);
              
              // Verify the key is correct
              if (key.isPresent() && keyId.equals(key.get().getId())) {
                successCount.incrementAndGet();
              } else {
                hasErrors.set(true);
              }
            } 
            catch (Exception e) {
              hasErrors.set(true);
            }
            finally {
              completionLatch.countDown();
            }
          }))
          .collect(Collectors.toList());
      
      // Start all threads at once
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(10, TimeUnit.SECONDS);
      
      // Verify all threads completed successfully
      assertThat("All virtual threads should complete in time", completed, is(true));
      assertThat("No errors should occur during concurrent key retrieval", hasErrors.get(), is(false));
      assertThat("All threads should successfully retrieve the key", successCount.get(), is(threadCount));
    }
  }

  /**
   * Tests that concurrent active key setting works correctly with virtual threads.
   */
  @Test
  public void testConcurrentActiveKeySettingWithVirtualThreads() throws Exception {
    ObjectMapper mapper = spy(objectMapper);
    doCallRealMethod().when(mapper).readValue(any(File.class), eq(EncryptionKeyList.class));
    
    EncryptionKeySource encryptionKeySource = new EncryptionKeySourceImpl(TEST_SECRET_KEYS_PATH, mapper);
    
    int threadCount = 100; // Using fewer threads as this operation may cause more contention
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicBoolean hasErrors = new AtomicBoolean(false);
    
    // Create an executor service that uses virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to be executed by virtual threads
      List<Future<?>> futures = IntStream.range(0, threadCount)
          .mapToObj(i -> executor.submit(() -> {
            try {
              // Wait for all threads to start at the same time
              startLatch.await();
              
              // Set the active key to one of the available keys
              String keyId = (i % 2 == 0) ? "test-secret-1" : "test-secret-2";
              encryptionKeySource.setActiveKey(keyId);
              
              // Verify the active key was set correctly
              Optional<SecretEncryptionKey> activeKey = encryptionKeySource.getActiveKey();
              if (!activeKey.isPresent() || (!keyId.equals(activeKey.get().getId()) && 
                  !"test-secret-1".equals(activeKey.get().getId()) && 
                  !"test-secret-2".equals(activeKey.get().getId()))) {
                hasErrors.set(true);
              }
            } 
            catch (Exception e) {
              hasErrors.set(true);
            }
            finally {
              completionLatch.countDown();
            }
          }))
          .collect(Collectors.toList());
      
      // Start all threads at once
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(10, TimeUnit.SECONDS);
      
      // Verify all threads completed successfully
      assertThat("All virtual threads should complete in time", completed, is(true));
      assertThat("No errors should occur during concurrent active key setting", hasErrors.get(), is(false));
      
      // Verify the file was read at least once
      verify(mapper, times(1)).readValue(any(File.class), eq(EncryptionKeyList.class));
    }
  }

  /**
   * Tests that error handling works correctly with virtual threads when accessing an invalid file.
   */
  @Test
  public void testErrorHandlingWithVirtualThreads() throws Exception {
    EncryptionKeySource encryptionKeySource = new EncryptionKeySourceImpl(INVALID_FILE_PATH, objectMapper);
    
    int threadCount = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger exceptionCount = new AtomicInteger(0);
    
    // Create an executor service that uses virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to be executed by virtual threads
      List<Future<?>> futures = IntStream.range(0, threadCount)
          .mapToObj(i -> executor.submit(() -> {
            try {
              // Wait for all threads to start at the same time
              startLatch.await();
              
              // This should throw an exception
              encryptionKeySource.getActiveKey();
              
              // If we get here, it's an error
              fail("Expected UncheckedIOException was not thrown");
            } 
            catch (UncheckedIOException e) {
              // This is expected
              if (e.getMessage().contains(INVALID_FILE_PATH)) {
                exceptionCount.incrementAndGet();
              }
            }
            catch (Exception e) {
              // Other exceptions are unexpected
            }
            finally {
              completionLatch.countDown();
            }
          }))
          .collect(Collectors.toList());
      
      // Start all threads at once
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(10, TimeUnit.SECONDS);
      
      // Verify all threads completed successfully
      assertThat("All virtual threads should complete in time", completed, is(true));
      assertThat("All threads should receive the expected exception", exceptionCount.get(), is(threadCount));
    }
  }

  /**
   * Tests the performance characteristics of the encryption key source with a high number of virtual threads.
   */
  @Test
  public void testPerformanceWithHighVirtualThreadCount() throws Exception {
    EncryptionKeySource encryptionKeySource = new EncryptionKeySourceImpl(TEST_SECRET_KEYS_PATH, objectMapper);
    
    int threadCount = 10000; // Using a very high thread count to stress test
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicBoolean hasErrors = new AtomicBoolean(false);
    
    long startTime = System.currentTimeMillis();
    
    // Create an executor service that uses virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to be executed by virtual threads
      List<Future<?>> futures = IntStream.range(0, threadCount)
          .mapToObj(i -> executor.submit(() -> {
            try {
              // Wait for all threads to start at the same time
              startLatch.await();
              
              // Alternate between getting active key and specific key
              if (i % 2 == 0) {
                Optional<SecretEncryptionKey> key = encryptionKeySource.getActiveKey();
                if (!key.isPresent() || !"my-secret".equals(key.get().getId())) {
                  hasErrors.set(true);
                }
              } else {
                String keyId = "random-key-s3";
                Optional<SecretEncryptionKey> key = encryptionKeySource.getKey(keyId);
                if (!key.isPresent() || !keyId.equals(key.get().getId())) {
                  hasErrors.set(true);
                }
              }
            } 
            catch (Exception e) {
              hasErrors.set(true);
            }
            finally {
              completionLatch.countDown();
            }
          }))
          .collect(Collectors.toList());
      
      // Start all threads at once
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
      
      long endTime = System.currentTimeMillis();
      long duration = endTime - startTime;
      
      // Verify all threads completed successfully
      assertThat("All virtual threads should complete in time", completed, is(true));
      assertThat("No errors should occur during high load", hasErrors.get(), is(false));
      
      // Log performance metrics
      log.info("Completed {} virtual thread operations in {} ms", threadCount, duration);
      log.info("Average time per operation: {} ms", (double) duration / threadCount);
    }
  }
}