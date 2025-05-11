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
package org.sonatype.nexus.repository.maven;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Enum representing the Content-Disposition header values for HTTP responses.
 * Used to control how content is presented in the browser (displayed inline or as an attachment).
 *
 * @since 3.33
 * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Content-Disposition">Content-Disposition HTTP Header</a>
 */
public enum ContentDisposition
{
  /**
   * Indicates the content should be displayed inline in the browser.
   */
  INLINE("inline"),
  
  /**
   * Indicates the content should be downloaded as an attachment.
   */
  ATTACHMENT("attachment");

  private static final Map<String, ContentDisposition> VALUE_MAP = Arrays.stream(values())
      .collect(Collectors.toUnmodifiableMap(ContentDisposition::getValue, Function.identity()));

  private final String value;

  ContentDisposition(final String value) {
    this.value = value;
  }

  /**
   * Returns the string value of this content disposition.
   *
   * @return the string value
   */
  public String getValue() {
    return value;
  }
  
  /**
   * Returns the string representation of this content disposition.
   *
   * @return the string representation
   */
  @Override
  public String toString() {
    return value;
  }
  
  /**
   * Converts a string value to the corresponding ContentDisposition enum.
   *
   * @param value the string value to convert
   * @return the corresponding ContentDisposition, or empty if not found
   */
  public static Optional<ContentDisposition> fromValue(final String value) {
    return Optional.ofNullable(value).map(VALUE_MAP::get);
  }
  
  /**
   * Converts a string value to the corresponding ContentDisposition enum.
   * If the value doesn't match any enum, returns the default value.
   *
   * @param value the string value to convert
   * @param defaultDisposition the default disposition to return if value is not found
   * @return the corresponding ContentDisposition, or the default if not found
   */
  public static ContentDisposition fromValue(final String value, final ContentDisposition defaultDisposition) {
    return fromValue(value).orElse(defaultDisposition);
  }
}