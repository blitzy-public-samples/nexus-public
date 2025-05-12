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
package org.sonatype.nexus.content.matcher;

import org.sonatype.nexus.repository.content.Component;

import org.hamcrest.FeatureMatcher;
import org.hamcrest.Matcher;

/**
 * Provides Hamcrest matchers for {@link Component} objects to be used in JUnit Jupiter tests.
 * <p>
 * This class leverages Hamcrest 2.2 matchers for testing component properties.
 * 
 * @since 3.38
 */
public class ComponentMatcher
{
  /**
   * Creates a matcher that matches when the examined {@link Component}'s name
   * satisfies the specified matcher.
   *
   * @param matcher the matcher to apply to the component's name
   * @return a matcher that matches when the examined component's name satisfies the specified matcher
   */
  public static FeatureMatcher<Component, String> name(Matcher<String> matcher) {
    return new FeatureMatcher<Component, String>(matcher, "name", "name")
    {
      @Override
      protected String featureValueOf(Component actual) {
        // In more complex scenarios, Java 21 pattern matching could be used here
        // For example: if (actual instanceof CustomComponent custom) { return custom.getSpecialName(); }
        return actual.name();
      }
    };
  }
}