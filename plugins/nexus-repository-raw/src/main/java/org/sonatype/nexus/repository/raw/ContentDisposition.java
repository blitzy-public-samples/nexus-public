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
package org.sonatype.nexus.repository.raw;

import java.util.Arrays;

/**
 * Enum representing HTTP Content-Disposition header values for Raw repositories.
 * 
 * @since 3.25
 */
public enum ContentDisposition
{
  INLINE("inline"),
  ATTACHMENT("attachment");

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
   * Returns a string representation of this content disposition.
   * Uses Java 21 string templates for improved readability.
   *
   * @return a string representation
   */
  @Override
  public String toString() {
    return STR."ContentDisposition[\{value}]";
  }

  /**
   * Converts a string value to the corresponding ContentDisposition enum.
   * Uses Java 21 pattern matching for switch to simplify the implementation.
   *
   * @param value the string value to convert
   * @return the corresponding ContentDisposition enum
   * @throws IllegalArgumentException if the value is not a valid content disposition
   */
  public static ContentDisposition fromString(String value) {
    return switch (value) {
      case String s when s.equalsIgnoreCase(INLINE.value) -> INLINE;
      case String s when s.equalsIgnoreCase(ATTACHMENT.value) -> ATTACHMENT;
      default -> throw new IllegalArgumentException(STR."Invalid content disposition: \{value}");
    };
  }

  /**
   * Checks if a string value is a valid content disposition.
   *
   * @param value the string value to check
   * @return true if the value is a valid content disposition, false otherwise
   */
  public static boolean isValid(String value) {
    return Arrays.stream(values())
        .anyMatch(disposition -> disposition.value.equalsIgnoreCase(value));
  }
}