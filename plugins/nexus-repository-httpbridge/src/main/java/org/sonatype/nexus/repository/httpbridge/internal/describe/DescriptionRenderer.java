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
package org.sonatype.nexus.repository.httpbridge.internal.describe;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Renders {@link Description} into HTML or JSON.
 * <p>
 * Java 21 compatible interface with support for both synchronous and asynchronous rendering.
 * Implementations can leverage Virtual Threads for improved performance with I/O-bound operations.
 *
 * @since 3.0
 */
public interface DescriptionRenderer
{
  /**
   * Renders the description as HTML.
   *
   * @param description the description to render
   * @return HTML string representation
   */
  String renderHtml(Description description);

  /**
   * Renders the description as JSON.
   *
   * @param description the description to render
   * @return JSON string representation
   */
  String renderJson(Description description);
  
  /**
   * Asynchronously renders the description as HTML using Virtual Threads.
   * <p>
   * This method leverages Java 21 Virtual Threads for improved performance
   * with I/O-bound operations like template rendering.
   *
   * @param description the description to render
   * @param executor the executor to use for asynchronous processing
   * @return a CompletableFuture that will complete with the HTML string representation
   * @since 3.41
   */
  default CompletableFuture<String> renderHtmlAsync(Description description, Executor executor) {
    return CompletableFuture.supplyAsync(() -> renderHtml(description), executor);
  }
  
  /**
   * Asynchronously renders the description as JSON using Virtual Threads.
   * <p>
   * This method leverages Java 21 Virtual Threads for improved performance
   * with I/O-bound operations like JSON serialization.
   *
   * @param description the description to render
   * @param executor the executor to use for asynchronous processing
   * @return a CompletableFuture that will complete with the JSON string representation
   * @since 3.41
   */
  default CompletableFuture<String> renderJsonAsync(Description description, Executor executor) {
    return CompletableFuture.supplyAsync(() -> renderJson(description), executor);
  }
}
