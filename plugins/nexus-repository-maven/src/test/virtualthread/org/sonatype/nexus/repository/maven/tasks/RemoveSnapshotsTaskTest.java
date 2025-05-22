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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.google.common.collect.Lists.newArrayList;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.RepositoryTaskSupport.ALL_REPOSITORIES;

@ExtendWith(MockitoExtension.class)
@Tag("virtualThread")
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
  public void testGroupMembersProcessed() throws Exception {
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
  public void testNestedGroups() throws Exception {
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
  public void testRepositoryNotProcessedTwice() throws Exception {
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
  public void testCyclicGroupReferencesHandledCorrectly() throws Exception {
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
  public void testConcurrentExecutionWithVirtualThreads() throws Exception {
    // Create a complex repository structure with nested groups
    Repository repo1 = mockRepo();
    Repository repo2 = mockRepo();
    Repository repo3 = mockRepo();
    Repository repo4 = mockRepo();
    
    Repository group1 = mockGroup(newArrayList(repo1, repo2));
    Repository group2 = mockGroup(newArrayList(repo3, repo4));
    Repository group3 = mockGroup(newArrayList(group1, group2));
    Repository group4 = mockGroup(newArrayList(group3, repo1)); // Cyclic reference to repo1
    
    when(repositoryManager.browse()).thenReturn(newArrayList(group1, group2, group3, group4, repo1, repo2, repo3, repo4));
    
    // Create 10 virtual threads to execute the task concurrently
    int threadCount = 10;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    for (int i = 0; i < threadCount; i++) {
      Runnable task = () -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          taskUnderTest.execute();
          successCount.incrementAndGet();
        }
        catch (Exception e) {
          log.error("Error executing task in virtual thread", e);
        }
        finally {
          completionLatch.countDown();
        }
      };
      
      Thread virtualThread = virtualThreadFactory.newThread(task);
      virtualThread.start();
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    completionLatch.await();
    
    // Verify all threads completed successfully
    assertTrue(successCount.get() == threadCount, "All virtual threads should complete successfully");
    
    // Verify repositories were processed correctly
    verifyGroups(group1, group2, group3, group4);
    
    // Each repository should be processed exactly once per thread execution
    verifyRepoProcessed(repo1, threadCount);
    verifyRepoProcessed(repo2, threadCount);
    verifyRepoProcessed(repo3, threadCount);
    verifyRepoProcessed(repo4, threadCount);
    
    // Each thread should call removeSnapshots on each repository
    verify(removeSnapshotsFacet, times(4 * threadCount)).removeSnapshots(any());
  }

  private void verifyGroups(final Repository... groups) {
    for (Repository group : groups) {
      assertTrue(taskUnderTest.hasBeenProcessed(group), "Group should be processed");
      verify(group, never()).facet(RemoveSnapshotsFacet.class); // groups should not have the facet executed against
    }
  }

  private void verifyRepoProcessed(final Repository repo, final int numFacetExecutions) {
    assertTrue(taskUnderTest.hasBeenProcessed(repo), "Repository should be processed");
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