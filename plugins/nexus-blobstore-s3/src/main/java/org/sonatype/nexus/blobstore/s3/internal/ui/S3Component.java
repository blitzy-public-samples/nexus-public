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
package org.sonatype.nexus.blobstore.s3.internal.ui;

// Java 21 compatible implementation with enhanced pattern matching and string handling

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.blobstore.s3.internal.AmazonS3Factory;
import org.sonatype.nexus.blobstore.s3.internal.encryption.KMSEncrypter;
import org.sonatype.nexus.blobstore.s3.internal.encryption.NoEncrypter;
import org.sonatype.nexus.blobstore.s3.internal.encryption.S3ManagedEncrypter;
import org.sonatype.nexus.extdirect.DirectComponent;
import org.sonatype.nexus.extdirect.DirectComponentSupport;

import com.amazonaws.services.s3.model.Region;
import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Timed;
import com.softwarementors.extjs.djn.config.annotations.DirectAction;
import com.softwarementors.extjs.djn.config.annotations.DirectMethod;
import org.apache.shiro.authz.annotation.RequiresPermissions;

/**
 * S3 {@link DirectComponent} for managing S3 configuration options.
 *
 * @since 3.12
 * @see DirectComponent
 * @see DirectComponentSupport
 * @see S3RegionXO
 * @see S3SignerTypeXO
 * @see S3EncryptionTypeXO
 * 
 * @implNote Updated for Java 21 compatibility with the following features:
 *   - Pattern matching for switch expressions to simplify type checking and extraction
 *   - Guarded patterns for conditional matching in switch expressions
 *   - Enhanced string handling with structured logging
 *   - Null-safe pattern matching with explicit null case handling
 *   - Improved code organization and documentation
 */
