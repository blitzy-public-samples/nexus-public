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
package org.sonatype.nexus.common.app;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.PACKAGE;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.ElementType.TYPE_PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Container annotation for multiple {@link FeatureFlag} annotations on the same element.
 * <p>
 * This annotation is not meant to be used directly. It is automatically applied when
 * multiple {@link FeatureFlag} annotations are used on the same element due to the
 * {@link java.lang.annotation.Repeatable} nature of the {@link FeatureFlag} annotation.
 * <p>
 * This annotation is retained at runtime to support reflection-based feature flag detection.
 * <p>
 * This annotation is compatible with Java 21 reflection and annotation processing.
 *
 * @since 3.19
 */
@Documented
@Retention(RUNTIME)
@Target({PACKAGE, TYPE, TYPE_PARAMETER})
public @interface FeatureFlagGroup
{
  /**
   * Returns the array of {@link FeatureFlag} annotations contained in this group.
   *
   * @return array of feature flag annotations
   */
  FeatureFlag[] value();
}