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
package org.sonatype.nexus.repository.content.tasks.normalize;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.common.entity.Continuation;
import org.sonatype.nexus.common.entity.Continuations;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.logging.task.ProgressLogIntervalHelper;
import org.sonatype.nexus.logging.task.TaskLogType;
import org.sonatype.nexus.logging.task.TaskLogging;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.kv.GlobalKeyValueStore;
import org.sonatype.nexus.kv.NexusKeyValue;
import org.sonatype.nexus.kv.ValueType;
import org.sonatype.nexus.repository.content.store.ComponentData;
import org.sonatype.nexus.repository.content.store.ComponentStore;
import org.sonatype.nexus.repository.content.store.FormatStoreManager;
import org.sonatype.nexus.repository.search.normalize.VersionNormalizerService;
import org.sonatype.nexus.scheduling.Cancelable;
import org.sonatype.nexus.scheduling.TaskInterruptedException;
import org.sonatype.nexus.scheduling.TaskSupport;

import static java.lang.String.format;
import static org.sonatype.nexus.common.app.FeatureFlags.DISABLE_NORMALIZE_VERSION_TASK;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * System task to populate the {format}_component tables
 * 
 * This task normalizes component versions across all repository formats.
 * It uses Virtual Threads for parallel processing to improve performance.
 */
