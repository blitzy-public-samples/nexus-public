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

import java.net.URI;
import java.net.URISyntaxException;

/**
 * URL field with Java 21 compatible validation.
 *
 * @since 3.2
 */
public class UrlFormField
    extends StringTextFormField
{
  /**
   * Default URL validation regex pattern that can be used for basic URL format validation.
   * 
   * @since 3.60
   */
  public static final String DEFAULT_URL_VALIDATION_REGEX = 
      "^(https?|ftp|file)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]";

  public UrlFormField(String id, String label, String helpText, boolean required, String regexValidation) {
    super(id, label, helpText, required, regexValidation);
  }

  public UrlFormField(String id, String label, String helpText, boolean required) {
    super(id, label, helpText, required);
  }

  public UrlFormField(String id) {
    super(id);
  }

  /**
   * Returns the field type.
   */
  @Override
  public String getType() {
    return "url";
  }
  
  /**
   * Validates if the provided string is a valid URL using Java's URI class.
   * This method is compatible with Java 21's recommended approach for URL validation.
   *
   * @param url the URL string to validate
   * @return true if the URL is valid, false otherwise
   * @since 3.60
   */
  public static boolean isValidUrl(String url) {
    if (url == null || url.isEmpty()) {
      return false;
    }
    
    try {
      URI uri = new URI(url);
      String scheme = uri.getScheme();
      
      // Check if scheme is present and is a common URL scheme
      if (scheme == null || !(scheme.equals("http") || scheme.equals("https") || 
          scheme.equals("ftp") || scheme.equals("file"))) {
        return false;
      }
      
      // For server-based URIs, validate the authority component
      if (uri.getHost() == null) {
        return false;
      }
      
      return true;
    } 
    catch (URISyntaxException e) {
      return false;
    }
  }
  
  /**
   * Creates a UrlFormField with the default URL validation regex pattern.
   *
   * @param id the field ID
   * @param label the field label
   * @param helpText the help text
   * @param required whether the field is required
   * @return a new UrlFormField with default URL validation
   * @since 3.60
   */
  public static UrlFormField withDefaultValidation(String id, String label, String helpText, boolean required) {
    return new UrlFormField(id, label, helpText, required, DEFAULT_URL_VALIDATION_REGEX);
  }
}
