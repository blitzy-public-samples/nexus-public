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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.RepositoryTaskSupport;
import org.sonatype.nexus.repository.group.GroupFacet;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.maven.MavenFacet;
import org.sonatype.nexus.repository.maven.RemoveSnapshotsFacet;
import org.sonatype.nexus.repository.maven.VersionPolicy;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.scheduling.TaskConfiguration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.google.common.collect.Lists.newArrayList;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.RepositoryTaskSupport.ALL_REPOSITORIES;

@ExtendWith(MockitoExtension.class)
@org.junit.experimental.categories.Category(VirtualThreadTestGroup.class)
public class RemoveSnapshotsTaskTest
    extends TestSupport
{
  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private MavenFacet mavenFacet;

  @Mock
  private RemoveSnapshotsFacet removeSnapshotsFacet;

  private TaskConfiguration configuration;

  private TestRemoveSnapshotsTask taskUnderTest;

  @BeforeEach
  public void setUp() throws Exception {
    configuration = new TaskConfiguration();
    configuration.setId("test");
    configuration.setTypeId("test");
    configuration.setString(RepositoryTaskSupport.REPOSITORY_NAME_FIELD_ID, ALL_REPOSITORIES);

    when(mavenFacet.getVersionPolicy()).thenReturn(VersionPolicy.SNAPSHOT);

    taskUnderTest = new TestRemoveSnapshotsTask(new Maven2Format());
    taskUnderTest.install(repositoryManager, new GroupType());
    taskUnderTest.configure(configuration);
  }

  @Test
  void testGroupMembersProcessed() throws Exception {
    Repository repo1 = mockRepo();
    Repository repo2 = mockRepo();
    Repository repoGroup = mockGroup(newArrayList(repo1, repo2));

    when(repositoryManager.browse()).thenReturn(newArrayList(repoGroup));

    taskUnderTest.execute();

    verifyGroups(repoGroup);

    verifyRepoProcessed(repo1, 1);
    verifyRepoProcessed(repo2, 1);
    verify(removeSnapshotsFacet, times(2)).removeSnapshots(any());
  }

  @Test
  void testNestedGroups() throws Exception {
    Repository repo1 = mockRepo();
    Repository repo2 = mockRepo();

    Repository group2 = mockGroup(newArrayList(repo2));
    Repository group1 = mockGroup(newArrayList(repo1, group2));

    when(repositoryManager.browse()).thenReturn(newArrayList(group1));

    taskUnderTest.execute();

    verifyGroups(group1, group2);

    verifyRepoProcessed(repo1, 1);
    verifyRepoProcessed(repo2, 1);
    verify(removeSnapshotsFacet, times(2)).removeSnapshots(any());
  }

  @Test
  void testRepositoryNotProcessedTwice() throws Exception {
    Repository repo1 = mockRepo();
    Repository repo2 = mockRepo();

    Repository group1 = mockGroup(newArrayList(repo1));
    Repository group2 = mockGroup(newArrayList(repo1, repo2));

    when(repositoryManager.browse()).thenReturn(newArrayList(group1, group2, repo1, repo2));

    taskUnderTest.execute();

    verifyGroups(group1, group2);

    verifyRepoProcessed(repo1, 1);
    verify(removeSnapshotsFacet, times(2)).removeSnapshots(any());
  }

  @Test
  void testCyclicGroupReferencesHandledCorrectly() throws Exception {
    Repository repo1 = mockRepo();
    Repository repo2 = mockRepo();

    Repository group2 = mockGroup(newArrayList(repo1, repo2));
    Repository group1 = mockGroup(newArrayList(repo1, group2));
    Repository group3 = mockGroup(newArrayList(group1, group2, repo1, repo2));

    when(repositoryManager.browse()).thenReturn(newArrayList(group1, group2, group3, repo1, repo2));

    taskUnderTest.execute();

    verifyGroups(group1, group2, group3);

    verifyRepoProcessed(repo1, 1);
    verifyRepoProcessed(repo2, 1);
    verify(removeSnapshotsFacet, times(2)).removeSnapshots(any());
  }
  
  @Test
  void testConcurrentExecutionWithVirtualThreads() throws Exception {
    // Create a complex repository structure with cyclic references
    Repository repo1 = mockRepo();
    Repository repo2 = mockRepo();
    Repository repo3 = mockRepo();
    
    Repository group2 = mockGroup(newArrayList(repo1, repo2));
    Repository group1 = mockGroup(newArrayList(repo1, group2));
    Repository group3 = mockGroup(newArrayList(group1, group2, repo1, repo2, repo3));
    
    when(repositoryManager.browse()).thenReturn(newArrayList(group1, group2, group3, repo1, repo2, repo3));
    
    // Create 10 virtual threads to execute the task concurrently
    int threadCount = 10;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
    
    try {
      // Submit the task to multiple virtual threads
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            // Each thread executes the task independently
            taskUnderTest.execute();
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            log.error("Error executing task in virtual thread", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete (with timeout)
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
      
      // Verify all threads completed successfully
      assertThat(successCount.get(), is(threadCount));
      
      // Verify repositories were processed correctly
      verifyGroups(group1, group2, group3);
      verifyRepoProcessed(repo1, threadCount);
      verifyRepoProcessed(repo2, threadCount);
      verifyRepoProcessed(repo3, threadCount);
      
      // Verify the removeSnapshots method was called the expected number of times
      // Each thread processes 3 repositories, and we have 10 threads
      verify(removeSnapshotsFacet, times(3 * threadCount)).removeSnapshots(any());
    } 
    finally {
      executor.shutdown();
    }
  }

  private void verifyGroups(final Repository... groups) {
    for (Repository group : groups) {
      assertThat(taskUnderTest.hasBeenProcessed(group), is(true));
      verify(group, never()).facet(RemoveSnapshotsFacet.class); // groups should not have the facet executed against
    }
  }

  private void verifyRepoProcessed(final Repository repo, final int numFacetExecutions) {
    assertThat(taskUnderTest.hasBeenProcessed(repo), is(true));
    verify(repo, times(numFacetExecutions)).facet(RemoveSnapshotsFacet.class);
  }

  private Repository mockRepo() {
    Repository repo = mock(Repository.class);
    when(repo.facet(RemoveSnapshotsFacet.class)).thenReturn(removeSnapshotsFacet);
    when(repo.optionalFacet(RemoveSnapshotsFacet.class)).thenReturn(Optional.of(removeSnapshotsFacet));
    when(repo.getFormat()).thenReturn(new Maven2Format());
    when(repo.facet(MavenFacet.class)).thenReturn(mavenFacet);

    return repo;
  }

  private Repository mockGroup(final List<Repository> groupMembers) {
    Repository group = mock(Repository.class);
    GroupFacet facet = mock(GroupFacet.class);

    when(group.getName()).thenReturn("");
    when(group.getType()).thenReturn(new GroupType());
    when(group.facet(GroupFacet.class)).thenReturn(facet);
    when(group.getFormat()).thenReturn(new Maven2Format());
    when(group.facet(MavenFacet.class)).thenReturn(mavenFacet);
    when(group.facet(RemoveSnapshotsFacet.class)).thenReturn(removeSnapshotsFacet);
    when(group.optionalFacet(RemoveSnapshotsFacet.class)).thenReturn(Optional.of(removeSnapshotsFacet));
    when(facet.members()).thenReturn(groupMembers);

    return group;
  }

  /**
   * exposing methods for use within test class
   */
  private class TestRemoveSnapshotsTask
      extends RemoveSnapshotsTask
  {
    TestRemoveSnapshotsTask(final Format format) {
      super(format);
    }

    @Override
    protected Object execute() throws Exception {
      return super.execute();
    }

    @Override
    protected boolean hasBeenProcessed(final Repository repository) {
      return super.hasBeenProcessed(repository);
    }
  }
}