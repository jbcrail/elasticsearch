/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.core.security.action.apikey;

import org.elasticsearch.action.ActionType;
import org.elasticsearch.common.io.stream.Writeable;

public final class QueryApiKeyAction extends ActionType<QueryApiKeyResponse> {

    public static final String NAME = "cluster:admin/xpack/security/api_key/query";
    public static final QueryApiKeyAction INSTANCE = new QueryApiKeyAction();

    private QueryApiKeyAction() {
        super(NAME, Writeable.Reader.localOnly());
    }

}
