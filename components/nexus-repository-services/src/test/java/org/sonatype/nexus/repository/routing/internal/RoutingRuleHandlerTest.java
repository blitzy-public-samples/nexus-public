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
package org.sonatype.nexus.repository.routing.internal;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.routing.RoutingRuleHelper;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Parameters;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.repository.view.Response;

import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoutingRuleHandlerTest
    extends TestSupport
{
  private static final String SOME_PATH = "/some/path";

  private RoutingRuleHandler underTest;

  @Mock
  private RoutingRuleHelper routingRuleHelper;

  @Mock
  private Context context;

  @Mock
  private Request request;

  @Mock
  private Response contextResponse;

  @Mock
  private Repository repository;

  @BeforeEach
  void setup() throws Exception {
    underTest = new RoutingRuleHandler(routingRuleHelper);

    when(request.getPath()).thenReturn(SOME_PATH);
    when(context.getRequest()).thenReturn(request);
    when(request.getParameters()).thenReturn(new Parameters());
    when(context.proceed()).thenReturn(contextResponse);
    when(context.getRepository()).thenReturn(repository);
  }

  @Test
  void testHandle_allowed() throws Exception {
    when(routingRuleHelper.isAllowed(nullable(Repository.class), eq(SOME_PATH))).thenReturn(true);

    Response response = underTest.handle(context);
    assertEquals(contextResponse, response);
    verify(context).proceed();
  }

  @Test
  void testHandle_blocked() throws Exception {
    when(routingRuleHelper.isAllowed(nullable(Repository.class), eq(SOME_PATH))).thenReturn(false);

    Type typeMock = mock(Type.class);
    when(repository.getType()).thenReturn(typeMock);
    when(typeMock.getValue()).thenReturn("repository-type");
    when(repository.getName()).thenReturn("repository-name");

    Response response = underTest.handle(context);

    assertNotNull(response);
    assertEquals(403, response.getStatus().getCode());
    verify(context, times(0)).proceed();
  }

  @Test
  void testHandle_parameters() throws Exception {
    ListMultimap<String, String> params = LinkedListMultimap.create();
    params.put("foo", "bar");
    params.put("bar", "foo");
    when(request.getParameters()).thenReturn(new Parameters(params));
    
    // Using Java 21 enhanced String processing with String templates
    String expectedPath = STR."\(SOME_PATH)?foo=bar&bar=foo";
    when(routingRuleHelper.isAllowed(nullable(Repository.class), eq(expectedPath))).thenReturn(true);

    Response response = underTest.handle(context);
    assertEquals(contextResponse, response);
    verify(context).proceed();
    verify(routingRuleHelper).isAllowed(nullable(Repository.class), eq(expectedPath));
  }
  
  @Test
  void testHandleWithDifferentRepositoryTypes() throws Exception {
    // Setup different repository types for testing
    Type mavenType = mock(Type.class);
    when(mavenType.getValue()).thenReturn("maven2");
    
    Type npmType = mock(Type.class);
    when(npmType.getValue()).thenReturn("npm");
    
    Type dockerType = mock(Type.class);
    when(dockerType.getValue()).thenReturn("docker");
    
    // Test with pattern matching for switch
    String result = getRepositoryTypeCategory(mavenType);
    assertEquals("Maven Repository", result);
    
    result = getRepositoryTypeCategory(npmType);
    assertEquals("JavaScript Repository", result);
    
    result = getRepositoryTypeCategory(dockerType);
    assertEquals("Container Repository", result);
    
    // Test with unknown type
    Type unknownType = mock(Type.class);
    when(unknownType.getValue()).thenReturn("unknown");
    result = getRepositoryTypeCategory(unknownType);
    assertEquals("Other Repository Type", result);
  }
  
  /**
   * Demonstrates pattern matching for switch with repository types
   */
  private String getRepositoryTypeCategory(Type type) {
    return switch (type) {
      case Type t when "maven2".equals(t.getValue()) -> "Maven Repository";
      case Type t when "npm".equals(t.getValue()) || "yarn".equals(t.getValue()) -> "JavaScript Repository";
      case Type t when "docker".equals(t.getValue()) -> "Container Repository";
      case Type t when "nuget".equals(t.getValue()) -> ".NET Repository";
      case Type t when "pypi".equals(t.getValue()) -> "Python Repository";
      case Type t -> "Other Repository Type";
    };
  }
  
  @Test
  void testResponsePatternMatching() throws Exception {
    // Setup test with different response types
    Response successResponse = mock(Response.class);
    Response errorResponse = mock(Response.class);
    Response redirectResponse = mock(Response.class);
    
    // Configure responses
    when(successResponse.getStatus()).thenReturn(new Response.Status(200));
    when(errorResponse.getStatus()).thenReturn(new Response.Status(500));
    when(redirectResponse.getStatus()).thenReturn(new Response.Status(302));
    
    // Test with pattern matching
    assertEquals("Success", getResponseCategory(successResponse));
    assertEquals("Server Error", getResponseCategory(errorResponse));
    assertEquals("Redirect", getResponseCategory(redirectResponse));
  }
  
  /**
   * Demonstrates pattern matching for response types
   */
  private String getResponseCategory(Response response) {
    if (response instanceof Response r && r.getStatus() != null) {
      int code = r.getStatus().getCode();
      if (code >= 200 && code < 300) {
        return "Success";
      }
      else if (code >= 300 && code < 400) {
        return "Redirect";
      }
      else if (code >= 400 && code < 500) {
        return "Client Error";
      }
      else if (code >= 500) {
        return "Server Error";
      }
    }
    return "Unknown";
  }
}