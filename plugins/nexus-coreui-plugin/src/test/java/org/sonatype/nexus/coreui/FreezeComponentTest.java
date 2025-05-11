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
package org.sonatype.nexus.coreui;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.FreezeService;

// JUnit Jupiter imports (JUnit 5)
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

// Updated Hamcrest imports for 2.2 compatibility
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
// Updated Mockito imports for 4.11.0
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test for {@link FreezeComponent}.
 * 
 * Updated for Java 21 compatibility and JUnit Jupiter (JUnit 5.10.1).
 */
public class FreezeComponentTest
    extends TestSupport
{
  private FreezeComponent underTest;

  @Mock
  private FreezeService freezeService;

  /**
   * Setup test fixture.
   */
  @BeforeEach
  public void setup() {
    underTest = new FreezeComponent(freezeService);
  }

  /**
   * Test reading freeze status.
   */
  @Test
  public void read() {
    when(freezeService.isFrozen()).thenReturn(true);
    FreezeStatusXO freezeStatusXO = underTest.read();
    assertThat(freezeStatusXO.frozen(), is(true));
  }

  /**
   * Test updating to release (unfreeze) state.
   */
  @Test
  public void testUpdateRelease() {
    // Create a new record instance with frozen=false
    FreezeStatusXO freezeStatusXO = new FreezeStatusXO(false);

    underTest.update(freezeStatusXO);

    verify(freezeService).cancelFreeze();
  }

  /**
   * Test updating to freeze state.
   */
  @Test
  public void testUpdateFreeze() {
    // Create a new record instance with frozen=true
    FreezeStatusXO freezeStatusXO = new FreezeStatusXO(true);

    underTest.update(freezeStatusXO);

    verify(freezeService).requestFreeze(isA(String.class));
  }
}