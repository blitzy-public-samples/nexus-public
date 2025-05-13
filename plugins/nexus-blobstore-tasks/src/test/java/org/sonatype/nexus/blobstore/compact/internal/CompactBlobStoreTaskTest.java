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
package org.sonatype.nexus.blobstore.compact.internal;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.api.BlobStoreUsageChecker;
import org.sonatype.nexus.repository.move.ChangeRepositoryBlobStoreConfiguration;
import org.sonatype.nexus.repository.move.ChangeRepositoryBlobStoreStore;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskUtils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.compact.internal.CompactBlobStoreTaskDescriptor.TYPE_ID;
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.BLOB_STORE_NAME_FIELD_ID;

/**
 * Tests for {@link CompactBlobStoreTask} with Java 21 compatibility.
 * 
 * This test validates the blob store compaction task's conflict detection logic
 * to ensure proper operation in the Java 21 runtime environment.
 */
@ExtendWith(MockitoExtension.class)
@org.junit.Category(Java21TestGroup.class)
public class CompactBlobStoreTaskTest
    extends TestSupport
{
  private final String BLOBSTORE_NAME = "test";

  private final String TASK_NAME = "test-task";

  @Mock
  BlobStoreManager blobStoreManager;

  @Mock
  ChangeRepositoryBlobStoreStore changeBlobstoreStore;

  @Mock
  BlobStoreUsageChecker blobStoreUsageChecker;

  @Mock
  TaskUtils taskUtils;

  TaskConfiguration configuration;

  CompactBlobStoreTask underTest;

  @BeforeEach
  public void setUp() {
    configuration = new TaskConfiguration();
    configuration.setString(BLOB_STORE_NAME_FIELD_ID, BLOBSTORE_NAME);
    configuration.setString(".name", TASK_NAME);
    configuration.setTypeId(TYPE_ID);
    configuration.setId(TASK_NAME);

    underTest = new CompactBlobStoreTask(blobStoreManager, changeBlobstoreStore, blobStoreUsageChecker, taskUtils);
  }

  /**
   * Verifies that the task properly detects and throws an exception when a conflicting task is running.
   * This ensures proper task scheduling and execution in the Java 21 environment.
   */
  @Test
  public void conflictingTaskRunningThrowsException() {
    underTest.configure(configuration);

    doThrow(new IllegalStateException("conflicting task"))
        .when(taskUtils).checkForConflictingTasks(anyString(), anyString(), any(List.class), any(Map.class));
    when(changeBlobstoreStore.findByBlobStoreName(anyString())).thenReturn(Collections.emptyList());

    IllegalStateException exception = assertThrows(IllegalStateException.class, underTest::checkForConflicts);

    assertEquals("conflicting task", exception.getMessage());
    verify(taskUtils, times(1)).checkForConflictingTasks(anyString(), anyString(), any(List.class), any(Map.class));
  }

  /**
   * Verifies that the task properly detects and throws an exception when an unfinished move task exists.
   * This ensures data integrity during blob store operations in the Java 21 environment.
   */
  @Test
  public void unfinishedMoveTaskThrowsException() {
    ChangeRepositoryBlobStoreConfiguration record = getRecord("test", BLOBSTORE_NAME, "target-blobstore");

    underTest.configure(configuration);

    doNothing()
        .when(taskUtils).checkForConflictingTasks(anyString(), anyString(), any(List.class), any(Map.class));
    when(changeBlobstoreStore.findByBlobStoreName(anyString())).thenReturn(Collections.singletonList(record));

    IllegalStateException exception = assertThrows(IllegalStateException.class, underTest::checkForConflicts);

    assertEquals(
        String.format("found unfinished move task(s) using blobstore '%s', task can't be executed", BLOBSTORE_NAME),
        exception.getMessage());
    verify(taskUtils, times(1)).checkForConflictingTasks(anyString(), anyString(), any(List.class), any(Map.class));
    verify(changeBlobstoreStore, times(1)).findByBlobStoreName(eq(BLOBSTORE_NAME));
  }

  /**
   * Creates a test record for a blob store configuration change.
   * 
   * @param name The name of the configuration
   * @param sourceBlobStoreName The source blob store name
   * @param targetBlobStoreName The target blob store name
   * @return A configured ChangeRepositoryBlobStoreConfiguration instance
   */
  private ChangeRepositoryBlobStoreConfiguration getRecord(final String name , final String sourceBlobStoreName , final String targetBlobStoreName) {
    return new ChangeRepositoryBlobStoreConfiguration()
    {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public void setName(final String name) {

      }

      @Override
      public String getTargetBlobStoreName() {
        return targetBlobStoreName;
      }

      @Override
      public void setTargetBlobStoreName(final String targetBlobStoreName) {

      }

      @Override
      public String getSourceBlobStoreName() {
        return sourceBlobStoreName;
      }

      @Override
      public void setSourceBlobStoreName(final String sourceBlobStoreName) {

      }

      @Override
      public OffsetDateTime getStarted() {
        return null;
      }

      @Override
      public void setStarted(final OffsetDateTime processStartDate) {

      }
    };
  }
}