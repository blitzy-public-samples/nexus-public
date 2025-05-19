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
package org.sonatype.nexus.common.decorator;

// JUnit 4 imports for backward compatibility
import org.junit.Test;

// JUnit Jupiter imports
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// Static imports for assertions
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.sonatype.nexus.common.decorator.DecoratorUtils.getDecoratedEntity;

@DisplayName("DecoratorUtils Tests")
public class DecoratorUtilsTest
{
  @Test
  @DisplayName("Verify getDecoratedEntity returns correct instances based on type")
  public void getDecoratedEntityReturnsCorrectInstancesBasedOnType() {
    DecoratorTest first = new DefaultDecoratorTest();
    DecoratorTest second = new TestDecorator1(first);
    DecoratorTest third = new TestDecorator2(second);

    assertNotNull(getDecoratedEntity(first, DefaultDecoratorTest.class), 
        "DefaultDecoratorTest instance should be found in first object");
    assertNull(getDecoratedEntity(first, TestDecorator1.class), 
        "TestDecorator1 instance should not be found in first object");
    assertNull(getDecoratedEntity(first, TestDecorator2.class), 
        "TestDecorator2 instance should not be found in first object");

    assertNotNull(getDecoratedEntity(second, DefaultDecoratorTest.class), 
        "DefaultDecoratorTest instance should be found in second object");
    assertNotNull(getDecoratedEntity(second, TestDecorator1.class), 
        "TestDecorator1 instance should be found in second object");
    assertEquals("special1", getDecoratedEntity(second, TestDecorator1.class).specialMethod1(), 
        "TestDecorator1's specialMethod1 should return 'special1'");
    assertNull(getDecoratedEntity(second, TestDecorator2.class), 
        "TestDecorator2 instance should not be found in second object");

    assertNotNull(getDecoratedEntity(third, DefaultDecoratorTest.class), 
        "DefaultDecoratorTest instance should be found in third object");
    assertNotNull(getDecoratedEntity(third, TestDecorator1.class), 
        "TestDecorator1 instance should be found in third object");
    assertEquals("special1", getDecoratedEntity(second, TestDecorator1.class).specialMethod1(), 
        "TestDecorator1's specialMethod1 should return 'special1'");
    assertNotNull(getDecoratedEntity(third, TestDecorator2.class), 
        "TestDecorator2 instance should be found in third object");
    assertEquals("special2", getDecoratedEntity(third, TestDecorator2.class).specialMethod2(), 
        "TestDecorator2's specialMethod2 should return 'special2'");
  }

  // DECORATOR CLASSES

  private interface DecoratorTest
  {
    String getValue();
  }

  private class DefaultDecoratorTest
      implements DecoratorTest
  {
    @Override
    public String getValue() {
      return "default";
    }
  }

  private abstract class DecoratedTest
      implements DecoratorTest, DecoratedObject<DecoratorTest>
  {
    private final DecoratorTest wrapped;

    DecoratedTest(final DecoratorTest wrapped) {
      this.wrapped = wrapped;
    }

    @Override
    public String getValue() {
      return wrapped.getValue();
    }

    @Override
    public DecoratorTest getWrappedObject() {
      return wrapped;
    }
  }

  private final class TestDecorator1
      extends DecoratedTest
  {
    TestDecorator1(final DecoratorTest wrapped) {
      super(wrapped);
    }

    @Override
    public String getValue() {
      return "overridden";
    }

    String specialMethod1() {
      return "special1";
    }
  }

  private final class TestDecorator2
      extends DecoratedTest
  {
    TestDecorator2(final DecoratorTest wrapped) {
      super(wrapped);
    }

    String specialMethod2() {
      return "special2";
    }
  }
}