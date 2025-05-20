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
package org.sonatype.nexus.repository.search.sql;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.rest.ValidationErrorsException;

import org.apache.commons.lang3.tuple.Pair;

/**
 * Support class for SQL search validation
 */
public abstract class SqlSearchValidationSupport
    extends ComponentSupport
{
  private static final char ZERO_OR_MORE_CHARACTERS = '*';

  private static final char ANY_CHARACTER = '?';

  private static final int MIN_ALLOWED_SYMBOLS_TO_SEARCH = 3;

  /*
   * For SQL search we prohibit leading wildcards and less than 3 characters with wildcards for performance reasons.
   */
  protected Collection<String> getValidTokens(final Collection<String> tokens) {
    ValidationErrorsException validation = new ValidationErrorsException();
    Set<String> validTokens = new LinkedHashSet<>(tokens);

    // Process each validation rule using pattern matching for switch
    tokens.stream()
        .filter(Objects::nonNull)
        .forEach(token -> {
          switch (validateToken(token)) {
            case ValidationResult(String errorMsg, boolean isLeadingWildcard, _, _) when isLeadingWildcard -> {
              validation.withError(errorMsg);
              log.debug("{} for token: {}", errorMsg, token);
              validTokens.remove(token);
            }
            case ValidationResult(String errorMsg, _, boolean isLeadingSpecialWithWildcard, _) when isLeadingSpecialWithWildcard -> {
              validation.withError(errorMsg);
              log.debug("{} for token: {}", errorMsg, token);
              validTokens.remove(token);
            }
            case ValidationResult(String errorMsg, _, _, boolean isNotEnoughSymbols) when isNotEnoughSymbols -> {
              validation.withError(errorMsg);
              log.debug("{} for token: {}", errorMsg, token);
              validTokens.remove(token);
            }
            default -> { /* Token is valid, keep it in the set */ }
          }
        });

    if (validTokens.isEmpty()) {
      log.debug("No valid search tokens");
      throw validation;
    }

    return validTokens;
  }
  
  /**
   * Record to hold validation result with error message and validation flags.
   */
  private record ValidationResult(String errorMsg, 
                                 boolean isLeadingWildcard, 
                                 boolean isLeadingSpecialWithWildcard, 
                                 boolean isNotEnoughSymbols) {}
  
  /**
   * Validates a token against all validation rules and returns a ValidationResult.
   */
  private ValidationResult validateToken(final String token) {
    String trimmedToken = token.trim();
    
    // Check for leading wildcard
    boolean isLeadingWildcard = hasLeadingWildcard(trimmedToken);
    
    // Check for leading special character followed by wildcard
    boolean isLeadingSpecialWithWildcard = hasLeadingSpecialCharacterAndWildcard(trimmedToken);
    
    // Check for not enough symbols with trailing wildcard
    boolean isNotEnoughSymbols = notEnoughSymbols(trimmedToken);
    
    // Determine error message based on validation results
    String errorMsg = switch (true) {
      case isLeadingWildcard -> "Leading wildcards are prohibited";
      case isLeadingSpecialWithWildcard -> "Searches cannot begin with a special character followed by a wildcard";
      case isNotEnoughSymbols -> String.format("%d characters or more are required with a trailing wildcard (*)",
          MIN_ALLOWED_SYMBOLS_TO_SEARCH);
      default -> null;
    };
    
    return new ValidationResult(errorMsg, isLeadingWildcard, isLeadingSpecialWithWildcard, isNotEnoughSymbols);
  }

  private static boolean hasLeadingWildcard(final String token) {
    if (token.length() > 0) {
      char firstChar = token.charAt(0);
      return switch (firstChar) {
        case ZERO_OR_MORE_CHARACTERS, ANY_CHARACTER -> true;
        default -> false;
      };
    }
    return false;
  }

  private static boolean hasLeadingSpecialCharacterAndWildcard(final String token) {
    if (token.length() < 2) {
      return false;
    }
    
    char firstChar = token.charAt(0);
    char secondChar = token.charAt(1);
    
    return switch (firstChar) {
      case '\\', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm',
           'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z',
           'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
           'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
           '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> false;
      default -> isWildcard(secondChar);
    };
  }

  private static boolean isWildcard(final char character) {
    return switch (character) {
      case ZERO_OR_MORE_CHARACTERS, ANY_CHARACTER -> true;
      default -> false;
    };
  }

  /**
   * Record to hold the result of checking for trailing asterisk wildcard.
   */
  private record TrailingAsteriskResult(boolean hasTrailingWildcard, int lengthWithoutWildcard) {}
  
  /**
   * Check if a given token contains trailing asterisk wildcard and returns a length of string without wildcard.
   *
   * @param token a token to check
   * @return a record containing whether the token has a trailing wildcard and the length without the wildcard
   */
  private static TrailingAsteriskResult checkTrailingAsterisk(final String token) {
    // The escaped asterisk (*) is not a wildcard token.
    String result = token.replace("\\*", "");
    boolean trailingAsteriskWildcard = result.endsWith("*");
    result = token.replace("*", "");
    
    return new TrailingAsteriskResult(trailingAsteriskWildcard, result.length());
  }

  private static boolean notEnoughSymbols(final String token) {
    TrailingAsteriskResult wildcard = checkTrailingAsterisk(token);
    
    return switch (wildcard) {
      case TrailingAsteriskResult(true, var length) when length < MIN_ALLOWED_SYMBOLS_TO_SEARCH -> true;
      default -> false;
    };
  }
}