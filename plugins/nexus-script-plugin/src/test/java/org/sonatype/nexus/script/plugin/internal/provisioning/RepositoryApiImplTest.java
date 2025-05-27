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
package org.sonatype.nexus.script.plugin.internal.provisioning;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.testcontainers.shaded.com.google.common.collect.ImmutableMap;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.MockBlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.manager.RepositoryManager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepositoryApiImplTest
    extends TestSupport
{
  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private BlobStoreManager blobStoreManager;

  @InjectMocks
  private RepositoryApiImpl api;

  @Test
  @DisplayName("Cannot validate blob store that does not exist")
  void cannotValidateBlobStoreThatDoesNotExist() {
    when(blobStoreManager.browse()).thenReturn(Collections.emptyList());
    
    assertThrows(IllegalArgumentException.class, () -> {
      api.validateBlobStore(configWithAttributes(ImmutableMap.of("storage", ImmutableMap.of("blobStoreName", "foo"))));
    });
  }

  @Test
  @DisplayName("Can validate given an existing blob store")
  void canValidateGivenAnExistingBlobStore() {
    BlobStore blobStore = mock(BlobStore.class);
    BlobStoreConfiguration configuration = new MockBlobStoreConfiguration();
    configuration.setName("foo");

    when(blobStoreManager.browse()).thenReturn(Collections.singletonList(blobStore));
    when(blobStore.getBlobStoreConfiguration()).thenReturn(configuration);

    assertDoesNotThrow(() -> {
      api.validateBlobStore(configWithAttributes(ImmutableMap.of("storage", ImmutableMap.of("blobStoreName", "foo"))));
    });

    verify(blobStoreManager).browse();
    verify(blobStore).getBlobStoreConfiguration();
  }

  @Test
  @DisplayName("Group member names must be a collection")
  void groupMemberNamesMustBeACollection() {
    assertThrows(ClassCastException.class, () -> {
      api.validateGroupMembers(configWithAttributes(ImmutableMap.of("group", ImmutableMap.of("memberNames", "foo"))));
    });
  }

  @Test
  @DisplayName("Cannot validate group that contains non-existent members")
  void cannotValidateGroupThatContainsNonExistentMembers() {
    when(repositoryManager.browse()).thenReturn(Collections.emptyList());
    
    assertThrows(IllegalStateException.class, () -> {
      api.validateGroupMembers(configWithAttributes(
          ImmutableMap.of("group", ImmutableMap.of("memberNames", Collections.singletonList("foo")))));
    });
  }

  @Test
  @DisplayName("Can validate group with existing members")
  void canValidateGroupWithExistingMembers() {
    Repository repository = mock(Repository.class);

    when(repositoryManager.browse()).thenReturn(Collections.singletonList(repository));
    when(repository.getName()).thenReturn("foo");

    assertDoesNotThrow(() -> {
      api.validateGroupMembers(configWithAttributes(
          ImmutableMap.of("group", ImmutableMap.of("memberNames", Collections.singletonList("foo")))));
    });

    verify(repositoryManager).browse();
    verify(repository).getName();
  }

  @Test
  @DisplayName("Non-group repositories pass group validation trivially")
  void nonGroupRepositoriesPassGroupValidationTrivially() {
    assertDoesNotThrow(() -> {
      api.validateGroupMembers(configWithAttributes(Collections.emptyMap()));
    });
  }

  @Test
  @DisplayName("Validate blob store operations with virtual threads")
  void validateBlobStoreOperationsWithVirtualThreads() throws Exception {
    // Setup test data
    BlobStore blobStore = mock(BlobStore.class);
    BlobStoreConfiguration configuration = new MockBlobStoreConfiguration();
    configuration.setName("foo");

    when(blobStoreManager.browse()).thenReturn(Collections.singletonList(blobStore));
    when(blobStore.getBlobStoreConfiguration()).thenReturn(configuration);

    // Create virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      int taskCount = 100;
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            api.validateBlobStore(configWithAttributes(ImmutableMap.of("storage", ImmutableMap.of("blobStoreName", "foo"))));
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "All tasks should complete within timeout");
      
      // Verify results
      assertTrue(errorCount.get() == 0, "No errors should occur during concurrent validation");
    } 
    finally {
      executor.shutdown();
    }
  }

  @Test
  @DisplayName("Validate group member operations with virtual threads")
  void validateGroupMemberOperationsWithVirtualThreads() throws Exception {
    // Setup test data
    Repository repository = mock(Repository.class);
    when(repositoryManager.browse()).thenReturn(Collections.singletonList(repository));
    when(repository.getName()).thenReturn("foo");

    // Create virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      int taskCount = 100;
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            api.validateGroupMembers(configWithAttributes(
                ImmutableMap.of("group", ImmutableMap.of("memberNames", Collections.singletonList("foo")))));
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "All tasks should complete within timeout");
      
      // Verify results
      assertTrue(errorCount.get() == 0, "No errors should occur during concurrent validation");
    } 
    finally {
      executor.shutdown();
    }
  }

  private Configuration configWithAttributes(final Map<String, Map<String, Object>> attributes) {
    Configuration config = mock(Configuration.class);
    when(config.getAttributes()).thenReturn(attributes);
    return config;
  }
}