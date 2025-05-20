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
package org.sonatype.nexus.repository.search.query;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.nexus.repository.search.query.SearchSubjectHelper.SubjectRegistration;

import org.apache.shiro.subject.Subject;
import org.junit.experimental.categories.Category;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
@Category(Java21TestGroup.class)
public class SearchSubjectHelperTest
    extends TestSupport
{
  @Mock
  Subject subject;

  SearchSubjectHelper helper;

  @BeforeEach
  public void setup() {
    helper = new SearchSubjectHelper();
  }

  @Test
  public void registration() {
    assertEquals(0, helper.subjects.size());
    try (SubjectRegistration registration = helper.register(subject)) {
      assertEquals(1, helper.subjects.size());
      assertSame(subject, helper.getSubject(registration.getId()));
    }
    assertEquals(0, helper.subjects.size());
  }

  @Test
  public void missingSubject() {
    assertThrows(NullPointerException.class, () -> helper.getSubject(""));
  }
}