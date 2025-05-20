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
import org.sonatype.nexus.test.Java21TestGroup;

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
@Category(Java21TestGroup.class)
public class RoutingRuleHandlerTest
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
  public void setup() throws Exception {
    underTest = new RoutingRuleHandler(routingRuleHelper);

    when(request.getPath()).thenReturn(SOME_PATH);
    when(context.getRequest()).thenReturn(request);
    when(request.getParameters()).thenReturn(new Parameters());
    when(context.proceed()).thenReturn(contextResponse);
    when(context.getRepository()).thenReturn(repository);
  }

  @Test
  void should_allow_when_rule_permits() throws Exception {
    when(routingRuleHelper.isAllowed(nullable(Repository.class), eq(SOME_PATH))).thenReturn(true);

    Response response = underTest.handle(context);
    assertEquals(contextResponse, response);
    verify(context).proceed();
  }

  @Test
  void should_block_when_rule_denies() throws Exception {
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
  void should_include_parameters_in_path_check() throws Exception {
    ListMultimap<String, String> params = LinkedListMultimap.create();
    params.put("foo", "bar");
    params.put("bar", "foo");
    when(request.getParameters()).thenReturn(new Parameters(params));
    when(routingRuleHelper.isAllowed(nullable(Repository.class), eq("/some/path?foo=bar&bar=foo"))).thenReturn(true);

    Response response = underTest.handle(context);
    assertEquals(contextResponse, response);
    verify(context).proceed();
    verify(routingRuleHelper).isAllowed(nullable(Repository.class), eq("/some/path?foo=bar&bar=foo"));
  }
}