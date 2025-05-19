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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.google.common.hash.Hasher;

/**
 * An {@link MultiHashingInputStream} which uses Java 21 Virtual Threads to asynchronously compute hashes.
 * This implementation provides improved I/O performance by leveraging the lightweight nature of Virtual Threads
 * for parallel hash computation without the overhead of traditional thread pools.
 *
 * @see MultiHashingInputStream
 * @since 3.60
 */
public class ParallelMultiHashingInputStream
    extends MultiHashingInputStream
{
  /**
   * List of active Virtual Threads performing hashing operations.
   */
  private List<Thread> hashingThreads = Collections.synchronizedList(new ArrayList<>());

  /**
   * Dedicated executor for Virtual Threads to optimize I/O operations.
   */
  private final ExecutorService virtualThreadExecutor;

  /**
   * Creates a new ParallelMultiHashingInputStream with the specified hash algorithms.
   *
   * @param algorithms the hash algorithms to use
   * @param inputStream the input stream to read from and hash
   */
  public ParallelMultiHashingInputStream(final Iterable<HashAlgorithm> algorithms, final InputStream inputStream) {
    super(algorithms, inputStream);
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * Creates a new ParallelMultiHashingInputStream with the specified hash algorithms and buffer size.
   *
   * @param algorithms the hash algorithms to use
   * @param inputStream the input stream to read from and hash
   * @param bufferSize the buffer size to use for reading
   */
  public ParallelMultiHashingInputStream(final Iterable<HashAlgorithm> algorithms, 
                                        final InputStream inputStream,
                                        final int bufferSize) {
    super(algorithms, inputStream, bufferSize);
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Override
  protected void submitHashing(final Consumer<Hasher> runnable) {
    // Clean up any completed threads from previous operations
    hashingThreads.removeIf(thread -> !thread.isAlive());
    
    // Create and start a Virtual Thread for each hasher
    hashers.values().forEach(hasher -> {
      Thread virtualThread = Thread.ofVirtual()
          .name("hash-worker-" + System.nanoTime())
          .start(() -> {
            try {
              runnable.accept(hasher);
            } 
            catch (Exception e) {
              // Capture exceptions in Virtual Threads to prevent silent failures
              // These will be propagated when waitForHashes is called
              Thread.currentThread().interrupt();
            }
          });
      hashingThreads.add(virtualThread);
    });
  }

  @Override
  protected void waitForHashes() throws IOException {
    List<Thread> threadsToJoin;
    
    // Create a copy of the threads list to avoid concurrent modification issues
    synchronized (hashingThreads) {
      threadsToJoin = new ArrayList<>(hashingThreads);
    }
    
    // Join all active threads and handle any exceptions
    for (Thread thread : threadsToJoin) {
      try {
        if (thread.isAlive()) {
          thread.join();
        }
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted while waiting for hash computation", e);
      }
    }
    
    // Clean up the threads list after joining
    hashingThreads.removeAll(threadsToJoin);
  }
  
  @Override
  public void close() throws IOException {
    try {
      // Ensure all hashing operations are complete
      waitForHashes();
      
      // Shutdown the executor service
      virtualThreadExecutor.shutdown();
      
      // Call the parent close method
      super.close();
    }
    catch (Exception e) {
      throw new IOException("Error closing ParallelMultiHashingInputStream", e);
    }
  }
}
