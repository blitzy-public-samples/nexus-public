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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ZipSupport}.
 */
class ZipSupportTest
    extends TestSupport
{
  @TempDir
  Path tempDir;

  private static final byte[] PAYLOAD = "payload".getBytes(UTF_8);

  private Path root;

  private final ZipSupport zipSupport = new ZipSupport();

  @BeforeEach
  void prepare() throws IOException {
    root = tempDir.resolve("root");
    Files.createDirectories(root);
    Files.write(root.resolve("file1.tx"), PAYLOAD);
    Files.write(root.resolve("file2.txt"), PAYLOAD);
    Files.write(root.resolve("file4.txt"), PAYLOAD);
  }

  @Test
  void testZipFiles() throws IOException {
    List<String> filesToZip = Arrays.asList("file1.txt", "file2.txt", "file3.txt");

    Path zipFilePath = root.resolve("test.zip");
    String zipFileName = zipFilePath.toString();

    zipSupport.zipFiles(root, filesToZip, zipFileName);

    File zipFile = zipFilePath.toFile();
    assertTrue(zipFile.exists(), "Zip file should exist");
  }
  
  @Test
  void testZipFilesWithConcurrentAccess() throws IOException, InterruptedException {
    // Create multiple files to zip
    for (int i = 0; i < 10; i++) {
      Files.write(root.resolve("concurrent-file" + i + ".txt"), PAYLOAD);
    }
    
    List<String> filesToZip = Arrays.asList(
        "concurrent-file0.txt", 
        "concurrent-file1.txt", 
        "concurrent-file2.txt",
        "concurrent-file3.txt",
        "concurrent-file4.txt");
    
    Path zipFilePath = root.resolve("concurrent-test.zip");
    String zipFileName = zipFilePath.toString();
    
    // Test concurrent access using Java 21 virtual threads
    Thread thread = Thread.ofVirtual().start(() -> {
      try {
        zipSupport.zipFiles(root, filesToZip, zipFileName);
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
    
    // Wait for the thread to complete
    thread.join();
    
    // Verify the zip file was created
    File zipFile = zipFilePath.toFile();
    assertTrue(zipFile.exists(), "Zip file should exist after concurrent operation");
  }
  
  @Test
  void testZipFileContents() throws IOException {
    // Create test files with different content
    byte[] content1 = "content1".getBytes(UTF_8);
    byte[] content2 = "content2".getBytes(UTF_8);
    
    Files.write(root.resolve("content1.txt"), content1);
    Files.write(root.resolve("content2.txt"), content2);
    
    List<String> filesToZip = Arrays.asList("content1.txt", "content2.txt");
    
    Path zipFilePath = root.resolve("contents-test.zip");
    String zipFileName = zipFilePath.toString();
    
    zipSupport.zipFiles(root, filesToZip, zipFileName);
    
    // Verify zip file exists
    File zipFile = zipFilePath.toFile();
    assertTrue(zipFile.exists(), "Zip file should exist");
    
    // Verify zip file contents using Hamcrest matchers
    try (ZipFile zip = new ZipFile(zipFile)) {
      // Check first file
      ZipEntry entry1 = zip.getEntry("content1.txt");
      assertThat("First entry should exist", entry1, is(notNullValue()));
      
      try (InputStream is = zip.getInputStream(entry1)) {
        byte[] fileBytes = is.readAllBytes();
        assertThat("First file content should match", fileBytes, is(equalTo(content1)));
      }
      
      // Check second file
      ZipEntry entry2 = zip.getEntry("content2.txt");
      assertThat("Second entry should exist", entry2, is(notNullValue()));
      
      try (InputStream is = zip.getInputStream(entry2)) {
        byte[] fileBytes = is.readAllBytes();
        assertThat("Second file content should match", fileBytes, is(equalTo(content2)));
      }
    }
  }
  
  @Test
  void testZipFilesWithNonExistentFiles() throws IOException {
    // Create one file that exists
    Files.write(root.resolve("existing.txt"), PAYLOAD);
    
    // Create a list with both existing and non-existing files
    List<String> filesToZip = Arrays.asList(
        "existing.txt",
        "non-existent1.txt",
        "non-existent2.txt"
    );
    
    Path zipFilePath = root.resolve("mixed-files.zip");
    String zipFileName = zipFilePath.toString();
    
    // This should complete without exceptions, skipping non-existent files
    zipSupport.zipFiles(root, filesToZip, zipFileName);
    
    // Verify zip file exists
    File zipFile = zipFilePath.toFile();
    assertTrue(zipFile.exists(), "Zip file should exist");
    
    // Verify only the existing file is in the zip
    try (ZipFile zip = new ZipFile(zipFile)) {
      // The existing file should be in the zip
      ZipEntry existingEntry = zip.getEntry("existing.txt");
      assertThat("Existing file should be in the zip", existingEntry, is(notNullValue()));
      
      // Non-existent files should not be in the zip
      ZipEntry nonExistentEntry1 = zip.getEntry("non-existent1.txt");
      assertThat("Non-existent file should not be in the zip", nonExistentEntry1, is(org.hamcrest.Matchers.nullValue()));
      
      ZipEntry nonExistentEntry2 = zip.getEntry("non-existent2.txt");
      assertThat("Non-existent file should not be in the zip", nonExistentEntry2, is(org.hamcrest.Matchers.nullValue()));
    }
  }
}