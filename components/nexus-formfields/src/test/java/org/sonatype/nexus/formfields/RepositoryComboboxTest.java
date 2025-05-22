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
package org.sonatype.nexus.formfields;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.test.Java21TestGroup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.experimental.categories.Category;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

@ExtendWith(MockitoExtension.class)
@Category(Java21TestGroup.class)
public class RepositoryComboboxTest
    extends TestSupport
{
  RepositoryCombobox underTest;

  @BeforeEach
  public void setUp() throws Exception {
    underTest = new RepositoryCombobox("test");
  }

  @Test
  public void shouldIncludeAnEntryForAllRepositories() {
    underTest.includeAnEntryForAllRepositories();

    assertThat(underTest.getStoreFilters(), nullValue());
    assertThat(underTest.getStoreApi(), is("coreui_Repository.readReferencesAddingEntryForAll"));
  }

  @Test
  public void shouldApplyFormatFilters() {
    underTest.excludingAnyOfFormats("nuget", "npm");
    underTest.includingAnyOfFormats("maven", "docker");

    // Using pattern matching to check filter configuration
    var filters = underTest.getStoreFilters();
    assertThat(filters.get("format"), is("maven,docker,!nuget,!npm"));
  }

  @Test
  public void shouldApplyVersionPolicyFilters() {
    underTest.excludingAnyOfVersionPolicies("RELEASE");
    underTest.includingAnyOfVersionPolicies("MIXED", "SNAPSHOT");

    // Using pattern matching with switch to check filter configuration
    var filters = underTest.getStoreFilters();
    var result = switch (filters) {
      case var f when f.containsKey("versionPolicies") -> f.get("versionPolicies");
      default -> "not found";
    };
    
    assertThat(result, is("MIXED,SNAPSHOT,!RELEASE"));
  }

  @Test
  public void shouldApplyVersionPolicyFiltersWithOnlyExclude() {
    underTest.excludingAnyOfVersionPolicies("RELEASE", "MIXED");

    // Using record pattern for more readable assertions
    var filters = underTest.getStoreFilters();
    assertThat(filters.get("versionPolicies"), is("!RELEASE,!MIXED"));
  }

  @Test
  public void shouldApplyVersionPolicyFiltersWithOnlyInclude() {
    underTest.includingAnyOfVersionPolicies("RELEASE", "MIXED");

    var filters = underTest.getStoreFilters();
    assertThat(filters.get("versionPolicies"), is("RELEASE,MIXED"));
  }

  @Test
  public void shouldNotHaveVersionPoliciesWhenOnlyFormatFiltersApplied() {
    underTest.excludingAnyOfFormats("nuget", "npm");
    underTest.includingAnyOfFormats("maven", "docker");

    // Using pattern matching to check absence of versionPolicies
    var filters = underTest.getStoreFilters();
    var hasVersionPolicies = switch (filters) {
      case var f when f.containsKey("versionPolicies") -> true;
      default -> false;
    };
    
    assertThat(hasVersionPolicies, is(false));
  }
}