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
package org.sonatype.nexus.repository.search.selector;

import java.util.Collections;
import java.util.Map;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.security.ContentPermissionChecker;
import org.sonatype.nexus.repository.security.VariableResolverAdapter;
import org.sonatype.nexus.repository.security.VariableResolverAdapterManager;
import org.sonatype.nexus.selector.VariableSource;

import org.apache.shiro.subject.Subject;
import org.elasticsearch.search.lookup.SourceLookup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.security.BreadActions.BROWSE;

/**
 * Tests for {@link ContentAuthPluginScript}.
 */
@ExtendWith(MockitoExtension.class)
@Category(Java21TestGroup.class)
public class ContentAuthPluginScriptTest
    extends TestSupport
{
  private static final String REPOSITORY_NAME = "repository-name";

  private static final String PATH = "path";

  private static final String FORMAT = "format";

  @Mock
  Subject subject;

  @Mock
  VariableSource variableSource;

  @Mock
  ContentPermissionChecker contentPermissionChecker;

  @Mock
  VariableResolverAdapterManager variableResolverAdapterManager;

  @Mock
  VariableResolverAdapter variableResolverAdapter;

  @Mock
  RepositoryManager repositoryManager;

  SourceLookup sourceLookup;

  ContentAuthPluginScript underTest;

  @BeforeEach
  public void setup() {
    sourceLookup = new SourceLookup();
    when(variableResolverAdapterManager.get(FORMAT)).thenReturn(variableResolverAdapter);
    when(variableResolverAdapter.fromSourceLookup(eq(sourceLookup), anyMap())).thenReturn(variableSource);
    when(repositoryManager.findContainingGroups(any())).thenReturn(Collections.emptyList());
    underTest = new ContentAuthPluginScript(subject, contentPermissionChecker,
        variableResolverAdapterManager, repositoryManager, true)
    {
      @Override
      protected SourceLookup getSourceLookup() {
        return sourceLookup;
      }
    };
  }

  @Test
  public void permittedReturnsTrue() {
    sourceLookup.setSource(Map.of(
        "format", FORMAT,
        "repository_name", REPOSITORY_NAME,
        "assets", Collections.singletonList(Collections.singletonMap("name", PATH))
    ));
    when(contentPermissionChecker.isPermitted(Collections.singleton(REPOSITORY_NAME), FORMAT, BROWSE, variableSource))
        .thenReturn(true);
    assertThat(underTest.run(), is(true));
    verify(contentPermissionChecker, times(1)).isPermitted(repositories -> repositories.equals(Collections.singleton(REPOSITORY_NAME)), 
        format -> format.equals(FORMAT), 
        action -> action.equals(BROWSE), 
        source -> source.equals(variableSource));
  }

  @Test
  public void notPermittedReturnsFalse() {
    sourceLookup.setSource(Map.of(
        "format", FORMAT,
        "repository_name", REPOSITORY_NAME,
        "assets", Collections.singletonList(Collections.singletonMap("name", PATH))
    ));
    when(contentPermissionChecker.isPermitted(Collections.singleton(REPOSITORY_NAME), FORMAT, BROWSE, variableSource))
        .thenReturn(false);
    assertThat(underTest.run(), is(false));
    verify(contentPermissionChecker, times(1)).isPermitted(repositories -> repositories.equals(Collections.singleton(REPOSITORY_NAME)), 
        format -> format.equals(FORMAT), 
        action -> action.equals(BROWSE), 
        source -> source.equals(variableSource));
  }

  @Test
  public void withoutAssetsReturnsFalse() {
    sourceLookup.setSource(Map.of(
        "format", FORMAT,
        "repository_name", REPOSITORY_NAME
    ));
    assertThat(underTest.run(), is(false));
    verifyNoInteractions(contentPermissionChecker);
  }
}