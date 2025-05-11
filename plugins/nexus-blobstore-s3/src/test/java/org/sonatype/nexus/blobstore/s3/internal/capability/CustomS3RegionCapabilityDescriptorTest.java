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
import java.util.List;
import java.util.Map;

import org.sonatype.nexus.capability.CapabilityType;
import org.sonatype.nexus.formfields.FormField;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for {@link CustomS3RegionCapabilityDescriptor}.
 * 
 * Note: If Mockito is needed in the future, use MockitoExtension with JUnit Jupiter:
 * @ExtendWith(MockitoExtension.class)
 */
@DisplayName("CustomS3RegionCapabilityDescriptor Tests")
public class CustomS3RegionCapabilityDescriptorTest {

  private CustomS3RegionCapabilityDescriptor descriptor;

  @BeforeEach
  void setUp() {
    descriptor = new CustomS3RegionCapabilityDescriptor();
  }

  @Test
  @DisplayName("Capability type should be 'customs3regions'")
  void typeShouldReturnCorrectCapabilityType() {
    CapabilityType type = descriptor.type();
    assertEquals("customs3regions", type.toString());
  }

  @Test
  @DisplayName("Capability name should be 'Custom S3 Regions'")
  void nameShouldReturnCorrectDisplayName() {
    String name = descriptor.name();
    assertEquals("Custom S3 Regions", name);
  }

  @Test
  @DisplayName("Form fields should contain a 'regions' field")
  void formFieldsShouldContainRegionsField() {
    List<FormField> formFields = descriptor.formFields();
    assertNotNull(formFields, "Form fields should not be null");
    assertEquals(1, formFields.size(), "Should have exactly one form field");
    assertEquals("regions", formFields.get(0).getId(), "Form field ID should be 'regions'");
  }

  @Test
  @DisplayName("Should create config from properties")
  void createConfigShouldReturnValidConfiguration() {
    Map<String, String> properties = new HashMap<>();
    properties.put("regions", "us-east-1,us-west-2");
    assertNotNull(descriptor.createConfig(properties), "Created config should not be null");
  }
}
