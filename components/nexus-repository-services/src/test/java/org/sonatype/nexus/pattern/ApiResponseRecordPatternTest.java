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
package org.sonatype.nexus.pattern;

import java.util.List;
import java.util.Optional;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for Java 21 record pattern matching with API response objects.
 */
@Category(Java21TestGroup.class)
public class ApiResponseRecordPatternTest
    extends TestSupport
{
  /**
   * Simple API response record that contains a status code and message.
   */
  record SimpleApiResponse(int statusCode, String message) {}

  /**
   * API response with data payload.
   */
  record ApiResponseWithData<T>(int statusCode, String message, T data) {}

  /**
   * Nested data structure for testing nested record patterns.
   */
  record RepositoryData(String name, String format, RepositoryDetails details) {}

  /**
   * Details about a repository for nested pattern testing.
   */
  record RepositoryDetails(String type, List<String> members, boolean online) {}

  /**
   * Error response with additional error details.
   */
  record ErrorResponse(int statusCode, String message, String errorCode, String stackTrace) {}

  @Test
  public void testBasicRecordPatternMatching() {
    Object response = new SimpleApiResponse(200, "OK");
    
    // Traditional approach with instanceof and accessor methods
    if (response instanceof SimpleApiResponse) {
      SimpleApiResponse apiResponse = (SimpleApiResponse) response;
      assertThat(apiResponse.statusCode(), is(200));
      assertThat(apiResponse.message(), is("OK"));
    } else {
      fail("Response should be an instance of SimpleApiResponse");
    }
    
    // Java 21 approach with record pattern matching
    if (response instanceof SimpleApiResponse(int statusCode, String message)) {
      assertThat(statusCode, is(200));
      assertThat(message, is("OK"));
    } else {
      fail("Response should match SimpleApiResponse pattern");
    }
  }

  @Test
  public void testRecordPatternWithTypeInference() {
    Object response = new SimpleApiResponse(201, "Created");
    
    // Using var for type inference in pattern variables
    if (response instanceof SimpleApiResponse(var statusCode, var message)) {
      assertThat(statusCode, is(201));
      assertThat(message, is("Created"));
    } else {
      fail("Response should match SimpleApiResponse pattern with type inference");
    }
  }

  @Test
  public void testGenericRecordPatternMatching() {
    RepositoryData repoData = new RepositoryData("maven-central", "maven2", 
        new RepositoryDetails("proxy", List.of("central"), true));
    
    Object response = new ApiResponseWithData<>(200, "OK", repoData);
    
    // Pattern matching with generic record type
    if (response instanceof ApiResponseWithData<RepositoryData>(var status, var message, var data)) {
      assertThat(status, is(200));
      assertThat(message, is("OK"));
      assertThat(data.name(), is("maven-central"));
      assertThat(data.format(), is("maven2"));
    } else {
      fail("Response should match ApiResponseWithData pattern");
    }
  }

  @Test
  public void testNestedRecordPatternMatching() {
    RepositoryData repoData = new RepositoryData("maven-central", "maven2", 
        new RepositoryDetails("proxy", List.of("central"), true));
    
    Object response = new ApiResponseWithData<>(200, "OK", repoData);
    
    // Nested record pattern matching
    if (response instanceof ApiResponseWithData<RepositoryData>(var status, var message, 
        RepositoryData(var name, var format, RepositoryDetails(var type, var members, var online)))) {
      
      assertThat(status, is(200));
      assertThat(message, is("OK"));
      assertThat(name, is("maven-central"));
      assertThat(format, is("maven2"));
      assertThat(type, is("proxy"));
      assertThat(members, is(List.of("central")));
      assertThat(online, is(true));
    } else {
      fail("Response should match nested record pattern");
    }
  }

  @Test
  public void testPatternMatchingWithGuardConditions() {
    Object successResponse = new SimpleApiResponse(200, "OK");
    Object errorResponse = new SimpleApiResponse(404, "Not Found");
    Object serverErrorResponse = new SimpleApiResponse(500, "Internal Server Error");
    
    // Pattern matching with guard conditions
    String successResult = processResponse(successResponse);
    String errorResult = processResponse(errorResponse);
    String serverErrorResult = processResponse(serverErrorResponse);
    
    assertThat(successResult, is("Success: OK"));
    assertThat(errorResult, is("Client Error: Not Found"));
    assertThat(serverErrorResult, is("Server Error: Internal Server Error"));
  }
  
  private String processResponse(Object response) {
    return switch (response) {
      case SimpleApiResponse(var status, var message) when status >= 200 && status < 300 ->
        "Success: " + message;
      case SimpleApiResponse(var status, var message) when status >= 400 && status < 500 ->
        "Client Error: " + message;
      case SimpleApiResponse(var status, var message) when status >= 500 ->
        "Server Error: " + message;
      default ->
        "Unknown response";
    };
  }

  @Test
  public void testPatternMatchingInSwitchExpression() {
    Object successResponse = new SimpleApiResponse(200, "OK");
    Object errorResponse = new ErrorResponse(400, "Bad Request", "INVALID_PARAM", "java.lang.IllegalArgumentException");
    
    // Using pattern matching in switch expression
    Optional<String> successMessage = extractMessage(successResponse);
    Optional<String> errorMessage = extractMessage(errorResponse);
    
    assertThat(successMessage.isPresent(), is(true));
    assertThat(successMessage.get(), is("OK"));
    
    assertThat(errorMessage.isPresent(), is(true));
    assertThat(errorMessage.get(), is("Bad Request (INVALID_PARAM)"));
  }
  
  private Optional<String> extractMessage(Object response) {
    return switch (response) {
      case SimpleApiResponse(var status, var message) ->
        Optional.of(message);
      case ErrorResponse(var status, var message, var errorCode, var stackTrace) ->
        Optional.of(message + " (" + errorCode + ")");
      default ->
        Optional.empty();
    };
  }

  @Test
  public void testComparisonWithTraditionalApproach() {
    RepositoryData repoData = new RepositoryData("maven-central", "maven2", 
        new RepositoryDetails("proxy", List.of("central"), true));
    
    ApiResponseWithData<RepositoryData> response = new ApiResponseWithData<>(200, "OK", repoData);
    
    // Traditional approach - multiple levels of accessor calls
    int statusCode = response.statusCode();
    String message = response.message();
    String repoName = response.data().name();
    String repoFormat = response.data().format();
    String repoType = response.data().details().type();
    List<String> repoMembers = response.data().details().members();
    boolean repoOnline = response.data().details().online();
    
    assertThat(statusCode, is(200));
    assertThat(message, is("OK"));
    assertThat(repoName, is("maven-central"));
    assertThat(repoFormat, is("maven2"));
    assertThat(repoType, is("proxy"));
    assertThat(repoMembers, is(List.of("central")));
    assertThat(repoOnline, is(true));
    
    // Record pattern approach - single destructuring operation
    if (response instanceof ApiResponseWithData<RepositoryData>(var status, var msg, 
        RepositoryData(var name, var format, RepositoryDetails(var type, var members, var online)))) {
      
      assertThat(status, is(statusCode));
      assertThat(msg, is(message));
      assertThat(name, is(repoName));
      assertThat(format, is(repoFormat));
      assertThat(type, is(repoType));
      assertThat(members, is(repoMembers));
      assertThat(online, is(repoOnline));
    } else {
      fail("Response should match nested record pattern");
    }
  }
}