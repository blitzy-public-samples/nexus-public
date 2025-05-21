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

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import static java.util.UUID.randomUUID;
import static java.util.stream.Stream.empty;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.core.IsNull.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.cleanup.config.CleanupPolicyConstants.CLEANUP_ATTRIBUTES_KEY;
import static org.sonatype.nexus.cleanup.config.CleanupPolicyConstants.CLEANUP_NAME_KEY;
import static org.sonatype.nexus.cleanup.storage.CleanupPolicy.ALL_CLEANUP_POLICY_FORMAT;

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
  private ArgumentCaptor<Configuration> configurationCaptor;
  
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
    // Create events using pattern matching for CleanupPolicy
    CleanupPolicyDeletedEvent event1 = createDeletedEvent(cleanupPolicy1);
    CleanupPolicyDeletedEvent event2 = createDeletedEvent(cleanupPolicy2);
    CleanupPolicyDeletedEvent event3 = createDeletedEvent(cleanupPolicy3);
    
    underTest.on(event1);
    underTest.on(event2);
    underTest.on(event3);
  
    assertThat(attributes1.get(CLEANUP_ATTRIBUTES_KEY), nullValue());
    assertThat(attributes2.get(CLEANUP_ATTRIBUTES_KEY), nullValue());
  
    verifyConfigurationUpdatedWithoutCleanupPolicyAttribute(3);
  }
  
  @Test
  void removedOneCleanupPolicyFromRepositoryWithMultiPolicy() {
    // Create event using pattern matching for CleanupPolicy
    CleanupPolicyDeletedEvent event = createDeletedEvent(cleanupPolicy3);
    
    underTest.on(event);
  
    verifyConfigurationUpdated(1);
    verifyContainsCleanupPolicies(attributes2, cleanupPolicy2.getName());
  }
  
  @Test
  void onlyRepositoryWithMatchingCleanupPolicyGetsAttributesRemoved() {
    // Create event using pattern matching for CleanupPolicy
    CleanupPolicyDeletedEvent event = createDeletedEvent(cleanupPolicy1);
    
    underTest.on(event);
  
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
  
    // Create event using pattern matching for CleanupPolicy
    CleanupPolicyDeletedEvent event = createDeletedEvent(cleanupPolicy1);
    
    underTest.on(event);
  
    assertThat(attributes1.get(CLEANUP_ATTRIBUTES_KEY), nullValue());
    assertThat(attributes2.get(CLEANUP_ATTRIBUTES_KEY), notNullValue());
  
    verifyConfigurationUpdatedWithoutCleanupPolicyAttribute(2);
    verifyContainsCleanupPolicies(attributes2, cleanupPolicy2.getName(), cleanupPolicy3.getName());
  }
  
  @Test
  void configurationWithoutRepositoryDoesNotGetUpdated() {
    when(repositoryManager.browseForCleanupPolicy(any())).thenReturn(empty());
  
    // Create events using pattern matching for CleanupPolicy
    CleanupPolicyDeletedEvent event1 = createDeletedEvent(cleanupPolicy1);
    CleanupPolicyDeletedEvent event2 = createDeletedEvent(cleanupPolicy2);
    
    underTest.on(event1);
    underTest.on(event2);
  
    verifyConfigurationNeverUpdated();
  }
  
  @Test
  void virtualThreadProcessingHandlesMultipleEventsCorrectly() throws Exception {
    // Configure test for virtual threads
    int eventCount = 100;
    CountDownLatch latch = new CountDownLatch(eventCount);
    AtomicInteger processedCount = new AtomicInteger(0);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple events to be processed concurrently
      for (int i = 0; i < eventCount; i++) {
        final int index = i % 3; // Cycle through our 3 cleanup policies
        executor.submit(() -> {
          try {
            CleanupPolicy policy = switch (index) {
              case 0 -> cleanupPolicy1;
              case 1 -> cleanupPolicy2;
              case 2 -> cleanupPolicy3;
              default -> throw new IllegalStateException("Unexpected index: " + index);
            };
            
            CleanupPolicyDeletedEvent event = createDeletedEvent(policy);
            underTest.on(event);
            processedCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all events to be processed
      latch.await(10, TimeUnit.SECONDS);
    }
    
    // Verify that all events were processed
    assertThat(processedCount.get(), hasSize(eventCount));
    
    // Verify that repository manager was called the expected number of times
    // Each policy is used approximately eventCount/3 times
    verify(repositoryManager, times(eventCount)).browseForCleanupPolicy(any());
  }
  
  private void verifyConfigurationUpdatedWithoutCleanupPolicyAttribute(final int count) {
    verify(repositoryManager, times(count)).update(configurationCaptor.capture());
  }
  
  private void verifyConfigurationUpdated(final int count) {
    verify(repositoryManager, times(count)).update(configurationCaptor.capture());
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
    return randomUUID().toString().replace("-", "");
  }
  
  /**
   * Creates a CleanupPolicyDeletedEvent for the given policy using Java 21 pattern matching
   */
  private CleanupPolicyDeletedEvent createDeletedEvent(final CleanupPolicy policy) {
    return new CleanupPolicyDeletedEvent() {
      @Override
      public boolean isLocal() {
        return true;
      }

      @Override
      public CleanupPolicy getCleanupPolicy() {
        return policy;
      }
    };
  }
}