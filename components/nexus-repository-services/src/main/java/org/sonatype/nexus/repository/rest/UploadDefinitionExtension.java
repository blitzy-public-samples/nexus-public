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
package org.sonatype.nexus.repository.rest;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import org.sonatype.nexus.repository.upload.UploadFieldDefinition;

/**
 * Extension point interface which provides a mechanism for contributing an {@link UploadFieldDefinition}
 * to the upload system.
 * <p>
 * This interface has been updated for Java 21 compatibility with support for both synchronous and
 * asynchronous contribution methods. The asynchronous method leverages Java 21 Virtual Threads for
 * improved scalability and performance in I/O-bound operations.
 * <p>
 * <h3>Java 21 Compatibility Notes:</h3>
 * <ul>
 *   <li><b>Virtual Threads:</b> Implementations can leverage {@link #contributeAsync()} which uses
 *       Virtual Threads by default for non-blocking, high-throughput processing.</li>
 *   <li><b>String Templates:</b> Implementations should use Java 21 String Templates for error messages
 *       and logging to improve readability and reduce string concatenation errors. For example:
 *       {@code STR."Error processing upload definition: \{fieldName}"}</li>
 *   <li><b>Pattern Matching:</b> When handling different types of upload fields, consider using
 *       pattern matching for instanceof and switch expressions for cleaner code.</li>
 * </ul>
 * <p>
 * <h3>Best Practices:</h3>
 * <ul>
 *   <li>Prefer implementing {@link #contributeAsync()} for I/O-bound operations to leverage Virtual Threads</li>
 *   <li>Keep implementations lightweight to avoid thread pinning</li>
 *   <li>Use structured concurrency patterns when spawning additional asynchronous tasks</li>
 *   <li>Avoid blocking operations in the synchronous {@link #contribute()} method</li>
 *   <li>Use String Templates for error messages and logging for improved readability</li>
 * </ul>
 *
 * @since 3.10
 */
public interface UploadDefinitionExtension
{
  /**
   * Synchronously contribute an {@link UploadFieldDefinition}.
   * <p>
   * This is the traditional synchronous method that should be implemented for simple,
   * non-blocking operations. For I/O-bound or potentially blocking operations,
   * consider implementing {@link #contributeAsync()} instead to leverage Virtual Threads.
   *
   * @return the upload field definition to contribute
   */
  UploadFieldDefinition contribute();

  /**
   * Asynchronously contribute an {@link UploadFieldDefinition} using Java 21 Virtual Threads.
   * <p>
   * This method provides a default implementation that delegates to the synchronous {@link #contribute()}
   * method, but executes it on a Virtual Thread for improved scalability. Implementations can override
   * this method to provide custom asynchronous behavior.
   * <p>
   * Example implementation using String Templates for error handling:
   * <pre>{@code
   * @Override
   * public CompletableFuture<UploadFieldDefinition> contributeAsync() {
   *   return CompletableFuture.supplyAsync(() -> {
   *     try {
   *       String fieldName = "example";
   *       // Use String Template for better error messages
   *       log.debug(STR."Processing upload field: \{fieldName}");
   *       return new UploadFieldDefinition(fieldName, false, Type.STRING);
   *     } catch (Exception e) {
   *       // Use String Template for better error messages
   *       throw new RuntimeException(STR."Failed to create upload definition: \{e.getMessage()}", e);
   *     }
   *   }, Executors.newVirtualThreadPerTaskExecutor());
   * }
   * }</pre>
   *
   * @return a CompletableFuture that will resolve to the upload field definition
   * @since 3.60.0
   */
  default CompletableFuture<UploadFieldDefinition> contributeAsync() {
    return CompletableFuture.supplyAsync(this::contribute, Executors.newVirtualThreadPerTaskExecutor());
  }
}
