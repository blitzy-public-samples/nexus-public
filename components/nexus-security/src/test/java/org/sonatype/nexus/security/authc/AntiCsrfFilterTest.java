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
package org.sonatype.nexus.security.authc;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.sonatype.goodies.testsupport.TestSupport;

import org.apache.shiro.subject.Subject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class AntiCsrfFilterTest
  extends TestSupport
{
  private AntiCsrfFilter underTest;

  @Mock
  private AntiCsrfHelper antiCrsfHelper;

  @Mock
  private PrintWriter printWriter;

  @Mock
  HttpServletRequest httpServletRequest;

  @Mock
  HttpServletResponse httpServletResponse;

  @BeforeEach
  void setup() throws IOException {
    underTest = new AntiCsrfFilter(antiCrsfHelper) {
      @Override
      protected Subject getSubject(final ServletRequest request, final ServletResponse response) {
        return null;
      }
    };
    when(httpServletResponse.getWriter()).thenReturn(printWriter);
  }

  @Test
  void testOnAccessDenied() throws IOException {
    assertFalse(underTest.onAccessDenied(httpServletRequest, httpServletResponse));
    verify(httpServletResponse).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verify(httpServletResponse).setContentType("text/plain");
    verify(printWriter).print(AntiCsrfHelper.ERROR_MESSAGE_TOKEN_MISMATCH);
  }

  @Test
  void testIsEnabled() {
    when(antiCrsfHelper.isEnabled()).thenReturn(true);
    assertTrue(underTest.isEnabled());

    when(antiCrsfHelper.isEnabled()).thenReturn(false);
    assertFalse(underTest.isEnabled());
  }

  @Test
  void testIsAccessAllowed() {
    when(antiCrsfHelper.isAccessAllowed(httpServletRequest)).thenReturn(true);
    assertTrue(underTest.isAccessAllowed(httpServletRequest, httpServletResponse, null));

    when(antiCrsfHelper.isAccessAllowed(httpServletRequest)).thenReturn(false);
    assertFalse(underTest.isAccessAllowed(httpServletRequest, httpServletResponse, null));
  }
  
  @Test
  void testCsrfProtectionWithSameSiteCookieAttributes() throws IOException {
    // Setup a request with SameSite cookie attributes
    Cookie csrfCookie = new Cookie("XSRF-TOKEN", "valid-token");
    csrfCookie.setSecure(true);
    csrfCookie.setHttpOnly(true);
    csrfCookie.setAttribute("SameSite", "Strict"); // Modern browsers support SameSite attribute
    
    when(httpServletRequest.getCookies()).thenReturn(new Cookie[]{csrfCookie});
    when(antiCrsfHelper.isAccessAllowed(httpServletRequest)).thenReturn(true);
    
    // Test that the request is allowed with proper SameSite cookie
    assertTrue(underTest.isAccessAllowed(httpServletRequest, httpServletResponse, null));
    
    // Verify that the helper was called to check access
    verify(antiCrsfHelper).isAccessAllowed(httpServletRequest);
  }
  
  @Test
  void testVirtualThreadCompatibility() throws Exception {
    // Create a virtual thread executor (Java 21 feature)
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      AtomicBoolean result = new AtomicBoolean(false);
      
      // Configure the mock to return true for isAccessAllowed
      when(antiCrsfHelper.isAccessAllowed(httpServletRequest)).thenReturn(true);
      
      // Execute the filter in a virtual thread
      executor.submit(() -> {
        try {
          result.set(underTest.isAccessAllowed(httpServletRequest, httpServletResponse, null));
        } 
        catch (Exception e) {
          log.error("Error in virtual thread execution", e);
        }
      }).get(5, TimeUnit.SECONDS); // Wait for completion with timeout
      
      // Verify the filter works correctly in a virtual thread
      assertTrue(result.get(), "Filter should work correctly in a virtual thread");
    }
  }
}