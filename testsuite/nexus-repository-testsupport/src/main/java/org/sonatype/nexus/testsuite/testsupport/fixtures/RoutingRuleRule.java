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
package org.sonatype.nexus.testsuite.testsupport.fixtures;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.inject.Provider;

import org.sonatype.nexus.repository.routing.RoutingMode;
import org.sonatype.nexus.repository.routing.RoutingRule;
import org.sonatype.nexus.repository.routing.RoutingRuleStore;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit Jupiter extension for managing {@link RoutingRule} instances in tests.
 * 
 * <p>This class is compatible with Java 21 and JUnit Jupiter 5.10.1.</p>
 * 
 * <p>Usage example:</p>
 * <pre>
 * {@code
 * @ExtendWith(RoutingRuleRule.class)
 * class MyTest {
 *   @Inject
 *   private Provider<RoutingRuleStore> ruleStoreProvider;
 *   
 *   @BeforeEach
 *   void setUp(RoutingRuleRule routingRuleRule) {
 *     // Create routing rules for test
 *     routingRuleRule.create("my-rule", ".*");
 *   }
 * }
 * }
 * </pre>
 */
public class RoutingRuleRule
    implements BeforeEachCallback, AfterEachCallback
{
  private final Provider<RoutingRuleStore> ruleStoreProvider;

  private final List<RoutingRule> rules = new ArrayList<>();

  public RoutingRuleRule(final Provider<RoutingRuleStore> ruleStoreProvider) {
    this.ruleStoreProvider = ruleStoreProvider;
  }

  /**
   * Create a RoutingRule with mode block and a dummy description
   *
   * @param name the name of the routing rule
   * @param pattern the pattern to match
   * @return the created routing rule
   */
  public RoutingRule create(final String name, final String pattern) {
    var routingRuleStore = ruleStoreProvider.get();
    return create(routingRuleStore.newRoutingRule()
        .name(name)
        .description("some description")
        .mode(RoutingMode.BLOCK)
        .matchers(Collections.singletonList(pattern))
    );
  }

  /**
   * Create a RoutingRule from the provided rule template
   *
   * @param routingRule the routing rule template to create
   * @return the created routing rule
   */
  public RoutingRule create(final RoutingRule routingRule) {
    var result = ruleStoreProvider.get().create(routingRule);
    rules.add(result);
    return result;
  }

  @Override
  public void beforeEach(final ExtensionContext context) {
    // No setup needed before each test
  }

  @Override
  public void afterEach(final ExtensionContext context) {
    // Clean up all created rules after each test
    for (RoutingRule rule : rules) {
      ruleStoreProvider.get().delete(rule);
    }
    rules.clear();
  }
}