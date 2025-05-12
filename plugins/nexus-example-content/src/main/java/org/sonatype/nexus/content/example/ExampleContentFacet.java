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
package org.sonatype.nexus.content.example;

import java.io.IOException;
import java.util.Optional;

import org.sonatype.nexus.repository.Facet;
import org.sonatype.nexus.repository.content.facet.ContentFacet;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Payload;

/**
 * Provides persistent content for an 'example' format.
 *
 * @since 3.24
 */
@Facet.Exposed
public interface ExampleContentFacet
    extends ContentFacet
{
  /**
   * Retrieves content at the specified path.
   *
   * @param path the path to retrieve content from
   * @return the content if found, otherwise empty
   * @throws IOException if there is a problem retrieving the content
   */
  Optional<Content> get(String path) throws IOException;

  /**
   * Stores content at the specified path.
   *
   * @param path the path to store content at
   * @param content the content to store
   * @return the stored content
   * @throws IOException if there is a problem storing the content
   */
  Content put(String path, Payload content) throws IOException;

  /**
   * Deletes content at the specified path.
   *
   * @param path the path to delete content from
   * @return true if content was deleted
   * @throws IOException if there is a problem deleting the content
   */
  boolean delete(String path) throws IOException;
}