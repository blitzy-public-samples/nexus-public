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
package org.sonatype.nexus.security.internal;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.ApplicationDirectories;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class AdminPasswordFileManagerImplTest
    extends TestSupport
{
  private AdminPasswordFileManagerImpl underTest;

  @Mock
  private ApplicationDirectories applicationDirectories;

  @TempDir
  Path tempDir;

  @BeforeEach
  public void setup() throws Exception {
    File workDir = tempDir.resolve("workdir").toFile();
    workDir.mkdir();
    when(applicationDirectories.getWorkDirectory()).thenReturn(workDir);
    underTest = new AdminPasswordFileManagerImpl(applicationDirectories);
  }

  @Test
  public void testExists() throws Exception {
    File passwordFile = new File(applicationDirectories.getWorkDirectory(), "admin.password");
    assertThat(underTest.exists(), is(false));
    Files.write(passwordFile.toPath(), "testpass".getBytes(StandardCharsets.UTF_8));
    assertThat(underTest.exists(), is(true));
    Files.delete(passwordFile.toPath());
    assertThat(underTest.exists(), is(false));
  }

  @Test
  public void testGetPath() {
    File passwordFile = new File(applicationDirectories.getWorkDirectory(), "admin.password");
    assertThat(passwordFile.getAbsolutePath(), is(underTest.getPath()));
  }

  @Test
  public void testWriteFile() throws Exception {
    underTest.writeFile("testpass");
    String storedPassword = Files.readString(
        new File(applicationDirectories.getWorkDirectory(), "admin.password").toPath());
    assertThat(storedPassword, is("testpass"));
  }

  @Test
  public void testWriteFile_workdirExists() throws Exception {
    File directory = applicationDirectories.getWorkDirectory();
    assertThat(directory.isDirectory(), is(true));
    assertThat(directory.exists(), is(true));

    underTest.writeFile("testpass");

    String storedPassword = Files.readString(
        new File(applicationDirectories.getWorkDirectory(), "admin.password").toPath());
    assertThat(storedPassword, is("testpass"));
  }

  @Test
  public void testWriteFile_failure() throws Exception {
    File passwordFile = new File(applicationDirectories.getWorkDirectory(), "admin.password");
    Files.write(passwordFile.toPath(), "testpass".getBytes(StandardCharsets.UTF_8));
    passwordFile.setWritable(false);

    assertThat(underTest.writeFile("testpass2"), is(false));
    String storedPassword = Files.readString(
        new File(applicationDirectories.getWorkDirectory(), "admin.password").toPath());
    assertThat(storedPassword, is("testpass"));
  }

  @Test
  public void testReadFile() throws Exception {
    File passwordFile = new File(applicationDirectories.getWorkDirectory(), "admin.password");
    Files.write(passwordFile.toPath(), "testpass".getBytes(StandardCharsets.UTF_8));
    assertThat(underTest.readFile(), is("testpass"));
  }

  @Test
  public void testRemoveFile() throws Exception {
    File passwordFile = new File(applicationDirectories.getWorkDirectory(), "admin.password");
    Files.write(passwordFile.toPath(), "testpass".getBytes(StandardCharsets.UTF_8));
    underTest.removeFile();
    assertThat(passwordFile.exists(), is(false));
  }
  
  /**
   * Tests file operations using virtual threads to ensure compatibility with Java 21 NIO.2 API.
   * This test creates multiple virtual threads that concurrently read and write to the password file.
   */
  @Test
  public void testFileOperationsWithVirtualThreads() throws Exception {
    // Number of concurrent operations to perform
    int concurrentOperations = 100;
    CountDownLatch latch = new CountDownLatch(concurrentOperations);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Write initial password file
      underTest.writeFile("initial-password");
      
      // Submit tasks to virtual threads
      for (int i = 0; i < concurrentOperations; i++) {
        final int taskId = i;
        executor.submit(() -> {
          try {
            // Perform different operations based on task ID
            if (taskId % 3 == 0) {
              // Read the file
              String content = underTest.readFile();
              if (content != null && !content.isEmpty()) {
                successCount.incrementAndGet();
              }
            } else if (taskId % 3 == 1) {
              // Write to the file
              String newPassword = "password-" + taskId;
              if (underTest.writeFile(newPassword)) {
                successCount.incrementAndGet();
              }
            } else {
              // Check if file exists
              if (underTest.exists()) {
                successCount.incrementAndGet();
              }
            }
          } catch (Exception e) {
            log.error("Error in virtual thread task", e);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete (with timeout)
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertThat("All virtual thread tasks should complete in time", completed, is(true));
      
      // Verify that most operations succeeded
      // We don't expect 100% success due to concurrent writes/deletes
      assertThat("Most operations should succeed", 
          successCount.get() > concurrentOperations * 0.7, is(true));
      
      // Final verification - the file should exist after all operations
      File passwordFile = new File(applicationDirectories.getWorkDirectory(), "admin.password");
      assertThat(passwordFile.exists(), is(true));
      
      // Clean up
      underTest.removeFile();
    } finally {
      executor.shutdown();
      boolean terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
      if (!terminated) {
        executor.shutdownNow();
      }
    }
  }