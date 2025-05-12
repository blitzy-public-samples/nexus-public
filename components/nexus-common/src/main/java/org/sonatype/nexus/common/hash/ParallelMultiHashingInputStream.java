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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import com.google.common.hash.Hasher;

/**
 * An {@link MultiHashingInputStream} which uses Java 21 Virtual Threads to asynchronously compute hashes
 * with improved scalability and reduced resource consumption.
 * <p>
 * Virtual Threads are lightweight threads that are managed by the JVM rather than the OS, making them
 * ideal for I/O-bound operations like hashing. This implementation creates a dedicated Virtual Thread
 * for each hash algorithm, allowing them to run concurrently with minimal resource overhead.
 *
 * @see MultiHashingInputStream
 * @since 3.60
 */
public class ParallelMultiHashingInputStream
    extends MultiHashingInputStream
{
  private List<Future<?>> hashingFutures = Collections.emptyList();
  private ExecutorService executor;
  private boolean completed = false;

  /**
   * Creates a new parallel hashing input stream using Virtual Threads.
   *
   * @param algorithms the hash algorithms to use
   * @param inputStream the input stream to hash
   */
  public ParallelMultiHashingInputStream(final Iterable<HashAlgorithm> algorithms, final InputStream inputStream) {
    super(algorithms, inputStream);
    // Create a virtual thread per task executor for optimal I/O-bound hash computation
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Override
  protected void submitHashing(final Consumer<Hasher> runnable) {
    // Submit each hashing task to be executed by a dedicated virtual thread
    List<Future<?>> futures = new ArrayList<>(hashers.size());
    for (Hasher hasher : hashers.values()) {
      futures.add(executor.submit(() -> runnable.accept(hasher)));
    }
    hashingFutures = futures;
  }

  @Override
  protected void waitForHashes() throws IOException {
    // Wait for all hashing tasks to complete
    for (Future<?> future : hashingFutures) {
      if (!future.isDone() && !future.isCancelled()) {
        try {
          future.get();
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("Hashing interrupted", e);
        }
        catch (ExecutionException e) {
          throw new IOException("Error during parallel hashing", e);
        }
      }
    }
    
    // Shutdown the executor if we're done with all hashing tasks
    // This allows the virtual threads to be garbage collected
    if (!executor.isShutdown()) {
      executor.shutdown();
      // Decrement the active operations counter when we're done
      if (!completed) {
        MultiHashingInputStreamFactory.decrementActiveOperations();
        completed = true;
      }
    }
  }
  
  @Override
  public void close() throws IOException {
    try {
      super.close();
    } finally {
      // Ensure we decrement the counter even if an exception occurs during close
      if (!completed) {
        MultiHashingInputStreamFactory.decrementActiveOperations();
        completed = true;
      }
      
      // Ensure the executor is shutdown
      if (!executor.isShutdown()) {
        executor.shutdown();
      }
    }
  }
}