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
package org.sonatype.nexus.repository.maven.tasks;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.maven.MavenFacet;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.repository.types.HostedType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.maven.VersionPolicy.MIXED;
import static org.sonatype.nexus.repository.maven.VersionPolicy.RELEASE;
import static org.sonatype.nexus.repository.maven.VersionPolicy.SNAPSHOT;

/**
 * Tests for {@link PurgeMavenUnusedSnapshotsTask} that verify the task's appliesTo logic
 * for different repository types and version policies.
 * 
 * @since 3.next
 */
@ExtendWith(MockitoExtension.class)
@Category(VirtualThreadTestGroup.class)
public class PurgeMavenUnusedSnapshotsTaskTest
    extends TestSupport
{
  @Mock
  private Repository repository;

  @Mock
  private MavenFacet mavenFacet;

  private final Type groupType = new GroupType();

  private final Type hostedType = new HostedType();

  private final Format maven2Format = new Maven2Format();

  @Mock
  private Format dockerFormat;

  private final PurgeMavenUnusedSnapshotsTask underTest =
      new PurgeMavenUnusedSnapshotsTask(groupType, hostedType, maven2Format);

  @BeforeEach
  void setup() {
    when(repository.facet(MavenFacet.class)).thenReturn(mavenFacet);
  }

  @Test
  void appliesToMavenHostedSnapshot() {
    when(repository.getFormat()).thenReturn(maven2Format);
    when(repository.getType()).thenReturn(hostedType);
    when(mavenFacet.getVersionPolicy()).thenReturn(SNAPSHOT);
    assertTrue(underTest.appliesTo(repository));
  }

  @Test
  void appliesToMavenGroupSnapshot() {
    when(repository.getFormat()).thenReturn(maven2Format);
    when(repository.getType()).thenReturn(groupType);
    when(mavenFacet.getVersionPolicy()).thenReturn(SNAPSHOT);
    assertTrue(underTest.appliesTo(repository));
  }

  @Test
  void appliesToMavenHostedMixed() {
    when(repository.getFormat()).thenReturn(maven2Format);
    when(repository.getType()).thenReturn(hostedType);
    when(mavenFacet.getVersionPolicy()).thenReturn(MIXED);
    assertTrue(underTest.appliesTo(repository));
  }

  @Test
  void appliesToMavenHostedNoVersionPolicy() {
    when(repository.getFormat()).thenReturn(maven2Format);
    when(repository.getType()).thenReturn(hostedType);
    when(mavenFacet.getVersionPolicy()).thenReturn(null);
    assertTrue(underTest.appliesTo(repository));
  }

  @Test
  void doesNotApplyToDockerGroupSnapshot() {
    when(repository.getFormat()).thenReturn(dockerFormat);
    when(repository.getType()).thenReturn(groupType);
    when(mavenFacet.getVersionPolicy()).thenReturn(SNAPSHOT);
    assertFalse(underTest.appliesTo(repository));
  }

  @Test
  void doesNotApplyToMavenHostedRelease() {
    when(repository.getFormat()).thenReturn(maven2Format);
    when(repository.getType()).thenReturn(hostedType);
    when(mavenFacet.getVersionPolicy()).thenReturn(RELEASE);
    assertFalse(underTest.appliesTo(repository));
  }
  
  /**
   * Tests that the {@link PurgeMavenUnusedSnapshotsTask#appliesTo} method is thread-safe
   * when called concurrently from many virtual threads.
   */
  @Test
  void testConcurrentAppliesToWithVirtualThreads() throws Exception {
    // Setup repository to return true for appliesTo
    when(repository.getFormat()).thenReturn(maven2Format);
    when(repository.getType()).thenReturn(hostedType);
    when(mavenFacet.getVersionPolicy()).thenReturn(SNAPSHOT);
    
    // Create virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Number of concurrent threads to test with
    int threadCount = 1000;
    
    // CountDownLatch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(threadCount);
    
    // Track any errors that occur during concurrent execution
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create and start threads
    for (int i = 0; i < threadCount; i++) {
      virtualThreadFactory.newThread(() -> {
        try {
          // Call the method under test
          boolean result = underTest.appliesTo(repository);
          
          // Verify the result is as expected
          if (!result) {
            errorCount.incrementAndGet();
          }
        } 
        catch (Exception e) {
          // Count any exceptions as errors
          errorCount.incrementAndGet();
        }
        finally {
          // Count down the latch regardless of success/failure
          latch.countDown();
        }
      }).start();
    }
    
    // Wait for all threads to complete
    latch.await();
    
    // Verify no errors occurred
    assertEquals(0, errorCount.get(), "Concurrent appliesTo calls should not produce errors");
  }
}