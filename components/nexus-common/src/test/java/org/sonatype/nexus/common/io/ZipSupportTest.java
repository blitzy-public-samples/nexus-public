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

import org.sonatype.goodies.testsupport.TestSupport;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link ZipSupport}.
 */
public class ZipSupportTest
    extends TestSupport
{
  @TempDir
  Path tempDir;

  private static final byte[] PAYLOAD = "payload".getBytes(UTF_8);

  private Path root;

  private final ZipSupport zipSupport = new ZipSupport();

  @BeforeEach
  void prepare() throws IOException {
    root = tempDir.resolve("ziptest");
    Files.createDirectories(root);
    Files.write(root.resolve("file1.txt"), PAYLOAD);
    Files.write(root.resolve("file2.txt"), PAYLOAD);
    Files.write(root.resolve("file4.txt"), PAYLOAD);
  }

  @Test
  @DisplayName("Zip files creates valid zip archive")
  void zipFilesCreatesValidZipArchive() throws IOException {
    List<String> filesToZip = Arrays.asList("file1.txt", "file2.txt", "file3.txt");

    String zipFileName = root.toString() + "/test.zip";

    zipSupport.zipFiles(root, filesToZip, zipFileName);

    File zipFile = new File(zipFileName);
    assertTrue(zipFile.exists(), "Zip file should exist");
  }

  @Test
  @DisplayName("Zip files handles non-existent files gracefully")
  void zipFilesHandlesNonExistentFilesGracefully() throws IOException {
    List<String> filesToZip = Arrays.asList("file1.txt", "nonexistent.txt", "file2.txt");

    String zipFileName = root.toString() + "/test-nonexistent.zip";

    zipSupport.zipFiles(root, filesToZip, zipFileName);

    File zipFile = new File(zipFileName);
    assertTrue(zipFile.exists(), "Zip file should exist even with non-existent files");
  }

  @Test
  @Tag("VirtualThreadTestGroup")
  @DisplayName("Zip files works with virtual threads")
  void zipFilesWorksWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create multiple zip files concurrently using virtual threads
      int concurrentTasks = 10;
      CountDownLatch latch = new CountDownLatch(concurrentTasks);
      AtomicInteger successCount = new AtomicInteger(0);
      
      for (int i = 0; i < concurrentTasks; i++) {
        final int taskNum = i;
        executor.submit(() -> {
          try {
            List<String> filesToZip = Arrays.asList("file1.txt", "file2.txt");
            String zipFileName = root.toString() + "/test-virtual-" + taskNum + ".zip";
            
            zipSupport.zipFiles(root, filesToZip, zipFileName);
            
            if (new File(zipFileName).exists()) {
              successCount.incrementAndGet();
            }
          } catch (Exception e) {
            log.error("Error in virtual thread zip task", e);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "All zip tasks should complete within timeout");
      
      // Verify all operations succeeded
      assertEquals(concurrentTasks, successCount.get(), "All concurrent zip operations should succeed");
    }
  }
}