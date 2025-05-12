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
package org.sonatype.nexus.repository.apt.datastore.internal.search.sql;

import java.util.concurrent.ExecutorService;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.search.sql.SearchRecord;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AptSearchCustomFieldContributor} with JUnit Jupiter and Java 21 compatibility.
 * 
 * @since 3.60.0
 */
@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
public class AptSearchCustomFieldContributorTest
    extends TestSupport
{
  @Mock
  private Asset asset;

  private AptSearchCustomFieldContributor underTest;

  @BeforeEach
  void setUp() {
    underTest = new AptSearchCustomFieldContributor();
  }

  @Test
  @DisplayName("Should add path without leading slash")
  void shouldAddPathWithoutLeadingSlash() {
    SearchRecord data = mock(SearchRecord.class);
    String path = "/org/foo/1.0/foo-1.0.txt";
    when(asset.path()).thenReturn(path);

    underTest.populateSearchCustomFields(data, asset);

    verify(data).addKeyword(path.substring(1));
  }

  @Test
  @DisplayName("Should work correctly in a Virtual Thread context")
  void shouldWorkCorrectlyInVirtualThreadContext() throws Exception {
    // Create a virtual thread executor
    try (ExecutorService executor = Thread.ofVirtual().name("apt-search-test-", 0).factory().newExecutor()) {
      // Submit a task to the virtual thread executor
      executor.submit(() -> {
        // Verify we're running in a virtual thread
        assert Thread.currentThread().isVirtual() : "Test not running in a virtual thread";
        
        // Run the same test in a virtual thread context
        SearchRecord data = mock(SearchRecord.class);
        String path = "/org/foo/1.0/foo-1.0.txt";
        when(asset.path()).thenReturn(path);

        underTest.populateSearchCustomFields(data, asset);

        verify(data).addKeyword(path.substring(1));
      }).get(); // Wait for completion
    }
  }
}
