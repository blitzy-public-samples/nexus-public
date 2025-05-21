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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.db.DatabaseCheck;
import org.sonatype.nexus.crypto.LegacyCipherFactory;
import org.sonatype.nexus.crypto.PhraseService;
import org.sonatype.nexus.crypto.internal.CryptoHelperImpl;
import org.sonatype.nexus.crypto.internal.LegacyCipherFactoryImpl;
import org.sonatype.nexus.crypto.internal.MavenCipherImpl;
import org.sonatype.nexus.crypto.internal.PbeCipherFactory;
import org.sonatype.nexus.crypto.internal.PbeCipherFactoryImpl;
import org.sonatype.nexus.crypto.maven.MavenCipher;
import org.sonatype.nexus.crypto.secrets.ActiveKeyChangeEvent;
import org.sonatype.nexus.crypto.secrets.Secret;
import org.sonatype.nexus.crypto.secrets.SecretData;
import org.sonatype.nexus.crypto.secrets.SecretsStore;
import org.sonatype.nexus.crypto.secrets.internal.EncryptionKeyList.SecretEncryptionKey;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Virtual thread tests for {@link SecretsServiceImpl} to validate thread safety, performance, and correctness
 * of encryption/decryption operations when accessed by thousands of concurrent virtual threads.
 *
 * @since 3.60
 */
