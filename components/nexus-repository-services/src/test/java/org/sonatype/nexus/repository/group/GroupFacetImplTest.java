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
package org.sonatype.nexus.repository.group;

import java.util.List;
import java.util.Optional;

import javax.validation.ConstraintViolation;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.collect.AttributesMap;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.cache.CacheController;
import org.sonatype.nexus.repository.cache.CacheInfo;
import org.sonatype.nexus.repository.cache.RepositoryCacheInvalidationService;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.config.ConfigurationFacet;
import org.sonatype.nexus.repository.group.GroupFacetImpl.Config;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.validation.ConstraintViolationFactory;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.google.common.collect.ImmutableList.copyOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.group.GroupFacetImpl.CONFIG_KEY;

@ExtendWith(MockitoExtension.class)
@Tag("Java21")
public class GroupFacetImplTest
    extends TestSupport
{
  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private ConfigurationFacet configurationFacet;

  @Mock
  private Format format = mock(Format.class);

  @Mock
  private Content content;

  @Mock
  private AttributesMap attributesMap;

  @Mock
  private CacheInfo cacheInfo;

  @Mock
  private RepositoryCacheInvalidationService repositoryCacheInvalidationService;

  private GroupType groupType = new GroupType();

  private GroupFacetImpl underTest;

  @BeforeEach
  public void setup() throws Exception {
    underTest = new GroupFacetImpl(repositoryManager, makeConstraintViolationFactory(), groupType,
        repositoryCacheInvalidationService);
    underTest.attach(makeRepositoryUnderTest());
  }

  @Test
  public void should_ValidateGroupDoesNotContainItself_WhenValid() {
    Config config = new Config();
    config.memberNames = ImmutableSet.of("repository1");
    assertNull(underTest.validateGroupDoesNotContainItself("repositoryUnderTest", config));
  }

  @Test
  public void should_FailValidation_WhenGroupContainsItself() {
    Config config = new Config();
    config.memberNames = ImmutableSet.of("repositoryUnderTest");
    assertNotNull(underTest.validateGroupDoesNotContainItself("repositoryUnderTest", config));
  }

  @Test
  public void should_FailValidation_WhenGroupContainsAGroupThatContainsItself() {
    Config config = new Config();
    config.memberNames = ImmutableSet.of("repository3");
    assertNotNull(underTest.validateGroupDoesNotContainItself("repositoryUnderTest", config));
  }

  @Test
  public void should_FailValidation_WhenGroupContainsNestedGroupWhichContainsItself() {
    Config config = new Config();
    config.memberNames = ImmutableSet.of("repository2");
    assertNotNull(underTest.validateGroupDoesNotContainItself("repositoryUnderTest", config));
  }

  @Test
  public void should_ReturnLeafMembers_WhenConfigured() throws Exception {
    Repository hosted1 = hostedRepository("hosted1");
    Repository hosted2 = hostedRepository("hosted2");
    Repository group1 = groupRepository("group1", hosted1);
    Config config = new Config();
    config.memberNames = ImmutableSet.of(hosted1.getName(), hosted2.getName(), group1.getName());
    Configuration configuration = mock(Configuration.class);
    when(configuration.attributes(CONFIG_KEY)).thenReturn(new NestedAttributesMap(
        "dummy",
        ImmutableMap.of("memberNames", config.memberNames)
    ));
    when(configurationFacet.readSection(configuration, CONFIG_KEY, Config.class)).thenReturn(config);
    underTest.doConfigure(configuration);
    assertIterableEquals(List.of(hosted1, hosted2), underTest.leafMembers());
  }

  @Test
  public void should_ReturnAllMembers_WhenAttached() throws Exception {
    Repository hosted1 = hostedRepository("hosted1");
    Repository group1 = groupRepository("group1", hosted1);
    underTest.attach(group1);

    for (Repository repo : underTest.allMembers()) {
      System.out.println(repo.getName());
    }
    assertIterableEquals(List.of(group1, hosted1), underTest.allMembers());
  }

  @Test
  public void should_ReturnStale_WhenContentIsNull() {
    assertTrue(underTest.isStale(null));
  }

  @Test
  public void should_ReturnStale_WhenCacheInfoIsNull() {
    when(content.getAttributes()).thenReturn(attributesMap);
    when(attributesMap.get(CacheInfo.class)).thenReturn(null);

    assertTrue(underTest.isStale(content));
  }

  @Test
  public void should_ReturnNotStale_WhenCacheIsPresent() {
    when(content.getAttributes()).thenReturn(attributesMap);
    when(attributesMap.get(CacheInfo.class)).thenReturn(cacheInfo);
    CacheController cacheController = mock(CacheController.class);
    underTest.cacheController = cacheController;
    when(cacheController.isStale(cacheInfo)).thenReturn(false);

    assertFalse(underTest.isStale(content));
  }

  private ConstraintViolationFactory makeConstraintViolationFactory() {
    ConstraintViolationFactory constraintViolationFactory = mock(ConstraintViolationFactory.class);
    doReturn(mock(ConstraintViolation.class))
        .when(constraintViolationFactory).createViolation(anyString(), anyString());
    return constraintViolationFactory;
  }

  private Repository makeRepositoryUnderTest() {
    Repository repositoryUnderTest = groupRepository("repositoryUnderTest");
    when(repositoryUnderTest.facet(GroupFacet.class)).thenReturn(underTest);
    when(repositoryUnderTest.facet(ConfigurationFacet.class)).thenReturn(configurationFacet);

    groupRepository("repository2",
        groupRepository("repository3",
            repositoryUnderTest,
            groupRepository("repository1")
        )
    );
    return repositoryUnderTest;
  }

  private Repository hostedRepository(final String name) {
    Repository hostedRepository = mock(Repository.class);
    when(hostedRepository.getType()).thenReturn(new HostedType());
    when(hostedRepository.getName()).thenReturn(name);
    when(hostedRepository.getFormat()).thenReturn(format);
    when(hostedRepository.optionalFacet(GroupFacet.class)).thenReturn(Optional.empty());
    when(repositoryManager.get(name)).thenReturn(hostedRepository);
    return hostedRepository;
  }

  private Repository groupRepository(final String name, final Repository... repositories) {
    Repository groupRepository = mock(Repository.class);
    when(groupRepository.getType()).thenReturn(groupType);
    when(groupRepository.getName()).thenReturn(name);
    when(groupRepository.getFormat()).thenReturn(format);
    when(repositoryManager.get(name)).thenReturn(groupRepository);
    GroupFacet groupFacet = mock(GroupFacet.class);
    when(groupRepository.facet(GroupFacet.class)).thenReturn(groupFacet);
    when(groupRepository.optionalFacet(GroupFacet.class)).thenReturn(Optional.of(groupFacet));
    when(groupFacet.members()).thenReturn(copyOf(repositories));
    return groupRepository;
  }
}
