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
package org.sonatype.nexus.repository.maven.internal.utils;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.repository.maven.MavenPath.HashType;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.payloads.PathPayload;
import org.sonatype.nexus.repository.view.payloads.StringPayload;

import com.google.common.hash.HashCode;
import com.google.common.hash.HashingOutputStream;

import static org.sonatype.nexus.repository.maven.internal.Constants.CHECKSUM_CONTENT_TYPE;

/**
 * The contents of this class is existing code refactored from
 * {@link org.sonatype.nexus.repository.maven.internal.orient.MavenFacetUtils}.
 *
 * Specifically, this was done so that both the Orient and SQL database code paths can use the none database specific
 * code now contained herein.
 *
 * The integration tests for Maven will exercise the code in this utility class when running against an Orient or SQL
 * database.
 *
 * @since 3.25
 */
public final class MavenIOUtils
{
  private MavenIOUtils() {
    //no-op
  }

  /**
   * Wrapper to pass in into {@link #createStreamPayload(Path, String, Writer)} to write out actual content.
   */
  @FunctionalInterface
  public interface Writer
  {
    /**
     * Writes content to the provided output stream.
     *
     * @param outputStream the stream to write to
     * @throws IOException if an I/O error occurs
     */
    void write(OutputStream outputStream) throws IOException;
  }

  /**
   * Creates a stream payload with calculated hash codes.
   *
   * @param path the path to write to
   * @param contentType the content type of the payload
   * @param writer the writer to write the content
   * @return a hashed payload containing the payload and its hash codes
   * @throws IOException if an I/O error occurs
   */
  public static HashedPayload createStreamPayload(
      final Path path, final String contentType,
      final Writer writer) throws IOException
  {
    Map<HashAlgorithm, HashingOutputStream> hashingStreams = writeToPath(path, writer);
    Map<HashAlgorithm, HashCode> hashCodes = generateHashCodes(hashingStreams);
    return new HashedPayload(asPayload(path, contentType), hashCodes);
  }

  /**
   * Writes content to a path using the provided writer, calculating hash codes during the process.
   * Uses a buffered output stream for efficiency.
   *
   * @param path the path to write to
   * @param writer the writer to write the content
   * @return a map of hash algorithms to their corresponding hashing output streams
   * @throws IOException if an I/O error occurs
   */
  private static Map<HashAlgorithm, HashingOutputStream> writeToPath(final Path path, final Writer writer)
      throws IOException
  {
    Map<HashAlgorithm, HashingOutputStream> hashingStreams = new HashMap<>();
    try (var outputStream = new BufferedOutputStream(Files.newOutputStream(path))) {
      OutputStream os = outputStream;
      for (HashType hashType : HashType.values()) {
        os = new HashingOutputStream(hashType.getHashAlgorithm().function(), os);
        hashingStreams.put(hashType.getHashAlgorithm(), (HashingOutputStream) os);
      }
      writer.write(os);
      os.flush();
    }
    return hashingStreams;
  }

  /**
   * Generates hash codes from the provided hashing output streams.
   *
   * @param hashingStreams a map of hash algorithms to their corresponding hashing output streams
   * @return a map of hash algorithms to their calculated hash codes
   */
  private static Map<HashAlgorithm, HashCode> generateHashCodes(
      final Map<HashAlgorithm, HashingOutputStream> hashingStreams)
  {
    Map<HashAlgorithm, HashCode> hashCodes = new HashMap<>();
    for (var entry : hashingStreams.entrySet()) {
      hashCodes.put(entry.getKey(), entry.getValue().hash());
    }
    return hashCodes;
  }

  /**
   * Creates a payload from a path and content type.
   *
   * @param path the path to create the payload from
   * @param contentType the content type of the payload
   * @return a payload representing the path and content type
   * @throws IOException if an I/O error occurs
   */
  private static Payload asPayload(final Path path, final String contentType) throws IOException {
    return new PathPayload(path, contentType);
  }

  /**
   * Converts hash codes to payloads.
   *
   * @param hashCodes a map of hash algorithms to their hash codes
   * @return a map of hash types to their corresponding payloads
   */
  public static Map<HashType, Payload> hashesToPayloads(final Map<HashAlgorithm, HashCode> hashCodes)
  {
    Map<HashType, Payload> payloadByHash = new EnumMap<>(HashType.class);
    for (HashType hashType : HashType.values()) {
      HashCode hashCode = hashCodes.get(hashType.getHashAlgorithm());
      if (hashCode != null) {
        var payload = new StringPayload(hashCode.toString(), CHECKSUM_CONTENT_TYPE);
        payloadByHash.put(hashType, payload);
      }
    }
    return payloadByHash;
  }
}