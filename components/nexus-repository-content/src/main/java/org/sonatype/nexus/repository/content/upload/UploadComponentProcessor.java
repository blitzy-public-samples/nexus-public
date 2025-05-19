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
package org.sonatype.nexus.repository.content.upload;

import java.util.Optional;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.store.InternalIds;
import org.sonatype.nexus.repository.upload.UploadProcessor;
import org.sonatype.nexus.repository.view.Content;

/**
 * Content implementation for {@link UploadProcessor}
 *
 * <p>This implementation leverages Java 21 pattern matching features for improved
 * type safety and code readability when processing uploaded content.</p>
 *
 * @since 3.29
 */
@Named
@Singleton
public class UploadComponentProcessor
    implements UploadProcessor
{
  /**
   * Extract {@link EntityId} of {@link Component} from {@link Content}
   * <p>
   * Uses Java 21 pattern matching to safely extract and process the Asset from Content attributes.
   *
   * @param content uploaded {@link Content} with {@link Asset} in attributes
   * @return {@link EntityId} of {@link Component}
   */
  @Override
  public Optional<EntityId> extractId(final Content content) {
    // Use pattern matching to check if the attribute is an Asset and extract it in one step
    if (content.getAttributes().get(Asset.class) instanceof Asset asset) {
      // Use pattern matching in the flatMap chain for improved type safety
      return asset.component()
          .map(component -> InternalIds.toExternalId(InternalIds.internalComponentId(component)));
    }
    return Optional.empty();
  }
}