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
package org.sonatype.nexus.repository.httpbridge;

import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.BadRequestException;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.httpbridge.internal.HttpResponseSenderSelector;
import org.sonatype.nexus.repository.httpbridge.internal.RepositoryPath;
import org.sonatype.nexus.repository.view.ContentTypes;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.repository.view.Response;
import org.sonatype.nexus.repository.view.Status;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for Java 21 Pattern Matching features in the HTTP Bridge component.
 * 
 * @since 3.60
 */
public class PatternMatchingHttpBridgeTest
    extends TestSupport
{
  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Mock
  private HttpServletRequest httpServletRequest;

  @Mock
  private Request request;

  @Mock
  private Response response;

  @Mock
  private Repository repository;

  @Before
  public void setUp() {
    when(repository.getName()).thenReturn("test-repo");
  }

  /**
   * Test Pattern Matching for repository path resolution using instanceof pattern matching.
   */
  @Test
  public void testRepositoryPathPatternMatching() {
    // Create test objects
    Object validPath = RepositoryPath.parse("/repo/path");
    Object invalidPath = "not-a-repository-path";
    Object nullPath = null;

    // Test pattern matching with instanceof
    String result = getRepositoryNameWithPatternMatching(validPath);
    assertThat(result, is("repo"));

    result = getRepositoryNameWithPatternMatching(invalidPath);
    assertThat(result, is("unknown"));

    result = getRepositoryNameWithPatternMatching(nullPath);
    assertThat(result, is("null"));
  }

  /**
   * Test Pattern Matching with switch expressions for HTTP methods.
   */
  @Test
  public void testHttpMethodPatternMatching() {
    // Test different HTTP methods
    assertThat(getMethodDescriptionWithPatternMatching("GET"), is("Read operation"));
    assertThat(getMethodDescriptionWithPatternMatching("POST"), is("Create operation"));
    assertThat(getMethodDescriptionWithPatternMatching("PUT"), is("Update operation"));
    assertThat(getMethodDescriptionWithPatternMatching("DELETE"), is("Delete operation"));
    assertThat(getMethodDescriptionWithPatternMatching("HEAD"), is("Metadata operation"));
    assertThat(getMethodDescriptionWithPatternMatching("OPTIONS"), is("Metadata operation"));
    assertThat(getMethodDescriptionWithPatternMatching("PATCH"), is("Partial update operation"));
    assertThat(getMethodDescriptionWithPatternMatching("UNKNOWN"), is("Unsupported operation"));
    assertThat(getMethodDescriptionWithPatternMatching(null), is("Invalid operation"));
  }

  /**
   * Test Pattern Matching with guarded patterns for HTTP status codes.
   */
  @Test
  public void testHttpStatusPatternMatching() {
    // Test different HTTP status codes
    assertThat(getStatusDescriptionWithPatternMatching(200), is("OK"));
    assertThat(getStatusDescriptionWithPatternMatching(201), is("Created"));
    assertThat(getStatusDescriptionWithPatternMatching(204), is("No Content"));
    assertThat(getStatusDescriptionWithPatternMatching(302), is("Redirection"));
    assertThat(getStatusDescriptionWithPatternMatching(304), is("Not Modified"));
    assertThat(getStatusDescriptionWithPatternMatching(400), is("Bad Request"));
    assertThat(getStatusDescriptionWithPatternMatching(401), is("Unauthorized"));
    assertThat(getStatusDescriptionWithPatternMatching(403), is("Forbidden"));
    assertThat(getStatusDescriptionWithPatternMatching(404), is("Not Found"));
    assertThat(getStatusDescriptionWithPatternMatching(500), is("Server Error"));
    assertThat(getStatusDescriptionWithPatternMatching(503), is("Service Unavailable"));
    assertThat(getStatusDescriptionWithPatternMatching(999), is("Unknown Status"));
  }

  /**
   * Test Pattern Matching with nested patterns for Response objects.
   */
  @Test
  public void testResponsePatternMatching() {
    // Create test responses
    Response okResponse = mock(Response.class);
    Status okStatus = new Status(true, 200);
    when(okResponse.getStatus()).thenReturn(okStatus);

    Response errorResponse = mock(Response.class);
    Status errorStatus = new Status(false, 500);
    when(errorResponse.getStatus()).thenReturn(errorStatus);

    Response redirectResponse = mock(Response.class);
    Status redirectStatus = new Status(true, 302);
    when(redirectResponse.getStatus()).thenReturn(redirectStatus);

    // Test pattern matching with nested patterns
    assertThat(getResponseDescriptionWithPatternMatching(okResponse), is("Successful response with status 200"));
    assertThat(getResponseDescriptionWithPatternMatching(errorResponse), is("Error response with status 500"));
    assertThat(getResponseDescriptionWithPatternMatching(redirectResponse), is("Redirect response with status 302"));
    assertThat(getResponseDescriptionWithPatternMatching(null), is("Null response"));
  }

  /**
   * Test Pattern Matching with content types.
   */
  @Test
  public void testContentTypePatternMatching() {
    // Test different content types
    assertThat(getContentTypeDescriptionWithPatternMatching(ContentTypes.APPLICATION_JSON), 
        is("JSON content"));
    assertThat(getContentTypeDescriptionWithPatternMatching(ContentTypes.APPLICATION_XML), 
        is("XML content"));
    assertThat(getContentTypeDescriptionWithPatternMatching(ContentTypes.TEXT_HTML), 
        is("HTML content"));
    assertThat(getContentTypeDescriptionWithPatternMatching(ContentTypes.TEXT_PLAIN), 
        is("Plain text content"));
    assertThat(getContentTypeDescriptionWithPatternMatching("application/octet-stream"), 
        is("Binary content"));
    assertThat(getContentTypeDescriptionWithPatternMatching("image/jpeg"), 
        is("Image content"));
    assertThat(getContentTypeDescriptionWithPatternMatching("unknown/type"), 
        is("Unknown content type"));
    assertThat(getContentTypeDescriptionWithPatternMatching(null), 
        is("No content type"));
  }

  /**
   * Test error handling with Pattern Matching constructs.
   */
  @Test
  public void testErrorHandlingWithPatternMatching() {
    // Test with various exception types
    Exception badRequestException = new BadRequestException("Invalid request");
    Exception illegalArgumentException = new IllegalArgumentException("Invalid argument");
    Exception runtimeException = new RuntimeException("Runtime error");
    Exception nullPointerException = new NullPointerException("Null pointer");

    // Test pattern matching for exception handling
    assertThat(getExceptionDescriptionWithPatternMatching(badRequestException), 
        is("Bad Request: Invalid request"));
    assertThat(getExceptionDescriptionWithPatternMatching(illegalArgumentException), 
        is("Invalid argument: Invalid argument"));
    assertThat(getExceptionDescriptionWithPatternMatching(runtimeException), 
        is("Runtime error: Runtime error"));
    assertThat(getExceptionDescriptionWithPatternMatching(nullPointerException), 
        is("Null pointer: Null pointer"));
    assertThat(getExceptionDescriptionWithPatternMatching(null), 
        is("No exception"));
  }

  /**
   * Test Pattern Matching with HttpResponseSender implementations.
   */
  @Test
  public void testHttpResponseSenderPatternMatching() {
    // Create mock HttpResponseSender implementations
    HttpResponseSender defaultSender = mock(HttpResponseSender.class);
    HttpResponseSender jsonSender = mock(HttpResponseSender.class);
    HttpResponseSender xmlSender = mock(HttpResponseSender.class);
    
    // Create a map of content type to sender
    Map<String, HttpResponseSender> senders = Map.of(
        ContentTypes.APPLICATION_JSON, jsonSender,
        ContentTypes.APPLICATION_XML, xmlSender
    );
    
    // Create a selector with the senders
    HttpResponseSenderSelector selector = new HttpResponseSenderSelector(senders, defaultSender);
    
    // Test pattern matching with HttpResponseSenderSelector
    assertThat(getResponseSenderDescriptionWithPatternMatching(selector, ContentTypes.APPLICATION_JSON), 
        is("JSON response sender"));
    assertThat(getResponseSenderDescriptionWithPatternMatching(selector, ContentTypes.APPLICATION_XML), 
        is("XML response sender"));
    assertThat(getResponseSenderDescriptionWithPatternMatching(selector, ContentTypes.TEXT_HTML), 
        is("Default response sender"));
    assertThat(getResponseSenderDescriptionWithPatternMatching(selector, null), 
        is("Default response sender"));
    assertThat(getResponseSenderDescriptionWithPatternMatching(null, ContentTypes.APPLICATION_JSON), 
        is("No response sender"));
  }

  /**
   * Helper method that uses Pattern Matching with instanceof to get repository name.
   */
  private String getRepositoryNameWithPatternMatching(Object path) {
    // Using Pattern Matching with instanceof
    if (path instanceof RepositoryPath repositoryPath) {
      return repositoryPath.getRepositoryName();
    } else if (path instanceof String stringPath) {
      return "string-path";
    } else if (path == null) {
      return "null";
    } else {
      return "unknown";
    }
  }

  /**
   * Helper method that uses Pattern Matching with switch expressions for HTTP methods.
   */
  private String getMethodDescriptionWithPatternMatching(String method) {
    // Using Pattern Matching with switch expressions
    return switch (method) {
      case "GET" -> "Read operation";
      case "POST" -> "Create operation";
      case "PUT" -> "Update operation";
      case "DELETE" -> "Delete operation";
      case "HEAD", "OPTIONS" -> "Metadata operation";
      case "PATCH" -> "Partial update operation";
      case null -> "Invalid operation";
      default -> "Unsupported operation";
    };
  }

  /**
   * Helper method that uses Pattern Matching with guarded patterns for HTTP status codes.
   */
  private String getStatusDescriptionWithPatternMatching(int statusCode) {
    // Using Pattern Matching with guarded patterns in switch expressions
    return switch (statusCode) {
      case Integer i when i == 200 -> "OK";
      case Integer i when i == 201 -> "Created";
      case Integer i when i == 204 -> "No Content";
      case Integer i when i >= 300 && i < 400 && i != 304 -> "Redirection";
      case Integer i when i == 304 -> "Not Modified";
      case Integer i when i == 400 -> "Bad Request";
      case Integer i when i == 401 -> "Unauthorized";
      case Integer i when i == 403 -> "Forbidden";
      case Integer i when i == 404 -> "Not Found";
      case Integer i when i == 500 -> "Server Error";
      case Integer i when i == 503 -> "Service Unavailable";
      default -> "Unknown Status";
    };
  }

  /**
   * Helper method that uses Pattern Matching with nested patterns for Response objects.
   */
  private String getResponseDescriptionWithPatternMatching(Response response) {
    // Using Pattern Matching with nested patterns
    return switch (response) {
      case null -> "Null response";
      case Response r when r.getStatus() == null -> "Response with null status";
      case Response r when r.getStatus().isSuccessful() && r.getStatus().getCode() == 200 -> 
          "Successful response with status 200";
      case Response r when !r.getStatus().isSuccessful() && r.getStatus().getCode() == 500 -> 
          "Error response with status 500";
      case Response r when r.getStatus().isSuccessful() && r.getStatus().getCode() == 302 -> 
          "Redirect response with status 302";
      default -> "Unknown response type";
    };
  }

  /**
   * Helper method that uses Pattern Matching with content types.
   */
  private String getContentTypeDescriptionWithPatternMatching(String contentType) {
    // Using Pattern Matching with switch expressions for content types
    return switch (contentType) {
      case null -> "No content type";
      case ContentTypes.APPLICATION_JSON -> "JSON content";
      case ContentTypes.APPLICATION_XML -> "XML content";
      case ContentTypes.TEXT_HTML -> "HTML content";
      case ContentTypes.TEXT_PLAIN -> "Plain text content";
      case String s when s.startsWith("image/") -> "Image content";
      case String s when s.equals("application/octet-stream") -> "Binary content";
      default -> "Unknown content type";
    };
  }

  /**
   * Helper method that uses Pattern Matching for exception handling.
   */
  private String getExceptionDescriptionWithPatternMatching(Exception exception) {
    // Using Pattern Matching with switch expressions for exceptions
    return switch (exception) {
      case null -> "No exception";
      case BadRequestException e -> "Bad Request: " + e.getMessage();
      case IllegalArgumentException e -> "Invalid argument: " + e.getMessage();
      case RuntimeException e -> "Runtime error: " + e.getMessage();
      case NullPointerException e -> "Null pointer: " + e.getMessage();
      default -> "Unknown exception: " + exception.getMessage();
    };
  }

  /**
   * Helper method that uses Pattern Matching with HttpResponseSenderSelector.
   */
  private String getResponseSenderDescriptionWithPatternMatching(HttpResponseSenderSelector selector, String contentType) {
    // Using Pattern Matching with switch expressions for HttpResponseSenderSelector
    if (selector == null) {
      return "No response sender";
    }
    
    HttpResponseSender sender = selector.select(contentType);
    return switch (sender) {
      case null -> "No matching sender";
      case HttpResponseSender s when contentType != null && contentType.equals(ContentTypes.APPLICATION_JSON) -> 
          "JSON response sender";
      case HttpResponseSender s when contentType != null && contentType.equals(ContentTypes.APPLICATION_XML) -> 
          "XML response sender";
      default -> "Default response sender";
    };
  }
}