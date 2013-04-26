/*
 * Copyright 2013 NGDATA nv
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lilyproject.tenant.model.api;

import com.google.common.base.Preconditions;

/**
 * Definition of a tenant in the context of multi-tenancy.
 *
 * <p>Lily supports multi-tenancy, whereby each tenant has its own Repository containing its own tables.</p>
 *
 * <p>Tenants are managed through {@link TenantModel}.</p>
 */
public class Tenant {
    private String name;
    private TenantLifecycleState lifecycleState;

    public Tenant(String name, TenantLifecycleState lifecycleState) {
        Preconditions.checkNotNull(name, "name");
        Preconditions.checkNotNull(lifecycleState, "lifecycleState");

        this.name = name;
        this.lifecycleState = lifecycleState;
    }

    public String getName() {
        return name;
    }

    public TenantLifecycleState getLifecycleState() {
        return lifecycleState;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Tenant tenant = (Tenant)o;

        if (lifecycleState != tenant.lifecycleState) return false;
        if (!name.equals(tenant.name)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + lifecycleState.hashCode();
        return result;
    }

    public static enum TenantLifecycleState {
        ACTIVE, CREATE_REQUESTED, DELETE_REQUESTED
    }
}
