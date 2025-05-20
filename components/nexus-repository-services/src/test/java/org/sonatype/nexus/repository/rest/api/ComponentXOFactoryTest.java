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

import java.util.List;
import java.util.Map;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.experimental.categories.Category;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    assertNotNull(componentXO, "Component XO should not be null");
    assertTrue(componentXO instanceof TestComponentXO, "Component XO should be an instance of TestComponentXO");
    verify(componentXODecorator).decorate(any(ComponentXO.class));
    TestComponentXO testComponentXO = (TestComponentXO) componentXO;
    assertTrue(testComponentXO.getWrappedObject() instanceof DefaultComponentXO, 
        "Wrapped object should be an instance of DefaultComponentXO");
    assertThat(testComponentXO.getDecoratedExtraJsonAttributes(), hasEntry("foo", "bar"));
  }
  
  @Test
  public void testComponentXOWithRecordPattern() {
    // Setup a component with specific values
    DefaultComponentXO defaultComponentXO = new DefaultComponentXO();
    defaultComponentXO.setId("test-id");
    defaultComponentXO.setGroup("test-group");
    defaultComponentXO.setName("test-name");
    defaultComponentXO.setVersion("1.0.0");
    defaultComponentXO.setRepository("test-repo");
    defaultComponentXO.setFormat("test-format");
    defaultComponentXO.setAssets(ImmutableList.of());
    
    // Create a decorated component that will be returned by our factory
    TestComponentXO decoratedComponentXO = new TestComponentXO(defaultComponentXO);
    when(componentXODecorator.decorate(any(ComponentXO.class))).thenReturn(decoratedComponentXO);
    
    // Get the component from the factory
    ComponentXO componentXO = underTest.createComponentXO();
    
    // Use record pattern matching to extract and validate component data
    if (componentXO instanceof ComponentXO comp && 
        comp.asRecord() instanceof ComponentXO.ComponentData(String id, String group, String name, 
                                                           String version, String repo, String format, List<?> assets)) {
      // Verify extracted values match what we set
      assertEquals("test-id", id, "ID should match");
      assertEquals("test-group", group, "Group should match");
      assertEquals("test-name", name, "Name should match");
      assertEquals("1.0.0", version, "Version should match");
      assertEquals("test-repo", repo, "Repository should match");
      assertEquals("test-format", format, "Format should match");
      assertTrue(assets.isEmpty(), "Assets should be empty");
      
      // Verify we can still access the decorated attributes
      if (comp instanceof TestComponentXO testComp) {
        assertThat(testComp.getDecoratedExtraJsonAttributes(), hasEntry("foo", "bar"));
      }
    } else {
      // This should never happen if pattern matching works correctly
      throw new AssertionError("Record pattern matching failed");
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
    
    @Override
    public ComponentData asRecord() {
      // Override to ensure we're testing our implementation
      return new ComponentData(
          getId(), getGroup(), getName(), getVersion(),
          getRepository(), getFormat(), getAssets());
    }
  }
}
