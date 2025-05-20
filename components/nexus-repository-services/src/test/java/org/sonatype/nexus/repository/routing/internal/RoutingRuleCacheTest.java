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
package org.sonatype.nexus.repository.routing.internal;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.entity.DetachedEntityId;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.manager.RepositoryDeletedEvent;
import org.sonatype.nexus.repository.manager.RepositoryUpdatedEvent;
import org.sonatype.nexus.repository.routing.RoutingRule;
import org.sonatype.nexus.repository.routing.RoutingRuleStore;
import org.sonatype.nexus.virtualthread.Java21TestGroup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Category(Java21TestGroup.class)
public class RoutingRuleCacheTest
    extends TestSupport
{
  private RoutingRuleCache routingRuleCache;

  @Mock
  private RoutingRuleStore store;

  @Mock
  private EventManager eventManager;

  @BeforeEach
  public void setup() {
    routingRuleCache = new RoutingRuleCache(store);
  }

  @Test
  public void shouldInvalidateRepoCacheOnUpdate() throws Exception {
    mockRule("rule-a");
    Repository repository = createRepository("repo-a", "rule-a");

    // prime the cache
    assertNotNull(routingRuleCache.getRoutingRule(repository));

    // Clear the cache
    routingRuleCache.handle(new RepositoryUpdatedEvent(repository, null));

    // update the assigned rule
    RoutingRule rule = mockRule("rule-b");
    Configuration configuration = repository.getConfiguration();
    when(configuration.getRoutingRuleId()).thenReturn(new DetachedEntityId("rule-b"));

    assertThat(routingRuleCache.getRoutingRule(repository)).isEqualTo(rule);
  }

  @Test
  public void shouldInvalidateRepoCacheOnDelete() throws Exception {
    mockRule("rule-a");
    Repository repository = createRepository("repo-a", "rule-a");

    // prime the cache
    assertNotNull(routingRuleCache.getRoutingRule(repository));

    // Clear the cache
    routingRuleCache.handle(new RepositoryDeletedEvent(repository));

    // update the assigned rule
    RoutingRule rule = mockRule("rule-b");
    Configuration configuration = repository.getConfiguration();
    when(configuration.getRoutingRuleId()).thenReturn(new DetachedEntityId("rule-b"));

    assertThat(routingRuleCache.getRoutingRule(repository)).isEqualTo(rule);
  }

  @Test
  public void shouldInvalidateRuleCacheOnUpdate() throws Exception {
    RoutingRule rule = mockRule("rule-a");
    Repository repository = createRepository("repo-a", "rule-a");

    // prime the cache
    assertNotNull(routingRuleCache.getRoutingRule(repository));
    verify(store, times(1)).getById("rule-a");

    // Clear the cache
    // routingRuleCache.handle(new OrientRoutingRuleUpdatedEvent(rule.getEntityMetadata()));
    assertNotNull(routingRuleCache.getRoutingRule(repository));

    // we should have hit the store again
    verify(store, times(2)).getById("rule-a");
  }

  @Test
  public void shouldInvalidateRuleCacheOnDelete() throws Exception {
    RoutingRule rule = mockRule("rule-a");
    Repository repository = createRepository("repo-a", "rule-a");

    // prime the cache
    assertNotNull(routingRuleCache.getRoutingRule(repository));
    verify(store, times(1)).getById("rule-a");

    // Clear the cache
    //routingRuleCache.handle(new OrientRoutingRuleDeletedEvent(rule.getEntityMetadata()));
    assertNotNull(routingRuleCache.getRoutingRule(repository));

    // we should have hit the store again
    verify(store, times(2)).getById("rule-a");
  }

  @Test
  public void shouldReturnNullForBogusConfig() throws Exception {
    Repository repository = createRepository("missing-val", "");
    assertNull(routingRuleCache.getRoutingRule(repository));
  }

  @Test
  public void shouldReturnNullForNullRuleId() throws Exception {
    Repository repository = createRepository("null-id", null);
    assertNull(routingRuleCache.getRoutingRule(repository));
  }

  @Test
  public void shouldReturnNullForNotConfiguredRepository() throws Exception {
    Configuration configuration = mock(Configuration.class);
    when(configuration.getRepositoryName()).thenReturn("missing-config");
    Repository repository = createRepository(configuration);
    assertNull(routingRuleCache.getRoutingRule(repository));
  }

  @Test
  public void shouldGetRoutingRuleAndCacheResults() throws Exception {
    RoutingRule ruleA = mockRule("rule-a");
    Repository repository = createRepository("repo-a", "rule-a");

    // verify we get right value back
    assertThat(routingRuleCache.getRoutingRule(repository)).isEqualTo(ruleA);
    verify(store, times(1)).getById("rule-a");

    // verify we don't hit DB twice
    routingRuleCache.getRoutingRule(repository);
    verify(store, times(1)).getById("rule-a");

    // check another repo
    assertThat(routingRuleCache.getRoutingRule(createRepository("repo-b", "rule-a"))).isEqualTo(ruleA);
    verify(store, times(1)).getById("rule-a");

    RoutingRule ruleC = mockRule("rule-c");
    assertThat(routingRuleCache.getRoutingRule(createRepository("repo-c", "rule-c"))).isEqualTo(ruleC);
    verify(store, times(1)).getById("rule-c");
  }

  @Test
  public void shouldGetRoutingRuleId() throws Exception {
    Repository repository = createRepository("repo-a", "rule-a");

    // verify we get right value back
    assertThat(routingRuleCache.getRoutingRuleId(repository)).isEqualTo(new DetachedEntityId("rule-a"));
    verifyNoInteractions(store);
  }

  private RoutingRuleData mockRule(final String ruleId) {
    RoutingRuleData rule = mock(RoutingRuleData.class);
    when(rule.getId()).thenReturn(new DetachedEntityId(ruleId));
    when(store.getById(ruleId)).thenReturn(rule);
    return rule;
  }

  private Repository createRepository(final String name, final String repositoryRuleId) throws Exception {
    Configuration configuration = mock(Configuration.class);
    when(configuration.getRepositoryName()).thenReturn(name);
    if (repositoryRuleId != null) {
      when(configuration.getRoutingRuleId()).thenReturn(new DetachedEntityId(repositoryRuleId));
    }

    return createRepository(configuration);
  }

  private Repository createRepository(final Configuration configuration) throws Exception {
    Repository repo = mock(Repository.class);
    when(repo.getConfiguration()).thenReturn(configuration);
    return repo;
  }
}
