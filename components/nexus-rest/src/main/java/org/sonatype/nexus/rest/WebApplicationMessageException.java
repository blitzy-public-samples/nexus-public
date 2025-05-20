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
   * Creates a new exception with the specified status and message using TEXT_PLAIN media type.
   *
   * @param status the HTTP status code
   * @param message the error message
   */
  public WebApplicationMessageException(final Status status, final String message) {
    this(status, message, TEXT_PLAIN);
  }

  /**
   * Creates a new exception with the specified status, message, and media type.
   *
   * @param status the HTTP status code
   * @param message the error message or object (will be converted to string)
   * @param mediaType the media type of the response
   */
  public WebApplicationMessageException(final Status status, final Object message, final String mediaType) {
    super(Response.status(checkNotNull(status))
        .entity(new GenericEntity<>(new ValidationErrorXO(checkNotNull(message).toString()), ValidationErrorXO.class))
        .type(mediaType)
        .build());
  }

  /**
   * Creates a new exception with the specified numeric status, message, and media type.
   *
   * @param status the numeric HTTP status code
   * @param message the error message or object (will be converted to string)
   * @param mediaType the media type of the response
   */
  public WebApplicationMessageException(int status, final Object message, final String mediaType) {
    super(Response.status(status)
        .entity(new GenericEntity<>(new ValidationErrorXO(checkNotNull(message).toString()), ValidationErrorXO.class))
        .type(mediaType)
        .build());
  }
}