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

import com.google.common.collect.ImmutableList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("WebApplicationMessageException Tests")
class WebApplicationMessageExceptionTest
{
  /**
   * Method under test:
   * {@link WebApplicationMessageException#WebApplicationMessageException(Response.Status, Object, String)}
   */
  @Test
  @DisplayName("Constructor with media type sets correct status and content type")
  void testConstructor() {
    WebApplicationMessageException exception = new WebApplicationMessageException(
        Response.Status.BAD_REQUEST, "Message", MediaType.APPLICATION_JSON);
    Response response = exception.getResponse();

    assertEquals(400, response.getStatus());

    Object entity = response.getEntity();

    assertTrue(entity instanceof ValidationErrorXO);
    assertEquals("Message", ((ValidationErrorXO) entity).getMessage());
    assertEquals(ValidationErrorXO.GENERIC, ((ValidationErrorXO) entity).getId());
    assertEquals(ImmutableList.of(MediaType.APPLICATION_JSON), response.getHeaders().get("Content-Type"));
  }

  /**
   * Method under test: {@link WebApplicationMessageException#WebApplicationMessageException(Response.Status, String)}
   */
  @Test
  @DisplayName("Constructor without media type defaults to TEXT_PLAIN")
  void testConstructorNoMediaType() {
    WebApplicationMessageException exception = new WebApplicationMessageException(
        Response.Status.NOT_FOUND, "Message");
    Response response = exception.getResponse();

    assertEquals(404, response.getStatus());

    Object entity = response.getEntity();

    assertTrue(entity instanceof ValidationErrorXO);
    assertEquals("Message", ((ValidationErrorXO) entity).getMessage());
    assertEquals(ValidationErrorXO.GENERIC, ((ValidationErrorXO) entity).getId());
    assertEquals(ImmutableList.of(MediaType.TEXT_PLAIN), response.getHeaders().get("Content-Type"));
  }

  /**
   * Method under test: Validate different response types using pattern matching for switch
   */
  @Test
  @DisplayName("Pattern matching for switch validates different response types")
  void testResponseValidationWithPatternMatching() {
    // Test different status codes with pattern matching
    validateResponseWithPatternMatching(
        new WebApplicationMessageException(Response.Status.BAD_REQUEST, "Bad Request").getResponse());
    validateResponseWithPatternMatching(
        new WebApplicationMessageException(Response.Status.NOT_FOUND, "Not Found").getResponse());
    validateResponseWithPatternMatching(
        new WebApplicationMessageException(Response.Status.UNAUTHORIZED, "Unauthorized").getResponse());
    validateResponseWithPatternMatching(
        new WebApplicationMessageException(Response.Status.INTERNAL_SERVER_ERROR, "Server Error").getResponse());
  }

  /**
   * Helper method that uses pattern matching for switch to validate response based on status code
   */
  private void validateResponseWithPatternMatching(Response response) {
    ValidationErrorXO errorXO = (ValidationErrorXO) response.getEntity();
    String expectedMessage = errorXO.getMessage();
    
    String result = switch (response.getStatus()) {
      case 400 -> {
        assertEquals("Bad Request", expectedMessage);
        yield "Bad Request validated";
      }
      case 404 -> {
        assertEquals("Not Found", expectedMessage);
        yield "Not Found validated";
      }
      case 401 -> {
        assertEquals("Unauthorized", expectedMessage);
        yield "Unauthorized validated";
      }
      case 500 -> {
        assertEquals("Server Error", expectedMessage);
        yield "Server Error validated";
      }
      default -> "Unexpected status code: " + response.getStatus();
    };
    
    assertTrue(result.contains("validated"), "Response validation failed: " + result);
  }
}