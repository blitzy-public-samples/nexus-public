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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.BlobMetrics;

import com.google.common.collect.ImmutableMap;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link FileBlobAttributes}.
 * 
 * This test validates the persistence and retrieval of blob attributes in the file system,
 * with compatibility for both platform threads and virtual threads.
 */
public class FileBlobAttributesTest
    extends TestSupport
{
  @TempDir
  Path temporaryFolder;

  /**
   * Tests the persistence of blob attributes to the file system.
   */
  @Test
  public void testPersistence() throws Exception {
    Path path = Files.createTempFile(temporaryFolder, "test", ".properties");

    Map<String, String> headers = ImmutableMap.of("hello", "world");
    BlobMetrics metrics = new BlobMetrics(new DateTime(987654321), "0123456789ABCDEF", 42);
    FileBlobAttributes original = new FileBlobAttributes(path, headers, metrics);

    original.store();

    assertTrue(Files.isRegularFile(original.getPath()), "Attribute file should exist");

    Properties properties = new Properties();
    try (var reader = Files.newBufferedReader(original.getPath())) {
      properties.load(reader);
    }

    assertThat(properties.remove("@hello"), is("world"));
    assertThat(properties.remove("creationTime"), is("987654321"));
    assertThat(properties.remove("sha1"), is("0123456789ABCDEF"));
    assertThat(properties.remove("size"), is("42"));
    assertThat(properties.keySet(), is(empty()));

    original.setDeleted(true);
    original.store();

    try (var reader = Files.newBufferedReader(original.getPath())) {
      properties.load(reader);
    }

    assertThat(properties.remove("@hello"), is("world"));
    assertThat(properties.remove("creationTime"), is("987654321"));
    assertThat(properties.remove("sha1"), is("0123456789ABCDEF"));
    assertThat(properties.remove("size"), is("42"));
    assertThat(properties.remove("deleted"), is("true"));
    assertThat(properties.remove("deletedReason"), is("No reason supplied"));
    assertThat(properties.keySet(), is(empty()));

    original.setDeletedReason("Spring cleaning");
    original.store();

    try (var reader = Files.newBufferedReader(original.getPath())) {
      properties.load(reader);
    }

    assertThat(properties.remove("@hello"), is("world"));
    assertThat(properties.remove("creationTime"), is("987654321"));
    assertThat(properties.remove("sha1"), is("0123456789ABCDEF"));
    assertThat(properties.remove("size"), is("42"));
    assertThat(properties.remove("deleted"), is("true"));
    assertThat(properties.remove("deletedReason"), is("Spring cleaning"));
    assertThat(properties.keySet(), is(empty()));
  }

  /**
   * Tests the roundtrip of storing and loading blob attributes.
   */
  @Test
  public void testRoundtrip() throws Exception {
    Path path = Files.createTempFile(temporaryFolder, "test", ".properties");

    Map<String, String> headers = ImmutableMap.of("hello", "world");
    BlobMetrics metrics = new BlobMetrics(DateTime.now(), "0123456789ABCDEF", 42);
    FileBlobAttributes original = new FileBlobAttributes(path, headers, metrics);

    verifyRoundtrip(original);

    original.setDeleted(true);

    verifyRoundtrip(original);

    original.setDeletedReason("Spring cleaning");

    verifyRoundtrip(original);
  }

  /**
   * Tests updating attributes from another instance.
   */
  @Test
  public void testUpdateFrom() throws Exception {
    Path originalPath = Files.createTempFile(temporaryFolder, "original", ".properties");

    Map<String, String> headers = ImmutableMap.of("hello", "world");
    BlobMetrics metrics = new BlobMetrics(DateTime.now(), "0123456789ABCDEF", 42);
    FileBlobAttributes original = new FileBlobAttributes(originalPath, headers, metrics);

    Path updatedPath = Files.createTempFile(temporaryFolder, "updated", ".properties");

    FileBlobAttributes updated = new FileBlobAttributes(updatedPath);
    updated.updateFrom(original);
    updated.store();

    updated = new FileBlobAttributes(updatedPath);
    updated.load();

    assertThat(updated.getHeaders(), is(original.getHeaders()));
    assertThat(updated.getMetrics().getCreationTime(), is(original.getMetrics().getCreationTime()));
    assertThat(updated.getMetrics().getSha1Hash(), is(original.getMetrics().getSha1Hash()));
    assertThat(updated.getMetrics().getContentSize(), is(original.getMetrics().getContentSize()));
    assertThat(updated.isDeleted(), is(original.isDeleted()));
    assertThat(updated.getDeletedReason(), is(original.getDeletedReason()));
  }

  /**
   * Tests concurrent operations on blob attributes using virtual threads.
   * This test validates that the FileBlobAttributes implementation works correctly
   * under high concurrency with virtual threads.
   */
  @Test
  public void testConcurrentOperationsWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Number of concurrent operations to perform
      int operationCount = 100;
      
      // Create a temporary file for each operation
      Path[] paths = new Path[operationCount];
      for (int i = 0; i < operationCount; i++) {
        paths[i] = Files.createTempFile(temporaryFolder, "concurrent-" + i, ".properties");
      }
      
      // Submit tasks to create, store, and load attributes concurrently
      for (int i = 0; i < operationCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create and store attributes
            Map<String, String> headers = ImmutableMap.of("key-" + index, "value-" + index);
            BlobMetrics metrics = new BlobMetrics(DateTime.now(), "hash-" + index, index * 100);
            FileBlobAttributes attributes = new FileBlobAttributes(paths[index], headers, metrics);
            attributes.store();
            
            // Load attributes and verify
            FileBlobAttributes loaded = new FileBlobAttributes(paths[index]);
            loaded.load();
            
            // Verify the loaded attributes match what we stored
            assertThat(loaded.getHeaders().get("key-" + index), is("value-" + index));
            assertThat(loaded.getMetrics().getSha1Hash(), is("hash-" + index));
            assertThat(loaded.getMetrics().getContentSize(), is((long) index * 100));
            
            return null;
          } catch (IOException e) {
            throw new RuntimeException("Failed concurrent operation", e);
          }
        });
      }
      
      // Shutdown the executor and wait for all tasks to complete
      executor.shutdown();
      assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), 
          "All concurrent operations should complete within timeout");
    }
  }

  /**
   * Helper method to verify roundtrip persistence of blob attributes.
   */
  private static void verifyRoundtrip(final FileBlobAttributes original) throws IOException {
    original.store();

    FileBlobAttributes restored = new FileBlobAttributes(original.getPath());

    restored.load();

    assertThat(restored.getPath(), is(original.getPath()));
    assertThat(restored.getHeaders(), is(original.getHeaders()));
    assertThat(restored.getMetrics().getCreationTime(), is(original.getMetrics().getCreationTime()));
    assertThat(restored.getMetrics().getSha1Hash(), is(original.getMetrics().getSha1Hash()));
    assertThat(restored.getMetrics().getContentSize(), is(original.getMetrics().getContentSize()));
    assertThat(restored.isDeleted(), is(original.isDeleted()));
    assertThat(restored.getDeletedReason(), is(original.getDeletedReason()));
  }
}