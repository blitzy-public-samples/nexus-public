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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import static jakarta.ws.rs.core.Response.Status.*;
import static org.junit.jupiter.api.Assertions.*;

public class SimpleApiResponseTest
{

  @Test
  @DisplayName("OK response without data should have correct status and message")
  public void okResponseWithoutData() {
    Response simpleApiResponse = SimpleApiResponse.ok("message");
    assertResponse(simpleApiResponse, OK, null);
  }

  @Test
  @DisplayName("OK response with data should have correct status, message and data")
  public void okResponseWithData() {
    Response simpleApiResponse = SimpleApiResponse.ok("message", new Data("bar"));
    assertResponse(simpleApiResponse, OK, "bar");
  }

  @Test
  @DisplayName("Not Found response without data should have correct status and message")
  public void notFoundResponseWithoutData() {
    Response simpleApiResponse = SimpleApiResponse.notFound("message");
    assertResponse(simpleApiResponse, NOT_FOUND, null);
  }

  @Test
  @DisplayName("Not Found response with data should have correct status, message and data")
  public void notFoundResponseWithData() {
    Response simpleApiResponse = SimpleApiResponse.notFound("message", new Data("bar"));
    assertResponse(simpleApiResponse, NOT_FOUND, "bar");
  }

  @Test
  @DisplayName("Bad Request response without data should have correct status and message")
  public void badRequestResponseWithoutData() {
    Response simpleApiResponse = SimpleApiResponse.badRequest("message");
    assertResponse(simpleApiResponse, BAD_REQUEST, null);
  }

  @Test
  @DisplayName("Bad Request response with data should have correct status, message and data")
  public void badRequestResponseWithData() {
    Response simpleApiResponse = SimpleApiResponse.badRequest("message", new Data("bar"));
    assertResponse(simpleApiResponse, BAD_REQUEST, "bar");
  }

  @Test
  @DisplayName("Unauthorized response without data should have correct status and message")
  public void unauthorizedResponseWithoutData() {
    Response simpleApiResponse = SimpleApiResponse.unauthorized("message");
    assertResponse(simpleApiResponse, UNAUTHORIZED, null);
  }

  @Test
  @DisplayName("Unauthorized response with data should have correct status, message and data")
  public void unauthorizedResponseWithData() {
    Response simpleApiResponse = SimpleApiResponse.unauthorized("message", new Data("bar"));
    assertResponse(simpleApiResponse, UNAUTHORIZED, "bar");
  }

  private void assertResponse(Response simpleApiResponse, Status status, String value) {
    assertEquals(status.getStatusCode(), simpleApiResponse.getStatus());
    SimpleApiResponse entity = (SimpleApiResponse) simpleApiResponse.getEntity();
    assertEquals(status.getStatusCode(), entity.getStatus());
    assertEquals("message", entity.getMessage());
    if (value == null) {
      assertNull(entity.getData());
    }
    else {
      assertEquals("bar", ((Data) entity.getData()).getFoo());
    }
  }

  private static class Data
  {
    private final String foo;

    public Data(String foo) {
      this.foo = foo;
    }

    public String getFoo() {
      return foo;
    }
  }
}
