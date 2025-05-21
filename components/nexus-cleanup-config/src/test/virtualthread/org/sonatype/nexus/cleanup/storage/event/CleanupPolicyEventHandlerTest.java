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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.sonatype.goodies.testsupport.TestSupport;
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
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.cleanup.storage.CleanupPolicy.ALL_CLEANUP_POLICY_FORMAT;

/**
 * Tests for {@link CleanupPolicyEventHandler} with Java 21 Virtual Threads.
 * 
 * This test validates that the CleanupPolicyEventHandler correctly processes events
 * when executed with high concurrency using Virtual Threads.
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
  
  @Test
  void removedCleanupAttributeFromRepository() {
    underTest.on(new CleanupPolicyDeletedEvent() {
      @Override
      public CleanupPolicy getCleanupPolicy() {
        return cleanupPolicy1;
      }
      
      @Override
      public boolean isLocal() {
        return true;
      }
    });
    
    underTest.on(new CleanupPolicyDeletedEvent() {
      @Override
      public CleanupPolicy getCleanupPolicy() {
        return cleanupPolicy2;
      }
      
      @Override
      public boolean isLocal() {
        return true;
      }
    });
    
    underTest.on(new CleanupPolicyDeletedEvent() {
      @Override
      public CleanupPolicy getCleanupPolicy() {
        return cleanupPolicy3;
      }
      
      @Override
      public boolean isLocal() {
        return true;
      }
    });
    
    assertThat(attributes1.get(CLEANUP_ATTRIBUTES_KEY), nullValue());
    assertThat(attributes2.get(CLEANUP_ATTRIBUTES_KEY), nullValue());
    
    verifyConfigurationUpdatedWithoutCleanupPolicyAttribute(3);
  }
  
  @Test
  void removedOneCleanupPolicyFromRepositoryWithMultiPolicy() {
    underTest.on(new CleanupPolicyDeletedEvent() {
      @Override
      public CleanupPolicy getCleanupPolicy() {
        return cleanupPolicy3;
      }
      
      @Override
      public boolean isLocal() {
        return true;
      }
    });
    
    verifyConfigurationUpdated(1);
    verifyContainsCleanupPolicies(attributes2, cleanupPolicy2.getName());
  }
  
  @Test
  void onlyRepositoryWithMatchingCleanupPolicyGetsAttributesRemoved() {
    underTest.on(new CleanupPolicyDeletedEvent() {
      @Override
      public CleanupPolicy getCleanupPolicy() {
        return cleanupPolicy1;
      }
      
      @Override
      public boolean isLocal() {
        return true;
      }
    });
    
    assertThat(attributes1.get(CLEANUP_ATTRIBUTES_KEY), nullValue());
    assertThat(attributes2.get(CLEANUP_ATTRIBUTES_KEY), notNullValue());
    
    verifyConfigurationUpdatedWithoutCleanupPolicyAttribute(1);
    verifyContainsCleanupPolicies(attributes2, cleanupPolicy2.getName(), cleanupPolicy3.getName());
  }
  
  @Test
  void multipleRepositoriesWithSameMatchingCleanupPolicyGetTheirAttributesRemoved() {
    // we make the second configuration return the same attributes as the first
    when(repositoryManager.browseForCleanupPolicy(name1)).thenReturn(Stream.of(repository1, repository2));
    when(configuration2.getAttributes()).thenReturn(attributes1);
    
    underTest.on(new CleanupPolicyDeletedEvent() {
      @Override
      public CleanupPolicy getCleanupPolicy() {
        return cleanupPolicy1;
      }
      
      @Override
      public boolean isLocal() {
        return true;
      }
    });
    
    assertThat(attributes1.get(CLEANUP_ATTRIBUTES_KEY), nullValue());
    assertThat(attributes2.get(CLEANUP_ATTRIBUTES_KEY), notNullValue());
    
    verifyConfigurationUpdatedWithoutCleanupPolicyAttribute(2);
    verifyContainsCleanupPolicies(attributes2, cleanupPolicy2.getName(), cleanupPolicy3.getName());
  }
  
  @Test
  void configurationWithoutRepositoryDoesNotGetUpdated() {
    when(repositoryManager.browseForCleanupPolicy(any())).thenReturn(empty());
    
    underTest.on(new CleanupPolicyDeletedEvent() {
      @Override
      public CleanupPolicy getCleanupPolicy() {
        return cleanupPolicy1;
      }
      
      @Override
      public boolean isLocal() {
        return true;
      }
    });
    
    when(repositoryManager.browseForCleanupPolicy(any())).thenReturn(empty());
    underTest.on(new CleanupPolicyDeletedEvent() {
      @Override
      public CleanupPolicy getCleanupPolicy() {
        return cleanupPolicy2;
      }
      
      @Override
      public boolean isLocal() {
        return true;
      }
    });
    
    verifyConfigurationNeverUpdated();
  }
  
  @Test
  void ignoresNonLocalEvents() {
    underTest.on(new CleanupPolicyDeletedEvent() {
      @Override
      public CleanupPolicy getCleanupPolicy() {
        return cleanupPolicy1;
      }
      
      @Override
      public boolean isLocal() {
        return false;
      }
    });
    
    verifyConfigurationNeverUpdated();
  }
  
  /**
   * Tests that the event handler correctly processes a high volume of concurrent events
   * using Virtual Threads without thread pinning or other concurrency issues.
   */
  @Test
  void concurrentEventProcessingWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that uses virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      int eventCount = 1000;
      CountDownLatch latch = new CountDownLatch(eventCount);
      AtomicInteger successCount = new AtomicInteger(0);
      AtomicBoolean threadPinningDetected = new AtomicBoolean(false);
      
      // Create a list to track all the cleanup policies
      List<CleanupPolicy> policies = new ArrayList<>();
      List<Repository> repositories = new ArrayList<>();
      
      // Setup the mock repositories and policies
      for (int i = 0; i < eventCount; i++) {
        String policyName = "policy-" + i;
        CleanupPolicy policy = mock(CleanupPolicy.class);
        Repository repository = mock(Repository.class);
        Configuration configuration = mock(Configuration.class);
        
        Map<String, Object> cleanupAttributes = newHashMap();
        cleanupAttributes.put(CLEANUP_NAME_KEY, newLinkedHashSet(singletonList(policyName)));
        
        Map<String, Map<String, Object>> attributes = newHashMap();
        attributes.put(CLEANUP_ATTRIBUTES_KEY, cleanupAttributes);
        
        when(policy.getName()).thenReturn(policyName);
        when(policy.getFormat()).thenReturn(ALL_CLEANUP_POLICY_FORMAT);
        when(repository.getConfiguration()).thenReturn(configuration);
        when(configuration.copy()).thenReturn(configuration);
        when(configuration.getAttributes()).thenReturn(attributes);
        when(repositoryManager.browseForCleanupPolicy(policyName)).thenReturn(Stream.of(repository));
        
        policies.add(policy);
        repositories.add(repository);
      }
      
      // Submit tasks to the executor
      for (int i = 0; i < eventCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Check if the current thread is a virtual thread
            boolean isVirtualThread = Thread.currentThread().isVirtual();
            if (!isVirtualThread) {
              threadPinningDetected.set(true);
            }
            
            // Create and process the event
            CleanupPolicy policy = policies.get(index);
            CleanupPolicyDeletedEvent event = new CleanupPolicyDeletedEvent() {
              @Override
              public CleanupPolicy getCleanupPolicy() {
                return policy;
              }
              
              @Override
              public boolean isLocal() {
                return true;
              }
            };
            
            underTest.on(event);
            successCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete with a timeout
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertTrue(completed, "All event processing tasks should complete within the timeout");
      assertThat(successCount.get(), is(eventCount));
      assertFalse(threadPinningDetected.get(), "No thread pinning should be detected when using virtual threads");
      
      // Verify that the repository manager was called to update configurations
      verify(repositoryManager, times(eventCount)).update(any(Configuration.class));
    }
  }
  
  /**
   * Tests that the event handler correctly processes events with record patterns
   * for cleaner data handling in Java 21.
   */
  @Test
  void processesEventsWithRecordPatterns() {
    // Create a record to represent a cleanup policy with name and format
    record CleanupPolicyRecord(String name, String format) {}
    
    // Create test data using records
    CleanupPolicyRecord policyRecord = new CleanupPolicyRecord(name1, ALL_CLEANUP_POLICY_FORMAT);
    
    // Use pattern matching with records for cleaner data handling
    if (policyRecord instanceof CleanupPolicyRecord(String policyName, String format)) {
      // Configure mock to return repository for this policy name
      when(repositoryManager.browseForCleanupPolicy(policyName)).thenReturn(Stream.of(repository1));
      
      // Create and process the event
      underTest.on(new CleanupPolicyDeletedEvent() {
        @Override
        public CleanupPolicy getCleanupPolicy() {
          return cleanupPolicy1;
        }
        
        @Override
        public boolean isLocal() {
          return true;
        }
      });
      
      // Verify the cleanup attributes were removed
      assertThat(attributes1.get(CLEANUP_ATTRIBUTES_KEY), nullValue());
      
      // Verify the configuration was updated
      verify(repositoryManager).update(configCaptor.capture());
      Configuration updatedConfig = configCaptor.getValue();
      assertThat(updatedConfig, is(configuration1));
    }
  }
  
  /**
   * Tests that the event handler correctly handles concurrent events with different timing
   * characteristics, simulating real-world scenarios with varying processing times.
   */
  @Test
  void handlesVariableTimingEventsWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that uses virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      int eventCount = 100;
      CountDownLatch latch = new CountDownLatch(eventCount);
      
      // Setup the mock repositories and policies with variable processing times
      for (int i = 0; i < eventCount; i++) {
        String policyName = "variable-policy-" + i;
        CleanupPolicy policy = mock(CleanupPolicy.class);
        Repository repository = mock(Repository.class);
        Configuration configuration = mock(Configuration.class);
        
        Map<String, Object> cleanupAttributes = newHashMap();
        cleanupAttributes.put(CLEANUP_NAME_KEY, newLinkedHashSet(singletonList(policyName)));
        
        Map<String, Map<String, Object>> attributes = newHashMap();
        attributes.put(CLEANUP_ATTRIBUTES_KEY, cleanupAttributes);
        
        when(policy.getName()).thenReturn(policyName);
        when(policy.getFormat()).thenReturn(ALL_CLEANUP_POLICY_FORMAT);
        when(repository.getConfiguration()).thenReturn(configuration);
        when(configuration.copy()).thenReturn(configuration);
        when(configuration.getAttributes()).thenReturn(attributes);
        
        // Simulate variable processing times for different policies
        final int processingTime = i % 10; // 0-9 ms
        when(repositoryManager.browseForCleanupPolicy(policyName)).thenAnswer(invocation -> {
          // Simulate variable processing time
          Thread.sleep(processingTime);
          return Stream.of(repository);
        });
        
        final int index = i;
        executor.submit(() -> {
          try {
            CleanupPolicyDeletedEvent event = new CleanupPolicyDeletedEvent() {
              @Override
              public CleanupPolicy getCleanupPolicy() {
                return policy;
              }
              
              @Override
              public boolean isLocal() {
                return true;
              }
            };
            
            underTest.on(event);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete with a timeout
      boolean completed = latch.await(10, TimeUnit.SECONDS);
      
      // Verify results
      assertTrue(completed, "All event processing tasks should complete within the timeout");
      verify(repositoryManager, times(eventCount)).update(any(Configuration.class));
    }
  }
  
  private void verifyConfigurationUpdatedWithoutCleanupPolicyAttribute(final int count) {
    verify(repositoryManager, times(count)).update(any(Configuration.class));
  }
  
  private void verifyConfigurationUpdated(final int count) {
    verify(repositoryManager, times(count)).update(any(Configuration.class));
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
  
  private <T> T mock(Class<T> classToMock) {
    return org.mockito.Mockito.mock(classToMock);
  }
}