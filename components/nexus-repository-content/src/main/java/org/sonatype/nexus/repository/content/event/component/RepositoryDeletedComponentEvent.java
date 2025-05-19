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
package org.sonatype.nexus.repository.content.event.component;

import java.io.Serializable;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.store.ContentStoreEvent;

/**
 * Event sent when {@link Component}s are deleted during repository deletion.
 * <p>
 * This event is optimized for Virtual Thread execution context and efficient propagation
 * in high-concurrency environments.
 */
public class RepositoryDeletedComponentEvent
    extends ContentStoreEvent
    implements Serializable
{
  private static final long serialVersionUID = 1L;
  
  private final int deletedComponentsAmount;
  private final String repositoryName;
  private final String repositoryFormat;

  /**
   * Creates a new event for components deleted during repository deletion.
   *
   * @param repositoryId The ID of the repository being deleted
   * @param deletedComponentsAmount The number of components deleted
   */
  public RepositoryDeletedComponentEvent(final int repositoryId, final int deletedComponentsAmount) {
    super(repositoryId);
    this.deletedComponentsAmount = deletedComponentsAmount;
    this.repositoryName = null;
    this.repositoryFormat = null;
  }

  /**
   * Creates a new event for components deleted during repository deletion with additional context.
   *
   * @param repositoryId The ID of the repository being deleted
   * @param deletedComponentsAmount The number of components deleted
   * @param repositoryName The name of the repository being deleted
   * @param repositoryFormat The format of the repository being deleted
   */
  public RepositoryDeletedComponentEvent(final int repositoryId, 
                                        final int deletedComponentsAmount,
                                        final String repositoryName,
                                        final String repositoryFormat) {
    super(repositoryId);
    this.deletedComponentsAmount = deletedComponentsAmount;
    this.repositoryName = repositoryName;
    this.repositoryFormat = repositoryFormat;
  }

  /**
   * Gets the number of components deleted during repository deletion.
   *
   * @return the number of deleted components
   */
  public int getDeletedComponentsAmount() {
    return deletedComponentsAmount;
  }

  /**
   * Gets the name of the repository being deleted, if available.
   *
   * @return the repository name or null if not provided
   */
  public String getRepositoryName() {
    return repositoryName;
  }

  /**
   * Gets the format of the repository being deleted, if available.
   *
   * @return the repository format or null if not provided
   */
  public String getRepositoryFormat() {
    return repositoryFormat;
  }

  @Override
  public String toString() {
    Repository repository = getRepository().orElse(null);
    String repoName = repositoryName != null ? repositoryName : 
                     (repository != null ? repository.getName() : "unknown");
    String repoFormat = repositoryFormat != null ? repositoryFormat : 
                       (repository != null ? repository.getFormat().getValue() : "unknown");
    
    return "RepositoryDeletedComponentEvent{" +
           "repository=" + repository + ", " +
           "repositoryName=" + repoName + ", " +
           "repositoryFormat=" + repoFormat + ", " +
           "deletedComponentsAmount=" + deletedComponentsAmount +
           "}";  
  }
}