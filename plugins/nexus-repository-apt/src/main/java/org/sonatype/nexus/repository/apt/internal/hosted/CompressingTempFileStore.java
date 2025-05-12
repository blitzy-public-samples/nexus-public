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
package org.sonatype.nexus.repository.apt.internal.hosted;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.io.InputStreamSupplier;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.io.output.CountingOutputStream;
import org.bouncycastle.util.io.TeeOutputStream;

/**
 * Stores a set of temp files, automatically compressing each into a GZIP, BZ2 and plain format.
 * <p>
 * This implementation leverages Java 21 features for improved resource management and I/O handling.
 *
 * @since 3.17
 */
public class CompressingTempFileStore
    extends ComponentSupport
    implements AutoCloseable
{
  private final Map<String, FileHolder> holdersByKey = new HashMap<>();

  /**
   * Opens an output writer for the specified key.
   * <p>
   * The writer will simultaneously write to three output streams:
   * - GZIP compressed
   * - BZip2 compressed
   * - Plain (uncompressed)
   *
   * @param key the identifier for this output
   * @return a Writer that writes to all three output formats
   * @throws IllegalStateException if output for this key is already opened
   * @throws UncheckedIOException if an I/O error occurs
   */
  public Writer openOutput(final String key) {
    try {
      if (holdersByKey.containsKey(key)) {
        throw new IllegalStateException("Output already opened");
      }
      FileHolder holder = new FileHolder();
      holdersByKey.put(key, holder);
      
      return new OutputStreamWriter(
          new TeeOutputStream(
              new TeeOutputStream(
                  new GZIPOutputStream(Files.newOutputStream(holder.gzTempFile)),
                  new BZip2CompressorOutputStream(Files.newOutputStream(holder.bzTempFile))),
              Files.newOutputStream(holder.plainTempFile)), 
          StandardCharsets.UTF_8);
    }
    catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Returns metadata about all files stored in this instance.
   *
   * @return a map of file metadata by key
   */
  public Map<String, FileMetadata> getFiles() {
    return holdersByKey.entrySet().stream()
        .collect(HashMap::new, 
            (map, entry) -> map.put(entry.getKey(), new FileMetadata(entry.getValue())),
            HashMap::putAll);
  }

  /**
   * Closes all resources and deletes temporary files.
   * This method should be called when the files are no longer needed.
   */
  @Override
  public void close() {
    List<Path> notDeletedFiles = new LinkedList<>();

    for (FileHolder holder : holdersByKey.values()) {
      deleteFile(holder.bzTempFile, notDeletedFiles);
      deleteFile(holder.gzTempFile, notDeletedFiles);
      deleteFile(holder.plainTempFile, notDeletedFiles);
    }

    if (!notDeletedFiles.isEmpty()) {
      log.warn("Files were not successfully deleted: {}", notDeletedFiles);
    }
  }

  /**
   * Attempts to delete a file, adding it to the provided list if deletion fails.
   *
   * @param path the path to the file to delete
   * @param paths list to add the path to if deletion fails
   */
  private void deleteFile(final Path path, final List<Path> paths) {
    try {
      Files.deleteIfExists(path);
    }
    catch (IOException e) { // NOSONAR
      paths.add(path);
    }
  }

  /**
   * Metadata about a set of compressed and uncompressed files.
   */
  public static class FileMetadata
  {
    private final FileHolder holder;

    private FileMetadata(final FileHolder holder) {
      this.holder = holder;
    }

    /**
     * @return the size of the BZip2 compressed file in bytes
     */
    public long bzSize() {
      return holder.bzStream.getByteCount();
    }

    /**
     * @return an input stream supplier for the BZip2 compressed file
     */
    public InputStreamSupplier bzSupplier() {
      return () -> Files.newInputStream(holder.bzTempFile);
    }

    /**
     * @return the size of the GZIP compressed file in bytes
     */
    public long gzSize() {
      return holder.gzStream.getByteCount();
    }

    /**
     * @return an input stream supplier for the GZIP compressed file
     */
    public InputStreamSupplier gzSupplier() {
      return () -> Files.newInputStream(holder.gzTempFile);
    }

    /**
     * @return the size of the uncompressed file in bytes
     */
    public long plainSize() {
      return holder.plainStream.getByteCount();
    }

    /**
     * @return an input stream supplier for the uncompressed file
     */
    public InputStreamSupplier plainSupplier() {
      return () -> Files.newInputStream(holder.plainTempFile);
    }
  }

  /**
   * Holds the temporary files and their associated streams.
   */
  private static class FileHolder
  {
    final CountingOutputStream plainStream;
    final Path plainTempFile;
    final CountingOutputStream gzStream;
    final Path gzTempFile;
    final CountingOutputStream bzStream;
    final Path bzTempFile;

    /**
     * Creates temporary files for plain, GZIP, and BZip2 formats.
     *
     * @throws IOException if an I/O error occurs
     */
    public FileHolder() throws IOException {
      this.plainTempFile = Files.createTempFile("", "");
      this.plainStream = new CountingOutputStream(Files.newOutputStream(plainTempFile));
      this.gzTempFile = Files.createTempFile("", "");
      this.gzStream = new CountingOutputStream(Files.newOutputStream(gzTempFile));
      this.bzTempFile = Files.createTempFile("", "");
      this.bzStream = new CountingOutputStream(Files.newOutputStream(bzTempFile));
    }
  }
}