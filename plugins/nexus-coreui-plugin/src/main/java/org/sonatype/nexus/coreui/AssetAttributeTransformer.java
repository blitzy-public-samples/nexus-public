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

import java.util.Map;
import java.util.function.Predicate;

/**
 * Functional interface for transforming or replacing one or more attributes of an {@link AssetXO}.
 *
 * @since 3.0
 * @since 3.x Updated to leverage Java 21 functional interface capabilities
 */
@FunctionalInterface
public interface AssetAttributeTransformer
{
  /**
   * Transforms the given {@link AssetXO} by modifying or replacing its attributes.
   *
   * @param assetXO the asset to be transformed
   */
  void transform(AssetXO assetXO);
  
  /**
   * Creates a conditional transformer that only applies if the predicate matches.
   *
   * @param predicate the condition to check before transformation
   * @return a new transformer that only applies when the condition is met
   */
  default AssetAttributeTransformer onlyIf(Predicate<AssetXO> predicate) {
    return assetXO -> {
      if (predicate.test(assetXO)) {
        transform(assetXO);
      }
    };
  }
  
  /**
   * Creates a transformer that only applies to assets of a specific format.
   *
   * @param format the format to match
   * @return a new transformer that only applies to the specified format
   */
  default AssetAttributeTransformer forFormat(String format) {
    return onlyIf(assetXO -> format.equals(assetXO.format()));
  }
  
  /**
   * Creates a transformer that only applies if a specific attribute exists.
   *
   * @param format the format namespace in attributes
   * @param attributeName the attribute name to check for existence
   * @return a new transformer that only applies when the attribute exists
   */
  default AssetAttributeTransformer whenAttributeExists(String format, String attributeName) {
    return onlyIf(assetXO -> {
      Map<String, Object> attributes = assetXO.attributes();
      if (attributes.containsKey(format)) {
        @SuppressWarnings("unchecked")
        Map<String, Object> formatAttributes = (Map<String, Object>) attributes.get(format);
        return formatAttributes.containsKey(attributeName);
      }
      return false;
    });
  }
  
  /**
   * Combines this transformer with another, applying this transformer first,
   * then the other.
   *
   * @param after the transformer to apply after this one
   * @return a combined transformer
   */
  default AssetAttributeTransformer andThen(AssetAttributeTransformer after) {
    return assetXO -> {
      transform(assetXO);
      after.transform(assetXO);
    };
  }
}
