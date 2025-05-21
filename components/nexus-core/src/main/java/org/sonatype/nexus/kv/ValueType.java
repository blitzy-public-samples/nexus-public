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
package org.sonatype.nexus.kv;

import java.util.List;
import java.util.Map;

/**
 * Enum representing the types of values that can be stored in the key-value store.
 * Enhanced with Java 21 Pattern Matching for switch to simplify type-based decisions.
 */
public enum ValueType
{
  CHARACTER,
  NUMBER,
  BOOLEAN,
  OBJECT;

  /**
   * Determines the appropriate ValueType for a given Java object using Pattern Matching for switch.
   * This method demonstrates Java 21's enhanced pattern matching capabilities.
   *
   * @param value the object to determine the type for
   * @return the appropriate ValueType for the given object
   */
  public static ValueType fromObject(final Object value) {
    return switch (value) {
      case String s -> CHARACTER;
      case Integer i -> NUMBER;
      case Long l -> NUMBER;
      case Double d -> NUMBER;
      case Float f -> NUMBER;
      case Boolean b -> BOOLEAN;
      case null -> throw new IllegalArgumentException("Value cannot be null");
      default -> OBJECT;
    };
  }

  /**
   * Converts a stored value to its appropriate Java type based on this ValueType.
   * Uses Java 21 Pattern Matching for switch to simplify type conversion logic.
   *
   * @param value the value to convert
   * @return the converted value in its appropriate Java type
   * @throws IllegalArgumentException if the value is not compatible with this ValueType
   */
  public Object convertValue(final Object value) {
    return switch (this) {
      case CHARACTER -> switch (value) {
        case String s -> s;
        case null -> throw new IllegalArgumentException("Value cannot be null");
        default -> value.toString();
      };
      case NUMBER -> switch (value) {
        case Integer i -> i;
        case Long l -> l;
        case Double d -> d;
        case Float f -> f;
        case String s -> {
          try {
            if (s.contains(".")) {
              yield Double.parseDouble(s);
            } else {
              yield Integer.parseInt(s);
            }
          } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Cannot convert " + s + " to a number", e);
          }
        }
        case null -> throw new IllegalArgumentException("Value cannot be null");
        default -> throw new IllegalArgumentException("Cannot convert " + value.getClass().getName() + " to a number");
      };
      case BOOLEAN -> switch (value) {
        case Boolean b -> b;
        case String s -> Boolean.parseBoolean(s);
        case Integer i -> i != 0;
        case null -> throw new IllegalArgumentException("Value cannot be null");
        default -> throw new IllegalArgumentException("Cannot convert " + value.getClass().getName() + " to a boolean");
      };
      case OBJECT -> value;
    };
  }

  /**
   * Validates if a given object is compatible with this ValueType.
   * Uses Java 21 Pattern Matching for switch to simplify validation logic.
   *
   * @param value the object to validate
   * @return true if the object is compatible with this ValueType, false otherwise
   */
  public boolean isCompatible(final Object value) {
    if (value == null) {
      return false;
    }
    
    return switch (this) {
      case CHARACTER -> switch (value) {
        case String s -> true;
        case Character c -> true;
        default -> value.toString() != null;
      };
      case NUMBER -> switch (value) {
        case Integer i -> true;
        case Long l -> true;
        case Double d -> true;
        case Float f -> true;
        case String s -> {
          try {
            Double.parseDouble(s);
            yield true;
          } catch (NumberFormatException e) {
            yield false;
          }
        }
        default -> false;
      };
      case BOOLEAN -> switch (value) {
        case Boolean b -> true;
        case String s -> s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false");
        case Integer i -> i == 0 || i == 1;
        default -> false;
      };
      case OBJECT -> true; // All objects are compatible with OBJECT type
    };
  }

  /**
   * Gets the default Java class for this ValueType.
   * Uses Java 21 Pattern Matching for switch to simplify type mapping.
   *
   * @return the default Java class for this ValueType
   */
  public Class<?> getDefaultClass() {
    return switch (this) {
      case CHARACTER -> String.class;
      case NUMBER -> Integer.class;
      case BOOLEAN -> Boolean.class;
      case OBJECT -> Object.class;
    };
  }

  /**
   * Determines if the given value needs conversion to be stored as this ValueType.
   * Uses Java 21 Pattern Matching for switch for optimized performance.
   *
   * @param value the value to check
   * @return true if the value needs conversion, false if it can be stored as-is
   */
  public boolean needsConversion(final Object value) {
    if (value == null) {
      return false;
    }
    
    return switch (this) {
      case CHARACTER -> !(value instanceof String);
      case NUMBER -> switch (value) {
        case Integer i -> false;
        case Long l -> false;
        case Double d -> false;
        case Float f -> false;
        default -> true;
      };
      case BOOLEAN -> !(value instanceof Boolean);
      case OBJECT -> false; // No conversion needed for OBJECT type
    };
  }
}
