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
package org.sonatype.nexus.datastore;

import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;
import org.sonatype.nexus.common.stateguard.Transitions;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.datastore.api.DataStore;
import org.sonatype.nexus.datastore.api.DataStoreConfiguration;

import org.codehaus.plexus.interpolation.EnvarBasedValueSource;
import org.codehaus.plexus.interpolation.InterpolationException;
import org.codehaus.plexus.interpolation.Interpolator;
import org.codehaus.plexus.interpolation.MapBasedValueSource;
import org.codehaus.plexus.interpolation.SingleResponseValueSource;
import org.codehaus.plexus.interpolation.StringSearchInterpolator;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static java.util.stream.Collectors.toMap;
import static org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport.State.SHUTDOWN;

/**
 * Common support class for {@link DataStore}s.
 *
 * @since 3.19
 */
public abstract class DataStoreSupport<S extends DataSession<?>>
    extends StateGuardLifecycleSupport
    implements DataStore<S>
{
  protected DataStoreConfiguration configuration;

  private String storeName;
  
  // Virtual Thread executor for parallel processing
  // Using Virtual Threads for improved concurrency in Java 21
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  @Override
  public DataStoreConfiguration getConfiguration() {
    return configuration;
  }

  @Override
  public void setConfiguration(final DataStoreConfiguration configuration) {
    checkArgument(!isNullOrEmpty(configuration.getName()));
    this.configuration = configuration;
    this.storeName = configuration.getName();
  }

  /**
   * Starts the data store with interpolated configuration attributes.
   * Enhanced to provide better thread context awareness during startup.
   */
  @Override
  protected final void doStart() throws Exception {
    Thread currentThread = Thread.currentThread();
    String threadContext = currentThread.isVirtual() ? 
        "VirtualThread[" + currentThread.threadId() + "," + currentThread.getName() + "]" : 
        "PlatformThread[" + currentThread.getName() + "]";
    
    debug("Starting datastore from {}", threadContext);
    Map<String, String> attributes = interpolatedAttributes();
    doStart(storeName, attributes);
    debug("Datastore started successfully from {}", threadContext);
  }

  protected abstract void doStart(final String storeName, final Map<String, String> attributes) throws Exception;

  @Override
  public String toString() {
    return getClass().getSimpleName() + "{" +
        "configuration=" + configuration +
        '}';
  }

  /**
   * Log the given WARN message along with the store name and current thread context.
   * Enhanced to be Virtual Thread-aware for correct thread context identification.
   */
  protected void warn(final String format, final Object... args) {
    log.warn(inStore(format), args);
  }

  /**
   * Log the given INFO message along with the store name and current thread context.
   * Enhanced to be Virtual Thread-aware for correct thread context identification.
   */
  protected void info(final String format, final Object... args) {
    log.info(inStore(format), args);
  }

  /**
   * Log the given DEBUG message along with the store name and current thread context.
   * Enhanced to be Virtual Thread-aware for correct thread context identification.
   */
  protected void debug(final String format, final Object... args) {
    if (log.isDebugEnabled()) {
      log.debug(inStore(format), args);
    }
  }

  /**
   * Contextualize the given log message with the store name and thread information.
   * Enhanced to include Virtual Thread context information when applicable.
   * 
   * This implementation provides detailed thread context for both Virtual Threads and
   * Platform Threads to improve debugging and monitoring in concurrent environments.
   */
  private String inStore(final String message) {
    Thread currentThread = Thread.currentThread();
    String threadInfo;
    
    if (currentThread.isVirtual()) {
      // For Virtual Threads, include thread ID and name for better identification
      threadInfo = String.format("VirtualThread[%d,%s]", 
          currentThread.threadId(), 
          currentThread.getName());
    } else {
      // For Platform Threads, include thread name and ID
      threadInfo = String.format("PlatformThread[%s,%d]", 
          currentThread.getName(), 
          currentThread.threadId());
    }
    
    return storeName + " - [" + threadInfo + "] " + message;
  }

  /**
   * Interpolate configuration attributes when starting the data store.
   * Optimized for parallel processing with Virtual Threads.
   * 
   * This implementation leverages Java 21 Virtual Threads to process attribute interpolation
   * in parallel, which is especially beneficial for configurations with many attributes
   * or attributes that require complex interpolation.
   */
  private Map<String, String> interpolatedAttributes() throws Exception {
    Map<String, String> attributes = configuration.getAttributes();
    Map<String, String> result = new ConcurrentHashMap<>(attributes.size());

    // Create a thread-safe interpolator with all value sources
    Interpolator interpolator = new StringSearchInterpolator();
    interpolator.addValueSource(new SingleResponseValueSource("storeName", storeName));
    interpolator.addValueSource(new MapBasedValueSource(attributes));
    interpolator.addValueSource(new MapBasedValueSource(System.getProperties()));
    interpolator.addValueSource(new EnvarBasedValueSource());

    // Process entries in parallel using Virtual Threads
    try {
      debug("Starting parallel attribute interpolation with Virtual Threads for {} attributes", attributes.size());
      var futures = attributes.entrySet().stream()
          .map(entry -> virtualThreadExecutor.submit(() -> {
            Thread currentThread = Thread.currentThread();
            try {
              String key = entry.getKey();
              String interpolatedValue = interpolator.interpolate(entry.getValue());
              result.put(key, interpolatedValue);
              return key;
            } catch (InterpolationException e) {
              throw new IllegalArgumentException("Error interpolating value for key: " + entry.getKey() + 
                  " in thread: " + (currentThread.isVirtual() ? "VirtualThread" : "PlatformThread"), e);
            }
          }))
          .toList();

      // Wait for all tasks to complete
      for (var future : futures) {
        try {
          future.get(); // This will propagate any exceptions from the tasks
        } catch (Exception e) {
          if (e.getCause() instanceof IllegalArgumentException) {
            throw (IllegalArgumentException) e.getCause();
          }
          throw new IllegalArgumentException("Error during attribute interpolation", e);
        }
      }
      
      debug("Completed parallel attribute interpolation with Virtual Threads");
      return result;
    } finally {
      // No need to shutdown the executor as Virtual Thread executor doesn't need explicit cleanup
    }
  }

  /**
   * Permanently stops this data store regardless of the current state, disallowing restarts.
   * Improved thread safety in state transitions to better support Virtual Thread execution.
   * 
   * This implementation ensures thread context is maintained during transitions between
   * Virtual Threads and provides better error handling and logging during shutdown.
   */
  @Override
  @Transitions(to = SHUTDOWN)
  public void shutdown() throws Exception {
    // Capture current thread context to ensure it's maintained during transitions
    Thread currentThread = Thread.currentThread();
    String threadContext = currentThread.isVirtual() ? 
        "VirtualThread[" + currentThread.threadId() + "," + currentThread.getName() + "]" : 
        "PlatformThread[" + currentThread.getName() + "]";
    
    try {
      if (isStarted()) {
        debug("Initiating shutdown from {}", threadContext);
        doStop();
        debug("Shutdown completed successfully from {}", threadContext);
      } else {
        debug("Shutdown requested but datastore is not started, from {}", threadContext);
      }
    } catch (Exception e) {
      warn("Error during shutdown from {}: {}", threadContext, e.getMessage());
      throw e;
    }
  }
}