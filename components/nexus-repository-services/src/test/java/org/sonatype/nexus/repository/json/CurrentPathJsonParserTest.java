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
package org.sonatype.nexus.repository.json;

import java.io.IOException;

import org.sonatype.goodies.testsupport.TestSupport;

import com.fasterxml.jackson.core.JsonFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(MockitoExtension.class)
public class CurrentPathJsonParserTest
    extends TestSupport
{
  private final static String SIMPLE_JSON = "{\"_id\":\"simple\",\"user\":{\"description\":\"simplestuff\"}}";

  private CurrentPathJsonParser underTest;

  @BeforeEach
  public void setUp() throws IOException {
    underTest = new CurrentPathJsonParser(new JsonFactory().createParser(SIMPLE_JSON));
  }

  @Test
  public void should_Return_CurrentPath_For_Parser() throws IOException {
    assertEquals("/", underTest.currentPath());

    underTest.nextValue();
    assertEquals("/", underTest.currentPath());

    underTest.nextValue();
    assertEquals("/_id", underTest.currentPath());

    underTest.nextValue();
    assertEquals("/user", underTest.currentPath());

    underTest.nextValue();
    assertEquals("/user/description", underTest.currentPath());

    underTest.nextValue();
    assertEquals("/user", underTest.currentPath());

    underTest.nextValue();
    assertEquals("/", underTest.currentPath());
  }

  @Test
  public void should_Return_CurrentPath_InParts_For_Parser() throws IOException {
    assertEquals(0, underTest.currentPathInParts().length);

    underTest.nextValue();
    assertEquals(0, underTest.currentPathInParts().length);

    underTest.nextValue();
    assertEquals(1, underTest.currentPathInParts().length);
    assertEquals("_id", underTest.currentPathInParts()[0]);

    underTest.nextValue();
    assertEquals(1, underTest.currentPathInParts().length);
    assertEquals("user", underTest.currentPathInParts()[0]);

    underTest.nextValue();
    assertEquals(2, underTest.currentPathInParts().length);
    assertEquals("user", underTest.currentPathInParts()[0]);
    assertEquals("description", underTest.currentPathInParts()[1]);

    underTest.nextValue();
    assertEquals(1, underTest.currentPathInParts().length);
    assertEquals("user", underTest.currentPathInParts()[0]);

    underTest.nextValue();
    assertEquals(0, underTest.currentPathInParts().length);
  }

  @Test
  public void should_Return_CurrentPointer() {
    assertNotNull(underTest.currentPointer());
  }
}
