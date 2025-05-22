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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import javax.management.DescriptorKey;

/**
 * Helper to test {@link DescriptorKey}.
 * 
 * <p>This annotation is designed to be compatible with Java 21's enhanced reflection system
 * and stronger encapsulation rules. The {@link DescriptorKey} annotation is used to add
 * information to Descriptors for MBeans through annotations in the MBean interface.</p>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@interface TestComments
{
  /**
   * Returns the comments value to be used in the descriptor.
   * 
   * @return The comments value
   */
  @DescriptorKey("comments")
  String value();
}