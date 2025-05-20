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
package org.sonatype.nexus.repository.search.query;

import org.junit.jupiter.api.Test;
import org.junit.experimental.categories.Category;
import org.sonatype.nexus.virtualthread.Java21TestGroup;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@Category(Java21TestGroup.class)
public class ElasticSearchContributionSupportTest
{
  private ElasticSearchContributionSupport
      elasticSearchContributionSupport = new ElasticSearchContributionSupport();

  @Test
  void leavesRegularCharactersAsIs() {
    String regularCharacters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890.";
    assertThat(elasticSearchContributionSupport.escape(regularCharacters), is(regularCharacters));
  }

  @Test
  void leavesSupportedSpecialCharactersUnescaped() {
    String supportedSpecialCharacters = "?*\"\"";
    assertThat(elasticSearchContributionSupport.escape(supportedSpecialCharacters), is(supportedSpecialCharacters));
  }

  @Test
  void escapesAllUnsupportedSpecialCharacters() {
    String input = ":[]-+!(){}^~/\\";
    String expected = "\\:\\[\\]\\-\\+\\!\\(\\)\\{\\}\\^\\~\\/\\\\";
    
    // Using pattern matching to validate the escape behavior
    String result = elasticSearchContributionSupport.escape(input);
    assertThat(result, is(expected));
  }

  @Test
  void escapesOddNumberOfDoubleQuotes() {
    // Using pattern matching to test different cases with odd number of quotes
    String result1 = elasticSearchContributionSupport.escape("\"");
    String result2 = elasticSearchContributionSupport.escape("\"a\"b\"");
    
    switch (result1) {
      case String s when s.equals("\\\"") -> assertThat(true, is(true)); // Success case
      default -> assertThat("Failed to properly escape single quote", false, is(true));
    }
    
    assertThat(result2, is("\\\"a\\\"b\\\""));
  }

  @Test
  void ignoresEvenNumberOfDoubleQuotes() {
    // Test cases with even number of quotes using pattern matching
    String input1 = "\"ab\"";
    String input2 = "\"ab\" \"ab\"";
    String input3 = "\"\"\"\"";
    
    // Pattern matching to validate results
    switch (elasticSearchContributionSupport.escape(input1)) {
      case String s when s.equals(input1) -> assertThat(true, is(true)); // Success case
      default -> assertThat("Failed to preserve even number of quotes", false, is(true));
    }
    
    assertThat(elasticSearchContributionSupport.escape(input2), is(input2));
    assertThat(elasticSearchContributionSupport.escape(input3), is(input3));
  }

  @Test
  void supportsCommonSearches() {
    // Test common search patterns with pattern matching for validation
    String input1 = "library/alpine-dev";
    String expected1 = "library\\/alpine\\-dev";
    String input2 = "org.sonatype.nexus";
    String input3 = "org.apache.maven maven-plugin-registry";
    String expected3 = "org.apache.maven maven\\-plugin\\-registry";
    
    String result1 = elasticSearchContributionSupport.escape(input1);
    switch (result1) {
      case String s when s.equals(expected1) -> assertThat(true, is(true)); // Success case
      default -> assertThat("Failed to escape path separator correctly", false, is(true));
    }
    
    assertThat(elasticSearchContributionSupport.escape(input2), is(input2));
    assertThat(elasticSearchContributionSupport.escape(input3), is(expected3));
  }
}
