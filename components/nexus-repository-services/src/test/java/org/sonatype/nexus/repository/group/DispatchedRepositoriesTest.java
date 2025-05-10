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

import java.util.Set;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.group.GroupHandler.DispatchedRepositories;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class DispatchedRepositoriesTest
    extends TestSupport
{
  private static final String REPOSITORY_1 = "repository1";

  private static final String REPOSITORY_2 = "repository2";

  private static final String REPOSITORY_3 = "repository3";

  @Mock
  private Repository repository1;

  @Mock
  private Repository repository2;

  @Mock
  private Repository repository3;

  private DispatchedRepositories underTest;

  @BeforeEach
  public void setUp() throws Exception {
    underTest = new DispatchedRepositories();
    when(repository1.toString()).thenReturn(REPOSITORY_1);
    when(repository1.getName()).thenReturn(REPOSITORY_1);
    when(repository2.toString()).thenReturn(REPOSITORY_2);
    when(repository2.getName()).thenReturn(REPOSITORY_2);
    when(repository3.toString()).thenReturn(REPOSITORY_3);
    when(repository3.getName()).thenReturn(REPOSITORY_3);
  }

  @Test
  public void checkDispatchedRepositoryInsertionWillPreserveOrder() {
    underTest.add(repository1);
    underTest.add(repository2);
    underTest.add(repository3);

    Set<String> dispatched = underTest.getDispatched();
    assertEquals(3, dispatched.size(), "Should have 3 repositories");
    assertTrue(dispatched.toString().contains(String.format("[%s, %s, %s]", REPOSITORY_1, REPOSITORY_2, REPOSITORY_3)), 
        "Repositories should be in the correct order");
  }

  @Test
  public void checkDispatchedRepositoryInsertionWillPreserveOrderWhenAlternateSequence() {
    underTest.add(repository3);
    underTest.add(repository1);
    underTest.add(repository2);

    Set<String> dispatched = underTest.getDispatched();
    assertEquals(3, dispatched.size(), "Should have 3 repositories");
    assertTrue(dispatched.toString().contains(String.format("[%s, %s, %s]", REPOSITORY_3, REPOSITORY_1, REPOSITORY_2)),
        "Repositories should be in the correct order");
  }

  /**
   * Test using Java 21 Record Patterns to verify repository dispatching.
   * This demonstrates how to use pattern matching with records for more
   * expressive and type-safe testing.
   */
  @Test
  public void verifyDispatchedRepositoriesUsingRecordPatterns() {
    // Define a record to represent repository results
    record RepositoryResult(String name, boolean dispatched) {}
    
    // Add repositories in a specific order
    underTest.add(repository1);
    underTest.add(repository2);
    underTest.add(repository3);
    
    // Create result records using repository information
    RepositoryResult result1 = new RepositoryResult(repository1.getName(), true);
    RepositoryResult result2 = new RepositoryResult(repository2.getName(), true);
    RepositoryResult result3 = new RepositoryResult(repository3.getName(), true);
    
    // Use pattern matching to verify results
    if (result1 instanceof RepositoryResult(String name, boolean dispatched)) {
      assertEquals(REPOSITORY_1, name, "First repository name should match");
      assertTrue(dispatched, "First repository should be marked as dispatched");
      assertTrue(underTest.getDispatched().contains(name), "Dispatched set should contain the repository");
    }
    
    // Verify all repositories are in the dispatched set in the correct order
    var dispatchedNames = underTest.getDispatched().toArray(new String[0]);
    assertEquals(3, dispatchedNames.length, "Should have 3 dispatched repositories");
    assertEquals(REPOSITORY_1, dispatchedNames[0], "First dispatched repository should be repository1");
    assertEquals(REPOSITORY_2, dispatchedNames[1], "Second dispatched repository should be repository2");
    assertEquals(REPOSITORY_3, dispatchedNames[2], "Third dispatched repository should be repository3");
  }
}