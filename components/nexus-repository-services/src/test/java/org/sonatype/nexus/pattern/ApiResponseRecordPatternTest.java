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

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.java21.Java21TestGroup;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for Java 21 record pattern matching with API responses.
 * 
 * This test validates that record patterns can be used to destructure and access
 * components of API response records without explicit casting or accessor methods.
 */
@Category(Java21TestGroup.class)
public class ApiResponseRecordPatternTest
    extends TestSupport
{
  /**
   * Simple API response record for testing.
   * This simulates the refactored version of SimpleApiResponse as a record.
   */
  record SimpleApiResponse(int status, String message, Object data) {
    // Static factory methods similar to the original SimpleApiResponse
    static SimpleApiResponse ok(String message) {
      return new SimpleApiResponse(200, message, null);
    }
    
    static SimpleApiResponse ok(String message, Object data) {
      return new SimpleApiResponse(200, message, data);
    }
    
    static SimpleApiResponse notFound(String message) {
      return new SimpleApiResponse(404, message, null);
    }
    
    static SimpleApiResponse badRequest(String message) {
      return new SimpleApiResponse(400, message, null);
    }
    
    static SimpleApiResponse unauthorized(String message) {
      return new SimpleApiResponse(401, message, null);
    }
  }
  
  /**
   * Nested record for testing nested pattern matching.
   */
  record RepositoryInfo(String name, String format, String type) {}
  
  /**
   * Component record for testing nested pattern matching.
   */
  record ComponentInfo(String group, String name, String version) {}
  
  /**
   * Test basic record pattern matching with SimpleApiResponse.
   * 
   * This test demonstrates how to use record patterns to directly access
   * the components of a SimpleApiResponse record without using accessor methods.
   */
  @Test
  public void testBasicRecordPatternMatching() {
    Object response = SimpleApiResponse.ok("Success");
    
    // Traditional approach using instanceof and accessor methods
    if (response instanceof SimpleApiResponse) {
      SimpleApiResponse apiResponse = (SimpleApiResponse) response;
      assertThat(apiResponse.status(), is(200));
      assertThat(apiResponse.message(), is("Success"));
      assertThat(apiResponse.data(), is(null));
    }
    
    // Using Java 21 record pattern matching
    if (response instanceof SimpleApiResponse(int status, String message, Object data)) {
      assertThat(status, is(200));
      assertThat(message, is("Success"));
      assertThat(data, is(null));
    } else {
      // This should not happen
      throw new AssertionError("Record pattern did not match");
    }
  }
  
  /**
   * Test record pattern matching with non-null data field.
   */
  @Test
  public void testRecordPatternMatchingWithData() {
    RepositoryInfo repoInfo = new RepositoryInfo("maven-central", "maven2", "proxy");
    Object response = SimpleApiResponse.ok("Repository found", repoInfo);
    
    // Using Java 21 record pattern matching
    if (response instanceof SimpleApiResponse(int status, String message, Object data)) {
      assertThat(status, is(200));
      assertThat(message, is("Repository found"));
      assertThat(data, is(notNullValue()));
      
      // We can further check the data type and access its components
      if (data instanceof RepositoryInfo(String name, String format, String type)) {
        assertThat(name, is("maven-central"));
        assertThat(format, is("maven2"));
        assertThat(type, is("proxy"));
      } else {
        throw new AssertionError("Nested record pattern did not match");
      }
    } else {
      throw new AssertionError("Record pattern did not match");
    }
  }
  
  /**
   * Test nested record pattern matching in a single step.
   * 
   * This test demonstrates how to use nested record patterns to directly access
   * the components of nested records in a single pattern matching step.
   */
  @Test
  public void testNestedRecordPatternMatching() {
    RepositoryInfo repoInfo = new RepositoryInfo("maven-central", "maven2", "proxy");
    Object response = SimpleApiResponse.ok("Repository found", repoInfo);
    
    // Using Java 21 nested record pattern matching
    if (response instanceof SimpleApiResponse(int status, String message, RepositoryInfo(String name, String format, String type))) {
      assertThat(status, is(200));
      assertThat(message, is("Repository found"));
      assertThat(name, is("maven-central"));
      assertThat(format, is("maven2"));
      assertThat(type, is("proxy"));
    } else {
      throw new AssertionError("Nested record pattern did not match");
    }
  }
  
  /**
   * Test record pattern matching with guard conditions.
   * 
   * This test demonstrates how to use pattern matching with guard conditions
   * to conditionally match records based on their component values.
   */
  @Test
  public void testRecordPatternMatchingWithGuardConditions() {
    Object[] responses = {
        SimpleApiResponse.ok("Success"),
        SimpleApiResponse.notFound("Not found"),
        SimpleApiResponse.badRequest("Bad request"),
        SimpleApiResponse.unauthorized("Unauthorized")
    };
    
    int successCount = 0;
    int errorCount = 0;
    
    for (Object response : responses) {
      // Using pattern matching with guard conditions
      if (response instanceof SimpleApiResponse(int status, String message, Object data) && status >= 200 && status < 300) {
        // Success response
        successCount++;
        assertThat(message, is("Success"));
      } else if (response instanceof SimpleApiResponse(int status, String message, Object data) && status >= 400) {
        // Error response
        errorCount++;
        assertThat(status, is(equalTo(404)) || equalTo(400) || equalTo(401));
      }
    }
    
    assertThat(successCount, is(1));
    assertThat(errorCount, is(3));
  }
  
  /**
   * Test record pattern matching in switch expressions.
   * 
   * This test demonstrates how to use record patterns in switch expressions
   * to handle different types of API responses.
   */
  @Test
  public void testRecordPatternMatchingInSwitch() {
    Object[] responses = {
        SimpleApiResponse.ok("Success"),
        SimpleApiResponse.notFound("Not found"),
        SimpleApiResponse.badRequest("Bad request"),
        SimpleApiResponse.unauthorized("Unauthorized"),
        new ComponentInfo("org.example", "example-lib", "1.0.0")
    };
    
    for (Object response : responses) {
      String result = switch (response) {
        case SimpleApiResponse(int status, String message, Object data) when status == 200 ->
            "Success: " + message;
        case SimpleApiResponse(int status, String message, Object data) when status == 404 ->
            "Not Found: " + message;
        case SimpleApiResponse(int status, String message, Object data) when status == 400 ->
            "Bad Request: " + message;
        case SimpleApiResponse(int status, String message, Object data) when status == 401 ->
            "Unauthorized: " + message;
        case ComponentInfo(String group, String name, String version) ->
            "Component: " + group + ":" + name + ":" + version;
        default ->
            "Unknown response type";
      };
      
      // Verify the switch expression result
      if (response instanceof SimpleApiResponse(int status, String message, Object data)) {
        switch (status) {
          case 200 -> assertThat(result, is("Success: Success"));
          case 404 -> assertThat(result, is("Not Found: Not found"));
          case 400 -> assertThat(result, is("Bad Request: Bad request"));
          case 401 -> assertThat(result, is("Unauthorized: Unauthorized"));
        }
      } else if (response instanceof ComponentInfo) {
        assertThat(result, is("Component: org.example:example-lib:1.0.0"));
      }
    }
  }
  
  /**
   * Test record pattern matching with var for type inference.
   * 
   * This test demonstrates how to use 'var' in record patterns to let
   * the compiler infer the types of pattern variables.
   */
  @Test
  public void testRecordPatternMatchingWithVarTypeInference() {
    ComponentInfo componentInfo = new ComponentInfo("org.example", "example-lib", "1.0.0");
    Object response = SimpleApiResponse.ok("Component found", componentInfo);
    
    // Using 'var' for type inference in record patterns
    if (response instanceof SimpleApiResponse(var status, var message, var data)) {
      assertThat(status, is(200));
      assertThat(message, is("Component found"));
      assertThat(data, is(notNullValue()));
      
      // We can further check the data type and access its components with 'var'
      if (data instanceof ComponentInfo(var group, var name, var version)) {
        assertThat(group, is("org.example"));
        assertThat(name, is("example-lib"));
        assertThat(version, is("1.0.0"));
      }
    }
  }
  
  /**
   * Test comparison between traditional object access and record pattern matching.
   * 
   * This test demonstrates the difference in code clarity and conciseness between
   * traditional object access and record pattern matching.
   */
  @Test
  public void testComparisonWithTraditionalAccess() {
    ComponentInfo componentInfo = new ComponentInfo("org.example", "example-lib", "1.0.0");
    Object response = SimpleApiResponse.ok("Component found", componentInfo);
    
    // Traditional approach with multiple casts and accessor methods
    if (response instanceof SimpleApiResponse) {
      SimpleApiResponse apiResponse = (SimpleApiResponse) response;
      assertThat(apiResponse.status(), is(200));
      assertThat(apiResponse.message(), is("Component found"));
      
      if (apiResponse.data() instanceof ComponentInfo) {
        ComponentInfo component = (ComponentInfo) apiResponse.data();
        assertThat(component.group(), is("org.example"));
        assertThat(component.name(), is("example-lib"));
        assertThat(component.version(), is("1.0.0"));
      }
    }
    
    // Record pattern matching approach - more concise and type-safe
    if (response instanceof SimpleApiResponse(int status, String message, ComponentInfo(String group, String name, String version))) {
      assertThat(status, is(200));
      assertThat(message, is("Component found"));
      assertThat(group, is("org.example"));
      assertThat(name, is("example-lib"));
      assertThat(version, is("1.0.0"));
    }
  }
}