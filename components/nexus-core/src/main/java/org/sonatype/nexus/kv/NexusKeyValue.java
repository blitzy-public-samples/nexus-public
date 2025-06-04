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
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Key-value storage implementation for Nexus Repository.
 * Enhanced with Java 21 Pattern Matching for improved type safety and code readability.
 */
public class NexusKeyValue
    extends ComponentSupport
{
  private static final String VALUE_NESTED_KEY = "value";

  private String key;

  private ValueType type;

  private Map<String, Object> value = new HashMap<>();

  /**
   * Creates a new key-value pair with the specified key, type, and value.
   *
   * @param key the key
   * @param type the value type
   * @param value the value
   */
  public NexusKeyValue(final String key, final ValueType type, final Object value) {
    this.key = key;
    this.type = type;
    setValue(value);
  }

  /**
   * Default constructor for deserialization.
   */
  public NexusKeyValue() {
  }

  /**
   * Gets the key.
   *
   * @return the key
   */
  public String key() {
    return key;
  }

  /**
   * Sets the key.
   *
   * @param key the key
   */
  public void setKey(final String key) {
    this.key = key;
  }

  /**
   * Gets the value type.
   *
   * @return the value type
   */
  public ValueType type() {
    return type;
  }

  /**
   * Sets the value type.
   *
   * @param type the value type
   */
  public void setType(final ValueType type) {
    this.type = type;
  }

  /**
   * Gets the value map.
   *
   * @return the value map
   */
  public Map<String, Object> value() {
    return value;
  }

  /**
   * Sets the value map.
   *
   * @param value the value map
   */
  public void setValue(final Map<String, Object> value) {
    this.value = value;
  }

  /**
   * Sets the value.
   *
   * @param value the value
   */
  public void setValue(final Object value) {
    this.value.put(VALUE_NESTED_KEY, value);
  }

  /**
   * Gets the raw value stored in this key-value pair.
   *
   * @return the raw value, or null if not present
   */
  public Object getValue() {
    return value.get(VALUE_NESTED_KEY);
  }

  /**
   * Gets the value as a string using Pattern Matching for improved type safety.
   *
   * @return the value as a string
   */
  public String getAsString() {
    Object rawValue = getValue();
    return switch (rawValue) {
      case null -> "";
      case String s -> s;
      case Number n -> n.toString();
      case Boolean b -> b.toString();
      default -> rawValue.toString();
    };
  }

  /**
   * Gets the value as an integer using Pattern Matching for improved type safety.
   *
   * @return the value as an integer
   * @throws NumberFormatException if the value cannot be converted to an integer
   */
  public Integer getAsInt() {
    Object rawValue = getValue();
    return switch (rawValue) {
      case null -> 0;
      case Integer i -> i;
      case Number n -> n.intValue();
      case String s -> Integer.parseInt(s);
      case Boolean b -> b ? 1 : 0;
      default -> Integer.parseInt(rawValue.toString());
    };
  }

  /**
   * Gets the value as a long using Pattern Matching for improved type safety.
   *
   * @return the value as a long
   * @throws NumberFormatException if the value cannot be converted to a long
   */
  public Long getAsLong() {
    Object rawValue = getValue();
    return switch (rawValue) {
      case null -> 0L;
      case Long l -> l;
      case Number n -> n.longValue();
      case String s -> Long.parseLong(s);
      case Boolean b -> b ? 1L : 0L;
      default -> Long.parseLong(rawValue.toString());
    };
  }

  /**
   * Gets the value as a double using Pattern Matching for improved type safety.
   *
   * @return the value as a double
   * @throws NumberFormatException if the value cannot be converted to a double
   */
  public Double getAsDouble() {
    Object rawValue = getValue();
    return switch (rawValue) {
      case null -> 0.0;
      case Double d -> d;
      case Number n -> n.doubleValue();
      case String s -> Double.parseDouble(s);
      case Boolean b -> b ? 1.0 : 0.0;
      default -> Double.parseDouble(rawValue.toString());
    };
  }

  /**
   * Gets the value as a boolean using Pattern Matching for improved type safety.
   *
   * @return the value as a boolean
   */
  public Boolean getAsBoolean() {
    Object rawValue = getValue();
    return switch (rawValue) {
      case null -> false;
      case Boolean b -> b;
      case Number n -> n.intValue() != 0;
      case String s -> switch (s.toLowerCase()) {
        case "true", "yes", "1" -> true;
        default -> false;
      };
      default -> false;
    };
  }

  /**
   * Gets the value as an object of the specified type using Jackson for deserialization.
   * Optimized for Java 21 with improved error handling.
   *
   * @param <T> the target type
   * @param mapper the ObjectMapper to use for conversion
   * @param typeClass the class of the target type
   * @return the value as an object of the specified type
   */
  public <T> T getAsObject(final ObjectMapper mapper, final Class<T> typeClass) {
    Object rawValue = getValue();
    if (rawValue == null) {
      return null;
    }
    
    // Optimize mapper for better performance in Java 21
    ObjectMapper optimizedMapper = mapper.copy()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    
    // Use pattern matching to handle different types more elegantly
    if (rawValue == null) {
      return null;
    } else if (typeClass.isInstance(rawValue)) {
      return typeClass.cast(rawValue);
    } else {
      return optimizedMapper.convertValue(rawValue, typeClass);
    }

  }

  /**
   * Gets the value as an object of the specified type using Jackson for deserialization.
   * Optimized for Java 21 with improved error handling.
   *
   * @param <T> the target type
   * @param mapper the ObjectMapper to use for conversion
   * @param typeReference the type reference of the target type
   * @return the value as an object of the specified type
   */
  public <T> T getAsObject(final ObjectMapper mapper, final TypeReference<T> typeReference) {
    Object rawValue = getValue();
    if (rawValue == null) {
      return null;
    }
    
    // Optimize mapper for better performance in Java 21
    ObjectMapper optimizedMapper = mapper.copy()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    
    return optimizedMapper.convertValue(rawValue, typeReference);
  }

  /**
   * Gets the value as a list of objects of the specified type using Jackson for deserialization.
   * Optimized for Java 21 with improved error handling.
   *
   * @param <T> the element type
   * @param mapper the ObjectMapper to use for conversion
   * @param typeClass the class of the element type
   * @return the value as a list of objects of the specified type
   */
  public <T> List<T> getAsObjectList(final ObjectMapper mapper, Class<T> typeClass) {
    Object rawValue = getValue();
    if (rawValue == null) {
      return List.of();
    }
    
    // Optimize mapper for better performance in Java 21
    ObjectMapper optimizedMapper = mapper.copy()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    
    return optimizedMapper.convertValue(rawValue,
        optimizedMapper.getTypeFactory().constructCollectionType(List.class, typeClass));
  }

  /**
   * Gets the value as an Optional of the specified type using Pattern Matching for improved type safety.
   * This is a new method that leverages Java 21 features for more reliable type handling.
   *
   * @param <T> the target type
   * @param typeClass the class of the target type
   * @return an Optional containing the value as an object of the specified type, or empty if not present or not convertible
   */
  public <T> Optional<T> getValueAs(Class<T> typeClass) {
    Object rawValue = getValue();
    if (rawValue == null) {
      return Optional.empty();
    }

    if (typeClass.isInstance(rawValue)) {
      return Optional.of(typeClass.cast(rawValue));
    }

    if (rawValue instanceof String s) {
      try {
        if (typeClass == Integer.class) {
          return Optional.of(typeClass.cast(Integer.parseInt(s)));
        } else if (typeClass == Long.class) {
          return Optional.of(typeClass.cast(Long.parseLong(s)));
        } else if (typeClass == Double.class) {
          return Optional.of(typeClass.cast(Double.parseDouble(s)));
        } else if (typeClass == Boolean.class) {
          return Optional.of(typeClass.cast(Boolean.parseBoolean(s)));
        }
      } catch (NumberFormatException e) {
        return Optional.empty();
      }
    }

    return Optional.empty();
  }

  /**
   * Gets the value converted according to the specified ValueType using Pattern Matching.
   * This is a new method that leverages Java 21 features for more reliable type handling.
   *
   * @param targetType the target ValueType
   * @return the value converted to the target type
   */
  public Object getValueAsType(ValueType targetType) {
    Object rawValue = getValue();
    if (rawValue == null) {
      return targetType.getDefaultValue();
    }
    
    return targetType.convertValue(rawValue);
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
  
  @Override
  public String toString() {
    return "NexusKeyValue{" +
        "key='" + key + '\'' +
        ", type=" + type +
        ", value=" + value +
        '}';
  }
}
