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
package org.sonatype.nexus.formfields;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for {@link UrlFormField}.
 *
 * @since 3.60
 */
public class UrlFormFieldTest
{
  @Test
  public void testBasicProperties() {
    UrlFormField field = new UrlFormField("test-id");
    assertThat(field.getId(), is("test-id"));
    assertThat(field.getType(), is("url"));
  }

  @Test
  public void testWithDefaultValidation() {
    UrlFormField field = UrlFormField.withDefaultValidation("test-id", "Test Label", "Test Help", true);
    assertThat(field.getId(), is("test-id"));
    assertThat(field.getLabel(), is("Test Label"));
    assertThat(field.getHelpText(), is("Test Help"));
    assertThat(field.isRequired(), is(true));
    assertThat(field.getRegexValidation(), is(notNullValue()));
    assertThat(field.getRegexValidation(), is(equalTo(UrlFormField.DEFAULT_URL_VALIDATION_REGEX)));
  }

  @Test
  public void testIsValidUrl_ValidUrls() {
    // Test valid URLs
    assertThat(UrlFormField.isValidUrl("http://example.com"), is(true));
    assertThat(UrlFormField.isValidUrl("https://example.com"), is(true));
    assertThat(UrlFormField.isValidUrl("http://example.com:8080"), is(true));
    assertThat(UrlFormField.isValidUrl("https://example.com/path"), is(true));
    assertThat(UrlFormField.isValidUrl("https://example.com/path?query=value"), is(true));
    assertThat(UrlFormField.isValidUrl("https://example.com/path?query=value#fragment"), is(true));
    assertThat(UrlFormField.isValidUrl("ftp://example.com"), is(true));
    assertThat(UrlFormField.isValidUrl("file:///path/to/file"), is(true));
  }

  @Test
  public void testIsValidUrl_InvalidUrls() {
    // Test invalid URLs
    assertThat(UrlFormField.isValidUrl(null), is(false));
    assertThat(UrlFormField.isValidUrl(""), is(false));
    assertThat(UrlFormField.isValidUrl("not a url"), is(false));
    assertThat(UrlFormField.isValidUrl("http://"), is(false));
    assertThat(UrlFormField.isValidUrl("http:///example.com"), is(false));
    assertThat(UrlFormField.isValidUrl("http:/example.com"), is(false));
    assertThat(UrlFormField.isValidUrl("example.com"), is(false)); // Missing scheme
    assertThat(UrlFormField.isValidUrl("javascript:alert('XSS')"), is(false)); // Invalid scheme
  }
}