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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.blobstore.MockBlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.manager.RepositoryManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.testcontainers.shaded.com.google.common.collect.ImmutableMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link RepositoryApiImpl} using Java 21 Virtual Threads to validate
 * concurrent repository and blob store operations.
 */
@ExtendWith(MockitoExtension.class)
@VirtualThreadTestGroup
public class RepositoryApiImplVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_OPERATIONS = 100;
  private static final int TIMEOUT_SECONDS = 10;

  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private BlobStoreManager blobStoreManager;

  @InjectMocks
  private RepositoryApiImpl api;

  private BlobStore blobStore;
  private BlobStoreConfiguration blobStoreConfiguration;
  private Repository repository;

  @BeforeEach
  void setUp() {
    // Setup mock blob store
    blobStore = mock(BlobStore.class);
    blobStoreConfiguration = new MockBlobStoreConfiguration();
    blobStoreConfiguration.setName("test-blob-store");
    when(blobStore.getBlobStoreConfiguration()).thenReturn(blobStoreConfiguration);
    when(blobStoreManager.browse()).thenReturn(Collections.singletonList(blobStore));

    // Setup mock repository
    repository = mock(Repository.class);
    when(repository.getName()).thenReturn("test-repo");
    when(repositoryManager.browse()).thenReturn(Collections.singletonList(repository));
  }

  @Test
  void validateBlobStoreConcurrently() throws Exception {
    // Create a configuration with a valid blob store name
    Configuration config = configWithAttributes(
        ImmutableMap.of("storage", ImmutableMap.of("blobStoreName", "test-blob-store")));

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create and submit tasks
      CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
      AtomicInteger errorCount = new AtomicInteger(0);

      List<CompletableFuture<Void>> futures = IntStream.range(0, CONCURRENT_OPERATIONS)
          .mapToObj(i -> CompletableFuture.runAsync(() -> {
            try {
              api.validateBlobStore(config);
            } catch (Exception e) {
              errorCount.incrementAndGet();
            } finally {
              latch.countDown();
            }
          }, executor))
          .collect(Collectors.toList());

      // Wait for all tasks to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(completed, "Not all tasks completed within the timeout period");
      assertEquals(0, errorCount.get(), "Some validation tasks failed unexpectedly");

      // Verify the blobStoreManager.browse() was called the expected number of times
      verify(blobStoreManager, times(CONCURRENT_OPERATIONS)).browse();
    }
  }

  @Test
  void validateGroupMembersConcurrently() throws Exception {
    // Create a configuration with valid group members
    Configuration config = configWithAttributes(
        ImmutableMap.of("group", ImmutableMap.of("memberNames", Collections.singletonList("test-repo"))));

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create and submit tasks
      CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
      AtomicInteger errorCount = new AtomicInteger(0);

      List<CompletableFuture<Void>> futures = IntStream.range(0, CONCURRENT_OPERATIONS)
          .mapToObj(i -> CompletableFuture.runAsync(() -> {
            try {
              api.validateGroupMembers(config);
            } catch (Exception e) {
              errorCount.incrementAndGet();
            } finally {
              latch.countDown();
            }
          }, executor))
          .collect(Collectors.toList());

      // Wait for all tasks to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(completed, "Not all tasks completed within the timeout period");
      assertEquals(0, errorCount.get(), "Some validation tasks failed unexpectedly");

      // Verify the repositoryManager.browse() was called the expected number of times
      verify(repositoryManager, times(CONCURRENT_OPERATIONS)).browse();
    }
  }

  @Test
  void validateBlobStoreWithInvalidNameConcurrently() throws Exception {
    // Create a configuration with an invalid blob store name
    Configuration config = configWithAttributes(
        ImmutableMap.of("storage", ImmutableMap.of("blobStoreName", "non-existent-blob-store")));

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create and submit tasks
      CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
      AtomicInteger expectedErrorCount = new AtomicInteger(0);

      List<CompletableFuture<Void>> futures = IntStream.range(0, CONCURRENT_OPERATIONS)
          .mapToObj(i -> CompletableFuture.runAsync(() -> {
            try {
              assertThrows(IllegalArgumentException.class, () -> api.validateBlobStore(config));
              expectedErrorCount.incrementAndGet();
            } finally {
              latch.countDown();
            }
          }, executor))
          .collect(Collectors.toList());

      // Wait for all tasks to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(completed, "Not all tasks completed within the timeout period");
      assertEquals(CONCURRENT_OPERATIONS, expectedErrorCount.get(), 
          "Not all tasks threw the expected IllegalArgumentException");
    }
  }

  @Test
  void validateGroupMembersWithInvalidMembersConcurrently() throws Exception {
    // Create a configuration with invalid group members
    Configuration config = configWithAttributes(
        ImmutableMap.of("group", ImmutableMap.of("memberNames", Collections.singletonList("non-existent-repo"))));

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create and submit tasks
      CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
      AtomicInteger expectedErrorCount = new AtomicInteger(0);

      List<CompletableFuture<Void>> futures = IntStream.range(0, CONCURRENT_OPERATIONS)
          .mapToObj(i -> CompletableFuture.runAsync(() -> {
            try {
              assertThrows(IllegalStateException.class, () -> api.validateGroupMembers(config));
              expectedErrorCount.incrementAndGet();
            } finally {
              latch.countDown();
            }
          }, executor))
          .collect(Collectors.toList());

      // Wait for all tasks to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(completed, "Not all tasks completed within the timeout period");
      assertEquals(CONCURRENT_OPERATIONS, expectedErrorCount.get(), 
          "Not all tasks threw the expected IllegalStateException");
    }
  }

  @Test
  void validateNonGroupRepositoryConcurrently() throws Exception {
    // Create a configuration for a non-group repository (empty attributes)
    Configuration config = configWithAttributes(Collections.emptyMap());

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create and submit tasks
      CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
      AtomicInteger errorCount = new AtomicInteger(0);

      List<CompletableFuture<Void>> futures = IntStream.range(0, CONCURRENT_OPERATIONS)
          .mapToObj(i -> CompletableFuture.runAsync(() -> {
            try {
              assertDoesNotThrow(() -> api.validateGroupMembers(config));
            } catch (Exception e) {
              errorCount.incrementAndGet();
            } finally {
              latch.countDown();
            }
          }, executor))
          .collect(Collectors.toList());

      // Wait for all tasks to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(completed, "Not all tasks completed within the timeout period");
      assertEquals(0, errorCount.get(), "Some validation tasks failed unexpectedly");
    }
  }

  @Test
  void validateGroupMembersWithInvalidTypeConcurrently() throws Exception {
    // Create a configuration with invalid member type (String instead of Collection)
    Configuration config = configWithAttributes(
        ImmutableMap.of("group", ImmutableMap.of("memberNames", "invalid-type")));

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create and submit tasks
      CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
      AtomicInteger expectedErrorCount = new AtomicInteger(0);

      List<CompletableFuture<Void>> futures = IntStream.range(0, CONCURRENT_OPERATIONS)
          .mapToObj(i -> CompletableFuture.runAsync(() -> {
            try {
              assertThrows(ClassCastException.class, () -> api.validateGroupMembers(config));
              expectedErrorCount.incrementAndGet();
            } finally {
              latch.countDown();
            }
          }, executor))
          .collect(Collectors.toList());

      // Wait for all tasks to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(completed, "Not all tasks completed within the timeout period");
      assertEquals(CONCURRENT_OPERATIONS, expectedErrorCount.get(), 
          "Not all tasks threw the expected ClassCastException");
    }
  }

  /**
   * Helper method to create a mock Configuration with the specified attributes.
   */
  private Configuration configWithAttributes(final Map<String, Map<String, Object>> attributes) {
    Configuration config = mock(Configuration.class);
    when(config.getAttributes()).thenReturn(attributes);
    return config;
  }
}