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
package org.sonatype.nexus.common.io;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class FileFinderTest
    extends TestSupport
{
  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private static final byte[] PAYLOAD = "payload".getBytes(UTF_8);

  private File root;

  private final FileFinder fileFinder = new FileFinder();

  @Before
  public void prepare() throws IOException {
    root = util.createTempDir();
    Path path = root.toPath();
    Files.write(path.resolve("file-2024-05-15-10-50-44.txt"), PAYLOAD);
    Files.write(path.resolve("file-2024-04-15-10-50-44.txt"), PAYLOAD);
    Files.write(path.resolve("file-2024-06-15-10-50-44.txt"), PAYLOAD);
  }

  @Test
  public void findLatestTimestampedFileWhenDirectoryEmpty() throws IOException {
    String prefix = "prefix";
    String suffix = "suffix";

    Optional<Path> result = fileFinder.findLatestTimestampedFile(root.toPath(), prefix, suffix);

    assertFalse(result.isPresent());
  }

  @Test
  public void findLatestTimestampedFileWhenDirectoryContainsMatchingFiles() throws IOException {
    String prefix = "file-";
    String suffix = ".txt";

    Optional<Path> result = fileFinder.findLatestTimestampedFile(root.toPath(), prefix, suffix);

    String expectedFileName = "file-2024-06-15-10-50-44.txt";
    assertTrue(result.isPresent());
    assertTrue(result.get().toString().contains(expectedFileName));
  }
  
  /**
   * Tests concurrent file finding operations using Virtual Threads.
   * This test validates that the FileFinder can be safely used from multiple concurrent threads
   * without interference, leveraging Java 21's Virtual Thread capabilities for improved concurrency.
   */
  @Test
  @Category(VirtualThreadTestGroup.class)
  public void concurrentFileFinderOperationsWithVirtualThreads() throws Exception {
    // Create a thread factory for virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that uses virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Number of concurrent operations to perform
      int concurrentOperations = 100;
      
      // Latch to coordinate thread execution
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(concurrentOperations);
      
      // Reference to hold any exception that might occur during execution
      AtomicReference<Throwable> errorRef = new AtomicReference<>();
      
      // Submit tasks to find files concurrently
      for (int i = 0; i < concurrentOperations; i++) {
        final int taskId = i;
        executor.submit(() -> {
          try {
            // Wait for all tasks to be ready before starting
            startLatch.await();
            
            // Alternate between searching for existing and non-existing files
            if (taskId % 2 == 0) {
              // Search for existing files
              Optional<Path> result = fileFinder.findLatestTimestampedFile(root.toPath(), "file-", ".txt");
              
              // Verify the result is as expected
              String expectedFileName = "file-2024-06-15-10-50-44.txt";
              if (!result.isPresent() || !result.get().toString().contains(expectedFileName)) {
                errorRef.compareAndSet(null, new AssertionError(
                    "Task " + taskId + " failed: Expected to find " + expectedFileName + 
                    " but got " + (result.isPresent() ? result.get() : "no result")));
              }
            } else {
              // Search for non-existing files
              Optional<Path> result = fileFinder.findLatestTimestampedFile(root.toPath(), "nonexistent-", ".txt");
              
              // Verify the result is as expected
              if (result.isPresent()) {
                errorRef.compareAndSet(null, new AssertionError(
                    "Task " + taskId + " failed: Expected no result but found " + result.get()));
              }
            }
          } catch (Throwable t) {
            // Capture any exception that occurs
            errorRef.compareAndSet(null, t);
          } finally {
            // Signal that this task is complete
            completionLatch.countDown();
          }
        });
      }
      
      // Start all tasks simultaneously
      startLatch.countDown();
      
      // Wait for all tasks to complete (with timeout for safety)
      boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
      assertTrue("Not all concurrent operations completed within the timeout", completed);
      
      // Check if any errors occurred
      assertNull("Concurrent file finding operations failed: " + 
                (errorRef.get() != null ? errorRef.get().getMessage() : ""), 
                errorRef.get());
    } finally {
      // Clean up the executor service
      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}