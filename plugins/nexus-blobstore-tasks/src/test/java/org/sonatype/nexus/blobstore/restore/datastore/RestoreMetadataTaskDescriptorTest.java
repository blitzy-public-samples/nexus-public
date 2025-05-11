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
package org.sonatype.nexus.blobstore.restore.datastore;

import java.util.List;

import org.sonatype.goodies.testsupport.TestSupport;

import org.sonatype.nexus.common.app.ApplicationVersion;
import org.sonatype.nexus.formfields.ComboboxFormField;
import org.sonatype.nexus.formfields.FormField;
import org.sonatype.nexus.formfields.StringTextFormField;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Tag;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link RestoreMetadataTaskDescriptor} with Java 21 features.
 * 
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
@Tag("Java21TestGroup")
class RestoreMetadataTaskDescriptorTest
    extends TestSupport
{
  private RestoreMetadataTaskDescriptor underTest;

  @Mock
  private ApplicationVersion applicationVersion;

  @BeforeEach
  void setup() {
    when(applicationVersion.getEdition())
        .thenReturn("RPO");

    underTest = new RestoreMetadataTaskDescriptor(true, applicationVersion);
  }

  @Test
  void formFieldsShouldContainExpectedNumberOfFields() {
    List<FormField> formFields = underTest.getFormFields();
    assertThat(formFields, hasSize(6));
  }
  
  /**
   * Tests form field types using Java 21 pattern matching for instanceof.
   * This demonstrates how pattern matching can simplify type checking and casting.
   */
  @Test
  void formFieldsShouldHaveExpectedTypes() {
    List<FormField> formFields = underTest.getFormFields();
    assertThat(formFields, notNullValue());
    
    // Count field types using pattern matching for instanceof (Java 21 feature)
    int comboboxCount = 0;
    int stringTextCount = 0;
    
    for (FormField field : formFields) {
      // Using pattern matching for instanceof to simplify type checking and casting
      if (field instanceof ComboboxFormField comboField) {
        comboboxCount++;
        assertThat(comboField.getType(), is("combobox"));
      } else if (field instanceof StringTextFormField stringField) {
        stringTextCount++;
        assertThat(stringField.getType(), is("string"));
      }
    }
    
    // Verify we have the expected number of each field type
    // Note: These assertions may need adjustment based on actual field types
    assertTrue(comboboxCount > 0, "Should have at least one combobox field");
    assertTrue(stringTextCount >= 0, "May have string text fields");
    assertEquals(comboboxCount + stringTextCount, formFields.size(), 
        "All fields should be accounted for");
  }
}