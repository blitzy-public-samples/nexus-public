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
package org.sonatype.nexus.repository.internal;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import javax.inject.Provider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.BlobStoreDescriptor;
import org.sonatype.nexus.blobstore.MockBlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreException;
import org.sonatype.nexus.blobstore.api.tasks.BlobStoreTaskService;
import org.sonatype.nexus.common.app.FreezeService;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.common.node.NodeAccess;
import org.sonatype.nexus.crypto.secrets.Secret;
import org.sonatype.nexus.crypto.secrets.SecretsService;
import org.sonatype.nexus.repository.blobstore.BlobStoreConfigurationStore;
import org.sonatype.nexus.repository.internal.blobstore.BlobStoreManagerImpl;
import org.sonatype.nexus.repository.internal.blobstore.BlobStoreOverride;
import org.sonatype.nexus.repository.internal.blobstore.DefaultFileBlobStoreProvider;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.replication.ReplicationBlobStoreStatusManager;
import org.sonatype.nexus.security.UserIdHelper;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.api.BlobStoreManager.DEFAULT_BLOBSTORE_NAME;

/**
 * Tests for {@link BlobStoreManagerImpl} with Java 21 Virtual Threads.
 */
@ExtendWith(MockitoExtension.class)
public class BlobStoreManagerImplVirtualThreadTest
    extends TestSupport
{
  private static final String SECRET_FIELD_KEY = "secretAccessKey";

  private static final String SECRET_FIELD_VALUE = "secretAccessKeyValue";

  private static final String TEST_USER = "test-user";

  private static final String SECRET_ID = "_1";

  private static final int CONCURRENT_THREADS = 1000;

  @TempDir
  File temporaryFolder;

  @Mock
  private EventManager eventManager;

  @Mock
  private BlobStoreConfigurationStore store;

  @Mock
  private BlobStoreDescriptor descriptor;

  @Mock
  private Provider<BlobStore> provider;

  @Mock
  private FreezeService freezeService;

  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private NodeAccess nodeAccess;

  @Mock
  private ReplicationBlobStoreStatusManager replicationBlobStoreStatusManager;

  @Mock
  private BlobStoreTaskService blobStoreTaskService;

  @Mock
  private Provider<BlobStoreOverride> blobStoreOverrideProvider;

  @Mock
  private SecretsService secretsService;

  private MockedStatic<UserIdHelper> userIdHelperMockedStatic;

  private BlobStoreManagerImpl underTest;

  @BeforeEach
  void setup() {
    userIdHelperMockedStatic = mockStatic(UserIdHelper.class);
    userIdHelperMockedStatic.when(UserIdHelper::get).thenReturn(TEST_USER);
    when(store.newConfiguration()).thenReturn(new MockBlobStoreConfiguration());
    underTest = newBlobStoreManager(false);
  }

  @AfterEach
  void destroy() {
    userIdHelperMockedStatic.close();
  }

  private BlobStoreManagerImpl newBlobStoreManager(Boolean provisionDefaults) {
    Map<String, BlobStoreDescriptor> descriptors = new HashMap<>();
    descriptors.put("test", descriptor);
    descriptors.put("File", descriptor);
    Map<String, Provider<BlobStore>> providers = new HashMap<>();
    providers.put("test", provider);
    providers.put("File", provider);
    return spy(new BlobStoreManagerImpl(eventManager, store,
        descriptors,
        providers,
        freezeService, () -> repositoryManager,
        nodeAccess, provisionDefaults,
        new DefaultFileBlobStoreProvider(),
        blobStoreTaskService,
        blobStoreOverrideProvider,
        replicationBlobStoreStatusManager,
        secretsService));
  }

  /**
   * Tests concurrent creation of blob stores using virtual threads.
   * Verifies that many blob stores can be created concurrently without issues.
   */
  @Test
  void concurrentBlobStoreCreationWithVirtualThreads() throws Exception {
    // Set up mocks
    BlobStore blobStore = mock(BlobStore.class);
    when(provider.get()).thenReturn(blobStore);
    when(store.exists(anyString())).thenReturn(false);

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    int taskCount = CONCURRENT_THREADS;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    ConcurrentHashMap<String, BlobStore> createdStores = new ConcurrentHashMap<>();

    try {
      // Submit multiple concurrent tasks using virtual threads
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            String name = "test-blob-store-" + index;
            BlobStoreConfiguration config = createConfig(name);
            BlobStore created = underTest.create(config);
            createdStores.put(name, created);
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        }, executor);
        futures.add(future);
      }

      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

      // Verify results
      assertEquals(0, errorCount.get(), "No errors should occur during concurrent creation");
      assertEquals(taskCount, createdStores.size(), "All blob stores should be created successfully");

    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests concurrent deletion of blob stores using virtual threads.
   * Verifies that many blob stores can be deleted concurrently without issues.
   */
  @Test
  void concurrentBlobStoreDeletionWithVirtualThreads() throws Exception {
    // Set up mocks
    int storeCount = 100; // Using a smaller number for deletion test
    Map<String, BlobStore> blobStores = new HashMap<>();
    List<BlobStoreConfiguration> configurations = new ArrayList<>();

    for (int i = 0; i < storeCount; i++) {
      String name = "test-blob-store-" + i;
      BlobStore blobStore = mock(BlobStore.class);
      BlobStoreConfiguration config = createConfig(name);
      when(blobStore.getBlobStoreConfiguration()).thenReturn(config);
      blobStores.put(name, blobStore);
      configurations.add(config);
      doReturn(blobStore).when(underTest).blobStore(name);
    }

    when(store.list()).thenReturn(configurations);
    when(repositoryManager.isBlobstoreUsed(anyString())).thenReturn(false);

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    CountDownLatch latch = new CountDownLatch(storeCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicInteger successCount = new AtomicInteger(0);

    try {
      // Submit multiple concurrent tasks using virtual threads
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      for (int i = 0; i < storeCount; i++) {
        final int index = i;
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            String name = "test-blob-store-" + index;
            underTest.delete(name);
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        }, executor);
        futures.add(future);
      }

      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

      // Verify results
      assertEquals(0, errorCount.get(), "No errors should occur during concurrent deletion");
      assertEquals(storeCount, successCount.get(), "All blob stores should be deleted successfully");

    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests concurrent updates of blob stores using virtual threads.
   * Verifies that many blob stores can be updated concurrently without issues.
   */
  @Test
  void concurrentBlobStoreUpdateWithVirtualThreads() throws Exception {
    // Set up mocks for sensitive field handling
    when(descriptor.getSensitiveConfigurationFields()).thenReturn(Collections.singletonList(SECRET_FIELD_KEY));
    Secret oldSecret = mock(Secret.class);
    when(secretsService.from(SECRET_ID)).thenReturn(oldSecret);
    when(oldSecret.decrypt()).thenReturn(SECRET_FIELD_VALUE.toCharArray());

    Secret newSecret = mock(Secret.class);
    when(newSecret.getId()).thenReturn("_2");
    when(secretsService.encryptMaven(anyString(), any(char[].class), anyString())).thenReturn(newSecret);

    // Set up blob stores
    int storeCount = 100; // Using a smaller number for update test
    Map<String, BlobStore> blobStores = new HashMap<>();

    for (int i = 0; i < storeCount; i++) {
      String name = "test-blob-store-" + i;
      BlobStore blobStore = mock(BlobStore.class);
      
      // Create old configuration with secret
      Map<String, Map<String, Object>> oldBlobStoreAttributes = new HashMap<>();
      Map<String, Object> oldBlobConfigMap = new HashMap<>();
      oldBlobConfigMap.put(SECRET_FIELD_KEY, SECRET_ID);
      oldBlobStoreAttributes.put("test", oldBlobConfigMap);
      oldBlobStoreAttributes.put("file", Collections.singletonMap("path", "foo"));
      BlobStoreConfiguration oldBlobStoreConfig = createConfig(name, oldBlobStoreAttributes);
      
      when(blobStore.getBlobStoreConfiguration()).thenReturn(oldBlobStoreConfig);
      blobStores.put(name, blobStore);
      underTest.track(name, blobStore);
    }

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    CountDownLatch latch = new CountDownLatch(storeCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicInteger successCount = new AtomicInteger(0);

    try {
      // Submit multiple concurrent tasks using virtual threads
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      for (int i = 0; i < storeCount; i++) {
        final int index = i;
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            String name = "test-blob-store-" + index;
            
            // Create new configuration with updated attributes
            Map<String, Map<String, Object>> updatedBlobStoreAttributes = new HashMap<>();
            Map<String, Object> newBlobConfigMap = new HashMap<>();
            newBlobConfigMap.put(SECRET_FIELD_KEY, SECRET_FIELD_VALUE);
            updatedBlobStoreAttributes.put("test", newBlobConfigMap);
            updatedBlobStoreAttributes.put("file", Collections.singletonMap("path", "updated-path-" + index));
            BlobStoreConfiguration newBlobStoreConfig = createConfig(name, updatedBlobStoreAttributes);
            
            underTest.update(newBlobStoreConfig);
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        }, executor);
        futures.add(future);
      }

      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

      // Verify results
      assertEquals(0, errorCount.get(), "No errors should occur during concurrent updates");
      assertEquals(storeCount, successCount.get(), "All blob stores should be updated successfully");

    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests performance comparison between virtual threads and platform threads
   * for blob store operations.
   */
  @Test
  void performanceComparisonBetweenVirtualAndPlatformThreads() throws Exception {
    // Set up mocks
    BlobStore blobStore = mock(BlobStore.class);
    when(provider.get()).thenReturn(blobStore);
    when(store.exists(anyString())).thenReturn(false);

    // Create thread factories
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();

    int operationCount = 1000;

    // Measure platform thread performance
    long platformThreadTime = measureExecutionTime(() -> {
      ExecutorService executor = Executors.newThreadPerTaskExecutor(platformThreadFactory);
      try {
        runConcurrentBlobStoreCreations(executor, operationCount);
      } 
      finally {
        executor.shutdown();
      }
    });

    // Measure virtual thread performance
    long virtualThreadTime = measureExecutionTime(() -> {
      ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
      try {
        runConcurrentBlobStoreCreations(executor, operationCount);
      } 
      finally {
        executor.shutdown();
      }
    });

    // Virtual threads should be more efficient for I/O bound operations
    assertThat("Virtual threads should be faster than platform threads for I/O bound operations",
        virtualThreadTime, lessThan(platformThreadTime));
  }

  /**
   * Tests that sensitive attribute encryption/decryption works correctly with virtual threads.
   */
  @Test
  void sensitiveAttributeEncryptionWithVirtualThreads() throws Exception {
    // Set up mocks for sensitive field handling
    when(descriptor.getSensitiveConfigurationFields()).thenReturn(Collections.singletonList(SECRET_FIELD_KEY));
    Secret secret = mock(Secret.class);
    when(secret.getId()).thenReturn(SECRET_ID);
    when(secretsService.encryptMaven(BlobStoreManagerImpl.BLOBSTORE_CONFIG, SECRET_FIELD_VALUE.toCharArray(), TEST_USER))
        .thenReturn(secret);

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicBoolean encryptionVerified = new AtomicBoolean(true);

    try {
      // Submit multiple concurrent tasks using virtual threads
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            // Create configuration with sensitive field
            Map<String, Map<String, Object>> blobStoreAttributes = new HashMap<>();
            Map<String, Object> blobConfigMap = new HashMap<>();
            blobConfigMap.put(SECRET_FIELD_KEY, SECRET_FIELD_VALUE);
            blobStoreAttributes.put("test", blobConfigMap);
            blobStoreAttributes.put("file", Collections.singletonMap("path", "foo-" + index));
            BlobStoreConfiguration configuration = createConfig("test-" + index, blobStoreAttributes);

            // Create blob store which should trigger encryption
            BlobStore blobStore = mock(BlobStore.class);
            when(provider.get()).thenReturn(blobStore);
            underTest.create(configuration);

            // Verify encryption happened
            Object encryptedValue = configuration.getAttributes().get("test").get(SECRET_FIELD_KEY);
            if (!SECRET_ID.equals(encryptedValue)) {
              encryptionVerified.set(false);
            }
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        }, executor);
        futures.add(future);
      }

      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

      // Verify results
      assertEquals(0, errorCount.get(), "No errors should occur during encryption operations");
      assertTrue(encryptionVerified.get(), "All sensitive attributes should be properly encrypted");

    } 
    finally {
      executor.shutdown();
    }

    // Verify encryption service was called
    verify(secretsService, times(taskCount)).encryptMaven(
        BlobStoreManagerImpl.BLOBSTORE_CONFIG, SECRET_FIELD_VALUE.toCharArray(), TEST_USER);
  }

  /**
   * Tests that concurrent creation resilience is maintained with virtual threads.
   * This test verifies that the system can handle concurrent creation attempts
   * that might fail without affecting other operations.
   */
  @Test
  void concurrentCreationResilienceWithVirtualThreads() throws Exception {
    // Set up mocks
    BlobStore goodBlobStore = mock(BlobStore.class);
    BlobStore failingBlobStore = mock(BlobStore.class);
    
    // Configure some blob stores to fail on initialization
    AtomicInteger counter = new AtomicInteger(0);
    when(provider.get()).thenAnswer(invocation -> {
      int current = counter.getAndIncrement();
      // Make every 5th blob store fail
      if (current % 5 == 0) {
        doThrow(new IllegalStateException("Simulated failure")).when(failingBlobStore).init(any(BlobStoreConfiguration.class));
        return failingBlobStore;
      }
      return goodBlobStore;
    });

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger expectedFailureCount = new AtomicInteger(0);

    try {
      // Submit multiple concurrent tasks using virtual threads
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            String name = "test-blob-store-" + index;
            BlobStoreConfiguration config = createConfig(name);
            underTest.create(config);
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            if (e instanceof IllegalStateException && "Simulated failure".equals(e.getMessage())) {
              expectedFailureCount.incrementAndGet();
            }
          } 
          finally {
            latch.countDown();
          }
        }, executor);
        futures.add(future);
      }

      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

      // Verify results - we expect some failures but the system should remain operational
      int expectedSuccesses = taskCount - (taskCount / 5); // Every 5th should fail
      assertEquals(expectedSuccesses, successCount.get(), 
          "Successful operations should match expected count");
      assertEquals(taskCount / 5, expectedFailureCount.get(), 
          "Failed operations should match expected count");

    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that default blob store creation works correctly with virtual threads.
   */
  @Test
  void defaultBlobStoreCreationWithVirtualThreads() throws Exception {
    // Set up for default blob store creation
    underTest = newBlobStoreManager(true);
    ArgumentCaptor<BlobStoreConfiguration> configurationArgumentCaptor = ArgumentCaptor.forClass(BlobStoreConfiguration.class);
    when(store.list()).thenReturn(Collections.emptyList());
    
    // Create a virtual thread factory and run the start operation in a virtual thread
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    Thread virtualThread = virtualThreadFactory.newThread(() -> {
      try {
        underTest.doStart();
      } 
      catch (Exception e) {
        fail("Failed to start BlobStoreManager in virtual thread: " + e.getMessage());
      }
    });
    
    // Start and wait for completion
    virtualThread.start();
    virtualThread.join(10000);
    assertFalse(virtualThread.isAlive(), "Virtual thread should complete within timeout");
    
    // Verify default blob store was created
    verify(store).create(configurationArgumentCaptor.capture());
    assertThat(configurationArgumentCaptor.getValue().getName(), is(DEFAULT_BLOBSTORE_NAME));
  }

  /**
   * Helper method to measure execution time of a task.
   */
  private long measureExecutionTime(Runnable task) {
    long startTime = System.nanoTime();
    task.run();
    long endTime = System.nanoTime();
    return Duration.ofNanos(endTime - startTime).toMillis();
  }

  /**
   * Helper method to run concurrent blob store creations.
   */
  private void runConcurrentBlobStoreCreations(ExecutorService executor, int count) {
    CountDownLatch latch = new CountDownLatch(count);
    
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      final int index = i;
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          String name = "perf-test-blob-store-" + index;
          BlobStoreConfiguration config = createConfig(name);
          underTest.create(config);
        } 
        catch (Exception e) {
          // Ignore exceptions for performance test
        } 
        finally {
          latch.countDown();
        }
      }, executor);
      futures.add(future);
    }

    try {
      latch.await(30, TimeUnit.SECONDS);
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    } 
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private BlobStoreConfiguration createConfig(String name) {
    Map<String, Map<String, Object>> fileAttributes = new HashMap<>();
    fileAttributes.put("file", Collections.singletonMap("path", "baz"));
    return createConfig(name, fileAttributes);
  }

  private BlobStoreConfiguration createConfig(String name, Map<String, Map<String, Object>> fileAttributes) {
    MockBlobStoreConfiguration config = new MockBlobStoreConfiguration();
    config.setName(name);
    config.setType("test");
    config.setAttributes(fileAttributes);
    return config;
  }
}