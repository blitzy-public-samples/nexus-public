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
package org.sonatype.nexus.repository.purge;

import java.util.List;

import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.nexus.formfields.FormField;
import org.sonatype.nexus.formfields.NumberTextFormField;
import org.sonatype.nexus.scheduling.TaskDescriptor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.experimental.categories.Category;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.sonatype.nexus.repository.RepositoryTaskSupport.REPOSITORY_NAME_FIELD_ID;
import static org.sonatype.nexus.repository.purge.PurgeUnusedTaskDescriptor.LAST_USED_INIT_VALUE;
import static org.sonatype.nexus.repository.purge.PurgeUnusedTaskDescriptor.LAST_USED_MIN_VALUE;

@ExtendWith(MockitoExtension.class)
@Category(Java21TestGroup.class)
public class PurgeUnusedTaskDescriptorTest
{
  private TaskDescriptor purgeUnusedTaskDescriptor;

  @BeforeEach
  public void setUp() {
    purgeUnusedTaskDescriptor = new PurgeUnusedTaskDescriptor();
  }

  /**
   * Ensures the construction of the descriptor has the appropriate/default values
   */
  @Test
  public void shouldHaveCorrectDescriptorConfiguration() {
    List<FormField> formFields = purgeUnusedTaskDescriptor.getFormFields();

    assertThat(formFields.size(), is(2));
    assertThat(formFields.get(0).getId(), is(REPOSITORY_NAME_FIELD_ID));

    // Using record pattern matching for cleaner code
    if (formFields.get(1) instanceof NumberTextFormField(var id, var minimumValue, var initialValue, var _, var __, var ___)) {
      assertThat(id, is(PurgeUnusedTask.LAST_USED_FIELD_ID));
      assertThat(minimumValue, is(LAST_USED_MIN_VALUE));
      assertThat(initialValue, is(LAST_USED_INIT_VALUE));
    }
  }
}
