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
package org.sonatype.nexus.rest;

import jakarta.xml.bind.annotation.XmlRootElement;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Validation error exchange object.
 *
 * @since 3.0
 */
@XmlRootElement(name = "validationError")
public record ValidationErrorXO(
    @JsonProperty String id,
    @JsonProperty String message
) {
  /**
   * Denotes that validation does not applies to a specific value.
   */
  public static final String GENERIC = "*";

  /**
   * Creates a validation error with default constructor.
   * Initializes id to GENERIC.
   */
  public ValidationErrorXO {
    if (id == null) {
      id = GENERIC;
    }
  }

  /**
   * Creates a validation error that does not applies to a specific value.
   *
   * @param message validation description
   */
  public ValidationErrorXO(final String message) {
    this(GENERIC, message);
  }

  /**
   * Creates a validation error with the specified id and message.
   * Static factory method for fluent API usage.
   *
   * @param id identifier of value failing validation
   * @param message validation description
   * @return a new ValidationErrorXO instance
   */
  public static ValidationErrorXO withId(final String id, final String message) {
    return new ValidationErrorXO(id, message);
  }

  /**
   * Creates a validation error with the specified message and GENERIC id.
   * Static factory method for fluent API usage.
   *
   * @param message validation description
   * @return a new ValidationErrorXO instance
   */
  public static ValidationErrorXO withMessage(final String message) {
    return new ValidationErrorXO(GENERIC, message);
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "{" +
        "id='" + id + '\'' +
        ", message='" + message + '\'' +
        '}';
  }
}