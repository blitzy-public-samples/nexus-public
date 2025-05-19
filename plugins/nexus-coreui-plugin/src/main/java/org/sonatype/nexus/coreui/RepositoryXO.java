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
import java.util.LinkedHashMap;
import java.util.SequencedMap;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

import org.sonatype.nexus.repository.config.UniqueRepositoryName;
import org.sonatype.nexus.validation.constraint.NamePatternConstants;
import org.sonatype.nexus.validation.group.Create;

/**
 * Repository exchange object.
 *
 * @since 3.0
 */
public record RepositoryXO(
    @Pattern(regexp = NamePatternConstants.REGEX, message = NamePatternConstants.MESSAGE)
    @NotEmpty
    @UniqueRepositoryName(groups = Create.class)
    String name,
    
    String type,
    
    String format,
    
    Long size,
    
    @NotBlank(groups = Create.class)
    String recipe,
    
    @NotNull
    Boolean online,
    
    String routingRuleId,
    
    @NotEmpty
    SequencedMap<String, Map<String, Object>> attributes,
    
    String url,
    
    RepositoryStatusXO status
) {
    /**
     * Compact constructor with conversion from regular Map to SequencedMap for backward compatibility.
     * This ensures existing code that passes a regular Map will still work with the new SequencedMap interface.
     */
    public RepositoryXO {
        if (!(attributes instanceof SequencedMap)) {
            attributes = new LinkedHashMap<>(attributes);
        }
    }
}