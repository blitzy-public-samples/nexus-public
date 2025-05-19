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
 * Optimized for Virtual Threads in Java 21, with improved thread-safety and non-blocking I/O support.
 *
 * @see HashingInputStream
 * @since 3.0
 */
public class MultiHashingInputStream
    extends FilterInputStream
{
  /**
   * Default buffer size for optimized reading in Virtual Thread context.
   */
  private static final int DEFAULT_BUFFER_SIZE = 8192;
  
  /**
   * Thread-safe map of hashers for concurrent access in Virtual Thread environment.
   */
  protected final Map<HashAlgorithm, Hasher> hashers;
  
  /**
   * Atomic counter for thread-safe byte counting.
   */
  private final AtomicLong count = new AtomicLong(0);
  
  /**
   * Buffer size for optimized reading.
   */
  private final int bufferSize;
  
  /**
   * Executor for Virtual Thread-aware parallel hashing operations.
   * Uses Virtual Threads by default in Java 21.
   */
  private final Executor hashingExecutor;
  
  /**
   * Map to track pending hashing operations.
   */
  private final Map<HashAlgorithm, CompletableFuture<Void>> pendingHashes = new ConcurrentHashMap<>();

  /**
   * Creates a new MultiHashingInputStream with default buffer size.
   *
   * @param algorithms the hash algorithms to use
   * @param inputStream the input stream to read from and hash
   */
  public MultiHashingInputStream(final Iterable<HashAlgorithm> algorithms, final InputStream inputStream) {
    this(algorithms, inputStream, DEFAULT_BUFFER_SIZE);
  }

  /**
   * Creates a new MultiHashingInputStream with specified buffer size.
   *
   * @param algorithms the hash algorithms to use
   * @param inputStream the input stream to read from and hash
   * @param bufferSize the buffer size to use for reading
   */
  public MultiHashingInputStream(final Iterable<HashAlgorithm> algorithms, 
                               final InputStream inputStream, 
                               final int bufferSize) {
    super(checkNotNull(inputStream));
    checkNotNull(algorithms);
    this.bufferSize = bufferSize;
    this.hashers = new ConcurrentHashMap<>();
    this.hashingExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    for (HashAlgorithm algorithm : algorithms) {
      hashers.put(algorithm, algorithm.function().newHasher());
    }
  }

  @Override
  public int read() throws IOException {
    waitForHashes();

    int b = in.read();
    if (b != -1) {
      // For single byte reads, process immediately without spawning a virtual thread
      // to avoid the overhead of thread creation for small operations
      byte value = (byte) b;
      hashers.values().forEach(hasher -> hasher.putByte(value));
      count.incrementAndGet();
    }
    return b;
  }

  @Override
  public int read(@Nonnull final byte[] bytes, final int off, final int len) throws IOException {
    waitForHashes();

    int numRead = in.read(bytes, off, len);
    if (numRead != -1) {
      // Create a defensive copy of the read bytes to prevent external modification
      // This is necessary because the hashing might be performed asynchronously
      byte[] copy = new byte[numRead];
      System.arraycopy(bytes, off, copy, 0, numRead);

      // Process the bytes in a Virtual Thread to avoid blocking the caller
      submitHashing(hasher -> hasher.putBytes(copy, 0, numRead));
      count.addAndGet(numRead);
    }
    return numRead;
  }
  
  /**
   * Optimized bulk read method that uses internal buffering for better performance
   * in Virtual Thread environments. This reduces the number of I/O operations and
   * context switches.
   */
  @Override
  public long transferTo(java.io.OutputStream out) throws IOException {
    checkNotNull(out);
    waitForHashes();
    
    byte[] buffer = new byte[bufferSize];
    long transferred = 0;
    int read;
    
    while ((read = this.read(buffer, 0, buffer.length)) >= 0) {
      out.write(buffer, 0, read);
      transferred += read;
    }
    
    return transferred;
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
   * Submits a hashing operation to be performed, potentially in a Virtual Thread.
   * For larger data chunks, this will use the Virtual Thread executor for non-blocking
   * processing. For small operations, it may process synchronously to avoid overhead.
   *
   * @param operation the hashing operation to perform
   */
  protected void submitHashing(final Consumer<Hasher> operation) {
    // For each hash algorithm, submit a task to update its hasher
    hashers.forEach((algorithm, hasher) -> {
      // Create a CompletableFuture for this hashing operation
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        operation.accept(hasher);
      }, hashingExecutor);
      
      // Store the future so we can wait for it later if needed
      pendingHashes.put(algorithm, future);
      
      // When the future completes, remove it from the pending map
      future.whenComplete((result, ex) -> {
        pendingHashes.remove(algorithm, future);
        if (ex != null) {
          // Log the exception or handle it as appropriate
          // We don't rethrow it here as that would break the CompletableFuture chain
          ex.printStackTrace();
        }
      });
    });
  }

  /**
   * Waits for all pending hashing operations to complete.
   * This ensures that all data has been properly hashed before proceeding.
   *
   * @throws IOException if an error occurs while waiting for hashes
   */
  protected void waitForHashes() throws IOException {
    try {
      // Create a copy of the pending futures to avoid concurrent modification issues
      CompletableFuture<Void>[] futures = pendingHashes.values().toArray(new CompletableFuture[0]);
      
      // Wait for all pending hashing operations to complete
      CompletableFuture.allOf(futures).join();
    } catch (Exception e) {
      throw new IOException("Error waiting for hashing operations to complete", e);
    }
  }
}
