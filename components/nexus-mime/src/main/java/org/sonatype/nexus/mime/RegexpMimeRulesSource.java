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
package org.sonatype.nexus.mime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Ordered regular-expression {@link MimeRulesSource} implementation.
 *
 * @since 2.0
 */
public class RegexpMimeRulesSource
    implements MimeRulesSource
{
  private final LinkedHashMap<Pattern, MimeRule> rules = new LinkedHashMap<>();

  /**
   * Sealed interface for pattern matching results used with switch expressions.
   */
  private sealed interface MatchResult {
    /**
     * Represents a successful match with an associated MimeRule.
     */
    record Match(MimeRule rule) implements MatchResult {}
    
    /**
     * Represents no match found.
     */
    record NoMatch() implements MatchResult {}
  }

  public void addRule(final String pattern, final String mimeType) {
    checkNotNull(pattern, STR."Pattern string cannot be null");
    checkNotNull(mimeType, STR."MIME type cannot be null");
    addRule(Pattern.compile(pattern), mimeType);
  }

  public void addRule(final Pattern pattern, final String mimeType) {
    checkNotNull(pattern, STR."Pattern cannot be null");
    checkNotNull(mimeType, STR."MIME type cannot be null");
    rules.put(pattern, new MimeRule(false, mimeType));
  }

  @Override
  @Nullable
  public MimeRule getRuleForName(final String name) {
    checkNotNull(name, STR."Name cannot be null");
    
    // Find the first matching pattern and create a MatchResult
    MatchResult result = findMatchingRule(name);
    
    // Use pattern matching with switch expression to handle the result
    return switch (result) {
      case MatchResult.Match match -> {
        // Using String Template to log the match if needed
        // Logger would be used here in a real implementation
        // log.debug(STR."Found matching rule for \{name} with mime types \{match.rule().getMimetypes()}");
        yield match.rule();
      }
      case MatchResult.NoMatch ignored -> {
        // Using String Template to log the no-match case if needed
        // Logger would be used here in a real implementation
        // log.debug(STR."No matching rule found for \{name}");
        yield null;
      }
    };
  }
  
  /**
   * Finds the first matching rule for the given name.
   *
   * @param name the name to match against patterns
   * @return a MatchResult containing either the matched rule or indicating no match
   */
  private MatchResult findMatchingRule(final String name) {
    return rules.entrySet().stream()
        .filter(entry -> entry.getKey().matcher(name).matches())
        .findFirst()
        .map(entry -> (MatchResult) new MatchResult.Match(entry.getValue()))
        .orElse(new MatchResult.NoMatch());
  }
}
