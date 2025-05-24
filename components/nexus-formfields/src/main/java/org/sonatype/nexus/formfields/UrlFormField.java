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
import java.net.URL;
import java.net.MalformedURLException;

/**
 * URL field with enhanced Java 21 validation capabilities.
 *
 * @since 3.2
 */
public class UrlFormField
    extends StringTextFormField
{
  public UrlFormField(String id, String label, String helpText, boolean required, String regexValidation) {
    super(id, label, helpText, required, regexValidation);
  }

  public UrlFormField(String id, String label, String helpText, boolean required) {
    super(id, label, helpText, required);
  }

  public UrlFormField(String id) {
    super(id);
  }

  public String getType() {
    return "url";
  }
  
  /**
   * Validates if the input is a valid URL using Java 21 recommended approach.
   * Uses URI for parsing and validation, then converts to URL if needed.
   *
   * @param input The URL string to validate
   * @return true if the input is a valid URL, false otherwise
   */
  @Override
  public boolean isValidInput(final String input) {
    // First check regex validation from parent class
    if (!super.isValidInput(input)) {
      return false;
    }
    
    if (input == null || input.isEmpty()) {
      return !isRequired();
    }
    
    try {
      // Use URI for parsing and validation as recommended in Java 21
      URI uri = new URI(input);
      
      // Validate that the URI has a scheme
      if (uri.getScheme() == null) {
        return false;
      }
      
      // If the URI has an authority component, validate it can be parsed as server-based
      if (uri.getAuthority() != null) {
        try {
          uri.parseServerAuthority();
        } catch (URISyntaxException e) {
          return false;
        }
      }
      
      // Additional validation by attempting to convert to URL
      uri.toURL();
      return true;
    } catch (URISyntaxException | MalformedURLException e) {
      return false;
    }
  }
  
  /**
   * Validates the URL and returns a detailed validation message using String Templates.
   * 
   * @param input The URL string to validate
   * @return Validation message or null if valid
   */
  public String validateUrl(final String input) {
    if (input == null || input.isEmpty()) {
      return isRequired() ? "URL is required" : null;
    }
    
    try {
      // Use URI for parsing and validation as recommended in Java 21
      URI uri = new URI(input);
      
      // Validate that the URI has a scheme
      if (uri.getScheme() == null) {
        return STR."Invalid URL: missing scheme (protocol) in \{input}";
      }
      
      // If the URI has an authority component, validate it can be parsed as server-based
      if (uri.getAuthority() != null) {
        try {
          uri.parseServerAuthority();
        } catch (URISyntaxException e) {
          return STR."Invalid URL authority component in \{input}: \{e.getMessage()}";
        }
      }
      
      // Additional validation by attempting to convert to URL
      uri.toURL();
      return null;
    } catch (URISyntaxException e) {
      return STR."Invalid URL syntax in \{input}: \{e.getMessage()}";
    } catch (MalformedURLException e) {
      return STR."Malformed URL in \{input}: \{e.getMessage()}";
    }
  }
  
  /**
   * Creates a new UrlFormField with the specified initial URL value.
   *
   * @param initialValue The initial URL value
   * @return This field instance for fluent API usage
   */
  public UrlFormField withInitialUrl(final String initialValue) {
    setInitialValue(initialValue);
    return this;
  }
}