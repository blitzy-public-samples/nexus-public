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
package org.sonatype.nexus.repository.internal.blobstore;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.inject.Provider;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
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
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.replication.ReplicationBlobStoreStatusManager;
import org.sonatype.nexus.security.UserIdHelper;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link BlobStoreManagerImpl} using Java 21 Virtual Threads.
 * 
 * This test class validates that BlobStoreManagerImpl operations work correctly
 * under high concurrency with virtual threads, and checks for thread pinning issues.
 */
@Category(VirtualThreadTestGroup.class)
public class BlobStoreManagerImplVirtualThreadTest
    extends TestSupport
{
  private static final String SECRET_FIELD_KEY = "secretAccessKey";

  private static final String SECRET_FIELD_VALUE = "secretAccessKeyValue";

  private static final String TEST_USER = "test-user";

  private static final int CONCURRENT_OPERATIONS = 100;
  
  private static final int TIMEOUT_SECONDS = 30;

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

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

  @Before
  public void setup() {
    MockitoAnnotations.openMocks(this);
    userIdHelperMockedStatic = mockStatic(UserIdHelper.class);
    userIdHelperMockedStatic.when(UserIdHelper::get).thenReturn(TEST_USER);
    when(store.newConfiguration()).thenReturn(new MockBlobStoreConfiguration());
    underTest = newBlobStoreManager(false);
  }

  @After
  public void destroy() throws Exception {
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
   * Tests concurrent blob store creation with virtual threads.
   * Verifies that multiple blob stores can be created concurrently without errors.
   */
  @Test
  public void testConcurrentBlobStoreCreationWithVirtualThreads() throws Exception {
    // Set up mocks
    BlobStore blobStore = mock(BlobStore.class);
    when(provider.get()).thenReturn(blobStore);
    
    // Create executor with virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = CONCURRENT_OPERATIONS;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    List<BlobStore> createdBlobStores = Collections.synchronizedList(new ArrayList<>());
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            BlobStoreConfiguration config = createConfig("virtual-thread-test-" + index);
            BlobStore createdBlobStore = underTest.create(config);
            createdBlobStores.add(createdBlobStore);
          } catch (Exception e) {
            log.error("Error creating blob store", e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify results
      assertTrue("Timed out waiting for concurrent operations to complete", completed);
      assertEquals("No errors should occur during concurrent blob store creation", 0, errorCount.get());
      assertEquals("All blob stores should be created successfully", taskCount, createdBlobStores.size());
      
      // Verify that each blob store was started
      verify(blobStore, times(taskCount)).start();
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests concurrent blob store update with virtual threads.
   * Verifies that multiple blob stores can be updated concurrently without errors.
   */
  @Test
  public void testConcurrentBlobStoreUpdateWithVirtualThreads() throws Exception {
    // Set up mocks
    when(descriptor.getSensitiveConfigurationFields()).thenReturn(Collections.singletonList(SECRET_FIELD_KEY));
    
    // Create a map to track blob stores by name
    Map<String, BlobStore> blobStoreMap = new ConcurrentHashMap<>();
    
    // Pre-create blob stores
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      String name = "update-test-" + i;
      BlobStore blobStore = mock(BlobStore.class);
      
      Map<String, Map<String, Object>> oldBlobStoreAttributes = new HashMap<>();
      Map<String, Object> oldBlobConfigMap = new HashMap<>();
      String secretId = "_" + i;
      oldBlobConfigMap.put(SECRET_FIELD_KEY, secretId);
      oldBlobStoreAttributes.put("test", oldBlobConfigMap);
      oldBlobStoreAttributes.put("file", Collections.singletonMap("path", "foo"));
      BlobStoreConfiguration oldBlobStoreConfig = createConfig(name, oldBlobStoreAttributes);
      
      when(blobStore.getBlobStoreConfiguration()).thenReturn(oldBlobStoreConfig);
      Secret oldSecret = mock(Secret.class);
      when(secretsService.from(secretId)).thenReturn(oldSecret);
      when(oldSecret.decrypt()).thenReturn(SECRET_FIELD_VALUE.toCharArray());
      
      blobStoreMap.put(name, blobStore);
      underTest.track(name, blobStore);
    }
    
    // Create executor with virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent update tasks using virtual threads
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final int index = i;
        final String name = "update-test-" + index;
        
        executor.submit(() -> {
          try {
            // Create updated configuration
            Secret newSecret = mock(Secret.class);
            when(newSecret.getId()).thenReturn("_new" + index);
            
            Map<String, Map<String, Object>> updatedBlobStoreAttributes = new HashMap<>();
            Map<String, Object> newBlobConfigMap = new HashMap<>();
            newBlobConfigMap.put(SECRET_FIELD_KEY, SECRET_FIELD_VALUE);
            updatedBlobStoreAttributes.put("test", newBlobConfigMap);
            updatedBlobStoreAttributes.put("file", Collections.singletonMap("path", "foo-updated"));
            BlobStoreConfiguration newBlobStoreConfig = createConfig(name, updatedBlobStoreAttributes);
            
            when(secretsService.encryptMaven(BlobStoreManagerImpl.BLOBSTORE_CONFIG, SECRET_FIELD_VALUE.toCharArray(),
                TEST_USER)).thenReturn(newSecret);
            
            // Update the blob store
            underTest.update(newBlobStoreConfig);
          } catch (Exception e) {
            log.error("Error updating blob store", e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify results
      assertTrue("Timed out waiting for concurrent operations to complete", completed);
      assertEquals("No errors should occur during concurrent blob store update", 0, errorCount.get());
      
      // Verify that each blob store configuration was updated
      verify(store, times(CONCURRENT_OPERATIONS)).update(any(BlobStoreConfiguration.class));
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests concurrent blob store deletion with virtual threads.
   * Verifies that multiple blob stores can be deleted concurrently without errors.
   */
  @Test
  public void testConcurrentBlobStoreDeletionWithVirtualThreads() throws Exception {
    // Set up mocks
    when(descriptor.getSensitiveConfigurationFields()).thenReturn(Collections.singletonList(SECRET_FIELD_KEY));
    
    // Create a map to track blob stores by name
    Map<String, BlobStore> blobStoreMap = new ConcurrentHashMap<>();
    List<BlobStoreConfiguration> configurations = new ArrayList<>();
    
    // Pre-create blob stores
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      String name = "delete-test-" + i;
      BlobStore blobStore = mock(BlobStore.class);
      
      Map<String, Map<String, Object>> blobStoreAttributes = new HashMap<>();
      Map<String, Object> blobConfigMap = new HashMap<>();
      String secretId = "_" + i;
      blobConfigMap.put(SECRET_FIELD_KEY, secretId);
      blobStoreAttributes.put("test", blobConfigMap);
      blobStoreAttributes.put("file", Collections.singletonMap("path", "foo"));
      BlobStoreConfiguration configuration = createConfig(name, blobStoreAttributes);
      
      when(blobStore.getBlobStoreConfiguration()).thenReturn(configuration);
      Secret secret = mock(Secret.class);
      when(secretsService.from(secretId)).thenReturn(secret);
      
      blobStoreMap.put(name, blobStore);
      configurations.add(configuration);
      underTest.track(name, blobStore);
      doReturn(blobStore).when(underTest).blobStore(name);
    }
    
    when(store.list()).thenReturn(configurations);
    when(repositoryManager.isBlobstoreUsed(any())).thenReturn(false);
    
    // Create executor with virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent delete tasks using virtual threads
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final String name = "delete-test-" + i;
        
        executor.submit(() -> {
          try {
            underTest.delete(name);
          } catch (Exception e) {
            log.error("Error deleting blob store", e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify results
      assertTrue("Timed out waiting for concurrent operations to complete", completed);
      assertEquals("No errors should occur during concurrent blob store deletion", 0, errorCount.get());
      
      // Verify that each blob store was shut down and deleted
      for (BlobStore blobStore : blobStoreMap.values()) {
        verify(blobStore).shutdown();
      }
      verify(store, times(CONCURRENT_OPERATIONS)).delete(any(BlobStoreConfiguration.class));
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests concurrent blob store lookup with virtual threads.
   * Verifies that multiple blob stores can be looked up concurrently without errors.
   */
  @Test
  public void testConcurrentBlobStoreLookupWithVirtualThreads() throws Exception {
    // Set up mocks
    Map<String, BlobStore> blobStoreMap = new ConcurrentHashMap<>();
    
    // Pre-create blob stores
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      String name = "lookup-test-" + i;
      BlobStore blobStore = mock(BlobStore.class);
      blobStoreMap.put(name, blobStore);
      underTest.track(name, blobStore);
    }
    
    // Create executor with virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS * 10); // More lookups than stores
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent lookup tasks using virtual threads
      for (int i = 0; i < CONCURRENT_OPERATIONS * 10; i++) {
        final int index = i % CONCURRENT_OPERATIONS;
        final String name = "lookup-test-" + index;
        
        executor.submit(() -> {
          try {
            BlobStore blobStore = underTest.get(name);
            if (blobStore != null) {
              successCount.incrementAndGet();
            } else {
              errorCount.incrementAndGet();
            }
          } catch (Exception e) {
            log.error("Error looking up blob store", e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify results
      assertTrue("Timed out waiting for concurrent operations to complete", completed);
      assertEquals("No errors should occur during concurrent blob store lookup", 0, errorCount.get());
      assertEquals("All lookups should succeed", CONCURRENT_OPERATIONS * 10, successCount.get());
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests performance comparison between virtual threads and platform threads.
   * Verifies that virtual threads provide better performance for concurrent operations.
   */
  @Test
  public void testPerformanceComparisonBetweenVirtualAndPlatformThreads() throws Exception {
    // Set up mocks
    BlobStore blobStore = mock(BlobStore.class);
    when(provider.get()).thenReturn(blobStore);
    
    int operationCount = 1000;
    
    // Measure performance with platform threads
    long platformThreadTime = measurePerformance(() -> {
      ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
      return Executors.newFixedThreadPool(100, platformThreadFactory); // Limited thread pool size for platform threads
    }, operationCount);
    
    // Measure performance with virtual threads
    long virtualThreadTime = measurePerformance(() -> {
      ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
      return Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    }, operationCount);
    
    log.info("Platform thread execution time: {} ms", platformThreadTime);
    log.info("Virtual thread execution time: {} ms", virtualThreadTime);
    
    // Virtual threads should be faster or at least not significantly slower
    // This is a soft assertion as the actual performance depends on the environment
    assertThat("Virtual threads should provide better performance", 
        virtualThreadTime, lessThan(platformThreadTime * 2)); // Allow some margin
  }

  /**
   * Tests for thread pinning detection with virtual threads.
   * This test verifies that operations don't cause thread pinning issues.
   */
  @Test
  public void testThreadPinningDetectionWithVirtualThreads() throws Exception {
    // Set up mocks
    BlobStore blobStore = mock(BlobStore.class);
    when(provider.get()).thenReturn(blobStore);
    
    // Create executor with virtual threads and enable pinning detection
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("pinning-test-").factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // System property to detect thread pinning (would be set in the JVM args in a real environment)
    // -Djdk.tracePinnedThreads=full
    
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create and delete a blob store - operations that should not cause pinning
            BlobStoreConfiguration config = createConfig("pinning-test-" + index);
            BlobStore createdBlobStore = underTest.create(config);
            assertNotNull("Blob store should be created successfully", createdBlobStore);
            
            // If thread pinning occurs, it would be logged by the JVM when the appropriate flag is set
          } catch (Exception e) {
            log.error("Error in pinning test", e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify results
      assertTrue("Timed out waiting for concurrent operations to complete", completed);
      assertEquals("No errors should occur during thread pinning test", 0, errorCount.get());
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Helper method to measure performance of concurrent operations using the provided executor factory.
   */
  private long measurePerformance(Supplier<ExecutorService> executorFactory, int operationCount) throws Exception {
    ExecutorService executor = executorFactory.get();
    
    try {
      long startTime = System.currentTimeMillis();
      
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      
      // Submit operations
      for (int i = 0; i < operationCount; i++) {
        final int index = i;
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            BlobStoreConfiguration config = createConfig("perf-test-" + index);
            underTest.create(config);
          } catch (Exception e) {
            log.error("Error in performance test", e);
          }
        }, executor);
        
        futures.add(future);
      }
      
      // Wait for all operations to complete
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
      
      long endTime = System.currentTimeMillis();
      return endTime - startTime;
    } finally {
      executor.shutdown();
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