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
package org.sonatype.nexus.repository.rest;

import javax.inject.Named;

import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.rest.api.AbstractRepositoryApiRequestToConfigurationConverter;
import org.sonatype.nexus.repository.rest.api.model.GroupAttributes;
import org.sonatype.nexus.repository.rest.api.model.GroupDeployAttributes;
import org.sonatype.nexus.repository.rest.api.model.GroupRepositoryApiRequest;
import org.sonatype.nexus.repository.rest.api.model.StorageAttributes;

import static org.sonatype.nexus.repository.config.ConfigurationConstants.BLOB_STORE_NAME;
import static org.sonatype.nexus.repository.config.ConfigurationConstants.GROUP_WRITE_MEMBER;
import static org.sonatype.nexus.repository.config.ConfigurationConstants.STORAGE;
import static org.sonatype.nexus.repository.config.ConfigurationConstants.STRICT_CONTENT_TYPE_VALIDATION;

/**
 * Converter for group repository API requests to configuration objects.
 * 
 * @since 3.20
 */
@Named
public class GroupRepositoryApiRequestToConfigurationConverter<T extends GroupRepositoryApiRequest>
    extends AbstractRepositoryApiRequestToConfigurationConverter<T>
{
  public Configuration convert(final T request) {
    Configuration configuration = super.convert(request);

    // Use pattern matching to extract storage attributes
    StorageAttributes storage = request.getStorage();
    configuration.attributes(STORAGE).set(BLOB_STORE_NAME, storage.getBlobStoreName());
    configuration.attributes(STORAGE).set(STRICT_CONTENT_TYPE_VALIDATION, storage.getStrictContentTypeValidation());
    maybeAddDataStoreName(configuration);

    // Configure group attributes
    GroupAttributes group = request.getGroup();
    configuration.attributes("group").set("memberNames", group.getMemberNames());

    // Use pattern matching for instanceof to simplify type checking and casting
    if (group instanceof GroupDeployAttributes groupDeployAttributes) {
      String writableMember = groupDeployAttributes.getWritableMember();
      if (writableMember != null && !writableMember.isEmpty()) {
        configuration.attributes("group").set(GROUP_WRITE_MEMBER, writableMember);
        
        // Using String Template for logging or validation messages if needed
        // log.debug(STR."Configured writable member: {writableMember} for group repository");
      }
    }
    
    return configuration;
  }
}