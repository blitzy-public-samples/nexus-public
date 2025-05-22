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
package org.sonatype.nexus.jmx.reflect;

import java.lang.reflect.Method;
import java.util.function.Supplier;

import javax.annotation.Nullable;
import javax.management.Descriptor;
import javax.management.ImmutableDescriptor;
import javax.management.MBeanAttributeInfo;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.jmx.MBeanAttribute;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * Reflection {@link MBeanAttribute}.
 *
 * @since 3.0
 */
public class ReflectionMBeanAttribute
  extends ComponentSupport
  implements MBeanAttribute
{
  private final MBeanAttributeInfo info;

  private final String name;

  private final Supplier target;

  private final Method getter;

  private final Method setter;

  public ReflectionMBeanAttribute(final MBeanAttributeInfo info,
                                  final Supplier target,
                                  @Nullable final Method getter,
                                  @Nullable final Method setter)
  {
    this.info = checkNotNull(info);
    this.name = info.getName();
    this.target = checkNotNull(target);
    this.getter = getter;
    this.setter = setter;
  }

  @Override
  public MBeanAttributeInfo getInfo() {
    return info;
  }

  @Override
  public String getName() {
    return name;
  }

  public Supplier getTarget() {
    return target;
  }

  @Nullable
  public Method getGetter() {
    return getter;
  }

  @Nullable
  public Method getSetter() {
    return setter;
  }

  private Object target() {
    Object result = target.get();
    checkState(result != null, "Target supplier returned null");
    return result;
  }

  // TODO: coercion for non-open types?

  @Override
  @Nullable
  public Object getValue() throws Exception {
    checkState(getter != null, "Getter method is not available");
    log.trace(STR."Get value: \{getter}");
    try {
      return getter.invoke(target());
    }
    catch (Exception e) {
      log.error(STR."Failed to get value for attribute \{name}", e);
      throw e;
    }
  }

  @Override
  public void setValue(@Nullable final Object value) throws Exception {
    checkState(setter != null, "Setter method is not available");
    log.trace(STR."Set value: \{value} -> \{setter}");
    try {
      setter.invoke(target(), value);
    }
    catch (Exception e) {
      log.error(STR."Failed to set value \{value} for attribute \{name}", e);
      throw e;
    }
  }

  @Override
  public String toString() {
    return STR."\{getClass().getSimpleName()}{name='\{name}'}"; 
  }

  //
  // Builder
  //

  /**
   * {@link ReflectionMBeanAttribute} builder.
   */
  public static class Builder
    extends ComponentSupport
  {
    private String name;

    private String description;

    private Supplier target;

    private Method getter;

    private Method setter;

    public Builder name(final String name) {
      this.name = name;
      return this;
    }

    public Builder description(final String description) {
      this.description = description;
      return this;
    }

    public Builder target(final Supplier target) {
      this.target = target;
      return this;
    }

    public Builder getter(final Method getter) {
      this.getter = getter;
      return this;
    }

    public Builder setter(final Method setter) {
      this.setter = setter;
      return this;
    }

    public ReflectionMBeanAttribute build() {
      checkState(name != null, "Name is required");
      checkState(target != null, "Target is required");
      checkState(getter != null || setter != null, "Either getter or setter is required");

      Descriptor getterDescriptor = null;
      if (getter != null) {
        getterDescriptor = DescriptorHelper.build(getter);
      }
      Descriptor setterDescriptor = null;
      if (setter != null) {
        setterDescriptor = DescriptorHelper.build(setter);
      }

      MBeanAttributeInfo info = new MBeanAttributeInfo(
          name,
          attributeType(getter, setter).getName(),
          description,
          getter != null, // readable
          setter != null, // writable
          isIs(getter),
          ImmutableDescriptor.union(getterDescriptor, setterDescriptor)
      );

      log.trace(STR."Building attribute with info: \{info}");
      return new ReflectionMBeanAttribute(info, target, getter, setter);
    }

    //
    // Helpers
    //

    /**
     * Returns the type of attribute.
     */
    private Class<?> attributeType(final Method getter, final Method setter) {
      // TODO: sanity check methods
      if (getter != null) {
        return getter.getReturnType();
      }
      else {
        return setter.getParameterTypes()[0];
      }
    }

    /**
     * Check if the method is an 'is' form getter.
     */
    private boolean isIs(final Method getter) {
      // TODO: sanity check method
      return getter != null && getter.getName().startsWith("is");
    }
  }
}