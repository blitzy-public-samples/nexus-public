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
package org.sonatype.nexus.coreui.internal;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.browse.node.BrowseNodeConfiguration;
import org.sonatype.nexus.repository.browse.node.RebuildBrowseNodesTaskDescriptor;
import org.sonatype.nexus.scheduling.CurrentState;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskInfo;
import org.sonatype.nexus.scheduling.TaskScheduler;
import org.sonatype.nexus.scheduling.TaskState;

import com.google.common.collect.Sets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.RepositoryTaskSupport.ALL_REPOSITORIES;

@ExtendWith(MockitoExtension.class)
public class BrowseStateContributorTest
    extends TestSupport
{
  @Mock
  private BrowseNodeConfiguration browseNodeConfiguration;

  @Mock
  private TaskScheduler taskScheduler;

  private BrowseStateContributor underTest;

  @BeforeEach
  public void setup() {
    when(browseNodeConfiguration.getMaxNodes()).thenReturn(10);
    underTest = new BrowseStateContributor(browseNodeConfiguration, taskScheduler, 60);
  }

  @Test
  public void getStateShouldReturnCorrectStateForRunningTask() {
    List<TaskInfo> tasks = Arrays
        .asList(createTaskInfo(RebuildBrowseNodesTaskDescriptor.TYPE_ID, TaskState.RUNNING, ALL_REPOSITORIES));
    when(taskScheduler.listsTasks()).thenReturn(tasks);

    Map<String, Object> state = underTest.getState();

    assertThat(state).hasSize(2);
    assertThat(state.get("rebuildingRepositories")).isEqualTo(Collections.singleton(ALL_REPOSITORIES));
    assertThat(state.get("browseTreeMaxNodes")).isEqualTo(10);
  }

  @Test
  public void getStateShouldReturnCorrectStateForMultipleTasks() {
    List<TaskInfo> tasks = Arrays
        .asList(createTaskInfo(RebuildBrowseNodesTaskDescriptor.TYPE_ID, TaskState.RUNNING, "repo1"),
            createTaskInfo(RebuildBrowseNodesTaskDescriptor.TYPE_ID, TaskState.RUNNING, "repo2"),
            createTaskInfo(RebuildBrowseNodesTaskDescriptor.TYPE_ID, TaskState.RUNNING, "repo3"));
    when(taskScheduler.listsTasks()).thenReturn(tasks);

    Map<String, Object> state = underTest.getState();

    assertThat(state).hasSize(2);
    assertThat(state.get("rebuildingRepositories")).isEqualTo(Sets.newHashSet("repo1", "repo2", "repo3"));
    assertThat(state.get("browseTreeMaxNodes")).isEqualTo(10);
  }

  @Test
  public void getStateShouldIgnoreOtherTasksWhenFirstTaskIsAllRepositories() {
    List<TaskInfo> tasks = Arrays
        .asList(createTaskInfo(RebuildBrowseNodesTaskDescriptor.TYPE_ID, TaskState.RUNNING, ALL_REPOSITORIES),
            createTaskInfo(RebuildBrowseNodesTaskDescriptor.TYPE_ID, TaskState.RUNNING, "repo2"),
            createTaskInfo(RebuildBrowseNodesTaskDescriptor.TYPE_ID, TaskState.RUNNING, "repo3"));
    when(taskScheduler.listsTasks()).thenReturn(tasks);

    Map<String, Object> state = underTest.getState();

    assertThat(state).hasSize(2);
    assertThat(state.get("rebuildingRepositories")).isEqualTo(Collections.singleton(ALL_REPOSITORIES));
    assertThat(state.get("browseTreeMaxNodes")).isEqualTo(10);

    verifyNoInteractions(tasks.get(1));
    verifyNoInteractions(tasks.get(2));
  }

  @Test
  public void getStateShouldOnlyIncludeRebuildBrowseNodesTasks() {
    List<TaskInfo> tasks = Arrays
        .asList(createTaskInfo(RebuildBrowseNodesTaskDescriptor.TYPE_ID, TaskState.RUNNING, "repo1"),
            createTaskInfo("typeId", TaskState.RUNNING, "repo2"));
    when(taskScheduler.listsTasks()).thenReturn(tasks);

    Map<String, Object> state = underTest.getState();

    assertThat(state).hasSize(2);
    assertThat(state.get("rebuildingRepositories")).isEqualTo(Collections.singleton("repo1"));
    assertThat(state.get("browseTreeMaxNodes")).isEqualTo(10);
  }

  private TaskInfo createTaskInfo(String typeId, TaskState runState, String repositoryName) {
    CurrentState currentState = mock(CurrentState.class);
    when(currentState.getRunState()).thenReturn(runState);

    TaskConfiguration taskConfiguration = new TaskConfiguration();
    taskConfiguration.setString("repositoryName", repositoryName);

    TaskInfo taskInfo = mock(TaskInfo.class);
    when(taskInfo.getTypeId()).thenReturn(typeId);
    when(taskInfo.getCurrentState()).thenReturn(currentState);
    when(taskInfo.getConfiguration()).thenReturn(taskConfiguration);
    return taskInfo;
  }
}
