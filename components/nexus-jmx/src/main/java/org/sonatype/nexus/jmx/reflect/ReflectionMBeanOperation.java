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

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.function.Supplier;

import javax.annotation.Nullable;
import javax.management.Descriptor;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.jmx.MBeanOperation;
import org.sonatype.nexus.jmx.OperationKey;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * Reflection {@link MBeanOperation}.
 *
 * @since 3.0
 */
public class ReflectionMBeanOperation
  extends ComponentSupport
  implements MBeanOperation
{
  private final MBeanOperationInfo info;

  private final String name;

  private final OperationKey key;

  private final Supplier target;

  private final Method method;

  public ReflectionMBeanOperation(final MBeanOperationInfo info,
                                  final Supplier target,
                                  final Method method)
  {
    this.info = checkNotNull(info);
    this.name = info.getName();
    this.key = new OperationKey(info);
    this.target = checkNotNull(target);
    this.method = checkNotNull(method);
  }

  @Override
  public MBeanOperationInfo getInfo() {
    return info;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public OperationKey getKey() {
    return key;
  }

  public Supplier getTarget() {
    return target;
  }

  public Method getMethod() {
    return method;
  }

  private Object target() {
    Object result = target.get();
    checkState(result != null, "Target supplier returned null");
    return result;
  }

  /**
   * Invokes the underlying method with the given parameters.
   * Handles parameter conversion and error reporting.
   */
  @Override
  @Nullable
  public Object invoke(final Object[] params) throws Exception {
    try {
      log.trace(STR."Invoke: \{Arrays.asList(params)} -> \{method}");
      return method.invoke(target(), params);
    } catch (Exception e) {
      log.error(STR."Error invoking \{method} with parameters \{Arrays.asList(params)}", e);
      throw e;
    }
  }

  @Override
  public String toString() {
    return STR."\{getClass().getSimpleName()}{name='\{name}', key=\{key}}";
  }

  //
  // Builder
  //

  /**
   * {@link ReflectionMBeanOperation} builder.
   */
  public static class Builder
    extends ComponentSupport
  {
    private String name;

    private String description;

    private Supplier target;

    private Method method;

    private int impact = MBeanOperationInfo.UNKNOWN;

    public Builder name(final String name) {
      this.name = name;
      return this;
    }

    public Builder description(final String description) {
      this.description = description;
      return this;
    }

    public Builder target(final Supplier target) {
      this.target = checkNotNull(target, "Target supplier cannot be null");
      return this;
    }

    public Builder method(final Method method) {
      this.method = checkNotNull(method, "Method cannot be null");
      return this;
    }

    public Builder impact(final int impact) {
      this.impact = impact;
      return this;
    }

    public ReflectionMBeanOperation build() {
      checkState(target != null, "Target supplier is required");
      checkState(method != null, "Method is required");

      // default to method-name if not provided
      if (name == null) {
        name = method.getName();
      }

      MBeanOperationInfo info = new MBeanOperationInfo(
          name,
          description,
          signature(method),
          method.getReturnType().getName(),
          impact,
          DescriptorHelper.build(method)
      );

      log.trace(STR."Building operation with info: \{info}");
      return new ReflectionMBeanOperation(info, target, method);
    }

    //
    // Helpers
    //

    /**
     * Extract {@link MBeanParameterInfo} signature for given method using Java 21's native reflection.
     */
    private MBeanParameterInfo[] signature(final Method method) {
      Parameter[] parameters = method.getParameters();
      Class<?>[] types = method.getParameterTypes();
      Annotation[][] annotations = method.getParameterAnnotations();
      
      MBeanParameterInfo[] result = new MBeanParameterInfo[parameters.length];
      for (int i = 0; i < parameters.length; i++) {
        // Use record pattern to extract parameter information
        if (parameters[i] instanceof Parameter(String name, int modifiers, Class<?> type, boolean namePresent)) {
          Descriptor descriptor = DescriptorHelper.build(annotations[i]);
          String paramDescription = DescriptorHelper.stringValue(descriptor, "description");
          
          // Use the parameter name if available, otherwise generate a default name
          String paramName = namePresent ? name : "arg" + i;
          
          result[i] = new MBeanParameterInfo(
              paramName,
              types[i].getName(),
              paramDescription,
              descriptor
          );
        } else {
          // Fallback in case the record pattern doesn't match (shouldn't happen with standard Parameter implementation)
          Parameter param = parameters[i];
          Descriptor descriptor = DescriptorHelper.build(annotations[i]);
          String paramDescription = DescriptorHelper.stringValue(descriptor, "description");
          
          // Use the parameter name if available, otherwise generate a default name
          String paramName = param.isNamePresent() ? param.getName() : "arg" + i;
          
          result[i] = new MBeanParameterInfo(
              paramName,
              types[i].getName(),
              paramDescription,
              descriptor
          );
          
          log.debug(STR."Using fallback parameter extraction for \{method.getName()}[\{i}]: \{paramName}");
        }
      }

      return result;
    }
  }
}
