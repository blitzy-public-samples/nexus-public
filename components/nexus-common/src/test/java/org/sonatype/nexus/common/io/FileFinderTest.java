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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import org.junit.Before;
import org.junit.Category;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

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
  
  @Test
  @Category(VirtualThreadTestGroup.class)
  public void concurrentFileFinderOperationsWithVirtualThreads() throws Exception {
    // Setup test data
    String prefix = "file-";
    String suffix = ".txt";
    int concurrentOperations = 50;
    
    // Create a virtual thread executor
    ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
    
    // Use CountDownLatch to coordinate test completion
    CountDownLatch latch = new CountDownLatch(concurrentOperations);
    AtomicReference<Exception> testException = new AtomicReference<>();
    
    try {
      // Submit multiple concurrent file finding operations
      for (int i = 0; i < concurrentOperations; i++) {
        executor.submit(() -> {
          try {
            Optional<Path> result = fileFinder.findLatestTimestampedFile(root.toPath(), prefix, suffix);
            
            // Verify each operation returns the expected result
            assertTrue("File finding operation should return a result", result.isPresent());
            String expectedFileName = "file-2024-06-15-10-50-44.txt";
            assertTrue("File finding operation should return the latest file", 
                result.get().toString().contains(expectedFileName));
          } 
          catch (Exception e) {
            testException.set(e);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      assertTrue("Timed out waiting for concurrent operations to complete", 
          latch.await(30, TimeUnit.SECONDS));
      
      // Check if any operations failed
      Exception exception = testException.get();
      if (exception != null) {
        throw exception;
      }
    } 
    finally {
      executor.shutdown();
      assertTrue("Executor did not terminate properly", 
          executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }
}