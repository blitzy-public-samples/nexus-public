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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Tests for {@link WebApplicationMessageException}.
 */
public class WebApplicationMessageExceptionTest
{
  /**
   * Method under test:
   * {@link WebApplicationMessageException#WebApplicationMessageException(Response.Status, Object, String)}
   */
  @Test
  @DisplayName("Test constructor with media type")
  public void testConstructor() {
    WebApplicationMessageException exception = new WebApplicationMessageException(
        Response.Status.BAD_REQUEST, "Message", MediaType.APPLICATION_JSON);
    Response response = exception.getResponse();

    assertEquals(400, response.getStatus());

    Object entity = response.getEntity();

    assertInstanceOf(ValidationErrorXO.class, entity);
    assertEquals("Message", ((ValidationErrorXO) entity).message());
    assertEquals(ValidationErrorXO.GENERIC, ((ValidationErrorXO) entity).id());
    assertEquals(ImmutableList.of(MediaType.APPLICATION_JSON), response.getHeaders().get("Content-Type"));
  }

  /**
   * Method under test: {@link WebApplicationMessageException#WebApplicationMessageException(Response.Status, String)}
   */
  @Test
  @DisplayName("Test constructor without media type")
  public void testConstructorNoMediaType() {
    WebApplicationMessageException exception = new WebApplicationMessageException(
        Response.Status.NOT_FOUND, "Message");
    Response response = exception.getResponse();

    assertEquals(404, response.getStatus());

    Object entity = response.getEntity();

    assertInstanceOf(ValidationErrorXO.class, entity);
    assertEquals("Message", ((ValidationErrorXO) entity).message());
    assertEquals(ValidationErrorXO.GENERIC, ((ValidationErrorXO) entity).id());
    assertEquals(ImmutableList.of(MediaType.TEXT_PLAIN), response.getHeaders().get("Content-Type"));
  }
  
  /**
   * Tests multiple status code responses using pattern matching.
   */
  @Test
  @DisplayName("Test multiple status codes using pattern matching")
  public void testMultipleStatusCodes() {
    // Test with different status codes using pattern matching
    Response.Status[] statuses = {
        Response.Status.BAD_REQUEST,
        Response.Status.NOT_FOUND,
        Response.Status.INTERNAL_SERVER_ERROR
    };
    
    for (Response.Status status : statuses) {
      WebApplicationMessageException exception = new WebApplicationMessageException(
          status, "Test message", MediaType.APPLICATION_JSON);
      Response response = exception.getResponse();
      
      // Use pattern matching to validate response based on status
      switch (status) {
        case Response.Status.BAD_REQUEST -> {
          assertEquals(400, response.getStatus());
          var entity = assertInstanceOf(ValidationErrorXO.class, response.getEntity());
          assertEquals("Test message", entity.message());
        }
        case Response.Status.NOT_FOUND -> {
          assertEquals(404, response.getStatus());
          var entity = assertInstanceOf(ValidationErrorXO.class, response.getEntity());
          assertEquals("Test message", entity.message());
        }
        case Response.Status.INTERNAL_SERVER_ERROR -> {
          assertEquals(500, response.getStatus());
          var entity = assertInstanceOf(ValidationErrorXO.class, response.getEntity());
          assertEquals("Test message", entity.message());
        }
        default -> throw new IllegalArgumentException("Unexpected status: " + status);
      }
    }
  }
  
  /**
   * Tests constructor with integer status code.
   */
  @Test
  @DisplayName("Test constructor with integer status code")
  public void testConstructorWithIntegerStatus() {
    // Test with direct integer status code
    WebApplicationMessageException exception = new WebApplicationMessageException(
        418, "I'm a teapot", MediaType.APPLICATION_JSON);
    Response response = exception.getResponse();
    
    assertEquals(418, response.getStatus());
    var entity = assertInstanceOf(ValidationErrorXO.class, response.getEntity());
    assertEquals("I'm a teapot", entity.message());
    assertEquals(ValidationErrorXO.GENERIC, entity.id());
  }
}
