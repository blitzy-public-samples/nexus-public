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
package org.sonatype.nexus.common.hash;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javax.annotation.Nonnull;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.HashingInputStream;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * An {@link InputStream} that maintains multiple hashes and the number of bytes of data read from it.
 * Optimized for Java 21 Virtual Threads with non-blocking I/O operations and thread-safe hashing.
 *
 * @see HashingInputStream
 * @since 3.0
 */
public class MultiHashingInputStream
    extends FilterInputStream
{
  /**
   * Default buffer size for optimized reading in Virtual Thread context.
   * Smaller buffer size reduces memory pressure and improves Virtual Thread scheduling.
   */
  private static final int DEFAULT_BUFFER_SIZE = 8192;

  /**
   * Virtual Thread executor for asynchronous hashing operations.
   * Uses Java 21's Virtual Thread per task executor for optimal I/O performance.
   */
  private static final Executor HASHING_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

  /**
   * Thread-safe map of hash algorithms to their corresponding hashers.
   */
  protected final Map<HashAlgorithm, Hasher> hashers;

  /**
   * Atomic counter for tracking the number of bytes read.
   * Using AtomicLong for thread safety in Virtual Thread environment.
   */
  private final AtomicLong count = new AtomicLong(0);

  /**
   * Tracks pending hashing operations to ensure all hashes are complete before returning results.
   */
  private final Map<HashAlgorithm, CompletableFuture<Void>> pendingHashes = new ConcurrentHashMap<>();

  /**
   * Buffer size used for reading operations, optimized for Virtual Threads.
   */
  private final int bufferSize;

  /**
   * Creates a new MultiHashingInputStream with the default buffer size.
   *
   * @param algorithms the hash algorithms to use
   * @param inputStream the input stream to read from and hash
   */
  public MultiHashingInputStream(final Iterable<HashAlgorithm> algorithms, final InputStream inputStream) {
    this(algorithms, inputStream, DEFAULT_BUFFER_SIZE);
  }

  /**
   * Creates a new MultiHashingInputStream with a custom buffer size.
   *
   * @param algorithms the hash algorithms to use
   * @param inputStream the input stream to read from and hash
   * @param bufferSize the buffer size to use for reading operations
   */
  public MultiHashingInputStream(final Iterable<HashAlgorithm> algorithms, final InputStream inputStream, final int bufferSize) {
    super(checkNotNull(inputStream));
    checkNotNull(algorithms);
    this.bufferSize = bufferSize;
    
    // Using ConcurrentHashMap for thread safety in Virtual Thread environment
    this.hashers = new ConcurrentHashMap<>();
    for (HashAlgorithm algorithm : algorithms) {
      hashers.put(algorithm, algorithm.function().newHasher());
    }
  }

  @Override
  public int read() throws IOException {
    waitForHashes();

    int b = in.read();
    if (b != -1) {
      submitHashing(hasher -> hasher.putByte((byte) b));
      count.incrementAndGet();
    }
    return b;
  }

  @Override
  public int read(@Nonnull final byte[] bytes, final int off, final int len) throws IOException {
    waitForHashes();

    int numRead = in.read(bytes, off, len);
    if (numRead != -1) {
      // Create a copy of the read bytes in case the provided buffer is externally modified
      byte[] copy = new byte[numRead];
      System.arraycopy(bytes, off, copy, 0, numRead);

      submitHashing(hasher -> hasher.putBytes(copy, 0, numRead));
      count.addAndGet(numRead);
    }
    return numRead;
  }

  /**
   * Optimized bulk read method that uses a buffer sized appropriately for Virtual Threads.
   * This method reduces context switching and improves performance in Virtual Thread environments.
   *
   * @param buffer the buffer to read into
   * @return the number of bytes read, or -1 if the end of the stream is reached
   * @throws IOException if an I/O error occurs
   */
  public int readOptimized(final byte[] buffer) throws IOException {
    waitForHashes();

    int numRead = in.read(buffer, 0, buffer.length);
    if (numRead != -1) {
      // Create a copy of the read bytes to ensure thread safety
      byte[] copy = new byte[numRead];
      System.arraycopy(buffer, 0, copy, 0, numRead);

      submitHashing(hasher -> hasher.putBytes(copy, 0, numRead));
      count.addAndGet(numRead);
    }
    return numRead;
  }

  /**
   * Creates and returns a new buffer with the optimal size for Virtual Thread operations.
   *
   * @return a new byte array with the optimal buffer size
   */
  public byte[] createOptimalBuffer() {
    return new byte[bufferSize];
  }

  @Override
  public boolean markSupported() {
    return false;
  }

  @Override
  public void mark(final int readlimit) {
    // no-op
  }

  @Override
  public void reset() throws IOException {
    throw new IOException("reset not supported");
  }

  /**
   * Gets the {@link HashCode}s based on the data read from this stream.
   * Waits for any pending hashing operations to complete before returning results.
   */
  public Map<HashAlgorithm, HashCode> hashes() {
    try {
      waitForHashes();
    }
    catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    Map<HashAlgorithm, HashCode> hashes = new HashMap<>(hashers.size());
    for (Entry<HashAlgorithm, Hasher> entry : hashers.entrySet()) {
      hashes.put(entry.getKey(), entry.getValue().hash());
    }
    return hashes;
  }

  /**
   * Gets the number of bytes read from this stream.
   */
  public long count() {
    return count.get();
  }

  /**
   * Submits a hashing operation to be executed asynchronously using Virtual Threads.
   * This prevents blocking the current thread during CPU-intensive hashing operations.
   *
   * @param operation the hashing operation to perform
   */
  protected void submitHashing(final Consumer<Hasher> operation) {
    // For small operations, perform synchronously to avoid overhead
    if (hashers.size() <= 2) {
      hashers.values().forEach(operation::accept);
      return;
    }

    // For larger sets of hashers, process asynchronously using Virtual Threads
    hashers.forEach((algorithm, hasher) -> {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        operation.accept(hasher);
      }, HASHING_EXECUTOR);
      pendingHashes.put(algorithm, future);
    });
  }

  /**
   * Waits for all pending hashing operations to complete.
   * This ensures data consistency when retrieving hash results.
   *
   * @throws IOException if an error occurs while waiting for hashing operations
   */
  protected void waitForHashes() throws IOException {
    if (pendingHashes.isEmpty()) {
      return;
    }

    try {
      // Create a combined future that completes when all hashing operations complete
      CompletableFuture<Void> allDone = CompletableFuture.allOf(
          pendingHashes.values().toArray(new CompletableFuture[0]));
      
      // Wait for all hashing operations to complete
      allDone.join();
      
      // Clear the pending hashes map for the next batch
      pendingHashes.clear();
    } catch (Exception e) {
      throw new IOException("Error waiting for hashing operations to complete", e);
    }
  }
}
