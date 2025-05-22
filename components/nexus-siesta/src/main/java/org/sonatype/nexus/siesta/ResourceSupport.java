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
package org.sonatype.nexus.siesta;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.rest.Resource;

/**
 * Support for {@link Resource} implementations with enhanced Java 21 features.
 *
 * <p>This class provides utility methods for logging with String Templates and
 * executing operations using Virtual Threads for improved scalability and performance.</p>
 *
 * @since 3.0
 */
public class ResourceSupport
  extends ComponentSupport
  implements Resource
{
  /**
   * Executor service using Virtual Threads for non-blocking I/O operations.
   * This allows for high concurrency with minimal resource usage.
   */
  private static final ExecutorService VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
  
  /**
   * Logs a message at DEBUG level using Java 21 String Templates.
   * 
   * @param messageTemplate The message template with embedded expressions
   * @param args The arguments to be embedded in the template
   */
  protected void logDebugTemplate(String messageTemplate, Object... args) {
    if (isDebugEnabled()) {
      log.debug(STR."{messageTemplate} {formatArgs(args)}");
    }
  }

  /**
   * Logs a message at INFO level using Java 21 String Templates.
   * 
   * @param messageTemplate The message template with embedded expressions
   * @param args The arguments to be embedded in the template
   */
  protected void logInfoTemplate(String messageTemplate, Object... args) {
    if (isInfoEnabled()) {
      log.info(STR."{messageTemplate} {formatArgs(args)}");
    }
  }

  /**
   * Logs a message at WARN level using Java 21 String Templates.
   * 
   * @param messageTemplate The message template with embedded expressions
   * @param args The arguments to be embedded in the template
   */
  protected void logWarnTemplate(String messageTemplate, Object... args) {
    log.warn(STR."{messageTemplate} {formatArgs(args)}");
  }

  /**
   * Logs a message at ERROR level using Java 21 String Templates.
   * 
   * @param messageTemplate The message template with embedded expressions
   * @param args The arguments to be embedded in the template
   */
  protected void logErrorTemplate(String messageTemplate, Object... args) {
    log.error(STR."{messageTemplate} {formatArgs(args)}");
  }

  /**
   * Formats the arguments for logging.
   * 
   * @param args The arguments to format
   * @return A formatted string representation of the arguments
   */
  private String formatArgs(Object... args) {
    if (args == null || args.length == 0) {
      return "";
    }
    
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < args.length; i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(args[i]);
    }
    return sb.toString();
  }

  /**
   * Executes a task asynchronously using Virtual Threads.
   * This method is useful for I/O-bound operations that would otherwise block a platform thread.
   *
   * @param <T> The type of the result
   * @param task The task to execute
   * @return A Future representing the pending completion of the task
   */
  protected <T> Future<T> executeAsync(Supplier<T> task) {
    logDebugTemplate("Executing async task using Virtual Thread");
    return VIRTUAL_EXECUTOR.submit(task::get);
  }

  /**
   * Executes a runnable task asynchronously using Virtual Threads.
   * This method is useful for I/O-bound operations that would otherwise block a platform thread.
   *
   * @param task The task to execute
   * @return A Future representing the pending completion of the task
   */
  protected Future<?> executeAsync(Runnable task) {
    logDebugTemplate("Executing async runnable using Virtual Thread");
    return VIRTUAL_EXECUTOR.submit(task);
  }
  
  /**
   * Creates a Response with the given entity and status.
   * This method is compatible with RESTEasy 6.2.7.Final and Jakarta REST API.
   *
   * @param entity The entity to include in the response
   * @param status The HTTP status code
   * @return A Response object
   */
  protected Response createResponse(Object entity, Status status) {
    logDebugTemplate("Creating response with status: {}", status);
    return Response.status(status).entity(entity).build();
  }
  
  /**
   * Creates a successful (200 OK) Response with the given entity.
   * This method is compatible with RESTEasy 6.2.7.Final and Jakarta REST API.
   *
   * @param entity The entity to include in the response
   * @return A Response object with 200 OK status
   */
  protected Response createSuccessResponse(Object entity) {
    return createResponse(entity, Status.OK);
  }
}