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

import java.util.Map;

import org.sonatype.nexus.capability.CapabilityContext;
import org.sonatype.nexus.capability.CapabilityDescriptor;
import org.sonatype.nexus.capability.CapabilityEvent;
import org.sonatype.nexus.capability.CapabilityReference;
import org.sonatype.nexus.capability.CapabilityRegistry;
import org.sonatype.nexus.capability.condition.EventManagerTestSupport;

import static java.lang.StringTemplate.STR;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.capability.CapabilityIdentity.capabilityIdentity;
import static org.sonatype.nexus.capability.CapabilityType.capabilityType;

/**
 * {@link CapabilityHasNoDuplicatesCondition} UTs.
 */
@ExtendWith(MockitoExtension.class)
public class CapabilityHasNoDuplicatesConditionTest
    extends EventManagerTestSupport
{

  @Mock
  private CapabilityRegistry capabilityRegistry;

  private CapabilityHasNoDuplicatesCondition underTest;

  private CapabilityReference reference;

  private CapabilityContext context;

  private CapabilityDescriptor descriptor;

  @BeforeEach
  void setUpCondition() throws Exception {
    reference = createReference("testRef1", "testType");

    context = reference.context();
    descriptor = context.descriptor();

    underTest = new CapabilityHasNoDuplicatesCondition(eventManager);
  }

  @Test
  void standardLifecycle() {
    assertFalse(underTest.isSatisfied());

    underTest.setContext(reference.context());

    assertFalse(underTest.isSatisfied());

    underTest.bind();

    assertTrue(underTest.isSatisfied());

    underTest.release();

    InOrder inOrder = Mockito.inOrder(eventManager);
    inOrder.verify(eventManager).register(underTest);
    inOrder.verify(eventManager).unregister(underTest);
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  void duplicatesDetectedDuringBind() {
    underTest.setContext(reference.context());

    when(descriptor.isDuplicated(context.id(), context.properties())).thenReturn(true);

    underTest.bind();

    assertFalse(underTest.isSatisfied());

    underTest.release();
  }

  @Test
  void duplicatesDetectedDuringEvents() {
    CapabilityReference unrelatedRef = createReference("testRef2", "anotherType");
    CapabilityReference duplicateRef = createReference("testRef3", "testType");

    underTest.setContext(reference.context());
    underTest.bind();

    // only checked after matching event
    when(descriptor.isDuplicated(context.id(), context.properties())).thenReturn(true);
    assertTrue(underTest.isSatisfied());
    // different type, shouldn't trigger change
    underTest.handle(new CapabilityEvent.Created(capabilityRegistry, unrelatedRef));
    assertTrue(underTest.isSatisfied());

    // same type, condition should check for dups
    underTest.handle(new CapabilityEvent.Created(capabilityRegistry, duplicateRef));

    assertFalse(underTest.isSatisfied());

    // only checked after matching event
    when(descriptor.isDuplicated(context.id(), context.properties())).thenReturn(false);
    assertFalse(underTest.isSatisfied());
    // different type, shouldn't trigger change
    underTest.handle(new CapabilityEvent.AfterRemove(capabilityRegistry, unrelatedRef));
    assertFalse(underTest.isSatisfied());

    // same type, condition should check for dups
    underTest.handle(new CapabilityEvent.AfterRemove(capabilityRegistry, duplicateRef));

    assertTrue(underTest.isSatisfied());

    underTest.release();

    InOrder inOrder = Mockito.inOrder(descriptor);
    // isDuplicated should only be called during bind + once for each event of same type
    inOrder.verify(descriptor, times(3)).isDuplicated(context.id(), context.properties());
    inOrder.verifyNoMoreInteractions();
  }

  /**
   * Verify that contextualization fails if already bound.
   */
  @Test
  void contextualizationFailsWhenAlreadyBounded() {
    IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
      underTest.setContext(reference.context());
      underTest.bind();
      underTest.setContext(reference.context());
    });
    assertEquals(STR."Cannot contextualize when already bound", exception.getMessage());
  }

  /**
   * Verify that contextualization fails if already contextualized.
   */
  @Test
  void contextualizationFailsWhenAlreadyContextualized() {
    IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
      underTest.setContext(reference.context());
      underTest.setContext(reference.context());
    });
    assertEquals(STR."Already contextualized", exception.getMessage());
  }

  private static CapabilityReference createReference(final String id, final String type) {
    CapabilityDescriptor descriptor = mock(CapabilityDescriptor.class);

    CapabilityContext context = mock(CapabilityContext.class);
    when(context.id()).thenReturn(capabilityIdentity(id));
    Map<String, String> testProperties = Map.of("testKey", "testValue");
    when(context.properties()).thenReturn(testProperties);
    when(context.descriptor()).thenReturn(descriptor);
    when(context.type()).thenReturn(capabilityType(type));

    CapabilityReference reference = mock(CapabilityReference.class);
    when(reference.context()).thenReturn(context);

    return reference;
  }
}