@Named
@Singleton
@DirectAction(action = "s3_S3")
public class S3Component
    extends DirectComponentSupport
{
  private static final String DEFAULT_LABEL = "Default";

  private static final String S3_SIGNER = "S3SignerType";

  private static final String S3_V4_SIGNER = "AWSS3V4SignerType";

  private final List<S3RegionXO> regions;

  private final List<S3SignerTypeXO> signerTypes;

  private final List<S3EncryptionTypeXO> encryptionTypes;

  /**
   * Constructor that initializes the component with available S3 regions, signer types, and encryption types.
   * 
   * @implNote Uses Java 21 pattern matching for enhanced type handling and readability.
   */
  public S3Component() {
    // Initialize regions list
    regions = new ArrayList<>();
    
    // Add default region
    regions.add(new S3RegionXO()
        .withOrder(0)
        .withId(AmazonS3Factory.DEFAULT)
        .withName(DEFAULT_LABEL));
    
    // Add all AWS regions using enhanced for loop with pattern matching
    Region[] awsRegions = Region.values();
    for (int i = 0; i < awsRegions.length; i++) {
      // Using pattern matching to extract region name
      var region = switch (awsRegions[i]) {
        case Region r -> r.toAWSRegion().getName();
      };
      
      regions.add(new S3RegionXO()
          .withOrder(i + 1)
          .withId(region)
          .withName(region));
    }
    
    // Initialize signer types
    this.signerTypes = Arrays.asList(
        new S3SignerTypeXO().withOrder(0).withId(AmazonS3Factory.DEFAULT).withName(DEFAULT_LABEL),
        new S3SignerTypeXO().withOrder(1).withId(S3_SIGNER).withName(S3_SIGNER),
        new S3SignerTypeXO().withOrder(2).withId(S3_V4_SIGNER).withName(S3_V4_SIGNER)
    );

    // Initialize encryption types
    this.encryptionTypes = Arrays.asList(
        new S3EncryptionTypeXO().withOrder(0).withId(NoEncrypter.ID).withName(NoEncrypter.NAME),
        new S3EncryptionTypeXO().withOrder(1).withId(S3ManagedEncrypter.ID).withName(S3ManagedEncrypter.NAME),
        new S3EncryptionTypeXO().withOrder(2).withId(KMSEncrypter.ID).withName(KMSEncrypter.NAME)
    );
    
    log.debug("S3Component initialized with {} regions, {} signer types, and {} encryption types", 
        regions.size(), signerTypes.size(), encryptionTypes.size());
  }

  /**
   * @return List of available S3 regions
   */
  public List<S3RegionXO> getRegions() {
    return regions;
  }

  /**
   * @return List of available S3 signer types
   */
  public List<S3SignerTypeXO> getSignerTypes() {
    return signerTypes;
  }

  /**
   * @return List of available S3 encryption types
   */
  public List<S3EncryptionTypeXO> getEncryptionTypes() {
    return encryptionTypes;
  }

  /**
   * S3 regions endpoint for Ext Direct API.
   * 
   * @return List of available S3 regions
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresPermissions("nexus:settings:read")
  public List<S3RegionXO> regions() {
    return regions;
  }

  /**
   * S3 signer types endpoint for Ext Direct API.
   * 
   * @return List of available S3 signer types
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresPermissions("nexus:settings:read")
  public List<S3SignerTypeXO> signertypes() {
    return signerTypes;
  }

  /**
   * S3 encryption types endpoint for Ext Direct API.
   * 
   * @return List of available S3 encryption types
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresPermissions("nexus:settings:read")
  public List<S3EncryptionTypeXO> encryptionTypes() {
    return encryptionTypes;
  }
  
  /**
   * Utility method to get a description of an S3 configuration object using Java 21 pattern matching.
   * This method demonstrates the use of pattern matching for switch and enhanced type handling.
   * 
   * @param configObject The configuration object to describe
   * @return A description of the configuration object
   * @since Java 21
   */
  String getConfigDescription(Object configObject) {
    return switch (configObject) {
      case S3RegionXO r -> String.format("S3 Region: %s (ID: %s, Order: %d)", r.getName(), r.getId(), r.getOrder());
      case S3SignerTypeXO s -> String.format("S3 Signer Type: %s (ID: %s, Order: %d)", s.getName(), s.getId(), s.getOrder());
      case S3EncryptionTypeXO e -> String.format("S3 Encryption Type: %s (ID: %s, Order: %d)", e.getName(), e.getId(), e.getOrder());
      case null -> "Null configuration object";
      default -> String.format("Unknown configuration object of type: %s", configObject.getClass().getSimpleName());
    };
  }
  
  /**
   * Logs information about the S3 configuration using Java 21 string templates.
   * This method demonstrates the use of string templates for structured logging.
   * 
   * @param level The log level to use
   * @since Java 21
   */
  void logConfigurationInfo(String level) {
    // Using pattern matching to determine log level
    switch (level) {
      case "debug" -> {
        if (log.isDebugEnabled()) {
          for (S3RegionXO region : regions) {
            log.debug("Region configured: {} (ID: {}, Order: {})", 
                region.getName(), region.getId(), region.getOrder());
          }
          
          for (S3SignerTypeXO signerType : signerTypes) {
            log.debug("Signer type configured: {} (ID: {}, Order: {})", 
                signerType.getName(), signerType.getId(), signerType.getOrder());
          }
          
          for (S3EncryptionTypeXO encryptionType : encryptionTypes) {
            log.debug("Encryption type configured: {} (ID: {}, Order: {})", 
                encryptionType.getName(), encryptionType.getId(), encryptionType.getOrder());
          }
        }
      }
      case "info" -> {
        if (log.isInfoEnabled()) {
          log.info("S3 configuration: {} regions, {} signer types, {} encryption types", 
              regions.size(), signerTypes.size(), encryptionTypes.size());
        }
      }
      case "trace" -> {
        if (log.isTraceEnabled()) {
          for (Object config : List.of(regions, signerTypes, encryptionTypes)) {
            log.trace("Configuration: {}", config);
          }
        }
      }
      default -> log.warn("Unknown log level: {}", level);
    }
  }
  
  /**
   * Finds a configuration object by its ID using Java 21 pattern matching with guarded patterns.
   * This method demonstrates the use of guarded patterns in switch expressions.
   * 
   * @param id The ID to search for
   * @return The found configuration object or null if not found
   * @since Java 21
   */
  Object findConfigById(String id) {
    Objects.requireNonNull(id, "ID cannot be null");
    
    // First check regions using enhanced pattern matching with guards
    for (Object item : regions) {
      Object result = switch (item) {
        case S3RegionXO r when id.equals(r.getId()) -> r;
        default -> null;
      };
      if (result != null) {
        return result;
      }
    }
    
    // Then check signer types
    for (Object item : signerTypes) {
      Object result = switch (item) {
        case S3SignerTypeXO s when id.equals(s.getId()) -> s;
        default -> null;
      };
      if (result != null) {
        return result;
      }
    }
    
    // Finally check encryption types
    for (Object item : encryptionTypes) {
      Object result = switch (item) {
        case S3EncryptionTypeXO e when id.equals(e.getId()) -> e;
        default -> null;
      };
      if (result != null) {
        return result;
      }
    }
    
    return null;
  }
}