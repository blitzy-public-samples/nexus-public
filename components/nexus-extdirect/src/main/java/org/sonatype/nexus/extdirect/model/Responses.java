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
 * @since 3.0
 */
public class Responses
{
  private Responses() {
    // empty
  }

  /**
   * Create a success response with no data.
   *
   * @return a success response with null data
   */
  public static Response<Object> success() {
    return success(null);
  }

  /**
   * Create a success response with the provided data.
   *
   * @param data the data to include in the response
   * @param <T> the type of data
   * @return a success response with the provided data
   */
  public static <T> Response<T> success(T data) {
    return new Response<>(true, data);
  }

  /**
   * Create an error response from a throwable cause.
   *
   * @param cause the throwable that caused the error
   * @return an error response with the cause's message
   */
  public static ErrorResponse error(final Throwable cause) {
    return new ErrorResponse(cause);
  }

  /**
   * Create an error response with a custom message.
   *
   * @param message the error message
   * @return an error response with the provided message
   */
  public static ErrorResponse error(final String message) {
    return new ErrorResponse(message);
  }

  /**
   * Create a validation response from a constraint violation exception.
   *
   * @param cause the constraint violation exception
   * @return a validation response with the constraint violations
   */
  public static ValidationResponse invalid(final ConstraintViolationException cause) {
    return new ValidationResponse(cause);
  }
  
  /**
   * Create a paged response with the provided total count and data collection.
   *
   * @param total the total number of items
   * @param data the collection of items for the current page
   * @param <T> the type of items in the collection
   * @return a paged response with the provided total and data
   */
  public static <T> PagedResponse<T> paged(final long total, final Collection<T> data) {
    return new PagedResponse<>(total, data);
  }
  
  /**
   * Create a limited paged response with the provided limit, total count, and data collection.
   *
   * @param limit the maximum number of items to return
   * @param total the total number of items (unlimited)
   * @param data the collection of items for the current page
   * @param <T> the type of items in the collection
   * @return a limited paged response with the provided limit, total, and data
   */
  public static <T> LimitedPagedResponse<T> limitedPaged(final long limit, final long total, final Collection<T> data) {
    return new LimitedPagedResponse<>(limit, total, data);
  }
  
  /**
   * Create a limited paged response with the provided limit, total count, data collection, and timeout flag.
   *
   * @param limit the maximum number of items to return
   * @param total the total number of items (unlimited)
   * @param data the collection of items for the current page
   * @param timedOut whether the operation timed out
   * @param <T> the type of items in the collection
   * @return a limited paged response with the provided limit, total, data, and timeout flag
   */
  public static <T> LimitedPagedResponse<T> limitedPaged(final long limit, final long total, final Collection<T> data, final boolean timedOut) {
    return new LimitedPagedResponse<>(limit, total, data, timedOut);
  }
}
