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
package org.sonatype.nexus.blobstore.group.internal;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

import org.sonatype.goodies.i18n.I18N;
import org.sonatype.goodies.i18n.MessageBundle;
import org.sonatype.nexus.blobstore.BlobStoreDescriptor;
import org.sonatype.nexus.blobstore.BlobStoreDescriptorSupport;
import org.sonatype.nexus.blobstore.BlobStoreUtil;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.group.BlobStoreGroup;
import org.sonatype.nexus.blobstore.group.BlobStoreGroupService;
import org.sonatype.nexus.blobstore.group.FillPolicy;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaService;
import org.sonatype.nexus.common.upgrade.AvailabilityVersion;
import org.sonatype.nexus.formfields.ComboboxFormField;
import org.sonatype.nexus.formfields.FormField;
import org.sonatype.nexus.formfields.ItemselectFormField;
import org.sonatype.nexus.rest.ValidationErrorsException;

import org.apache.commons.lang.StringUtils;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Streams.stream;
import static java.lang.StringTemplate.STR;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.sonatype.nexus.blobstore.group.BlobStoreGroup.CONFIG_KEY;
import static org.sonatype.nexus.blobstore.group.BlobStoreGroup.FILL_POLICY_KEY;
import static org.sonatype.nexus.blobstore.group.BlobStoreGroup.MEMBERS_KEY;
import static org.sonatype.nexus.blobstore.group.BlobStoreGroupConfigurationHelper.memberNames;
import static org.sonatype.nexus.formfields.FormField.MANDATORY;

/**
 * A {@link BlobStoreDescriptor} for {@link BlobStoreGroup}.
 *
 * @since 3.14
 */
