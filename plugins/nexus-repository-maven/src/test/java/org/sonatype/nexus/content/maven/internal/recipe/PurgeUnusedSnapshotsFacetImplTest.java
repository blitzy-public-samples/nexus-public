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
package org.sonatype.nexus.content.maven.internal.recipe;

import java.time.LocalDate;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.content.maven.MavenContentFacet;
import org.sonatype.nexus.content.maven.store.Maven2ComponentStore;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.facet.ContentFacetStores;
import org.sonatype.nexus.repository.content.fluent.FluentComponents;
import org.sonatype.nexus.repository.content.store.AssetStore;
import org.sonatype.nexus.repository.maven.PurgeUnusedSnapshotsFacet;
import org.sonatype.nexus.repository.maven.internal.group.MavenGroupFacet;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.repository.types.HostedType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test for {@link PurgeUnusedSnapshotsFacetImpl} with Java 21 compatibility.
 * 
 * This test class has been updated to use JUnit Jupiter (JUnit 5) and Mockito 4.11.0
 * to ensure compatibility with Java 21 features including virtual threads.
 */
@ExtendWith(MockitoExtension.class)
public class PurgeUnusedSnapshotsFacetImplTest
    extends TestSupport
{
  static final int FIND_UNUSED_LIMIT = 10;

  static final int NUMBER_OF_COMPONENTS = 35;

  static final int NUMBER_OF_DAYS_UNUSED = 5;

  static final Integer CONTENT_REPOSITORY_ID = 7;

  static final Integer COMPONENT_ID = 3;

  static final LocalDate OLD_SNAPSHOTS_DATE_LIMIT = LocalDate.now().minusDays(NUMBER_OF_DAYS_UNUSED);

  @Mock
  Repository repository;

  @Mock
  MavenGroupFacet mavenGroupFacet;

  @Mock
  MavenContentFacetImpl mavenContentFacet;

  @Mock
  ContentFacetStores contentFacetStores;

  @Mock
  Maven2ComponentStore componentStore;

  @Mock
  AssetStore assetStore;

  @Mock
  FluentComponents fluentComponents;

  GroupType groupType = new GroupType();

  HostedType hostedType = new HostedType();

  // Not using @InjectMocks because we need to manually create and attach the facet
  PurgeUnusedSnapshotsFacetImpl purgeUnusedSnapshotsFacet;

  @BeforeEach
  public void setUp() throws Exception {
    purgeUnusedSnapshotsFacet = new PurgeUnusedSnapshotsFacetImpl(groupType, hostedType, FIND_UNUSED_LIMIT);
    purgeUnusedSnapshotsFacet.attach(repository);

    when(repository.getType()).thenReturn(new HostedType());
    when(repository.facet(MavenContentFacet.class)).thenReturn(mavenContentFacet);
    when(repository.getName()).thenReturn("maven2-unit-test-hosted-repo");
    when(repository.facet(PurgeUnusedSnapshotsFacet.class)).thenReturn(purgeUnusedSnapshotsFacet);

    // Using reflection to set internal state instead of Whitebox
    // This is more compatible with Java 21 and modern testing practices
    try {
      java.lang.reflect.Field componentStoreField = ContentFacetStores.class.getDeclaredField("componentStore");
      componentStoreField.setAccessible(true);
      componentStoreField.set(contentFacetStores, componentStore);

      java.lang.reflect.Field assetStoreField = ContentFacetStores.class.getDeclaredField("assetStore");
      assetStoreField.setAccessible(true);
      assetStoreField.set(contentFacetStores, assetStore);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new RuntimeException("Failed to set up test", e);
    }

    // Final methods can't be mocked so mocking fields here
    when(mavenContentFacet.stores()).thenReturn(contentFacetStores);
    when(mavenContentFacet.components()).thenReturn(fluentComponents);
    when(mavenContentFacet.contentRepositoryId()).thenReturn(CONTENT_REPOSITORY_ID);

    when(fluentComponents.count()).thenReturn(NUMBER_OF_COMPONENTS);

    when(componentStore.selectUnusedSnapshots(CONTENT_REPOSITORY_ID, OLD_SNAPSHOTS_DATE_LIMIT, FIND_UNUSED_LIMIT))
        .thenReturn(Collections.singletonList(COMPONENT_ID))
        .thenReturn(Collections.emptyList()); // Imitates deleting all components during the first iteration.
  }

  @Test
  public void deleteUnusedSnapshotComponentsInHostedRepo() {
    purgeUnusedSnapshotsFacet.purgeUnusedSnapshots(NUMBER_OF_DAYS_UNUSED);

    verify(componentStore, times(2)).selectUnusedSnapshots(anyInt(), any(), anyLong());
    verify(mavenContentFacet, times(1)).deleteComponents(new int[]{COMPONENT_ID});
  }

  @Test
  public void deleteUnusedSnapshotComponentsInGroupOfHostedRepo() throws Exception {
    Repository groupRepository = Mockito.mock(Repository.class);
    when(groupRepository.getType()).thenReturn(new GroupType());
    when(groupRepository.getName()).thenReturn("maven2-unit-test-group-repo");
    when(groupRepository.facet(MavenGroupFacet.class)).thenReturn(mavenGroupFacet);
    when(mavenGroupFacet.leafMembers()).thenReturn(Collections.singletonList(repository));

    PurgeUnusedSnapshotsFacet purgeUnusedSnapshotsFacetForHostedRepo
        = new PurgeUnusedSnapshotsFacetImpl(groupType, hostedType, FIND_UNUSED_LIMIT);
    purgeUnusedSnapshotsFacetForHostedRepo.attach(groupRepository);

    purgeUnusedSnapshotsFacetForHostedRepo.purgeUnusedSnapshots(NUMBER_OF_DAYS_UNUSED);

    verify(componentStore, times(2)).selectUnusedSnapshots(anyInt(), any(), anyLong());
    verify(mavenContentFacet, times(1)).deleteComponents(new int[]{COMPONENT_ID});
  }

  /**
   * Tests the purge operation using Java 21 Virtual Threads for concurrent processing.
   * This demonstrates how the purge operation could be optimized for high concurrency
   * using Java 21's lightweight thread implementation.
   */
  @Test
  public void concurrentPurgeOperationsWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      int taskCount = 10;
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Set up component store to handle concurrent requests
      when(componentStore.selectUnusedSnapshots(anyInt(), any(), anyLong()))
          .thenReturn(Collections.singletonList(COMPONENT_ID))
          .thenReturn(Collections.emptyList());
      
      // Submit multiple concurrent purge tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            purgeUnusedSnapshotsFacet.purgeUnusedSnapshots(NUMBER_OF_DAYS_UNUSED);
            successCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(5, TimeUnit.SECONDS);
      
      // Verify all tasks completed successfully
      assertEquals(taskCount, successCount.get(), "All purge operations should complete successfully");
      
      // Verify the component store was called the expected number of times
      // Each task calls selectUnusedSnapshots twice (once with results, once empty)
      verify(componentStore, times(taskCount * 2)).selectUnusedSnapshots(anyInt(), any(), anyLong());
      verify(mavenContentFacet, times(taskCount)).deleteComponents(new int[]{COMPONENT_ID});
    }
  }
}