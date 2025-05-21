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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.sonatype.goodies.common.ComponentSupport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;

/**
 * Key-value storage implementation for Nexus Repository Manager.
 * Enhanced with Java 21 Pattern Matching for type conversion and improved type safety.
 */
public class NexusKeyValue
    extends ComponentSupport
{
  private static final String VALUE_NESTED_KEY = "value";

  private String key;

  private ValueType type;

  private Map<String, Object> value = new HashMap<>();

  public NexusKeyValue(final String key, final ValueType type, final Object value) {
    this.key = key;
    this.type = type;
    setValue(value);
  }

  public NexusKeyValue() {
  }

  public String key() {
    return key;
  }

  public void setKey(final String key) {
    this.key = key;
  }

  public ValueType type() {
    return type;
  }

  public void setType(final ValueType type) {
    this.type = type;
  }

  public Map<String, Object> value() {
    return value;
  }

  public void setValue(final Map<String, Object> value) {
    this.value = value;
  }

  public void setValue(final Object value) {
    this.value.put(VALUE_NESTED_KEY, value);
  }

  /**
   * Gets the raw value stored in this key-value pair.
   *
   * @return the raw value object
   */
  public Object getValue() {
    return value.get(VALUE_NESTED_KEY);
  }

  /**
   * Gets the value converted to the appropriate type based on the ValueType.
   * Uses Pattern Matching for switch to handle different types more elegantly.
   *
   * @return the converted value
   */
  public Object getValueAs() {
    Object rawValue = getValue();
    if (rawValue == null) {
      return null;
    }
    
    return switch (type) {
      case CHARACTER -> switch (rawValue) {
        case String s -> s;
        default -> rawValue.toString();
      };
      case NUMBER -> switch (rawValue) {
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
            log.warn("Failed to parse number from string: {}", s, e);
            yield 0;
          }
        }
        default -> {
          log.warn("Unexpected type for NUMBER: {}", rawValue.getClass().getName());
          yield 0;
        }
      };
      case BOOLEAN -> switch (rawValue) {
        case Boolean b -> b;
        case String s -> Boolean.parseBoolean(s);
        case Integer i -> i != 0;
        default -> {
          log.warn("Unexpected type for BOOLEAN: {}", rawValue.getClass().getName());
          yield false;
        }
      };
      case OBJECT -> rawValue;
    };
  }

  /**
   * Gets the value as a string, using Pattern Matching for improved type handling.
   *
   * @return the value as a string
   */
  public String getAsString() {
    Object rawValue = getValue();
    return switch (rawValue) {
      case null -> "";
      case String s -> s;
      default -> rawValue.toString();
    };
  }

  /**
   * Gets the value as an integer, using Pattern Matching for improved type handling.
   *
   * @return the value as an integer
   */
  public Integer getAsInt() {
    Object rawValue = getValue();
    return switch (rawValue) {
      case null -> 0;
      case Integer i -> i;
      case Long l -> l.intValue();
      case Double d -> d.intValue();
      case Float f -> f.intValue();
      case String s -> {
        try {
          yield Integer.parseInt(s);
        } catch (NumberFormatException e) {
          log.warn("Failed to parse integer from string: {}", s, e);
          yield 0;
        }
      }
      default -> {
        log.warn("Unexpected type for integer conversion: {}", rawValue.getClass().getName());
        yield 0;
      }
    };
  }

  /**
   * Gets the value as a long, using Pattern Matching for improved type handling.
   *
   * @return the value as a long
   */
  public Long getAsLong() {
    Object rawValue = getValue();
    return switch (rawValue) {
      case null -> 0L;
      case Integer i -> i.longValue();
      case Long l -> l;
      case Double d -> d.longValue();
      case Float f -> f.longValue();
      case String s -> {
        try {
          yield Long.parseLong(s);
        } catch (NumberFormatException e) {
          log.warn("Failed to parse long from string: {}", s, e);
          yield 0L;
        }
      }
      default -> {
        log.warn("Unexpected type for long conversion: {}", rawValue.getClass().getName());
        yield 0L;
      }
    };
  }

  /**
   * Gets the value as a double, using Pattern Matching for improved type handling.
   *
   * @return the value as a double
   */
  public Double getAsDouble() {
    Object rawValue = getValue();
    return switch (rawValue) {
      case null -> 0.0;
      case Integer i -> i.doubleValue();
      case Long l -> l.doubleValue();
      case Double d -> d;
      case Float f -> f.doubleValue();
      case String s -> {
        try {
          yield Double.parseDouble(s);
        } catch (NumberFormatException e) {
          log.warn("Failed to parse double from string: {}", s, e);
          yield 0.0;
        }
      }
      default -> {
        log.warn("Unexpected type for double conversion: {}", rawValue.getClass().getName());
        yield 0.0;
      }
    };
  }

  /**
   * Gets the value as a boolean, using Pattern Matching for improved type handling.
   *
   * @return the value as a boolean
   */
  public Boolean getAsBoolean() {
    Object rawValue = getValue();
    return switch (rawValue) {
      case null -> false;
      case Boolean b -> b;
      case String s -> Boolean.parseBoolean(s);
      case Integer i -> i != 0;
      case Long l -> l != 0;
      default -> {
        log.warn("Unexpected type for boolean conversion: {}", rawValue.getClass().getName());
        yield false;
      }
    };
  }

  /**
   * Gets the value as an object of the specified class, using Jackson for conversion.
   * Optimized for Java 21 with improved error handling.
   *
   * @param mapper the ObjectMapper to use for conversion
   * @param typeClass the class to convert to
   * @return the value as an object of the specified class
   */
  public <T> T getAsObject(final ObjectMapper mapper, final Class<T> typeClass) {
    Object rawValue = getValue();
    if (rawValue == null) {
      return null;
    }
    
    try {
      // Configure mapper for better performance with Java 21
      ObjectMapper optimizedMapper = mapper.copy()
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
      
      return switch (rawValue) {
        case null -> null;
        default -> {
          if (typeClass.isInstance(rawValue)) {
            yield typeClass.cast(rawValue);
          } else {
            yield optimizedMapper.convertValue(rawValue, typeClass);
          }
        }
      };
    } catch (Exception e) {
      log.warn("Failed to convert value to {}: {}", typeClass.getName(), e.getMessage());
      return null;
    }
  }

  /**
   * Gets the value as an object of the specified type reference, using Jackson for conversion.
   * Optimized for Java 21 with improved error handling.
   *
   * @param mapper the ObjectMapper to use for conversion
   * @param typeReference the type reference to convert to
   * @return the value as an object of the specified type reference
   */
  public <T> T getAsObject(final ObjectMapper mapper, final TypeReference<T> typeReference) {
    Object rawValue = getValue();
    if (rawValue == null) {
      return null;
    }
    
    try {
      // Configure mapper for better performance with Java 21
      ObjectMapper optimizedMapper = mapper.copy()
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
      
      return optimizedMapper.convertValue(rawValue, typeReference);
    } catch (Exception e) {
      log.warn("Failed to convert value to type reference: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Gets the value as a list of objects of the specified class, using Jackson for conversion.
   * Optimized for Java 21 with improved error handling.
   *
   * @param mapper the ObjectMapper to use for conversion
   * @param typeClass the class of the list elements
   * @return the value as a list of objects of the specified class
   */
  public <T> List<T> getAsObjectList(final ObjectMapper mapper, Class<T> typeClass) {
    Object rawValue = getValue();
    if (rawValue == null) {
      return List.of();
    }
    
    try {
      // Configure mapper for better performance with Java 21
      ObjectMapper optimizedMapper = mapper.copy()
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
      
      return switch (rawValue) {
        case List<?> list -> {
          if (list.isEmpty() || typeClass.isInstance(list.get(0))) {
            @SuppressWarnings("unchecked")
            List<T> typedList = (List<T>) list;
            yield typedList;
          } else {
            yield optimizedMapper.convertValue(list,
                optimizedMapper.getTypeFactory().constructCollectionType(List.class, typeClass));
          }
        }
        default -> optimizedMapper.convertValue(rawValue,
            optimizedMapper.getTypeFactory().constructCollectionType(List.class, typeClass));
      };
    } catch (Exception e) {
      log.warn("Failed to convert value to list of {}: {}", typeClass.getName(), e.getMessage());
      return List.of();
    }
  }

  /**
   * Gets the value as an Optional of the specified class, using Pattern Matching for improved type handling.
   * This method provides a null-safe way to get values.
   *
   * @param typeClass the class to convert to
   * @return an Optional containing the value as an object of the specified class, or empty if conversion fails
   */
  public <T> Optional<T> getAsOptional(Class<T> typeClass) {
    Object rawValue = getValue();
    return switch (rawValue) {
      case null -> Optional.empty();
      default -> {
        if (typeClass.isInstance(rawValue)) {
          yield Optional.of(typeClass.cast(rawValue));
        } else {
          try {
            if (typeClass == String.class) {
              @SuppressWarnings("unchecked")
              T result = (T) rawValue.toString();
              yield Optional.of(result);
            } else if (typeClass == Integer.class && rawValue instanceof Number n) {
              @SuppressWarnings("unchecked")
              T result = (T) Integer.valueOf(n.intValue());
              yield Optional.of(result);
            } else if (typeClass == Long.class && rawValue instanceof Number n) {
              @SuppressWarnings("unchecked")
              T result = (T) Long.valueOf(n.longValue());
              yield Optional.of(result);
            } else if (typeClass == Double.class && rawValue instanceof Number n) {
              @SuppressWarnings("unchecked")
              T result = (T) Double.valueOf(n.doubleValue());
              yield Optional.of(result);
            } else if (typeClass == Boolean.class) {
              @SuppressWarnings("unchecked")
              T result = (T) Boolean.valueOf(getAsBoolean());
              yield Optional.of(result);
            } else {
              yield Optional.empty();
            }
          } catch (Exception e) {
            log.debug("Failed to convert value to {}: {}", typeClass.getName(), e.getMessage());
            yield Optional.empty();
          }
        }
      }
    };
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    NexusKeyValue that = (NexusKeyValue) o;
    return Objects.equals(key, that.key) && type == that.type && Objects.equals(value, that.value);
  }

  @Override
  public int hashCode() {
    return Objects.hash(key, type, value);
  }
}
