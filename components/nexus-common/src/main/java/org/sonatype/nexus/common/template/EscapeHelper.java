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
package org.sonatype.nexus.common.template;

import java.util.stream.Stream;

import org.sonatype.nexus.common.encoding.EncodingUtil;

import org.apache.commons.lang.StringEscapeUtils;

import static java.lang.StringTemplate.STR;
import static java.util.stream.Collectors.joining;

/**
 * Helper to escape values.
 *
 * @since 3.0
 */
@TemplateAccessible
public class EscapeHelper
{
  public String html(final String value) {
    return StringEscapeUtils.escapeHtml(value);
  }

  public String html(final Object value) {
    return html(String.valueOf(value));
  }

  public String url(final String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    else {
      return EncodingUtil.urlEncode(value);
    }
  }

  public String url(final Object value) {
    return url(String.valueOf(value));
  }

  public String xml(final String value) {
    return StringEscapeUtils.escapeXml(value);
  }

  public String xml(final Object value) {
    return xml(String.valueOf(value));
  }

  public String uri(final String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    else {
      String encoded = url(value);
      // Using String Template to replace special characters with their URI-friendly equivalents
      return STR."""
             \{encoded.replace("+", "%20")
                    .replace("%21", "!")
                    .replace("%27", "'")
                    .replace("%28", "(")
                    .replace("%29", ")")
                    .replace("%7E", "~")}
             """;
    }
  }

  private String transform(final String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    else {
      // Using String Template for character replacements
      return STR."""
             \{value.replace("%", "%25")
                   .replace(":", "%3A")
                   .replace(" ", "%20")}
             """;
    }
  }

  public String uri(final Object value) {
    return uri(String.valueOf(value));
  }

  public String uriSegments(final String value) {
    // Using String Template with map operation for more concise code
    return STR."\{Stream.of(value.split("/")).map(this::transform).collect(joining("/"))}";
  }

  /**
   * Strip java el start token from a string
   * 
   * @since 3.14
   */
  public String stripJavaEl(final String value) {
    if (value != null) {
      // Using String Template with replaceAll for regex pattern replacement
      String result = value.replaceAll("\\$+\\{", "{");
      return STR."\{result.replaceAll("\\$+\\\\A\\{", "{")}";
    }
    return null;
  }
}