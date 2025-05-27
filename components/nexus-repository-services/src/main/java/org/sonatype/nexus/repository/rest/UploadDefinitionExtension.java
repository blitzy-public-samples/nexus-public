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

import org.sonatype.nexus.repository.upload.UploadFieldDefinition;

/**
 * Extension point interface which provides a mechanism for contributing an {@link UploadFieldDefinition}
 *
 * @since 3.10
 */
public interface UploadDefinitionExtension
{
  /**
   * Contribute an {@link UploadFieldDefinition} to the upload system.
   * 
   * @return the field definition to contribute
   */
  UploadFieldDefinition contribute();
  
  /**
   * Asynchronously contribute an {@link UploadFieldDefinition} to the upload system using Java 21 Virtual Threads.
   * This method is designed to be used with I/O-bound operations that benefit from the lightweight concurrency
   * model provided by Virtual Threads.
   * <p>
   * Implementation best practices:
   * <ul>
   *   <li>Use Virtual Threads for I/O-bound operations by leveraging {@code Executors.newVirtualThreadPerTaskExecutor()}</li>
   *   <li>Implement error handling using String Templates for clearer error messages, e.g.,
   *       {@code STR."Error processing upload definition: \{errorDetails}"}</li>
   *   <li>Avoid blocking operations in the main thread path</li>
   *   <li>Use structured concurrency patterns when spawning multiple Virtual Threads</li>
   * </ul>
   * <p>
   * Example implementation:
   * <pre>{@code
   * @Override
   * public CompletableFuture<UploadFieldDefinition> contributeAsync() {
   *   return CompletableFuture.supplyAsync(() -> {
   *     try {
   *       // I/O-bound operations to determine field definition
   *       String fieldName = "example";
   *       String helpText = "Example field";
   *       return new UploadFieldDefinition(fieldName, helpText, false, Type.STRING);
   *     } catch (Exception e) {
   *       // Using Java 21 String Templates for clearer error messages
   *       String errorDetails = e.getMessage();
   *       log.error(STR."Failed to create upload field definition: \{errorDetails}");
   *       throw e;
   *     }
   *   }, Executors.newVirtualThreadPerTaskExecutor());
   * }
   * }</pre>
   * 
   * @return a CompletableFuture that will resolve to the field definition to contribute
   * @since 3.60 (Java 21 compatibility update)
   */
  default CompletableFuture<UploadFieldDefinition> contributeAsync() {
    // Default implementation calls the synchronous method
    return CompletableFuture.completedFuture(contribute());
  }
}