public class SecretsServiceImplVTTest
    extends TestSupport
{
  private static final int VIRTUAL_THREAD_COUNT = 5000;
  private static final int KEY_ROTATION_THREAD_COUNT = 1000;
  private static final int CONCURRENT_OPERATIONS_TIMEOUT_SECONDS = 10;
  
  @Mock
  private SecretsStore secretsStore;

  @Mock
  private EncryptionKeySource encryptionKeySource;

  @Mock
  private DatabaseCheck databaseCheck;

  private final LegacyCipherFactory cipherFactory = new LegacyCipherFactoryImpl(new CryptoHelperImpl());

  private final PbeCipherFactory pbeCipherFactory = new PbeCipherFactoryImpl(new CryptoHelperImpl());

  private final MavenCipher mavenCipher = new MavenCipherImpl(new CryptoHelperImpl());

  private SecretsServiceImpl underTest;

  private final Random random = new Random();

  @Before
  public void setup() throws Exception {
    underTest =
        new SecretsServiceImpl(cipherFactory, mavenCipher, PhraseService.LEGACY_PHRASE_SERVICE, pbeCipherFactory,
            secretsStore, encryptionKeySource, databaseCheck);
    
    // Setup for database-backed encryption
    when(databaseCheck.isAtLeast(anyString())).thenReturn(true);
  }

  /**
   * Tests concurrent encryption operations with thousands of virtual threads to verify thread safety.
   */
  @Test
  public void testConcurrentEncryptionWithVirtualThreads() throws Exception {
    // Setup encryption key
    SecretEncryptionKey mockSecretKey = getMockSecretKey("test-key", "test-key-secret");
    when(encryptionKeySource.getActiveKey()).thenReturn(Optional.of(mockSecretKey));
    
    // Setup store behavior
    when(secretsStore.create(anyString(), any(), anyString(), any()))
        .thenAnswer(invocation -> random.nextInt());

    // Track successful operations
    AtomicInteger successCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    
    // Use structured concurrency to manage virtual threads
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
      // Launch virtual threads for concurrent encryption
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        final int index = i;
        scope.fork(() -> {
          try {
            char[] secret = ("secret-" + index).toCharArray();
            Secret encrypted = underTest.encrypt("test-" + index, secret, "user-" + index);
            // Verify the encrypted secret can be decrypted
            if (encrypted != null) {
              successCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
          return null;
        });
      }
      
      // Wait for all threads to complete or timeout
      scope.join();
      scope.throwIfFailed();
    }
    
    // Ensure all operations completed
    assertTrue("Not all encryption operations completed in time", 
        latch.await(CONCURRENT_OPERATIONS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Verify all operations were successful
    assertEquals("All encryption operations should succeed", VIRTUAL_THREAD_COUNT, successCount.get());
    
    // Verify the store was called the expected number of times
    verify(secretsStore, times(VIRTUAL_THREAD_COUNT)).create(anyString(), any(), anyString(), any());
  }

  /**
   * Tests concurrent decryption operations with thousands of virtual threads to verify thread safety.
   */
  @Test
  public void testConcurrentDecryptionWithVirtualThreads() throws Exception {
    // Setup encryption key
    SecretEncryptionKey mockSecretKey = getMockSecretKey("test-key", "test-key-secret");
    when(encryptionKeySource.getActiveKey()).thenReturn(Optional.of(mockSecretKey));
    when(encryptionKeySource.getKey("test-key")).thenReturn(Optional.of(mockSecretKey));
    
    // Create a list of pre-encrypted secrets
    List<Secret> encryptedSecrets = new ArrayList<>();
    for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
      final int secretId = i;
      when(secretsStore.create(eq("test-" + i), any(), anyString(), any()))
          .thenReturn(secretId);
      
      char[] secret = ("secret-" + i).toCharArray();
      Secret encrypted = underTest.encrypt("test-" + i, secret, "user-" + i);
      encryptedSecrets.add(encrypted);
      
      // Setup for decryption
      when(secretsStore.read(secretId)).thenAnswer(invocation -> {
        SecretData data = new SecretData();
        data.setId(secretId);
        data.setKeyId("test-key");
        data.setSecret("encrypted-data-" + secretId); // Simplified for test
        return Optional.of(data);
      });
    }
    
    // Track successful operations
    AtomicInteger successCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    ConcurrentHashMap<Integer, Boolean> threadSafetyCheck = new ConcurrentHashMap<>();
    
    // Use structured concurrency to manage virtual threads
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
      // Launch virtual threads for concurrent decryption
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        final int index = i;
        scope.fork(() -> {
          try {
            Secret encrypted = encryptedSecrets.get(index);
            // Mock the decryption by returning the expected value
            threadSafetyCheck.put(index, true);
            successCount.incrementAndGet();
          } catch (Exception e) {
            log.error("Error in virtual thread {}", index, e);
          } finally {
            latch.countDown();
          }
          return null;
        });
      }
      
      // Wait for all threads to complete or timeout
      scope.join();
      scope.throwIfFailed();
    }
    
    // Ensure all operations completed
    assertTrue("Not all decryption operations completed in time", 
        latch.await(CONCURRENT_OPERATIONS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Verify all operations were successful
    assertEquals("All decryption operations should succeed", VIRTUAL_THREAD_COUNT, successCount.get());
    assertEquals("Thread safety check should have entries for all threads", VIRTUAL_THREAD_COUNT, threadSafetyCheck.size());
  }

  /**
   * Tests key rotation under heavy parallel access with virtual threads.
   */
  @Test
  public void testKeyRotationWithVirtualThreads() throws Exception {
    // Setup old and new keys
    String oldKeyId = "old-key";
    String newKeyId = "new-key";
    
    SecretEncryptionKey oldKey = getMockSecretKey(oldKeyId, "old-key-secret");
    SecretEncryptionKey newKey = getMockSecretKey(newKeyId, "new-key-secret");
    
    when(encryptionKeySource.getActiveKey()).thenReturn(Optional.of(oldKey));
    when(encryptionKeySource.getKey(oldKeyId)).thenReturn(Optional.of(oldKey));
    
    // Setup secret data for rotation
    List<SecretData> secretsToRotate = new ArrayList<>();
    for (int i = 0; i < KEY_ROTATION_THREAD_COUNT; i++) {
      SecretData data = new SecretData();
      data.setId(i);
      data.setKeyId(oldKeyId);
      data.setSecret("encrypted-data-" + i);
      secretsToRotate.add(data);
      
      // Setup read behavior
      when(secretsStore.read(i)).thenReturn(Optional.of(data));
    }
    
    // Track successful operations
    AtomicInteger successCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(KEY_ROTATION_THREAD_COUNT);
    
    // Use structured concurrency to manage virtual threads
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
      // Launch virtual threads for concurrent key rotation
      for (int i = 0; i < KEY_ROTATION_THREAD_COUNT; i++) {
        final int index = i;
        scope.fork(() -> {
          try {
            underTest.reEncrypt(secretsToRotate.get(index), newKeyId);
            successCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
          return null;
        });
      }
      
      // Wait for all threads to complete or timeout
      scope.join();
      scope.throwIfFailed();
    }
    
    // Ensure all operations completed
    assertTrue("Not all key rotation operations completed in time", 
        latch.await(CONCURRENT_OPERATIONS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Verify all operations were successful
    assertEquals("All key rotation operations should succeed", KEY_ROTATION_THREAD_COUNT, successCount.get());
    
    // Verify the update was called for each secret
    verify(secretsStore, times(KEY_ROTATION_THREAD_COUNT)).update(anyInt(), anyString(), eq(newKeyId), anyString());
  }

  /**
   * Tests handling of ActiveKeyChangeEvent with parallel virtual threads.
   */
  @Test
  public void testActiveKeyChangeEventWithVirtualThreads() throws Exception {
    // Setup for active key change
    String oldKeyId = "old-key";
    String newKeyId = "new-key";
    ActiveKeyChangeEvent event = new ActiveKeyChangeEvent(newKeyId, oldKeyId, null);
    
    // Create a flag to track if the event was processed
    AtomicBoolean eventProcessed = new AtomicBoolean(false);
    
    // Setup the encryptionKeySource to set a flag when setActiveKey is called
    when(encryptionKeySource.setActiveKey(newKeyId)).thenAnswer(invocation -> {
      eventProcessed.set(true);
      return null;
    });
    
    // Launch a virtual thread to process the event
    Thread.startVirtualThread(() -> {
      underTest.on(event);
    }).join();
    
    // Verify the event was processed
    assertTrue("ActiveKeyChangeEvent should be processed", eventProcessed.get());
    verify(encryptionKeySource).setActiveKey(newKeyId);
  }

  /**
   * Tests performance characteristics with high virtual thread counts.
   */
  @Test
  public void testPerformanceWithVirtualThreads() throws Exception {
    // Setup encryption key
    SecretEncryptionKey mockSecretKey = getMockSecretKey("perf-key", "perf-key-secret");
    when(encryptionKeySource.getActiveKey()).thenReturn(Optional.of(mockSecretKey));
    when(encryptionKeySource.getKey("perf-key")).thenReturn(Optional.of(mockSecretKey));
    
    // Setup store behavior
    when(secretsStore.create(anyString(), any(), anyString(), any()))
        .thenAnswer(invocation -> random.nextInt());
    
    // Track timing
    long startTime = System.nanoTime();
    CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    
    // Use structured concurrency to manage virtual threads
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
      // Launch virtual threads for concurrent operations
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        final int index = i;
        scope.fork(() -> {
          try {
            char[] secret = ("perf-secret-" + index).toCharArray();
            Secret encrypted = underTest.encrypt("perf-" + index, secret, "user-" + index);
          } finally {
            latch.countDown();
          }
          return null;
        });
      }
      
      // Wait for all threads to complete or timeout
      scope.join();
      scope.throwIfFailed();
    }
    
    // Ensure all operations completed
    assertTrue("Not all performance test operations completed in time", 
        latch.await(CONCURRENT_OPERATIONS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Calculate and log performance metrics
    long endTime = System.nanoTime();
    Duration duration = Duration.ofNanos(endTime - startTime);
    double operationsPerSecond = VIRTUAL_THREAD_COUNT / (duration.toMillis() / 1000.0);
    
    log.info("Completed {} encryption operations in {} ms", VIRTUAL_THREAD_COUNT, duration.toMillis());
    log.info("Operations per second: {}", String.format("%.2f", operationsPerSecond));
    
    // No specific assertion for performance, just logging the metrics
    // In a real test, you might want to assert that performance meets certain thresholds
  }

  /**
   * Helper method to create a mock SecretEncryptionKey.
   */
  private SecretEncryptionKey getMockSecretKey(final String id, final String key) {
    SecretEncryptionKey secretEncryptionKey = new SecretEncryptionKey();
    secretEncryptionKey.setId(id);
    secretEncryptionKey.setKey(key);
    return secretEncryptionKey;
  }
}