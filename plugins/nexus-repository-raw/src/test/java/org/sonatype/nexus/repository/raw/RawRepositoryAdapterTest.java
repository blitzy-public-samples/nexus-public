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
package org.sonatype.nexus.repository.raw;

import java.util.Arrays;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.BaseUrlHolder;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.manager.internal.RepositoryImpl;
import org.sonatype.nexus.repository.raw.internal.RawFormat;
import org.sonatype.nexus.repository.rest.api.model.AbstractApiRepository;
import org.sonatype.nexus.repository.rest.api.model.SimpleApiGroupRepository;
import org.sonatype.nexus.repository.routing.RoutingRuleStore;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.types.ProxyType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.google.common.collect.Maps.newHashMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.AdditionalMatchers.not;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test for {@link RawRepositoryAdapter} that validates the adapter's functionality
 * for different repository types (group, hosted, proxy).
 * 
 * <p>This test has been updated to use JUnit Jupiter (JUnit 5) and MockitoExtension
 * for Java 21 compatibility.</p>
 */
@ExtendWith(MockitoExtension.class)
public class RawRepositoryAdapterTest
    extends TestSupport
{
  private RawRepositoryAdapter adapter;

  @Mock
  private RoutingRuleStore routingRuleStore;

  @BeforeEach
  public void setup() {
    adapter = new RawRepositoryAdapter(routingRuleStore);
    BaseUrlHolder.set("http://nexus-url", "");
  }

  /**
   * Tests the adaptation of a group repository.
   * 
   * <p>This test verifies that a group repository is correctly adapted to a {@link SimpleApiGroupRepository}.</p>
   */
  @Test
  public void testAdapt_groupRepository() throws Exception {
    // No maven specific props so simple smoke test
    Repository repository = createRepository(new GroupType());
    Configuration configuration = repository.getConfiguration();
    configuration.attributes("group").set("memberNames", Arrays.asList("a", "b"));
    repository.update(configuration);

    SimpleApiGroupRepository groupRepository = (SimpleApiGroupRepository) adapter.adapt(repository);
    assertRepository(groupRepository, "group", true);
  }

  /**
   * Tests the adaptation of a hosted repository.
   * 
   * <p>This test verifies that a hosted repository is correctly adapted to a {@link RawHostedApiRepository}.</p>
   */
  @Test
  public void testAdapt_hostedRepository() throws Exception {
    Repository repository = createRepository(new HostedType(), ContentDisposition.INLINE);

    RawHostedApiRepository hostedRepository = (RawHostedApiRepository) adapter.adapt(repository);
    assertRepository(hostedRepository, "hosted", true);
    assertThat(hostedRepository.getRaw().getContentDisposition(), is("INLINE"));
    // Check fields are populated, actual values validated with SimpleApiRepositoryAdapterTest
    assertThat(hostedRepository.getCleanup(), nullValue());
    assertThat(hostedRepository.getStorage(), notNullValue());
  }

  /**
   * Tests the adaptation of a proxy repository.
   * 
   * <p>This test verifies that a proxy repository is correctly adapted to a {@link RawProxyApiRepository}.</p>
   * <p>Proxy repositories are particularly important for remote content retrieval and would benefit 
   * from Java 21's Virtual Threads for I/O operations in production code.</p>
   */
  @Test
  public void testAdapt_proxyRepository() throws Exception {
    Repository repository = createRepository(new ProxyType(), ContentDisposition.INLINE);

    RawProxyApiRepository proxyRepository = (RawProxyApiRepository) adapter.adapt(repository);
    assertRepository(proxyRepository, "proxy", true);
    assertThat(proxyRepository.getRaw().getContentDisposition(), is("INLINE"));
    // Check fields are populated, actual values validated with SimpleApiRepositoryAdapterTest
    assertThat(proxyRepository.getCleanup(), nullValue());
    assertThat(proxyRepository.getHttpClient(), notNullValue());
    assertThat(proxyRepository.getNegativeCache(), notNullValue());
    assertThat(proxyRepository.getProxy(), notNullValue());
    assertThat(proxyRepository.getStorage(), notNullValue());
  }

  /**
   * Helper method to assert common repository properties.
   * 
   * <p>This method verifies that the repository has the expected format, name, online status, type, and URL.</p>
   * 
   * @param repository the repository to check
   * @param type the expected repository type
   * @param online the expected online status
   */
  private static void assertRepository(
      final AbstractApiRepository repository, final String type, final Boolean online)
  {
    assertThat(repository.getFormat(), is("raw"));
    assertThat(repository.getName(), is("my-repo"));
    assertThat(repository.getOnline(), is(online));
    assertThat(repository.getType(), is(type));
    assertThat(repository.getUrl(), is(BaseUrlHolder.get() + "/repository/my-repo"));
  }

  /**
   * Creates a mock configuration for testing.
   * 
   * <p>This method creates a mock {@link Configuration} with the specified repository name.</p>
   * 
   * @param repositoryName the name of the repository
   * @return a mock configuration
   */
  private static Configuration config(final String repositoryName) {
    Configuration configuration = mock(Configuration.class);
    when(configuration.isOnline()).thenReturn(true);
    when(configuration.getRepositoryName()).thenReturn(repositoryName);
    when(configuration.attributes(not(eq("raw")))).thenReturn(new NestedAttributesMap("dummy", newHashMap()));
    return configuration;
  }

  /**
   * Creates a repository with the specified type for testing.
   * 
   * <p>This method creates a {@link Repository} with the specified type and initializes it with a default configuration.</p>
   * 
   * @param type the repository type
   * @return the created repository
   * @throws Exception if an error occurs during repository creation
   */
  private static Repository createRepository(final Type type) throws Exception {
    Repository repository = new RepositoryImpl(Mockito.mock(EventManager.class), type, new RawFormat());
    repository.init(config("my-repo"));
    return repository;
  }

  /**
   * Creates a repository with the specified type and content disposition for testing.
   * 
   * <p>This method creates a {@link Repository} with the specified type and content disposition,
   * and initializes it with a configuration that includes the content disposition setting.</p>
   * 
   * @param type the repository type
   * @param contentDisposition the content disposition setting
   * @return the created repository
   * @throws Exception if an error occurs during repository creation
   */
  private static Repository createRepository(
      final Type type, final ContentDisposition contentDisposition) throws Exception
  {
    Repository repository = new RepositoryImpl(Mockito.mock(EventManager.class), type, new RawFormat());

    Configuration configuration = config("my-repo");
    NestedAttributesMap raw = new NestedAttributesMap("raw", newHashMap());
    raw.set("contentDisposition", contentDisposition.toString());
    when(configuration.attributes("raw")).thenReturn(raw);
    repository.init(configuration);
    return repository;
  }
}
