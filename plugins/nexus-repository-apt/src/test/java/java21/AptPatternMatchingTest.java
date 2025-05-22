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
package java21;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.BaseUrlHolder;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.apt.AptFormat;
import org.sonatype.nexus.repository.apt.api.AptApiRepository;
import org.sonatype.nexus.repository.apt.api.AptApiRepositoryAdapter;
import org.sonatype.nexus.repository.apt.api.AptHostedApiRepository;
import org.sonatype.nexus.repository.apt.api.AptProxyApiRepository;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.manager.internal.RepositoryImpl;
import org.sonatype.nexus.repository.routing.RoutingRuleStore;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.types.ProxyType;

import com.google.common.collect.Maps;

/**
 * Test class demonstrating Java 21's Pattern Matching for instanceof with APT repository types.
 * 
 * This test shows how pattern matching simplifies type checking and eliminates explicit casting,
 * making the code more concise and less error-prone.
 */
public class AptPatternMatchingTest extends TestSupport
{
  private AptApiRepositoryAdapter adapter;

  @Mock
  private RoutingRuleStore routingRuleStore;

  @Before
  public void setup() {
    adapter = new AptApiRepositoryAdapter(routingRuleStore);
    BaseUrlHolder.set("http://nexus-url", "");
  }

  /**
   * Demonstrates the old way of type checking and casting with instanceof.
   */
  @Test
  public void testTraditionalInstanceOf() throws Exception {
    // Create test repositories
    Repository hostedRepo = createRepository(new HostedType(), "bionic", "keypair-data", "passphrase", null);
    Repository proxyRepo = createRepository(new ProxyType(), "focal", null, null, true);
    Repository groupRepo = createRepository(new GroupType(), "jammy", null, null, null);
    
    // Traditional approach with explicit type checking and casting
    AptApiRepository hostedApiRepo = adapter.adapt(hostedRepo);
    if (hostedApiRepo instanceof AptHostedApiRepository) {
      AptHostedApiRepository hosted = (AptHostedApiRepository) hostedApiRepo;
      assertThat(hosted.getApt().getDistribution(), is("bionic"));
      assertThat(hosted.getAptSigning().getKeypair(), is("keypair-data"));
    }
    
    AptApiRepository proxyApiRepo = adapter.adapt(proxyRepo);
    if (proxyApiRepo instanceof AptProxyApiRepository) {
      AptProxyApiRepository proxy = (AptProxyApiRepository) proxyApiRepo;
      assertThat(proxy.getApt().getDistribution(), is("focal"));
      assertThat(proxy.getApt().getFlat(), is(true));
    }
  }

  /**
   * Demonstrates Java 21's pattern matching for instanceof, which combines
   * type checking and variable declaration in a single step.
   */
  @Test
  public void testPatternMatchingInstanceOf() throws Exception {
    // Create test repositories
    Repository hostedRepo = createRepository(new HostedType(), "bionic", "keypair-data", "passphrase", null);
    Repository proxyRepo = createRepository(new ProxyType(), "focal", null, null, true);
    Repository groupRepo = createRepository(new GroupType(), "jammy", null, null, null);
    
    // Java 21 pattern matching approach - combines type checking and variable declaration
    AptApiRepository hostedApiRepo = adapter.adapt(hostedRepo);
    if (hostedApiRepo instanceof AptHostedApiRepository hosted) {
      // No explicit cast needed - 'hosted' is already the correct type
      assertThat(hosted.getApt().getDistribution(), is("bionic"));
      assertThat(hosted.getAptSigning().getKeypair(), is("keypair-data"));
    }
    
    AptApiRepository proxyApiRepo = adapter.adapt(proxyRepo);
    if (proxyApiRepo instanceof AptProxyApiRepository proxy) {
      // No explicit cast needed - 'proxy' is already the correct type
      assertThat(proxy.getApt().getDistribution(), is("focal"));
      assertThat(proxy.getApt().getFlat(), is(true));
    }
  }

  /**
   * Demonstrates pattern matching with conditional AND operations.
   * The pattern variable is in scope for the right side of the AND expression.
   */
  @Test
  public void testPatternMatchingWithConditional() throws Exception {
    Repository hostedRepo = createRepository(new HostedType(), "bionic", "keypair-data", "passphrase", null);
    Repository proxyRepo = createRepository(new ProxyType(), "focal", null, null, true);
    
    AptApiRepository hostedApiRepo = adapter.adapt(hostedRepo);
    AptApiRepository proxyApiRepo = adapter.adapt(proxyRepo);
    
    // Pattern matching with conditional AND - pattern variable is in scope on right side
    if (hostedApiRepo instanceof AptHostedApiRepository hosted && hosted.getAptSigning() != null) {
      assertThat(hosted.getAptSigning().getKeypair(), is("keypair-data"));
    }
    
    if (proxyApiRepo instanceof AptProxyApiRepository proxy && proxy.getApt().getFlat()) {
      assertThat(proxy.getApt().getDistribution(), is("focal"));
    }
  }

  /**
   * Demonstrates nested pattern matching with repository objects.
   */
  @Test
  public void testNestedPatternMatching() throws Exception {
    Repository hostedRepo = createRepository(new HostedType(), "bionic", "keypair-data", "passphrase", null);
    Object repoObject = adapter.adapt(hostedRepo);
    
    // Nested pattern matching - first check if it's an AptApiRepository, then check specific type
    if (repoObject instanceof AptApiRepository apiRepo) {
      if (apiRepo instanceof AptHostedApiRepository hosted) {
        assertThat(hosted.getApt().getDistribution(), is("bionic"));
        assertThat(hosted.getAptSigning().getKeypair(), is("keypair-data"));
      }
    }
    
    // More concise approach with a single pattern match
    if (repoObject instanceof AptHostedApiRepository hosted) {
      assertThat(hosted.getApt().getDistribution(), is("bionic"));
      assertThat(hosted.getAptSigning().getKeypair(), is("keypair-data"));
    }
  }

  /**
   * Helper method to create repository instances for testing.
   */
  private Repository createRepository(
      final Type type,
      final String distribution,
      final String keypair,
      final String passphrase,
      final Boolean flat) throws Exception
  {
    Repository repository = new RepositoryImpl(mock(EventManager.class), type, new AptFormat());

    Configuration configuration = mock(Configuration.class);
    when(configuration.isOnline()).thenReturn(true);
    when(configuration.getRepositoryName()).thenReturn("test-repo");

    Map<String, Object> apt = Maps.newHashMap();
    apt.put("distribution", distribution);
    apt.put("flat", flat);
    NestedAttributesMap aptNested = new NestedAttributesMap("apt", apt);
    when(configuration.attributes("apt")).thenReturn(aptNested);

    Map<String, Object> aptSigning = Maps.newHashMap();
    aptSigning.put("keypair", keypair);
    aptSigning.put("passphrase", passphrase);
    NestedAttributesMap aptSigningNested = new NestedAttributesMap("aptSigning", aptSigning);
    when(configuration.attributes("aptSigning")).thenReturn(aptSigningNested);

    repository.init(configuration);
    return repository;
  }
}