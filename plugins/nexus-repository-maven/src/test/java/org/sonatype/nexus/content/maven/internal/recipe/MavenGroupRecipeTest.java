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

import javax.inject.Provider;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.content.maven.internal.index.MavenContentGroupIndexFacet;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.group.GroupHandler;
import org.sonatype.nexus.repository.maven.PurgeUnusedSnapshotsFacet;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;
import org.sonatype.nexus.repository.maven.internal.group.MavenGroupFacet;
import org.sonatype.nexus.repository.maven.internal.group.MergingGroupHandler;
import org.sonatype.nexus.repository.types.GroupType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MavenGroupRecipe}.
 * 
 * Updated for Java 21 compatibility using JUnit Jupiter and Mockito 4.11.0.
 */
@ExtendWith(MockitoExtension.class)
public class MavenGroupRecipeTest
    extends MavenRecipeTestSupport
{
  @Mock
  private Repository mavenGroupRepository;

  @Mock
  private MavenGroupFacet mavenGroupFacet;

  private final Provider<MavenGroupFacet> mavenGroupFacetProvider = () -> mavenGroupFacet;

  @Mock
  private MavenContentGroupIndexFacet mavenContentIndexFacet;

  private final Provider<MavenContentGroupIndexFacet> mavenContentIndexFacetProvider = () -> mavenContentIndexFacet;

  @Mock
  private PurgeUnusedSnapshotsFacet purgeUnusedSnapshotsFacet;

  private final Provider<PurgeUnusedSnapshotsFacet> purgeUnusedSnapshotsFacetProvider = () -> purgeUnusedSnapshotsFacet;

  @Mock
  private MergingGroupHandler mergingGroupHandler;

  @Mock
  private MavenContentIndexGroupHandler mavenContentIndexGroupHandler;

  @Mock
  private GroupHandler groupHandler;

  private MavenGroupRecipe underTest;

  @BeforeEach
  public void setup() {
    underTest = new MavenGroupRecipe(new GroupType(), new Maven2Format(), mavenContentIndexFacetProvider,
        mavenGroupFacetProvider, purgeUnusedSnapshotsFacetProvider, groupHandler, mergingGroupHandler,
        mavenContentIndexGroupHandler);
    mockFacets(underTest);
    mockHandlers(underTest);
  }

  @Test
  public void testExpectedFacetsAreAttached() throws Exception {
    underTest.apply(mavenGroupRepository);
    verify(mavenGroupRepository).attach(securityFacet);
    verify(mavenGroupRepository).attach(mavenGroupFacet);
    verify(mavenGroupRepository).attach(mavenContentFacet);
    verify(mavenGroupRepository).attach(mavenContentIndexFacet);
    verify(mavenGroupRepository).attach(browseFacet);
    verify(mavenGroupRepository).attach(purgeUnusedSnapshotsFacet);
    verify(mavenGroupRepository).attach(viewFacet);
    verify(mavenGroupRepository).attach(mavenMaintenanceFacet);
    verify(mavenGroupRepository).attach(removeSnapshotsFacet);
  }
  
  /**
   * Demonstrates Java 21 pattern matching for switch statements when evaluating repository types.
   * This test validates that the recipe correctly identifies its format and type.
   */
  @Test
  public void testRecipeFormatAndTypeUsingPatternMatching() {
    // Using Java 21 pattern matching for switch to determine format type
    String formatName = switch (underTest.getFormat()) {
      case Maven2Format format -> "maven2";
      case null -> "unknown";
      default -> "other";
    };
    
    // Using Java 21 pattern matching for switch to determine repository type
    String typeName = switch (underTest.getType()) {
      case GroupType type -> "group";
      case null -> "unknown";
      default -> "other";
    };
    
    // Verify the format and type are correctly identified
    assertEquals("maven2", formatName, "Recipe should have maven2 format");
    assertEquals("group", typeName, "Recipe should have group type");
  }
  
  /**
   * Demonstrates Java 21 Virtual Threads capability for concurrent operations.
   * This test simulates multiple concurrent repository operations using virtual threads.
   */
  @Test
  public void testConcurrentOperationsWithVirtualThreads() {
    // Configure mock repository for concurrent operations
    when(mavenGroupRepository.getName()).thenReturn("maven-group");
    
    // Test parameters
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create a virtual thread executor (Java 21 feature)
    assertTimeout(java.time.Duration.ofSeconds(5), () -> {
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        // Submit multiple concurrent tasks using virtual threads
        for (int i = 0; i < taskCount; i++) {
          executor.submit(() -> {
            try {
              // Simulate repository operation
              underTest.apply(mavenGroupRepository);
              successCount.incrementAndGet();
            } 
            finally {
              latch.countDown();
            }
          });
        }
        
        // Wait for all tasks to complete
        latch.await(3, TimeUnit.SECONDS);
        
        // Verify all operations completed successfully
        assertEquals(taskCount, successCount.get(), 
            "All virtual thread operations should complete successfully");
      }
    });
  }
}