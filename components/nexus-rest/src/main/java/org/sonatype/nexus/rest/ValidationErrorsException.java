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

import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;

/**
 * Thrown when there are request validation errors.
 *
 * @see ValidationErrorXO
 * @since 3.0
 */
public class ValidationErrorsException
    extends RuntimeException
{
  private final List<ValidationErrorXO> errors = new ArrayList<ValidationErrorXO>();

  public ValidationErrorsException() {
    super();
  }

  public ValidationErrorsException(final String message) {
    errors.add(new ValidationErrorXO(message));
  }

  public ValidationErrorsException(final String message, final Throwable e) {
    super(e);
    errors.add(new ValidationErrorXO(message));
  }

  public ValidationErrorsException(final String id, final String message) {
    errors.add(new ValidationErrorXO(id, message));
  }

  public ValidationErrorsException withError(final String message) {
    errors.add(new ValidationErrorXO(message));
    return this;
  }

  public ValidationErrorsException withError(final String id, final String message) {
    errors.add(new ValidationErrorXO(id, message));
    return this;
  }

  public ValidationErrorsException withErrors(final ValidationErrorXO... validationErrors) {
    checkNotNull(validationErrors);
    errors.addAll(List.of(validationErrors));
    return this;
  }

  public ValidationErrorsException withErrors(final List<ValidationErrorXO> validationErrors) {
    checkNotNull(validationErrors);
    errors.addAll(validationErrors);
    return this;
  }

  public List<ValidationErrorXO> getValidationErrors() {
    return errors;
  }

  public boolean hasValidationErrors() {
    return !errors.isEmpty();
  }

  @Override
  public String getMessage() {
    if (errors.isEmpty()) {
      return "(No validation errors)";
    }
    
    // Use String Templates with String.join for efficient message formatting
    return STR."\{String.join(", ", errors.stream().map(ValidationErrorXO::getMessage).toList())}";
  }
}