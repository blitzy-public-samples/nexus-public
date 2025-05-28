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
 * Helper to test {@link DescriptorKey} annotation validation behavior with Java 21.
 * 
 * <p>This test annotation intentionally contains an invalid usage of {@link DescriptorKey}
 * to verify that Java 21's enhanced reflection system correctly validates annotation usage.
 * According to the JMX specification, an annotation element to be converted into a descriptor
 * field can be of any type allowed by the Java language, EXCEPT an annotation or an array of
 * annotations.</p>
 * 
 * <p>This test ensures that Java 21's stronger encapsulation rules and reflection system
 * still properly detect this invalid usage pattern, maintaining backward compatibility
 * with previous Java versions while enforcing the correct validation behavior.</p>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface TestInvalidAnnotationValue
{
  // DescriptorKey value is not allowed to be an annotation
  // This intentionally invalid usage tests Java 21's annotation validation behavior
  @DescriptorKey("invalid")
  TestComments value();
}
