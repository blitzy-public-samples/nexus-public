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
package org.sonatype.nexus.capability.condition.internal;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.crypto.CryptoHelper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CipherKeyHighStrengthCondition}.
 *
 * @since 2.7
 */
@ExtendWith(MockitoExtension.class)
public class CipherKeyHighStrengthConditionTest
    extends TestSupport
{
  private static final String FAKE_TRANSFORMATION = STR."fake-transformation";

  private CipherKeyHighStrengthCondition condition;

  @Mock
  private CryptoHelper crypto;
  
  @Mock
  private EventManager eventManager;

  @BeforeEach
  public void setUp() throws Exception {
    condition = new CipherKeyHighStrengthCondition(eventManager, crypto, FAKE_TRANSFORMATION);
  }

  @Test
  public void unsatisfiedWhenTransformStrengthIsLow() throws Exception {
    when(crypto.getCipherMaxAllowedKeyLength(FAKE_TRANSFORMATION))
        .thenReturn(CipherKeyHighStrengthCondition.MIN_BITS - 1);
    condition.bind();
    assertFalse(condition.isSatisfied());
  }

  @Test
  public void satisfiedWhenTransformStrengthIsHigh() throws Exception {
    when(crypto.getCipherMaxAllowedKeyLength(FAKE_TRANSFORMATION)).thenReturn(CipherKeyHighStrengthCondition.MIN_BITS);
    condition.bind();
    assertTrue(condition.isSatisfied());
  }

  @Test
  public void satisfiedWhenTransformStrengthIsHigher() throws Exception {
    when(crypto.getCipherMaxAllowedKeyLength(FAKE_TRANSFORMATION))
        .thenReturn(CipherKeyHighStrengthCondition.MIN_BITS + 1);
    condition.bind();
    assertTrue(condition.isSatisfied());
  }
}