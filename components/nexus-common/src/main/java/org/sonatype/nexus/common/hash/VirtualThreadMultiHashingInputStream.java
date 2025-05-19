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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.google.common.hash.Hasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An {@link MultiHashingInputStream} which uses Java 21 Virtual Threads to asynchronously compute hashes.
 * This implementation replaces the previous {@code ParallelMultiHashingInputStream} which used ForkJoinPool.
 *
 * @see MultiHashingInputStream
 * @since 3.60
 */
public class VirtualThreadMultiHashingInputStream
    extends MultiHashingInputStream
{
  private static final Logger log = LoggerFactory.getLogger(VirtualThreadMultiHashingInputStream.class);
  
  private List<Future<?>> hashingFutures = Collections.emptyList();
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  public VirtualThreadMultiHashingInputStream(final Iterable<HashAlgorithm> algorithms, final InputStream inputStream) {
    super(algorithms, inputStream);
    if (log.isTraceEnabled()) {
      log.trace(STR."Created VirtualThreadMultiHashingInputStream with \{hashers.size()} hash algorithms");
    }
    // Increment the counter for active virtual thread hashing operations
    MultiHashingInputStreamFactory.incrementActiveThreads();
  }

  @Override
  protected void submitHashing(final Consumer<Hasher> runnable) {
    hashingFutures = hashers.values()
        .stream()
        .map(hasher -> executor.submit(() -> {
          // Using String Templates for improved logging
          if (log.isTraceEnabled()) {
            Thread currentThread = Thread.currentThread();
            boolean isVirtual = currentThread.isVirtual();
            String threadName = currentThread.getName();
            log.trace(STR."Executing hash operation on thread \{threadName} (virtual: \{isVirtual})");
          }
          runnable.accept(hasher);
        }))
        .collect(Collectors.toList());
    
    if (log.isDebugEnabled()) {
      int activeCount = MultiHashingInputStreamFactory.getActiveThreadCount();
      log.debug(STR."Submitted \{hashingFutures.size()} hashing tasks. Total active: \{activeCount}");
    }
  }

  @Override
  protected void waitForHashes() throws IOException {
    if (hashingFutures.isEmpty()) {
      return;
    }
    
    if (log.isTraceEnabled()) {
      log.trace(STR."Waiting for \{hashingFutures.size()} hashing tasks to complete");
    }
    
    long startTime = System.nanoTime();
    int completed = 0;
    
    for (Future<?> future : hashingFutures) {
      if (!future.isDone() && !future.isCancelled()) {
        try {
          future.get();
          completed++;
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          log.warn("Hashing operation interrupted", e);
        }
        catch (ExecutionException e) {
          log.error("Error during hash computation", e);
          throw new IOException(STR."Hash computation failed: \{e.getMessage()}", e);
        }
      } else {
        completed++;
      }
    }
    
    if (log.isDebugEnabled()) {
      long duration = System.nanoTime() - startTime;
      double durationMs = duration / 1_000_000.0;
      log.debug(STR."Completed \{completed}/\{hashingFutures.size()} hashing tasks in \{durationMs} ms");
    }
  }
  
  @Override
  public void close() throws IOException {
    try {
      // Make sure all hashing operations are complete
      waitForHashes();
      // Shutdown the executor service
      executor.shutdown();
    } 
    finally {
      // Decrement the counter for active virtual thread hashing operations
      MultiHashingInputStreamFactory.decrementActiveThreads();
      super.close();
    }
  }
}