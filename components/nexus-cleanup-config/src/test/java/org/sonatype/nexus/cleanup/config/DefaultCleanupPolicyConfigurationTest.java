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

import java.util.Map;

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

public class DefaultCleanupPolicyConfigurationTest
    extends TestSupport
{
  @Test
  public void verifyDefaultConfigurationState() {
    DefaultCleanupPolicyConfiguration underTest = new DefaultCleanupPolicyConfiguration();

    assertThat(underTest.getConfiguration().get(LAST_BLOB_UPDATED_KEY), is(equalTo(true)));
    assertThat(underTest.getConfiguration().get(LAST_DOWNLOADED_KEY), is(equalTo(true)));
    assertThat(underTest.getConfiguration().get(IS_PRERELEASE_KEY), is(equalTo(false)));
    assertThat(underTest.getConfiguration().get(REGEX_KEY), is(equalTo(false)));
    assertThat(underTest.getConfiguration().get(RETAIN_KEY), is(equalTo(false)));
    assertThat(underTest.getConfiguration().get(RETAIN_SORT_BY_KEY), is(equalTo(false)));
  }
  
  @Test
  public void demonstrationStringTemplatesForStatusMessages() {
    DefaultCleanupPolicyConfiguration underTest = new DefaultCleanupPolicyConfiguration();
    Map<String, Boolean> config = underTest.getConfiguration();
    
    // Using Java 21 String Templates to create status messages for cleanup policy configuration
    String lastBlobUpdatedStatus = STR."Last Blob Updated criterion is \{config.get(LAST_BLOB_UPDATED_KEY) ? "enabled" : "disabled"}";
    String lastDownloadedStatus = STR."Last Downloaded criterion is \{config.get(LAST_DOWNLOADED_KEY) ? "enabled" : "disabled"}";
    String isPrereleaseStatus = STR."Prerelease criterion is \{config.get(IS_PRERELEASE_KEY) ? "enabled" : "disabled"}";
    String regexStatus = STR."Regex criterion is \{config.get(REGEX_KEY) ? "enabled" : "disabled"}";
    String retainStatus = STR."Retain criterion is \{config.get(RETAIN_KEY) ? "enabled" : "disabled"}";
    String retainSortByStatus = STR."Retain Sort By criterion is \{config.get(RETAIN_SORT_BY_KEY) ? "enabled" : "disabled"}";
    
    // Create a summary message with all configuration statuses
    String summaryMessage = STR."""
        Cleanup Policy Configuration Status:
        - \{lastBlobUpdatedStatus}
        - \{lastDownloadedStatus}
        - \{isPrereleaseStatus}
        - \{regexStatus}
        - \{retainStatus}
        - \{retainSortByStatus}
        """;
    
    // Verify the generated status messages
    assertThat(lastBlobUpdatedStatus, is(equalTo("Last Blob Updated criterion is enabled")));
    assertThat(lastDownloadedStatus, is(equalTo("Last Downloaded criterion is enabled")));
    assertThat(isPrereleaseStatus, is(equalTo("Prerelease criterion is disabled")));
    assertThat(regexStatus, is(equalTo("Regex criterion is disabled")));
    assertThat(retainStatus, is(equalTo("Retain criterion is disabled")));
    assertThat(retainSortByStatus, is(equalTo("Retain Sort By criterion is disabled")));
    
    // Log the summary message for demonstration purposes
    log.info(summaryMessage);
  }
}
