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
package org.sonatype.nexus.repository.security;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.collect.AttributesMap;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Handler;
import org.sonatype.nexus.virtualthread.Java21TestGroup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Category(Java21TestGroup.class)
public class SecurityHandlerTest
    extends TestSupport
{
  private SecurityHandler underTest;

  @Mock
  private Context context;

  @Mock
  private Repository repository;

  @Mock
  private SecurityFacet securityFacet;

  @Mock
  private Handler loginsCounterHandler;

  private AttributesMap attributesMap;

  @BeforeEach
  public void setup() {
    underTest = new SecurityHandler(null);

    attributesMap = new AttributesMap();
    when(repository.facet(SecurityFacet.class)).thenReturn(securityFacet);
    when(context.getRepository()).thenReturn(repository);
    when(context.getAttributes()).thenReturn(attributesMap);
  }

  @Test
  public void shouldEnsurePermittedWhenHandling() throws Exception {
    underTest.handle(context);
    verify(securityFacet).ensurePermitted(any());
  }

  @Test
  public void shouldSkipPermissionCheckWhenAlreadyAuthorized() throws Exception {
    attributesMap.set(SecurityHandler.AUTHORIZED_KEY, true);
    underTest.handle(context);
    verify(securityFacet, never()).ensurePermitted(any());
  }

  @Test
  public void shouldNotInsertHandlerWhenLoginsCounterHandlerIsNull() throws Exception {
    underTest.handle(context);
    verify(context, never()).insertHandler(loginsCounterHandler);
  }

  @Test
  public void shouldInsertHandlerWhenLoginsCounterHandlerNonNull() throws Exception {
    underTest = new SecurityHandler(loginsCounterHandler);
    underTest.handle(context);
    verify(context, atMostOnce()).insertHandler(loginsCounterHandler);
  }
}