@Named
@TaskLogging(TaskLogType.TASK_LOG_ONLY_WITH_PROGRESS)
public class NormalizeComponentVersionTask
    extends TaskSupport
    implements Cancelable
{
  public static final String KEY_FORMAT = "%s.normalized.version.available";

  private final NormalizationPriorityService normalizationPriorityService;

  private final VersionNormalizerService versionNormalizerService;

  private final GlobalKeyValueStore globalKeyValueStore;

  private final EventManager eventManager;

  private ProgressLogIntervalHelper progressLogger;

  private final boolean disableTask;
  
  private ExecutorService virtualThreadExecutor;

  @Inject
  public NormalizeComponentVersionTask(
      final NormalizationPriorityService normalizationPriorityService,
      final VersionNormalizerService versionNormalizerService,
      final GlobalKeyValueStore globalKeyValueStore,
      final EventManager eventManager,
      @Named("${" + DISABLE_NORMALIZE_VERSION_TASK + ":-false}") final boolean disableTask)
  {
    this.normalizationPriorityService = normalizationPriorityService;
    this.versionNormalizerService = versionNormalizerService;
    this.globalKeyValueStore = globalKeyValueStore;
    this.eventManager = eventManager;
    this.disableTask = disableTask;
  }

  @Override
  public String getMessage() {
    return "populate normalized_version column on {format}_component tables using Virtual Threads";
  }
  
  @Override
  public boolean cancel() {
    boolean result = super.cancel();
    
    // Attempt to interrupt the virtual thread executor if it's running
    if (virtualThreadExecutor != null && !virtualThreadExecutor.isShutdown()) {
      log.info("Shutting down virtual thread executor due to task cancellation");
      virtualThreadExecutor.shutdownNow();
    }
    
    return result;
  }

  @Override
  protected Object execute() throws Exception
  {
    if (disableTask) {
      throw new TaskInterruptedException("The normalize version task was disabled", disableTask);
    }

    progressLogger = new ProgressLogIntervalHelper(log, 10);
    Map<Format, FormatStoreManager> formats = normalizationPriorityService.getPrioritizedFormats();

    int totalCount = formats.size();
    AtomicInteger skippedCount = new AtomicInteger();
    AtomicInteger processedCount = new AtomicInteger();
    
    // Create a virtual thread executor for parallel processing
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      this.virtualThreadExecutor = executor;
      
      // Process each format in parallel using virtual threads
      List<Runnable> tasks = new ArrayList<>();
      formats.forEach((format, manager) -> {
        tasks.add(() -> processFormat(totalCount, skippedCount, processedCount, format, manager));
      });
      
      // Submit all tasks to the executor
      tasks.forEach(executor::execute);
      
      // Wait for all tasks to complete or until the task is canceled
      executor.shutdown();
      if (!executor.awaitTermination(1, TimeUnit.HOURS)) {
        log.warn("Not all formats were processed within the time limit");
      }
    } catch (InterruptedException e) {
      log.warn("Task was interrupted during execution", e);
      Thread.currentThread().interrupt();
      throw new TaskInterruptedException("Task was interrupted", true);
    } finally {
      this.virtualThreadExecutor = null;
    }

    return null;
  }

  private void processFormat(
      final int totalCount,
      final AtomicInteger skippedCount,
      final AtomicInteger processedCount,
      final Format format,
      final FormatStoreManager manager)
  {
    log.info("normalizing {} components version", format.getValue());

    ComponentStore<?> componentStore = manager.componentStore(DEFAULT_DATASTORE_NAME);

    // Using pattern matching for switch to handle format-specific logic
    switch (format) {
      case Format f when !isFormatNormalized(f) -> {
        try {
          //initially set normalization state as false
          setNormalizationState(format, false);
          normalizeFormat(format, componentStore);
          //once normalization is done set state as true
          setNormalizationState(format, true);
          //publish an event to let interested know the format has been normalized
          eventManager.post(new FormatVersionNormalizedEvent(format.getValue()));

          int currentCount = processedCount.incrementAndGet();

          // Thread-safe logging of progress using AtomicInteger counters
          progressLogger.info(" task progress : {}% ({} of {} formats - skipped : {}) - elapsed : {}",
              Math.round(((float) currentCount / totalCount) * 100),
              currentCount, totalCount, skippedCount.get(), progressLogger.getElapsed());
        } catch (Exception e) {
          log.error("Error normalizing format {}: {}", format.getValue(), e.getMessage(), e);
        }
      }
      case Format f -> {
        log.debug("skipping {} format since is already normalized.", format.getValue());
        skippedCount.getAndIncrement();
      }
    }
  }

  /**
   * Gets a normalization state for the given format
   *
   * @param format the format to perform the query
   * @return {@link Boolean} flag indicating the normalization state
   */
  private Boolean isFormatNormalized(final Format format) {
    return globalKeyValueStore.getKey(getFormatKey(format))
        .map(NexusKeyValue::getAsBoolean)
        .orElseGet(() -> {
          log.debug("no previous normalization state for {} format", format);
          return false;
        });
  }

  /**
   * Sets a normalization state for the given format
   *
   * @param format the format to set the as part of the key
   * @param value  a {@link Boolean} flag indicating if the normalized version is available
   */
  private void setNormalizationState(final Format format, final boolean value) {
    NexusKeyValue kv = new NexusKeyValue();
    kv.setKey(getFormatKey(format));
    kv.setType(ValueType.BOOLEAN);
    kv.setValue(value);

    globalKeyValueStore.setKey(kv);
  }

  /**
   * Builds a string key with the given format
   *
   * @param format the format to set as part of the key
   * @return a {@link String} value with the key
   */
  private String getFormatKey(final Format format) {
    return format(KEY_FORMAT, format.getValue());
  }

  /**
   * Normalizes version of {format}_component's records using Virtual Threads for parallel processing
   *
   * @param format         the given format
   * @param componentStore the format component store
   */
  private void normalizeFormat(final Format format, final ComponentStore<?> componentStore) {
    int totalCount = componentStore.countUnnormalized();
    AtomicInteger processedCount = new AtomicInteger(0);

    log.info("found {} unnormalized records on {} components", totalCount, format.getValue());
    
    if (totalCount == 0) {
      log.info("No unnormalized components found for format {}", format.getValue());
      return;
    }
    
    // Create a list to hold all batches of components
    List<Continuation<ComponentData>> batches = new ArrayList<>();
    Continuation<ComponentData> page = componentStore.browseUnnormalized(Continuations.BROWSE_LIMIT, null);
    
    // Collect all batches first
    while (!page.isEmpty() && page.nextContinuationToken() != null) {
      batches.add(page);
      page = componentStore.browseUnnormalized(Continuations.BROWSE_LIMIT, page.nextContinuationToken());
      
      // Check if task has been canceled
      if (isCanceled()) {
        log.info("Task was canceled during batch collection for format {}", format.getValue());
        return;
      }
    }
    
    log.info("Collected {} batches for format {}", batches.size(), format.getValue());
    
    // Process all batches in parallel using Virtual Threads
    try (ExecutorService batchExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit each batch for processing
      batches.forEach(batch -> {
        batchExecutor.execute(() -> {
          try {
            // Process each component in the batch
            batch.forEach(component -> {
              try {
                String normalizedVersion = versionNormalizerService.getNormalizedVersionByFormat(
                    component.version(), format);
                component.setNormalizedVersion(normalizedVersion);
                componentStore.updateComponentNormalizedVersion(component);
              } catch (Exception e) {
                log.error("Error normalizing component {}: {}", component.version(), e.getMessage(), e);
              }
            });
            
            // Update progress counter atomically
            int currentProcessed = processedCount.addAndGet(batch.size());
            
            // Log progress periodically
            if (currentProcessed % (Continuations.BROWSE_LIMIT * 5) < Continuations.BROWSE_LIMIT) {
              log.info(" {} format progress : {}% ({} of {}) - elapsed : {}", format.getValue(),
                  Math.round(((float) currentProcessed / totalCount) * 100),
                  currentProcessed, totalCount, progressLogger.getElapsed());
            }
          } catch (Exception e) {
            log.error("Error processing batch for format {}: {}", format.getValue(), e.getMessage(), e);
          }
        });
      });
      
      // Wait for all batches to complete or until the task is canceled
      batchExecutor.shutdown();
      if (!batchExecutor.awaitTermination(1, TimeUnit.HOURS)) {
        log.warn("Not all batches were processed within the time limit for format {}", format.getValue());
      }
    } catch (InterruptedException e) {
      log.warn("Task was interrupted during batch processing for format {}", format.getValue(), e);
      Thread.currentThread().interrupt();
    }
    
    // Final progress log
    log.info(" {} format completed : {}% ({} of {}) - elapsed : {}", format.getValue(),
        Math.round(((float) processedCount.get() / totalCount) * 100),
        processedCount.get(), totalCount, progressLogger.getElapsed());
  }
}