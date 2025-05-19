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
package org.sonatype.nexus.blobstore;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.BlobId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.sonatype.nexus.blobstore.DirectPathLocationStrategy.DIRECT_PATH_PREFIX;
import static org.sonatype.nexus.blobstore.DirectPathLocationStrategy.DIRECT_PATH_ROOT;

/**
 * Tests for {@link DirectPathLocationStrategy}.
 */
@ExtendWith(MockitoExtension.class)
public class DirectPathLocationStrategyTest
    extends TestSupport
{
  private static final String CORRECT_PATH = "/healthCheckSummary/maven-central/current/summary.html";

  private static final String PATH_WITH_TRAVERSAL = "/healthCheckSummary/maven-central/1/../details/details.html";

  private static final String PATH_WITH_PREFIX_INSIDE_TRAVERSAL =
      "/healthCheckSummary/maven-central/1/.path$./details/details.html";

  private static final String EXPECTED_PATH = STR"#{DIRECT_PATH_ROOT}/#{CORRECT_PATH}";

  private LocationStrategy underTest;

  @BeforeEach
  public void setUp() {
    underTest = new DirectPathLocationStrategy();
  }

  @Test
  public void testLocation() {
    String location = underTest.location(new BlobId(STR"#{DIRECT_PATH_PREFIX}#{CORRECT_PATH}"));
    assertEquals(EXPECTED_PATH, location);
  }

  @Test
  public void testLocationWithTraversal() {
    assertThrows(IllegalArgumentException.class, () -> {
      underTest.location(new BlobId(STR"#{DIRECT_PATH_PREFIX}#{PATH_WITH_TRAVERSAL}"));
    });
  }

  @Test
  public void testLocationWithPrefixInsideTraversal() {
    assertThrows(IllegalArgumentException.class, () -> {
      underTest.location(new BlobId(STR"#{DIRECT_PATH_PREFIX}#{PATH_WITH_PREFIX_INSIDE_TRAVERSAL}"));
    });
  }

  @Test
  public void testLocationWithNullableBlobId() {
    assertThrows(NullPointerException.class, () -> {
      underTest.location(null);
    });
  }
}