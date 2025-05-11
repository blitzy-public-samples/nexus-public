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
package org.sonatype.nexus.audit.internal;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.ClientInfo;
import org.sonatype.nexus.security.ClientInfoProvider;

import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link InitiatorProviderImpl}.
 * <p>
 * Updated for Java 21 compatibility using JUnit Jupiter and Mockito 4.11.0+.
 */
@ExtendWith(MockitoExtension.class)
class InitiatorProviderImplTest
    extends TestSupport
{
  @Mock
  private ClientInfoProvider clientInfoProvider;

  @InjectMocks
  private InitiatorProviderImpl underTest;

  @AfterEach
  void tearDown() {
    reset();
  }

  @Test
  @DisplayName("Should prefer client info when available")
  void preferClientInfoWhenAvailable() {
    ClientInfo clientInfo = ClientInfo.builder().userId("foo").remoteIP("1.2.3.4").userAgent("bar").build();
    when(clientInfoProvider.getCurrentThreadClientInfo()).thenReturn(clientInfo);

    String result = underTest.get();
    assertThat(result, equalTo("foo/1.2.3.4"));
  }

  @Test
  @DisplayName("Should use subject when client info is missing")
  void useSubjectWhenClientInfoMissing() {
    ThreadContext.bind(subject("foo"));
    String result = underTest.get();
    assertThat(result, equalTo("foo"));
  }

  private static void reset() {
    ThreadContext.unbindSubject();
    ThreadContext.unbindSecurityManager();
  }

  private static Subject subject(final Object principal) {
    Subject subject = mock(Subject.class);
    when(subject.getPrincipal()).thenReturn(principal);
    return subject;
  }
}