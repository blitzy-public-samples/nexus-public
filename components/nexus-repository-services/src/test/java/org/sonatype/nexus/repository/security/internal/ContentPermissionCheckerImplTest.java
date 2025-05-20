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
package org.sonatype.nexus.repository.security.internal;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.security.RepositoryContentSelectorPermission;
import org.sonatype.nexus.repository.security.RepositoryViewPermission;
import org.sonatype.nexus.security.BreadActions;
import org.sonatype.nexus.security.SecurityHelper;
import org.sonatype.nexus.selector.JexlSelector;
import org.sonatype.nexus.selector.SelectorConfiguration;
import org.sonatype.nexus.selector.SelectorManager;
import org.sonatype.nexus.selector.VariableSource;
import org.sonatype.nexus.virtualthread.Java21TestGroup;

import com.google.common.collect.Sets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("Java21")
public class ContentPermissionCheckerImplTest
    extends TestSupport
    implements Java21TestGroup
{
  @Mock
  SecurityHelper securityHelper;

  @Mock
  SelectorManager selectorManager;

  @Mock
  VariableSource variableSource;

  SelectorConfiguration config;

  ContentPermissionCheckerImpl impl;

  @BeforeEach
  public void setup() {
    impl = new ContentPermissionCheckerImpl(securityHelper, selectorManager);

    config = mock(SelectorConfiguration.class);
    when(config.getName()).thenReturn("selector");
    when(config.getDescription()).thenReturn("selector");
    when(config.getType()).thenReturn(JexlSelector.TYPE);
    when(config.getAttributes()).thenReturn(Collections.singletonMap("expression", "true"));

  }

  @Test
  public void testIsViewPermitted_permitted() throws Exception {
    when(securityHelper
        .anyPermitted(eq(new RepositoryViewPermission("repoFormat", "repoName", Arrays.asList(BreadActions.READ)))))
        .thenReturn(true);

    assertTrue(impl.isViewPermitted("repoName", "repoFormat", BreadActions.READ));
  }

  @Test
  public void testIsViewPermitted_notPermitted() throws Exception {
    assertFalse(impl.isViewPermitted("repoName", "repoFormat", BreadActions.READ));

    //just to make sure it was actually called, since returning false is the default behaviour
    verify(securityHelper)
        .anyPermitted(eq(new RepositoryViewPermission("repoFormat", "repoName", Arrays.asList(BreadActions.READ))));
  }

  @Test
  public void testIsContentPermitted_permitted() throws Exception {
    when(selectorManager.evaluate(any(), any())).thenReturn(true);

    when(securityHelper.anyPermitted(eq(new RepositoryContentSelectorPermission("selector", "repoFormat", "repoName",
        Arrays.asList(BreadActions.READ))))).thenReturn(true);

    assertTrue(impl.isContentPermitted("repoName", "repoFormat", BreadActions.READ, config, variableSource));
  }

  @Test
  public void testIsContentPermitted_notPermitted() throws Exception {
    when(selectorManager.evaluate(any(), any())).thenReturn(true);

    assertFalse(impl.isContentPermitted("repoName", "repoFormat", BreadActions.READ, config, variableSource));

    //just to make sure it was actually called, since returning false is the default behaviour
    verify(securityHelper).anyPermitted(eq(new RepositoryContentSelectorPermission("selector", "repoFormat", "repoName",
        Arrays.asList(BreadActions.READ))));
  }

  @Test
  public void testIsPermitted_viewPermittedContentPermitted() throws Exception {
    when(securityHelper
        .anyPermitted(eq(new RepositoryViewPermission("repoFormat", "repoName", Arrays.asList(BreadActions.READ)))))
        .thenReturn(true);

    when(selectorManager.browse()).thenReturn(Arrays.asList(config));

    when(selectorManager.evaluate(any(), any())).thenReturn(true);

    assertTrue(impl.isPermitted("repoName", "repoFormat", BreadActions.READ, variableSource));
  }

  @Test
  public void testIsPermitted_viewPermittedContentNotPermitted() throws Exception {
    when(securityHelper
        .anyPermitted(eq(new RepositoryViewPermission("repoFormat", "repoName", Arrays.asList(BreadActions.READ)))))
        .thenReturn(true);

    when(selectorManager.browse()).thenReturn(Arrays.asList(config));

    when(selectorManager.evaluate(any(), any())).thenReturn(false);

    assertTrue(impl.isPermitted("repoName", "repoFormat", BreadActions.READ, variableSource));
  }

  @Test
  public void testIsPermitted_viewNotPermittedContentPermitted() throws Exception {
    when(securityHelper
        .anyPermitted(eq(new RepositoryContentSelectorPermission("selector", "repoFormat", "repoName", Arrays.asList(BreadActions.READ)))))
        .thenReturn(true);

    when(selectorManager.browse()).thenReturn(Arrays.asList(config));

    when(selectorManager.evaluate(any(), any())).thenReturn(true);

    assertTrue(impl.isPermitted("repoName", "repoFormat", BreadActions.READ, variableSource));

    //just to validate 'view' permission didn't sneak in and authorize the above call
    verify(securityHelper).anyPermitted(eq(new RepositoryContentSelectorPermission("selector", "repoFormat", "repoName",
        Arrays.asList(BreadActions.READ))));
  }

  @Test
  public void testIsPermitted_viewNotPermittedContentNotPermitted() throws Exception {
    when(securityHelper
        .anyPermitted(eq(new RepositoryViewPermission("repoFormat", "repoName", Arrays.asList(BreadActions.READ)))))
        .thenReturn(false);

    when(selectorManager.browse()).thenReturn(Arrays.asList(config));

    when(selectorManager.evaluate(any(), any())).thenReturn(false);

    assertFalse(impl.isPermitted("repoName", "repoFormat", BreadActions.READ, variableSource));
  }

  @Test
  public void testIsViewPermittedMultipleRepositories_permitted() throws Exception {
    when(securityHelper
        .anyPermitted(
            eq(new RepositoryViewPermission("repoFormat", "repoName", Arrays.asList(BreadActions.READ))),
            eq(new RepositoryViewPermission("repoFormat", "repoName2", Arrays.asList(BreadActions.READ)))))
        .thenReturn(true);

    assertTrue(impl.isViewPermitted(Sets.newLinkedHashSet(Arrays.asList("repoName", "repoName2")), "repoFormat", BreadActions.READ));
  }

  @Test
  public void testIsViewPermittedMultipleRepositories_notPermitted() throws Exception {
    assertFalse(impl.isViewPermitted(Sets.newLinkedHashSet(Arrays.asList("repoName", "repoName2")), "repoFormat", BreadActions.READ));

    //just to make sure it was actually called, since returning false is the default behaviour
    verify(securityHelper)
        .anyPermitted(
            eq(new RepositoryViewPermission("repoFormat", "repoName", Arrays.asList(BreadActions.READ))),
            eq(new RepositoryViewPermission("repoFormat", "repoName2", Arrays.asList(BreadActions.READ))));
  }

  @Test
  public void testIsContentPermittedMultipleRepositories_permitted() throws Exception {
    when(selectorManager.evaluate(any(), any())).thenReturn(true);

    when(securityHelper
        .anyPermitted(
            eq(new RepositoryContentSelectorPermission("selector", "repoFormat", "repoName", Arrays.asList(BreadActions.READ))),
            eq(new RepositoryContentSelectorPermission("selector", "repoFormat", "repoName2", Arrays.asList(BreadActions.READ)))))
        .thenReturn(true);

    assertTrue(impl.isContentPermitted(Sets.newLinkedHashSet(Arrays.asList("repoName", "repoName2")), "repoFormat", BreadActions.READ, config, variableSource));
  }

  @Test
  public void testIsContentPermittedMultipleRepositories_notPermitted() throws Exception {
    when(selectorManager.evaluate(any(), any())).thenReturn(true);

    assertFalse(impl.isContentPermitted(Sets.newLinkedHashSet(Arrays.asList("repoName", "repoName2")), "repoFormat", BreadActions.READ, config, variableSource));

    //just to make sure it was actually called, since returning false is the default behaviour
    verify(securityHelper)
        .anyPermitted(
            eq(new RepositoryContentSelectorPermission("selector", "repoFormat", "repoName", Arrays.asList(BreadActions.READ))),
            eq(new RepositoryContentSelectorPermission("selector", "repoFormat", "repoName2", Arrays.asList(BreadActions.READ))));
  }

  @Test
  public void testIsPermittedMultipleRepositories_viewPermittedContentPermitted() throws Exception {
    when(securityHelper
        .anyPermitted(
            eq(new RepositoryViewPermission("repoFormat", "repoName", Arrays.asList(BreadActions.READ))),
            eq(new RepositoryViewPermission("repoFormat", "repoName2", Arrays.asList(BreadActions.READ)))))
        .thenReturn(true);

    when(selectorManager.browse()).thenReturn(Arrays.asList(config));

    when(selectorManager.evaluate(any(), any())).thenReturn(true);

    assertTrue(impl.isPermitted(Sets.newLinkedHashSet(Arrays.asList("repoName", "repoName2")), "repoFormat", BreadActions.READ, variableSource));
  }

  @Test
  public void testIsPermittedMultipleRepositories_viewPermittedContentNotPermitted() throws Exception {
    when(securityHelper
        .anyPermitted(
            eq(new RepositoryViewPermission("repoFormat", "repoName", Arrays.asList(BreadActions.READ))),
            eq(new RepositoryViewPermission("repoFormat", "repoName2", Arrays.asList(BreadActions.READ)))))
        .thenReturn(true);

    when(selectorManager.browse()).thenReturn(Arrays.asList(config));

    when(selectorManager.evaluate(any(), any())).thenReturn(false);

    assertTrue(impl.isPermitted(Sets.newLinkedHashSet(Arrays.asList("repoName", "repoName2")), "repoFormat", BreadActions.READ, variableSource));
  }

  @Test
  public void testIsPermittedMultipleRepositories_viewNotPermittedContentPermitted() throws Exception {
    when(securityHelper
        .anyPermitted(
            eq(new RepositoryContentSelectorPermission("selector", "repoFormat", "repoName", Arrays.asList(BreadActions.READ))),
            eq(new RepositoryContentSelectorPermission("selector", "repoFormat", "repoName2", Arrays.asList(BreadActions.READ)))))
        .thenReturn(true);

    Set<String> repositoryNames = Sets.newLinkedHashSet(Arrays.asList("repoName", "repoName2"));

    when(selectorManager.browseActive(repositoryNames, Collections.singletonList("repoFormat"))).thenReturn(Arrays.asList(config));

    when(selectorManager.evaluate(any(), any())).thenReturn(true);

    assertTrue(impl.isPermitted(Sets.newLinkedHashSet(Arrays.asList("repoName", "repoName2")), "repoFormat", BreadActions.READ, variableSource));

    //just to validate 'view' permission didn't sneak in and authorize the above call
    verify(securityHelper).anyPermitted(
        eq(new RepositoryContentSelectorPermission("selector", "repoFormat", "repoName", Arrays.asList(BreadActions.READ))),
        eq(new RepositoryContentSelectorPermission("selector", "repoFormat", "repoName2", Arrays.asList(BreadActions.READ))));
  }

  @Test
  public void testIsPermittedMultipleRepositories_viewNotPermittedContentNotPermitted() throws Exception {
    when(securityHelper
        .anyPermitted(
            eq(new RepositoryViewPermission("repoFormat", "repoName", Arrays.asList(BreadActions.READ))),
            eq(new RepositoryViewPermission("repoFormat", "repoName2", Arrays.asList(BreadActions.READ)))))
        .thenReturn(false);

    when(selectorManager.browse()).thenReturn(Arrays.asList(config));

    when(selectorManager.evaluate(any(), any())).thenReturn(false);

    assertFalse(impl.isPermitted(Sets.newHashSet("repoName", "repoName2"), "repoFormat", BreadActions.READ, variableSource));
  }
}
