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
package org.sonatype.nexus.repository.maven.internal.validation;

import java.io.IOException;
import java.io.InputStream;

import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.repository.InvalidContentException;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.internal.MavenModels;

import org.apache.maven.artifact.repository.metadata.Metadata;

import static org.apache.commons.lang3.StringUtils.isNotEmpty;
import static org.sonatype.nexus.repository.maven.internal.hosted.metadata.MetadataUtils.metadataPath;

/**
 * Validates that maven-metadata.xml is parsable and has the correct path.
 * 
 * @since 3.16
 */
@Singleton
public class MavenMetadataContentValidator
    extends ComponentSupport
{
  public void validate(final String path, final InputStream mavenMetadata) {
    try {
      Metadata metadata = MavenModels.readMetadata(mavenMetadata);

      if (metadata == null) {
        throw new InvalidContentException(STR."Metadata at path \{path} is not a valid maven-metadata.xml");
      }

      // maven-metadata.xml files for plugins do not contain groupId and therefore cannot be validated
      if (isNotEmpty(metadata.getGroupId())) {
        validatePath(path, metadata);
      }
      else {
        log.debug("No groupId found in maven-metadata.xml therefore skipping validation");
      }
    }
    catch (IOException e) {
      log.warn(STR."Unable to read maven-metadata.xml at path \{path}", e);
      
      throw new InvalidContentException(STR."Unable to read maven-metadata.xml reason: \{e.getMessage()}");
    }
  }

  private void validatePath(final String path, final Metadata metadata) {
    String version = getMetadataVersion(metadata);

    MavenPath expectedPath = metadataPath(metadata.getGroupId(), metadata.getArtifactId(), version);

    if (!path.equals(expectedPath.getPath())) {
      log.warn(STR."maven-metadata.xml path \{path} does not match the expected path \{expectedPath.getPath()}");

      throw new InvalidContentException(
          STR."Invalid maven-metadata.xml GAV \{metadata.getGroupId()}, \{metadata.getArtifactId()}, \{metadata.getVersion()} does not match request path \{path}");
    }
  }

  private String getMetadataVersion(final Metadata metadata) {
    return switch (metadata.getVersion()) {
      case String v when v != null && v.contains("-SNAPSHOT") -> {
        log.debug(STR."maven-metadata.xml contains a SNAPSHOT version (\{v}) therefore the version is expected to be part of the path");
        yield v;
      }
      default -> {
        log.debug(STR."maven-metadata.xml version (\{metadata.getVersion()}) is either null or not a SNAPSHOT therefore not expected in the path");
        yield null;
      }
    };
  }
}