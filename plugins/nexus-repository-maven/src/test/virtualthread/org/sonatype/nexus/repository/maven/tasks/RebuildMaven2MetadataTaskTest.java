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
import org.sonatype.nexus.repository.RepositoryTaskSupport;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.maven.MavenMetadataRebuildFacet;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.scheduling.TaskConfiguration;

import org.junit.experimental.categories.Category;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.RepositoryTaskSupport.ALL_REPOSITORIES;
import static org.sonatype.nexus.repository.maven.tasks.RebuildMaven2MetadataTaskDescriptor.ARTIFACTID_FIELD_ID;
import static org.sonatype.nexus.repository.maven.tasks.RebuildMaven2MetadataTaskDescriptor.BASEVERSION_FIELD_ID;
import static org.sonatype.nexus.repository.maven.tasks.RebuildMaven2MetadataTaskDescriptor.CASCADE_REBUILD;
import static org.sonatype.nexus.repository.maven.tasks.RebuildMaven2MetadataTaskDescriptor.GROUPID_FIELD_ID;
import static org.sonatype.nexus.repository.maven.tasks.RebuildMaven2MetadataTaskDescriptor.REBUILD_CHECKSUMS;

/**
 * Tests for {@link RebuildMaven2MetadataTask}.
 * 
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
@Category(VirtualThreadTestGroup.class)
public class RebuildMaven2MetadataTaskTest
    extends TestSupport
{
  private static final Format MAVEN_FORMAT = new Maven2Format();

  private static final Type HOSTED_TYPE = new HostedType();

  private static final String GROUP_ID_VALUE = "group id";

  private static final String ARTIFACT_ID_VALUE = "artifact id";

  private static final String BASE_VERSION_VALUE = "base version";

  private static final String REBUILD_CHECKSUMS_VALUE = "false";

  private static final String CASCADE_REBUILD_VALUE = "true";

  @Mock
  private Repository repository;

  @Mock
  private MavenMetadataRebuildFacet rebuildFacet;

  private RebuildMaven2MetadataTask underTest;

  @BeforeEach
  public void setUp() throws Exception {
    TaskConfiguration configuration = new TaskConfiguration();
    configuration.setId("Rebuild metadata test");
    configuration.setTypeId("Rebuild metadata test");
    configuration.setString(GROUPID_FIELD_ID, GROUP_ID_VALUE);
    configuration.setString(ARTIFACTID_FIELD_ID, ARTIFACT_ID_VALUE);
    configuration.setString(BASEVERSION_FIELD_ID, BASE_VERSION_VALUE);
    configuration.setString(REBUILD_CHECKSUMS, REBUILD_CHECKSUMS_VALUE);
    configuration.setString(CASCADE_REBUILD, CASCADE_REBUILD_VALUE);
    configuration.setString(RepositoryTaskSupport.REPOSITORY_NAME_FIELD_ID, ALL_REPOSITORIES);

    when(repository.facet(MavenMetadataRebuildFacet.class)).thenReturn(rebuildFacet);

    underTest = new RebuildMaven2MetadataTask(HOSTED_TYPE, MAVEN_FORMAT);
    underTest.configure(configuration);
  }

  @Test
  public void testTask() {
    underTest.execute(repository);
    verify(rebuildFacet).rebuildMetadata(GROUP_ID_VALUE, ARTIFACT_ID_VALUE, BASE_VERSION_VALUE, false, true, false);
  }
  
  /**
   * Tests that the task can be executed concurrently from multiple virtual threads without issues.
   * This verifies thread safety of the task implementation when used with Java 21 Virtual Threads.
   */
  @Test
  public void testConcurrentExecutionWithVirtualThreads() throws Exception {
    // Number of virtual threads to create for concurrent testing
    int threadCount = 100;
    
    // Create a virtual thread factory using Java 21 API
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Use CountDownLatch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(threadCount);
    
    // Track any errors that occur during concurrent execution
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create and start virtual threads to execute the task concurrently
    for (int i = 0; i < threadCount; i++) {
      Thread virtualThread = virtualThreadFactory.newThread(() -> {
        try {
          // Execute the task on this virtual thread
          underTest.execute(repository);
        } catch (Exception e) {
          // Count any errors that occur
          errorCount.incrementAndGet();
          log.error("Error executing task on virtual thread", e);
        } finally {
          // Signal that this thread has completed
          latch.countDown();
        }
      });
      
      // Start the virtual thread
      virtualThread.start();
    }
    
    // Wait for all virtual threads to complete
    latch.await();
    
    // Verify that the task was executed the expected number of times
    verify(rebuildFacet, times(threadCount)).rebuildMetadata(
        GROUP_ID_VALUE, ARTIFACT_ID_VALUE, BASE_VERSION_VALUE, false, true, false);
    
    // Verify that no errors occurred during concurrent execution
    if (errorCount.get() > 0) {
      throw new AssertionError("Errors occurred during concurrent execution: " + errorCount.get());
    }
  }
}