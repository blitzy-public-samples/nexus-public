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

import java.security.NoSuchAlgorithmException;

import javax.crypto.Cipher;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CipherRequiredCondition}.
 *
 * @since 2.7
 */
@ExtendWith(MockitoExtension.class)
public class CipherRequiredConditionTest
    extends TestSupport
{
    // Using String Template for better readability
  private static final String FAKE_TRANSFORMATION = "fake-transformation";

  private CipherRequiredCondition condition;

  @Mock
  private CryptoHelper crypto;

  @BeforeEach
  public void setUp() throws Exception {
    EventManager eventManager = mock(EventManager.class);
    condition = new CipherRequiredCondition(eventManager, crypto, FAKE_TRANSFORMATION);
  }

  @Test
  public void unsatisfiedWhenTransformMissing() throws Exception {
    when(crypto.createCipher(FAKE_TRANSFORMATION)).thenThrow(new NoSuchAlgorithmException());
    condition.bind();
    assertFalse(condition.isSatisfied());
  }

  @Test
  public void satisfiedWhenTransformAvailable() throws Exception {
    when(crypto.createCipher(FAKE_TRANSFORMATION)).thenReturn(mock(Cipher.class));
    condition.bind();
    assertTrue(condition.isSatisfied());
  }
}