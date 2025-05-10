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
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.google.common.hash.Hasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An {@link MultiHashingInputStream} which uses Java 21 Virtual Threads to asynchronously compute hashes.
 * This implementation leverages Virtual Threads for I/O-bound operations, providing better scalability
 * and resource utilization compared to traditional thread pools.
 *
 * @see MultiHashingInputStream
 * @since 3.0
 */
public class ParallelMultiHashingInputStream
    extends MultiHashingInputStream
{
  private static final Logger log = LoggerFactory.getLogger(ParallelMultiHashingInputStream.class);
  
  private List<Thread> hashingThreads = Collections.emptyList();
  private boolean closed = false;

  /**
   * Creates a new parallel hashing input stream using Virtual Threads.
   *
   * @param algorithms the hash algorithms to use
   * @param inputStream the input stream to hash
   */
  public ParallelMultiHashingInputStream(final Iterable<HashAlgorithm> algorithms, final InputStream inputStream) {
    super(algorithms, inputStream);
    if (log.isTraceEnabled()) {
      log.trace("Created parallel hashing stream with Virtual Threads");
    }
  }

  @Override
  protected void submitHashing(final Consumer<Hasher> runnable) {
    if (log.isTraceEnabled()) {
      log.trace("Submitting hash computation to Virtual Threads");
    }
    
    hashingThreads = hashers.values()
        .stream()
        .map(hasher -> {
          Thread virtualThread = Thread.ofVirtual()
              .name("hash-computation-" + hasher.hashCode())
              .start(() -> {
                try {
                  runnable.accept(hasher);
                  if (log.isTraceEnabled()) {
                    log.trace("Virtual Thread hash computation completed for {}", hasher.hashCode());
                  }
                }
                catch (Exception e) {
                  log.error("Error in Virtual Thread hash computation", e);
                }
              });
          return virtualThread;
        })
        .collect(Collectors.toList());
  }

  @Override
  protected void waitForHashes() throws IOException {
    if (hashingThreads.isEmpty()) {
      return;
    }
    
    if (log.isTraceEnabled()) {
      log.trace("Waiting for {} Virtual Threads to complete hash computation", hashingThreads.size());
    }
    
    for (Thread thread : hashingThreads) {
      if (thread.isAlive()) {
        try {
          thread.join();
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted while waiting for hash computation to complete", e);
        }
      }
    }
  }
  
  @Override
  public void close() throws IOException {
    if (!closed) {
      try {
        // Ensure all hashing threads are completed before closing
        waitForHashes();
        super.close();
      }
      finally {
        // Decrement active operations counter
        MultiHashingInputStreamFactory.decrementActiveOperations();
        closed = true;
        
        if (log.isTraceEnabled()) {
          log.trace("Closed parallel hashing stream, active operations: {}", 
              MultiHashingInputStreamFactory.getActiveOperations());
        }
      }
    }
  }
}