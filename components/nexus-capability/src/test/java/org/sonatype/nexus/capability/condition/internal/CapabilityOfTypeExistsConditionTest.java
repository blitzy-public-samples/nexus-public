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

import java.util.List;

import org.sonatype.nexus.capability.CapabilityContext;
import org.sonatype.nexus.capability.CapabilityDescriptor;
import org.sonatype.nexus.capability.CapabilityDescriptorRegistry;
import org.sonatype.nexus.capability.CapabilityEvent;
import org.sonatype.nexus.capability.CapabilityReference;
import org.sonatype.nexus.capability.CapabilityRegistry;
import org.sonatype.nexus.capability.CapabilityType;
import org.sonatype.nexus.capability.condition.EventManagerTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.capability.CapabilityType.capabilityType;

/**
 * {@link CapabilityOfTypeExistsCondition} UTs.
 *
 * @since capabilities 2.0
 */
@ExtendWith(MockitoExtension.class)
public class CapabilityOfTypeExistsConditionTest
    extends EventManagerTestSupport
{

  @Mock
  private CapabilityReference ref1;

  @Mock
  private CapabilityReference ref2;

  @Mock
  private CapabilityRegistry capabilityRegistry;

  private CapabilityOfTypeExistsCondition underTest;

  @BeforeEach
  public final void setUpCapabilityOfTypeExistsCondition()
      throws Exception
  {
    final CapabilityType capabilityType = capabilityType(this.getClass().getName());

    when(ref1.context()).thenReturn(mock(CapabilityContext.class));
    when(ref1.context().type()).thenReturn(capabilityType);

    when(ref2.context()).thenReturn(mock(CapabilityContext.class));
    when(ref2.context().type()).thenReturn(capabilityType);

    final CapabilityDescriptorRegistry descriptorRegistry = mock(CapabilityDescriptorRegistry.class);
    final CapabilityDescriptor descriptor = mock(CapabilityDescriptor.class);

    when(descriptor.name()).thenReturn(this.getClass().getSimpleName());
    when(descriptorRegistry.get(capabilityType)).thenReturn(descriptor);

    underTest = new CapabilityOfTypeExistsCondition(
        eventManager, descriptorRegistry, capabilityRegistry, capabilityType
    );
    underTest.bind();

    verify(eventManager).register(underTest);
  }

  /**
   * Initially, condition should be unsatisfied.
   */
  @Test
  public void initiallyNotSatisfied() {
    assertFalse(underTest.isSatisfied(), STR."Condition should initially be unsatisfied for type \{underTest}");
  }

  /**
   * Condition should become satisfied if an active capability of specified type is added.
   */
  @Test
  public void capabilityOfTypeExists01() {
    doReturn(List.of(ref1)).when(capabilityRegistry).getAll();
    when(ref1.context().isActive()).thenReturn(true);
    underTest.handle(new CapabilityEvent.Created(capabilityRegistry, ref1));
    assertTrue(underTest.isSatisfied(), STR."Condition should be satisfied after adding active capability \{ref1}");

    verifyEventManagerEvents(satisfied(underTest));
  }

  /**
   * Condition should become satisfied if a non active capability of specified type is added.
   */
  @Test
  public void capabilityOfTypeExists02() {
    doReturn(List.of(ref1)).when(capabilityRegistry).getAll();
    when(ref1.context().isActive()).thenReturn(false);
    underTest.handle(new CapabilityEvent.Created(capabilityRegistry, ref1));
    assertTrue(underTest.isSatisfied(), STR."Condition should be satisfied after adding non-active capability \{ref1}");

    verifyEventManagerEvents(satisfied(underTest));
  }

  /**
   * Condition should not be re-satisfied if a new active capability of specified type is added.
   */
  @Test
  public void capabilityOfTypeExists03() {
    doReturn(List.of(ref1)).when(capabilityRegistry).getAll();
    underTest.handle(new CapabilityEvent.Created(capabilityRegistry, ref1));
    assertTrue(underTest.isSatisfied(), STR."Condition should be satisfied after adding first capability \{ref1}");

    doReturn(List.of(ref1, ref2)).when(capabilityRegistry).getAll();
    underTest.handle(new CapabilityEvent.Created(capabilityRegistry, ref2));
    assertTrue(underTest.isSatisfied(), STR."Condition should remain satisfied after adding second capability \{ref2}");

    verifyEventManagerEvents(satisfied(underTest));
  }

  /**
   * Condition should remain satisfied if another capability of the specified type is removed.
   */
  @Test
  public void capabilityOfTypeExists04() {
    doReturn(List.of(ref1)).when(capabilityRegistry).getAll();
    underTest.handle(new CapabilityEvent.Created(capabilityRegistry, ref1));
    assertTrue(underTest.isSatisfied(), STR."Condition should be satisfied after adding first capability \{ref1}");

    doReturn(List.of(ref1, ref2)).when(capabilityRegistry).getAll();
    underTest.handle(new CapabilityEvent.Created(capabilityRegistry, ref2));
    assertTrue(underTest.isSatisfied(), STR."Condition should remain satisfied after adding second capability \{ref2}");

    doReturn(List.of(ref2)).when(capabilityRegistry).getAll();
    underTest.handle(new CapabilityEvent.AfterRemove(capabilityRegistry, ref1));
    assertTrue(underTest.isSatisfied(), STR."Condition should remain satisfied after removing one capability \{ref1}");

    verifyEventManagerEvents(satisfied(underTest));
  }

  /**
   * Condition should become unsatisfied when all capabilities have been removed.
   */
  @Test
  public void capabilityOfTypeExists05() {
    doReturn(List.of(ref1)).when(capabilityRegistry).getAll();
    underTest.handle(new CapabilityEvent.Created(capabilityRegistry, ref1));
    assertTrue(underTest.isSatisfied(), STR."Condition should be satisfied after adding capability \{ref1}");

    doReturn(List.of()).when(capabilityRegistry).getAll();
    underTest.handle(new CapabilityEvent.AfterRemove(capabilityRegistry, ref1));
    assertFalse(underTest.isSatisfied(), STR."Condition should be unsatisfied after removing all capabilities");

    verifyEventManagerEvents(satisfied(underTest), unsatisfied(underTest));
  }

  /**
   * Event bus handler is removed when releasing.
   */
  @Test
  public void releaseRemovesItselfAsHandler() {
    underTest.release();

    verify(eventManager).unregister(underTest);
  }

}
