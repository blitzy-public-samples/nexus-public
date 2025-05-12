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

import javax.annotation.Nullable;

/**
 * Base-URL manager.
 *
 * @since 3.0
 */
public interface BaseUrlManager
{
  /**
   * Sets the base URL.
   *
   * @param url The base URL to set
   */
  void setUrl(String url);

  /**
   * Gets the currently configured base URL.
   *
   * @return The current base URL
   */
  String getUrl();

  /**
   * Checks if the base URL is forced.
   *
   * @return true if the base URL is forced, false otherwise
   */
  boolean isForce();

  /**
   * Sets whether the base URL should be forced.
   *
   * @param force true to force the base URL, false otherwise
   */
  void setForce(boolean force);

  /**
   * Detect base-URL from current environment.
   *
   * @return The detected base URL, or null if unable to detect
   */
  @Nullable
  String detectUrl();

  /**
   * Detect base-URL and register with {@link BaseUrlHolder} if non-null.
   */
  void detectAndHoldUrl();
  
  /**
   * Detect base-URL from current environment, returning an Optional.
   * This is a modern alternative to {@link #detectUrl()} using Java's Optional type.
   *
   * @return Optional containing the detected base URL, or empty if unable to detect
   * @since 3.60
   */
  default Optional<String> detectUrlOptional() {
    return Optional.ofNullable(detectUrl());
  }
}