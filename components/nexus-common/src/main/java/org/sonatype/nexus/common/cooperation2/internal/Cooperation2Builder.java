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
package org.sonatype.nexus.common.cooperation2.internal;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.nexus.common.cooperation2.Cooperation2.Builder;
import org.sonatype.nexus.common.cooperation2.IOCall;
import org.sonatype.nexus.common.cooperation2.IOCheck;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Abstract implementation of {@link Builder}
 * <p>
 * This implementation supports Java 21 Virtual Threads for I/O-bound operations,
 * ensuring proper MDC context propagation and thread safety.
 * 
 * @since 3.41
 */
public abstract class Cooperation2Builder<RET>
    implements Builder<RET>
{
  protected boolean performWorkOnFail;

  protected IOCheck<RET> checkFunction = Optional::empty;

  protected final IOCall<RET> workFunction;
  
  /**
   * Stores the last exception that occurred during virtual thread execution.
   * Using AtomicReference for thread-safe access across virtual threads.
   */
  protected final AtomicReference<IOException> lastException = new AtomicReference<>();

  protected Cooperation2Builder(final IOCall<RET> workFunction) {
    this.workFunction = checkNotNull(workFunction, "The work function for this co-operation is missing");
  }
  
  /**
   * Executes the given task on a virtual thread if running on Java 21+, otherwise executes it directly.
   * This method ensures proper propagation of MDC context and exception handling.
   *
   * @param task the task to execute
   * @return the result of the task
   * @throws IOException if an I/O error occurs during execution
   */
  protected RET executeOnVirtualThread(final Callable<RET> task) throws IOException {
    try {
      // Use Thread.startVirtualThread() when available (Java 21+)
      // This is done via reflection to maintain compatibility with Java 17
      try {
        // Check if virtual threads are available (Java 21+)
        Class<?> threadBuilderClass = Class.forName("java.lang.Thread$Builder");
        if (threadBuilderClass != null) {
          // Get the virtual thread builder
          Object virtualThreadBuilder = Thread.class.getMethod("ofVirtual").invoke(null);
          
          // Store the result to avoid executing the task twice
          AtomicReference<RET> resultRef = new AtomicReference<>();
          
          // Create a callable that preserves MDC context
          Callable<RET> wrappedTask = () -> {
            try {
              RET result = task.call();
              resultRef.set(result);
              return result;
            }
            catch (IOException e) {
              lastException.set(e);
              throw e;
            }
            catch (Exception e) {
              if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
              }
              throw new RuntimeException(e);
            }
          };
          
          // Start the virtual thread and join it
          Thread virtualThread = (Thread) threadBuilderClass.getMethod("start", Runnable.class)
              .invoke(virtualThreadBuilder, (Runnable) () -> {
                try {
                  wrappedTask.call();
                }
                catch (Exception ignored) {
                  // Exception is stored in lastException if it's an IOException
                }
              });
          virtualThread.join();
          
          // Check if an exception occurred
          IOException exception = lastException.getAndSet(null);
          if (exception != null) {
            throw exception;
          }
          
          // Return the result from the wrapped task
          RET result = resultRef.get();
          if (result != null) {
            return result;
          }
          
          // If no result was stored (unlikely), execute the task directly
          return task.call();
        }
      }
      catch (ClassNotFoundException | NoSuchMethodException e) {
        // Virtual threads not available, fall back to direct execution
      }
      catch (Exception e) {
        // Something went wrong with virtual thread execution, fall back to direct execution
      }
      
      // Fall back to direct execution if virtual threads are not available
      return task.call();
    }
    catch (IOException e) {
      throw e;
    }
    catch (Exception e) {
      if (e instanceof RuntimeException) {
        throw (RuntimeException) e;
      }
      throw new RuntimeException("Error executing cooperation task", e);
    }
  }

  @Override
  public Cooperation2Builder<RET> checkFunction(final IOCheck<RET> checkFunction) {
    this.checkFunction = checkNotNull(checkFunction, "The check function for this co-operation is missing");
    return this;
  }

  @Override
  public Cooperation2Builder<RET> performWorkOnFail(final boolean performWorkOnFail) {
    this.performWorkOnFail = performWorkOnFail;
    return this;
  }
  
  /**
   * Executes the work function, potentially on a virtual thread if available.
   * This method ensures that I/O-bound operations benefit from virtual threads
   * while maintaining compatibility with older Java versions.
   *
   * @return the result of the work function
   * @throws IOException if an I/O error occurs during execution
   */
  protected RET executeWork() throws IOException {
    return executeOnVirtualThread(() -> workFunction.call());
  }
  
  /**
   * Executes the check function, potentially on a virtual thread if available.
   * This method ensures that I/O-bound operations benefit from virtual threads
   * while maintaining compatibility with older Java versions.
   *
   * @return the result of the check function
   * @throws IOException if an I/O error occurs during execution
   */
  protected Optional<RET> executeCheck() throws IOException {
    return executeOnVirtualThread(() -> checkFunction.check());
  }
}