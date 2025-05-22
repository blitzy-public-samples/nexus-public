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
package org.sonatype.nexus.repository.maven.tasks;

import java.util.List;

import org.sonatype.nexus.formfields.FormField;
import org.sonatype.nexus.formfields.NumberTextFormField;
import org.sonatype.nexus.scheduling.TaskDescriptor;
// Using Tag annotation for Virtual Thread test categorization

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.sonatype.nexus.repository.RepositoryTaskSupport.REPOSITORY_NAME_FIELD_ID;
import static org.sonatype.nexus.repository.maven.tasks.PurgeMavenUnusedSnapshotsTaskDescriptor.LAST_USED_INIT_VALUE;
import static org.sonatype.nexus.repository.maven.tasks.PurgeMavenUnusedSnapshotsTaskDescriptor.LAST_USED_MIN_VALUE;

/**
 * Tests for {@link PurgeMavenUnusedSnapshotsTaskDescriptor}.
 * 
 * This test verifies that the task descriptor correctly defines form fields
 * for repository name and 'last used' snapshots threshold with proper configuration.
 * 
 * Converted to JUnit 5 and categorized for Virtual Thread testing as part of Java 21 migration.
 */
@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("VirtualThread")
public class PurgeMavenUnusedSnapshotsTaskDescriptorTest
{
  private TaskDescriptor purgeMavenUnusedSnapshotsTaskDescriptor;

  @BeforeEach
  void setUp() {
    purgeMavenUnusedSnapshotsTaskDescriptor = new PurgeMavenUnusedSnapshotsTaskDescriptor();
  }

  /**
   * Ensures the construction of the descriptor has the appropriate/default values
   */
  @Test
  void testDescriptorConfig() {
    List<FormField> formFields = purgeMavenUnusedSnapshotsTaskDescriptor.getFormFields();

    assertThat(formFields.size(), is(2));
    assertThat(formFields.get(0).getId(), is(REPOSITORY_NAME_FIELD_ID));

    NumberTextFormField lastUsedField = (NumberTextFormField) formFields.get(1);

    assertThat(lastUsedField.getId(), is(PurgeMavenUnusedSnapshotsTask.LAST_USED_FIELD_ID));
    assertThat(lastUsedField.getMinimumValue(), is(LAST_USED_MIN_VALUE));
    assertThat(lastUsedField.getInitialValue(), is(LAST_USED_INIT_VALUE));
  }
}