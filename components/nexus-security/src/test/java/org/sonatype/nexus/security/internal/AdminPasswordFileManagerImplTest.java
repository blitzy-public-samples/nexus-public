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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

  private File workDir;

  @BeforeEach
  public void setup() throws Exception {
    workDir = tempDir.resolve("workdir").toFile();
    workDir.mkdirs();
    when(applicationDirectories.getWorkDirectory()).thenReturn(workDir);
    underTest = new AdminPasswordFileManagerImpl(applicationDirectories);
  }

  @Test
  public void testExists() throws Exception {
    Path passwordFile = Path.of(applicationDirectories.getWorkDirectory().getPath(), "admin.password");
    assertThat(underTest.exists(), is(false));
    Files.writeString(passwordFile, "testpass", StandardCharsets.UTF_8);
    assertThat(underTest.exists(), is(true));
    Files.delete(passwordFile);
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
        Path.of(applicationDirectories.getWorkDirectory().getPath(), "admin.password"), StandardCharsets.UTF_8);
    assertThat(storedPassword, is("testpass"));
  }

  @Test
  public void testWriteFile_workdirExists() throws Exception {
    File directory = applicationDirectories.getWorkDirectory();
    directory.mkdirs();
    assertThat(directory.isDirectory(), is(true));
    assertThat(directory.exists(), is(true));

    underTest.writeFile("testpass");

    String storedPassword = Files.readString(
        Path.of(applicationDirectories.getWorkDirectory().getPath(), "admin.password"), StandardCharsets.UTF_8);
    assertThat(storedPassword, is("testpass"));
  }

  @Test
  public void testWriteFile_failure() throws Exception {
    Path passwordFile = Path.of(applicationDirectories.getWorkDirectory().getPath(), "admin.password");
    Files.writeString(passwordFile, "testpass", StandardCharsets.UTF_8);
    File passwordFileAsFile = passwordFile.toFile();
    passwordFileAsFile.setWritable(false);

    assertThat(underTest.writeFile("testpass2"), is(false));
    String storedPassword = Files.readString(passwordFile, StandardCharsets.UTF_8);
    assertThat(storedPassword, is("testpass"));
    
    // Restore writability for cleanup
    passwordFileAsFile.setWritable(true);
  }

  @Test
  public void testReadFile() throws Exception {
    Path passwordFile = Path.of(applicationDirectories.getWorkDirectory().getPath(), "admin.password");
    Files.writeString(passwordFile, "testpass", StandardCharsets.UTF_8);
    assertThat(underTest.readFile(), is("testpass"));
  }

  @Test
  public void testRemoveFile() throws Exception {
    Path passwordFile = Path.of(applicationDirectories.getWorkDirectory().getPath(), "admin.password");
    Files.writeString(passwordFile, "testpass", StandardCharsets.UTF_8);
    underTest.removeFile();
    assertThat(Files.exists(passwordFile), is(false));
  }
  
  @Test
  public void testVirtualThreadCompatibility() throws Exception {
    // Skip test if virtual threads are not supported
    if (!VirtualThreadTestSupport.isVirtualThreadSupported()) {
      return;
    }
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      int taskCount = 100;
      List<Future<Boolean>> futures = new CopyOnWriteArrayList<>();
      
      // Submit tasks to write, read, and remove files using virtual threads
      IntStream.range(0, taskCount).forEach(i -> {
        futures.add(executor.submit(() -> {
          try {
            // Create a unique file manager for each thread with its own work directory
            Path threadWorkDir = tempDir.resolve("vt-workdir-" + i);
            Files.createDirectories(threadWorkDir);
            
            ApplicationDirectories appDirs = mock(ApplicationDirectories.class);
            when(appDirs.getWorkDirectory()).thenReturn(threadWorkDir.toFile());
            
            AdminPasswordFileManagerImpl fileManager = new AdminPasswordFileManagerImpl(appDirs);
            
            // Test write operation
            String password = "password-" + i;
            boolean writeResult = fileManager.writeFile(password);
            assertTrue(writeResult, "Write operation failed for thread " + i);
            
            // Test read operation
            String readPassword = fileManager.readFile();
            assertEquals(password, readPassword, "Read operation returned incorrect value for thread " + i);
            
            // Test exists operation
            assertTrue(fileManager.exists(), "Exists operation failed for thread " + i);
            
            // Test remove operation
            fileManager.removeFile();
            return !fileManager.exists();
          }
          catch (Exception e) {
            log.error("Error in virtual thread test", e);
            return false;
          }
        }));
      });
      
      // Wait for all tasks to complete and verify results
      List<Boolean> results = futures.stream()
          .map(f -> {
            try {
              return f.get(10, TimeUnit.SECONDS);
            }
            catch (Exception e) {
              log.error("Error getting future result", e);
              return false;
            }
          })
          .collect(Collectors.toList());
      
      // Verify all operations completed successfully
      assertTrue(results.stream().allMatch(Boolean::booleanValue), 
          "Some virtual thread operations failed: " + results.stream().filter(r -> !r).count() + " failures");
    }
  }
}
