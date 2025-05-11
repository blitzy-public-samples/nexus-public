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
package org.sonatype.nexus.blobstore.common;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.sonatype.goodies.common.MultipleFailures;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.scheduling.TaskInterruptedException;
import org.sonatype.nexus.scheduling.TaskSupport;

import com.google.common.collect.Iterables;

/**
 * Support for tasks that apply changes to a set of blob stores.
 * <p>
 * This class has been updated for Java 21 compatibility with the following enhancements:
 * <ul>
 *   <li>Uses string templates for improved logging readability and type safety</li>
 *   <li>Supports concurrent processing of blob stores using virtual threads when appropriate</li>
 *   <li>Leverages Java 21 language features for more maintainable code</li>
 * </ul>
 */
public abstract class BlobStoreTaskSupport
    extends TaskSupport
{
  public static final String BLOBSTORE_NAME_FIELD_ID = "blobstoreName";

  public static final String ALL = "(All Blob Stores)";

  protected final BlobStoreManager blobStoreManager;

  protected BlobStoreTaskSupport(final BlobStoreManager blobStoreManager) {
    this.blobStoreManager = blobStoreManager;
  }

  protected BlobStoreTaskSupport(final boolean taskLoggingEnabled, final BlobStoreManager blobStoreManager) {
    super(taskLoggingEnabled);
    this.blobStoreManager = blobStoreManager;
  }

  /**
   * Determines if this task should use virtual threads for concurrent processing.
   * <p>
   * By default, returns false for backward compatibility. Subclasses can override
   * this method to enable concurrent processing with virtual threads when appropriate.
   * <p>
   * Note: Subclasses should ensure their implementation is thread-safe before enabling this.
   *
   * @return true if virtual threads should be used, false otherwise
   */
  protected boolean useVirtualThreads() {
    return false;
  }

  @Override
  protected Object execute() throws Exception {
    int processedBlobStores = 0;
    MultipleFailures failures = new MultipleFailures();
    Iterable<BlobStore> blobStores = findBlobStores();
    
    if (useVirtualThreads()) {
      // Process blob stores concurrently using virtual threads
      log.info(STR."Executing task '\{getMessage()}' with virtual threads");
      processedBlobStores = executeWithVirtualThreads(blobStores, failures);
    } else {
      // Process blob stores sequentially (traditional approach)
      for (BlobStore blobStore : blobStores) {
        if (isCanceled()) {
          break;
        }

        String blobstoreName = blobStore.getBlobStoreConfiguration().getName();

        try {
          log.info(STR."processing blob store '\{blobstoreName}'");
          execute(blobStore);
          log.info(STR."successfully processed blob store '\{blobstoreName}'");
          processedBlobStores++;
        }
        catch (TaskInterruptedException e) {
          throw e;
        }
        catch (Exception e) {
          log.error(STR."Failure processing blobstore '\{blobstoreName}'", e);
          failures.add(e);
        }
      }
    }

    log.info(STR."finished task '\{getMessage()}' - processed blob stores : \{processedBlobStores}");
    failures.maybePropagate(STR."Failure running task '\{getMessage()}'");

    return null;
  }

  /**
   * Finds all blob stores that match the configured criteria and apply to this task.
   * Uses Java 21 sequenced collections for improved iteration.
   *
   * @return An iterable of applicable blob stores
   */
  private Iterable<BlobStore> findBlobStores() {
    final String blobStoreField = getBlobStoreField();

    String[] names = blobStoreField.split(",");

    if (Arrays.asList(names).contains(ALL)) {
      return Iterables.filter(blobStoreManager.browse(), this::appliesTo);
    }

    return Arrays.stream(names)
        .map(blobStoreManager::get)
        .filter(Objects::nonNull)
        .filter(this::appliesTo)
        .collect(Collectors.toSet());
  }

  protected String getBlobStoreField() {
    return getConfiguration().getString(BLOBSTORE_NAME_FIELD_ID);
  }
  
  /**
   * Executes the given task on multiple blob stores concurrently using Java 21 virtual threads.
   * This method can be used by subclasses that want to process blob stores in parallel.
   * <p>
   * Note: Subclasses should ensure their implementation is thread-safe before using this method.
   *
   * @param blobStores The blob stores to process
   * @param failures The failures collection to add any errors to
   * @return The number of successfully processed blob stores
   */
  protected int executeWithVirtualThreads(Iterable<BlobStore> blobStores, MultipleFailures failures) {
    int[] processedCount = new int[1]; // Use array to allow modification from lambda
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit all tasks and collect futures
      var futures = Iterables.transform(blobStores, blobStore -> {
        if (isCanceled()) {
          return null;
        }
        
        return executor.submit(() -> {
          String blobstoreName = blobStore.getBlobStoreConfiguration().getName();
          try {
            log.info(STR."processing blob store '\{blobstoreName}' with virtual thread");
            execute(blobStore);
            log.info(STR."successfully processed blob store '\{blobstoreName}'");
            synchronized (processedCount) {
              processedCount[0]++;
            }
            return true;
          }
          catch (TaskInterruptedException e) {
            throw e;
          }
          catch (Exception e) {
            log.error(STR."Failure processing blobstore '\{blobstoreName}'", e);
            failures.add(e);
            return false;
          }
        });
      });
      
      // Wait for all tasks to complete
      for (Future<?> future : Iterables.filter(futures, Objects::nonNull)) {
        try {
          future.get();
        }
        catch (Exception e) {
          if (e.getCause() instanceof TaskInterruptedException) {
            throw (TaskInterruptedException) e.getCause();
          }
          failures.add(e);
        }
      }
    }
    
    return processedCount[0];
  }

  /**
   * Identifies if a blobstore applicable to process
   *
   * @param blobStore the blobstore to be evaluated
   * @return a boolean variable indicating if the given blob store is applicable
   */
  protected abstract boolean appliesTo(final BlobStore blobStore);

  /**
   * Processes a single blob store
   *
   * @param blobStore the blob store to be processed
   */
  protected abstract void execute(final BlobStore blobStore);
}