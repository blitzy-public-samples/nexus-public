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
package org.sonatype.nexus.repository.rest.api;

import java.util.Map;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Category(Java21TestGroup.class)
public class ComponentXOFactoryTest
    extends TestSupport
{
  @Mock
  private ComponentXODecorator componentXODecorator;

  private ComponentXOFactory underTest;

  @BeforeEach
  public void setup() {
    underTest = new ComponentXOFactory(ImmutableSet.of(componentXODecorator));

    ComponentXO componentXO = new DefaultComponentXO();
    when(componentXODecorator.decorate(any(ComponentXO.class))).thenReturn(new TestComponentXO(componentXO));
  }

  @Test
  public void testComponentXO() {
    ComponentXO componentXO = underTest.createComponentXO();
    assertNotNull(componentXO);
    assertInstanceOf(TestComponentXO.class, componentXO);
    verify(componentXODecorator).decorate(any(ComponentXO.class));
    TestComponentXO testComponentXO = (TestComponentXO) componentXO;
    assertInstanceOf(DefaultComponentXO.class, testComponentXO.getWrappedObject());
    assertTrue(testComponentXO.getDecoratedExtraJsonAttributes().containsKey("foo"));
    assertEquals("bar", testComponentXO.getDecoratedExtraJsonAttributes().get("foo"));
  }
  
  @Test
  public void testComponentXOWithRecordPattern() {
    ComponentXO componentXO = underTest.createComponentXO();
    assertNotNull(componentXO);
    
    // Using record pattern to extract and validate the component
    if (componentXO instanceof TestComponentXO(ComponentXO wrappedObject)) {
      assertInstanceOf(DefaultComponentXO.class, wrappedObject);
      
      // Get decorated attributes using the record pattern extracted component
      Map<String, Object> attributes = ((TestComponentXO) componentXO).getDecoratedExtraJsonAttributes();
      assertEquals("bar", attributes.get("foo"));
      
      verify(componentXODecorator).decorate(any(ComponentXO.class));
    } else {
      throw new AssertionError("Expected TestComponentXO instance");
    }
  }

  private class TestComponentXO
      extends DecoratedComponentXO
      implements ComponentXO
  {
    TestComponentXO(final ComponentXO componentXO) {
      super(componentXO);
    }

    @Override
    public Map<String, Object> getDecoratedExtraJsonAttributes() {
      return ImmutableMap.of("foo", "bar");
    }
  }
}