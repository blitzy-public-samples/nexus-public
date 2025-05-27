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
package org.sonatype.nexus.formfields;

import org.java21.Java21TestGroup;

import org.junit.experimental.categories.Category;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.hamcrest.Matchers.equalTo;

/**
 * {@link AbstractFormField} tests.
 */
@Category(Java21TestGroup.class)
public class AbstractFormFieldTest
{
  private static final String ID = "testId";

  private static final String TYPE = "testField";

  private AbstractFormField<String> formField;

  @BeforeEach
  public void setUp() {
    formField = new AbstractFormField<String>(ID)
    {
      @Override
      public String getType() {
        return TYPE;
      }
    };
  }

  @Test
  public void shouldHaveCorrectIdWhenCreated() {
    assertThat(formField.getId(), equalTo(ID));
  }

  @Test
  public void shouldHaveCorrectTypeWhenCreated() {
    assertThat(formField.getType(), equalTo(TYPE));
  }

  @Test
  public void shouldNotBeRequiredByDefault() {
    assertFalse(formField.isRequired());
  }

  @Test
  public void shouldNotBeDisabledByDefault() {
    assertFalse(formField.isDisabled());
  }

  @Test
  public void shouldNotBeReadOnlyByDefault() {
    assertFalse(formField.isReadOnly());
  }
}