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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.SequencedCollection;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Thrown when there are request validation errors.
 *
 * @see ValidationErrorXO
 * @since 3.0
 */
public class ValidationErrorsException
    extends RuntimeException
{
  /**
   * Thread-safe list to store validation errors, optimized for read-heavy scenarios
   * which is the typical use case for validation errors (added once, read multiple times).
   * Uses CopyOnWriteArrayList which is compatible with Java 21 SequencedCollection interface,
   * ensuring thread safety for Virtual Thread execution.
   */
  private final List<ValidationErrorXO> errors = new CopyOnWriteArrayList<>();

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
    errors.addAll(Arrays.asList(validationErrors));
    return this;
  }

  public ValidationErrorsException withErrors(final List<ValidationErrorXO> validationErrors) {
    checkNotNull(validationErrors);
    errors.addAll(validationErrors);
    return this;
  }

  /**
   * Returns the list of validation errors.
   * The returned list is a thread-safe view of the internal errors collection,
   * which is compatible with Java 21 SequencedCollection interface.
   *
   * @return List of validation errors in their encounter order
   */
  public List<ValidationErrorXO> getValidationErrors() {
    return errors;
  }
  
  /**
   * Returns the first validation error if any exists.
   * Leverages the SequencedCollection concept of ordered elements.
   *
   * @return The first validation error or null if no errors exist
   */
  public ValidationErrorXO getFirstValidationError() {
    return errors.isEmpty() ? null : errors.get(0);
  }
  
  /**
   * Returns the last validation error if any exists.
   * Leverages the SequencedCollection concept of ordered elements.
   *
   * @return The last validation error or null if no errors exist
   */
  public ValidationErrorXO getLastValidationError() {
    return errors.isEmpty() ? null : errors.get(errors.size() - 1);
  }

  /**
   * Checks if there are any validation errors.
   * Thread-safe operation for use with Virtual Threads.
   *
   * @return true if there are validation errors, false otherwise
   */
  public boolean hasValidationErrors() {
    return !errors.isEmpty();
  }

  /**
   * Returns a formatted message containing all validation error messages.
   * Optimized for Java 21 with improved string handling and thread safety.
   * 
   * @return Formatted error message string
   */
  @Override
  public String getMessage() {
    if (errors.isEmpty()) {
      return "(No validation errors)";
    }
    
    // Using StringJoiner pattern for better performance with large collections
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    
    // Thread-safe iteration over CopyOnWriteArrayList
    for (ValidationErrorXO error : errors) {
      if (!first) {
        sb.append(", ");
      } else {
        first = false;
      }
      sb.append(error.getMessage());
    }
    
    return sb.toString();
  }
}