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
package org.sonatype.nexus.tasklog;

import java.io.File;
import java.io.IOException;
import java.time.ZonedDateTime;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.nio.file.Files.createTempDirectory;
import static org.apache.commons.io.FileUtils.deleteQuietly;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Test for TaskLogCleanup functionality.
 * 
 * Updated for Java 21 compatibility using JUnit Jupiter (JUnit 5) and modern testing practices.
 * This test verifies the task log cleanup functionality with proper isolation and Java 21 features.
 */
@ExtendWith(MockitoExtension.class)
public class TaskLogCleanupTest
    extends TestSupport
{
  private static final Integer DAYS_AGO = 1;

  private static File tempTaskFolder;

  private TaskLogCleanup taskLogCleanup;

  private File todayFile;

  private File yesterdayFile;

  private File twoDaysOldFile;

  @BeforeAll
  public static void init() throws IOException {
    tempTaskFolder = createTempDirectory("tmp-task-folder").toFile();
  }

  @AfterAll
  public static void end() {
    tempTaskFolder.deleteOnExit();
  }

  @BeforeEach
  public void setup() throws IOException {
    taskLogCleanup = spy(new TaskLogCleanup(DAYS_AGO));

    todayFile = createFile("today", 0);
    yesterdayFile = createFile("yesterday", 1);
    twoDaysOldFile = createFile("twoDaysOld", 2);
  }

  @AfterEach
  public void tearDown() throws IOException {
    deleteQuietly(todayFile);
    deleteQuietly(yesterdayFile);
    deleteQuietly(twoDaysOldFile);
  }

  /**
   * Test cleanup behavior when task log home is not set.
   * No files should be deleted in this case.
   */
  @Test
  public void cleanup_NoTaskLogHome() throws Exception {
    when(taskLogCleanup.getTaskLogHome()).thenReturn(null);

    taskLogCleanup.cleanup();

    // nothing is deleted
    assertThat(todayFile.exists(), is(true));
    assertThat(yesterdayFile.exists(), is(true));
    assertThat(twoDaysOldFile.exists(), is(true));
  }

  /**
   * Test normal cleanup behavior.
   * Files older than the configured threshold should be deleted.
   */
  @Test
  public void cleanup() throws Exception {
    when(taskLogCleanup.getTaskLogHome()).thenReturn(tempTaskFolder.getAbsolutePath());

    taskLogCleanup.cleanup();

    // only two day old file is deleted
    assertThat(todayFile.exists(), is(true));
    assertThat(yesterdayFile.exists(), is(true));
    assertThat(twoDaysOldFile.exists(), is(false));
  }

  /**
   * Helper method to create a test file with a specific age.
   * Uses Java time APIs to set the last modified time accurately.
   *
   * @param name The name of the file to create
   * @param ageInDays The age of the file in days
   * @return The created file
   */
  private File createFile(final String name, final int ageInDays) throws IOException {
    File file = new File(tempTaskFolder, name);
    file.createNewFile();
    file.setLastModified(ZonedDateTime.now().minusDays(ageInDays).toInstant().toEpochMilli());
    return file;
  }
}
