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

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;

/**
 * Base-URL manager.
 * <p>
 * Manages the base URL configuration for the application. Implementations should be thread-safe.
 * </p>
 *
 * @since 3.0
 */
public interface BaseUrlManager
{
  /**
   * Sets the base URL.
   *
   * @param url the base URL to set
   */
  void setUrl(String url);

  /**
   * Gets the configured base URL.
   *
   * @return the configured base URL
   */
  String getUrl();

  /**
   * Checks if the base URL is forced.
   *
   * @return true if the base URL is forced, false otherwise
   */
  boolean isForce();

  /**
   * Sets whether the base URL is forced.
   *
   * @param force true to force the base URL, false otherwise
   */
  void setForce(boolean force);

  /**
   * Detects base-URL from current environment.
   *
   * @return the detected base URL, or null if detection fails
   */
  @Nullable
  String detectUrl();
  
  /**
   * Detects base-URL from current environment, returning as an Optional.
   *
   * @return an Optional containing the detected base URL, or empty if detection fails
   * @since 3.60
   */
  default Optional<String> detectUrlOptional() {
    return Optional.ofNullable(detectUrl());
  }

  /**
   * Detects base-URL and registers with {@link BaseUrlHolder} if non-null.
   */
  void detectAndHoldUrl();
  
  /**
   * Asynchronously detects the base URL using a virtual thread if available.
   * <p>
   * This method is useful for non-blocking URL detection in I/O-bound scenarios.
   * </p>
   *
   * @return a CompletableFuture that will complete with the detected URL or null if detection fails
   * @since 3.60
   */
  default CompletableFuture<String> detectUrlAsync() {
    return CompletableFuture.supplyAsync(this::detectUrl, 
        Thread.ofVirtual().name("base-url-detector-").factory());
  }
}