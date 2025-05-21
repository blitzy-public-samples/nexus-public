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

import java.util.HashMap;
import java.util.Map;

import org.sonatype.nexus.capability.CapabilityContext;
import org.sonatype.nexus.capability.CapabilityEvent;
import org.sonatype.nexus.capability.CapabilityIdentity;
import org.sonatype.nexus.capability.CapabilityReference;
import org.sonatype.nexus.capability.CapabilityRegistry;
import org.sonatype.nexus.capability.condition.EventManagerTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.capability.CapabilityIdentity.capabilityIdentity;

/**
 * {@link PassivateCapabilityDuringUpdateCondition} UTs.
 *
 * @since capabilities 2.0
 */
@ExtendWith(MockitoExtension.class)
public class PassivateCapabilityDuringUpdateConditionTest
    extends EventManagerTestSupport
{

  @Mock
  private CapabilityReference reference;

  @Mock
  private CapabilityRegistry capabilityRegistry;

  private PassivateCapabilityDuringUpdateCondition underTest;

  @BeforeEach
  public final void setUpPassivateCapabilityDuringUpdateCondition()
      throws Exception
  {
    final CapabilityIdentity id = capabilityIdentity("test");

    final CapabilityContext context = mock(CapabilityContext.class);
    when(context.id()).thenReturn(id);

    when(reference.context()).thenReturn(context);

    underTest = new PassivateCapabilityDuringUpdateCondition(eventManager);
    underTest.setContext(context);
    underTest.bind();

    verify(eventManager).register(underTest);
  }

  /**
   * Condition should become unsatisfied before update and satisfied after update.
   */
  @Test
  public void passivateDuringUpdate() {
    underTest.handle(new CapabilityEvent.BeforeUpdate(
        capabilityRegistry, reference, Map.of(), Map.of()
    ));
    underTest.handle(new CapabilityEvent.AfterUpdate(
        capabilityRegistry, reference, Map.of(), Map.of()
    ));

    verifyEventManagerEvents(unsatisfied(underTest), satisfied(underTest));
  }

  /**
   * Event bus handler is removed when releasing.
   */
  @Test
  public void releaseRemovesItselfAsHandler() {
    underTest.release();

    verify(eventManager).unregister(underTest);
  }

  /**
   * Verify that binding fails if id was not set before.
   */
  @Test
  public void bindWithoutIdBeingSet() {
    IllegalStateException exception = assertThrows(IllegalStateException.class, 
        () -> new PassivateCapabilityDuringUpdateCondition(eventManager).bind());
    assertThat(exception.getMessage(), notNullValue());
  }

  /**
   * Verify that binding succeeds after contextualization.
   */
  @Test
  public void bindAfterContextualization() {
    assertThat(
        new PassivateCapabilityDuringUpdateCondition(eventManager).setContext(reference.context()).bind(),
        notNullValue()
    );
  }

  /**
   * Verify that contextualization fails if already bounded.
   */
  @Test
  public void contextualizationWhenAlreadyBounded() {
    IllegalStateException exception = assertThrows(IllegalStateException.class, 
        () -> underTest.setContext(reference.context()));
    assertThat(exception.getMessage(), notNullValue());
  }

  /**
   * Verify that contextualization fails if already contextualized.
   */
  @Test
  public void contextualizationWhenAlreadyContextualized() {
    IllegalStateException exception = assertThrows(IllegalStateException.class, 
        () -> new PassivateCapabilityDuringUpdateCondition(eventManager)
            .setContext(reference.context())
            .setContext(reference.context()));
    assertThat(exception.getMessage(), notNullValue());
  }

}