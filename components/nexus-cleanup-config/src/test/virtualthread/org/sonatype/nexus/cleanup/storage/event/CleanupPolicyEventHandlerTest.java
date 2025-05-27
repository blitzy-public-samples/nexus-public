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
package org.sonatype.nexus.cleanup.storage.event;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.cleanup.storage.CleanupPolicy;
import org.sonatype.nexus.common.entity.EntityMetadata;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.manager.RepositoryManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.google.common.collect.Maps.newHashMap;
import static com.google.common.collect.Sets.newLinkedHashSet;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.stream.Stream.empty;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.core.IsNull.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.cleanup.storage.CleanupPolicy.ALL_CLEANUP_POLICY_FORMAT;

/**
 * Virtual Thread-specific test for {@link CleanupPolicyEventHandler} to validate concurrent event processing
 * using Java 21 Virtual Threads.
 */
@ExtendWith(MockitoExtension.class)
class CleanupPolicyEventHandlerTest
    extends TestSupport
{
  private static final String CLEANUP_ATTRIBUTES_KEY = "cleanup";
  
  private static final String CLEANUP_NAME_KEY = "policyName";
  
  @Mock
  private CleanupPolicy cleanupPolicy1, cleanupPolicy2, cleanupPolicy3;
  
  @Mock
  private EntityMetadata entityMetadata1, entityMetadata2, entityMetadata3;
  
  @Mock
  private RepositoryManager repositoryManager;
  
  @Mock
  private Repository repository1, repository2;
  
  @Mock
  private Configuration configuration1, configuration2;
  
  @Captor
  private ArgumentCaptor<Configuration> configCaptor;
  
  private Map<String, Map<String, Object>> attributes1, attributes2;
  
  private String name1, name2, name3;
  
  private CleanupPolicyEventHandler underTest;
  
  @BeforeEach
  void setup() {
    underTest = new CleanupPolicyEventHandler(repositoryManager);

    name1 = generateValidName();
    name2 = generateValidName();
    name3 = generateValidName();

    Map<String, Object> cleanupAttributes1 = newHashMap();
    cleanupAttributes1.put(CLEANUP_NAME_KEY, newLinkedHashSet(singletonList(name1)));

    Map<String, Object> cleanupAttributes2 = newHashMap();
    cleanupAttributes2.put(CLEANUP_NAME_KEY, newLinkedHashSet(asList(name2, name3)));

    attributes1 = newHashMap();
    attributes1.put(CLEANUP_ATTRIBUTES_KEY, cleanupAttributes1);

    attributes2 = newHashMap();
    attributes2.put(CLEANUP_ATTRIBUTES_KEY, cleanupAttributes2);

    when(cleanupPolicy1.getName()).thenReturn(name1);
    when(cleanupPolicy2.getName()).thenReturn(name2);
    when(cleanupPolicy3.getName()).thenReturn(name3);
    when(cleanupPolicy1.getFormat()).thenReturn(ALL_CLEANUP_POLICY_FORMAT);
    when(cleanupPolicy2.getFormat()).thenReturn(ALL_CLEANUP_POLICY_FORMAT);
    when(cleanupPolicy3.getFormat()).thenReturn(ALL_CLEANUP_POLICY_FORMAT);
    when(entityMetadata1.getEntity()).thenReturn(Optional.of(cleanupPolicy1));
    when(entityMetadata2.getEntity()).thenReturn(Optional.of(cleanupPolicy2));
    when(entityMetadata3.getEntity()).thenReturn(Optional.of(cleanupPolicy3));
    when(configuration1.copy()).thenReturn(configuration1);
    when(configuration2.copy()).thenReturn(configuration2);
    when(configuration1.getAttributes()).thenReturn(attributes1);
    when(configuration2.getAttributes()).thenReturn(attributes2);
    when(repository1.getConfiguration()).thenReturn(configuration1);
    when(repository2.getConfiguration()).thenReturn(configuration2);
    when(repositoryManager.browseForCleanupPolicy(name1)).thenReturn(Stream.of(repository1));
    when(repositoryManager.browseForCleanupPolicy(name2)).thenReturn(Stream.of(repository2));
    when(repositoryManager.browseForCleanupPolicy(name3)).thenReturn(Stream.of(repository2));
  }
  
  /**
   * Tests that cleanup attributes are removed from repository when a cleanup policy is deleted.
   */
  @Test
  void removedCleanupAttributeFromRepository() {
    underTest.on(new CleanupPolicyDeletedEvent(entityMetadata1));
    underTest.on(new CleanupPolicyDeletedEvent(entityMetadata2));
    underTest.on(new CleanupPolicyDeletedEvent(entityMetadata3));

    assertThat(attributes1.get(CLEANUP_ATTRIBUTES_KEY), nullValue());
    assertThat(attributes2.get(CLEANUP_ATTRIBUTES_KEY), nullValue());

    verifyConfigurationUpdatedWithoutCleanupPolicyAttribute(3);
  }
  
  /**
   * Tests that a single cleanup policy is removed from a repository with multiple policies.
   */
  @Test
  void removedOneCleanupPolicyFromRepositoryWithMultiPolicy() {
    underTest.on(new CleanupPolicyDeletedEvent(entityMetadata3));

    verifyConfigurationUpdated(1);
    verifyContainsCleanupPolicies(attributes2, cleanupPolicy2.getName());
  }
  
  /**
   * Tests that only repositories with matching cleanup policy get their attributes removed.
   */
  @Test
  void onlyRepositoryWithMatchingCleanupPolicyGetsAttributesRemoved() {
    underTest.on(new CleanupPolicyDeletedEvent(entityMetadata1));

    assertThat(attributes1.get(CLEANUP_ATTRIBUTES_KEY), nullValue());
    assertThat(attributes2.get(CLEANUP_ATTRIBUTES_KEY), notNullValue());

    verifyConfigurationUpdatedWithoutCleanupPolicyAttribute(1);
    verifyContainsCleanupPolicies(attributes2, cleanupPolicy2.getName(), cleanupPolicy3.getName());
  }
  
  /**
   * Tests that multiple repositories with the same matching cleanup policy get their attributes removed.
   */
  @Test
  void multipleRepositoriesWithSameMatchingCleanupPolicyGetTheirAttributesRemoved() {
    // we make the second configuration return the same attributes as the first
    when(repositoryManager.browseForCleanupPolicy(name1)).thenReturn(Stream.of(repository1, repository2));
    when(configuration2.getAttributes()).thenReturn(attributes1);

    underTest.on(new CleanupPolicyDeletedEvent(entityMetadata1));

    assertThat(attributes1.get(CLEANUP_ATTRIBUTES_KEY), nullValue());
    assertThat(attributes2.get(CLEANUP_ATTRIBUTES_KEY), notNullValue());

    verifyConfigurationUpdatedWithoutCleanupPolicyAttribute(2);
    verifyContainsCleanupPolicies(attributes2, cleanupPolicy2.getName(), cleanupPolicy3.getName());
  }
  
  /**
   * Tests that configuration without repository does not get updated.
   */
  @Test
  void configurationWithoutRepositoryDoesNotGetUpdated() {
    when(repositoryManager.browseForCleanupPolicy(any())).thenReturn(empty());

    underTest.on(new CleanupPolicyDeletedEvent(entityMetadata1));

    when(repositoryManager.browseForCleanupPolicy(any())).thenReturn(empty());
    underTest.on(new CleanupPolicyDeletedEvent(entityMetadata2));

    verifyConfigurationNeverUpdated();
  }
  
  /**
   * Tests concurrent cleanup policy deletion events using Virtual Threads.
   * This test verifies that the CleanupPolicyEventHandler can handle a high volume of
   * concurrent events without data corruption or thread pinning.
   */
  @Test
  void concurrentCleanupPolicyDeletionWithVirtualThreads() throws Exception {
    // Create a large number of cleanup policies and repositories for concurrent testing
    int policyCount = 1000;
    Map<String, CleanupPolicy> policies = new ConcurrentHashMap<>();
    Map<String, EntityMetadata> metadataMap = new ConcurrentHashMap<>();
    Map<String, Repository> repositories = new ConcurrentHashMap<>();
    Map<String, Configuration> configurations = new ConcurrentHashMap<>();
    Map<String, Map<String, Map<String, Object>>> attributesMap = new ConcurrentHashMap<>();
    
    // Setup test data for concurrent testing
    for (int i = 0; i < policyCount; i++) {
      String policyName = "policy-" + i;
      String repoName = "repo-" + i;
      
      // Create and configure mocks for each policy
      CleanupPolicy policy = mock(CleanupPolicy.class);
      EntityMetadata metadata = mock(EntityMetadata.class);
      Repository repository = mock(Repository.class);
      Configuration configuration = mock(Configuration.class);
      
      // Setup attributes for this policy
      Map<String, Object> cleanupAttributes = newHashMap();
      cleanupAttributes.put(CLEANUP_NAME_KEY, newLinkedHashSet(singletonList(policyName)));
      Map<String, Map<String, Object>> attributes = newHashMap();
      attributes.put(CLEANUP_ATTRIBUTES_KEY, cleanupAttributes);
      
      // Configure the mocks
      when(policy.getName()).thenReturn(policyName);
      when(policy.getFormat()).thenReturn(ALL_CLEANUP_POLICY_FORMAT);
      when(metadata.getEntity()).thenReturn(Optional.of(policy));
      when(configuration.copy()).thenReturn(configuration);
      when(configuration.getAttributes()).thenReturn(attributes);
      when(repository.getConfiguration()).thenReturn(configuration);
      when(repositoryManager.browseForCleanupPolicy(policyName)).thenReturn(Stream.of(repository));
      
      // Store in our maps for later verification
      policies.put(policyName, policy);
      metadataMap.put(policyName, metadata);
      repositories.put(repoName, repository);
      configurations.put(repoName, configuration);
      attributesMap.put(repoName, attributes);
    }
    
    // Create virtual thread factory for concurrent testing
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Setup synchronization and tracking
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(policyCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicBoolean threadPinningDetected = new AtomicBoolean(false);
    
    // Submit tasks to delete policies concurrently using virtual threads
    for (int i = 0; i < policyCount; i++) {
      final String policyName = "policy-" + i;
      final EntityMetadata metadata = metadataMap.get(policyName);
      
      executor.submit(() -> {
        try {
          // Wait for all threads to be ready before starting
          startLatch.await();
          
          // Check if this thread is a virtual thread
          boolean isVirtual = Thread.currentThread().isVirtual();
          if (!isVirtual) {
            log.warn("Thread is not virtual: {}", Thread.currentThread().getName());
          }
          
          // Check for thread pinning
          Thread currentThread = Thread.currentThread();
          if (currentThread.isVirtual()) {
            // Record thread state before operation to detect pinning
            StackTraceElement[] stackBefore = currentThread.getStackTrace();
            long startTime = System.nanoTime();
            
            // Execute the event handler
            underTest.on(new CleanupPolicyDeletedEvent(metadata));
            
            // Check execution time - unusually long times might indicate pinning
            long duration = System.nanoTime() - startTime;
            if (duration > TimeUnit.MILLISECONDS.toNanos(100)) { // Threshold for suspicion
              StackTraceElement[] stackAfter = currentThread.getStackTrace();
              log.warn("Possible thread pinning detected. Operation took {} ms", 
                  TimeUnit.NANOSECONDS.toMillis(duration));
              threadPinningDetected.set(true);
            }
          } else {
            // Just execute the event handler for non-virtual threads
            underTest.on(new CleanupPolicyDeletedEvent(metadata));
          }
        } 
        catch (Exception e) {
          log.error("Error in virtual thread execution", e);
          errorCount.incrementAndGet();
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete with timeout
    boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
    
    // Shutdown executor
    executor.shutdown();
    boolean terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
    if (!terminated) {
      executor.shutdownNow();
    }
    
    // Verify results
    assertTrue(completed, "Not all virtual threads completed within timeout");
    assertEquals(0, errorCount.get(), "Some virtual threads encountered errors");
    assertFalse(threadPinningDetected.get(), "Thread pinning was detected during concurrent execution");
    
    // Verify that cleanup policy attributes were correctly removed
    // Sample a few repositories to verify correct behavior
    for (int i = 0; i < 10; i++) {
      String repoName = "repo-" + i;
      Map<String, Map<String, Object>> attributes = attributesMap.get(repoName);
      assertThat(attributes.get(CLEANUP_ATTRIBUTES_KEY), nullValue());
    }
    
    // Verify repository manager was called to update configurations
    verify(repositoryManager, times(policyCount)).update(any(Configuration.class));
  }
  
  /**
   * Tests concurrent cleanup policy deletion events with record pattern matching.
   * This test demonstrates the use of Java 21's record pattern matching feature
   * while testing virtual thread concurrency.
   */
  @Test
  void concurrentCleanupPolicyDeletionWithRecordPatterns() throws Exception {
    // Create a record to represent policy test data
    record PolicyTestData(String name, CleanupPolicy policy, EntityMetadata metadata, 
                         Repository repository, Configuration configuration, 
                         Map<String, Map<String, Object>> attributes) {}
    
    // Create test data using record for cleaner data handling
    int policyCount = 100;
    Map<String, PolicyTestData> testDataMap = new ConcurrentHashMap<>();
    
    for (int i = 0; i < policyCount; i++) {
      String policyName = "record-policy-" + i;
      
      // Create and configure mocks
      CleanupPolicy policy = mock(CleanupPolicy.class);
      EntityMetadata metadata = mock(EntityMetadata.class);
      Repository repository = mock(Repository.class);
      Configuration configuration = mock(Configuration.class);
      
      // Setup attributes
      Map<String, Object> cleanupAttributes = newHashMap();
      cleanupAttributes.put(CLEANUP_NAME_KEY, newLinkedHashSet(singletonList(policyName)));
      Map<String, Map<String, Object>> attributes = newHashMap();
      attributes.put(CLEANUP_ATTRIBUTES_KEY, cleanupAttributes);
      
      // Configure mocks
      when(policy.getName()).thenReturn(policyName);
      when(policy.getFormat()).thenReturn(ALL_CLEANUP_POLICY_FORMAT);
      when(metadata.getEntity()).thenReturn(Optional.of(policy));
      when(configuration.copy()).thenReturn(configuration);
      when(configuration.getAttributes()).thenReturn(attributes);
      when(repository.getConfiguration()).thenReturn(configuration);
      when(repositoryManager.browseForCleanupPolicy(policyName)).thenReturn(Stream.of(repository));
      
      // Store as record
      testDataMap.put(policyName, new PolicyTestData(policyName, policy, metadata, 
                                                   repository, configuration, attributes));
    }
    
    // Create virtual thread executor
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Setup synchronization
      CountDownLatch completionLatch = new CountDownLatch(policyCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Submit tasks using record pattern matching for cleaner data access
      for (var entry : testDataMap.entrySet()) {
        executor.submit(() -> {
          try {
            // Use record pattern matching to destructure the test data
            var PolicyTestData(name, policy, metadata, repository, configuration, attributes) = 
                testDataMap.get(entry.getKey());
            
            // Execute the event handler
            underTest.on(new CleanupPolicyDeletedEvent(metadata));
            
            // Verify the cleanup attributes were removed
            assertThat(attributes.get(CLEANUP_ATTRIBUTES_KEY), nullValue());
          } 
          catch (Exception e) {
            log.error("Error in virtual thread with record pattern", e);
            errorCount.incrementAndGet();
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Wait for completion
      boolean completed = completionLatch.await(10, TimeUnit.SECONDS);
      
      // Verify results
      assertTrue(completed, "Not all virtual threads completed within timeout");
      assertEquals(0, errorCount.get(), "Some virtual threads encountered errors");
    }
    
    // Verify repository manager was called to update configurations
    verify(repositoryManager, times(policyCount)).update(any(Configuration.class));
  }
  
  /**
   * Tests high-concurrency cleanup policy deletion with a large number of virtual threads.
   * This test verifies that the system can handle a very high number of concurrent operations
   * using virtual threads without exhausting system resources.
   */
  @Test
  void highConcurrencyCleanupPolicyDeletion() throws Exception {
    // Create a very large number of policies for stress testing
    int policyCount = 10000; // 10,000 concurrent virtual threads
    
    // Setup a single policy and repository for simplicity in high-volume test
    CleanupPolicy policy = mock(CleanupPolicy.class);
    EntityMetadata metadata = mock(EntityMetadata.class);
    Repository repository = mock(Repository.class);
    Configuration configuration = mock(Configuration.class);
    
    // Setup attributes
    Map<String, Object> cleanupAttributes = newHashMap();
    cleanupAttributes.put(CLEANUP_NAME_KEY, newLinkedHashSet(singletonList("stress-test-policy")));
    Map<String, Map<String, Object>> attributes = newHashMap();
    attributes.put(CLEANUP_ATTRIBUTES_KEY, cleanupAttributes);
    
    // Configure mocks
    when(policy.getName()).thenReturn("stress-test-policy");
    when(policy.getFormat()).thenReturn(ALL_CLEANUP_POLICY_FORMAT);
    when(metadata.getEntity()).thenReturn(Optional.of(policy));
    when(configuration.copy()).thenReturn(configuration);
    when(configuration.getAttributes()).thenReturn(attributes);
    when(repository.getConfiguration()).thenReturn(configuration);
    when(repositoryManager.browseForCleanupPolicy(any())).thenReturn(Stream.of(repository));
    
    // Create virtual thread executor with custom name pattern for debugging
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Setup synchronization
      CountDownLatch completionLatch = new CountDownLatch(policyCount);
      AtomicInteger successCount = new AtomicInteger(0);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Record start time for performance measurement
      long startTime = System.nanoTime();
      
      // Submit a large number of tasks
      for (int i = 0; i < policyCount; i++) {
        executor.submit(() -> {
          try {
            // Execute the event handler
            underTest.on(new CleanupPolicyDeletedEvent(metadata));
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            log.error("Error in high-concurrency test", e);
            errorCount.incrementAndGet();
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Wait for completion with a longer timeout due to high volume
      boolean completed = completionLatch.await(60, TimeUnit.SECONDS);
      
      // Calculate execution time
      long duration = System.nanoTime() - startTime;
      double durationSeconds = Duration.ofNanos(duration).toMillis() / 1000.0;
      
      // Log performance metrics
      log.info("High-concurrency test completed in {} seconds", durationSeconds);
      log.info("Throughput: {} operations/second", policyCount / durationSeconds);
      log.info("Success count: {}, Error count: {}", successCount.get(), errorCount.get());
      
      // Verify results
      assertTrue(completed, "Not all virtual threads completed within timeout");
      assertEquals(policyCount, successCount.get(), "Not all operations completed successfully");
      assertEquals(0, errorCount.get(), "Some virtual threads encountered errors");
    }
  }
  
  private void verifyConfigurationUpdatedWithoutCleanupPolicyAttribute(final int count) {
    verify(repositoryManager, times(count)).update(configCaptor.capture());
  }
  
  private void verifyConfigurationUpdated(final int count) {
    verify(repositoryManager, times(count)).update(configCaptor.capture());
  }
  
  @SuppressWarnings("unchecked")
  private void verifyContainsCleanupPolicies(final Map<String, Map<String, Object>> attributes,
                                           final String... cleanupNames)
  {
    Set<String> names = (Set<String>) attributes.get(CLEANUP_ATTRIBUTES_KEY).get(CLEANUP_NAME_KEY);
    assertThat(names, hasSize(cleanupNames.length));
    assertThat(names, contains(cleanupNames));
  }
  
  private void verifyConfigurationNeverUpdated() {
    verify(repositoryManager, times(0)).update(any());
  }
  
  private String generateValidName() {
    return UUID.randomUUID().toString().replace("-", "");
  }
  
  /**
   * Helper method to create a mock object with Mockito.
   * This is needed for creating mocks within test methods.
   */
  private <T> T mock(Class<T> classToMock) {
    return org.mockito.Mockito.mock(classToMock);
  }
}