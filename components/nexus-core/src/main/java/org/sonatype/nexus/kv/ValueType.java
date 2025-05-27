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

/**
 * Enum defining the supported value types in the key-value store.
 * Enhanced with Java 21 Pattern Matching for switch and optimized enum handling.
 */
public enum ValueType
{
  CHARACTER,
  NUMBER,
  BOOLEAN,
  OBJECT;
  
  /**
   * Determines if the given object is compatible with this value type.
   * Uses Pattern Matching for switch to simplify type-based decisions.
   *
   * @param value the object to check
   * @return true if the object is compatible with this value type
   */
  public boolean isCompatible(Object value) {
    if (value == null) {
      return true; // null is compatible with all types
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
        case Short s -> true;
        case Byte b -> true;
        case String s -> {
          try {
            Double.parseDouble(s);
            yield true;
          } catch (NumberFormatException e) {
            yield false;
          }
        };
        default -> false;
      };
      case BOOLEAN -> switch (value) {
        case Boolean b -> true;
        case String s -> s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false");
        case Integer i -> i == 0 || i == 1;
        case Long l -> l == 0L || l == 1L;
        default -> false;
      };
      case OBJECT -> true; // All objects are compatible with OBJECT type
    };
  }
  
  /**
   * Converts the given object to a type compatible with this value type.
   * Uses Pattern Matching for switch to simplify type conversion.
   *
   * @param value the object to convert
   * @return the converted object
   */
  public Object convertValue(Object value) {
    if (value == null) {
      return null;
    }
    
    return switch (this) {
      case CHARACTER -> switch (value) {
        case String s -> s;
        case Character c -> c.toString();
        default -> value.toString();
      };
      case NUMBER -> switch (value) {
        case Integer i -> i;
        case Long l -> l;
        case Double d -> d;
        case Float f -> f;
        case Short s -> s.intValue();
        case Byte b -> b.intValue();
        case String s -> {
          try {
            if (s.contains(".")) {
              yield Double.parseDouble(s);
            } else {
              yield Integer.parseInt(s);
            }
          } catch (NumberFormatException e) {
            yield 0;
          }
        };
        default -> 0;
      };
      case BOOLEAN -> switch (value) {
        case Boolean b -> b;
        case String s -> Boolean.parseBoolean(s);
        case Integer i -> i != 0;
        case Long l -> l != 0L;
        default -> false;
      };
      case OBJECT -> value;
    };
  }
  
  /**
   * Gets the default value for this value type.
   * Uses enhanced switch expressions for cleaner code.
   *
   * @return the default value
   */
  public Object getDefaultValue() {
    return switch (this) {
      case CHARACTER -> "";
      case NUMBER -> 0;
      case BOOLEAN -> false;
      case OBJECT -> null;
    };
  }
  
  /**
   * Determines the most appropriate ValueType for the given object.
   * Uses Pattern Matching for switch to simplify type detection.
   *
   * @param value the object to analyze
   * @return the most appropriate ValueType
   */
  public static ValueType fromObject(Object value) {
    if (value == null) {
      return OBJECT;
    }
    
    return switch (value) {
      case String s -> CHARACTER;
      case Character c -> CHARACTER;
      case Integer i -> NUMBER;
      case Long l -> NUMBER;
      case Double d -> NUMBER;
      case Float f -> NUMBER;
      case Short s -> NUMBER;
      case Byte b -> NUMBER;
      case Boolean b -> BOOLEAN;
      default -> OBJECT;
    };
  }
  
  /**
   * Optimized method to check if this ValueType is numeric.
   * Uses constant-time comparison for improved performance in high-throughput scenarios.
   *
   * @return true if this ValueType is numeric
   */
  public boolean isNumeric() {
    return this == NUMBER;
  }
  
  /**
   * Optimized method to check if this ValueType is textual.
   * Uses constant-time comparison for improved performance in high-throughput scenarios.
   *
   * @return true if this ValueType is textual
   */
  public boolean isTextual() {
    return this == CHARACTER;
  }
  
  /**
   * Optimized method to check if this ValueType is boolean.
   * Uses constant-time comparison for improved performance in high-throughput scenarios.
   *
   * @return true if this ValueType is boolean
   */
  public boolean isBoolean() {
    return this == BOOLEAN;
  }
  
  /**
   * Optimized method to check if this ValueType is an object.
   * Uses constant-time comparison for improved performance in high-throughput scenarios.
   *
   * @return true if this ValueType is an object
   */
  public boolean isObject() {
    return this == OBJECT;
  }
}