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
package org.sonatype.nexus.coreui;

import java.util.SequencedMap;

/**
 * Functional interface for transforming or replacing one or more attributes of an {@link AssetXO}.
 * <p>
 * Since {@link AssetXO} is implemented as an immutable record in Java 21, transformers
 * must return a new instance rather than modifying the existing one.
 */
@FunctionalInterface
public interface AssetAttributeTransformer
{
  /**
   * Transforms the given {@link AssetXO} by creating a new instance with modified attributes.
   * <p>
   * Implementation note: Since {@link AssetXO} is immutable, implementations should create
   * a new instance with the desired changes rather than attempting to modify the original.
   *
   * @param assetXO the asset to be transformed
   * @return a new {@link AssetXO} instance with the transformed attributes
   */
  AssetXO transform(AssetXO assetXO);
  
  /**
   * Convenience method to create a transformer that only modifies the attributes map.
   * <p>
   * This factory method simplifies creating transformers that only need to modify the
   * attributes map without changing other fields of the {@link AssetXO}.
   *
   * @param attributesTransformer a function that transforms the attributes map
   * @return an {@link AssetAttributeTransformer} that applies the given transformation to the attributes map
   */
  static AssetAttributeTransformer ofAttributesOnly(java.util.function.Function<SequencedMap<String, Object>, SequencedMap<String, Object>> attributesTransformer) {
    return assetXO -> new AssetXO(
        assetXO.id(),
        assetXO.name(),
        assetXO.format(),
        assetXO.contentType(),
        assetXO.size(),
        assetXO.repositoryName(),
        assetXO.containingRepositoryName(),
        assetXO.blobCreated(),
        assetXO.blobUpdated(),
        assetXO.lastDownloaded(),
        assetXO.blobRef(),
        assetXO.componentId(),
        assetXO.createdBy(),
        assetXO.createdByIp(),
        attributesTransformer.apply(assetXO.attributes())
    );
  }
  
  /**
   * Returns a composed transformer that first applies this transformer and then
   * applies the {@code after} transformer.
   *
   * @param after the transformer to apply after this transformer is applied
   * @return a composed transformer that first applies this transformer and then
   *         applies the {@code after} transformer
   * @throws NullPointerException if after is null
   */
  default AssetAttributeTransformer andThen(AssetAttributeTransformer after) {
    java.util.Objects.requireNonNull(after);
    return assetXO -> after.transform(transform(assetXO));
  }
}