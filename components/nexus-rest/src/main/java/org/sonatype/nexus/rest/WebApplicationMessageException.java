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

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import static com.google.common.base.Preconditions.checkNotNull;
import static jakarta.ws.rs.core.MediaType.TEXT_PLAIN;

/**
 * {@link WebApplicationException} with {@link Status} and a text message.
 *
 * @since 3.8
 */
public class WebApplicationMessageException
    extends WebApplicationException
{
  /**
   * Creates a new exception with the given status and message using TEXT_PLAIN media type.
   *
   * @param status the HTTP status
   * @param message the error message
   */
  public WebApplicationMessageException(final Status status, final String message) {
    this(status, message, TEXT_PLAIN);
  }

  /**
   * Creates a new exception with the given status, message and media type.
   *
   * @param status the HTTP status
   * @param message the error message object
   * @param mediaType the media type for the response
   */
  public WebApplicationMessageException(final Status status, final Object message, final String mediaType) {
    super(createResponse(status, message, mediaType));
  }

  /**
   * Creates a new exception with the given status code, message and media type.
   *
   * @param status the HTTP status code
   * @param message the error message object
   * @param mediaType the media type for the response
   */
  public WebApplicationMessageException(int status, final Object message, final String mediaType) {
    super(createResponse(status, message, mediaType));
  }
  
  /**
   * Creates a response with the given status, message and media type.
   * 
   * @param status the HTTP status
   * @param message the error message object
   * @param mediaType the media type for the response
   * @return the Response object
   */
  private static Response createResponse(final Status status, final Object message, final String mediaType) {
    return Response.status(checkNotNull(status))
        .entity(new GenericEntity<>(new ValidationErrorXO(checkNotNull(message).toString()), ValidationErrorXO.class))
        .type(mediaType)
        .build();
  }
  
  /**
   * Creates a response with the given status code, message and media type.
   * 
   * @param status the HTTP status code
   * @param message the error message object
   * @param mediaType the media type for the response
   * @return the Response object
   */
  private static Response createResponse(final int status, final Object message, final String mediaType) {
    return Response.status(status)
        .entity(new GenericEntity<>(new ValidationErrorXO(checkNotNull(message).toString()), ValidationErrorXO.class))
        .type(mediaType)
        .build();
  }
  
  /**
   * Creates a new exception with an appropriate message based on the status code.
   * Uses Pattern Matching for switch to handle different status codes elegantly.
   *
   * @param status the HTTP status
   * @return a WebApplicationMessageException with an appropriate message
   */
  public static WebApplicationMessageException forStatus(final Status status) {
    String message = switch (status) {
      case BAD_REQUEST -> "The request is invalid or malformed";
      case UNAUTHORIZED -> "Authentication is required";
      case FORBIDDEN -> "Insufficient permissions";
      case NOT_FOUND -> "The requested resource does not exist";
      case CONFLICT -> "The request conflicts with the current state of the resource";
      case INTERNAL_SERVER_ERROR -> "An unexpected error occurred";
      case SERVICE_UNAVAILABLE -> "The service is currently unavailable";
      default -> "HTTP error: " + status.getStatusCode() + " " + status.getReasonPhrase();
    };
    return new WebApplicationMessageException(status, message);
  }
  
  /**
   * Creates a new exception with an appropriate message based on the status code.
   * Uses Pattern Matching for switch to handle different status codes elegantly.
   *
   * @param statusCode the HTTP status code
   * @return a WebApplicationMessageException with an appropriate message
   */
  public static WebApplicationMessageException forStatus(final int statusCode) {
    // Convert int to Status if it's a standard status code, otherwise use the raw int
    try {
      Status status = Status.fromStatusCode(statusCode);
      if (status != null) {
        return forStatus(status);
      }
    } catch (IllegalArgumentException e) {
      // Status code not recognized, continue with raw int handling
    }
    
    String message = switch (statusCode) {
      case 400 -> "The request is invalid or malformed";
      case 401 -> "Authentication is required";
      case 403 -> "Insufficient permissions";
      case 404 -> "The requested resource does not exist";
      case 409 -> "The request conflicts with the current state of the resource";
      case 500 -> "An unexpected error occurred";
      case 503 -> "The service is currently unavailable";
      default -> "HTTP error: " + statusCode;
    };
    return new WebApplicationMessageException(statusCode, message, TEXT_PLAIN);
  }
}