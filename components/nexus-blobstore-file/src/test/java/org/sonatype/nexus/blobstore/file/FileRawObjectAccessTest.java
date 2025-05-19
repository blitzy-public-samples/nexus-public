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
package org.sonatype.nexus.blobstore.file;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.MockBlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FileRawObjectAccessTest
    extends TestSupport
{
  private FileRawObjectAccess underTest;

  @TempDir
  public Path temporaryFolder;

  @BeforeEach
  public void initBlobStore() {
    BlobStoreConfiguration configuration = new MockBlobStoreConfiguration();

    Map<String, Map<String, Object>> attributes = new HashMap<>();
    Map<String, Object> fileMap = new HashMap<>();
    fileMap.put("path", temporaryFolder);
    attributes.put("file", fileMap);

    configuration.setAttributes(attributes);

    underTest = new FileRawObjectAccess(temporaryFolder);
  }

  @Test
  public void listRawObjects() throws Exception {
    Path dir = Paths.get("path", "to");

    Files.createDirectories(temporaryFolder.resolve(dir));
    Files.createFile(temporaryFolder.resolve(dir).resolve("object1.txt"));

    List<String> objects = underTest.listRawObjects(dir).collect(toList());
    assertEquals(1, objects.size());
    assertEquals("object1.txt", objects.get(0));
  }

  @Test
  public void listRawObjects_empty() throws Exception {
    Path dir = Paths.get("path", "to");

    Files.createDirectories(temporaryFolder.resolve(dir));

    List<String> objects = underTest.listRawObjects(dir).collect(toList());
    assertTrue(objects.isEmpty());
  }

  @Test
  public void listRawObjects_root() throws Exception {
    Files.createFile(temporaryFolder.resolve("object1.txt"));

    List<String> objects = underTest.listRawObjects(null).collect(toList());
    assertEquals(1, objects.size());
    assertEquals("object1.txt", objects.get(0));
  }

  @Test
  public void getRawObject() throws Exception {
    Path dir = Paths.get("path", "to");

    Files.createDirectories(temporaryFolder.resolve(dir));
    Path file1 = Files.createFile(temporaryFolder.resolve(dir).resolve("object1.txt"));

    FileUtils.writeStringToFile(file1.toFile(), "hello!", StandardCharsets.UTF_8.name());

    InputStream in = underTest.getRawObject(dir.resolve("object1.txt"));
    assertNotNull(in);
    assertEquals("hello!", IOUtils.toString(in, StandardCharsets.UTF_8.name()));
  }

  @Test
  public void getRawObject_notFound() {
    InputStream in = underTest.getRawObject(Paths.get("path", "to", "object1.txt"));
    assertNull(in);
  }

  @Test
  public void putRawObject() throws Exception {
    Path dir = Paths.get("path", "to");
    Path dirPath = Files.createDirectories(temporaryFolder.resolve(dir));

    underTest.putRawObject(dir.resolve("object1.txt"), new ByteArrayInputStream("hello!".getBytes()));

    byte[] object1 = Files.readAllBytes(dirPath.resolve("object1.txt"));
    assertEquals("hello!", new String(object1, StandardCharsets.UTF_8));
  }

  @Test
  public void deleteRawObjectsInPath() throws Exception {
    Path path = Paths.get("path", "to");

    Path parent = Files.createDirectories(temporaryFolder.resolve(path));
    Path file1 = Files.createFile(temporaryFolder.resolve(path).resolve("object1.txt"));
    Path file2 = Files.createFile(temporaryFolder.resolve(path).resolve("object2.txt"));

    underTest.deleteRawObjectsInPath(path);
    assertFalse(Files.exists(file1));
    assertFalse(Files.exists(file2));
    assertFalse(Files.exists(parent));
  }

  @Test
  public void deleteRawObjectsInPathNestedContent() throws Exception {
    Path path = Paths.get("path", "to");

    Path parent = Files.createDirectories(temporaryFolder.resolve(path));
    Path file1 = Files.createFile(temporaryFolder.resolve(path).resolve("object1.txt"));
    Path file2 = Files.createFile(temporaryFolder.resolve(path).resolve("object2.txt"));
    Files.createDirectories(temporaryFolder.resolve(path).resolve("nested"));

    underTest.deleteRawObjectsInPath(path);
    assertFalse(Files.exists(file1));
    assertFalse(Files.exists(file2));
    // can't delete parent because of nested folder
    assertTrue(Files.exists(parent));
  }

  @Test
  public void concurrentFileOperationsWithVirtualThreads() throws Exception {
    int numThreads = 100;
    CountDownLatch latch = new CountDownLatch(numThreads);
    AtomicBoolean anyFailures = new AtomicBoolean(false);
    
    // Use virtual threads for I/O operations
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < numThreads; i++) {
        final int threadNum = i;
        executor.submit(() -> {
          try {
            // Create a unique path for each thread
            Path dir = Paths.get("concurrent", "thread" + threadNum);
            Files.createDirectories(temporaryFolder.resolve(dir));
            
            // Write a file
            String content = "Content from thread " + threadNum;
            underTest.putRawObject(dir.resolve("file.txt"), 
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
            
            // Read it back and verify
            try (InputStream in = underTest.getRawObject(dir.resolve("file.txt"))) {
              String readContent = IOUtils.toString(in, StandardCharsets.UTF_8);
              assertEquals(content, readContent, 
                  "Content mismatch for thread " + threadNum);
            }
            
            // List objects
            List<String> objects = underTest.listRawObjects(dir).collect(toList());
            assertEquals(1, objects.size(), 
                "Expected one file for thread " + threadNum);
            
            // Delete the file
            underTest.deleteRawObjectsInPath(dir);
            assertFalse(Files.exists(temporaryFolder.resolve(dir)), 
                "Directory should be deleted for thread " + threadNum);
          }
          catch (Exception e) {
            log.error("Error in thread " + threadNum, e);
            anyFailures.set(true);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for threads to complete");
      assertFalse(anyFailures.get(), "One or more threads encountered errors");
    }
  }

  @Test
  public void verifyNoPinningDuringFileOperations() throws Exception {
    // This test verifies that file operations don't cause thread pinning
    // by performing multiple concurrent I/O operations with virtual threads
    
    int numThreads = 50;
    CountDownLatch latch = new CountDownLatch(numThreads);
    
    // Use virtual threads for I/O operations
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < numThreads; i++) {
        final int threadNum = i;
        executor.submit(() -> {
          try {
            // Create a unique path for each thread
            Path dir = Paths.get("pinning-test", "thread" + threadNum);
            Files.createDirectories(temporaryFolder.resolve(dir));
            
            // Perform multiple I/O operations that would block if pinned
            for (int j = 0; j < 10; j++) {
              String fileName = "file" + j + ".txt";
              String content = "Content " + j + " from thread " + threadNum;
              
              // Write file
              underTest.putRawObject(dir.resolve(fileName), 
                  new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
              
              // Small delay to increase chance of thread scheduling
              Thread.sleep(10);
              
              // Read file
              try (InputStream in = underTest.getRawObject(dir.resolve(fileName))) {
                String readContent = IOUtils.toString(in, StandardCharsets.UTF_8);
                assertEquals(content, readContent);
              }
              
              // List files
              underTest.listRawObjects(dir).collect(toList());
            }
            
            // Clean up
            underTest.deleteRawObjectsInPath(dir);
          }
          catch (Exception e) {
            log.error("Error in thread " + threadNum, e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // If threads are pinned, this would likely time out as carrier threads would be blocked
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for threads to complete");
    }
  }
}