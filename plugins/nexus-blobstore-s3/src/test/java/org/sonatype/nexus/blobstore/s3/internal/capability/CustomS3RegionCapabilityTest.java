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
package org.sonatype.nexus.blobstore.s3.internal.capability;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for {@link CustomS3RegionCapability} to verify proper handling of S3 region configurations.
 */
@ExtendWith(MockitoExtension.class)
public class CustomS3RegionCapabilityTest {
  private CustomS3RegionCapability capability;

  @BeforeEach
  void setUp() {
    capability = new CustomS3RegionCapability();
  }

  /**
   * Verifies that the capability correctly creates a configuration object from properties
   * containing comma-separated region values.
   */
  @Test
  void should_create_config_with_valid_regions() {
    // given
    Map<String, String> properties = new HashMap<>();
    properties.put("regions", "us-east-1,us-west-2");

    // when
    CustomS3RegionCapabilityConfiguration config = capability.createConfig(properties);

    // then
    assertNotNull(config, "Configuration should not be null");
    assertEquals("us-east-1,us-west-2", config.getRegions(), "Regions should match the input string");
  }
}
