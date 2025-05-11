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
package org.sonatype.nexus.repository.httpbridge.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link HttpRequestPayloadAdapter}.
 */
public class HttpRequestPayloadAdapterTest
    extends TestSupport
{
  private static final String TEST_CONTENT_TYPE = "text/plain";
  private static final long TEST_CONTENT_LENGTH = 42L;
  private static final String TEST_CONTENT = "Test content";

  @Mock
  private HttpServletRequest request;

  @Mock
  private ServletInputStream servletInputStream;

  private HttpRequestPayloadAdapter underTest;

  @Before
  public void setUp() throws Exception {
    // Setup mock request with test data
    when(request.getContentType()).thenReturn(TEST_CONTENT_TYPE);
    when(request.getContentLength()).thenReturn((int) TEST_CONTENT_LENGTH);
    
    // Create a ServletInputStream that returns our test content
    ByteArrayInputStream byteStream = new ByteArrayInputStream(TEST_CONTENT.getBytes());
    when(request.getInputStream()).thenReturn(new ServletInputStream() {
      @Override
      public int read() throws IOException {
        return byteStream.read();
      }
    });

    // Create the adapter under test
    underTest = new HttpRequestPayloadAdapter(request);
  }

  @Test
  public void testGetContentType() {
    assertThat(underTest.getContentType(), is(TEST_CONTENT_TYPE));
  }

  @Test
  public void testGetSize() {
    assertThat(underTest.getSize(), is(TEST_CONTENT_LENGTH));
  }

  @Test
  public void testOpenInputStream() throws IOException {
    InputStream inputStream = underTest.openInputStream();
    assertThat(inputStream, is(notNullValue()));
    // Further testing of the stream would require more complex mocking
    // or integration testing with actual servlet containers
  }

  @Test
  public void testToString() {
    String toString = underTest.toString();
    assertThat(toString.contains(TEST_CONTENT_TYPE), is(true));
    assertThat(toString.contains(String.valueOf(TEST_CONTENT_LENGTH)), is(true));
  }

  // No additional mock classes needed
}