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
package org.sonatype.nexus.extdirect.model;

import java.util.Collection;
import javax.validation.ConstraintViolationException;

/**
 * Ext.Direct response builder.
 * 
 * This class provides factory methods for creating various response objects
 * that are compatible with Java 21 features including pattern matching and virtual threads.
 *
 * @since 3.0
 */
public class Responses
{
  private Responses() {
    // empty
  }

  /**
   * Creates a success response with no data.
   *
   * @return a success response with null data
   */
  public static Response<Object> success() {
    return success(null);
  }

  /**
   * Creates a success response with the provided data.
   *
   * @param data the data to include in the response
   * @param <T> the type of data
   * @return a success response containing the data
   */
  public static <T> Response<T> success(T data) {
    return new Response<>(true, data);
  }

  /**
   * Creates an error response from an exception.
   * This method is compatible with Java 21 Virtual Threads and pattern matching.
   *
   * @param cause the exception that caused the error
   * @return an error response containing the exception message
   */
  public static ErrorResponse error(final Throwable cause) {
    return new ErrorResponse(cause);
  }

  /**
   * Creates an error response with a custom message.
   * This method is compatible with Java 21 Virtual Threads and pattern matching.
   *
   * @param message the error message
   * @return an error response containing the message
   */
  public static ErrorResponse error(final String message) {
    return new ErrorResponse(message);
  }

  /**
   * Creates a validation response from a constraint violation exception.
   * This method is compatible with Java 21 Virtual Threads and pattern matching.
   *
   * @param cause the constraint violation exception
   * @return a validation response containing the validation errors
   */
  public static ValidationResponse invalid(final ConstraintViolationException cause) {
    return new ValidationResponse(cause);
  }
  
  /**
   * Creates a paged response with the provided total and data collection.
   * This method is compatible with Java 21 Virtual Threads and pattern matching.
   *
   * @param total the total number of results
   * @param data the collection of data to include in the response
   * @param <T> the type of data in the collection
   * @return a paged response containing the data and total
   */
  public static <T> PagedResponse<T> paged(long total, Collection<T> data) {
    return new PagedResponse<>(total, data);
  }
  
  /**
   * Creates a limited paged response with the provided limit, total, and data collection.
   * This method is compatible with Java 21 Virtual Threads and pattern matching.
   *
   * @param limit the maximum number of results to return
   * @param total the actual total number of results
   * @param data the collection of data to include in the response
   * @param <T> the type of data in the collection
   * @return a limited paged response containing the data, total, and limit information
   */
  public static <T> LimitedPagedResponse<T> limitedPaged(long limit, long total, Collection<T> data) {
    return new LimitedPagedResponse<>(limit, total, data);
  }
  
  /**
   * Creates a limited paged response with the provided limit, total, data collection, and timeout information.
   * This method is compatible with Java 21 Virtual Threads and pattern matching.
   *
   * @param limit the maximum number of results to return
   * @param total the actual total number of results
   * @param data the collection of data to include in the response
   * @param timedOut whether the operation timed out
   * @param <T> the type of data in the collection
   * @return a limited paged response containing the data, total, limit, and timeout information
   */
  public static <T> LimitedPagedResponse<T> limitedPaged(long limit, long total, Collection<T> data, boolean timedOut) {
    return new LimitedPagedResponse<>(limit, total, data, timedOut);
  }
}