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

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.rest.Resource;

/**
 * Support for {@link Resource} implementations.
 * 
 * Provides enhanced logging with Java 21 String Templates and leverages Java 21 features
 * for improved component lifecycle management. Compatible with RESTEasy 6.2.7.Final.
 *
 * @since 3.0
 */
public class ResourceSupport
  extends ComponentSupport
  implements Resource
{
  /**
   * Creates a virtual thread executor for handling I/O-bound operations.
   * 
   * @return A virtual thread per task executor service
   */
  protected ExecutorService createVirtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
  
  /**
   * Logs resource information with structured format using Java 21 String Templates.
   * 
   * @param resourceName The name of the resource
   * @param operation The operation being performed
   * @param details Additional operation details
   */
  protected void logResourceOperation(String resourceName, String operation, String details) {
    if (log.isDebugEnabled()) {
      log.debug(STR."Resource operation: [name=\{resourceName}] [operation=\{operation}] [details=\{details}]");
    }
  }
  
  /**
   * Logs a resource error with structured format using Java 21 String Templates.
   * 
   * @param resourceName The name of the resource
   * @param operation The operation being performed
   * @param error The error that occurred
   */
  protected void logResourceError(String resourceName, String operation, Throwable error) {
    log.error(STR."Resource error: [name=\{resourceName}] [operation=\{operation}]", error);
  }
  
  /**
   * Logs resource metrics with structured format using Java 21 String Templates.
   * 
   * @param resourceName The name of the resource
   * @param operation The operation being performed
   * @param durationMs The duration of the operation in milliseconds
   */
  protected void logResourceMetrics(String resourceName, String operation, long durationMs) {
    if (log.isTraceEnabled()) {
      log.trace(STR."Resource metrics: [name=\{resourceName}] [operation=\{operation}] [duration_ms=\{durationMs}]");
    }
  }
}