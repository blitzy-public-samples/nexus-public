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
package org.sonatype.nexus.repository.export;

import org.sonatype.nexus.repository.content.fluent.FluentAsset;

/**
 * Used to determine {@link FluentAsset} specifics during export.
 * <p>
 * Thread-safety considerations: Implementations of this interface must be thread-safe as they may be called
 * concurrently from multiple Virtual Threads during export operations. This includes ensuring that any shared state
 * is properly synchronized or using thread-safe data structures. Implementations should avoid using ThreadLocal
 * variables as they may not behave as expected in a Virtual Thread context.
 * <p>
 * Performance considerations: Methods in this interface should be non-blocking to optimize execution with Virtual Threads.
 * Avoid operations that could block the thread for extended periods, such as synchronous I/O or long-running computations.
 * If such operations are necessary, consider delegating them to a dedicated thread pool for CPU-intensive tasks.
 */
public interface ExportAssetFilter
{
  /**
   * Determines whether the export of the asset should be skipped for the provided {@link FluentAsset}.
   * <p>
   * This method should be implemented in a non-blocking manner to optimize execution with Virtual Threads.
   * <p>
   * Example implementation using Java 21 Pattern Matching for switch to filter assets by content type:
   * <pre>
   * {@code
   * public boolean shouldSkipAsset(FluentAsset asset) {
   *   return switch(asset.contentType()) {
   *     case String s when s.startsWith("text/html") -> true;
   *     case String s when s.startsWith("application/javascript") -> true;
   *     case String s when s.contains("/metadata/") -> true;
   *     case null -> false;
   *     default -> false;
   *   };
   * }
   * }
   * </pre>
   * <p>
   * When implementing this method, ensure that any thread context required by the implementation is properly preserved
   * when executed in a Virtual Thread context. Avoid using ThreadLocal variables directly, as they may not behave as
   * expected with Virtual Threads. Instead, consider using thread-safe data structures or context propagation mechanisms
   * that are compatible with Virtual Threads.
   *
   * @param asset the {@link FluentAsset} to use in the decision
   * @return true if the asset export should be skipped
   */
  boolean shouldSkipAsset(FluentAsset asset);

  /**
   * Determines whether the export of the attributes should be skipped for the provided {@link FluentAsset}.
   * <p>
   * This method should be implemented in a non-blocking manner to optimize execution with Virtual Threads.
   * <p>
   * Example implementation using Java 21 Pattern Matching for switch to filter assets by path pattern:
   * <pre>
   * {@code
   * public boolean shouldSkipAttributes(FluentAsset asset) {
   *   return switch(asset.path()) {
   *     case String p when p.endsWith(".index") -> true;
   *     case String p when p.contains("/internal/") -> true;
   *     case null -> false;
   *     default -> false;
   *   };
   * }
   * }
   * </pre>
   * <p>
   * When implementing this method, ensure that any thread context required by the implementation is properly preserved
   * when executed in a Virtual Thread context. Avoid using ThreadLocal variables directly, as they may not behave as
   * expected with Virtual Threads.
   *
   * @param asset the {@link FluentAsset} to use in the decision
   * @return true if the attribute export should be skipped
   */
  default boolean shouldSkipAttributes(FluentAsset asset) {
    return false;
  }

  /**
   * Provides a calculated export path for the provided {@link FluentAsset}.
   * <p>
   * This method should be implemented in a non-blocking manner to optimize execution with Virtual Threads.
   * <p>
   * Example implementation using Java 21 String Templates for more efficient path resolution:
   * <pre>
   * {@code
   * public String getAssetExportPath(FluentAsset asset) {
   *   String format = asset.kind();
   *   String path = asset.path();
   *   String repo = asset.repository().getName();
   *   
   *   return STR."\{repo}/\{format}/\{path}";
   * }
   * }
   * </pre>
   * <p>
   * When implementing this method, ensure that any thread context required by the implementation is properly preserved
   * when executed in a Virtual Thread context. Path resolution should be performed efficiently without blocking operations.
   *
   * @param asset the {@link FluentAsset} to calculate the export path for
   * @return the calculated export path
   */
  default String getAssetExportPath(FluentAsset asset) {
    return asset.path();
  }
}
