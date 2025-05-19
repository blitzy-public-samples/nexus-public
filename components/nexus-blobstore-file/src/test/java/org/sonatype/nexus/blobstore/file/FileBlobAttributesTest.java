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

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.BlobMetrics;

import com.google.common.collect.ImmutableMap;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link FileBlobAttributes}.
 * 
 * This test is compatible with both platform threads and virtual threads.
 */
public class FileBlobAttributesTest
    extends TestSupport
{
  @TempDir
  Path tempDir;

  @Test
  public void testPersistence() throws Exception {
    Path path = Files.createTempFile(tempDir, "blob-attributes", ".properties");

    Map<String, String> headers = ImmutableMap.of("hello", "world");
    BlobMetrics metrics = new BlobMetrics(new DateTime(987654321), "0123456789ABCDEF", 42);
    FileBlobAttributes original = new FileBlobAttributes(path, headers, metrics);

    original.store();

    assertTrue(Files.isRegularFile(original.getPath()));

    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(original.getPath(), StandardCharsets.UTF_8)) {
      properties.load(reader);
    }

    assertEquals("world", properties.remove("@hello"));
    assertEquals("987654321", properties.remove("creationTime"));
    assertEquals("0123456789ABCDEF", properties.remove("sha1"));
    assertEquals("42", properties.remove("size"));
    assertTrue(properties.keySet().isEmpty());

    original.setDeleted(true);
    original.store();

    try (Reader reader = Files.newBufferedReader(original.getPath(), StandardCharsets.UTF_8)) {
      properties.load(reader);
    }

    assertEquals("world", properties.remove("@hello"));
    assertEquals("987654321", properties.remove("creationTime"));
    assertEquals("0123456789ABCDEF", properties.remove("sha1"));
    assertEquals("42", properties.remove("size"));
    assertEquals("true", properties.remove("deleted"));
    assertEquals("No reason supplied", properties.remove("deletedReason"));
    assertTrue(properties.keySet().isEmpty());

    original.setDeletedReason("Spring cleaning");
    original.store();

    try (Reader reader = Files.newBufferedReader(original.getPath(), StandardCharsets.UTF_8)) {
      properties.load(reader);
    }

    assertEquals("world", properties.remove("@hello"));
    assertEquals("987654321", properties.remove("creationTime"));
    assertEquals("0123456789ABCDEF", properties.remove("sha1"));
    assertEquals("42", properties.remove("size"));
    assertEquals("true", properties.remove("deleted"));
    assertEquals("Spring cleaning", properties.remove("deletedReason"));
    assertTrue(properties.keySet().isEmpty());
  }

  @Test
  public void testRoundtrip() throws Exception {
    Path path = Files.createTempFile(tempDir, "blob-attributes", ".properties");

    Map<String, String> headers = ImmutableMap.of("hello", "world");
    BlobMetrics metrics = new BlobMetrics(DateTime.now(), "0123456789ABCDEF", 42);
    FileBlobAttributes original = new FileBlobAttributes(path, headers, metrics);

    verifyRoundtrip(original);

    original.setDeleted(true);

    verifyRoundtrip(original);

    original.setDeletedReason("Spring cleaning");

    verifyRoundtrip(original);
  }

  @Test
  public void testUpdateFrom() throws Exception {
    Path originalPath = Files.createTempFile(tempDir, "original-attributes", ".properties");

    Map<String, String> headers = ImmutableMap.of("hello", "world");
    BlobMetrics metrics = new BlobMetrics(DateTime.now(), "0123456789ABCDEF", 42);
    FileBlobAttributes original = new FileBlobAttributes(originalPath, headers, metrics);

    Path updatedPath = Files.createTempFile(tempDir, "updated-attributes", ".properties");

    FileBlobAttributes updated = new FileBlobAttributes(updatedPath);
    updated.updateFrom(original);
    updated.store();

    updated = new FileBlobAttributes(updatedPath);
    updated.load();

    assertEquals(original.getHeaders(), updated.getHeaders());
    assertEquals(original.getMetrics().getCreationTime(), updated.getMetrics().getCreationTime());
    assertEquals(original.getMetrics().getSha1Hash(), updated.getMetrics().getSha1Hash());
    assertEquals(original.getMetrics().getContentSize(), updated.getMetrics().getContentSize());
    assertEquals(original.isDeleted(), updated.isDeleted());
    assertEquals(original.getDeletedReason(), updated.getDeletedReason());
  }

  private static void verifyRoundtrip(final FileBlobAttributes original) throws IOException {
    original.store();

    FileBlobAttributes restored = new FileBlobAttributes(original.getPath());

    restored.load();

    assertEquals(original.getPath(), restored.getPath());
    assertEquals(original.getHeaders(), restored.getHeaders());
    assertEquals(original.getMetrics().getCreationTime(), restored.getMetrics().getCreationTime());
    assertEquals(original.getMetrics().getSha1Hash(), restored.getMetrics().getSha1Hash());
    assertEquals(original.getMetrics().getContentSize(), restored.getMetrics().getContentSize());
    assertEquals(original.isDeleted(), restored.isDeleted());
    assertEquals(original.getDeletedReason(), restored.getDeletedReason());
  }
}