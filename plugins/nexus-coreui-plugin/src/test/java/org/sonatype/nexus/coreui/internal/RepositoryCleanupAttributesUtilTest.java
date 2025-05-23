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
package org.sonatype.nexus.coreui.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.coreui.RepositoryXO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.coreui.internal.RepositoryCleanupAttributesUtil.initializeCleanupAttributes;

@ExtendWith(MockitoExtension.class)
public class RepositoryCleanupAttributesUtilTest
    extends TestSupport
{
  private static final String CLEANUP_ATTRIBUTES_KEY = "cleanup";

  private static final String CLEANUP_NAME_KEY = "policyName";

  @Mock
  private RepositoryXO repositoryXO;

  private Map<String, Map<String, Object>> attributes = new HashMap<>();

  private Map<String, Object> cleanup = new HashMap<>();

  @BeforeEach
  public void setup() {
    when(repositoryXO.getAttributes()).thenReturn(attributes);
    attributes.put(CLEANUP_ATTRIBUTES_KEY, cleanup);
    cleanup.put(CLEANUP_NAME_KEY, asList("policy1", "policy2"));
  }

  @Test
  public void initializeCleanupAttributesShouldThrowExceptionWhenRepositoryXOIsNull() {
    assertThatThrownBy(() -> initializeCleanupAttributes(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  public void initializeCleanupAttributesShouldConvertPoliciesIntoSet() {
    initializeCleanupAttributes(repositoryXO);

    assertThat(getCleanupPolicyNames()).isInstanceOf(Set.class);
  }

  @Test
  public void initializeCleanupAttributesShouldReturnSamePoliciesInOrder() {
    List<String> listPolicyNames = getCleanupPolicyNamesAsList();
    initializeCleanupAttributes(repositoryXO);

    Set<String> setPolicyNames = getCleanupPolicyNamesAsSet();
    assertThat(setPolicyNames).isNotEmpty();

    int index = 0;
    for (String policyName : getCleanupPolicyNamesAsSet()) {
      assertThat(listPolicyNames.get(index)).isEqualTo(policyName);
      index++;
    }
  }

  @Test
  public void initializeCleanupAttributesShouldRemoveCleanupAttributeWhenNullPoliciesProvided() {
    cleanup.put(CLEANUP_NAME_KEY, null);

    initializeCleanupAttributes(repositoryXO);

    assertThat(getCleanupAttribute()).isNull();
  }

  @Test
  public void initializeCleanupAttributesShouldRemoveCleanupAttributeWhenEmptyPoliciesProvided() {
    cleanup.put(CLEANUP_NAME_KEY, emptyList());

    initializeCleanupAttributes(repositoryXO);

    assertThat(getCleanupAttribute()).isNull();
  }

  @Test
  public void initializeCleanupAttributesShouldNotAddCleanupAttributeWhenNotProvided() {
    attributes.put(CLEANUP_ATTRIBUTES_KEY, null);

    initializeCleanupAttributes(repositoryXO);

    assertThat(getCleanupAttribute()).isNull();
  }

  private Map<String, Object> getCleanupAttribute() {
    return repositoryXO.getAttributes().get(CLEANUP_ATTRIBUTES_KEY);
  }

  private Object getCleanupPolicyNames() {
    return getCleanupAttribute().get(CLEANUP_NAME_KEY);
  }

  @SuppressWarnings("unchecked")
  private Set<String> getCleanupPolicyNamesAsSet() {
    return (Set<String>) getCleanupPolicyNames();
  }

  @SuppressWarnings("unchecked")
  private List<String> getCleanupPolicyNamesAsList() {
    return (List<String>) getCleanupPolicyNames();
  }
}
