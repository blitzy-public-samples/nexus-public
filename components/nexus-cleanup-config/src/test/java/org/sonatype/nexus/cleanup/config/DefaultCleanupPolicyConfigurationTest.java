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
package org.sonatype.nexus.cleanup.config;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.sonatype.nexus.cleanup.config.CleanupPolicyConstants.IS_PRERELEASE_KEY;
import static org.sonatype.nexus.cleanup.config.CleanupPolicyConstants.LAST_BLOB_UPDATED_KEY;
import static org.sonatype.nexus.cleanup.config.CleanupPolicyConstants.LAST_DOWNLOADED_KEY;
import static org.sonatype.nexus.cleanup.config.CleanupPolicyConstants.REGEX_KEY;
import static org.sonatype.nexus.cleanup.config.CleanupPolicyConstants.RETAIN_KEY;
import static org.sonatype.nexus.cleanup.config.CleanupPolicyConstants.RETAIN_SORT_BY_KEY;

/**
 * Tests for {@link DefaultCleanupPolicyConfiguration}
 */
public class DefaultCleanupPolicyConfigurationTest
    extends TestSupport
{
  @Test
  public void verifyDefaultConfigurationState() {
    // Given a default cleanup policy configuration
    DefaultCleanupPolicyConfiguration underTest = new DefaultCleanupPolicyConfiguration();

    // Then verify the expected configuration values
    assertThat(underTest.getConfiguration().get(LAST_BLOB_UPDATED_KEY), is(equalTo(true)));
    assertThat(underTest.getConfiguration().get(LAST_DOWNLOADED_KEY), is(equalTo(true)));
    assertThat(underTest.getConfiguration().get(IS_PRERELEASE_KEY), is(equalTo(false)));
    
    // Additional assertions for REGEX_KEY, RETAIN_KEY, and RETAIN_SORT_BY_KEY
    assertThat(underTest.getConfiguration().get(REGEX_KEY), is(equalTo(false)));
    assertThat(underTest.getConfiguration().get(RETAIN_KEY), is(equalTo(false)));
    assertThat(underTest.getConfiguration().get(RETAIN_SORT_BY_KEY), is(equalTo(false)));
  }
  
  /**
   * Demonstrates the use of Java 21 String Templates with cleanup policy configuration values.
   * String Templates provide a more readable way to create formatted strings with embedded expressions.
   */
  @Test
  public void demonstrationStringTemplatesForStatusMessages() {
    // Given a default cleanup policy configuration
    DefaultCleanupPolicyConfiguration configuration = new DefaultCleanupPolicyConfiguration();
    var config = configuration.getConfiguration();
    
    // When creating status messages using String Templates
    String lastBlobUpdatedStatus = STR."Last Blob Updated is \{config.get(LAST_BLOB_UPDATED_KEY) ? "enabled" : "disabled"}";
    String lastDownloadedStatus = STR."Last Downloaded is \{config.get(LAST_DOWNLOADED_KEY) ? "enabled" : "disabled"}";
    String isPrereleaseStatus = STR."Is Prerelease is \{config.get(IS_PRERELEASE_KEY) ? "enabled" : "disabled"}";
    String regexStatus = STR."Regex is \{config.get(REGEX_KEY) ? "enabled" : "disabled"}";
    String retainStatus = STR."Retain is \{config.get(RETAIN_KEY) ? "enabled" : "disabled"}";
    String retainSortByStatus = STR."Retain Sort By is \{config.get(RETAIN_SORT_BY_KEY) ? "enabled" : "disabled"}";
    
    // Then verify the status messages are correctly formatted
    assertThat(lastBlobUpdatedStatus, is(equalTo("Last Blob Updated is enabled")));
    assertThat(lastDownloadedStatus, is(equalTo("Last Downloaded is enabled")));
    assertThat(isPrereleaseStatus, is(equalTo("Is Prerelease is disabled")));
    assertThat(regexStatus, is(equalTo("Regex is disabled")));
    assertThat(retainStatus, is(equalTo("Retain is disabled")));
    assertThat(retainSortByStatus, is(equalTo("Retain Sort By is disabled")));
    
    // Demonstrate a more complex template with multiple values
    String summaryMessage = STR."""
        Cleanup Policy Configuration Summary:
        - Last Blob Updated: \{config.get(LAST_BLOB_UPDATED_KEY) ? "✓" : "✗"}
        - Last Downloaded: \{config.get(LAST_DOWNLOADED_KEY) ? "✓" : "✗"}
        - Is Prerelease: \{config.get(IS_PRERELEASE_KEY) ? "✓" : "✗"}
        - Regex: \{config.get(REGEX_KEY) ? "✓" : "✗"}
        - Retain: \{config.get(RETAIN_KEY) ? "✓" : "✗"}
        - Retain Sort By: \{config.get(RETAIN_SORT_BY_KEY) ? "✓" : "✗"}
        """;
    
    // Verify the summary contains the expected checkmarks and crosses
    assertThat(summaryMessage.contains("Last Blob Updated: ✓"), is(true));
    assertThat(summaryMessage.contains("Last Downloaded: ✓"), is(true));
    assertThat(summaryMessage.contains("Is Prerelease: ✗"), is(true));
    assertThat(summaryMessage.contains("Regex: ✗"), is(true));
    assertThat(summaryMessage.contains("Retain: ✗"), is(true));
    assertThat(summaryMessage.contains("Retain Sort By: ✗"), is(true));
  }
}