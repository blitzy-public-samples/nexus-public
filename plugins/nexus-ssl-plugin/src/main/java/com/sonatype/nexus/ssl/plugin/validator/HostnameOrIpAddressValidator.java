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
package com.sonatype.nexus.ssl.plugin.validator;

import javax.validation.ConstraintValidatorContext;

import org.sonatype.nexus.validation.ConstraintValidatorSupport;

import com.google.common.net.InetAddresses;
import com.google.common.net.InternetDomainName;

/**
 * Hostname or IP address validator.
 * <p>
 * Validates that a string is either a valid Internet domain name or a valid IP address.
 * Provides detailed error messages using Java 21 Pattern Matching for switch and String Templates.
 *
 * @since 3.36
 */
public class HostnameOrIpAddressValidator
    extends ConstraintValidatorSupport<HostnameOrIpAddress, String>
{
  @Override
  public boolean isValid(final String value, final ConstraintValidatorContext context) {
    // If the value is valid, return true immediately (maintaining backward compatibility)
    if (InternetDomainName.isValid(value) || InetAddresses.isInetAddress(value)) {
      return true;
    }
    
    // If validation fails, provide a detailed error message using pattern matching
    String errorMessage = getValidationErrorMessage(value);
    
    // Disable the default error message
    context.disableDefaultConstraintViolation();
    
    // Add a custom error message
    context.buildConstraintViolationWithTemplate(errorMessage)
           .addConstraintViolation();
    
    return false;
  }
  
  /**
   * Determines the specific validation error using Pattern Matching for switch.
   * 
   * @param value The value to validate
   * @return A detailed error message
   */
  private String getValidationErrorMessage(final String value) {
    return switch (value) {
      case null -> "Hostname or IP address cannot be null";
      case "" -> "Hostname or IP address cannot be empty";
      case String s when s.contains(" ") -> STR."\{s} is invalid: Hostname or IP address cannot contain spaces";
      case String s when s.startsWith("-") || s.endsWith("-") -> 
          STR."\{s} is invalid: Hostname cannot start or end with a hyphen";
      case String s when s.contains(":") && !isValidIpv6Format(s) -> 
          STR."\{s} is invalid: Not a valid IPv6 address format";
      case String s when containsIpv4Characters(s) && !InetAddresses.isInetAddress(s) -> 
          STR."\{s} is invalid: Not a valid IPv4 address format";
      case String s when !InternetDomainName.isValid(s) -> 
          STR."\{s} is invalid: Not a valid hostname format";
      default -> "Invalid hostname or IP address format";
    };
  }
  
  /**
   * Checks if a string contains only characters valid in an IPv4 address.
   * 
   * @param value The string to check
   * @return true if the string contains only digits and dots
   */
  private boolean containsIpv4Characters(final String value) {
    return value.matches("^[0-9.]+$");
  }
  
  /**
   * Performs basic validation of IPv6 format.
   * 
   * @param value The string to check
   * @return true if the string has a potentially valid IPv6 format
   */
  private boolean isValidIpv6Format(final String value) {
    // Basic check for IPv6 format - at least has colons and valid hex characters
    return value.contains(":") && value.matches("^[0-9a-fA-F:]+$");
  }
}