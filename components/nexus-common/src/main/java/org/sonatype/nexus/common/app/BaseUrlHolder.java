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
package org.sonatype.nexus.common.app;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * Helper to hold the calculated base URL of the current request.
 *
 * <p>This implementation is compatible with both platform threads and virtual threads in Java 21.
 * While InheritableThreadLocal is used for thread-local storage, care is taken to properly clean up
 * values to avoid memory leaks, especially when used with virtual threads which may be created in
 * large numbers.</p>
 *
 * <p>Note: When Java's ScopedValue API becomes a standard feature (currently in preview in Java 21),
 * this implementation may be updated to use ScopedValue instead of InheritableThreadLocal for better
 * performance and memory efficiency with virtual threads.</p>
 *
 * @since 2.8
 */
public final class BaseUrlHolder
{
  private static final Logger log = LoggerFactory.getLogger(BaseUrlHolder.class);

  /**
   * Thread-local storage for the base URL.
   * 
   * <p>Note: While InheritableThreadLocal works with virtual threads, it's important to call
   * {@link #unset()} when done to avoid potential memory issues when creating many virtual threads.</p>
   */
  private static final InheritableThreadLocal<String> baseUrl = new InheritableThreadLocal<>();

  /**
   * Thread-local storage for the relative path.
   * 
   * <p>Note: While InheritableThreadLocal works with virtual threads, it's important to call
   * {@link #unset()} when done to avoid potential memory issues when creating many virtual threads.</p>
   */
  private static final InheritableThreadLocal<String> relativePath = new InheritableThreadLocal<>();

  private BaseUrlHolder() {
    // empty
  }

  /**
   * Set the current base URL, and the relative path.
   *
   * The value will be normalized to never end with "/".
   * 
   * <p>When using with virtual threads, ensure {@link #unset()} is called when the context is no longer needed
   * to prevent memory leaks, as virtual threads are not pooled and may be created in large numbers.</p>
   */
  public static void set(final String url, final String newRelativePath) {
    checkNotNull(url);
    checkNotNull(newRelativePath);

    String strippedUrl = stripSlash(url);
    String strippedRelativePath = stripSlash(newRelativePath);

    log.trace("Set: {}", strippedUrl);
    baseUrl.set(strippedUrl);

    log.trace("Set relativePath: {}", strippedRelativePath);
    relativePath.set(strippedRelativePath);
  }

  private static String stripSlash(final String url) {
    // strip off trailing "/", note this is done so that script/template can easily $baseUrl/foo
    if (url.endsWith("/")) {
      return url.substring(0, url.length() - 1);
    }
    return url;
  }

  /**
   * Returns the current base URL; never null.
   *
   * @throws IllegalStateException if the base URL is not set
   */
  public static String get() {
    String url = baseUrl.get();
    checkState(url != null, "Base URL not set");
    return url;
  }

  /**
   * Returns the current relative path; never null.
   *
   * @throws IllegalStateException if the relative path is not set
   */
  public static String getRelativePath() {
    String url = relativePath.get();
    checkState(url != null, "Relative path not set");
    return url;
  }

  /**
   * Removes the base URL and relative path from the current thread.
   * 
   * <p>This method should always be called when the thread-local values are no longer needed,
   * especially when using virtual threads, to prevent memory leaks.</p>
   */
  public static void unset() {
    log.trace("Unset");
    baseUrl.remove();
    relativePath.remove();
  }

  /**
   * Checks if the base URL is set for the current thread.
   *
   * @return true if the base URL is set, false otherwise
   */
  public static boolean isSet() {
    return baseUrl.get() != null;
  }

  /**
   * Executes the given operation with the specified base URL and relative path,
   * then unsets the values afterward.
   *
   * <p>This method ensures proper cleanup of thread-local values even if the operation throws an exception,
   * making it safe to use with virtual threads.</p>
   *
   * @param url the base URL to set
   * @param relative the relative path to set
   * @param operation the operation to execute
   * @param <R> the return type of the operation
   * @return the result of the operation
   */
  public static <R> R with(final String url, final String relative, final Supplier<R> operation) {
    set(url, relative);
    try {
      return operation.get();
    }
    finally {
      unset();
    }
  }

  /**
   * Executes the given callable with the specified base URL and relative path,
   * then unsets the values afterward.
   *
   * <p>This method ensures proper cleanup of thread-local values even if the callable throws an exception,
   * making it safe to use with virtual threads.</p>
   *
   * @param url the base URL to set
   * @param relative the relative path to set
   * @param operation the callable to execute
   * @param <R> the return type of the callable
   * @return the result of the callable
   * @throws Exception if the callable throws an exception
   */
  public static <R> R call(final String url, final String relative, final Callable<R> operation) throws Exception {
    set(url, relative);
    try {
      return operation.call();
    }
    finally {
      unset();
    }
  }
}