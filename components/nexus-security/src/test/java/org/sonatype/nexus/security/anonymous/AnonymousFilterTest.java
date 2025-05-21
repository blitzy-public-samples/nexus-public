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
package org.sonatype.nexus.security.anonymous;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.inject.Provider;
import javax.servlet.http.HttpServletRequest;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.security.subject.FakeAlmightySubject;

import org.apache.shiro.util.ThreadContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class AnonymousFilterTest
    extends TestSupport
{
  private AnonymousFilter underTest;

  private static String ANONYMOUS_USER = "anonymousUser";

  @Mock
  private Provider<AnonymousManager> anonymousManagerProvider;

  @Mock
  private Provider<EventManager> eventManager;

  @Mock
  private HttpServletRequest request;

  @Mock
  private AnonymousManager anonymousManager;

  @BeforeEach
  void setup() {
    underTest = new AnonymousFilter(anonymousManagerProvider, eventManager);
    ThreadContext.bind(FakeAlmightySubject.forUserId(ANONYMOUS_USER));
  }

  @Test
  void buildSubjectWhenIsAnonymousUser() throws Exception {
    when(eventManager.get()).thenReturn(mock(EventManager.class));
    when(anonymousManagerProvider.get()).thenReturn(anonymousManager);
    when(anonymousManager.isEnabled()).thenReturn(true);
    when(anonymousManager.getConfiguration()).thenReturn(mock(AnonymousConfiguration.class));
    when(anonymousManager.getConfiguration().getUserId()).thenReturn(ANONYMOUS_USER);

    underTest.preHandle(request, null);

    verify(anonymousManager).buildSubject();
  }

  @Test
  void buildSubjectWithHttp2Protocol() throws Exception {
    when(eventManager.get()).thenReturn(mock(EventManager.class));
    when(anonymousManagerProvider.get()).thenReturn(anonymousManager);
    when(anonymousManager.isEnabled()).thenReturn(true);
    when(anonymousManager.getConfiguration()).thenReturn(mock(AnonymousConfiguration.class));
    when(anonymousManager.getConfiguration().getUserId()).thenReturn(ANONYMOUS_USER);
    when(request.getProtocol()).thenReturn("HTTP/2.0");

    underTest.preHandle(request, null);

    verify(anonymousManager).buildSubject();
  }

  @Test
  void buildSubjectWithHttp3Protocol() throws Exception {
    when(eventManager.get()).thenReturn(mock(EventManager.class));
    when(anonymousManagerProvider.get()).thenReturn(anonymousManager);
    when(anonymousManager.isEnabled()).thenReturn(true);
    when(anonymousManager.getConfiguration()).thenReturn(mock(AnonymousConfiguration.class));
    when(anonymousManager.getConfiguration().getUserId()).thenReturn(ANONYMOUS_USER);
    when(request.getProtocol()).thenReturn("HTTP/3.0");

    underTest.preHandle(request, null);

    verify(anonymousManager).buildSubject();
  }

  @Test
  void buildSubjectWithVirtualThreads() throws Exception {
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      executor.submit(() -> {
        try {
          when(eventManager.get()).thenReturn(mock(EventManager.class));
          when(anonymousManagerProvider.get()).thenReturn(anonymousManager);
          when(anonymousManager.isEnabled()).thenReturn(true);
          when(anonymousManager.getConfiguration()).thenReturn(mock(AnonymousConfiguration.class));
          when(anonymousManager.getConfiguration().getUserId()).thenReturn(ANONYMOUS_USER);
          
          underTest.preHandle(request, null);
          
          verify(anonymousManager).buildSubject();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }).get(5, TimeUnit.SECONDS); // Add timeout to prevent test hanging
    } finally {
      executor.shutdown();
    }
  }
}