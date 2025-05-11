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
package org.sonatype.nexus.coreui.internal.blobstore;

import java.util.List;
import java.util.Map;

import org.sonatype.nexus.blobstore.BlobStoreDescriptor;
import org.sonatype.nexus.blobstore.SelectOption;
import org.sonatype.nexus.formfields.FormField;

/**
 * Data transfer object for blob store type information used in the UI.
 * Converted to a record for Java 21 compatibility.
 *
 * @since 3.next
 */
public record BlobStoreTypesUIResponse(
    String id,
    String name,
    List<FormField> fields,
    String customSettingsForm,
    Map<String, List<SelectOption>> dropDownValues
) {
  /**
   * Constructs a response from a blob store descriptor entry.
   *
   * @param entry the map entry containing the blob store descriptor
   */
  public BlobStoreTypesUIResponse(final Map.Entry<String, BlobStoreDescriptor> entry) {
    this(
        entry.getValue().getId(),
        entry.getValue().getName(),
        entry.getValue().getFormFields(),
        entry.getValue().customFormName(),
        entry.getValue().getDropDownValues()
    );
  }
}
