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
package org.sonatype.nexus.content.maven.internal;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;
import org.sonatype.nexus.repository.maven.internal.search.MavenVersionNormalizer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.Collections.emptySet;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.maven.internal.Attributes.P_BASE_VERSION;
import static org.sonatype.nexus.repository.maven.internal.Constants.SNAPSHOT_VERSION_SUFFIX;

/**
 * Tests for {@link MavenSearchDocumentProducer} with Java 21 compatibility.
 * 
 * This test class has been updated to use JUnit Jupiter (JUnit 5) and is compatible with Java 21.
 * The test methods follow the JUnit Jupiter naming convention and leverage Mockito 4.11.0 with
 * MockitoExtension for better integration with JUnit Jupiter.
 * 
 * Java 21 features that could be utilized in the actual implementation include:
 * - Virtual Threads for concurrent operations
 * - Pattern Matching for more concise type checking
 * - Record Patterns for destructuring data
 * - String Templates for improved logging and messages
 */
@ExtendWith(MockitoExtension.class)
class MavenSearchDocumentProducerTest
    extends TestSupport
{
  private static final String VERSION_NUMBER = "1.0.0";

  private NestedAttributesMap attributes = new NestedAttributesMap();

  private NestedAttributesMap childAttributes = attributes.child(Maven2Format.NAME);

  @Mock
  private FluentComponent component;

  private MavenSearchDocumentProducer underTest;

  @BeforeEach
  void setup() {
    underTest =
        new MavenSearchDocumentProducer(emptySet(), new MavenVersionNormalizer(), new MavenPreReleaseEvaluator());
    when(component.attributes()).thenReturn(attributes);
  }

  @Test
  @DisplayName("isPrerelease should return false when base version is null")
  void isPrereleaseReturnsFalseWhenBaseVersionIsNull() {
    assertFalse(underTest.isPrerelease(component));
  }

  @Test
  @DisplayName("isPrerelease should return false when base version is not a snapshot")
  void isPrereleaseReturnsFalseWhenBaseVersionIsNotSnapshot() {
    childAttributes.set(P_BASE_VERSION, VERSION_NUMBER);

    assertFalse(underTest.isPrerelease(component));
  }

  @Test
  @DisplayName("isPrerelease should return true when base version is a snapshot")
  void isPrereleaseReturnsTrueWhenBaseVersionIsSnapshot() {
    childAttributes.set(P_BASE_VERSION, VERSION_NUMBER + SNAPSHOT_VERSION_SUFFIX);

    assertTrue(underTest.isPrerelease(component));
  }
  
  /**
   * Demonstrates how pattern matching in Java 21 could be used to evaluate different version types.
   * This test validates that the isPrerelease method correctly identifies snapshot versions
   * using a more complex version string.
   */
  @Test
  @DisplayName("isPrerelease should identify complex snapshot versions correctly")
  void isPrereleaseCorrectlyIdentifiesComplexSnapshotVersions() {
    // Test with a more complex version string that includes build information
    String complexVersion = VERSION_NUMBER + "-build123" + SNAPSHOT_VERSION_SUFFIX;
    childAttributes.set(P_BASE_VERSION, complexVersion);
    
    // The implementation could use pattern matching to identify snapshot versions more efficiently
    assertTrue(underTest.isPrerelease(component), 
        STR."Version \{complexVersion} should be identified as a snapshot version");
  }
  
  /**
   * Demonstrates how Virtual Threads in Java 21 could be used to improve performance
   * when processing multiple components concurrently.
   * 
   * This test simulates concurrent access to the MavenSearchDocumentProducer
   * using Java 21's Virtual Threads for lightweight concurrency.
   */
  @Test
  @DisplayName("Concurrent prerelease checking should work efficiently with Virtual Threads")
  void concurrentPrereleaseCheckingWithVirtualThreads() throws Exception {
    // Set up test data
    childAttributes.set(P_BASE_VERSION, VERSION_NUMBER + SNAPSHOT_VERSION_SUFFIX);
    
    // Create a virtual thread executor
    int taskCount = 100;
    AtomicInteger successCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          // Each virtual thread checks if the component is a prerelease
          if (underTest.isPrerelease(component)) {
            successCount.incrementAndGet();
          }
        });
      }
      
      // Shutdown and wait for completion
      executor.shutdown();
      boolean completed = executor.awaitTermination(5, TimeUnit.SECONDS);
      
      // Verify results
      assertTrue(completed, "All virtual threads should complete within the timeout");
      assertTrue(successCount.get() == taskCount, 
          STR."All \{taskCount} virtual threads should successfully identify the component as prerelease");
    }
  }
}