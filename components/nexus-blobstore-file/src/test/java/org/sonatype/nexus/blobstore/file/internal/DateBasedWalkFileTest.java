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
package org.sonatype.nexus.blobstore.file.internal;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.BlobRef;
import org.sonatype.nexus.common.time.UTC;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sonatype.nexus.blobstore.BlobStoreSupport.CONTENT_PREFIX;

/**
 * Unit Tests for {@link DateBasedWalkFile}.
 */
@ExtendWith(MockitoExtension.class)
public class DateBasedWalkFileTest
    extends TestSupport
{
  @TempDir
  Path tempDir;

  @Test
  public void testWalkFilesWithDifferentDuration() throws Exception {
    File contentDir = new File(tempDir.toFile(), CONTENT_PREFIX);

    OffsetDateTime blobCreated = UTC.now();
    String bytesFileNow = "now";
    String bytesFile1minOld = "1minOld";
    String bytesFile3minOld = "3minOld";
    String bytesFile10minOld = "10minOld";

    String bytesFile1hOld = "1hOld";
    String bytesFile3hOld = "3hOld";
    String bytesFile10hOld = "10hOld";

    String bytesFile1dOld = "1dOld";
    String bytesFile3dOld = "3dOld";
    String bytesFile10dOld = "10dOld";

    String bytesFile1mOld = "1mOld";
    String bytesFile3mOld = "3mOld";
    String bytesFile10mOld = "10mOld";

    // create 4 files: now, 1 min ago, 3 min ago, and 10 min ago
    File storageDirNow = new File(contentDir, getDatePath(blobCreated));
    assertTrue(storageDirNow.mkdirs());
    assertTrue(new File(storageDirNow, bytesFileNow + ".bytes").createNewFile());

    File storageDir1MinOld = new File(contentDir, getDatePath(blobCreated.minusMinutes(1)));
    assertTrue(storageDir1MinOld.mkdirs());
    assertTrue(new File(storageDir1MinOld, bytesFile1minOld + ".bytes").createNewFile());

    File storageDir3MinOld = new File(contentDir, getDatePath(blobCreated.minusMinutes(3)));
    assertTrue(storageDir3MinOld.mkdirs());
    assertTrue(new File(storageDir3MinOld, bytesFile3minOld + ".bytes").createNewFile());

    File storageDir10MinOld = new File(contentDir, getDatePath(blobCreated.minusMinutes(10)));
    assertTrue(storageDir10MinOld.mkdirs());
    assertTrue(new File(storageDir10MinOld, bytesFile10minOld + ".bytes").createNewFile());

    // create 3 files: 1 hour ago, 3 hours ago, and 10 hours ago
    File storageDir1hOld = new File(contentDir, getDatePath(blobCreated.minusHours(1)));
    assertTrue(storageDir1hOld.mkdirs());
    assertTrue(new File(storageDir1hOld, bytesFile1hOld + ".bytes").createNewFile());

    File storageDir3hOld = new File(contentDir, getDatePath(blobCreated.minusHours(3)));
    assertTrue(storageDir3hOld.mkdirs());
    assertTrue(new File(storageDir3hOld, bytesFile3hOld + ".bytes").createNewFile());

    File storageDir10hOld = new File(contentDir, getDatePath(blobCreated.minusHours(10)));
    assertTrue(storageDir10hOld.mkdirs());
    assertTrue(new File(storageDir10hOld, bytesFile10hOld + ".bytes").createNewFile());

    // create 3 files: 1 day ago, 3 days ago, and 10 days ago
    File storageDir1dOld = new File(contentDir, getDatePath(blobCreated.minusDays(1)));
    assertTrue(storageDir1dOld.mkdirs());
    assertTrue(new File(storageDir1dOld, bytesFile1dOld + ".bytes").createNewFile());

    File storageDir3dOld = new File(contentDir, getDatePath(blobCreated.minusDays(3)));
    assertTrue(storageDir3dOld.mkdirs());
    assertTrue(new File(storageDir3dOld, bytesFile3dOld + ".bytes").createNewFile());

    File storageDir10dOld = new File(contentDir, getDatePath(blobCreated.minusDays(10)));
    assertTrue(storageDir10dOld.mkdirs());
    assertTrue(new File(storageDir10dOld, bytesFile10dOld + ".bytes").createNewFile());

    // create 3 files: 1 month ago, 3 months ago, and 10 months ago
    File storageDir1mOld = new File(contentDir, getDatePath(blobCreated.minusMonths(1)));
    assertTrue(storageDir1mOld.mkdirs());
    assertTrue(new File(storageDir1mOld, bytesFile1mOld + ".bytes").createNewFile());

    File storageDir3mOld = new File(contentDir, getDatePath(blobCreated.minusMonths(3)));
    assertTrue(storageDir3mOld.mkdirs());
    assertTrue(new File(storageDir3mOld, bytesFile3mOld + ".bytes").createNewFile());

    File storageDir10mOld = new File(contentDir, getDatePath(blobCreated.minusMonths(10)));
    assertTrue(storageDir10mOld.mkdirs());
    assertTrue(new File(storageDir10mOld, bytesFile10mOld + ".bytes").createNewFile());

    // find all files that have been created 5 min ago
    Duration fiveMin = Duration.ofSeconds(blobCreated.toEpochSecond() - blobCreated.minusMinutes(5L).toEpochSecond());
    DateBasedWalkFile walkFile = new DateBasedWalkFile(contentDir.getAbsolutePath(), fiveMin);
    List<String> blobIds = new ArrayList<>(walkFile.getBlobIdToDateRef().keySet());
    assertThat(blobIds, containsInAnyOrder(bytesFileNow, bytesFile1minOld, bytesFile3minOld));

    // find all files that have been created 5 hours ago
    Duration fiveHours = Duration.ofSeconds(blobCreated.toEpochSecond() - blobCreated.minusHours(5L).toEpochSecond());
    walkFile = new DateBasedWalkFile(contentDir.getAbsolutePath(), fiveHours);
    blobIds = new ArrayList<>(walkFile.getBlobIdToDateRef().keySet());
    assertThat(blobIds, containsInAnyOrder(
        bytesFileNow, bytesFile1minOld, bytesFile3minOld, bytesFile10minOld,
        bytesFile1hOld, bytesFile3hOld));

    // find all files that have been created 5 days ago
    Duration fiveDays = Duration.ofSeconds(blobCreated.toEpochSecond() - blobCreated.minusDays(5L).toEpochSecond());
    walkFile = new DateBasedWalkFile(contentDir.getAbsolutePath(), fiveDays);
    blobIds = new ArrayList<>(walkFile.getBlobIdToDateRef().keySet());
    assertThat(blobIds, containsInAnyOrder(
        bytesFileNow, bytesFile1minOld, bytesFile3minOld, bytesFile10minOld,
        bytesFile1hOld, bytesFile3hOld, bytesFile10hOld,
        bytesFile1dOld, bytesFile3dOld));

    // find all files that have been created 5 months ago
    Duration fiveMonths = Duration.ofSeconds(blobCreated.toEpochSecond() - blobCreated.minusMonths(5L).toEpochSecond());
    walkFile = new DateBasedWalkFile(contentDir.getAbsolutePath(), fiveMonths);
    blobIds = new ArrayList<>(walkFile.getBlobIdToDateRef().keySet());
    assertThat(blobIds, containsInAnyOrder(
        bytesFileNow, bytesFile1minOld, bytesFile3minOld, bytesFile10minOld,
        bytesFile1hOld, bytesFile3hOld, bytesFile10hOld,
        bytesFile1dOld, bytesFile3dOld, bytesFile10dOld,
        bytesFile1mOld, bytesFile3mOld));
  }

  /**
   * Test that file walking works correctly when executed in a virtual thread.
   */
  @Test
  public void testWalkFilesInVirtualThread() throws Exception {
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        runWalkFilesTest();
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
    }, task -> Thread.startVirtualThread(task));
    
    future.get(); // Wait for the virtual thread to complete
  }
  
  /**
   * Runs the file walking test with different durations.
   */
  private void runWalkFilesTest() throws IOException {
    File contentDir = new File(tempDir.toFile(), CONTENT_PREFIX);

    OffsetDateTime blobCreated = UTC.now();
    String bytesFileNow = "now-vt";
    String bytesFile1minOld = "1minOld-vt";
    String bytesFile3minOld = "3minOld-vt";
    String bytesFile10minOld = "10minOld-vt";

    // create 4 files: now, 1 min ago, 3 min ago, and 10 min ago
    File storageDirNow = new File(contentDir, getDatePath(blobCreated));
    assertTrue(storageDirNow.mkdirs());
    assertTrue(new File(storageDirNow, bytesFileNow + ".bytes").createNewFile());

    File storageDir1MinOld = new File(contentDir, getDatePath(blobCreated.minusMinutes(1)));
    assertTrue(storageDir1MinOld.mkdirs());
    assertTrue(new File(storageDir1MinOld, bytesFile1minOld + ".bytes").createNewFile());

    File storageDir3MinOld = new File(contentDir, getDatePath(blobCreated.minusMinutes(3)));
    assertTrue(storageDir3MinOld.mkdirs());
    assertTrue(new File(storageDir3MinOld, bytesFile3minOld + ".bytes").createNewFile());

    File storageDir10MinOld = new File(contentDir, getDatePath(blobCreated.minusMinutes(10)));
    assertTrue(storageDir10MinOld.mkdirs());
    assertTrue(new File(storageDir10MinOld, bytesFile10minOld + ".bytes").createNewFile());

    // find all files that have been created 5 min ago
    Duration fiveMin = Duration.ofSeconds(blobCreated.toEpochSecond() - blobCreated.minusMinutes(5L).toEpochSecond());
    DateBasedWalkFile walkFile = new DateBasedWalkFile(contentDir.getAbsolutePath(), fiveMin);
    List<String> blobIds = new ArrayList<>(walkFile.getBlobIdToDateRef().keySet());
    
    // Check if the expected files are found
    // Note: This will include files from both tests since they share the same temp directory
    assertTrue(blobIds.contains(bytesFileNow));
    assertTrue(blobIds.contains(bytesFile1minOld));
    assertTrue(blobIds.contains(bytesFile3minOld));
  }
  
  /**
   * Test that multiple concurrent file walks can be performed using virtual threads.
   */
  @Test
  public void testConcurrentFileWalksWithVirtualThreads() throws Exception {
    File contentDir = new File(tempDir.toFile(), CONTENT_PREFIX);
    OffsetDateTime blobCreated = UTC.now();
    
    // Create test directory structure
    File storageDir = new File(contentDir, getDatePath(blobCreated));
    assertTrue(storageDir.mkdirs());
    assertTrue(new File(storageDir, "concurrent-test.bytes").createNewFile());
    
    // Create multiple virtual threads to perform file walks concurrently
    int threadCount = 10;
    CompletableFuture<?>[] futures = new CompletableFuture[threadCount];
    
    for (int i = 0; i < threadCount; i++) {
      final int threadId = i;
      futures[i] = CompletableFuture.runAsync(() -> {
        try {
          // Each thread performs a file walk with a different duration
          Duration duration = Duration.ofSeconds(blobCreated.toEpochSecond() - 
              blobCreated.minusMinutes(threadId + 1).toEpochSecond());
          DateBasedWalkFile walkFile = new DateBasedWalkFile(contentDir.getAbsolutePath(), duration);
          List<String> blobIds = new ArrayList<>(walkFile.getBlobIdToDateRef().keySet());
          
          // Verify that the file walk found at least the test file
          assertTrue(blobIds.contains("concurrent-test"));
        }
        catch (Exception e) {
          throw new RuntimeException("Error in virtual thread " + threadId, e);
        }
      }, task -> Thread.startVirtualThread(task));
    }
    
    // Wait for all virtual threads to complete
    CompletableFuture.allOf(futures).get();
  }

  private static String getDatePath(final OffsetDateTime blobCreated) {
    return blobCreated.format(BlobRef.DATE_TIME_PATH_FORMATTER);
  }
}