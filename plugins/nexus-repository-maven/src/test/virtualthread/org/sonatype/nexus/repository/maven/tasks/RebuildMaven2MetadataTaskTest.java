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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.RepositoryTaskSupport;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.maven.MavenMetadataRebuildFacet;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.scheduling.TaskConfiguration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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
 * Includes virtual thread testing to verify thread safety when executed concurrently.
 */
@ExtendWith(MockitoExtension.class)
@Tag("VirtualThreadTestGroup")
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
   * Tests concurrent execution of the task using virtual threads to verify thread safety.
   * This ensures that the task can be safely executed from multiple virtual threads simultaneously.
   */
  @Test
  public void testConcurrentExecutionWithVirtualThreads() throws Exception {
    // Number of virtual threads to create for concurrent testing
    final int threadCount = 100;
    final CountDownLatch latch = new CountDownLatch(threadCount);
    
    // Create a virtual thread factory
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // Launch multiple virtual threads to execute the task concurrently
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            underTest.execute(repository);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete (with timeout)
      if (!latch.await(30, TimeUnit.SECONDS)) {
        throw new AssertionError("Timed out waiting for virtual threads to complete");
      }
      
      // Verify that the rebuildMetadata method was called exactly threadCount times
      verify(rebuildFacet, times(threadCount)).rebuildMetadata(
          GROUP_ID_VALUE, ARTIFACT_ID_VALUE, BASE_VERSION_VALUE, false, true, false);
    } finally {
      executor.shutdown();
    }
  }
}