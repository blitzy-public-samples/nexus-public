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
package org.sonatype.nexus.security.token;

import javax.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.google.common.net.HttpHeaders.AUTHORIZATION;
import static java.util.UUID.randomUUID;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.hamcrest.core.IsNull.nullValue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class BearerTokenTest
{
  private static final String TOKEN = randomUUID().toString();

  private static final String FORMAT = "Format";

  private static final String BEARER_TOKEN = "Bearer " + FORMAT + "." + TOKEN;

  @Mock
  private HttpServletRequest request;

  private BearerToken underTest;

  @BeforeEach
  public void setup() {
    when(request.getHeader(AUTHORIZATION)).thenReturn(BEARER_TOKEN);
    underTest = new BearerToken(FORMAT);
  }

  @Test
  public void extractToken() {
    String token = underTest.extract(request);
    assertThat(token, is(equalTo(TOKEN)));
  }

  @Test
  public void nullIfNotBearer() {
    when(request.getHeader(AUTHORIZATION)).thenReturn("Basic " + TOKEN);
    assertThat(underTest.extract(request), is(nullValue()));
  }

  @Test
  public void nullIfTokenNotPresent() {
    when(request.getHeader(AUTHORIZATION)).thenReturn("Bearer ");
    assertThat(underTest.extract(request), is(nullValue()));
  }

  @Test
  public void nullIfHeaderNotPresent() {
    when(request.getHeader(AUTHORIZATION)).thenReturn(null);
    assertThat(underTest.extract(request), is(nullValue()));
  }

  @Test
  public void nullIfFormatDoesNotMatch() {
    when(request.getHeader(AUTHORIZATION)).thenReturn("Bearer NotFormat." + TOKEN);
    assertThat(underTest.extract(request), is(nullValue()));
  }
  
  @Test
  public void extractTokenInVirtualThread() throws Exception {
    Thread virtualThread = Thread.startVirtualThread(() -> {
      String token = underTest.extract(request);
      assertThat(token, is(equalTo(TOKEN)));
    });
    virtualThread.join();
  }
}