@AvailabilityVersion(from = "1.0")
@Named(BlobStoreGroup.TYPE)
public class BlobStoreGroupDescriptor
    extends BlobStoreDescriptorSupport
{
  private interface Messages
      extends MessageBundle
  {
    @DefaultMessage("Group")
    String name();

    @DefaultMessage("Members")
    String membersLabel();

    @DefaultMessage("Fill Policy")
    String fillPolicyLabel();
  }

  private static final Messages messages = I18N.create(Messages.class);

  private final BlobStoreManager blobStoreManager;

  private final BlobStoreUtil blobStoreUtil;

  private final Provider<BlobStoreGroupService> blobStoreGroupService;

  private final ItemselectFormField members;

  private final FormField fillPolicy;

  private final Map<String, FillPolicy> fillPolicies;

  @Inject
  public BlobStoreGroupDescriptor(
      final BlobStoreManager blobStoreManager,
      final BlobStoreUtil blobStoreUtil,
      final Provider<BlobStoreGroupService> blobStoreGroupService,
      final BlobStoreQuotaService quotaService,
      final Map<String, FillPolicy> fillPolicies)
  {
    super(quotaService);
    this.blobStoreManager = checkNotNull(blobStoreManager);
    this.blobStoreUtil = checkNotNull(blobStoreUtil);
    this.blobStoreGroupService = checkNotNull(blobStoreGroupService);
    this.fillPolicies = checkNotNull(fillPolicies);
    this.members = new ItemselectFormField(
        MEMBERS_KEY,
        messages.membersLabel(),
        null,
        MANDATORY);
    this.members.setStoreApi("coreui_Blobstore.readGroupable");
    this.members.setIdMapping("name");
    this.members.setButtons("up", "add", "remove", "down");
    this.members.setFromTitle("Available Blob Stores");
    this.members.setToTitle("Selected Blob Stores");
    this.fillPolicy = new ComboboxFormField<String>(
        BlobStoreGroup.FILL_POLICY_KEY,
        messages.fillPolicyLabel(),
        null,
        FormField.MANDATORY).withStoreApi("coreui_Blobstore.fillPolicies");
    this.fillPolicy.getAttributes()
        .put("options", fillPolicies.entrySet()
            .stream()
            .collect(
                toMap(Map.Entry::getKey, e -> e.getValue().getName())));
  }

  @Override
  public String getId() {
    return "group";
  }

  @Override
  public String getName() {
    return messages.name();
  }

  @Override
  public List<FormField> getFormFields() {
    List<String> blobStores = stream(blobStoreManager.browse())
        .map(BlobStore::getBlobStoreConfiguration)
        .map(BlobStoreConfiguration::getName)
        .collect(toList());
    this.members.getAttributes().put("options", blobStores);

    return asList(members, fillPolicy);
  }

  @Override
  public boolean isModifiable() {
    return isEnabled();
  }

  @Override
  public void validateConfig(final BlobStoreConfiguration config) {
    super.validateConfig(config);
    validateEnabled();
    String name = config.getName();

    // Use pattern matching to check fill policy
    if (config.attributes(CONFIG_KEY).get(FILL_POLICY_KEY, String.class) instanceof String fillPolicy) {
      if (StringUtils.isBlank(fillPolicy)) {
        throw new ValidationErrorsException(STR."Blob store group requires a fill policy configuration");
      }
      if (!fillPolicies.containsKey(fillPolicy)) {
        throw new ValidationErrorsException(
            STR."Blob store group requires a valid fill policy name, options include [\{String.join(", ", fillPolicies.keySet())}]");
      }
    } else {
      throw new ValidationErrorsException(STR."Blob store group requires a fill policy configuration");
    }

    // Use pattern matching to check member names
    if (config.attributes(CONFIG_KEY).get(MEMBERS_KEY, List.class) instanceof List<?> memberNamesList) {
      @SuppressWarnings("unchecked")
      List<String> memberNames = (List<String>) memberNamesList;
      validateNotEmptyOrSelfReferencing(name, memberNames);
      validateEligibleMembers(name, memberNames);
      validateOnlyEmptyOrNotWritableExistingMembersRemoved(name, memberNames);
    } else {
      throw new ValidationErrorsException(STR."Blob Store '\{name}' requires a valid members list");
    }
  }
  
  private void validateEnabled() {
    if (!isEnabled()) {
      throw new ValidationErrorsException(STR."Blob store groups are not enabled");
    }
  }

  private void validateNotEmptyOrSelfReferencing(final String name, final List<String> memberNames) {
    if (memberNames == null || memberNames.isEmpty()) {
      throw new ValidationErrorsException(STR."Blob Store '\{name}' cannot be empty");
    }

    if (memberNames.contains(name)) {
      throw new ValidationErrorsException(STR."Blob Store '\{name}' cannot contain itself");
    }
  }
  
  private void validateEligibleMembers(final String name, final List<String> memberNames) {
    for (String memberName : memberNames) {
      // Use pattern matching for member and its configuration
      if (blobStoreManager.get(memberName) instanceof BlobStore member) {
        if (!member.isGroupable() && member.getBlobStoreConfiguration() instanceof BlobStoreConfiguration memberConfig) {
          throw new ValidationErrorsException(
              STR."Blob Store '\{memberName}' is of type '\{memberConfig.getType()}' and is not eligible to be a group member");
        }

        if (blobStoreManager.hasConflictingTasks(memberName)) {
          throw new ValidationErrorsException(
              STR."Blob Store '\{memberName}' has conflicting tasks running and is not eligible to be a group member");
        }

        // target member may not be a member of a different group
        Predicate<String> sameGroup = name::equals;
        blobStoreManager.getParent(memberName).filter(sameGroup.negate()).ifPresent(groupName -> {
          throw new ValidationErrorsException(
              STR."Blob Store '\{memberName}' is already a member of Blob Store Group '\{groupName}'");
        });

        // target member may not be set as repository storage
        int repoCount = blobStoreUtil.usageCount(memberName);
        if (repoCount > 0) {
          throw new ValidationErrorsException(
              STR."Blob Store '\{memberName}' is set as storage for \{repoCount} repositories and is not eligible to be a group member");
        }
      } else {
        throw new ValidationErrorsException(STR."Blob Store '\{memberName}' not found");
      }
    }
  }
  
  private void validateOnlyEmptyOrNotWritableExistingMembersRemoved(final String name, final List<String> memberNames) {
    // Use pattern matching for blobStore and currentConfiguration
    if (blobStoreManager.get(name) instanceof BlobStore blobStore) {
      if (blobStore.getBlobStoreConfiguration() instanceof BlobStoreConfiguration currentConfiguration 
          && BlobStoreGroup.TYPE.equals(currentConfiguration.getType())) {
        for (String existingMemberName : memberNames(currentConfiguration)) {
          if (!memberNames.contains(existingMemberName)) {
            // Use pattern matching for existingMember
            if (blobStoreManager.get(existingMemberName) instanceof BlobStore existingMember) {
              if (existingMember.isWritable() || !existingMember.isEmpty()) {
                throw new ValidationErrorsException(
                    STR."Blob Store '\{existingMemberName}' cannot be removed from Blob Store Group '\{name}', " +
                    "use 'Admin - Remove a member from a blob store group' task instead");
              }
            }
          }
        }
      }
    }
  }
  
  @Override
  public boolean isEnabled() {
    return Optional.of(blobStoreGroupService)
        .map(Provider::get)
        .map(BlobStoreGroupService::isEnabled)
        .orElse(false);
  }

  @Override
  public boolean configHasDependencyOn(final BlobStoreConfiguration config, final String blobStoreName) {
    return memberNames(config).contains(blobStoreName);
  }